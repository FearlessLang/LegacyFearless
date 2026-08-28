package main.java;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import codegen.MIR;
import id.Id;
import magic.Magic;
import utils.IoErr;
import utils.Mapper;
import visitors.MIRVisitor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// What every type of a package is implemented by, written beside the cached generated code of
/// that package and read back by a later program.
///
/// A cached package needs this because `pkgInfo` cannot carry it. That file keeps method headers
/// and top level declarations only, so an inline declaration never reaches it, and most values in
/// the base library are inline. Their absence is the whole reason this file exists.
///
/// A package writes this only on the run that lowers it from source, where every body is present,
/// so the file is exact for the package that names it. Each package counts itself alone: a reader
/// joins the files of the packages it holds, which is how a type is found effectively final
/// without any pass reading a whole program.
public record ImplInfo(Map<Id.DecId, Entry> entries) {
  public static final ImplInfo EMPTY = new ImplInfo(Map.of());

  private static final ObjectMapper JSON = new ObjectMapper();

  /// What a package knew about one type it declares. `impls` holds names rather than
  /// {@link Id.DecId}, which is what the file carries and what Jackson maps without help.
  ///
  /// Nothing here says whether the runtime implements the type. That comes from the
  /// `base.RuntimeImplemented` marker, which `pkgInfo` keeps and `superDecIds` resolves.
  public record Entry(boolean sealed, boolean inlineDec, List<String> impls) {
    public static Entry of(boolean sealed, boolean inlineDec, Set<Id.DecId> impls) {
      return new Entry(sealed, inlineDec, impls.stream().map(Id.DecId::toString).toList());
    }

    /// The implementations as names again. One a later compiler no longer parses is left out,
    /// which costs a direct call and never makes a wrong one.
    public List<Id.DecId> implIds() {
      return impls.stream().flatMap(name -> parseDecId(name).stream()).toList();
    }
  }

  public static String fileName(String backend) { return "implInfo." + backend + ".json"; }

  /// Everything a later program needs to decide a call on a type `pkgName` declares.
  ///
  /// The values come from the lowered program, where a value is exactly a {@link MIR.CreateObj}:
  /// the singleton of a type that captures nothing, and every object literal a method body
  /// writes. Reading the lowered program rather than the source keeps this honest about what
  /// codegen makes, so a type that is never instantiated, and therefore carries no vtable, is no
  /// implementation of anything.
  public static ImplInfo of(String pkgName, ast.Program program, MIR.Program mir) {
    var values = valuesOf(mir).stream()
      .filter(value -> value.pkg().equals(pkgName))
      .collect(Collectors.toUnmodifiableSet());
    return new ImplInfo(Mapper.of(out -> targetsOf(pkgName, program).forEach(target ->
      out.put(target, Entry.of(
        program.superDecIds(target).contains(Magic.Sealed),
        program.isInlineDec(target),
        implsOf(program, target, values))))));
  }

  /// The types `pkgName` declares, inline declarations included.
  private static Stream<Id.DecId> targetsOf(String pkgName, ast.Program program) {
    return Stream.concat(program.ds().keySet().stream(), program.inlineDs().keySet().stream())
      .filter(d -> d.pkg().equals(pkgName))
      .distinct();
  }

  /// The values of `values` that are of `target`, or of a type that reaches it.
  private static Set<Id.DecId> implsOf(ast.Program program, Id.DecId target, Set<Id.DecId> values) {
    return values.stream()
      .filter(value -> program.superDecIds(value).contains(target))
      .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /// The concrete type of every value the lowered program makes.
  private static Set<Id.DecId> valuesOf(MIR.Program mir) {
    var found = new LinkedHashSet<Id.DecId>();
    MIRVisitor<Void> walk = new CreateObjCollector(found);
    mir.pkgs().forEach(pkg -> {
      pkg.defs().values().forEach(def -> def.singletonInstance().ifPresent(k -> k.accept(walk, true)));
      pkg.funs().forEach(fun -> fun.body().accept(walk, true));
    });
    return found;
  }

  /// Names every {@link MIR.CreateObj} an expression holds. {@link MIRVisitor} walks an
  /// optimisation wrapper through the expression it replaced, so a value a pass rewrote around is
  /// still seen. The body of a literal's method is lowered to its own {@link MIR.Fun}, which the
  /// caller walks, so this does not follow one.
  private record CreateObjCollector(Set<Id.DecId> found) implements MIRVisitor<Void> {
    @Override public Void visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
      createObj.t().name().ifPresent(found::add);
      return null;
    }
    @Override public Void visitX(MIR.X x, boolean checkMagic) { return null; }
    @Override public Void visitMCall(MIR.MCall call, boolean checkMagic) {
      call.recv().accept(this, checkMagic);
      call.args().forEach(arg -> arg.accept(this, checkMagic));
      return null;
    }
  }

  /// The file as JSON: an object whose keys are type names and whose values carry the flags and
  /// the implementations. A name is written as `pkg.Short/arity`, which {@link Id.DecId#toString}
  /// gives.
  ///
  /// ```json
  /// { "base.Opt/1": { "sealed": true, "inlineDec": false, "impls": ["base.Opt/1"] } }
  /// ```
  public String write() {
    Map<String, Entry> doc = Mapper.of(out ->
      entries.forEach((target, entry) -> out.put(target.toString(), entry)));
    return IoErr.of(() -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
  }

  /// The entries of every package of `pkgs`, joined. Each package writes the types it declares
  /// alone, so no two files name the same target.
  ///
  /// A package whose generated code is cached wrote this file beside it. Arriving here without
  /// one means the two came apart, and a decision taken on the half that is left would read a
  /// count that is a floor.
  public static ImplInfo readAll(Path cacheDir, Set<String> pkgs, String backend) {
    Map<Id.DecId, Entry> entries = Mapper.of(out -> pkgs.stream()
      .map(pkg -> fileOf(cacheDir, pkg, backend))
      .forEach(file -> out.putAll(read(file).entries())));
    return entries.isEmpty() ? EMPTY : new ImplInfo(entries);
  }

  private static Path fileOf(Path cacheDir, String pkg, String backend) {
    var file = cacheDir.resolve(pkg.replace(".", "/")).resolve(fileName(backend));
    if (Files.isRegularFile(file)) { return file; }
    throw new IllegalStateException("Cached package " + pkg + " has no " + fileName(backend)
      + " beside it, at " + file + ". A package writes the two together, so this cache predates"
      + " the file or lost it. Delete " + cacheDir + " to build the package again.");
  }

  public static ImplInfo read(Path file) {
    if (!Files.isRegularFile(file)) { return EMPTY; }
    Map<String, Entry> doc = IoErr.of(() -> JSON.readValue(file.toFile(), new TypeReference<>() {}));
    return new ImplInfo(Mapper.of(out -> doc.forEach((name, entry) ->
      parseDecId(name).ifPresent(target -> out.put(target, entry)))));
  }

  /// A name this file wrote, or empty when the text is not one.
  private static Optional<Id.DecId> parseDecId(String text) {
    var slash = text.lastIndexOf('/');
    if (slash <= 0) { return Optional.empty(); }
    try {
      return Optional.of(
        new Id.DecId(text.substring(0, slash), Integer.parseInt(text.substring(slash + 1))));
    } catch (NumberFormatException e) { return Optional.empty(); }
  }

  /// What a cached package said about `target`.
  public Optional<Entry> get(Id.DecId target) { return Optional.ofNullable(entries.get(target)); }

  public boolean knows(Id.DecId target) { return entries.containsKey(target); }
}
