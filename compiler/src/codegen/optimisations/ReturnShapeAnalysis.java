package codegen.optimisations;

import codegen.MIR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/// Per-function summaries that let a caller give a callee the storage for its result.
///
/// `freshObj(f)` is the one `CreateObj` whose fresh instance `f` returns: the body of `f`,
/// seen through `Box` and `Block`, is that transient-eligible `CreateObj`, or a tail
/// `DirectCall`/`StaticCall` to a function with the same summary.
///
/// `paramMayEscape(f, i)` is false only when param `i` provably stays inside the frame of
/// `f`: not a capture of a `CreateObj`, not the returned value, not an operand of a virtual
/// call, and flowing only into non-escaping positions of known callees. The fixpoint is
/// optimistic: every param starts at "does not escape" and escapes propagate until stable.
///
/// `slotWanted(f)` holds the functions some `DirectCall`/`StaticCall` site targets, so
/// codegen emits a `_transient` variant only where a site can reference it.
public final class ReturnShapeAnalysis {
  private final MIR.Program p;
  private final Map<MIR.FName, MIR.Fun> funMap;
  private final Predicate<MIR.E> isTransientCreateObj;
  private final Map<MIR.FName, Optional<MIR.CreateObj>> freshObjs = new HashMap<>();
  private final Map<MIR.FName, boolean[]> paramEscapes = new HashMap<>();
  private final Set<MIR.FName> wantedFuns = new HashSet<>();

  public ReturnShapeAnalysis(MIR.Program p, Predicate<MIR.E> isTransientCreateObj) {
    this.p = p;
    this.isTransientCreateObj = isTransientCreateObj;
    this.funMap = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f));
    computeFreshObjs();
    computeParamEscapes();
    collectWantedFuns();
  }

  public Optional<MIR.CreateObj> freshObj(MIR.FName f) {
    return freshObjs.getOrDefault(f, Optional.empty());
  }

  public boolean slotWanted(MIR.FName f) { return wantedFuns.contains(f); }

  public boolean paramMayEscape(MIR.FName f, int argIndex) {
    var flags = paramEscapes.get(f);
    return flags == null || argIndex >= flags.length || flags[argIndex];
  }

  /// The function behind the per-literal wrapper a `DirectCall` targets, resolved the way
  /// codegen binds it: the concrete literal first, then up the inheritance chain. A miss
  /// returns empty, which every caller reads as "assume the worst".
  public Optional<MIR.Fun> calleeOf(MIR.DirectCall d) {
    var key = new MIR.FName(d.concreteType(), d.original().name(), false, d.original().mdf());
    var cached = calleeCache.get(key);
    if (cached != null) { return cached; }
    var res = resolveCallee(d);
    calleeCache.put(key, res);
    return res;
  }

  private final Map<MIR.FName, Optional<MIR.Fun>> calleeCache = new HashMap<>();

  private Optional<MIR.Fun> resolveCallee(MIR.DirectCall d) {
    var direct = funOn(d.concreteType(), d);
    if (direct.isPresent()) { return direct; }
    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(d.concreteType()))
      .map(pkg -> pkg.defs().get(d.concreteType()))
      .findFirst()
      .orElse(null);
    if (typeDef == null) { return Optional.empty(); }
    try {
      return codegen.ParentWalker.of(p, typeDef).skip(1)
        .map(parent -> funOn(parent.name(), d))
        .filter(Optional::isPresent)
        .findFirst()
        .orElse(Optional.empty());
    } catch (RuntimeException ignored) {
      // A parent outside the MIR defs, so a magic type, has no fun to find.
      return Optional.empty();
    }
  }

  private Optional<MIR.Fun> funOn(id.Id.DecId owner, MIR.DirectCall d) {
    for (var capturesSelf : new boolean[]{ false, true }) {
      var fName = new MIR.FName(owner, d.original().name(), capturesSelf, d.original().mdf());
      var fun = funMap.get(fName);
      if (fun != null) { return Optional.of(fun); }
    }
    return Optional.empty();
  }

  public static MIR.E unwrap(MIR.E e) {
    while (true) {
      switch (e) {
        case MIR.Box box -> e = box.inner();
        case MIR.Block block -> e = block.original();
        default -> { return e; }
      }
    }
  }

  private void computeFreshObjs() {
    for (var f : funMap.keySet()) { freshObjs.put(f, Optional.empty()); }
    var changed = true;
    while (changed) {
      changed = false;
      for (var fun : funMap.values()) {
        if (freshObjs.get(fun.name()).isPresent()) { continue; }
        var shape = shapeOf(unwrap(fun.body()));
        if (shape.isPresent()) {
          freshObjs.put(fun.name(), shape);
          changed = true;
        }
      }
    }
  }

  private Optional<MIR.CreateObj> shapeOf(MIR.E e) {
    return switch (e) {
      case MIR.CreateObj k when isTransientCreateObj.test(k) -> Optional.of(k);
      case MIR.DirectCall d -> calleeOf(d).flatMap(f -> freshObjs.get(f.name()));
      case MIR.StaticCall s -> Optional.ofNullable(funMap.get(s.fun())).flatMap(f -> freshObjs.get(f.name()));
      default -> Optional.empty();
    };
  }

  private void computeParamEscapes() {
    for (var fun : funMap.values()) { paramEscapes.put(fun.name(), new boolean[fun.args().size()]); }
    var changed = true;
    while (changed) {
      changed = false;
      for (var fun : funMap.values()) {
        var escaped = new HashSet<String>();
        var top = unwrap(fun.body());
        if (top instanceof MIR.X x) { escaped.add(x.name()); }
        walk(top, escaped);
        var flags = paramEscapes.get(fun.name());
        var args = fun.args();
        for (int i = 0; i < args.size(); i++) {
          if (!flags[i] && escaped.contains(args.get(i).name())) {
            flags[i] = true;
            changed = true;
          }
        }
      }
    }
  }

  private void walk(MIR.E e, Set<String> escaped) {
    switch (e) {
      case MIR.X ignored -> {}
      case MIR.Box box -> walk(box.inner(), escaped);
      case MIR.Block block -> walk(block.original(), escaped);
      case MIR.CreateObj k -> {
        for (var x : k.captures()) { escaped.add(x.name()); }
      }
      case MIR.MCall call -> walkUnknownCall(call.recv(), call.args(), escaped);
      case MIR.UpdatableListAsIdFnCall u -> walkUnknownCall(u.e().recv(), u.e().args(), escaped);
      case MIR.DirectCall d -> {
        var callee = calleeOf(d);
        var call = d.original();
        if (callee.isEmpty()) {
          walkUnknownCall(call.recv(), call.args(), escaped);
          return;
        }
        var flags = paramEscapes.get(callee.get().name());
        // The callee fun takes (methodArgs..., self, captures...), so the receiver maps to
        // the fun arg after the method args, and arg i maps to fun arg i.
        walkOperand(call.recv(), call.args().size(), flags, escaped);
        for (int i = 0; i < call.args().size(); i++) {
          walkOperand(call.args().get(i), i, flags, escaped);
        }
      }
      case MIR.StaticCall s -> {
        var callee = funMap.get(s.fun());
        if (callee == null || callee.args().size() != s.args().size()) {
          for (var a : s.args()) {
            var u = unwrap(a);
            if (u instanceof MIR.X x) { escaped.add(x.name()); } else { walk(u, escaped); }
          }
          return;
        }
        var flags = paramEscapes.get(callee.name());
        for (int i = 0; i < s.args().size(); i++) {
          walkOperand(s.args().get(i), i, flags, escaped);
        }
      }
      case MIR.BoolExpr b -> {
        walk(b.condition(), escaped);
        armEscapes(b.then(), escaped);
        armEscapes(b.else_(), escaped);
      }
    }
  }

  private void walkUnknownCall(MIR.E recv, List<? extends MIR.E> args, Set<String> escaped) {
    var ops = new ArrayList<MIR.E>();
    ops.add(recv);
    ops.addAll(args);
    for (var op : ops) {
      var u = unwrap(op);
      if (u instanceof MIR.X x) { escaped.add(x.name()); } else { walk(u, escaped); }
    }
  }

  private void walkOperand(MIR.E op, int idx, boolean[] calleeFlags, Set<String> escaped) {
    var u = unwrap(op);
    if (u instanceof MIR.X x) {
      if (idx >= calleeFlags.length || calleeFlags[idx]) { escaped.add(x.name()); }
      return;
    }
    walk(u, escaped);
  }

  /// A `BoolExpr` arm is a separate function whose args are the same-named captures of the
  /// enclosing scope, so an arm param escaping means that enclosing var escapes.
  private void armEscapes(MIR.FName arm, Set<String> escaped) {
    var fun = funMap.get(arm);
    if (fun == null) { return; }
    var flags = paramEscapes.get(fun.name());
    var args = fun.args();
    for (int i = 0; i < args.size(); i++) {
      if (flags[i]) { escaped.add(args.get(i).name()); }
    }
  }

  private void collectWantedFuns() {
    for (var fun : funMap.values()) { collectWanted(fun.body()); }
  }

  private void collectWanted(MIR.E e) {
    switch (e) {
      case MIR.X ignored -> {}
      case MIR.Box box -> collectWanted(box.inner());
      case MIR.Block block -> collectWanted(block.original());
      case MIR.CreateObj ignored -> {} // The method bodies are their own funs.
      case MIR.BoolExpr b -> collectWanted(b.condition());
      case MIR.MCall call -> {
        collectWanted(call.recv());
        call.args().forEach(this::collectWanted);
      }
      case MIR.UpdatableListAsIdFnCall u -> collectWanted(u.e());
      case MIR.DirectCall d -> {
        calleeOf(d).ifPresent(f -> {
          if (freshObjs.get(f.name()).isPresent()) { wantedFuns.add(f.name()); }
        });
        collectWanted(d.original().recv());
        d.original().args().forEach(this::collectWanted);
      }
      case MIR.StaticCall s -> {
        var f = funMap.get(s.fun());
        if (f != null && freshObjs.get(f.name()).isPresent()) { wantedFuns.add(f.name()); }
        s.args().forEach(this::collectWanted);
      }
    }
  }
}
