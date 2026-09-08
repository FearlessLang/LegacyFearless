package codegen.optimisations;

import codegen.MIR;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// Which functions sit on a recursion cycle, as a compile-time stand-in for a profile.
///
/// The functions on a cycle run once per step of the computation the cycle drives, so a cycle
/// names the hot code without a profile. The graph holds one node per {@link MIR.Fun} and one
/// edge per call whose target is known when the code is generated: a `DirectCall`, each tested
/// arm of a `GuardedCall`, a `StaticCall`, and the two arms of a `BoolExpr`. A virtual call
/// adds no edge, so the graph understates the real one, which is sound for the use below.
///
/// `hot(f)` holds when `f` is on a cycle, or when a hot function calls `f` and `f` is small
/// enough to fold in -- a cycle calls a chain of small helpers, and a profiled build folds the
/// whole chain, not only its first link. The budget keeps that growth bounded.
///
/// `inlineWanted(f)` holds the functions some hint targets, so codegen emits an inline-only
/// wrapper for `f` only where a call site can name it.
///
/// `inlineTarget(caller, callee)` holds when a call from `caller` to `callee` should carry an
/// inline hint: `caller` is hot and `callee` sits in another component. That component test is
/// what makes a chain of hints terminate: no hint leaves a component for itself.
public final class RecursionHotness {
  private final Map<MIR.FName, MIR.Fun> funMap;
  private final ReturnShapeAnalysis shapes;
  private final Map<MIR.FName, Integer> nodes = new HashMap<>();
  private final List<MIR.FName> names = new ArrayList<>();
  private final List<List<Integer>> edges = new ArrayList<>();
  /// The largest callee body, in MIR nodes, that hotness reaches past a cycle into.
  private static final int INLINE_BUDGET = 150;

  private final int[] component;
  private final boolean[] onCycle;
  private final boolean[] hotBody;
  private final Set<MIR.FName> inlineWanted = new HashSet<>();
  private final Set<MIR.FName> armsSeen = new HashSet<>();
  private final Map<MIR.FName, Integer> sizes = new HashMap<>();

  public RecursionHotness(MIR.Program p, ReturnShapeAnalysis shapes) {
    this.shapes = shapes;
    this.funMap = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f, (a, b) -> a));
    for (var name : funMap.keySet()) { node(name); }
    var selfEdge = new boolean[nodes.size()];
    for (var fun : funMap.values()) {
      var from = node(fun.name());
      var targets = new LinkedHashSet<MIR.FName>();
      armsSeen.clear();
      collect(fun.body(), targets);
      for (var target : targets) {
        if (!funMap.containsKey(target)) { continue; }
        var to = node(target);
        edges.get(from).add(to);
        if (to == from) { selfEdge[from] = true; }
      }
    }
    this.component = components();
    var size = new int[nodes.size()];
    for (var c : component) { size[c]++; }
    this.onCycle = new boolean[nodes.size()];
    for (int i = 0; i < onCycle.length; i++) {
      onCycle[i] = selfEdge[i] || size[component[i]] > 1;
    }
    this.hotBody = spread();
    for (var from = 0; from < hotBody.length; from++) {
      if (!hotBody[from]) { continue; }
      for (var to : edges.get(from)) {
        if (component[from] != component[to]) { inlineWanted.add(names.get(to)); }
      }
    }
  }

  /// Hotness carried out of the cycles it starts in, one edge at a time, as far as the
  /// budget allows. A body folded into a hot caller is itself running hot, so what it calls
  /// is a hot call site too.
  private boolean[] spread() {
    var hot = onCycle.clone();
    Deque<Integer> work = new ArrayDeque<>();
    for (var i = 0; i < hot.length; i++) {
      if (hot[i]) { work.push(i); }
    }
    while (!work.isEmpty()) {
      for (var to : edges.get(work.pop())) {
        if (hot[to] || bodySize(names.get(to)) > INLINE_BUDGET) { continue; }
        hot[to] = true;
        work.push(to);
      }
    }
    return hot;
  }

  /// The node count of a body, counting a `BoolExpr` arm as part of it, which is how codegen
  /// writes it. A literal counts as one node: its methods are funs of their own.
  private int bodySize(MIR.FName name) {
    var cached = sizes.get(name);
    if (cached != null) { return cached; }
    // A cycle among arms would not terminate, and the entry is replaced below.
    sizes.put(name, INLINE_BUDGET + 1);
    var fun = funMap.get(name);
    var res = fun == null ? INLINE_BUDGET + 1 : sizeOf(fun.body());
    sizes.put(name, res);
    return res;
  }

  private int sizeOf(MIR.E e) {
    return 1 + switch (e) {
      case MIR.X ignored -> 0;
      case MIR.CreateObj ignored -> 0;
      case MIR.Box box -> sizeOf(box.inner());
      case MIR.Block block -> sizeOf(block.original())
        + block.stmts().stream().mapToInt(stmt -> sizeOf(stmt.e())).sum();
      case MIR.BoolExpr b -> sizeOf(b.condition()) + bodySize(b.then()) + bodySize(b.else_());
      // Codegen calls an arm rather than writing its body out, unlike a `BoolExpr`, so an arm
      // costs one node here however large the function it names is.
      case MIR.SumMatch m -> sizeOf(m.receiver()) + m.arms().size();
      case MIR.MCall call -> sizeOf(call.recv())
        + call.args().stream().mapToInt(this::sizeOf).sum();
      case MIR.UpdatableListAsIdFnCall u -> sizeOf(u.e());
      case MIR.GuardedCall g -> sizeOf(g.original());
      case MIR.DirectCall d -> sizeOf(d.original());
      case MIR.StaticCall s -> s.args().stream().mapToInt(this::sizeOf).sum();
    };
  }

  /// Whether some call site hints at `f`, so `f` needs its inline-only wrapper.
  public boolean inlineWanted(MIR.FName f) { return inlineWanted.contains(f); }

  /// Whether `f` is small enough to be worth copying into a call site. A cached package asks
  /// this of every method it writes, because the compilations that read it back cannot see its
  /// bodies and so cannot judge the size for themselves.
  public boolean fitsInlineBudget(MIR.FName f) { return bodySize(f) <= INLINE_BUDGET; }

  /// Whether `f` is on a recursion cycle.
  public boolean hot(MIR.FName f) {
    var i = nodes.get(f);
    return i != null && hotBody[i];
  }

  /// Whether a call from `caller` to `callee` carries an inline hint.
  public boolean inlineTarget(MIR.FName caller, MIR.FName callee) {
    if (caller == null || callee == null) { return false; }
    var from = nodes.get(caller);
    var to = nodes.get(callee);
    if (from == null || to == null) { return false; }
    return hotBody[from] && component[from] != component[to];
  }

  private int node(MIR.FName name) {
    var existing = nodes.get(name);
    if (existing != null) { return existing; }
    var i = nodes.size();
    nodes.put(name, i);
    names.add(name);
    edges.add(new ArrayList<>());
    return i;
  }

  private void collect(MIR.E e, Set<MIR.FName> out) {
    switch (e) {
      case MIR.X ignored -> {}
      case MIR.Box box -> collect(box.inner(), out);
      // Both views of a block: `original` is the expression it came from and `stmts` the
      // statements codegen emits, and a call can sit in either one alone.
      case MIR.Block block -> {
        collect(block.original(), out);
        block.stmts().forEach(stmt -> collect(stmt.e(), out));
      }
      // The methods of a literal are funs of their own, so they are reached as nodes, not
      // as calls from here.
      case MIR.CreateObj ignored -> {}
      case MIR.BoolExpr b -> {
        collect(b.condition(), out);
        collectArm(b.then(), out);
        collectArm(b.else_(), out);
      }
      // Only the receiver. Codegen calls an arm and never writes its body into the enclosing
      // function, so an arm neither grows that function nor lends it the arm's own calls. The
      // call it emits names the arm's plain wrapper, which every arm has, so no edge is needed
      // to make one exist. This is the treatment the virtual call these arms replace was given.
      case MIR.SumMatch m -> collect(m.receiver(), out);
      case MIR.MCall call -> {
        collect(call.recv(), out);
        call.args().forEach(a -> collect(a, out));
      }
      case MIR.UpdatableListAsIdFnCall u -> collect(u.e(), out);
      case MIR.GuardedCall g -> {
        // Both tested arms, because codegen may name the inline wrapper of either one.
        shapes.calleeOf(g).ifPresent(f -> out.add(f.name()));
        shapes.calleeOfAlt(g).ifPresent(f -> out.add(f.name()));
        collect(g.original().recv(), out);
        g.original().args().forEach(a -> collect(a, out));
      }
      case MIR.DirectCall d -> {
        shapes.calleeOf(d).ifPresent(f -> out.add(f.name()));
        collect(d.original().recv(), out);
        d.original().args().forEach(a -> collect(a, out));
      }
      case MIR.StaticCall s -> {
        out.add(s.fun());
        s.args().forEach(a -> collect(a, out));
      }
    }
  }

  /// An arm of a `BoolExpr` is a function of its own, and codegen either calls it or writes
  /// its body into the enclosing one. Both forms are recorded: the edge covers the call, and
  /// the walk into the arm body covers the inlined form, where the arm's own calls are
  /// emitted in the enclosing function and so take the hotness of that function.
  private void collectArm(MIR.FName arm, Set<MIR.FName> out) {
    out.add(arm);
    if (!armsSeen.add(arm)) { return; }
    var fun = funMap.get(arm);
    if (fun != null) { collect(fun.body(), out); }
  }

  /// Tarjan's algorithm, driven by an explicit stack because a call graph is as deep as the
  /// program is nested and a recursive walk can exhaust the Java stack.
  private int[] components() {
    var n = nodes.size();
    var comp = new int[n];
    var low = new int[n];
    var num = new int[n];
    var onStack = new boolean[n];
    Arrays.fill(comp, -1);
    Arrays.fill(num, -1);
    Deque<Integer> pending = new ArrayDeque<>();
    var frameNode = new int[n + 1];
    var frameEdge = new int[n + 1];
    var visited = 0;
    var found = 0;
    for (var root = 0; root < n; root++) {
      if (num[root] != -1) { continue; }
      num[root] = low[root] = visited++;
      pending.push(root);
      onStack[root] = true;
      var top = 0;
      frameNode[0] = root;
      frameEdge[0] = 0;
      while (top >= 0) {
        var v = frameNode[top];
        var vEdges = edges.get(v);
        if (frameEdge[top] < vEdges.size()) {
          var w = vEdges.get(frameEdge[top]++);
          if (num[w] == -1) {
            num[w] = low[w] = visited++;
            pending.push(w);
            onStack[w] = true;
            top++;
            frameNode[top] = w;
            frameEdge[top] = 0;
          } else if (onStack[w]) {
            low[v] = Math.min(low[v], num[w]);
          }
          continue;
        }
        if (low[v] == num[v]) {
          var id = found++;
          while (true) {
            var w = pending.pop();
            onStack[w] = false;
            comp[w] = id;
            if (w == v) { break; }
          }
        }
        top--;
        if (top >= 0) { low[frameNode[top]] = Math.min(low[frameNode[top]], low[v]); }
      }
    }
    return comp;
  }
}
