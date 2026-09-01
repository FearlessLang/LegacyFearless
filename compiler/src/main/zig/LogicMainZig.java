package main.zig;

import ast.Program;
import codegen.MIR;
import codegen.MIRInjectionVisitor;
import codegen.optimisations.OptimisationBuilder;
import codegen.zig.ZigBuildOpts;
import codegen.zig.ZigCompiler;
import codegen.zig.ZigMagicImpls;
import codegen.zig.ZigProgram;
import main.CompilationUnit;
import main.CompilerFrontEnd.Verbosity;
import main.FullLogicMain;
import main.InputOutput;
import main.Main;
import program.typesystem.TsT;

import main.java.HDCache;
import main.java.ImplInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public interface LogicMainZig extends FullLogicMain<ZigProgram> {
  /// Keys the cached package type info, which decides whether codegen skips a package. That
  /// info is found by a walk of the whole cached-base tree, so a subdirectory cannot separate
  /// two configurations the way {@link ZigCompiler#versionedCacheDir} does. Base generates
  /// different Zig with and without VPF, so each configuration needs its own name: otherwise a
  /// `--no-vpf` build finds the type info of a VPF build, skips base, and has no Zig for it.
  @Override default String backendName() { return backendName(buildOpts()); }
  static String backendName(ZigBuildOpts opts) { return opts.vpfEnabled() ? "zig" : "zig-novpf"; }
  Path executablePath();
  void setExecutablePath(Path path);

  /// The lowered program, kept because {@link ImplInfo} reads the values it makes and the cache
  /// step runs after the backend has compiled.
  MIR.Program loweredProgram();
  void setLoweredProgram(MIR.Program mir);

  default ZigBuildOpts buildOpts() { return ZigBuildOpts.DEFAULT; }

  @Override default void cachePackageTypes(Program program) {
    var versionedDir = new ZigCompiler(verbosity(), io(), buildOpts()).versionedCacheDir();
    var baseDecs = program.ds().values().stream()
      .filter(d -> CompilationUnit.isCached(d.name().pkg()))
      .collect(Collectors.groupingBy(d -> d.name().pkg()));
    baseDecs.forEach((pkg, decs) -> {
      var cache = new HDCache(versionedDir, program, backendName());
      cache.cacheTypeInfo(pkg, decs);
      // Only a package lowered from source has bodies to count. A cached package holds
      // `pkgInfo` bodies, which are all `base.Abort!`, and a count taken from those is wrong
      // rather than merely low. Its file is already on disk from the run that built it.
      if (!cachedPkg().contains(pkg)) { cache.cacheImplInfo(pkg, loweredProgram()); }
    });
    ZigCompiler.cleanOldVersions(versionedDir.getParent());
  }

  /// The implementation counts of every cached package, as {@link ImplInfo} wrote them when
  /// that package was lowered from source.
  default ImplInfo cachedImplInfo() {
    var versionedDir = new ZigCompiler(verbosity(), io(), buildOpts()).versionedCacheDir();
    return ImplInfo.readAll(versionedDir, cachedPkg(), backendName());
  }

  @Override default MIR.Program lower(Program program, ConcurrentHashMap<Long, TsT> resolvedCalls) {
    var mir = new MIRInjectionVisitor(cachedPkg(), program, resolvedCalls).visitProgram();
    var magic = new ZigMagicImpls(null, null, mir.p(), null);
    var guarded = new codegen.optimisations.DevirtualiseGuarded(
      magic, cachedImplInfo(), cachedPkg());
    var selfRec = new codegen.optimisations.DirectSelfRecursion();
    var sums = new codegen.optimisations.SumMatchOptimisation(
      magic, cachedImplInfo(), cachedPkg());
    var res = new OptimisationBuilder(magic)
      .withBoolIfOptimisation()
      .withBoolShortCircuitOptimisation()
      .withOptimisation(sums)
      .withBoxingOptimisation()
      .withOptimisation(selfRec)
      .withOptimisation(guarded)
      .run(mir);
    setLoweredProgram(res);
    if (verbosity().printCodegen()) {
      System.err.println("[selfrec] " + selfRec.rewrittenCalls() + " recursive call sites");
      System.err.println("[sums] " + sums.rewrittenCalls() + " matcher call sites");
      System.err.println("[guarded] " + guarded.guardedCalls()
        + " call sites, over " + guarded.targets().size() + " concrete types");
    }
    return res;
  }

  @Override default ZigProgram codeGeneration(MIR.Program mir) {
    var compiler = new ZigCompiler(verbosity(), io(), buildOpts());
    var cachedContent = compiler.loadCachedPackages(mir);
    cachedPkg().addAll(cachedContent.keySet());
    if (io().entry() == null) {
      return ZigProgram.ofUnit(mir, cachedPkg(), cachedContent, buildOpts().vpfEnabled(), cachedImplInfo());
    }
    return ZigProgram.of(io().entry(), mir, cachedPkg(), cachedContent, buildOpts().vpfEnabled(), cachedImplInfo());
  }

  @Override default void cacheCodeGeneration(ZigProgram src) {
    new ZigCompiler(verbosity(), io(), buildOpts()).saveCachedPackages(src);
  }

  /// Builds every {@link CompilationUnit} whose artefacts are not already in the cache, one at
  /// a time and in order, so that a unit reads the units before it.
  ///
  /// Call this before the application's {@link InputOutput} exists. `cachedFiles` is read when
  /// an `InputOutput` is made, so a `pkgInfo` written after that point is missed by the
  /// compilation that needs it.
  static void buildMissingUnits(Verbosity verbosity, ZigBuildOpts buildOpts, boolean isImm) {
    var versionedDir = ZigCompiler.versionedCacheDir(InputOutput.feartCachedBase(isImm), buildOpts);
    for (var unit : CompilationUnit.all()) {
      if (isUnitCached(versionedDir, unit, backendName(buildOpts))) { continue; }
      LogicMainZig.of(InputOutput.unitZig(unit, versionedDir, isImm), verbosity, buildOpts).buildUnit();
      Main.resetAll();
    }
  }

  /// Whether `unit` already wrote its type information, which sits under its root package.
  private static boolean isUnitCached(Path versionedDir, CompilationUnit unit, String backend) {
    return java.nio.file.Files.isRegularFile(versionedDir
      .resolve(unit.name().replace(".", "/"))
      .resolve("pkgInfo." + backend + ".txt"));
  }

  @Override default void compileBackEnd(ZigProgram src) {
    var compiler = new ZigCompiler(verbosity(), io(), buildOpts());
    var exePath = compiler.compile(src);
    setExecutablePath(exePath);
  }

  @Override default ProcessBuilder execution(ZigProgram exe) {
    var cmd = new ArrayList<String>();
    cmd.add(executablePath().toString());
    cmd.addAll(io().commandLineArguments());
    return new ProcessBuilder(cmd);
  }

  static LogicMainZig of(InputOutput io, Verbosity verbosity) {
    return of(io, verbosity, ZigBuildOpts.DEFAULT);
  }

  static LogicMainZig of(InputOutput io, Verbosity verbosity, Integer tokensThreshold, boolean fastBuild) {
    assert fastBuild;
    return of(io, verbosity, ZigBuildOpts.forTests(tokensThreshold));
  }

  static LogicMainZig of(InputOutput io, Verbosity verbosity, ZigBuildOpts buildOpts) {
    var cachedPkg = new HashSet<String>();
    return new LogicMainZig() {
      private Path exePath;
      private MIR.Program lowered;
      public InputOutput io() { return io; }
      public HashSet<String> cachedPkg() { return cachedPkg; }
      public Verbosity verbosity() { return verbosity; }
      public Path executablePath() { return exePath; }
      public void setExecutablePath(Path path) { exePath = path; }
      public MIR.Program loweredProgram() { return lowered; }
      public void setLoweredProgram(MIR.Program mir) { lowered = mir; }
      public ZigBuildOpts buildOpts() { return buildOpts; }
    };
  }
}
