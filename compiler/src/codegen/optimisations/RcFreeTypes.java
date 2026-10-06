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

/// Optimise reference counting based on static knowledge. This relies on [RapidTypeAnalysis], which is a lightweight
/// form of whole program analysis (uses cached [ImplInfo] information for other packages that aren't in the current
/// compilation unit).
///
/// Reference counting operations on non-heap objects (i.e. transient types) are no-ops; however, there is still a cost
/// due to having to check the storage class of the objects at runtime. For the types that we know will never be heap
///  allocated, we can elide that check.
public final class RcFreeTypes {
  public enum Strategy {
    NONE,
    HEAP,
    HEAP_OR_TRANSIENT,
    DYNAMIC;

    static Strategy join(Strategy a, Strategy b) { return a == b ? a : DYNAMIC; }
  }

  private static final Set<Id.DecId> PRIMITIVES =
    Set.of(Magic.Nat, Magic.Int, Magic.Float, Magic.Byte);

  private final RapidTypeAnalysis rta;
  private final ast.Program program;
  private final Set<String> erasedPkgs;
  private final ImplInfo cachedImpls;
  private final Map<Id.DecId, Strategy> cache = new HashMap<>();
  private final Set<Id.DecId> runtimeSupplied;

  public RcFreeTypes(RapidTypeAnalysis rta, ast.Program program, Set<String> erasedPkgs) {
    this(rta, program, erasedPkgs, ImplInfo.EMPTY);
  }

  public RcFreeTypes(
      RapidTypeAnalysis rta,
      ast.Program program,
      Set<String> erasedPkgs,
      ImplInfo cachedImpls
  ) {
    this.rta = rta;
    this.program = program;
    this.erasedPkgs = Set.copyOf(erasedPkgs);
    this.cachedImpls = cachedImpls;
    this.runtimeSupplied = runtimeSuppliedSupers(program);
  }

  private static Set<Id.DecId> runtimeSuppliedSupers(ast.Program program) {
    var supplied = new HashSet<>(MagicImpls.MAGIC_DECS);
    Stream.concat(program.ds().keySet().stream(), program.inlineDs().keySet().stream())
      .filter(d -> program.superDecIds(d).contains(Magic.RuntimeImplemented))
      .forEach(supplied::add);
    return supplied.stream()
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

  public Strategy strategyForever(MIR.MT t) {
    return t.name()
      .filter(declared -> isPrimitive(declared) || isClosed(declared))
      .map(this::strategy)
      .orElse(Strategy.DYNAMIC);
  }

  public Strategy strategyForever(MIR.E e) { return strategyForever(e.t()); }

  public boolean isRcFree(MIR.MT t) { return strategy(t) == Strategy.NONE; }

  public boolean isRcFree(MIR.E e) { return isRcFree(e.t()); }

  public boolean isRcFree(Id.DecId declared) { return strategy(declared) == Strategy.NONE; }

  public boolean isRcFreeForever(MIR.E e) { return strategyForever(e) == Strategy.NONE; }

  private boolean isPrimitive(Id.DecId declared) {
    if (PRIMITIVES.contains(declared)) { return true; }
    return LiteralKind.match(declared.name())
      .map(kind -> PRIMITIVES.contains(kind.magicKind()))
      .orElse(false);
  }

  private boolean isClosed(Id.DecId declared) {
    if (program.superDecIds(declared).contains(Magic.RuntimeImplemented)) { return false; }
    return program.isInlineDec(declared) || program.superDecIds(declared).contains(Magic.Sealed);
  }

  private Strategy compute(Id.DecId declared) {
    if (isPrimitive(declared)) { return Strategy.NONE; }
    if (runtimeSupplied.contains(declared)) { return Strategy.DYNAMIC; }
    var cached = cachedImpls.get(declared);
    if (erasedPkgs.contains(declared.pkg()) && cached.isEmpty()) { return Strategy.DYNAMIC; }
    var concretes = erasedPkgs.contains(declared.pkg())
      ? Stream.concat(cached.orElseThrow().implIds().stream(), rta.implsOf(declared).stream()
          .filter(concrete -> !erasedPkgs.contains(concrete.pkg())))
        .distinct().toList()
      : List.copyOf(rta.implsOf(declared));
    if (concretes.isEmpty()) { return Strategy.DYNAMIC; }

    return concretes.stream()
      .map(this::concreteStrategy)
      .reduce(Strategy::join)
      .orElse(Strategy.DYNAMIC);
  }

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

  private Strategy capturingStrategy(boolean hasIdentity) {
    return hasIdentity ? Strategy.HEAP : Strategy.HEAP_OR_TRANSIENT;
  }
}
