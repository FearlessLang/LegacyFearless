package main.java;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import codegen.MIR;
import id.Id;
import id.Mdf;
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
  /// `singleton` says the value of this type carries no reference count: the package made it as
  /// an object literal that captures nothing, so the runtime gives it a static header.
  ///
  /// `hasIdentity` says the type carries the `base.HasIdentity` marker, so the runtime always
  /// gives its values a heap header and never a transient one. A reader cannot work this out for
  /// itself, because an inline declaration never reaches `pkgInfo` and `superDecIds` therefore
  /// does not resolve one.
  ///
  /// Whether the runtime implements the type is not here. That comes from the
  /// `base.RuntimeImplemented` marker, which `pkgInfo` keeps and `superDecIds` resolves.
  public record Entry(boolean sealed, boolean inlineDec, boolean singleton, boolean hasIdentity,
                      List<String> impls, List<Forward> forwards) {
    public Entry {
      // A file written before this package counted forwards names none, which reads as a type
      // that forwards nothing and so is never rewritten.
      forwards = forwards == null ? List.of() : List.copyOf(forwards);
    }

    public static Entry of(boolean sealed, boolean inlineDec, boolean singleton,
                           boolean hasIdentity, Set<Id.DecId> impls, List<Forward> forwards) {
      return new Entry(sealed, inlineDec, singleton, hasIdentity,
        impls.stream().map(Id.DecId::toString).toList(), forwards);
    }

    /// The implementations as names again. One a later compiler no longer parses is left out,
    /// which costs a direct call and never makes a wrong one.
    public List<Id.DecId> implIds() {
      return impls.stream().flatMap(name -> parseDecId(name).stream()).toList();
    }

    /// What this type does with `name` under `mdf`, where that is a plain forward.
    public Optional<Forward> forward(Id.MethName name, Mdf mdf) {
      return forwards.stream()
        .filter(f -> f.on.equals(name.toString()) && f.mdf.equals(mdf.toString()))
        .findFirst();
    }
  }

  /// A method a type answers by forwarding straight to a method of its own first parameter,
  /// which is the shape every church encoded sum takes: `Opt` answers `.match` with `m.empty`,
  /// and the literal `Opts#` writes answers it with `m.some(x)`.
  ///
  /// `on` is the method as its `Id.MethName` prints, `mdf` the receiver modifier it is written
  /// for, `to` the method of that first parameter the body calls, and `args` the captures of
  /// this type the forward passes to it, in order. A reader takes those off the receiver rather
  /// than building the parameter at all.
  public record Forward(String on, String mdf, String to, List<String> args) {
    public Forward { args = args == null ? List.of() : List.copyOf(args); }

    /// The forwarded method's name, or empty when a later compiler no longer parses it.
    public Optional<Id.MethName> toName() { return parseMethName(to); }
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
    // Ordered, because the order reaches generated code: `implsOf` keeps it, and
    // `DevirtualiseGuarded` tests the guard arms in the order recorded here. An unordered set
    // iterates in an order the JVM randomises per run, which would make the arm order, and the
    // branch layout that follows from it, differ between two compilations of the same source.
    var values = valuesOf(mir).stream()
      .filter(value -> value.pkg().equals(pkgName))
      .collect(Collectors.toCollection(LinkedHashSet::new));
    var singletons = singletonsOf(mir);
    var forwards = forwardsOf(mir);
    return new ImplInfo(Mapper.of(out -> targetsOf(pkgName, program).forEach(target ->
      out.put(target, Entry.of(
        program.superDecIds(target).contains(Magic.Sealed),
        program.isInlineDec(target),
        singletons.contains(target),
        program.superDecIds(target).contains(Magic.HasIdentity),
        implsOf(program, target, values),
        forwards.getOrDefault(target, List.of()))))));
  }

  /// Every plain forward the lowered program holds, by the type that writes it.
  ///
  /// A forward is a method whose whole body is one call on its own first parameter, with every
  /// argument a name the receiver already carries. Nothing else can be read off the receiver at
  /// a call site, so nothing else is recorded.
  private static Map<Id.DecId, List<Forward>> forwardsOf(MIR.Program mir) {
    Map<Id.DecId, List<Forward>> out = new java.util.LinkedHashMap<>();
    mir.pkgs().forEach(pkg -> pkg.funs().forEach(fun ->
      forwardOf(fun).ifPresent(f ->
        out.computeIfAbsent(fun.name().d(), _ -> new java.util.ArrayList<>()).add(f))));
    return out;
  }

  /// `fun` as a forward, or empty where its body is anything else.
  private static Optional<Forward> forwardOf(MIR.Fun fun) {
    if (fun.args().isEmpty()) { return Optional.empty(); }
    if (!(unwrap(fun.body()) instanceof MIR.MCall call)) { return Optional.empty(); }
    // The call must be on this method's own first declared parameter: a fun takes its
    // parameters, then its receiver, then its captures.
    if (!(unwrap(call.recv()) instanceof MIR.X recv)) { return Optional.empty(); }
    if (!recv.name().equals(fun.args().getFirst().name())) { return Optional.empty(); }
    var args = new java.util.ArrayList<String>();
    for (var arg : call.args()) {
      if (!(unwrap(arg) instanceof MIR.X x)) { return Optional.empty(); }
      args.add(x.name());
    }
    return Optional.of(new Forward(
      fun.name().m().toString(), fun.name().mdf().toString(), call.name().toString(), args));
  }

  /// An expression with the wrappers a pass put around it removed.
  private static MIR.E unwrap(MIR.E e) {
    return switch (e) {
      case MIR.Box box -> unwrap(box.inner());
      case MIR.Block block -> unwrap(block.original());
      default -> e;
    };
  }

  /// The types the lowered program makes as an object literal capturing nothing. The runtime
  /// gives such a value a static header, so no reference count follows it.
  private static Set<Id.DecId> singletonsOf(MIR.Program mir) {
    var found = new LinkedHashSet<Id.DecId>();
    mir.pkgs().forEach(pkg -> pkg.defs().forEach((name, def) ->
      def.singletonInstance()
        .filter(k -> k.captures().isEmpty())
        .ifPresent(_ -> found.add(name))));
    return found;
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

  /// The file as JSON: an object whose keys are {@link Id.DecId#toString} names, of the form
  /// `pkg.Short/arity`, and whose values are the entries.
  public String write() {
    Map<String, Entry> doc = Mapper.of(out ->
      entries.forEach((target, entry) -> out.put(target.toString(), entry)));
    return IoErr.of(() -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
  }

  /// The entries of every package of `pkgs`, joined. Each package writes the types it declares
  /// alone, so no two files name the same target.
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

  /// A method name this file wrote, of the form `name/arity`, or empty when the text is not one.
  private static Optional<Id.MethName> parseMethName(String text) {
    var slash = text.lastIndexOf('/');
    if (slash <= 0) { return Optional.empty(); }
    try {
      return Optional.of(
        new Id.MethName(text.substring(0, slash), Integer.parseInt(text.substring(slash + 1))));
    } catch (NumberFormatException e) { return Optional.empty(); }
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
