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

/// Turns a call whose receiver has exactly one concrete type into a {@link MIR.DirectCall}.
///
/// The target is the per-literal method wrapper, not the lowered `Fun`. The wrapper takes
/// `(receiver, args...)` and reads the captures out of the receiver, so the call site needs
/// only the receiver expression. A `Fun` target would need the captures, which a caller
/// cannot supply when the receiver came out of a factory.
public class DevirtualiseByRTA implements MIRCloneVisitor {
  /// The receiver a runtime-backed body lowers to. `Magic!` is the source form and
  /// `base.Abort` what it becomes; neither runs generated code.
  private static final List<Id.DecId> MAGIC_BODY_RECEIVERS = List.of(Magic.MagicAbort, Magic.Abort);

  /// The types the runtime provides itself. A value of one carries an intrinsic vtable, so
  /// a call on it stays virtual whatever the declared receiver type says. This also covers a
  /// declared interface whose one implementation is magic: `.str` on a `Stringable` that only
  /// `Nat` implements must not become a call to the generated `Nat` wrapper.
  private static final Set<Id.DecId> MAGIC_DECS = Set.copyOf(Magic.allMagicDecs());

  private final MagicImpls<?> magic;
  private final Predicate<String> cachedPackage;
  private RapidTypeAnalysis rta;
  private Map<MIR.FName, MIR.Fun> funs;
  private final Map<Id.DecId, Boolean> runtimeBacked = new HashMap<>();

  private String currentPkg = "";

  /// Every concrete type a `DirectCall` names. Codegen needs each wrapper, and otherwise
  /// emits them only for the object literals it reaches.
  private final Set<Id.DecId> targets = new LinkedHashSet<>();

  private int rewrittenCalls = 0;

  public DevirtualiseByRTA(MagicImpls<?> magic, Predicate<String> cachedPackage) {
    this.magic = magic;
    this.cachedPackage = cachedPackage;
  }

  public Set<Id.DecId> targets() { return targets; }

  /// The runtime counters cannot report this: a devirtualised call site has no counter.
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

  /// True when the runtime, not codegen, provides the instances of this literal. Such a
  /// literal writes `Magic!` for a body and its values carry an intrinsic vtable that shares
  /// no wrapper with the generated one, so the IR literal describes the interface and not
  /// the code that runs.
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
    // A cached package serves later programs, which this analysis cannot see: one of them
    // can add an implementation and reach the wrapper of the wrong type.
    if (cachedPackage.test(currentPkg)) { return Optional.empty(); }

    // VPFParallelisable is admitted because it stays a plain call; `VPFCodegen` keeps its
    // shadow-frame instrumentation for a direct combiner. A flow variant does not.
    var variant = call.variant();
    if (!variant.contains(MIR.MCall.CallVariant.Standard)
      && !variant.contains(MIR.MCall.CallVariant.VPFParallelisable)) { return Optional.empty(); }

    var declared = call.recv().t().name();
    if (declared.isEmpty()) { return Optional.empty(); }
    if (magic.get(call.recv()).isPresent()) { return Optional.empty(); }

    var literal = rta.monomorphicImpl(declared.get());
    if (literal.isEmpty()) { return Optional.empty(); }
    // An anonymous literal's wrapper is named from a whole-program counter, so a cached
    // package's copy holds a different literal under that name in every later program.
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
