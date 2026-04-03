package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import id.Id;
import id.Id.DecId;
import magic.Magic;
import utils.Bug;
import visitors.MIRVisitor;

import java.util.*;
import java.util.stream.Collectors;

public class ZigSingleCodegen implements MIRVisitor<String> {
  protected final MIR.Program p;
  protected final Map<MIR.FName, MIR.Fun> funMap;
  private final ZigMagicImpls magic;
  public final ZigStringIds id = new ZigStringIds();
  final ZigSigStringBuilder sigBuilder;

  // Accumulated output sections
  public final LinkedHashSet<String> hashConstants = new LinkedHashSet<>();
  public final LinkedHashMap<DecId, String> captureStructs = new LinkedHashMap<>();
  public final LinkedHashMap<DecId, String> vtableDefs = new LinkedHashMap<>();
  public final List<String> functions = new ArrayList<>();
  // freshRecords equivalent: tracks which CreateObj types we've already emitted
  public final LinkedHashMap<DecId, Boolean> emittedTypes = new LinkedHashMap<>();

  private String pkg;

  public ZigSingleCodegen(MIR.Program p) {
    magic = new ZigMagicImpls(this, t -> "rt.FatPtr", p.p());
    sigBuilder = new ZigSigStringBuilder(p.p());
    this.p = p;
    this.funMap = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f));
  }

  public boolean isLiteral(DecId d) {
    return id.getLiteral(p.p(), d).isPresent();
  }

  public String visitTypeDef(String pkg, MIR.TypeDef def, List<MIR.Fun> funs) {
    this.pkg = pkg;
    var isMagic = pkg.equals("base") && def.name().name().endsWith("Instance");
    var isLiteral = isLiteral(def.name());
    if (isMagic || isLiteral) { return ""; }

    // Emit hash constants for all signatures
    for (var sig : def.sigs()) {
      addHashConstant(sig);
    }

    // Emit VTable for singleton types
    var leastSpecific = ParentWalker.leastSpecificSigs(p, def);

    // If this type has a singleton instance, emit it
    def.singletonInstance().ifPresent(objK -> {
      emitCreateObj(objK, true);
    });

    // Emit static functions
    for (var fun : funs) {
      visitFun(fun);
    }

    return ""; // All output accumulated in state
  }

  void addHashConstant(MIR.Sig sig) {
    hashConstants.add(sigBuilder.hashConstDecl(sig, id));
  }

  public void emitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
    if (magic.isMagic(Magic.Str, createObj.concreteT().id())) { return; }

    var magicImpl = magic.get(createObj);
    if (checkMagic && magicImpl.isPresent()) {
      var res = magicImpl.get().instantiate();
      if (res.isPresent()) { return; }
    }

    var objId = createObj.concreteT().id();
    if (emittedTypes.containsKey(objId)) { return; }
    emittedTypes.put(objId, true);

    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
    var leastSpecific = typeDef != null
      ? ParentWalker.leastSpecificSigs(p, typeDef)
      : java.util.Map.<Id.MethName, MIR.Sig>of();

    // Emit captures struct
    if (!createObj.captures().isEmpty()) {
      var fields = createObj.captures().stream()
        .map(x -> id.varName(x.name()) + ": rt.FatPtr,")
        .collect(Collectors.joining("\n"));
      captureStructs.put(objId,
        "const " + id.getSimpleName(objId) + "_Captures = extern struct {\n"
        + fields + "\n};");
    }

    // Emit MF_ and T_ functions for each method
    for (var meth : createObj.meths()) {
      emitMeth(meth, objId, false, leastSpecific);
    }
    for (var meth : createObj.unreachableMs()) {
      emitMeth(meth, objId, true, leastSpecific);
    }

    // Emit VTable
    emitVTable(createObj, objId);
  }

  private void emitMeth(MIR.Meth meth, DecId objId, boolean isUnreachable,
                         Map<Id.MethName, MIR.Sig> leastSpecific) {
    var sig = meth.sig();
    addHashConstant(sig);

    var methName = id.getMName(sig.mdf(), sig.name());
    var typeName = id.getSimpleName(objId);
    var mfName = "MF_" + typeName + "_" + methName;
    var tName = "T_" + typeName + "_" + methName;

    // Build parameter list
    var params = new ArrayList<String>();
    params.add("self_m: rt.FatPtr");
    for (var x : sig.xs()) {
      params.add(id.varName(x.name()) + ": rt.FatPtr");
    }
    var paramStr = String.join(", ", params);

    var paramDiscard = "_ = .{ " + params.stream().map(p -> p.split(":")[0].trim()).collect(Collectors.joining(", ")) + " };\n";
    if (isUnreachable || meth.fName().isEmpty()) {
      // Unreachable method
      functions.add("fn " + mfName + "(" + paramStr + ") rt.FatPtr {\n"
        + paramDiscard
        + "unreachable;\n"
        + "}");
    } else {
      // Real method: delegate to the static Fun
      var fName = id.getFName(meth.fName().get());
      var fun = funMap.get(meth.fName().get());
      if (fun != null) {
        var simpleArgs = new ArrayList<String>();
        for (var x : sig.xs()) {
          simpleArgs.add(id.varName(x.name()));
        }
        simpleArgs.add("self_m");
        for (var capture : meth.captures()) {
          // Captures need to be extracted from self if it's an object with captures
          if (!createObjHasCaptures(objId)) {
            simpleArgs.add("self_m"); // no captures, pass self as placeholder
          } else {
            simpleArgs.add(
              "rt.deref(" + id.getSimpleName(objId) + "_Captures, self_m)." + id.varName(capture));
          }
        }

        functions.add("fn " + mfName + "(" + paramStr + ") rt.FatPtr {\n"
          + paramDiscard
          + "return " + fName + "(" + String.join(", ", simpleArgs) + ");\n"
          + "}");
      } else {
        // Fun not found, make unreachable
        functions.add("fn " + mfName + "(" + paramStr + ") rt.FatPtr {\n"
          + "_ = .{ " + params.stream().map(p -> p.split(":")[0].trim()).collect(Collectors.joining(", ")) + " };\n"
          + "unreachable;\n"
          + "}");
      }
    }

    // C-ABI thunk
    var thunkParams = new ArrayList<String>();
    thunkParams.add("self_m: rt.FatPtr");
    for (var x : sig.xs()) {
      thunkParams.add(id.varName(x.name()) + ": rt.FatPtr");
    }
    var thunkCallArgs = new ArrayList<String>();
    thunkCallArgs.add("self_m");
    for (var x : sig.xs()) {
      thunkCallArgs.add(id.varName(x.name()));
    }

    functions.add("fn " + tName + "(" + String.join(", ", thunkParams) + ") callconv(.c) rt.FatPtr {\n"
      + "return " + mfName + "(" + String.join(", ", thunkCallArgs) + ");\n"
      + "}");
  }

  private boolean createObjHasCaptures(DecId objId) {
    return captureStructs.containsKey(objId);
  }

  private void emitVTable(MIR.CreateObj createObj, DecId objId) {
    var meths = new ArrayList<>(createObj.meths());
    meths.addAll(createObj.unreachableMs());

    var typeName = id.getSimpleName(objId);
    var hashes = new ArrayList<String>();
    var methods = new ArrayList<String>();

    for (var meth : meths) {
      var sig = meth.sig();
      hashes.add(sigBuilder.hashConstName(sig, id));
      methods.add("&T_" + typeName + "_" + id.getMName(sig.mdf(), sig.name()));
    }

    var hashesStr = hashes.isEmpty() ? "&.{}" :
      "&[_]u64{ " + String.join(", ", hashes) + " }";
    var methodsStr = methods.isEmpty() ? "&.{}" :
      "&[_]*const anyopaque{ " + String.join(", ", methods) + " }";

    vtableDefs.put(objId,
      "pub const VT_" + typeName + ": rt.VTable = .{\n"
      + "    .type_name = \"" + objId.name() + "/" + objId.gen() + "\",\n"
      + "    .hashes = " + hashesStr + ",\n"
      + "    .methods = " + methodsStr + ",\n"
      + "};");
  }

  public void visitFun(MIR.Fun fun) {
    var name = id.getFName(fun.name());
    var paramNames = fun.args().stream()
      .map(x -> id.varName(x.name()))
      .toList();
    var params = fun.args().stream()
      .map(x -> id.varName(x.name()) + ": rt.FatPtr")
      .collect(Collectors.joining(", "));

    // Check if this function body contains a VPF-parallelisable call
    var vpfCodegen = new VPFCodegen(this);
    var vpfInfo = vpfCodegen.findVPFCall(fun.body());
    // Top-level locals struct: N params (FatPtr=16) + r1 (FatPtr=16)
    int topLevelLocalsSize = (fun.args().size() + 1) * 16;
    if (vpfInfo != null && topLevelLocalsSize <= VPFCodegen.LOCALS_COPY_LIMIT) {
      vpfCodegen.emitVPFFun(fun, name, paramNames, params, vpfInfo);
      return;
    }

    var body = fun.body().accept(this, true);
    var sb = new StringBuilder();
    sb.append("fn ").append(name).append("(").append(params).append(") rt.FatPtr {\n");
    // Discard all params to avoid unused-parameter errors
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }
    sb.append("heartbeat.tryPromote();\n");
    if (body.equals("unreachable")) {
      sb.append("unreachable;\n");
    } else {
      sb.append("return ").append(body).append(";\n");
    }
    sb.append("}");
    functions.add(sb.toString());
  }

  @Override
  public String visitX(MIR.X x, boolean checkMagic) {
    return id.varName(x.name());
  }

  @Override
  public String visitMCall(MIR.MCall call, boolean checkMagic) {
    var magicImpl = magic.get(call.recv());
    if (checkMagic && magicImpl.isPresent()) {
      var impl = magicImpl.get()
        .call(call.name(), call.args(), call.variant(), call.t());
      if (impl.isPresent()) { return impl.get(); }
    }

    // Normal dispatch via rt.call
    var recv = call.recv().accept(this, checkMagic);
    var hashName = sigBuilder.hashConstName(
      new MIR.Sig(call.name(), call.args().stream().map(a -> new MIR.X("_", a.t())).toList(), call.originalRet()),
      id);

    // Build the original sig to get the hash
    addHashConstant(new MIR.Sig(call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet()));

    var args = call.args().stream()
      .map(a -> a.accept(this, checkMagic))
      .collect(Collectors.joining(", "));

    var argsTuple = args.isEmpty() ? ".{}" : ".{ " + args + " }";
    return "rt.call(" + recv + ", " + hashName + ", " + argsTuple + ", @src())";
  }

  @Override
  public String visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
    var magicImpl = magic.get(createObj);
    if (checkMagic && magicImpl.isPresent()) {
      var res = magicImpl.get().instantiate();
      if (res.isPresent()) { return res.get(); }
    }

    var objId = createObj.concreteT().id();
    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
    if (typeDef == null) {
      // Type not found in MIR — treat as singleton with empty vtable
      emitCreateObj(createObj, checkMagic);
      var typeName = id.getSimpleName(objId);
      return "rt.obj_k_singleton(&VT_" + typeName + ")";
    }
    var singleton = typeDef.singletonInstance().isPresent();

    // Make sure this type's struct/vtable/methods have been emitted
    emitCreateObj(createObj, checkMagic);

    var typeName = id.getSimpleName(objId);
    if (singleton) {
      return "rt.obj_k_singleton(&VT_" + typeName + ")";
    }

    if (createObj.captures().isEmpty()) {
      return "rt.obj_k_singleton(&VT_" + typeName + ")";
    }

    var captures = createObj.captures().stream()
      .map(x -> "." + id.varName(x.name()) + " = " + visitX(x, checkMagic))
      .collect(Collectors.joining(", "));
    return "rt.obj_k(" + typeName + "_Captures, &VT_" + typeName + ", .{ " + captures + " })";
  }

  @Override
  public String visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    String recv = expr.condition().accept(this, checkMagic);

    String thenBody = switch (this.funMap.get(expr.then()).body()) {
      case MIR.Block b -> inlineBlock(b);
      case MIR.E e -> e.accept(this, checkMagic);
    };
    String elseBody = switch (this.funMap.get(expr.else_()).body()) {
      case MIR.Block b -> inlineBlock(b);
      case MIR.E e -> e.accept(this, checkMagic);
    };

    return "(if (" + recv + ".vt == &VT_True_0) " + thenBody + " else " + elseBody + ")";
  }

  private String inlineBlock(MIR.Block block) {
    return visitBlockExpr(block, true);
  }

  // TODO: the block optimisation impl here is not correct, will clean up later.
  /*
  @Override
  public String visitBlockExpr(MIR.Block expr, boolean checkMagic) {
    var stmts = new ArrayDeque<>(expr.stmts());
    var sb = new StringBuilder();
    sb.append("blk: {\n");
    var doIdx = 0;
    while (!stmts.isEmpty()) {
      var stmt = stmts.poll();
      switch (stmt) {
        case MIR.Block.BlockStmt.Return ret ->
          sb.append("break :blk ").append(ret.e().accept(this, true)).append(";\n");
        case MIR.Block.BlockStmt.Do do_ -> {
          sb.append("_ = ").append(do_.e().accept(this, true)).append(";\n");
          doIdx++;
        }
        case MIR.Block.BlockStmt.Throw throw_ ->
          sb.append("@panic(\"Fearless error\");\n");
        case MIR.Block.BlockStmt.Loop loop ->
          sb.append("while (true) { _ = ").append(loop.e().accept(this, true)).append("; }\n");
        case MIR.Block.BlockStmt.If if_ -> {
          var nextStmt = stmts.poll();
          var body = nextStmt != null ? visitBlockStmt(nextStmt) : "unreachable";
          sb.append("if (").append(if_.pred().accept(this, true))
            .append(".vt == &VT_True_0) { ").append(body).append(" }\n");
        }
        case MIR.Block.BlockStmt.Let let -> {
          var vn = id.varName(let.name());
          sb.append("const ").append(vn).append(" = ")
            .append(let.value().accept(this, true)).append(";\n");
          sb.append("_ = .{ ").append(vn).append(" };\n");
        }
        case MIR.Block.BlockStmt.Var var_ -> {
          var vn = id.varName(var_.name());
          sb.append("const ").append(vn).append(" = ")
            .append(var_.value().accept(this, true)).append(";\n");
          sb.append("_ = .{ ").append(vn).append(" };\n");
        }
      }
    }
    sb.append("}");
    return sb.toString();
  }

  private String visitBlockStmt(MIR.Block.BlockStmt stmt) {
    return switch (stmt) {
      case MIR.Block.BlockStmt.Return ret -> "break :blk " + ret.e().accept(this, true) + ";";
      case MIR.Block.BlockStmt.Do do_ -> "_ = " + do_.e().accept(this, true) + ";";
      case MIR.Block.BlockStmt.Throw throw_ -> "@panic(\"Fearless error\");";
      case MIR.Block.BlockStmt.Loop loop -> "while (true) { _ = " + loop.e().accept(this, true) + "; }";
      case MIR.Block.BlockStmt.If if_ -> "if (" + if_.pred().accept(this, true) + ".vt == &VT_True_0)";
      case MIR.Block.BlockStmt.Let let -> "const " + id.varName(let.name()) + " = " + let.value().accept(this, true) + ";";
      case MIR.Block.BlockStmt.Var var_ -> "const " + id.varName(var_.name()) + " = " + var_.value().accept(this, true) + ";";
    };
  }
   */

  @Override
  public String visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
    var fName = id.getFName(call.fun());
    var args = call.args().stream()
      .map(a -> a.accept(this, checkMagic))
      .collect(Collectors.joining(", "));
    return fName + "(" + args + ")";
  }

  @Override
  public String visitUpdatableListAsIdFnCall(MIR.UpdatableListAsIdFnCall call, boolean checkMagic) {
    return "(rt.FatPtr{ .data = .{ .int = 0xDEAD }, .vt = @ptrFromInt(0) })"; // Lists not supported yet
  }

  // Not used directly - output is accumulated in state
  public String visitProgram(DecId entry) { throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg) { throw Bug.unreachable(); }
}
