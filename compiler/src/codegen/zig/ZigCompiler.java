package codegen.zig;

import main.CompilerFrontEnd;
import main.InputOutput;
import utils.Bug;
import utils.DeleteOnExit;
import utils.IoErr;

import java.io.IOException;
import java.nio.file.*;

public record ZigCompiler(CompilerFrontEnd.Verbosity verbosity, InputOutput io) {
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
    var workDir = IoErr.of(() -> Files.createTempDirectory("fearless-zig-"));
    if (verbosity.printCodegen()) {
      System.out.println("Zig build directory: " + workDir);
    }
    IoErr.of(() -> {
      var srcDir = workDir.resolve("src");
      Files.createDirectories(srcDir);

      // 1. Copy runtime files
      copyRuntime(workDir);

      // 2. Write generated code
      Files.writeString(srcDir.resolve("main.zig"), program.generatedCode());

      // 3. Write build.zig (trace/safety logging enabled when verbose)
      Files.writeString(workDir.resolve("build.zig"), buildZig(verbosity.printCodegen()));

      // 4. Write build.zig.zon
      Files.writeString(workDir.resolve("build.zig.zon"), buildZigZon());

      // 5. Fetch dependencies, then build
      runZigFetch(workDir);
      runZigBuild(workDir);

      return null;
    });
    if (!verbosity.printCodegen()) {
      DeleteOnExit.of(workDir);
    }
    return workDir.resolve("zig-out/bin/fearless-app");
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

  private void runZigFetch(Path workDir) throws IOException {
    var pb = new ProcessBuilder("zig", "build", "--fetch")
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
      throw Bug.of("zig build --fetch failed (exit " + exitCode + "):\n" + output);
    }
  }

  private void runZigBuild(Path workDir) throws IOException {
    var pb = new ProcessBuilder("zig", "build", "-Doptimize=ReleaseFast")
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

  private String buildZig(boolean enableTracing) {
    return """
      const std = @import("std");
      pub fn build(b: *std.Build) void {
          const target = b.standardTargetOptions(.{});
          const optimize = b.standardOptimizeOption(.{});

          const build_options = b.addOptions();
          build_options.addOption(bool, "log_scheduling", false);
          build_options.addOption(bool, "log_safety", %s);
          build_options.addOption(bool, "log_dispatch", false);
          build_options.addOption(bool, "log_trace", %s);

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
          exe.linkLibrary(gc_lib);
          const gc_include = gc_lib.getEmittedIncludeTree();
          exe.root_module.addIncludePath(gc_include);
          exe.root_module.addIncludePath(gc_include.path(b, "gc"));

          const exceptions_lib = b.addLibrary(.{
              .linkage = .static,
              .name = "exceptions",
              .root_module = b.createModule(.{
                  .target = target,
                  .optimize = optimize,
                  .link_libc = true,
              })
          });
          exceptions_lib.addCSourceFiles(.{
              .files = &.{"src/runtime/exceptions.c"},
              .flags = &.{"-std=c23"},
          });
          exe.linkLibrary(exceptions_lib);
          exe.addIncludePath(b.path("src/runtime"));
          switch (target.result.cpu.arch) {
              .x86_64 => exe.addAssemblyFile(b.path("src/runtime/context_switch_x86_64.S")),
              .aarch64 => exe.addAssemblyFile(b.path("src/runtime/context_switch_aarch64.S")),
              else => {},
          }
          exe.use_llvm = true;

          b.installArtifact(exe);

          const run_step = b.step("run", "Run the app");
          const run_cmd = b.addRunArtifact(exe);
          run_step.dependOn(&run_cmd.step);
          run_cmd.step.dependOn(b.getInstallStep());
          if (b.args) |args| {
              run_cmd.addArgs(args);
          }
      }
      """.formatted(enableTracing, enableTracing);
  }

  private String buildZigZon() {
    return """
      .{
          .name = .fearless_app,
          .version = "0.0.0",
          .fingerprint = 0xb506340ff5c420d3,
          .minimum_zig_version = "0.15.2",
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
