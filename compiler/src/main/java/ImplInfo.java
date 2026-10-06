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

public record ImplInfo(Map<Id.DecId, Entry> entries) {
  public static final ImplInfo EMPTY = new ImplInfo(Map.of());

  private static final ObjectMapper JSON = new ObjectMapper();

  public record Entry(
    boolean sealed,
    boolean inlineDec,
    boolean singleton,
    boolean hasIdentity,
    List<String> impls,
    List<Forward> forwards,
    List<String> captures
  ) {
    public Entry {
      forwards = forwards == null ? List.of() : List.copyOf(forwards);
      captures = captures == null ? null : List.copyOf(captures);
    }

    public static Entry of(
        boolean sealed,
        boolean inlineDec,
        boolean singleton,
        boolean hasIdentity,
        Set<Id.DecId> impls,
        List<Forward> forwards,
        List<String> captures
    ) {
      return new Entry(
        sealed,
        inlineDec,
        singleton,
        hasIdentity,
        impls.stream().map(Id.DecId::toString).toList(),
        forwards,
        captures
      );
    }

    /// The ordered capture names of the object literal of this declaration. Empty when no literal
    /// was seen, so the layout is unknown. A present empty list is a known layout with no captures.
    public Optional<List<String>> captureFields() { return Optional.ofNullable(captures); }

    public List<Id.DecId> implIds() {
      return impls.stream().flatMap(name -> parseDecId(name).stream()).toList();
    }

    public Optional<Forward> forward(Id.MethName name, Mdf mdf) {
      return forwards.stream()
        .filter(f -> f.on.equals(name.toString()) && f.mdf.equals(mdf.toString()))
        .findFirst();
    }
  }

  public record Forward(String on, String mdf, String to, List<String> args) {
    public Forward { args = args == null ? List.of() : List.copyOf(args); }

    public Optional<Id.MethName> toName() { return parseMethName(to); }
  }

  public static String fileName(String backend) { return "implInfo." + backend + ".json"; }

  public static ImplInfo of(String pkgName, ast.Program program, MIR.Program mir) {
    var literals = valuesOf(mir);
    var values = literals.keySet().stream()
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
        forwards.getOrDefault(target, List.of()),
        capturesOf(literals.get(target)))))));
  }

  private static List<String> capturesOf(MIR.CreateObj literal) {
    if (literal == null) { return null; }
    return literal.captures().stream().map(MIR.X::name).toList();
  }

  private static Map<Id.DecId, List<Forward>> forwardsOf(MIR.Program mir) {
    Map<Id.DecId, List<Forward>> out = new java.util.LinkedHashMap<>();
    mir.pkgs().forEach(pkg -> pkg.funs().forEach(fun ->
      forwardOf(fun).ifPresent(f ->
        out.computeIfAbsent(fun.name().d(), _ -> new java.util.ArrayList<>()).add(f))));
    return out;
  }

  /// The names a function takes from the captures of its receiver. A function takes its declared
  /// parameters, then its receiver, then the captures of its receiver. The receiver and the
  /// declared parameters are not captures.
  public static Set<String> receiverCaptures(MIR.Fun fun) {
    return fun.args().stream()
      .skip(fun.name().m().num() + 1L)
      .map(MIR.X::name)
      .collect(Collectors.toUnmodifiableSet());
  }

  /// The forward `fun` makes, where it is a call on its first parameter that passes only
  /// captures of its receiver.
  private static Optional<Forward> forwardOf(MIR.Fun fun) {
    if (fun.args().isEmpty()) { return Optional.empty(); }
    if (!(unwrap(fun.body()) instanceof MIR.MCall call)) { return Optional.empty(); }
    if (!(unwrap(call.recv()) instanceof MIR.X recv)) { return Optional.empty(); }
    if (!recv.name().equals(fun.args().getFirst().name())) { return Optional.empty(); }
    var carried = receiverCaptures(fun);
    var args = new java.util.ArrayList<String>();
    for (var arg : call.args()) {
      if (!(unwrap(arg) instanceof MIR.X x)) { return Optional.empty(); }
      if (!carried.contains(x.name())) { return Optional.empty(); }
      args.add(x.name());
    }
    return Optional.of(new Forward(
      fun.name().m().toString(), fun.name().mdf().toString(), call.name().toString(), args));
  }

  private static MIR.E unwrap(MIR.E e) {
    return switch (e) {
      case MIR.Box box -> unwrap(box.inner());
      case MIR.Block block -> unwrap(block.original());
      default -> e;
    };
  }

  private static Set<Id.DecId> singletonsOf(MIR.Program mir) {
    var found = new LinkedHashSet<Id.DecId>();
    mir.pkgs().forEach(pkg -> pkg.defs().forEach((name, def) ->
      def.singletonInstance()
        .filter(k -> k.captures().isEmpty())
        .ifPresent(_ -> found.add(name))));
    return found;
  }

  private static Stream<Id.DecId> targetsOf(String pkgName, ast.Program program) {
    return Stream.concat(program.ds().keySet().stream(), program.inlineDs().keySet().stream())
      .filter(d -> d.pkg().equals(pkgName))
      .distinct();
  }

  private static Set<Id.DecId> implsOf(ast.Program program, Id.DecId target, Set<Id.DecId> values) {
    return values.stream()
      .filter(value -> program.superDecIds(value).contains(target))
      .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Map<Id.DecId, MIR.CreateObj> valuesOf(MIR.Program mir) {
    var found = new java.util.LinkedHashMap<Id.DecId, MIR.CreateObj>();
    MIRVisitor<Void> walk = new CreateObjCollector(found);
    mir.pkgs().forEach(pkg -> {
      pkg.defs().values().forEach(def -> def.singletonInstance().ifPresent(k -> k.accept(walk, true)));
      pkg.funs().forEach(fun -> fun.body().accept(walk, true));
    });
    return found;
  }

  private record CreateObjCollector(Map<Id.DecId, MIR.CreateObj> found) implements MIRVisitor<Void> {
    @Override public Void visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
      createObj.t().name().ifPresent(name -> found.putIfAbsent(name, createObj));
      return null;
    }
    @Override public Void visitX(MIR.X x, boolean checkMagic) { return null; }
    @Override public Void visitMCall(MIR.MCall call, boolean checkMagic) {
      call.recv().accept(this, checkMagic);
      call.args().forEach(arg -> arg.accept(this, checkMagic));
      return null;
    }
  }

  public String write() {
    Map<String, Entry> doc = Mapper.of(out ->
      entries.forEach((target, entry) -> out.put(target.toString(), entry)));
    return IoErr.of(() -> JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
  }

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

  private static Optional<Id.MethName> parseMethName(String text) {
    var slash = text.lastIndexOf('/');
    if (slash <= 0) { return Optional.empty(); }
    try {
      return Optional.of(
        new Id.MethName(text.substring(0, slash), Integer.parseInt(text.substring(slash + 1))));
    } catch (NumberFormatException e) { return Optional.empty(); }
  }

  private static Optional<Id.DecId> parseDecId(String text) {
    var slash = text.lastIndexOf('/');
    if (slash <= 0) { return Optional.empty(); }
    try {
      return Optional.of(
        new Id.DecId(text.substring(0, slash), Integer.parseInt(text.substring(slash + 1))));
    } catch (NumberFormatException e) { return Optional.empty(); }
  }

  public Optional<Entry> get(Id.DecId target) { return Optional.ofNullable(entries.get(target)); }

  public boolean knows(Id.DecId target) { return entries.containsKey(target); }
}
