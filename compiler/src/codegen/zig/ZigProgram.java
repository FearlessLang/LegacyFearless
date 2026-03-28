package codegen.zig;

import codegen.MIR;
import id.Id;

import java.util.List;

public record ZigProgram(String generatedCode, String entryPoint) {
  public ZigProgram(ZigProgramBuilder builder) {
    this(builder.build(), builder.entryPoint);
  }

  public static ZigProgram of(String entryPoint, MIR.Program program, java.util.Set<String> cachedPkg) {
    return new ZigProgram(new ZigProgramBuilder(entryPoint, program, cachedPkg));
  }
}

class ZigProgramBuilder {
  final String entryPoint;
  private final MIR.Program program;
  private final java.util.Set<String> cachedPkg;

  ZigProgramBuilder(String entryPoint, MIR.Program program, java.util.Set<String> cachedPkg) {
    this.entryPoint = entryPoint;
    this.program = program;
    this.cachedPkg = cachedPkg;
  }

  String build() {
    var gen = new ZigSingleCodegen(program);

    // Visit all types in all packages
    for (MIR.Package pkg : program.pkgs()) {
      if (cachedPkg.contains(pkg.name())) { continue; }
      for (MIR.TypeDef def : pkg.defs().values()) {
        var funs = pkg.funs().stream()
          .filter(f -> f.name().d().equals(def.name()))
          .toList();
        gen.visitTypeDef(pkg.name(), def, funs);
      }
    }

    var sb = new StringBuilder();

    // Imports
    sb.append("const std = @import(\"std\");\n");
    sb.append("const rt = @import(\"runtime/objs.zig\");\n");
    sb.append("const nat_rt = @import(\"runtime/intrinsics/nat.zig\");\n");
    sb.append("const int_rt = @import(\"runtime/intrinsics/int.zig\");\n");
    sb.append("const gc = @import(\"runtime/gc.zig\");\n");
    sb.append("const str_rt = @import(\"runtime/intrinsics/str.zig\");\n");
    // VPF runtime imports
    sb.append("const shadow_stack_mod = @import(\"runtime/shadow_stack.zig\");\n");
    sb.append("const worker_mod = @import(\"runtime/worker.zig\");\n");
    sb.append("const JoinObligation = @import(\"runtime/sync/join_obligation.zig\").JoinObligation;\n");
    sb.append("const heartbeat = @import(\"runtime/heartbeat.zig\");\n");
    sb.append("const log = @import(\"runtime/log.zig\");\n");
    sb.append("const Fiber = @import(\"runtime/fiber.zig\").Fiber;\n");
    // Force fiber.zig to be compiled so fiber_trampoline is linked for the assembly files
    sb.append("comptime { _ = Fiber; }\n");
    sb.append('\n');

    // Hash constants
    if (!gen.hashConstants.isEmpty()) {
      sb.append("// === Hash Constants ===\n");
      for (var h : gen.hashConstants) {
        sb.append(h).append('\n');
      }
      sb.append('\n');
    }

    // Capture structs (forward declarations)
    if (!gen.captureStructs.isEmpty()) {
      sb.append("// === Capture Structs ===\n");
      for (var entry : gen.captureStructs.values()) {
        sb.append(entry).append('\n');
      }
      sb.append('\n');
    }

    // Functions (MF_, T_, static funs)
    if (!gen.functions.isEmpty()) {
      sb.append("// === Functions ===\n");
      for (var f : gen.functions) {
        sb.append(f).append('\n');
      }
      sb.append('\n');
    }

    // VTables
    if (!gen.vtableDefs.isEmpty()) {
      sb.append("// === VTables ===\n");
      for (var vt : gen.vtableDefs.values()) {
        sb.append(vt).append('\n');
      }
      sb.append('\n');
    }

    // Entry point main function
    sb.append(generateMain(gen));

    return sb.toString();
  }

  private String generateMain(ZigSingleCodegen gen) {
    // Parse the entry point name like "test.App" → DecId("App", "test", 0)
    var lastDot = entryPoint.lastIndexOf('.');
    String pkg = lastDot >= 0 ? entryPoint.substring(0, lastDot) : "";
    String typeName = lastDot >= 0 ? entryPoint.substring(lastDot + 1) : entryPoint;
    var entryDecId = new Id.DecId(pkg + "." + typeName, 0);
    var entryVtName = "VT_" + gen.id.getSimpleName(entryDecId);

    // immBase Main: { #(args: LList[Str]): Str }
    var sigStr = "imm #/1";
    var hashSuffix = Long.toHexString(ZigSigStringBuilder.fnv1a(sigStr));
    var hashMethName = gen.id.getMName(id.Mdf.imm, new Id.MethName("#", 1));
    var hashConstName = "H_" + hashMethName + "_" + hashSuffix;

    // Add the hash constant declaration if not already present
    gen.hashConstants.add("const " + hashConstName + " = rt.hash_signature(\"" + sigStr + "\");");

    // LList[Str] empty singleton — LList is generic (gen=1)
    var llistDecId = new Id.DecId("base.LList", 1);
    var llistVtName = "VT_" + gen.id.getSimpleName(llistDecId);

    var sb = new StringBuilder();
    sb.append("// === Entry Point ===\n");
    sb.append("pub fn main() void {\n");
    sb.append("    log.installCrashHandler();\n");
    sb.append("    gc.init_gc();\n");
    sb.append("    const cpu_count = std.Thread.getCpuCount() catch 1;\n");
    sb.append("    const pool = worker_mod.WorkerPool.init(cpu_count) catch @panic(\"OOM\");\n");
    sb.append("    const main_fiber = Fiber.create(struct {\n");
    sb.append("        fn run(_: *Fiber) void {\n");
    sb.append("            const entry = rt.obj_k_singleton(&").append(entryVtName).append(");\n");
    sb.append("            const args = rt.obj_k_singleton(&").append(llistVtName).append(");\n");
    sb.append("            const result = rt.call(entry, ").append(hashConstName).append(", .{args}, @src());\n");
    sb.append("            const str_data = str_rt.deref_str(result);\n");
    sb.append("            _ = std.posix.write(std.posix.STDOUT_FILENO, str_data) catch {};\n");
    sb.append("            _ = std.posix.write(std.posix.STDOUT_FILENO, \"\\n\") catch {};\n");
    sb.append("            worker_mod.global_done.store(true, .release);\n");
    sb.append("        }\n");
    sb.append("    }.run, null) catch @panic(\"OOM\");\n");
    sb.append("    pool.enqueueFiber(main_fiber);\n");
    sb.append("    pool.run();\n");
    sb.append("    log.dumpAllTraceBuffers();\n");
    sb.append("}\n");
    return sb.toString();
  }
}
