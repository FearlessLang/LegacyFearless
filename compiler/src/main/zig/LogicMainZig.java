package main.zig;

import ast.Program;
import codegen.MIR;
import codegen.MIRInjectionVisitor;
import codegen.optimisations.DevirtualiseByRTA;
import codegen.optimisations.OptimisationBuilder;
import codegen.zig.ZigBuildOpts;
import codegen.zig.ZigCompiler;
import codegen.zig.ZigMagicImpls;
import codegen.zig.ZigProgram;
import main.CompilerFrontEnd.Verbosity;
import main.FullLogicMain;
import main.InputOutput;
import program.typesystem.TsT;

import main.java.HDCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public interface LogicMainZig extends FullLogicMain<ZigProgram> {
  /// Keys the cached package type info, which decides whether codegen skips a package. That
  /// info is found by a walk of the whole cached-base tree, so a subdirectory cannot separate
  /// two configurations the way {@link ZigCompiler#versionedCacheDir} does. Base holds
  /// VPF-parallelisable calls and so generates different Zig with and without VPF, and each
  /// configuration needs its own name: otherwise a `--no-vpf` build finds the type info of a
  /// VPF build, skips base, and has no Zig for it.
  @Override default String backendName() { return buildOpts().vpfEnabled() ? "zig" : "zig-novpf"; }
  Path executablePath();
  void setExecutablePath(Path path);

  /// Optimisation mode, VPF, stack traces and more. No cross-backend interface shows it.
  default ZigBuildOpts buildOpts() { return ZigBuildOpts.DEFAULT; }

  @Override default void cachePackageTypes(Program program) {
    var versionedDir = new ZigCompiler(verbosity(), io(), buildOpts()).versionedCacheDir();
    var baseDecs = program.ds().values().stream()
      .filter(d -> ZigCompiler.isCacheablePackage(d.name().pkg()))
      .collect(Collectors.groupingBy(d -> d.name().pkg()));
    baseDecs.forEach((pkg, decs) ->
      new HDCache(versionedDir, program, backendName()).cacheTypeInfo(pkg, decs));
    ZigCompiler.cleanOldVersions(versionedDir.getParent());
  }

  @Override default MIR.Program lower(Program program, ConcurrentHashMap<Long, TsT> resolvedCalls) {
    var mir = new MIRInjectionVisitor(cachedPkg(), program, resolvedCalls).visitProgram();
    var magic = new ZigMagicImpls(null, null, mir.p());
    var devirtualise = new DevirtualiseByRTA(magic, ZigCompiler::isCacheablePackage);
    var res = new OptimisationBuilder(magic)
      .withBoolIfOptimisation()
      .withBoxingOptimisation()
      .withOptimisation(devirtualise)
      .run(mir);
    if (verbosity().printCodegen()) {
      System.err.println("[devirtualise] " + devirtualise.rewrittenCalls()
        + " call sites, over " + devirtualise.targets().size() + " concrete types");
    }
    return res;
  }

  @Override default ZigProgram codeGeneration(MIR.Program mir) {
    var compiler = new ZigCompiler(verbosity(), io(), buildOpts());
    var cachedContent = compiler.loadCachedPackages(mir);
    cachedPkg().addAll(cachedContent.keySet());
    return ZigProgram.of(io().entry(), mir, cachedPkg(), cachedContent, buildOpts().vpfEnabled());
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
      public InputOutput io() { return io; }
      public HashSet<String> cachedPkg() { return cachedPkg; }
      public Verbosity verbosity() { return verbosity; }
      public Path executablePath() { return exePath; }
      public void setExecutablePath(Path path) { exePath = path; }
      public ZigBuildOpts buildOpts() { return buildOpts; }
    };
  }
}
