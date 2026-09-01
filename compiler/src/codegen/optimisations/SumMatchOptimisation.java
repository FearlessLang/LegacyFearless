package codegen.optimisations;

import ast.E;
import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import magic.Magic;
import magic.MagicImpls;
import main.java.ImplInfo;
import program.typesystem.XBs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// Turns a call on a church encoded sum into a test on the receiver.
///
/// `Opt` is the shape this is written for. Its two implementations each answer `.match` by
/// forwarding straight to a method of the matcher: the empty one with `m.empty`, and the one
/// `Opts#` writes with `m.some(x)`. Where the matcher is a literal at the call site, naming the
/// method each implementation selects removes both dispatches and the matcher object with them.
///
/// Every check here guards against a matcher that is valid Fearless but not the shape this
/// rewrite assumes. A matcher arm that reads the matcher itself is the case that matters, as in
/// `o.match{'a .some(_) -> a, .empty -> a}`: the rewrite never builds the object `a` names, so
/// such a matcher keeps its call. {@link BoolIfOptimisation} makes the same checks for `Bool`.
public class SumMatchOptimisation implements MIRCloneVisitor {
  private final MagicImpls<?> magic;
  private final ImplInfo cachedImpls;
  private final Set<String> alreadyCompiledPkgs;
  private ast.Program ast;
  private Map<MIR.FName, MIR.Fun> funMap = Map.of();
  private final Set<Id.DecId> candidates = new LinkedHashSet<>();
  private final Map<Id.DecId, List<Id.DecId>> impls = new HashMap<>();
  /// How many implementations one chain of tests may name. The arms of a closed set are
  /// exhaustive, so the count bounds a chain of compares against no dispatch at all, not against
  /// the dispatch a guess sits in front of. Each arm can cost two compares, because a type with
  /// captures carries a transient vtable beside its heap one.
  private static final int MAX_ARMS = 4;
  private int rewritten = 0;

  public SumMatchOptimisation(MagicImpls<?> magic, ImplInfo cachedImpls,
                              Set<String> alreadyCompiledPkgs) {
    this.magic = magic;
    this.cachedImpls = cachedImpls;
    this.alreadyCompiledPkgs = Set.copyOf(alreadyCompiledPkgs);
  }

  public int rewrittenCalls() { return rewritten; }

  @Override public MIR.Program visitProgram(MIR.Program p) {
    this.ast = p.p();
    this.impls.clear();
    this.candidates.clear();
    this.candidates.addAll(ast.ds().keySet());
    this.candidates.addAll(ast.inlineDs().keySet());
    this.funMap = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f, (a, b) -> a, HashMap::new));
    return MIRCloneVisitor.super.visitProgram(p);
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    var visited = MIRCloneVisitor.super.visitMCall(call, checkMagic);
    if (!(visited instanceof MIR.MCall rewrittenCall)) { return visited; }
    return sumMatch(rewrittenCall).map(m -> (MIR.E) m).orElse(visited);
  }

  private Optional<MIR.SumMatch> sumMatch(MIR.MCall call) {
    // Only a plain call. A parallelisable one is instrumented through the call it holds.
    if (!call.variant().contains(MIR.MCall.CallVariant.Standard)) { return Optional.empty(); }
    // A receiver the runtime makes carries a vtable no declaration accounts for.
    if (magic.get(call.recv()).isPresent()) { return Optional.empty(); }
    // The matcher takes exactly the one argument slot, and is written here rather than passed
    // in. A matcher that arrives as a name is one object the caller already holds, and its
    // methods are not this package's to name.
    if (call.args().size() != 1) { return Optional.empty(); }
    if (!(call.args().getFirst() instanceof MIR.CreateObj matcher)) { return Optional.empty(); }
    if (!conventional(matcher)) { return Optional.empty(); }

    var declared = call.recv().t().name();
    if (declared.isEmpty()) { return Optional.empty(); }
    var found = implsOf(declared.get());
    if (found.isEmpty() || found.size() > MAX_ARMS) { return Optional.empty(); }
    // The arms replace the dispatch rather than guess in front of one, so the set they cover
    // must be closed by the declaration and not by what this compilation happens to hold.
    if (!isSealed(declared.get())) { return Optional.empty(); }

    var arms = new ArrayList<MIR.SumArm>();
    for (var impl : found) {
      var arm = armOf(impl, call, matcher);
      if (arm.isEmpty()) { return Optional.empty(); }
      arms.add(arm.get());
    }
    rewritten++;
    return Optional.of(new MIR.SumMatch(call, call.recv(), matcher, arms));
  }

  /// The arm `impl` selects, or empty where its answer to this call is not a plain forward into
  /// the matcher.
  ///
  /// The lowered program answers for an implementation this compilation holds. One a cached
  /// package wrote is not there, because `pkgInfo` keeps no object literal, so its implInfo
  /// answers instead: it recorded the same forward when that package was lowered from source.
  private Optional<MIR.SumArm> armOf(Id.DecId impl, MIR.MCall call, MIR.CreateObj matcher) {
    return localForward(impl, call)
      .or(() -> cachedForward(impl, call))
      .flatMap(forward -> armFor(impl, matcher, forward));
  }

  /// What `impl` does with this call, read from the lowered program.
  private Optional<Forward> localForward(Id.DecId impl, MIR.MCall call) {
    var fun = funOn(impl, call);
    if (fun.isEmpty()) { return Optional.empty(); }
    var body = unwrap(fun.get().body());
    if (!(body instanceof MIR.MCall forward)) { return Optional.empty(); }
    // The forward's receiver must be this method's own matcher parameter, which is its first
    // declared parameter: a fun takes its parameters, then its receiver, then its captures.
    var params = fun.get().args();
    if (params.isEmpty()) { return Optional.empty(); }
    if (!(unwrap(forward.recv()) instanceof MIR.X recv)) { return Optional.empty(); }
    if (!recv.name().equals(params.getFirst().name())) { return Optional.empty(); }
    // Every argument of the forward must be a name the receiver carries, so a reader can take it
    // off the receiver. Anything computed there would have to run before the arm, which is work
    // this rewrite has nowhere to put.
    var captures = new ArrayList<String>();
    for (var arg : forward.args()) {
      if (!(unwrap(arg) instanceof MIR.X x)) { return Optional.empty(); }
      captures.add(x.name());
    }
    return Optional.of(new Forward(forward.name(), captures));
  }

  /// What `impl` does with this call, read from the implInfo of the package that wrote it.
  private Optional<Forward> cachedForward(Id.DecId impl, MIR.MCall call) {
    return cachedImpls.get(impl)
      .flatMap(entry -> entry.forward(call.name(), call.mdf()))
      .flatMap(f -> f.toName().map(to -> new Forward(to, f.args())));
  }

  /// The arm a forward names, where the matcher writes that method with a body of its own.
  private Optional<MIR.SumArm> armFor(Id.DecId impl, MIR.CreateObj matcher, Forward forward) {
    return matcher.meths().stream()
      .filter(m -> m.sig().name().equals(forward.to())
        && m.sig().name().num() == forward.captures().size())
      .findFirst()
      .flatMap(MIR.Meth::fName)
      .map(armName -> new MIR.SumArm(impl, armName, forward.captures()));
  }

  /// A method answered by a plain call on the matcher: what it calls, and the captures of the
  /// receiver it passes.
  private record Forward(Id.MethName to, List<String> captures) {}

  private Optional<MIR.Fun> funOn(Id.DecId impl, MIR.MCall call) {
    for (var capturesSelf : new boolean[]{ false, true }) {
      var fun = funMap.get(new MIR.FName(impl, call.name(), capturesSelf, call.mdf()));
      if (fun != null) { return Optional.of(fun); }
    }
    return Optional.empty();
  }

  /// Whether the matcher is the shape this rewrite runs on: it writes a body for every method
  /// its declared type has, and no arm reads the matcher itself.
  ///
  /// The count is against the declared type rather than against the literal, so a matcher that
  /// implements something richer than the methods it writes keeps its call. Reading the matcher
  /// is what `capturesSelf` reports, and an arm that does so has no object to read once the
  /// rewrite has removed it.
  private boolean conventional(MIR.CreateObj matcher) {
    var name = matcher.t().name();
    if (name.isEmpty()) { return false; }
    if (!matcher.unreachableMs().isEmpty()) { return false; }
    var dec = magic.p().of(name.get());
    var declaredMs = magic.p().meths(XBs.empty(), matcher.t().mdf(), dec.toIT(), 0);
    if (declaredMs.size() != matcher.meths().size()) { return false; }
    return matcher.meths().stream()
      .noneMatch(m -> m.capturesSelf() || m.fName().isEmpty());
  }

  private static MIR.E unwrap(MIR.E e) {
    return switch (e) {
      case MIR.Box box -> unwrap(box.inner());
      case MIR.Block block -> unwrap(block.original());
      default -> e;
    };
  }

  private boolean isSealed(Id.DecId declared) {
    if (ast.superDecIds(declared).contains(Magic.RuntimeImplemented)) { return false; }
    return cachedImpls.get(declared)
      .map(ImplInfo.Entry::sealed)
      .orElseGet(() -> ast.superDecIds(declared).contains(Magic.Sealed));
  }

  /// The implementations of `declared`, joined from the implInfo of every cached package and the
  /// declarations of every package being lowered now. This is the join
  /// {@link DevirtualiseGuarded} makes, and neither reading is a whole program analysis.
  private List<Id.DecId> implsOf(Id.DecId declared) {
    return impls.computeIfAbsent(declared, d -> {
      var found = new LinkedHashSet<Id.DecId>();
      cachedImpls.get(d).ifPresent(known -> found.addAll(known.implIds()));
      for (var candidate : candidates) {
        if (alreadyCompiledPkgs.contains(candidate.pkg())) { continue; }
        if (!candidate.equals(d) && !ast.superDecIds(candidate).contains(d)) { continue; }
        if (!hasOwnBody(candidate)) { continue; }
        found.add(candidate);
        if (found.size() > MAX_ARMS) { break; }
      }
      return List.copyOf(found);
    });
  }

  /// Whether a declaration gives a body to every method it writes. An empty literal a sealed
  /// type permits outside its package takes the vtable of its parent, so it is no implementation
  /// of its own and must not become an arm.
  private boolean hasOwnBody(Id.DecId dec) {
    if (ast.superDecIds(dec).contains(Magic.RuntimeImplemented)) { return false; }
    var meths = ast.of(dec).lambda().meths();
    return !meths.isEmpty() && meths.stream().noneMatch(E.Meth::isAbs);
  }
}
