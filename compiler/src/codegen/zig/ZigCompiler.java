package codegen.zig;

import codegen.MIR;
import main.CompilerFrontEnd;
import main.InputOutput;
import utils.Bug;
import utils.DeleteDir;
import utils.IoErr;
import utils.OsCache;
import utils.ResolveResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record ZigCompiler(CompilerFrontEnd.Verbosity verbosity, InputOutput io, ZigBuildOpts opts) {
  static final int ZIG_CACHE_VERSION = 1;

  public ZigCompiler(CompilerFrontEnd.Verbosity verbosity, InputOutput io) {
    this(verbosity, io, ZigBuildOpts.DEFAULT);
  }

  private Path cacheBaseDir() { return io.cachedBase().resolve("zig-cache"); }
  public Path versionedCacheDir() { return cacheBaseDir().resolve("v" + ZIG_CACHE_VERSION); }
  // Shared, content-addressed Zig caches: stay warm across compiles and are safe
  // under concurrent builds (Zig locks them internally). The compiled runtime
  // lives here, so a per-program rebuild only recompiles the changed program.
  // They live in the per-user cache, not under io.cachedBase(): they are
  // machine-global by nature, grow into the gigabytes, and in the test harness
  // io.cachedBase() sits inside target/classes, which is jarred wholesale.
  private Path zigCacheDir() { return OsCache.root().resolve("zig-cache/local"); }
  private Path zigGlobalCacheDir() { return OsCache.root().resolve("zig-cache/global"); }
  // Per-compile build tree + output, under io.output() like the Java backend's
  // generated classes: the program source and binary differ per compile, so they
  // must not share a fixed path across concurrent test forks.
  private Path workDir() { return io.output().resolve("zig-build"); }
  private Path zigOutDir(Path workDir) { return workDir.resolve("zig-out"); }

  /** Load cached .zig content for base packages. Returns pkgName→zigContent for cache hits. */
  public Map<String, String> loadCachedPackages(MIR.Program program) {
    var dir = versionedCacheDir();
    if (!Files.isDirectory(dir)) { return Map.of(); }
    var cached = new HashMap<String, String>();
    for (var pkg : program.pkgs()) {
      var name = pkg.name();
      if (!(name.equals("base") || name.startsWith("base."))) { continue; }
      var file = dir.resolve(name.replace(".", "_") + ".zig");
      if (Files.exists(file)) {
        cached.put(name, IoErr.of(() -> Files.readString(file)));
      }
    }
    if (verbosity.printCodegen() && !cached.isEmpty()) {
      System.out.println("Zig codegen cache hit for: " + cached.keySet());
    }
    return cached;
  }
  /// The FeaRT runtime source tree. Normally the copy bundled with the compiler
  /// (assets/feaRT, i.e. /feaRT inside the jar); FEART_ROOT overrides it so the
  /// runtime can be developed against a checkout without rebuilding the compiler.
  private static Path feartRoot() {
    var envPath = System.getenv("FEART_ROOT");
    if (envPath != null) { return Path.of(envPath); }
    var bundled = ResolveResource.asset("/feaRT");
    if (Files.isDirectory(bundled)) { return bundled; }
    throw Bug.of("Cannot find the bundled FeaRT runtime at " + bundled + ". Set the FEART_ROOT environment variable to a FeaRT source tree.");
  }

  public Path compile(ZigProgram program) {
    var workDir = workDir();
    var outDir = zigOutDir(workDir);
    if (verbosity.printCodegen()) {
      System.out.println("Zig build directory: " + workDir);
    }
    IoErr.of(() -> {
      Files.createDirectories(workDir);
      var srcDir = workDir.resolve("src");
      var genDir = srcDir.resolve("generated");
      Files.createDirectories(genDir);

      copyRuntime(workDir);

      for (var entry : program.packageFiles().entrySet()) {
        var fileName = entry.getKey().replace(".", "_") + ".zig";
        Files.writeString(genDir.resolve(fileName), entry.getValue());
      }

      Files.writeString(srcDir.resolve("main.zig"), program.mainFile());
      Files.writeString(workDir.resolve("build.zig"), buildZig());
      Files.writeString(workDir.resolve("build.zig.zon"), buildZigZon());

      runZigBuild(workDir, zigCacheDir(), zigGlobalCacheDir(), outDir);

      // Save base package files to versioned cache dir
      saveCachedPackages(program);

      return null;
    });
    return outDir.resolve("bin/fearless-app");
  }

  public static void cleanOldVersions(Path cacheBase, Path keep) {
    if (!Files.isDirectory(cacheBase)) { return; }
    IoErr.of(() -> {
      try (var dirs = Files.list(cacheBase)) {
        dirs.filter(Files::isDirectory)
          .filter(d -> d.getFileName().toString().matches("v\\d+"))
          .filter(d -> !d.equals(keep))
          .forEach(DeleteDir::of);
      }
    });
  }

  private void saveCachedPackages(ZigProgram program) {
    var dir = versionedCacheDir();
    IoErr.of(() -> Files.createDirectories(dir));
    for (var entry : program.packageFiles().entrySet()) {
      if (!(entry.getKey().equals("base") || entry.getKey().startsWith("base."))) { continue; }
      var fileName = entry.getKey().replace(".", "_") + ".zig";
      IoErr.of(() -> Files.writeString(dir.resolve(fileName), entry.getValue()));
    }
  }

  private void copyRuntime(Path workDir) throws IOException {
    var feartSrc = feartRoot().resolve("src/runtime");
    var targetRuntime = workDir.resolve("src/runtime");

    // Copy runtime directory tree
    copyTree(feartSrc, targetRuntime);

    // Copy lib/ directory (contains zig-build-libgc)
    var feartLib = feartRoot().resolve("lib");
    var targetLib = workDir.resolve("lib");
    if (Files.isDirectory(feartLib)) {
      copyTree(feartLib, targetLib);
    }
  }

  private void copyTree(Path source, Path target) throws IOException {
    try (var walker = Files.walk(source)) {
      walker.forEach(src -> IoErr.of(() -> {
        // resolve via String: source may live in the jar's virtual file system
        var dest = target.resolve(source.relativize(src).toString());
        if (Files.isDirectory(src)) {
          Files.createDirectories(dest);
        } else {
          Files.createDirectories(dest.getParent());
          Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return null;
      }));
    }
  }

  private void runZigBuild(Path workDir, Path localCache, Path globalCache, Path outDir) throws IOException {
    var zig = ZigToolchain.resolve(OsCache.root().resolve("zig-toolchain"));
    var cmd = new java.util.ArrayList<>(java.util.List.of(
      zig.toAbsolutePath().toString(), "build",
      "-Doptimize=" + opts.optimizeMode(),
      "--cache-dir", localCache.toAbsolutePath().toString(),
      "--global-cache-dir", globalCache.toAbsolutePath().toString(),
      "--prefix", outDir.toAbsolutePath().toString()
    ));
    // The self-hosted (non-LLVM) linker cannot handle .sframe sections present in the
    // CRT objects of very new host glibc/gcc toolchains. Pinning a glibc version makes
    // zig build and link its own bundled CRT objects instead of using the host's.
    // Production builds keep a fully native target (LLD links .sframe fine, and a
    // native target keeps native CPU tuning for ReleaseFast).
    // glibc 2.34 keeps the binary runnable on older stable distros (RHEL 9, Ubuntu 22.04+, Debian 12+).
    if (!opts.useLlvm() && System.getProperty("os.name").toLowerCase().contains("linux")) {
      cmd.add("-Dtarget=native-native-gnu.2.34");
    }
    var pb = new ProcessBuilder(cmd)
      .directory(workDir.toFile())
      .redirectErrorStream(true);
    var process = pb.start();
    var output = new String(process.getInputStream().readAllBytes());
    int exitCode;
    try {
      exitCode = process.waitFor();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    if (exitCode != 0) {
      throw Bug.of("zig build failed (exit " + exitCode + "):\n" + output);
    }
    if (verbosity.printCodegen() && !output.isBlank()) {
      System.out.println("zig build output: " + output);
    }
  }

  private String buildZig() {
    var enableTracing = verbosity.printCodegen();
    return """
      const std = @import("std");
      pub fn build(b: *std.Build) void {
          const target = b.standardTargetOptions(.{});
          const optimize = b.standardOptimizeOption(.{});

          const log_trace = b.option(bool, "log_trace", "Enable trace ring buffer events (default: false)") orelse %s;
          const log_scheduling = b.option(bool, "log_scheduling", "Enable scheduling-related logging (default: false)") orelse false;
          const log_safety = b.option(bool, "log_safety", "Enable safety-related logging (default: false)") orelse %s;
          const log_dispatch = b.option(bool, "log_dispatch", "Enable dispatch/method resolution logging (default: false)") orelse false;
          const log_alloc_caching = b.option(bool, "log_alloc_caching", "Emit alloc-recycler miss events to the trace ring buffer (default: false).") orelse false;
          const track_allocs = b.option(bool, "track_allocs", "Record per-call-site allocation counts/bytes; dumps to FEART_ALLOCS_OUT (default ./feart-allocs.tsv) on exit. Slows execution significantly. (default: false)") orelse false;
          const trace_frames = b.option(bool, "trace_frames", "Push a per-call trace stack so an uncaught crash prints a Fearless stack trace (default: false)") orelse %s;
          const tokens_threshold = b.option(u32, "tokens_threshold", "Heartbeat promotion token threshold; lower forces more aggressive VPF promotion (default: 25_000_000)") orelse %d;
          const enable_vpf = b.option(bool, "enable_vpf", "Compile in the heartbeat/VPF automatic parallelism system (default: true)") orelse %s;

          const build_options = b.addOptions();
          build_options.addOption(bool, "log_scheduling", log_scheduling);
          build_options.addOption(bool, "log_safety", log_safety);
          build_options.addOption(bool, "log_dispatch", log_dispatch);
          build_options.addOption(bool, "log_trace", log_trace);
          build_options.addOption(bool, "log_alloc_caching", log_alloc_caching);
          build_options.addOption(bool, "track_allocs", track_allocs);
          build_options.addOption(bool, "trace_frames", trace_frames);
          build_options.addOption(u32, "tokens_threshold", tokens_threshold);
          build_options.addOption(bool, "enable_vpf", enable_vpf);

          const exe = b.addExecutable(.{
              .name = "fearless-app",
              .root_module = b.createModule(.{
                  .root_source_file = b.path("src/main.zig"),
                  .target = target,
                  .optimize = optimize,
                  .imports = &.{
                      .{ .name = "build_options", .module = build_options.createModule() },
                  },
              }),
          });

          const libgc = b.dependency("libgc", .{ .target = target, .optimize = optimize });
          const gc_lib = libgc.artifact("gc");
          const gc_include = gc_lib.getEmittedIncludeTree();

          const context_switch_lib = b.addLibrary(.{
              .linkage = .static,
              .name = "context_switch",
              .root_module = b.createModule(.{
                  .target = target,
                  .optimize = optimize,
              })
          });
          switch (target.result.cpu.arch) {
              .x86_64 => context_switch_lib.root_module.addAssemblyFile(b.path("src/runtime/context_switch_x86_64.S")),
              .aarch64 => context_switch_lib.root_module.addAssemblyFile(b.path("src/runtime/context_switch_aarch64.S")),
              else => {},
          }

          const c_libgc_tc = b.addTranslateC(.{
              .root_source_file = gc_include.path(b, "gc.h"),
              .target = target,
              .optimize = optimize,
              .link_libc = true,
          });
          c_libgc_tc.defineCMacro("GC_THREADS", "1");
          c_libgc_tc.defineCMacro("GC_PTHREADS", "1");
          c_libgc_tc.addIncludePath(gc_include);
          c_libgc_tc.addIncludePath(gc_include.path(b, "gc"));
          const c_libgc_mod = c_libgc_tc.createModule();
          c_libgc_mod.linkLibrary(gc_lib);

          exe.root_module.addImport("libgc", c_libgc_mod);
          exe.root_module.linkLibrary(context_switch_lib);

          exe.use_llvm = %b;
          exe.root_module.omit_frame_pointer = false;
          exe.root_module.strip = false;

          const exe_tests = b.addTest(.{
              .root_module = exe.root_module,
          });
          exe_tests.use_llvm = %b;

          const test_step = b.step("test", "Run tests");
          const run_exe_tests = b.addRunArtifact(exe_tests);
          test_step.dependOn(&run_exe_tests.step);

          b.installArtifact(exe);

          const run_step = b.step("run", "Run the app");
          const run_cmd = b.addRunArtifact(exe);
          run_step.dependOn(&run_cmd.step);
          run_cmd.step.dependOn(b.getInstallStep());
          if (b.args) |args| {
              run_cmd.addArgs(args);
          }
      }
      """.formatted(
        enableTracing || opts.logTrace(),
        enableTracing,
        opts.traceFrames(),
        Optional.ofNullable(opts.tokensThreshold()).orElse(25_000_000),
        opts.vpfEnabled(),
        opts.useLlvm(),
        opts.useLlvm());
  }

  private String buildZigZon() {
    return """
      .{
          .name = .fearless_app,
          .version = "0.0.0",
          .fingerprint = 0xb506340ff5c420d3,
          .minimum_zig_version = "0.16.0",
          .dependencies = .{
              .libgc = .{ .path = "lib/zig-build-libgc" }
          },
          .paths = .{
              "build.zig",
              "build.zig.zon",
              "src",
          },
      }
      """;
  }
}
