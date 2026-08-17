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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record ZigCompiler(CompilerFrontEnd.Verbosity verbosity, InputOutput io, ZigBuildOpts opts) {
  static final int ZIG_CACHE_VERSION = 1;

  public ZigCompiler(CompilerFrontEnd.Verbosity verbosity, InputOutput io) {
    this(verbosity, io, ZigBuildOpts.DEFAULT);
  }

  private Path cacheBaseDir() { return io.cachedBase().resolve("zig-cache"); }
  /// The base library holds VPF-parallelisable calls, so its generated Zig differs between a VPF
  /// build and a `--no-vpf` build. Each configuration gets its own cache directory. With a shared
  /// directory, a `--no-vpf` build takes VPF-shaped base code and loses the sequential baseline
  /// that it must measure. The split is at directory level, not at filename level, because
  /// {@link main.java.HDCache} also caches the package type info here. That type info decides if
  /// a package is cached at all, so the two must agree.
  public Path versionedCacheDir() {
    return cacheBaseDir().resolve(currentVersion() + (opts.vpfEnabled() ? "vpf" : "novpf"));
  }
  private static String currentVersion() { return "v" + ZIG_CACHE_VERSION + "-"; }
  /// The content-addressed build cache of zig (`--cache-dir`). Each test compiles into its own
  /// throwaway output dir, so the tests share one warm cache under target/.
  private Path zigCacheDir() {
    if (opts.fastTestBuild()) { return targetDir().resolve("fearless-zig-cache/local"); }
    return io.output().resolve("zig-cache/local");
  }
  /// The build runner and the fetched dependency packages, shared by the full machine
  private Path zigGlobalCacheDir() { return OsCache.root().resolve("zig-cache/global"); }
  /// Only {@link ZigBuildOpts#fastTestBuild()} calls this. A test runs out of target/classes,
  /// so the parent of the resource root is target.
  private static Path targetDir() { return ResolveResource.artefact("/").getParent(); }
  /// The zig build tree: `build.zig`, the runtime source copy, the generated program and
  /// `zig-out`. The zig build cache keys on the absolute paths of the build root and its
  /// sources, so a work dir that moves between compiles defeats the cache. Each test fork gets
  /// one stable dir under target/, thus concurrent forks keep apart and the path stays equal.
  Path workDir() {
    if (opts.fastTestBuild()) { return targetDir().resolve("fearless-zig-work/fork-" + forkId()); }
    return io.output().resolve("zig-build");
  }
  /// Identifies the surefire fork of this JVM, from the `surefire.forkNumber` that the pom puts
  /// into argLine. A plain `java` run or an IDE run uses the pid, and loses only the cache reuse
  /// between runs.
  private static String forkId() {
    var fork = System.getProperty("surefire.forkNumber");
    if (fork != null && !fork.isBlank() && !fork.contains("$")) { return fork; }
    return "pid" + ProcessHandle.current().pid();
  }
  private Path zigOutDir(Path workDir) { return workDir.resolve("zig-out"); }

  /// Returns the cached .zig content of the base packages, keyed by package name.
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
  /// The FeaRT runtime source tree: the copy in the compiler, or `FEART_ROOT`. With
  /// `FEART_ROOT` you can develop the runtime in a checkout and not build the compiler again.
  static Path feartRoot() {
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

      var generated = new HashSet<String>();
      for (var entry : program.packageFiles().entrySet()) {
        var fileName = entry.getKey().replace(".", "_") + ".zig";
        generated.add(fileName);
        writeIfChanged(genDir.resolve(fileName), entry.getValue());
      }
      pruneGenerated(genDir, generated);

      writeIfChanged(srcDir.resolve("main.zig"), program.mainFile());
      writeIfChanged(workDir.resolve("build.zig"), buildZig());
      writeIfChanged(workDir.resolve("build.zig.zon"), buildZigZon());

      runZigBuild(workDir, zigCacheDir(), zigGlobalCacheDir(), outDir);

      saveCachedPackages(program);

      return null;
    });
    return outDir.resolve("bin/fearless-app");
  }

  /// Deletes the cache directories of an old {@link #ZIG_CACHE_VERSION}. The siblings of the
  /// current version hold the other VPF configuration, so they stay. Thus a change between
  /// `--feart` and `--feart --no-vpf` does not discard the cache of the other build.
  public static void cleanOldVersions(Path cacheBase) {
    if (!Files.isDirectory(cacheBase)) { return; }
    IoErr.of(() -> {
      try (var dirs = Files.list(cacheBase)) {
        dirs.filter(Files::isDirectory)
          .filter(d -> d.getFileName().toString().matches("v\\d+(-\\w+)?"))
          .filter(d -> !d.getFileName().toString().startsWith(currentVersion()))
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

    copyTree(feartSrc, targetRuntime);

    var feartLib = feartRoot().resolve("lib");
    var targetLib = workDir.resolve("lib");
    if (Files.isDirectory(feartLib)) {
      copyTree(feartLib, targetLib);
    }

    copyNativeStaticLib(workDir);
  }

  /// Copies the Rust C-ABI staticlib next to the runtime, so that build.zig can link it. The
  /// lib holds the `frt_*` string and regex surface, which the Java backend also uses. Its
  /// `<arch>-<os>-libnative_rt.a` name uses the arch and os spelling of the JNI `.so` loader in
  /// assets/rt/NativeRuntime.java.
  private void copyNativeStaticLib(Path workDir) throws IOException {
    var osName = System.getProperty("os.name").toLowerCase();
    String os;
    if (osName.contains("linux")) { os = "linux"; }
    else if (osName.contains("mac") || osName.contains("darwin")) { os = "macos"; }
    else if (osName.contains("windows")) { os = "windows"; }
    else { throw Bug.of("Unsupported OS for native runtime staticlib: " + osName); }

    var archName = System.getProperty("os.arch").toLowerCase();
    String arch = switch (archName) {
      case "x86_64", "amd64" -> "amd64";
      case "aarch64", "arm64" -> "arm64";
      default -> throw Bug.of("Unsupported architecture for native runtime staticlib: " + archName);
    };

    var source = ResolveResource.artefact("/rt/libnative/static/" + arch + "-" + os + "-libnative_rt.a");
    if (!Files.exists(source)) {
      throw Bug.of("Cannot find the native runtime staticlib at " + source + ". Build the `native-rt` crate with `--features capi` for this host.");
    }
    var target = workDir.resolve("lib/native/libnative_rt.a");
    Files.createDirectories(target.getParent());
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /// Writes `content` only when the file holds different bytes. Thus an unchanged file keeps its
  /// mtime, and zig needs only a stat.
  private static void writeIfChanged(Path dest, String content) throws IOException {
    var bytes = content.getBytes(StandardCharsets.UTF_8);
    if (Files.isRegularFile(dest) && Arrays.equals(Files.readAllBytes(dest), bytes)) { return; }
    Files.write(dest, bytes);
  }

  /// Deletes the package files of an earlier compile into the same work dir. No file imports
  /// them, but they collect without a limit.
  private static void pruneGenerated(Path genDir, Set<String> keep) throws IOException {
    try (var files = Files.list(genDir)) {
      for (var file : files.toList()) {
        if (!keep.contains(file.getFileName().toString())) { Files.deleteIfExists(file); }
      }
    }
  }

  /// True when `dest` holds the bytes of `src`. `src` can be in the virtual file system of the
  /// jar, so this compares the content and not the metadata.
  private static boolean sameContent(Path src, Path dest) throws IOException {
    if (!Files.isRegularFile(dest)) { return false; }
    if (Files.size(dest) != Files.size(src)) { return false; }
    return Arrays.equals(Files.readAllBytes(dest), Files.readAllBytes(src));
  }

  private void copyTree(Path source, Path target) throws IOException {
    try (var walker = Files.walk(source)) {
      walker.forEach(src -> IoErr.of(() -> {
        // Resolve through a String: the source can be in the virtual file system of the jar
        var dest = target.resolve(source.relativize(src).toString());
        if (Files.isDirectory(src)) {
          Files.createDirectories(dest);
        } else {
          Files.createDirectories(dest.getParent());
          // A rewrite of an equal file changes its mtime, and then zig hashes it again
          if (!sameContent(src, dest)) {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
          }
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
    // The self-hosted (non-LLVM) linker cannot read the .sframe sections in the CRT objects of a
    // very new host glibc or gcc. A pinned glibc version makes zig link its own CRT objects.
    // Version 2.34 also runs on older stable distros. An LLVM build keeps a fully native target,
    // because LLD reads .sframe and because ReleaseFast tunes for the native CPU.
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

          // A direct link of the native runtime archive lets the linker drop its unused JNI
          // objects. The archive needs libc (pthread and more), and its Rust objects need the
          // platform unwinder in libgcc_s.
          exe.root_module.addObjectFile(b.path("lib/native/libnative_rt.a"));
          exe.root_module.link_libc = true;
          exe.root_module.linkSystemLibrary("gcc_s", .{});

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
