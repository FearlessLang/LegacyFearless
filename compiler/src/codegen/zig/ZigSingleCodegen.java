package codegen.zig;

import codegen.MIR;
import codegen.MethExprKind;
import codegen.ParentWalker;
import id.Id;
import id.Id.DecId;
import magic.Magic;
import utils.Bug;
import utils.Streams;
import visitors.MIRVisitor;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static codegen.MethExprKind.Kind.*;
import static magic.Magic.getLiteral;

public class ZigSingleCodegen implements MIRVisitor<String> {
  protected final MIR.Program p;
  protected final Map<MIR.FName, MIR.Fun> funMap;
  private final ZigMagicImpls magic;
  public final ZigStringIds id = new ZigStringIds();
  private final ZigSigStringBuilder sigBuilder;

  // Accumulated output sections
  public final LinkedHashSet<String> hashConstants = new LinkedHashSet<>();
  public final LinkedHashMap<DecId, String> captureStructs = new LinkedHashMap<>();
  public final LinkedHashMap<DecId, String> vtableDefs = new LinkedHashMap<>();
  public final List<String> functions = new ArrayList<>();
  // freshRecords equivalent: tracks which CreateObj types we've already emitted
  public final LinkedHashMap<DecId, Boolean> emittedTypes = new LinkedHashMap<>();

  private String pkg;

  // Counter for unique VPF thief names within a codegen run
  private int vpfCounter = 0;

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

  private void addHashConstant(MIR.Sig sig) {
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
    var vpfInfo = findVPFCall(fun.body());
    if (vpfInfo != null) {
      emitVPFFun(fun, name, paramNames, params, vpfInfo);
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

  // --- VPF (Very Parallel Fearless) Support ---

  /** Info about a VPF call found in a function body. */
  private record VPFCallInfo(
    MIR.MCall vpfCall,
    MIR.BoolExpr boolExpr, // non-null if wrapped in BoolExpr (base case + recursive case)
    List<SubExprInfo> subExprs, // all sub-expressions (recv + args), classified
    String hashName // hash constant name for the VPF call's method
  ) {}

  /** Classification of a sub-expression within a VPF call. */
  private record SubExprInfo(MIR.E expr, boolean isFrameAdding, int index) {}

  /** Find a VPFParallelisable MCall in the function body, return null if none. */
  private VPFCallInfo findVPFCall(MIR.E body) {
    return switch (body) {
      case MIR.MCall call -> {
        if (call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
          yield buildVPFInfo(call, null);
        }
        yield null;
      }
      case MIR.BoolExpr boolExpr -> {
        // The VPF call is typically in the else-branch (recursive case)
        var elseFun = funMap.get(boolExpr.else_());
        if (elseFun != null) {
          var inner = findVPFCallInner(elseFun.body());
          if (inner != null) {
            yield new VPFCallInfo(inner.vpfCall, boolExpr, inner.subExprs, inner.hashName);
          }
        }
        yield null;
      }
      default -> null;
    };
  }

  /** Like findVPFCall but doesn't look through BoolExpr (inner level). */
  private VPFCallInfo findVPFCallInner(MIR.E body) {
    if (body instanceof MIR.MCall call &&
        call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
      return buildVPFInfo(call, null);
    }
    return null;
  }

  private VPFCallInfo buildVPFInfo(MIR.MCall call, MIR.BoolExpr boolExpr) {
    var subExprs = new ArrayList<SubExprInfo>();
    // Receiver is sub-expr 0
    subExprs.add(new SubExprInfo(call.recv(), isFrameAddingExpr(call.recv()), 0));
    // Args are sub-exprs 1..N
    for (int i = 0; i < call.args().size(); i++) {
      var arg = call.args().get(i);
      subExprs.add(new SubExprInfo(arg, isFrameAddingExpr(arg), i + 1));
    }

    var sig = new MIR.Sig(call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet());
    addHashConstant(sig);
    var hashName = sigBuilder.hashConstName(sig, id);

    return new VPFCallInfo(call, boolExpr, subExprs, hashName);
  }

  /** MCalls and BoolExprs are the only MIR expressions that add stack frames. */
  private boolean isFrameAddingExpr(MIR.E expr) {
    return expr instanceof MIR.MCall || expr instanceof MIR.BoolExpr;
  }

  /** Emit a VPF-instrumented function: locals struct + thief function + instrumented body. */
  private void emitVPFFun(MIR.Fun fun, String name, List<String> paramNames, String params, VPFCallInfo vpf) {
    int vpfId = vpfCounter++;
    var localsName = name + "_" + vpfId + "_Locals";
    var thiefName = name + "_" + vpfId + "_thief";

    var frameAddingExprs = vpf.subExprs.stream().filter(s -> s.isFrameAdding).toList();
    var plainExprs = vpf.subExprs.stream().filter(s -> !s.isFrameAdding).toList();

    // Build set of function parameter names for the thief's locals prefix
    var funParamNames = new HashSet<String>();
    for (var arg : fun.args()) {
      funParamNames.add(arg.name());
    }

    // 1. Emit Locals struct: all function params + r1 slot
    var localsFields = new StringBuilder();
    for (var arg : fun.args()) {
      localsFields.append(id.varName(arg.name())).append(": rt.FatPtr,\n");
    }
    localsFields.append("r1: rt.FatPtr,\n");
    captureStructs.put(
      new DecId(localsName, 0),
      "const " + localsName + " = extern struct {\n" + localsFields + "};"
    );

    // 2. Emit thief function
    emitThiefFunction(thiefName, localsName, fun, vpf, frameAddingExprs, funParamNames);

    // 3. Emit the instrumented function body
    var sb = new StringBuilder();
    sb.append("fn ").append(name).append("(").append(params).append(") rt.FatPtr {\n");
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }

    // heartbeat.tryPromote() — before base case check, so promotion always runs
    sb.append("heartbeat.tryPromote();\n");

    // If there's a BoolExpr, emit the base case as an early return
    if (vpf.boolExpr != null) {
      var cond = vpf.boolExpr.condition().accept(this, true);
      var thenFun = funMap.get(vpf.boolExpr.then());
      String thenBody = thenFun.body().accept(this, true);
      sb.append("if (").append(cond).append(".vt == &VT_True_0) return ").append(thenBody).append(";\n");
    }

    // Initialize locals struct
    sb.append("var locals = ").append(localsName).append("{ ");
    for (var arg : fun.args()) {
      sb.append(".").append(id.varName(arg.name())).append(" = ").append(id.varName(arg.name())).append(", ");
    }
    sb.append(".r1 = undefined };\n");

    // Compiler fence to flush locals to memory before pushing frame
    sb.append("asm volatile (\"\" ::: .{ .memory = true });\n");

    // Push shadow frame using helper
    var methodHash = vpf.hashName;
    sb.append("const frame_idx = shadow_stack_mod.pushFrame(.{\n");
    sb.append("    .target_method = ").append(methodHash).append(",\n");
    sb.append("    .join_obligation = std.atomic.Value(?*JoinObligation).init(null),\n");
    sb.append("    .child_obligation = std.atomic.Value(?*JoinObligation).init(null),\n");
    sb.append("    .locals = @ptrCast(&locals),\n");
    sb.append("    .locals_size = @sizeOf(").append(localsName).append("),\n");
    sb.append("    .thief_fn = &").append(thiefName).append(",\n");
    sb.append("});\n");

    // Main thread computes first frame-adding sub-expression → locals.r1
    if (!frameAddingExprs.isEmpty()) {
      var firstFrameAdding = frameAddingExprs.getFirst();
      var firstExprCode = firstFrameAdding.expr.accept(this, true);
      sb.append("locals.r1 = ").append(firstExprCode).append(";\n");
    }

    // Pop and claim the frame using helper
    sb.append("if (shadow_stack_mod.popAndClaim(frame_idx)) |obligation| {\n");
    // Promoted path: deliver r1 to thief, wait for thief result
    sb.append("    shadow_stack_mod.fulfillChildObligation(frame_idx, locals.r1);\n");
    sb.append("    return obligation.wait(worker_mod.getCurrentWorker().?);\n");
    sb.append("}\n");

    // Not-promoted path: compute remaining frame-adding sub-exprs ourselves, call combiner
    for (int i = 1; i < frameAddingExprs.size(); i++) {
      var expr = frameAddingExprs.get(i);
      sb.append("const r").append(i + 1).append(" = ").append(expr.expr.accept(this, true)).append(";\n");
    }

    // Call combiner with all results
    sb.append("return ").append(emitCombiner(vpf, frameAddingExprs, plainExprs, false)).append(";\n");

    sb.append("}");
    functions.add(sb.toString());
  }

  /** Emit the thief function that computes all frame-adding sub-exprs except the first. */
  private void emitThiefFunction(String thiefName, String localsName, MIR.Fun fun,
                                  VPFCallInfo vpf, List<SubExprInfo> frameAddingExprs,
                                  Set<String> funParamNames) {
    // Build a thief-local codegen that prefixes param references with "locals."
    var thiefGen = new ThiefCodegen(this, funParamNames);

    var sb = new StringBuilder();
    sb.append("fn ").append(thiefName).append("(locals_ptr: *anyopaque, child_obl_opt: ?*JoinObligation) rt.FatPtr {\n");
    sb.append("const locals: *const ").append(localsName).append(" = @ptrCast(@alignCast(locals_ptr));\n");

    // Compute all frame-adding sub-exprs except the first (r2, r3, ...)
    for (int i = 1; i < frameAddingExprs.size(); i++) {
      var expr = frameAddingExprs.get(i);
      sb.append("const r").append(i + 1).append(" = ").append(expr.expr.accept(thiefGen, true)).append(";\n");
    }

    // Wait for r1 from main thread via child_obl_opt
    sb.append("const r1 = child_obl_opt.?.wait(worker_mod.getCurrentWorker().?);\n");

    // Call combiner
    var plainExprs = vpf.subExprs.stream().filter(s -> !s.isFrameAdding).toList();
    sb.append("return ").append(emitCombiner(vpf, frameAddingExprs, plainExprs, true)).append(";\n");
    sb.append("}");
    functions.add(sb.toString());
  }

  /**
   * Emit the combiner expression for a VPF call.
   * Replaces each sub-expression with its result variable name.
   * For magic types (Nat, Int, Str) we try to inline the intrinsic since they have no VTables.
   * Falls back to rt.call for non-magic types.
   * @param inThief if true, plain expressions use "locals." prefix for param refs
   */
  private String emitCombiner(VPFCallInfo vpf, List<SubExprInfo> frameAddingExprs,
                               List<SubExprInfo> plainExprs, boolean inThief) {
    // Build a mapping from sub-expr index to its emitted code
    var resultMap = new HashMap<Integer, String>();

    // Frame-adding exprs get result variable names
    // In the thief, r1 comes from child_obl_opt.wait() (a local variable), not locals.r1
    // In the main function, r1 is in locals.r1
    for (int i = 0; i < frameAddingExprs.size(); i++) {
      resultMap.put(frameAddingExprs.get(i).index, i == 0 ? (inThief ? "r1" : "locals.r1") : "r" + (i + 1));
    }

    // Build string args array with result variable names for all sub-expressions
    var allArgs = new String[vpf.subExprs.size()];
    for (var sub : vpf.subExprs) {
      if (resultMap.containsKey(sub.index)) {
        allArgs[sub.index] = resultMap.get(sub.index);
      } else {
        allArgs[sub.index] = inThief
          ? emitExprWithLocalsPrefix(sub.expr)
          : sub.expr.accept(this, true);
      }
    }

    // All method calls go through rt.call — intrinsics are handled by runtime dispatch
    String recvStr = allArgs[0];
    var argStrs = new ArrayList<String>();
    for (int i = 1; i < allArgs.length; i++) {
      argStrs.add(allArgs[i]);
    }

    var argsTuple = argStrs.isEmpty() ? ".{}" : ".{ " + String.join(", ", argStrs) + " }";
    return "rt.call(" + recvStr + ", " + vpf.hashName + ", " + argsTuple + ", @src())";
  }

  /** Emit an expression with locals. prefix for any X that is a function parameter. */
  private String emitExprWithLocalsPrefix(MIR.E expr) {
    if (expr instanceof MIR.X x) {
      return "locals." + id.varName(x.name());
    }
    return expr.accept(this, true);
  }

  /**
   * ThiefCodegen: a wrapper that overrides visitX to prefix param names with "locals."
   * for use inside thief functions where params are accessed via the locals struct pointer.
   */
  private static class ThiefCodegen implements MIRVisitor<String> {
    private final ZigSingleCodegen delegate;
    private final Set<String> paramNames;

    ThiefCodegen(ZigSingleCodegen delegate, Set<String> paramNames) {
      this.delegate = delegate;
      this.paramNames = paramNames;
    }

    @Override public String visitX(MIR.X x, boolean checkMagic) {
      if (paramNames.contains(x.name())) {
        return "locals." + delegate.id.varName(x.name());
      }
      return delegate.visitX(x, checkMagic);
    }
    @Override public String visitMCall(MIR.MCall call, boolean checkMagic) {
      // In the thief, all MCalls go through rt.call with locals-prefixed variable references.
      // We skip magic inlining here because (a) params come from a locals struct pointer, and
      // (b) not all magic methods are in the numOps table.
      var recv = call.recv().accept(this, checkMagic);
      var sig = new MIR.Sig(call.name(),
        call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
        call.originalRet());
      delegate.addHashConstant(sig);
      var hashName = delegate.sigBuilder.hashConstName(sig, delegate.id);

      var args = call.args().stream()
        .map(a -> a.accept(this, checkMagic))
        .collect(Collectors.joining(", "));
      var argsTuple = args.isEmpty() ? ".{}" : ".{ " + args + " }";
      return "rt.call(" + recv + ", " + hashName + ", " + argsTuple + ", @src())";
    }
    @Override public String visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
      return delegate.visitCreateObj(createObj, checkMagic);
    }
    @Override public String visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
      return delegate.visitBoolExpr(expr, checkMagic);
    }
    @Override public String visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
      return delegate.visitStaticCall(call, checkMagic);
    }
    @Override public String visitUpdatableListAsIdFnCall(MIR.UpdatableListAsIdFnCall call, boolean checkMagic) {
      return delegate.visitUpdatableListAsIdFnCall(call, checkMagic);
    }
  }

  @Override
  public String visitX(MIR.X x, boolean checkMagic) {
    return id.varName(x.name());
  }

  @Override
  public String visitMCall(MIR.MCall call, boolean checkMagic) {
    // Check magic first
    if (checkMagic && !call.variant().contains(MIR.MCall.CallVariant.Standard)) {
      // variant calls - not supported yet
    }

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
