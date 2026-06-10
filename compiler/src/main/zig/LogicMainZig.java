package main.zig;

import ast.Program;
import codegen.MIR;
import codegen.MIRInjectionVisitor;
import codegen.optimisations.OptimisationBuilder;
import codegen.zig.ZigCompiler;
import codegen.zig.ZigMagicImpls;
import codegen.zig.ZigProgram;
import main.CompilerFrontEnd.Verbosity;
import main.FullLogicMain;
import main.InputOutput;
import program.typesystem.TsT;

import main.java.HDCache;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public interface LogicMainZig extends FullLogicMain<ZigProgram> {
  @Override default String backendName() { return "zig"; }
  Path executablePath();
  void setExecutablePath(Path path);

  /** Optional heartbeat-promotion threshold override forwarded to the Zig build
   * (null = use the runtime default). Tests use it to force aggressive VPF
   * promotion; not exposed on any cross-backend interface. */
  default Integer tokensThreshold() { return null; }

  /** When true, build with the self-hosted backend + Debug instead of LLVM +
   * ReleaseFast. ~9x faster to compile; used by the codegen test harness only. */
  default boolean fastBuild() { return false; }

  @Override default void cachePackageTypes(Program program) {
    var versionedDir = new ZigCompiler(verbosity(), io()).versionedCacheDir();
    var baseDecs = program.ds().values().stream()
      .filter(d -> {
        var pkg = d.name().pkg();
        return pkg.equals("base") || pkg.startsWith("base.");
      })
      .collect(Collectors.groupingBy(d -> d.name().pkg()));
    baseDecs.forEach((pkg, decs) ->
      new HDCache(versionedDir, program, backendName()).cacheTypeInfo(pkg, decs));
    ZigCompiler.cleanOldVersions(versionedDir.getParent(), versionedDir);
  }

  @Override default MIR.Program lower(Program program, ConcurrentHashMap<Long, TsT> resolvedCalls) {
    var mir = new MIRInjectionVisitor(cachedPkg(), program, resolvedCalls).visitProgram();
    var magic = new ZigMagicImpls(null, null, mir.p());
    return new OptimisationBuilder(magic)
      .withBoolIfOptimisation()
      .withBoxingOptimisation()
      .run(mir);
  }

  @Override default ZigProgram codeGeneration(MIR.Program mir) {
    var compiler = new ZigCompiler(verbosity(), io());
    var cachedContent = compiler.loadCachedPackages(mir);
    cachedPkg().addAll(cachedContent.keySet());
    return ZigProgram.of(io().entry(), mir, cachedPkg(), cachedContent);
  }

  @Override default void compileBackEnd(ZigProgram src) {
    var compiler = new ZigCompiler(verbosity(), io(), tokensThreshold(), fastBuild());
    var exePath = compiler.compile(src);
    setExecutablePath(exePath);
  }

  @Override default ProcessBuilder execution(ZigProgram exe) {
    return new ProcessBuilder(executablePath().toString());
  }

  static LogicMainZig of(InputOutput io, Verbosity verbosity) {
    return of(io, verbosity, null);
  }

  static LogicMainZig of(InputOutput io, Verbosity verbosity, Integer tokensThreshold) {
    return of(io, verbosity, tokensThreshold, false);
  }

  static LogicMainZig of(InputOutput io, Verbosity verbosity, Integer tokensThreshold, boolean fastBuild) {
    var cachedPkg = new HashSet<String>();
    return new LogicMainZig() {
      private Path exePath;
      public InputOutput io() { return io; }
      public HashSet<String> cachedPkg() { return cachedPkg; }
      public Verbosity verbosity() { return verbosity; }
      public Path executablePath() { return exePath; }
      public void setExecutablePath(Path path) { exePath = path; }
      public Integer tokensThreshold() { return tokensThreshold; }
      public boolean fastBuild() { return fastBuild; }
    };
  }
}
