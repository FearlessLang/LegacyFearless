package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import magic.Magic;
import magic.MagicImpls;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/// Turns a method call whose receiver has exactly one concrete type into a {@link MIR.DirectCall}.
///
/// The target is the per-literal method wrapper, not the lowered `Fun`: the wrapper takes
/// `(receiver, args...)` and reads the captures out of the receiver, so the call site needs
/// nothing beyond the receiver expression. A call site that targeted the `Fun` would have to
/// supply the captures, which a caller cannot do when the receiver came out of a factory.
public class DevirtualiseByRTA implements MIRCloneVisitor {
  /// The receiver of the call a runtime-backed body is lowered to. `Magic!` is the source form;
  /// `base.Abort` is what it becomes, and a body that only aborts runs no generated code either
  /// way, so both belong here.
  private static final List<Id.DecId> MAGIC_BODY_RECEIVERS = List.of(Magic.MagicAbort, Magic.Abort);

  /// The types the runtime provides itself. A value of one of these carries an intrinsic vtable,
  /// so a call on it must stay virtual and reach the intrinsic dispatch, whatever the declared
  /// type of the receiver says. A declared interface with a magic type as its one implementation
  /// resolves here: `.str` on a `Stringable` parameter that only `Nat` implements must not become
  /// a call to the generated `Nat` wrapper.
  private static final Set<Id.DecId> MAGIC_DECS = Set.copyOf(Magic.allMagicDecs());

  private final MagicImpls<?> magic;
  /// Tells whether the backend caches and reuses the generated code of a package.
  private final Predicate<String> cachedPackage;
  private RapidTypeAnalysis rta;
  private Map<MIR.FName, MIR.Fun> funs;
  private final Map<Id.DecId, Boolean> runtimeBacked = new HashMap<>();

  /// The package the visitor is in. It decides whether a call site there may be devirtualised.
  private String currentPkg = "";

  /// Every concrete type a `DirectCall` names. Codegen has to have the wrapper of each one, and
  /// its emission is otherwise driven by the object literals it reaches.
  private final Set<Id.DecId> targets = new LinkedHashSet<>();

  private int rewrittenCalls = 0;

  public DevirtualiseByRTA(MagicImpls<?> magic, Predicate<String> cachedPackage) {
    this.magic = magic;
    this.cachedPackage = cachedPackage;
  }

  public Set<Id.DecId> targets() { return targets; }

  /// How many call sites this pass resolved. It reports whether the pass fired at all, which the
  /// runtime counters cannot: a devirtualised call site carries no counter.
  public int rewrittenCalls() { return rewrittenCalls; }

  @Override public MIR.Program visitProgram(MIR.Program p) {
    this.rta = new RapidTypeAnalysis(p);
    this.funs = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f, (a, b) -> a));
    this.runtimeBacked.clear();
    return MIRCloneVisitor.super.visitProgram(p);
  }

  @Override public MIR.Package visitPackage(MIR.Package pkg) {
    this.currentPkg = pkg.name();
    var res = MIRCloneVisitor.super.visitPackage(pkg);
    this.currentPkg = "";
    return res;
  }

  /// True when the runtime, and not codegen, provides the instances of this literal. Such a
  /// literal writes `Magic!` for a body, and a value of its type carries an intrinsic vtable that
  /// shares no method wrapper with the generated one. So the object literal in the IR describes
  /// the interface of the type and not the code that runs, and a call on it stays virtual.
  private boolean isRuntimeBacked(MIR.CreateObj literal) {
    return runtimeBacked.computeIfAbsent(literal.concreteT().id(), ignored ->
      literal.meths().stream()
        .flatMap(m -> m.fName().stream())
        .map(funs::get)
        .anyMatch(fun -> fun != null && isMagicBody(fun.body())));
  }

  private boolean isMagicBody(MIR.E body) {
    return switch (body) {
      case MIR.Box box -> isMagicBody(box.inner());
      case MIR.Block block -> isMagicBody(block.original());
      case MIR.MCall call -> MAGIC_BODY_RECEIVERS.stream().anyMatch(dec -> magic.isMagic(dec, call.recv()));
      default -> false;
    };
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    var visited = MIRCloneVisitor.super.visitMCall(call, checkMagic);
    if (!(visited instanceof MIR.MCall rewritten)) { return visited; }
    return resolve(rewritten).<MIR.E>map(concrete -> {
      targets.add(concrete);
      rewrittenCalls++;
      return new MIR.DirectCall(rewritten, concrete);
    }).orElse(rewritten);
  }

  private Optional<Id.DecId> resolve(MIR.MCall call) {
    // The generated code of a cached package is kept on disk and reused by every later program,
    // while this analysis sees one program only. "This declared type has one implementation" is
    // therefore not a fact about the programs that code will run in: a user program can add
    // another implementation and then reaches the wrapper of the wrong type. A call site in a
    // package that is generated again for each program has the whole binary in view and is safe.
    if (cachedPackage.test(currentPkg)) { return Optional.empty(); }

    // A flow variant lowers to something other than a plain method wrapper. A VPF-parallelisable
    // call does not: it is a plain call that the caller instruments with a shadow frame, and
    // `VPFCodegen` keeps that instrumentation when the combining call is direct.
    var variant = call.variant();
    if (!variant.contains(MIR.MCall.CallVariant.Standard)
      && !variant.contains(MIR.MCall.CallVariant.VPFParallelisable)) { return Optional.empty(); }

    // An erased generic receiver has no declared type to look up.
    var declared = call.recv().t().name();
    if (declared.isEmpty()) { return Optional.empty(); }
    // The runtime, and not a generated wrapper, answers a call on a magic receiver.
    if (magic.get(call.recv()).isPresent()) { return Optional.empty(); }

    var literal = rta.monomorphicImpl(declared.get());
    if (literal.isEmpty()) { return Optional.empty(); }
    // The wrapper of an anonymous literal is named after a counter that runs over the whole
    // program, so the same literal of a cached package gets a different name in each program. A
    // call site here would name the wrapper of whichever program filled the cache, and that name
    // holds a different literal now. A named type mangles from its declaration and stays stable.
    var targetId = literal.get().concreteT().id();
    if (targetId.isFresh() && cachedPackage.test(targetId.pkg())) { return Optional.empty(); }
    if (MAGIC_DECS.contains(literal.get().concreteT().id())) { return Optional.empty(); }
    if (isRuntimeBacked(literal.get())) { return Optional.empty(); }

    var wanted = call.name().withMdf(Optional.of(call.mdf()));
    var hasMeth = literal.get().meths().stream()
      .filter(m -> m.fName().isPresent())
      .anyMatch(m -> m.sig().name().withMdf(Optional.of(m.sig().mdf())).equals(wanted));
    if (!hasMeth) { return Optional.empty(); }

    return Optional.of(literal.get().concreteT().id());
  }
}
