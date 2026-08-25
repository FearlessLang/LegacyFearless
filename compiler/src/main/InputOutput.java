package main;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.stream.IntStream;

import codegen.java.JavaFile;
import parser.Parser;
import utils.IoErr;
import utils.OsCache;
import utils.ResolveResource;

public interface InputOutput{
  String entry();
  List<String> commandLineArguments();
  Path baseDir();
  Path magicDir();
  Path output();//where we put temp .class
  Path cachedBase(); //where we save base, can be the same of output
  List<Parser> cachedFiles();
  //here since it can be derived from output+cachedBase
  List<Parser> inputFiles();
  //here instead of 'input'
  Path defaultAliases();

  default List<Parser> baseFiles(){//crucially, this method is lazy
    return InputOutputHelper.loadFiles(baseDir(),".fear");
  }
  default List<JavaFile> magicFiles(){//crucially, this method is lazy
    return InputOutputHelper.readMagicFiles(magicDir());
  }

  default String generateAliases() {
    return ResolveResource.read(this.defaultAliases());
  }

  record FieldsInputOutput(
    String entry,
    List<String> commandLineArguments,
    Path baseDir,
    Path magicDir,
    List<Parser> inputFiles,
    Path output,
    Path cachedBase,
    List<Parser> cachedFiles,
    Path defaultAliases
  ) implements InputOutput {}

  static InputOutput trash(boolean isImm) {
    return new FieldsInputOutput(
      null,
      List.of(),
      null,
      null,
      List.of(),
      null,
      null,
      List.of(),
      isImm ? ResolveResource.asset("/default-imm-aliases.fear") : ResolveResource.asset("/default-aliases.fear")
    );
  }
  static InputOutput userFolder(String entry, List<String> commandLineArguments, Path userFolder) {
    Path output= userFolder.resolve("out");
    List<Parser> inputFiles= InputOutputHelper.loadInputFiles(userFolder);
    List<Parser> cachedFiles= InputOutputHelper.loadCachedFiles(output);
    return new FieldsInputOutput(
      entry,
      commandLineArguments,
      ResolveResource.asset("/base"),
      ResolveResource.asset("/rt"),
      inputFiles,
      output,
      output,
      cachedFiles,
      ResolveResource.asset("/default-aliases.fear")
    );
  }
  static InputOutput userFolderImm(String entry, List<String> commandLineArguments, Path userFolder) {
    Path output= userFolder.resolve("out");
    List<Parser> inputFiles= InputOutputHelper.loadInputFiles(userFolder);
    List<Parser> cachedFiles= InputOutputHelper.loadCachedFiles(output);
    return new FieldsInputOutput(
      entry,
      commandLineArguments,
      ResolveResource.asset("/immBase"),
      ResolveResource.asset("/immRt"),
      inputFiles,
      output,
      output,
      cachedFiles,
      ResolveResource.asset("/default-imm-aliases.fear")
    );
  }
  /// IO for the FeaRT (Zig) backend, with the base-library cache in the shared
  /// per-user cache directory rather than the project's out/ — the compiled base
  /// is project-independent, so it is built once per machine per library variant
  /// (per cache version: bumping ZigCompiler.ZIG_CACHE_VERSION discards it).
  static InputOutput userFolderZig(String entry, List<String> commandLineArguments, Path userFolder, boolean isImm) {
    Path output= userFolder.resolve("out");
    Path cachedBase= feartCachedBase(isImm);
    List<Parser> inputFiles= InputOutputHelper.loadInputFiles(userFolder);
    List<Parser> cachedFiles= InputOutputHelper.loadCachedFiles(cachedBase);
    return new FieldsInputOutput(
      entry,
      commandLineArguments,
      ResolveResource.asset(isImm ? "/immBase" : "/base"),
      ResolveResource.asset(isImm ? "/immRt" : "/rt"),
      inputFiles,
      output,
      cachedBase,
      cachedFiles,
      ResolveResource.asset(isImm ? "/default-imm-aliases.fear" : "/default-aliases.fear")
    );
  }
  /// Where the FeaRT backend keeps everything a {@link CompilationUnit} writes, one directory
  /// per library variant, shared by every project on the machine.
  static Path feartCachedBase(boolean isImm) {
    return OsCache.root().resolve("feart").resolve(isImm ? "immBase" : "base");
  }

  /// IO for a {@link CompilationUnit} build. It has no project: `inputFiles` is empty, so
  /// {@link LogicMain#parse} reads the unit's own sources through {@link #baseFiles}, and the
  /// cached files are those of the units before it alone, so nothing of this unit is read back
  /// from an earlier build of it.
  ///
  /// `versionedCacheDir` is where a cached package writes its type information, and its position
  /// under `cachedBase` is what names the package a `pkgInfo` file belongs to.
  static InputOutput unitZig(CompilationUnit unit, Path versionedCacheDir, boolean isImm) {
    Path cachedBase= feartCachedBase(isImm);
    List<Parser> cachedFiles= InputOutputHelper.loadCachedFiles(cachedBase).stream()
      .filter(p->CompilationUnit.anyContains(
        unit.dependencies(), InputOutputHelper.pkgOf(versionedCacheDir, p.fileName())))
      .toList();
    return new FieldsInputOutput(
      null,
      List.of(),
      ResolveResource.asset(isImm ? "/immBase" : "/base"),
      ResolveResource.asset(isImm ? "/immRt" : "/rt"),
      List.of(),
      cachedBase,
      cachedBase,
      cachedFiles,
      ResolveResource.asset(isImm ? "/default-imm-aliases.fear" : "/default-aliases.fear")
    );
  }
  static InputOutput programmatic(
    String entry,
    List<String> commandLineArguments,
    List<String> files,
    Path output,
    Path cachedBase
  ) {
    List<Parser> inputFiles= IntStream.range(0,files.size())
      .mapToObj(i->new Parser(Path.of("Dummy"+i+".fear"),files.get(i)))
      .toList();
    List<Parser> cachedFiles= InputOutputHelper.loadCachedFiles(cachedBase);
    return new FieldsInputOutput(
      entry,
      commandLineArguments,
      ResolveResource.asset("/base"),
      ResolveResource.asset("/rt"),
      inputFiles,
      output,
      cachedBase,//ResolveResource.of("/cachedBase"),
      cachedFiles,
      ResolveResource.asset("/default-aliases.fear")
    );
  }
  static InputOutput programmaticImm(
    String entry,
    List<String> commandLineArguments,
    List<String> files,
    Path output,
    Path cachedBase
  ) {
    List<Parser> inputFiles= IntStream.range(0,files.size())
            .mapToObj(i->new Parser(Path.of("Dummy"+i+".fear"),files.get(i)))
            .toList();
    List<Parser> cachedFiles= InputOutputHelper.loadCachedFiles(cachedBase);
    return new FieldsInputOutput(
      entry,
      commandLineArguments,
      ResolveResource.asset("/immBase"),
      ResolveResource.asset("/immRt"),
      inputFiles,
      output,
      cachedBase,//ResolveResource.of("/cachedImmBase"),
      cachedFiles,
      ResolveResource.asset("/default-imm-aliases.fear")
    );
  }
  //This will only work outside of the jar
  static InputOutput programmaticAuto(List<String> files){
    return programmaticAuto("test.Test",files);
  }
  static InputOutput programmaticAuto(String startPoint,List<String> files){
    var workingDir = ResolveResource.freshTmpPath();
    IoErr.of(()->Files.createDirectories(workingDir));
    return programmatic(startPoint, List.of(),files,
      workingDir,
      ResolveResource.artefact("/cachedBase"));
  }
  static InputOutput programmaticAuto(List<String> files, List<String> args){
    var workingDir = ResolveResource.freshTmpPath();
    IoErr.of(()->Files.createDirectories(workingDir));
    return programmatic("test.Test", args, files,
      workingDir,
      ResolveResource.artefact("/cachedBase"));
  }
}
class InputOutputHelper{
  /// The package a cached file belongs to, read from where it sits under `cacheDir`: the
  /// directories between the two are the parts of the package name. Empty for a file that is
  /// not under `cacheDir`, which no package pattern matches.
  static String pkgOf(Path cacheDir, Path file) {
    if (!file.startsWith(cacheDir)) { return ""; }
    var rel = cacheDir.relativize(file).getParent();
    if (rel == null) { return ""; }
    return StreamSupport.stream(rel.spliterator(), false)
      .map(Path::toString)
      .collect(Collectors.joining("."));
  }
  static List<Parser> loadInputFiles(Path root) {
    return loadFiles(root,".fear");
  }
  static List<Parser> loadCachedFiles(Path root) {
    IoErr.of(()->Files.createDirectories(root));
    return loadFiles(root,".txt");
  }
  static List<Parser> loadFiles(Path root,String endsWith) {
    return IoErr.of(()->{try(var fs = Files.walk(root)) {
      return fs
        .filter(Files::isRegularFile)
        .filter(p->p.getFileName().toString().endsWith(endsWith))
        //.map(p->{System.out.println(p); return p;})
        .map(p->new Parser(p, ResolveResource.read(p)))
        .toList();
    }});
  }
  public static List<JavaFile> readMagicFiles(Path root){
    List<Parser> files= loadFiles(root,".java");
    return files.stream()
      .map(p->new JavaFile(p.fileName(),p.content()))
      .toList();
  }
}