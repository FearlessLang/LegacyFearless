package codegen.zig;

import codegen.MIR;
import id.Id;

import java.util.LinkedHashMap;
import java.util.Map;

public record ZigProgram(Map<String, String> packageFiles, String mainFile, String entryPoint) {
  ZigProgram(ZigProgramBuilder builder) {
    this(builder.packageFiles, builder.mainFile, builder.entryPoint);
  }

  public static ZigProgram of(String entryPoint, MIR.Program program, java.util.Set<String> cachedPkg, Map<String, String> cachedContent) {
    return new ZigProgram(new ZigProgramBuilder(entryPoint, program, cachedPkg, cachedContent));
  }
}

class ZigProgramBuilder {
  final String entryPoint;
  final Map<String, String> packageFiles;
  final String mainFile;
  private final MIR.Program program;
  private final java.util.Set<String> cachedPkg;

  ZigProgramBuilder(String entryPoint, MIR.Program program, java.util.Set<String> cachedPkg, Map<String, String> cachedContent) {
    this.entryPoint = entryPoint;
    this.program = program;
    this.cachedPkg = cachedPkg;

    var gen = new ZigSingleCodegen(program);

    for (MIR.Package pkg : program.pkgs()) {
      if (cachedPkg.contains(pkg.name())) { continue; }
      for (MIR.TypeDef def : pkg.defs().values()) {
        var funs = pkg.funs().stream()
          .filter(f -> f.name().d().equals(def.name()))
          .toList();
        gen.visitTypeDef(pkg.name(), def, funs);
      }
    }

    this.packageFiles = buildPackageFiles(gen);
    this.packageFiles.putAll(cachedContent);

    this.mainFile = buildMainFile(gen);
  }

  private Map<String, String> buildPackageFiles(ZigSingleCodegen gen) {
    var files = new LinkedHashMap<String, String>();
    for (var entry : gen.packageStates.entrySet()) {
      var pkgName = entry.getKey();
      var state = entry.getValue();
      var sb = new StringBuilder();

      // Root import + runtime aliases
      sb.append("const root = @import(\"root\");\n");
      sb.append("const std = root.std;\n");
      sb.append("const rt = root.rt;\n");
      sb.append("const nat_rt = root.nat_rt;\n");
      sb.append("const int_rt = root.int_rt;\n");
      sb.append("const float_rt = root.float_rt;\n");
      sb.append("const byte_rt = root.byte_rt;\n");
      sb.append("const gc = root.gc;\n");
      sb.append("const str_rt = root.str_rt;\n");
      sb.append("const regex_rt = root.regex_rt;\n");
      sb.append("const hash_rt = root.hash_rt;\n");
      sb.append("const map_rt = root.map_rt;\n");
      sb.append("const var_rt = root.var_rt;\n");
      sb.append("const sys_rt = root.sys_rt;\n");
      sb.append("const list_rt = root.list_rt;\n");
      sb.append("const isopod_rt = root.isopod_rt;\n");
      sb.append("const flow_rt = root.flow_rt;\n");
      sb.append("const try_rt = root.try_rt;\n");
      sb.append("const error_rt = root.error_rt;\n");
      sb.append("const errors = root.errors;\n");
      sb.append("const heartbeat = root.heartbeat;\n");
      sb.append("const shadow_stack_mod = root.shadow_stack_mod;\n");
      sb.append("const worker_mod = root.worker_mod;\n");
      sb.append("const JoinObligation = root.JoinObligation;\n");
      sb.append("const log = root.log;\n");
      sb.append("const Fiber = root.Fiber;\n");
      sb.append('\n');

      // Capture structs
      if (!state.captureStructs.isEmpty()) {
        for (var cs : state.captureStructs.values()) {
          sb.append(cs).append('\n');
        }
        sb.append('\n');
      }

      // Functions
      if (!state.functions.isEmpty()) {
        for (var f : state.functions) {
          sb.append(f).append('\n');
        }
        sb.append('\n');
      }

      // VTables (pub for cross-package access)
      if (!state.vtableDefs.isEmpty()) {
        for (var vt : state.vtableDefs.values()) {
          sb.append(vt).append('\n');
        }
        sb.append('\n');
      }

      files.put(pkgName, sb.toString());
    }
    return files;
  }

  private String buildMainFile(ZigSingleCodegen gen) {
    var sb = new StringBuilder();

    // Imports — all pub so package files can access via @import("root")
    sb.append("pub const std = @import(\"std\");\n");
    sb.append("pub const rt = @import(\"runtime/objs.zig\");\n");
    sb.append("pub const nat_rt = @import(\"runtime/intrinsics/nat.zig\");\n");
    sb.append("pub const int_rt = @import(\"runtime/intrinsics/int.zig\");\n");
    sb.append("pub const float_rt = @import(\"runtime/intrinsics/float.zig\");\n");
    sb.append("pub const byte_rt = @import(\"runtime/intrinsics/byte.zig\");\n");
    sb.append("pub const gc = @import(\"runtime/gc.zig\");\n");
    sb.append("pub const process = @import(\"runtime/process_singletons.zig\");\n");
    sb.append("pub const str_rt = @import(\"runtime/intrinsics/strings/index.zig\");\n");
    sb.append("pub const regex_rt = @import(\"runtime/intrinsics/regex.zig\");\n");
    sb.append("pub const hash_rt = @import(\"runtime/intrinsics/hash.zig\");\n");
    sb.append("pub const map_rt = @import(\"runtime/intrinsics/map.zig\");\n");
    sb.append("pub const var_rt = @import(\"runtime/intrinsics/var.zig\");\n");
    sb.append("pub const sys_rt = @import(\"runtime/intrinsics/sys.zig\");\n");
    sb.append("pub const list_rt = @import(\"runtime/intrinsics/list.zig\");\n");
    sb.append("pub const isopod_rt = @import(\"runtime/intrinsics/isopod.zig\");\n");
    sb.append("pub const flow_rt = @import(\"runtime/intrinsics/flow.zig\");\n");
    sb.append("pub const try_rt = @import(\"runtime/try.zig\");\n");
    sb.append("pub const error_rt = @import(\"runtime/error.zig\");\n");
    sb.append("pub const errors = @import(\"runtime/errors/errors.zig\");\n");
    sb.append("pub const shadow_stack_mod = @import(\"runtime/shadow_stack.zig\");\n");
    sb.append("pub const worker_mod = @import(\"runtime/worker.zig\");\n");
    sb.append("pub const JoinObligation = @import(\"runtime/sync/join_obligation.zig\").JoinObligation;\n");
    sb.append("pub const heartbeat = @import(\"runtime/heartbeat.zig\");\n");
    sb.append("pub const log = @import(\"runtime/log.zig\");\n");
    sb.append("pub const Fiber = @import(\"runtime/fiber.zig\").Fiber;\n");
    sb.append("comptime { _ = Fiber; }\n");
    sb.append("pub const native = @import(\"runtime/native.zig\");\n");
    // A `@panic`-class fault inside a fiber becomes a non-deterministic error
    // that unwinds to the nearest `CapTry`/top-level boundary. See
    // `runtime/errors/unwind.zig`.
    sb.append("pub const panic = std.debug.FullPanic(errors.ndPanicHandler);\n");
    sb.append('\n');

    // Generated package imports — all pub for cross-package @import("root") access
    for (var pkgName : packageFiles.keySet()) {
      var fieldName = "pkg_" + pkgName.replace(".", "_");
      var fileName = pkgName.replace(".", "_") + ".zig";
      sb.append("pub const ").append(fieldName).append(" = @import(\"generated/").append(fileName).append("\");\n");
    }
    sb.append('\n');

    // Re-export core VTables for runtime compatibility
    appendReExportIfPresent(sb, gen, "VT_Void_0", new Id.DecId("base.Void", 0));
    appendReExportIfPresent(sb, gen, "VT_True_0", new Id.DecId("base.True", 0));
    appendReExportIfPresent(sb, gen, "VT_False_0", new Id.DecId("base.False", 0));
    sb.append('\n');

    // Entry point
    sb.append(generateMain(gen));

    return sb.toString();
  }

  private void appendReExportIfPresent(StringBuilder sb, ZigSingleCodegen gen, String vtName, Id.DecId decId) {
    var owningPkg = gen.typeToPackage.get(decId);
    if (owningPkg != null && packageFiles.containsKey(owningPkg)) {
      var fieldName = "pkg_" + owningPkg.replace(".", "_");
      sb.append("pub const ").append(vtName).append(" = &").append(fieldName).append(".").append(vtName).append(";\n");
    }
  }

  private boolean isBaseMain() {
    return program.pkgs().stream().anyMatch(pkg -> pkg.name().equals("base.caps"));
  }

  private String generateMain(ZigSingleCodegen gen) {
    var lastDot = entryPoint.lastIndexOf('.');
    String pkg = lastDot >= 0 ? entryPoint.substring(0, lastDot) : "";
    String typeName = lastDot >= 0 ? entryPoint.substring(lastDot + 1) : entryPoint;
    var entryDecId = new Id.DecId(pkg + "." + typeName, 0);
    var entryVtName = gen.id.getSimpleName(entryDecId);

    // Resolve entry VTable reference via package
    var entryPkg = gen.typeToPackage.get(entryDecId);
    String entryVtRef;
    if (entryPkg != null) {
      entryVtRef = "pkg_" + entryPkg.replace(".", "_") + ".VT_" + entryVtName;
    } else {
      entryVtRef = "VT_" + entryVtName;
    }

    // Inline hash for "imm #/1"
    var sigStr = "imm #/1";
    var hashExpr = "comptime rt.hash_signature(\"" + sigStr + "\")";

    var sb = new StringBuilder();
    sb.append("// === Entry Point ===\n");
    sb.append("pub fn main(init: std.process.Init) void {\n");
    sb.append("process.set_runtime_io(init.io);\n");
    sb.append("process.set_launch_args(init.minimal.args.vector);\n");
    sb.append("std.mem.doNotOptimizeAway(native.linkProbe());\n");
    sb.append("if (init.environ_map.get(\"FEART_ALLOCS_OUT\")) |p| gc.set_allocs_out_path(p);\n");
    sb.append("log.installCrashHandler();\n");
    sb.append("gc.init_gc();\n");
    sb.append("errors.installNdHandlers();\n");
    sb.append("const cpu_count = std.Thread.getCpuCount() catch 1;\n");
    sb.append("const pool = worker_mod.WorkerPool.init(cpu_count) catch @panic(\"OOM\");\n");
    sb.append("const main_fiber = Fiber.create(struct {\n");
    sb.append("fn run(_: *Fiber) void {\n");
    sb.append("const entry = rt.obj_k_singleton(&").append(entryVtRef).append(");\n");

    if (isBaseMain()) {
      sb.append("const sys = sys_rt.make_system();\n");
      sb.append("_ = rt.call(entry, ").append(hashExpr).append(", .{sys}, @src());\n");
    } else {
      var llistDecId = new Id.DecId("base.LList", 1);
      var llistVtName = gen.id.getSimpleName(llistDecId);
      var llistPkg = gen.typeToPackage.get(llistDecId);
      String llistVtRef;
      if (llistPkg != null) {
        llistVtRef = "pkg_" + llistPkg.replace(".", "_") + ".VT_" + llistVtName;
      } else {
        llistVtRef = "VT_" + llistVtName;
      }
      sb.append("const args = rt.obj_k_singleton(&").append(llistVtRef).append(");\n");
      sb.append("const result = rt.call(entry, ").append(hashExpr).append(", .{args}, @src());\n");
      sb.append("const str_data = str_rt.deref_str(result);\n");
      sb.append("_ = std.posix.system.write(std.posix.STDOUT_FILENO, str_data.ptr, str_data.len);\n");
      sb.append("_ = std.posix.system.write(std.posix.STDOUT_FILENO, \"\\n\", 1);\n");
    }

    sb.append("worker_mod.global_done.store(true, .release);\n");
    sb.append("}\n");
    sb.append("}.run, null) catch @panic(\"OOM\");\n");
    sb.append("pool.enqueueFiber(main_fiber);\n");
    sb.append("pool.run();\n");
    sb.append("log.dumpAllTraceBuffers();\n");
    sb.append("gc.dump_allocs();\n");
    sb.append("}\n");
    return sb.toString();
  }
}
