package codegen.optimisations;

import codegen.MIR;
import id.Id;
import magic.LiteralKind;
import magic.Magic;
import magic.MagicImpls;
import main.java.ImplInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// The reference-count operation that the possible run-time storage modes require.
///
/// A `FatPtr` carries its storage mode on its vtable. Where rapid type analysis constrains all
/// values of a static type to one supported storage-mode class, code generation can avoid the
/// general storage-mode dispatch.
///
/// Two tiers answer the question. {@link #strategy} uses the concrete types in the current
/// compilation. {@link #strategyForever} additionally requires a declaration that later
/// compilations cannot implement, and is therefore safe to write into cached package code.
///
/// Missing information gives {@link Strategy#DYNAMIC}, so an incomplete answer costs speed and
/// never correctness.
public final class RcFreeTypes {
  public enum Strategy {
    /// Every possible storage mode makes share and decrement no-ops.
    NONE,
    /// Every possible value is a generated heap object, so no storage-mode test is needed.
    HEAP,
    /// Every possible value is a generated object that can be heap or transient, so one heap
    /// test stands in for the general dispatch.
    HEAP_OR_TRANSIENT,
    /// The storage mode needs the general run-time dispatch.
    DYNAMIC;

    /// What serves two concrete types at once. Disagreement falls to the general dispatch: a
    /// widening join is sound, but the specialised operations are `inline`, and the code they
    /// add at a hot site costs more than the dispatch they remove.
    static Strategy join(Strategy a, Strategy b) { return a == b ? a : DYNAMIC; }
  }

  /// The magic types the runtime holds entirely inside the `FatPtr`, with no object header.
  private static final Set<Id.DecId> PRIMITIVES =
    Set.of(Magic.Nat, Magic.Int, Magic.Float, Magic.Byte);

  private final RapidTypeAnalysis rta;
  private final ast.Program program;
  /// The packages the front end read back from their type information rather than from source.
  /// Every body such a package holds is `base.Abort!`, so its object literals never reached
  /// {@link #rta} and only {@link #cachedImpls} answers for it.
  private final Set<String> erasedPkgs;
  /// What every cached package recorded about the types it declares, joined.
  private final ImplInfo cachedImpls;
  private final Map<Id.DecId, Strategy> cache = new HashMap<>();
  /// Every declared type a value the runtime makes itself can flow into.
  ///
  /// No implementation list holds such a value: {@link RapidTypeAnalysis} has no object literal
  /// to record for it, and {@link ImplInfo} counts object literals alone. Both tiers would
  /// therefore answer from the Fearless implementations only, and a runtime storage mode the
  /// Fearless ones do not use, such as the `primitiveContainer` of `base.Var`, would break the
  /// specialised operation the answer picked.
  private final Set<Id.DecId> runtimeSupplied;

  public RcFreeTypes(RapidTypeAnalysis rta, ast.Program program, Set<String> erasedPkgs) {
    this(rta, program, erasedPkgs, ImplInfo.EMPTY);
  }

  public RcFreeTypes(RapidTypeAnalysis rta, ast.Program program, Set<String> erasedPkgs,
                     ImplInfo cachedImpls) {
    this.rta = rta;
    this.program = program;
    this.erasedPkgs = Set.copyOf(erasedPkgs);
    this.cachedImpls = cachedImpls;
    this.runtimeSupplied = runtimeSuppliedSupers(program);
  }

  /// The supertypes of every declaration the runtime implements without an object literal: the
  /// traits of {@link MagicImpls#MAGIC_DECS} and everything carrying {@link Magic#RuntimeImplemented}.
  private static Set<Id.DecId> runtimeSuppliedSupers(ast.Program program) {
    var supplied = new HashSet<>(MagicImpls.MAGIC_DECS);
    Stream.concat(program.ds().keySet().stream(), program.inlineDs().keySet().stream())
      .filter(d -> program.superDecIds(d).contains(Magic.RuntimeImplemented))
      .forEach(supplied::add);
    return supplied.stream()
      // `superDecIds` needs a declaration, which a program that never imports the type lacks.
      .filter(d -> program.ds().containsKey(d) || program.inlineDs().containsKey(d))
      .flatMap(d -> program.superDecIds(d).stream())
      .collect(Collectors.toUnmodifiableSet());
  }

  public Strategy strategy(MIR.MT t) {
    return t.name().map(this::strategy).orElse(Strategy.DYNAMIC);
  }

  public Strategy strategy(MIR.E e) { return strategy(e.t()); }

  public Strategy strategy(Id.DecId declared) {
    return cache.computeIfAbsent(declared, this::compute);
  }

  /// The strategy for generated text that outlives this compilation, such as a
  /// {@link main.CompilationUnit} that later compilations read as it stands.
  public Strategy strategyForever(MIR.MT t) {
    return t.name()
      .map(declared -> (isPrimitive(declared) || isClosed(declared))
        ? strategy(declared)
        : Strategy.DYNAMIC)
      .orElse(Strategy.DYNAMIC);
  }

  public Strategy strategyForever(MIR.E e) { return strategyForever(e.t()); }

  public boolean isRcFree(MIR.MT t) { return strategy(t) == Strategy.NONE; }

  public boolean isRcFree(MIR.E e) { return isRcFree(e.t()); }

  public boolean isRcFree(Id.DecId declared) { return strategy(declared) == Strategy.NONE; }

  public boolean isRcFreeForever(MIR.E e) { return strategyForever(e) == Strategy.NONE; }

  /// Whether the declaration is one the runtime keeps inside the `FatPtr`.
  ///
  /// A number literal has a declared type of its own, such as `base.natLit.1`, rather than the
  /// magic type it carries. That type names the magic type it stands for.
  private boolean isPrimitive(Id.DecId declared) {
    if (PRIMITIVES.contains(declared)) { return true; }
    return LiteralKind.match(declared.name())
      .map(kind -> PRIMITIVES.contains(kind.magicKind()))
      .orElse(false);
  }

  /// Whether the declaration bars a package other than its declaring package from implementing
  /// it. A runtime-implemented declaration is open because sealing does not remove the runtime's
  /// implementation.
  private boolean isClosed(Id.DecId declared) {
    if (program.superDecIds(declared).contains(Magic.RuntimeImplemented)) { return false; }
    return program.isInlineDec(declared) || program.superDecIds(declared).contains(Magic.Sealed);
  }

  private Strategy compute(Id.DecId declared) {
    if (isPrimitive(declared)) { return Strategy.NONE; }
    if (runtimeSupplied.contains(declared)) { return Strategy.DYNAMIC; }
    var concretes = erasedPkgs.contains(declared.pkg())
      ? cachedImpls.get(declared).map(ImplInfo.Entry::implIds).orElse(List.of())
      : List.copyOf(rta.implsOf(declared));
    // Nothing was seen making a value of this type, which does not mean nothing can.
    if (concretes.isEmpty()) { return Strategy.DYNAMIC; }

    return concretes.stream()
      .map(this::concreteStrategy)
      .reduce(Strategy::join)
      .orElse(Strategy.DYNAMIC);
  }

  /// An erased package answers from {@link #cachedImpls} alone. Most of its values are inline
  /// declarations, which `pkgInfo` does not keep, so asking the program about one raises
  /// {@link failure.Fail#traitNotFound}.
  private Strategy concreteStrategy(Id.DecId concrete) {
    if (isPrimitive(concrete)) { return Strategy.NONE; }
    if (erasedPkgs.contains(concrete.pkg())) {
      return cachedImpls.get(concrete)
        .map(entry -> entry.singleton()
          ? Strategy.NONE
          : capturingStrategy(entry.hasIdentity()))
        .orElse(Strategy.DYNAMIC);
    }
    return rta.literalOf(concrete)
      .map(literal -> literal.captures().isEmpty()
        ? Strategy.NONE
        : capturingStrategy(program.superDecIds(concrete).contains(Magic.HasIdentity)))
      .orElse(Strategy.DYNAMIC);
  }

  /// A capturing value is heap allocated, and transient as well where the runtime may hold it in
  /// the frame that makes it. An identity the program can compare bars the transient form, which
  /// leaves the heap alone and needs no test.
  private Strategy capturingStrategy(boolean hasIdentity) {
    return hasIdentity ? Strategy.HEAP : Strategy.HEAP_OR_TRANSIENT;
  }
}
