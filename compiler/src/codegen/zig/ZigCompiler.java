package codegen.zig;

import codegen.MIR;
import main.CompilerFrontEnd;
import main.InputOutput;
import utils.Bug;
import utils.DeleteOnExit;
import utils.IoErr;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record ZigCompiler(CompilerFrontEnd.Verbosity verbosity, InputOutput io, Integer tokensThreshold) {
  static final int ZIG_CACHE_VERSION = 1;

  public ZigCompiler(CompilerFrontEnd.Verbosity verbosity, InputOutput io) {
    this(verbosity, io, null);
  }

  private Path cacheBaseDir() { return io.cachedBase().resolve("zig-cache"); }
  public Path versionedCacheDir() { return cacheBaseDir().resolve("v" + ZIG_CACHE_VERSION); }
  private Path zigCacheDir() { return cacheBaseDir().resolve("zig-cache/local"); }
  private Path zigGlobalCacheDir() { return cacheBaseDir().resolve("zig-cache/global"); }
  private Path zigOutDir() { return cacheBaseDir().resolve("zig-out"); }
  private Path stableWorkDir() { return cacheBaseDir().resolve("zig-build"); }

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
  /** The absolute path to the feart runtime source tree (the experiments/feart directory). */
  private static Path feartRoot() {
    // Resolve from the location of this class or use an env var
    var envPath = System.getenv("FEART_ROOT");
    if (envPath != null) { return Path.of(envPath); }
    // Default: assume we're in the experiments directory structure
    // Try relative to working directory
    var candidates = new Path[]{
      Path.of("feart"),
      Path.of("../feart"),
      Path.of("../../feart"),
      Path.of("experiments/feart"),
    };
    for (var c : candidates) {
      if (Files.isDirectory(c)) { return c.toAbsolutePath(); }
    }
    throw Bug.of("Cannot find feart runtime. Set FEART_ROOT environment variable.");
  }

  public Path compile(ZigProgram program) {
    var workDir = stableWorkDir();
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
      Files.writeString(workDir.resolve("build.zig"), buildZig(verbosity.printCodegen(), Optional.ofNullable(tokensThreshold).orElse(25_000_000)));
      Files.writeString(workDir.resolve("build.zig.zon"), buildZigZon());

      runZigBuild(workDir, zigCacheDir(), zigGlobalCacheDir(), zigOutDir());

      // Save base package files to versioned cache dir
      saveCachedPackages(program);

      return null;
    });
    return zigOutDir().resolve("bin/fearless-app");
  }

  public static void cleanOldVersions(Path cacheBase, Path keep) {
    if (!Files.isDirectory(cacheBase)) { return; }
    IoErr.of(() -> {
      try (var dirs = Files.list(cacheBase)) {
        dirs.filter(Files::isDirectory)
          .filter(d -> d.getFileName().toString().matches("v\\d+"))
          .filter(d -> !d.equals(keep))
          .forEach(ZigCompiler::deleteTree);
      }
    });
  }

  private static void deleteTree(Path root) {
    IoErr.of(() -> {
      try (var walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder())
          .forEach(f -> IoErr.of(() -> Files.deleteIfExists(f)));
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
        var dest = target.resolve(source.relativize(src));
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
    var pb = new ProcessBuilder(
      "zig", "build",
      "-Doptimize=ReleaseFast",
      "-Dtrace_frames=true",
      "--cache-dir", localCache.toAbsolutePath().toString(),
      "--global-cache-dir", globalCache.toAbsolutePath().toString(),
      "--prefix", outDir.toAbsolutePath().toString()
    )
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

  private String buildZig(boolean enableTracing, int tokensThreshold) {
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
          const trace_frames = b.option(bool, "trace_frames", "Push a per-call trace stack so an uncaught crash prints a Fearless stack trace (default: false)") orelse false;
          const tokens_threshold = b.option(u32, "tokens_threshold", "Heartbeat promotion token threshold; lower forces more aggressive VPF promotion (default: 25_000_000)") orelse %d;

          const build_options = b.addOptions();
          build_options.addOption(bool, "log_scheduling", log_scheduling);
          build_options.addOption(bool, "log_safety", log_safety);
          build_options.addOption(bool, "log_dispatch", log_dispatch);
          build_options.addOption(bool, "log_trace", log_trace);
          build_options.addOption(bool, "log_alloc_caching", log_alloc_caching);
          build_options.addOption(bool, "track_allocs", track_allocs);
          build_options.addOption(bool, "trace_frames", trace_frames);
          build_options.addOption(u32, "tokens_threshold", tokens_threshold);

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

          exe.use_llvm = true;
          exe.root_module.omit_frame_pointer = false;
          exe.root_module.strip = false;

          const exe_tests = b.addTest(.{
              .root_module = exe.root_module,
          });
          exe_tests.use_llvm = true;

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
      """.formatted(enableTracing, enableTracing, tokensThreshold);
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
