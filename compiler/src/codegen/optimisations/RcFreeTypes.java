package codegen.optimisations;

import codegen.MIR;
import id.Id;
import magic.Magic;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/// Which static types need no reference counting at run time.
///
/// A `FatPtr` carries its storage mode on its vtable, so `share`, `rc_decrement` and
/// `box_transient` all start by loading that byte and branching on it. Where the static type
/// admits only storage modes that make those operations the identity, a backend can drop the
/// operation and the load with it.
///
/// Two tiers answer the question, and they hold for different lengths of time:
///
///   * A primitive number type is `.primitive` by construction, whatever else the program
///     contains. This is the same trust a backend already places in a declared type when it
///     emits a comptime-resolved primitive dispatch with no vtable test.
///   * Any other type needs the set of concrete types that can reach it, which is what
///     {@link RapidTypeAnalysis} computes for devirtualisation. A declared type whose
///     concrete types are all primitives or singletons is rc-free as well. This tier reads the
///     compilation running now, and a later compilation can add a concrete type to the set.
///
/// {@link #isRcFreeForever} is the part of the second tier that no later compilation can take
/// away: a declaration nothing outside its package may implement has its whole concrete set
/// here, so the answer is a property of the declaration rather than of this program.
///
/// Every answer is one-way: an unknown type is counted, so a missing entry costs speed and
/// never correctness.
public final class RcFreeTypes {
  /// The magic types the runtime holds entirely inside the `FatPtr`, with no object header.
  private static final Set<Id.DecId> PRIMITIVES =
    Set.of(Magic.Nat, Magic.Int, Magic.Float, Magic.Byte);

  private final RapidTypeAnalysis rta;
  private final ast.Program program;
  /// The packages the front end read back from their type information rather than from source.
  /// Every body such a package holds is `base.Abort!`, so its object literals never reached the
  /// table and every impl set it would have filled is a floor. A literal one of these owns can
  /// only register against a type that same package declares, so refusing by declaring package
  /// covers each of them.
  private final Set<String> erasedPkgs;
  private final Map<Id.DecId, Boolean> cache = new HashMap<>();

  public RcFreeTypes(RapidTypeAnalysis rta, ast.Program program, Set<String> erasedPkgs) {
    this.rta = rta;
    this.program = program;
    this.erasedPkgs = Set.copyOf(erasedPkgs);
  }

  /// Both tiers, for the compilation running now. False for a type variable, whose runtime type
  /// the declaration does not pin down.
  public boolean isRcFree(MIR.MT t) {
    return t.name().map(this::isRcFree).orElse(false);
  }

  public boolean isRcFree(MIR.E e) { return isRcFree(e.t()); }

  public boolean isRcFree(Id.DecId declared) {
    return cache.computeIfAbsent(declared, this::compute);
  }

  /// The answer where it outlives the compilation that took it, such as inside a
  /// {@link main.CompilationUnit} whose generated text later compilations read as it stands.
  ///
  /// A primitive qualifies by construction. Anything else qualifies only where the declaration
  /// closes the set of its implementations, because a later compilation adds packages this one
  /// cannot see and any of them may implement an open declaration.
  public boolean isRcFreeForever(MIR.E e) {
    return e.t().name()
      .map(declared -> PRIMITIVES.contains(declared) || (isClosed(declared) && isRcFree(declared)))
      .orElse(false);
  }

  /// Whether the declaration itself bars a package other than the declaring one from
  /// implementing it: a named inline declaration, which nothing may implement, or a sealed
  /// type, which only its own package may.
  private boolean isClosed(Id.DecId declared) {
    return program.isInlineDec(declared) || program.superDecIds(declared).contains(Magic.Sealed);
  }

  private boolean compute(Id.DecId declared) {
    if (PRIMITIVES.contains(declared)) { return true; }
    if (erasedPkgs.contains(declared.pkg())) { return false; }
    var concretes = rta.implsOf(declared);
    // Nothing recorded means nothing was seen creating a value of this type, which is not
    // the same as nothing being able to.
    if (concretes.isEmpty()) { return false; }
    return concretes.stream().allMatch(this::isRcFreeConcrete);
  }

  /// A concrete type carries no reference count when its storage mode has no header to
  /// count: a primitive, or a singleton, which is an object literal capturing nothing. A
  /// runtime-backed type with no object literal behind it is counted.
  private boolean isRcFreeConcrete(Id.DecId concrete) {
    if (PRIMITIVES.contains(concrete)) { return true; }
    return rta.literalOf(concrete).map(k -> k.captures().isEmpty()).orElse(false);
  }
}
