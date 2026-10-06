package codegen.optimisations;

import codegen.MIR;
import id.Id;
import magic.Magic;
import magic.MagicImpls;
import main.java.ImplInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Finds closed sums whose values generated code carries without an object of their own.
///
/// Two shapes qualify. If no variant captures, the sum is a byte tag: each variant is a
/// singleton, so the tag holds the full value. If the sum has two variants, one with no capture
/// and one with one capture, the value is that capture. The empty variant is a sentinel vtable
/// that no program value carries.
public final class ScalarSumTypes {
  /// Raising this needs a new representation: {@link Repr#NICHE} holds one capture only.
  public static final int MAX_CAPTURES = 1;

  private static final Comparator<Id.DecId> TAG_ORDER =
    Comparator.comparing(Id.DecId::name).thenComparingInt(Id.DecId::gen);

  private final RapidTypeAnalysis rta;
  private final ast.Program program;
  private final Set<String> erasedPkgs;
  private final ImplInfo cachedImpls;
  private final Set<Id.DecId> runtimeSupplied;
  private final Map<Id.DecId, Optional<Shape>> cache = new HashMap<>();

  public enum Repr {
    /// A `u8` holding the index of the variant in {@link Shape#variants}.
    TAG,
    /// The capture of the capturing variant, as its own `FatPtr`. The other variant is the
    /// sentinel vtable.
    NICHE
  }

  /// `captures` is in the field order of the object literal.
  public record Variant(Id.DecId id, List<String> captures) {
    public Variant { captures = List.copyOf(captures); }
  }

  /// `variants` is in tag order.
  public record Shape(Id.DecId declared, List<Variant> variants, Repr repr) {
    public Shape { variants = List.copyOf(variants); }

    public List<Id.DecId> variantIds() { return variants.stream().map(Variant::id).toList(); }

    public int tag(Id.DecId variant) { return variantIds().indexOf(variant); }

    public List<String> capturesOf(Id.DecId variant) {
      return variants.stream()
        .filter(v -> v.id().equals(variant))
        .findFirst()
        .map(Variant::captures)
        .orElse(List.of());
    }

    /// The variant the niche carries as the value itself.
    public Variant capturingVariant() { return withCaptures(1); }

    /// The variant the niche's sentinel vtable stands for.
    public Variant capturelessVariant() { return withCaptures(0); }

    private Variant withCaptures(int n) {
      return variants.stream()
        .filter(v -> v.captures().size() == n)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
          "A niche sum has one variant of each arity, and " + declared + " has none of " + n));
    }
  }

  public ScalarSumTypes(RapidTypeAnalysis rta, ast.Program program, Set<String> erasedPkgs, ImplInfo cachedImpls) {
    this.rta = rta;
    this.program = program;
    this.erasedPkgs = Set.copyOf(erasedPkgs);
    this.cachedImpls = cachedImpls;
    this.runtimeSupplied = runtimeSuppliedSupers(program);
  }

  public Optional<Shape> shape(MIR.MT type) {
    return type.name().flatMap(this::shape);
  }

  public Optional<Shape> shape(Id.DecId declared) {
    return cache.computeIfAbsent(declared, this::compute);
  }

  private Optional<Shape> compute(Id.DecId declared) {
    if (runtimeSupplied.contains(declared)) { return Optional.empty(); }
    if (erasedPkgs.contains(declared.pkg())) {
      // An erased package has no literals in MIR or `program`. Its ImplInfo gives closure,
      // membership and layout, and is exact for the declaring package.
      var cached = cachedImpls.get(declared);
      if (cached.isEmpty()) { return Optional.empty(); }
      var entry = cached.orElseThrow();
      if (!(entry.sealed() || entry.inlineDec())) { return Optional.empty(); }
      return shapeOf(declared, entry.implIds(), this::cachedVariant);
    }
    if (!isClosed(declared)) { return Optional.empty(); }
    var variants = rta.implsOf(declared).stream()
      .filter(v -> v.pkg().equals(declared.pkg()))
      .collect(Collectors.toCollection(ArrayList::new));
    return shapeOf(declared, variants, this::localVariant);
  }

  /// A variant with unknown layout excludes the sum. If we guess no capture, we can drop a field.
  /// If we guess a capture, we can address a field that the object does not hold.
  private Optional<Shape> shapeOf(Id.DecId declared, List<Id.DecId> variants, Function<Id.DecId, Optional<Variant>> layout) {
    var ordered = variants.stream().distinct().sorted(TAG_ORDER).toList();
    if (ordered.size() < 2 || ordered.size() > 256) { return Optional.empty(); }
    var found = new ArrayList<Variant>();
    for (var variant : ordered) {
      var one = layout.apply(variant);
      if (one.isEmpty() || one.orElseThrow().captures().size() > MAX_CAPTURES) {
        return Optional.empty();
      }
      found.add(one.orElseThrow());
    }
    return reprOf(found).map(repr -> new Shape(declared, found, repr));
  }

  private Optional<Repr> reprOf(List<Variant> variants) {
    if (variants.stream().allMatch(v -> v.captures().isEmpty())) {
      return Optional.of(Repr.TAG);
    }
    var arities = variants.stream().map(v -> v.captures().size()).sorted().toList();
    if (!arities.equals(List.of(0, 1))) { return Optional.empty(); }
    // The niche builds a new container each time a position needs an object. A type with
    // identity would see different objects for one value.
    return variants.stream().anyMatch(v -> hasIdentity(v.id()))
      ? Optional.empty()
      : Optional.of(Repr.NICHE);
  }

  private boolean hasIdentity(Id.DecId concrete) {
    if (erasedPkgs.contains(concrete.pkg())) {
      return cachedImpls.get(concrete).map(ImplInfo.Entry::hasIdentity).orElse(true);
    }
    return program.superDecIds(concrete).contains(Magic.HasIdentity);
  }

  private Optional<Variant> localVariant(Id.DecId concrete) {
    return rta.literalOf(concrete)
      .map(k -> new Variant(concrete, k.captures().stream().map(MIR.X::name).toList()));
  }

  private Optional<Variant> cachedVariant(Id.DecId concrete) {
    var entry = cachedImpls.get(concrete);
    if (entry.isEmpty()) { return Optional.empty(); }
    if (entry.orElseThrow().singleton()) { return Optional.of(new Variant(concrete, List.of())); }
    return entry.orElseThrow().captureFields().map(fields -> new Variant(concrete, fields));
  }

  private boolean isClosed(Id.DecId declared) {
    if (!program.ds().containsKey(declared) && !program.inlineDs().containsKey(declared)) {
      return false;
    }
    if (program.superDecIds(declared).contains(Magic.RuntimeImplemented)) { return false; }
    return program.isInlineDec(declared) || program.superDecIds(declared).contains(Magic.Sealed);
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
}
