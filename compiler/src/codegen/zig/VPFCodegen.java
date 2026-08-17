package codegen.zig;

import codegen.MIR;
import id.Id.DecId;
import visitors.MIRVisitor;

import java.util.*;
import java.util.stream.Collectors;

/// VPF (Very Parallel Fearless) codegen. It finds the VPF-eligible calls in a function body,
/// then emits parallel thief functions with shadow-frame instrumentation.
class VPFCodegen {
  private final ZigSingleCodegen parent;
  private int vpfCounter = 0;

  // Must match StolenTask.locals_copy size in worker.zig
  static final int LOCALS_COPY_LIMIT = 256;

  VPFCodegen(ZigSingleCodegen parent) {
    this.parent = parent;
  }

  record VPFCallInfo(
    MIR.MCall vpfCall,
    MIR.BoolExpr boolExpr, // null unless a BoolExpr wraps the call
    List<SubExprInfo> subExprs, // receiver at index 0, then the args
    List<SubExprInfo> plainExprs, // the sub-exprs that add no stack frame
    String hashName
  ) {}

  record SubExprInfo(MIR.E expr, boolean isFrameAdding, int index) {}

  /// Returns null when the body has no VPFParallelisable call.
  VPFCallInfo findVPFCall(MIR.E body) {
    return switch (body) {
      case MIR.Box box -> findVPFCall(box.inner());
      case MIR.MCall call -> {
        if (call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
          yield buildVPFInfo(call);
        }
        yield null;
      }
      case MIR.BoolExpr boolExpr -> {
        // The recursive case, and thus the VPF call, is usually the else-branch
        var elseFun = parent.funMap.get(boolExpr.else_());
        if (elseFun != null) {
          var inner = findVPFCallInner(elseFun.body());
          if (inner != null) {
            yield new VPFCallInfo(inner.vpfCall, boolExpr, inner.subExprs, inner.plainExprs, inner.hashName);
          }
        }
        yield null;
      }
      default -> null;
    };
  }

  /// True when the body of this function holds a VPFParallelisable call, also through a BoolExpr
  /// branch. The caller uses this to know if inlining a branch would discard the instrumentation
  /// of the branch function.
  boolean containsVPFCall(MIR.FName fName) {
    return containsVPFCall(fName, new HashSet<>());
  }

  private boolean containsVPFCall(MIR.FName fName, Set<MIR.FName> visited) {
    var cached = parent.vpfBranchCache.get(fName);
    if (cached != null) { return cached; }
    if (!visited.add(fName)) { return false; }
    var fun = parent.funMap.get(fName);
    var res = fun != null && containsVPFCall(fun.body(), visited);
    parent.vpfBranchCache.put(fName, res);
    return res;
  }

  /// A VPF call in the receiver or the args of another call is not reported, by design. The
  /// de-inlining of a BoolExpr branch cannot help it, so a search there de-inlines more branches
  /// than necessary.
  private boolean containsVPFCall(MIR.E body, Set<MIR.FName> visited) {
    return switch (body) {
      case MIR.Box box -> containsVPFCall(box.inner(), visited);
      case MIR.Block block -> containsVPFCall(block.original(), visited);
      case MIR.MCall call -> call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable);
      case MIR.BoolExpr boolExpr ->
        containsVPFCall(boolExpr.then(), visited) || containsVPFCall(boolExpr.else_(), visited);
      default -> false;
    };
  }

  /// Emits a locals struct, a thief function and an instrumented body.
  void emitVPFFun(MIR.Fun fun, String name, List<String> paramNames, String params, VPFCallInfo vpf) {
    int vpfId = vpfCounter++;
    var localsName = name + "_" + vpfId + "_Locals";
    var thiefName = name + "_" + vpfId + "_thief";

    var frameAddingExprs = vpf.subExprs.stream().filter(s -> s.isFrameAdding).toList();
    var funParamNames = new HashSet<String>();
    for (var arg : fun.args()) {
      funParamNames.add(arg.name());
    }

    var localsFields = new StringBuilder();
    for (var arg : fun.args()) {
      localsFields.append(parent.id.varName(arg.name())).append(": rt.FatPtr,\n");
    }
    localsFields.append("r1: rt.FatPtr,\n");
    parent.currentState().captureStructs.put(
      new DecId(localsName, 0),
      "const " + localsName + " = extern struct {\n" + localsFields + "};"
    );
    emitLocalsHooks(localsName, fun.args().stream().map(a -> parent.id.varName(a.name())).toList());

    var remaining = frameAddingExprs.subList(1, frameAddingExprs.size());
    if (remaining.size() >= 2 && canDeepenVPF(fun, 1)) {
      emitVPFThiefFunction(thiefName, localsName, fun, vpf, frameAddingExprs,
        remaining, funParamNames, List.of());
    } else {
      emitSimpleThiefFunction(thiefName, localsName, fun, vpf, frameAddingExprs,
        funParamNames, List.of());
    }

    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("(").append(params).append(") rt.FatPtr {\n");
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }

    // Promotion runs before the base-case check, so that it always runs
    sb.append("heartbeat.tryPromote();\n");
    for (var paramName : paramNames) {
      sb.append("defer ").append(paramName).append(".rc_decrement();\n");
    }

    // The base case becomes an early return
    if (vpf.boolExpr != null) {
      var cond = vpf.boolExpr.condition().accept(parent, true);
      var thenBranch = vpf.boolExpr.then();
      String thenBody = parent.deInlinedBranch(thenBranch, parent, true)
        .orElseGet(() -> parent.returnExpr(parent.funMap.get(thenBranch).body(), true));
      sb.append("if (").append(cond).append(".vt == &").append(parent.vtableRef(new DecId("base.True", 0))).append(") return ").append(thenBody).append(";\n");
    }

    sb.append("var locals = ").append(localsName).append("{ ");
    for (var arg : fun.args()) {
      sb.append(".").append(parent.id.varName(arg.name())).append(" = ").append(parent.id.varName(arg.name())).append(", ");
    }
    sb.append(".r1 = undefined };\n");

    // Compiler fence. It writes the locals to memory before the frame push
    sb.append("asm volatile (\"\" ::: .{ .memory = true });\n");

    emitPushFrame(sb, vpf.hashName, "locals", localsName, thiefName);

    if (!frameAddingExprs.isEmpty()) {
      var firstFrameAdding = frameAddingExprs.getFirst();
      var firstExprCode = firstFrameAdding.expr.accept(parent, true);
      sb.append("locals.r1 = ").append(firstExprCode).append(";\n");
    }

    sb.append("if (frame_idx_opt) |frame_idx| {\n");
    sb.append("    if (shadow_stack_mod.popAndClaim(frame_idx)) |obligation| {\n");
    // Promoted path: give r1 to the thief, then wait for its result
    sb.append("        shadow_stack_mod.fulfillChildObligation(frame_idx, locals.r1);\n");
    sb.append("        const wait_result = obligation.wait(worker_mod.getCurrentWorker().?);\n");
    sb.append("        shadow_stack_mod.freeObligation(obligation);\n");
    // The thief usually delivers its combined result here. But if it unwound, from a
    // deterministic Error! or an ND fault, it fulfilled the obligation with a tag-typed error
    // payload. Unwind again on this fiber, so that the error goes up one level and does not
    // enter the combiner as a value.
    sb.append("        if (error_rt.tagOf(wait_result) != .none) errors.feart_unwind(wait_result);\n");
    sb.append("        return wait_result;\n");
    sb.append("    }\n");
    sb.append("}\n");

    // Not-promoted path: compute the remaining frame-adding sub-exprs here
    for (int i = 1; i < frameAddingExprs.size(); i++) {
      var expr = frameAddingExprs.get(i);
      sb.append("const r").append(i + 1).append(" = ").append(expr.expr.accept(parent, true)).append(";\n");
    }

    var resultMap = new HashMap<Integer, String>();
    for (int i = 0; i < frameAddingExprs.size(); i++) {
      resultMap.put(frameAddingExprs.get(i).index, i == 0 ? "locals.r1" : "r" + (i + 1));
    }
    for (var sub : vpf.plainExprs) {
      resultMap.put(sub.index, parent.ownedExpr(sub.expr, true));
    }
    sb.append("return ").append(emitCombinerFromMap(vpf, resultMap, parent)).append(";\n");

    sb.append("}");
    parent.currentState().functions.add(sb.toString());
  }

  /// As findVPFCall, but it does not look through a BoolExpr.
  private VPFCallInfo findVPFCallInner(MIR.E body) {
    if (body instanceof MIR.Box box) {
      return findVPFCallInner(box.inner());
    }
    if (body instanceof MIR.MCall call &&
        call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
      return buildVPFInfo(call);
    }
    return null;
  }

  private VPFCallInfo buildVPFInfo(MIR.MCall call) {
    var subExprs = new ArrayList<SubExprInfo>();
    subExprs.add(new SubExprInfo(call.recv(), isFrameAddingExpr(call.recv()), 0));
    for (int i = 0; i < call.args().size(); i++) {
      var arg = call.args().get(i);
      subExprs.add(new SubExprInfo(arg, isFrameAddingExpr(arg), i + 1));
    }

    var sig = new MIR.Sig(call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet());
    var hashExpr = parent.sigBuilder.inlineHash(sig);

    var plainExprs = subExprs.stream().filter(s -> !s.isFrameAdding).toList();
    return new VPFCallInfo(call, null, subExprs, plainExprs, hashExpr);
  }

  /// Only an MCall and a BoolExpr add a stack frame. A Box is transparent here.
  private boolean isFrameAddingExpr(MIR.E expr) {
    if (expr instanceof MIR.Box box) {
      return isFrameAddingExpr(box.inner());
    }
    return expr instanceof MIR.MCall || expr instanceof MIR.BoolExpr;
  }

  /// An instrumented thief. It computes one sub-expr, then pushes a shadow frame for the inner
  /// thief. On the stolen path it waits for the result of the inner thief. On the not-stolen
  /// path it computes the remaining sub-exprs in sequence.
  private void emitVPFThiefFunction(String thiefName, String localsName, MIR.Fun fun,
                                     VPFCallInfo vpf, List<SubExprInfo> allFrameAddingExprs,
                                     List<SubExprInfo> remainingFrameAdding,
                                     Set<String> funParamNames,
                                     List<String> forwardedChildOblFields) {
    int innerVpfId = vpfCounter++;
    var innerLocalsName = thiefName + "_" + innerVpfId + "_Locals";
    var innerThiefName = thiefName + "_" + innerVpfId + "_thief";

    var thiefGen = new ThiefCodegen(parent, funParamNames);

    var myExpr = remainingFrameAdding.getFirst();
    int myGlobalIdx = allFrameAddingExprs.indexOf(myExpr);

    var innerFields = new StringBuilder();
    for (var arg : fun.args()) {
      innerFields.append(parent.id.varName(arg.name())).append(": rt.FatPtr,\n");
    }
    var newForwardedFields = new ArrayList<>(forwardedChildOblFields);
    for (var fwdField : forwardedChildOblFields) {
      innerFields.append(fwdField).append(": usize,\n");
    }
    var newFwdFieldName = "fwd_child_obl_" + forwardedChildOblFields.size();
    innerFields.append(newFwdFieldName).append(": usize,\n");
    newForwardedFields.add(newFwdFieldName);
    innerFields.append("r_thief: rt.FatPtr,\n");

    parent.currentState().captureStructs.put(
      new DecId(innerLocalsName, 0),
      "const " + innerLocalsName + " = extern struct {\n" + innerFields + "};"
    );
    emitLocalsHooks(innerLocalsName, fun.args().stream().map(a -> parent.id.varName(a.name())).toList());

    var innerRemaining = remainingFrameAdding.subList(1, remainingFrameAdding.size());
    if (innerRemaining.size() >= 2 && canDeepenVPF(fun, newForwardedFields.size())) {
      emitVPFThiefFunction(innerThiefName, innerLocalsName, fun, vpf,
        allFrameAddingExprs, innerRemaining, funParamNames, newForwardedFields);
    } else {
      emitSimpleThiefFunction(innerThiefName, innerLocalsName, fun, vpf,
        allFrameAddingExprs, funParamNames, newForwardedFields);
    }

    int fwdCount = forwardedChildOblFields.size();

    var sb = new StringBuilder();
    sb.append("fn ").append(thiefName).append("(locals_ptr: *anyopaque, child_obl_opt: ?*JoinObligation) rt.FatPtr {\n");
    sb.append("const locals: *const ").append(localsName).append(" = @ptrCast(@alignCast(locals_ptr));\n");

    sb.append("var thief_locals = ").append(innerLocalsName).append("{ ");
    for (var arg : fun.args()) {
      var vn = parent.id.varName(arg.name());
      sb.append(".").append(vn).append(" = locals.").append(vn).append(", ");
    }
    for (var fwdField : forwardedChildOblFields) {
      sb.append(".").append(fwdField).append(" = locals.").append(fwdField).append(", ");
    }
    sb.append(".").append(newFwdFieldName).append(" = @intFromPtr(child_obl_opt), ");
    sb.append(".r_thief = undefined };\n");

    sb.append("asm volatile (\"\" ::: .{ .memory = true });\n");

    emitPushFrame(sb, vpf.hashName, "thief_locals", innerLocalsName, innerThiefName);

    sb.append("thief_locals.r_thief = ").append(myExpr.expr.accept(thiefGen, true)).append(";\n");

    // Stolen path: give the result to the inner thief, then wait for its combined result
    sb.append("if (frame_idx_opt) |frame_idx| {\n");
    sb.append("    if (shadow_stack_mod.popAndClaim(frame_idx)) |inner_obl| {\n");
    sb.append("        shadow_stack_mod.fulfillChildObligation(frame_idx, thief_locals.r_thief);\n");
    sb.append("        const wait_result = inner_obl.wait(worker_mod.getCurrentWorker().?);\n");
    sb.append("        shadow_stack_mod.freeObligation(inner_obl);\n");
    sb.append("        if (error_rt.tagOf(wait_result) != .none) errors.feart_unwind(wait_result);\n");
    sb.append("        return wait_result;\n");
    sb.append("    }\n");
    sb.append("}\n");

    // Not-stolen path
    emitThiefTail(sb, thiefGen, remainingFrameAdding.subList(1, remainingFrameAdding.size()),
      allFrameAddingExprs, vpf, fwdCount, myGlobalIdx, forwardedChildOblFields);

    sb.append("}");
    parent.currentState().functions.add(sb.toString());
  }

  private void emitLocalsHooks(String localsName, List<String> fatPtrFields) {
    var retain = new StringBuilder();
    retain.append("fn ").append(localsName).append("_retain(copy_ptr: *anyopaque, parent_ptr: *anyopaque) void {\n");
    retain.append("const copy: *").append(localsName).append(" = @ptrCast(@alignCast(copy_ptr));\n");
    retain.append("const parent: *").append(localsName).append(" = @ptrCast(@alignCast(parent_ptr));\n");
    if (fatPtrFields.isEmpty()) {
      retain.append("_ = .{ copy, parent };\n");
    } else {
      for (var field : fatPtrFields) {
        retain.append("if (parent.").append(field).append(".is_transient()) parent.").append(field).append(" = parent.").append(field).append(".box_transient();\n");
        retain.append("copy.").append(field).append(" = parent.").append(field).append(".share();\n");
      }
    }
    retain.append("}");
    parent.currentState().functions.add(retain.toString());

    var drop = new StringBuilder();
    drop.append("fn ").append(localsName).append("_drop(locals_ptr: *anyopaque) void {\n");
    drop.append("const locals: *const ").append(localsName).append(" = @ptrCast(@alignCast(locals_ptr));\n");
    if (fatPtrFields.isEmpty()) {
      drop.append("_ = locals;\n");
    } else {
      for (var field : fatPtrFields) {
        drop.append("locals.").append(field).append(".rc_decrement();\n");
      }
    }
    drop.append("}");
    parent.currentState().functions.add(drop.toString());
  }

  /// The innermost thief. It computes its sub-exprs in sequence, waits for the obligation of its
  /// parent, waits for the forwarded obligations, then calls the full combiner.
  private void emitSimpleThiefFunction(String thiefName, String localsName, MIR.Fun fun,
                                        VPFCallInfo vpf, List<SubExprInfo> frameAddingExprs,
                                        Set<String> funParamNames,
                                        List<String> forwardedChildOblFields) {
    var thiefGen = new ThiefCodegen(parent, funParamNames);
    int fwdCount = forwardedChildOblFields.size();

    // The body comes first, because it shows if a locals decl is necessary
    var body = new StringBuilder();
    emitThiefTail(body, thiefGen, frameAddingExprs.subList(fwdCount + 1, frameAddingExprs.size()),
      frameAddingExprs, vpf, fwdCount, -1, forwardedChildOblFields);

    var sb = new StringBuilder();
    sb.append("fn ").append(thiefName).append("(locals_ptr: *anyopaque, child_obl_opt: ?*JoinObligation) rt.FatPtr {\n");
    if (body.toString().contains("locals.")) {
      sb.append("const locals: *const ").append(localsName).append(" = @ptrCast(@alignCast(locals_ptr));\n");
    } else {
      sb.append("_ = locals_ptr;\n");
    }
    sb.append(body);

    sb.append("}");
    parent.currentState().functions.add(sb.toString());
  }

  /// The shared tail of a thief function. It computes the remaining exprs, waits for the
  /// obligations, then combines and returns.
  private void emitThiefTail(StringBuilder sb, ThiefCodegen thiefGen,
                              List<SubExprInfo> exprsToCompute,
                              List<SubExprInfo> allFrameAdding,
                              VPFCallInfo vpf,
                              int fwdCount, int myGlobalIdx,
                              List<String> forwardedChildOblFields) {
    for (var expr : exprsToCompute) {
      int globalIdx = allFrameAdding.indexOf(expr);
      sb.append("const r").append(globalIdx + 1).append(" = ")
        .append(expr.expr.accept(thiefGen, true)).append(";\n");
    }
    sb.append("const r1 = child_obl_opt.?.wait(worker_mod.getCurrentWorker().?);\n");
    sb.append("shadow_stack_mod.freeObligation(child_obl_opt.?);\n");
    // child_obl_opt holds the sub-expr value of the parent. But if the parent unwound,
    // feart_unwind put a tag-typed error here. Unwind again on this fiber, to send it up.
    sb.append("if (error_rt.tagOf(r1) != .none) errors.feart_unwind(r1);\n");
    emitWaitForwardedObligations(sb, forwardedChildOblFields);
    var resultMap = buildThiefCombinerMap(allFrameAdding, fwdCount, myGlobalIdx);
    sb.append("return ").append(emitCombinerFromMap(vpf, resultMap, thiefGen)).append(";\n");
  }

  private void emitPushFrame(StringBuilder sb, String hashName,
                              String localsVar, String localsTypeName,
                              String thiefFnName) {
    sb.append("const frame_idx_opt = shadow_stack_mod.pushFrame(.{\n");
    sb.append("    .target_method = ").append(hashName).append(",\n");
    sb.append("    .join_obligation = std.atomic.Value(?*JoinObligation).init(null),\n");
    sb.append("    .child_obligation = std.atomic.Value(?*JoinObligation).init(null),\n");
    sb.append("    .locals = @ptrCast(&").append(localsVar).append("),\n");
    sb.append("    .locals_size = @sizeOf(").append(localsTypeName).append("),\n");
    sb.append("    .retain_fn = &").append(localsTypeName).append("_retain,\n");
    sb.append("    .drop_fn = &").append(localsTypeName).append("_drop,\n");
    sb.append("    .thief_fn = &").append(thiefFnName).append(",\n");
    sb.append("});\n");
  }

  private void emitWaitForwardedObligations(StringBuilder sb,
                                              List<String> forwardedChildOblFields) {
    for (int i = forwardedChildOblFields.size() - 1; i >= 0; i--) {
      var field = forwardedChildOblFields.get(i);
      sb.append("const fwd_obl_").append(i).append(": ?*JoinObligation = @ptrFromInt(locals.").append(field).append(");\n");
      sb.append("const fwd_r").append(i).append(" = fwd_obl_").append(i).append(".?.wait(worker_mod.getCurrentWorker().?);\n");
      sb.append("if (error_rt.tagOf(fwd_r").append(i).append(") != .none) errors.feart_unwind(fwd_r").append(i).append(");\n");
    }
  }

  private Map<Integer, String> buildThiefCombinerMap(List<SubExprInfo> frameAddingExprs,
                                                      int fwdCount, int myGlobalIdx) {
    var resultMap = new HashMap<Integer, String>();

    // A forwarded obligation holds the sub-expr with the same index: fwd_child_obl_0 holds e0
    for (int i = 0; i < fwdCount; i++) {
      resultMap.put(frameAddingExprs.get(i).index, "fwd_r" + i);
    }

    // child_obl_opt delivers the sub-expr of the parent
    resultMap.put(frameAddingExprs.get(fwdCount).index, "r1");

    // A simple thief computes no sub-expr of its own, and thus has no index here
    if (myGlobalIdx >= 0) {
      resultMap.put(frameAddingExprs.get(myGlobalIdx).index, "thief_locals.r_thief");
    }

    int startIdx = (myGlobalIdx >= 0) ? myGlobalIdx + 1 : fwdCount + 1;
    for (int i = startIdx; i < frameAddingExprs.size(); i++) {
      if (!resultMap.containsKey(frameAddingExprs.get(i).index)) {
        resultMap.put(frameAddingExprs.get(i).index, "r" + (i + 1));
      }
    }

    return resultMap;
  }

  private String emitCombinerFromMap(VPFCallInfo vpf, Map<Integer, String> resultMap, MIRVisitor<String> codegen) {
    var allArgs = new String[vpf.subExprs.size()];
    for (var sub : vpf.subExprs) {
      if (resultMap.containsKey(sub.index)) {
        allArgs[sub.index] = resultMap.get(sub.index);
      } else {
        allArgs[sub.index] = (sub.expr instanceof MIR.X x)
          ? "locals." + parent.id.varName(x.name()) + ".share()"
          : sub.expr.accept(codegen, true);
      }
    }

    String recvStr = allArgs[0];
    var argStrs = new ArrayList<String>();
    for (int i = 1; i < allArgs.length; i++) {
      argStrs.add(allArgs[i]);
    }
    var argsTuple = argStrs.isEmpty() ? ".{}" : ".{ " + String.join(", ", argStrs) + " }";
    return "rt.call(" + recvStr + ", " + vpf.hashName + ", " + argsTuple + ", @src())";
  }

  /// True when one more thief level keeps the locals in the runtime buffer.
  private boolean canDeepenVPF(MIR.Fun fun, int nextFwdCount) {
    int structSize = fun.args().size() * 16  // FatPtr params
                   + nextFwdCount * 8         // forwarded obligation usize fields
                   + 16;                      // r_thief FatPtr
    return structSize <= LOCALS_COPY_LIMIT;
  }

  /// A wrapper that gives each param name the "locals." prefix. A thief function reads its
  /// params through the locals struct pointer.
  static class ThiefCodegen implements MIRVisitor<String> {
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
      return delegate.emitMCall(call, this, checkMagic);
    }
    @Override public String visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
      // The parent does the type and vtable emission, and the magic
      String parentResult = delegate.visitCreateObj(createObj, checkMagic);

      // A singleton or magic result has no captures to prefix
      if (createObj.captures().isEmpty()) { return parentResult; }
      if (parentResult.contains("obj_k_singleton") || !parentResult.contains("obj_k(")) { return parentResult; }

      // Generate again with this visitX, to give the captures the locals. prefix
      var objId = createObj.concreteT().id();
      var captures = createObj.captures().stream()
        .map(x -> "." + delegate.id.varName(x.name()) + " = " + this.visitX(x, checkMagic))
        .collect(Collectors.joining(", "));
      return "rt.obj_k(" + delegate.capturesRef(objId) + ", &" + delegate.vtableRef(objId) + ", .{ " + captures + " })";
    }
    @Override public String visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
      return delegate.boolExpr(expr, this, checkMagic, false);
    }
    @Override public String visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
      var fRef = delegate.funRef(call.fun());
      var prelude = new ArrayList<String>();
      var args = call.args().stream()
        .map(a -> {
          if (delegate.isTransientCreateObj(a)) {
            var materialised = delegate.materialiseTransient((MIR.CreateObj) a, this, checkMagic);
            prelude.addAll(materialised.prelude());
            return materialised.ref();
          }
          return delegate.ownedExpr(a, this, checkMagic);
        })
        .collect(Collectors.joining(", "));
      return delegate.withTransientPrelude(prelude, fRef + "(" + args + ")");
    }
    @Override public String visitBox(MIR.Box box, boolean checkMagic) {
      return delegate.boxExpr(box.inner(), this, checkMagic);
    }

  }
}
