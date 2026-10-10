package codegen.zig;

import codegen.MIR;
import codegen.optimisations.RcFreeTypes;
import id.Id.DecId;
import utils.Bug;
import visitors.MIRVisitor;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/// Emits Zig for calls that have the `VPFParallelisable` variant. Each call pushes one frame that the runtime can promote.
/// A promoted frame runs the emitted thief function, which computes the remaining operands.
class VPFCodegen {
  private final ZigSingleCodegen parent;
  private int vpfCounter = 0;

  /// Maximum size in bytes of a locals struct that a frame can carry.
  /// On promotion the runtime copies the struct into `locals_copy` in worker.zig, which has this size.
  static final int LOCALS_COPY_LIMIT = 256;

  VPFCodegen(ZigSingleCodegen parent) {
    this.parent = parent;
  }

  record VPFCallInfo(
    MIR.MCall vpfCall,
    MIR.BoolExpr boolExpr,
    List<SubExprInfo> subExprs,
    List<SubExprInfo> plainExprs,
    String hashName,
    Optional<DecId> directTarget,
    Optional<DecId> guardTarget
  ) {}

  record SubExprInfo(MIR.E expr, boolean isFrameAdding, int index) {}

  VPFCallInfo findVPFCall(MIR.E body) {
    return switch (body) {
      case MIR.Box box -> findVPFCall(box.inner());
      case MIR.MCall call -> {
        if (call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
          yield buildVPFInfo(call, Optional.empty());
        }
        yield null;
      }
      case MIR.DirectCall dc when dc.original().variant().contains(MIR.MCall.CallVariant.VPFParallelisable) ->
        buildVPFInfo(dc.original(), Optional.of(dc.concreteType()));
      case MIR.GuardedCall gc when gc.original().variant().contains(MIR.MCall.CallVariant.VPFParallelisable) ->
        buildGuardedVPFInfo(gc);
      case MIR.BoolExpr boolExpr -> {
        var elseFun = parent.funMap.get(boolExpr.else_());
        if (elseFun != null) {
          var inner = findVPFCallInner(elseFun.body());
          if (inner != null) {
            yield new VPFCallInfo(inner.vpfCall, boolExpr, inner.subExprs, inner.plainExprs, inner.hashName, inner.directTarget, inner.guardTarget);
          }
        }
        yield null;
      }
      default -> null;
    };
  }

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

  private boolean containsVPFCall(MIR.E body, Set<MIR.FName> visited) {
    return switch (body) {
      case MIR.Box box -> containsVPFCall(box.inner(), visited);
      case MIR.Block block -> containsVPFCall(block.original(), visited);
      case MIR.MCall call -> call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable);
      case MIR.DirectCall dc -> dc.original().variant().contains(MIR.MCall.CallVariant.VPFParallelisable);
      case MIR.GuardedCall gc -> gc.original().variant().contains(MIR.MCall.CallVariant.VPFParallelisable);
      case MIR.BoolExpr boolExpr ->
        containsVPFCall(boolExpr.then(), visited) || containsVPFCall(boolExpr.else_(), visited);
      default -> false;
    };
  }

  /// Emits the function that owns a VPF call. It pushes a frame and computes the first operand, then combines
  /// in place, or publishes that result and waits when a thief runs the other operands.
  void emitVPFFun(
      MIR.Fun fun,
      String name,
      ZigSingleCodegen.FunSignature signature,
      List<ZigSingleCodegen.Drop> dropNames,
      VPFCallInfo vpf
  ) {
    int vpfId = vpfCounter++;
    var localsName = name + "_" + vpfId + "_Locals";
    var thiefName = name + "_" + vpfId + "_thief";
    var params = signature.params();
    var paramNames = signature.discardNames();

    var frameAddingExprs = vpf.subExprs.stream().filter(s -> s.isFrameAdding).toList();
    var funParamExprs = thiefParamExprs(fun);

    var localsFields = new StringBuilder(localsFieldDecls(fun));
    var firstResultExpr = frameAddingExprs.isEmpty() ? null : frameAddingExprs.getFirst().expr();
    localsFields.append("r1: ").append(resultType(firstResultExpr)).append(",\n");
    parent.currentState().captureStructs.put(
      new DecId(localsName, 0),
      "const " + localsName + " = extern struct {\n" + localsFields + "};"
    );
    emitLocalsHooks(localsName, fun);

    var remaining = frameAddingExprs.subList(1, frameAddingExprs.size());
    if (remaining.size() >= 2 && canDeepenVPF(fun, 1)) {
      emitVPFThiefFunction(
        thiefName,
        localsName,
        fun,
        vpf,
        frameAddingExprs,
        remaining,
        funParamExprs,
        List.of()
      );
    } else {
      emitSimpleThiefFunction(
        thiefName,
        localsName,
        fun,
        vpf,
        frameAddingExprs,
        funParamExprs,
        List.of()
      );
    }

    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("(").append(params).append(") callconv(.c) ")
      .append(parent.funResultShape(fun.name()).map(ZigSingleCodegen.Scalar::zigType).orElse("rt.FatPtr"))
      .append(" {\n");
    sb.append(signature.prologue());
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }

    sb.append("heartbeat.tryPromote();\n");
    for (var drop : dropNames) {
      sb.append("defer ").append(parent.generateDecrement(drop.name(), drop.t())).append(";\n");
    }

    if (vpf.boolExpr != null) {
      var cond = vpf.boolExpr.condition().accept(parent, true);
      var thenBranch = vpf.boolExpr.then();
      var deInlined = parent.deInlinedBranch(thenBranch, parent, true);
      String thenBody = deInlined
        .orElseGet(() -> parent.returnExpr(parent.funMap.get(thenBranch).body(), true));
      sb.append("if (").append(parent.boolCondition(vpf.boolExpr.condition(), cond)).append(") return ")
        .append(earlyReturnResult(fun, thenBranch, thenBody, deInlined.isPresent()))
        .append(";\n");
    }

    sb.append("var locals = ").append(localsName).append("{ ");
    var shape = parent.funShape(fun.name());
    for (int i = 0; i < fun.args().size(); i++) {
      if (i < shape.size() && shape.get(i).elided()) { continue; }
      sb.append(".").append(parent.id.varName(fun.args().get(i).name()))
        .append(" = ").append(signature.scalarNames().get(i)).append(", ");
    }
    sb.append(".r1 = undefined };\n");

    var firstSlot = frameAddingExprs.isEmpty()
      ? Optional.<ZigSingleCodegen.VPFResultSlot>empty()
      : parent.vpfResultSlot(frameAddingExprs.getFirst().expr);
    firstSlot.ifPresent(slot -> {
      sb.append(slot.decl());
      sb.append(slot.dropDefer());
    });

    // Forces the stores to `locals` before the push. A promotion can copy `locals` at each nested call.
    sb.append("asm volatile (\"\" ::: .{ .memory = true });\n");

    // The first operand can promote this frame, so the push comes first.
    emitPushFrame(sb, vpf.hashName, "locals", localsName, thiefName);

    var firstResult = parent.freshName("fear_vpf_first_");
    var borrowedFirst = frameAddingExprs.isEmpty() || firstSlot.isPresent()
      ? Optional.<ZigSingleCodegen.BorrowedCapture>empty()
      : parent.borrowedCaptureRead(frameAddingExprs.getFirst().expr(), parent, true);
    if (!frameAddingExprs.isEmpty()) {
      if (borrowedFirst.isPresent()) {
        sb.append("const ").append(firstResult).append(": rt.FatPtr = ")
          .append(borrowedFirst.get().read()).append(";\n");
      } else if (firstSlot.isPresent()) {
        sb.append(firstSlot.get().operandStatements());
        sb.append("const ").append(firstResult).append(": ").append(resultType(firstResultExpr)).append(" = ")
          .append(nativeResult(firstResultExpr, firstSlot.get().call(), parent)).append(";\n");
      } else {
        var firstExprCode = frameAddingExprs.getFirst().expr.accept(parent, true);
        sb.append("const ").append(firstResult).append(": ").append(resultType(firstResultExpr)).append(" = ")
          .append(nativeResult(firstResultExpr, firstExprCode, parent)).append(";\n");
      }
      // `locals.r1` copies the local without a count. Each path consumes only one of the two names.
      sb.append("locals.r1 = ").append(firstResult).append(";\n");
    }
    // The thief consumes a count on the published result, so a borrowed result gets one here.
    var published = borrowedFirst
      .map(borrowed -> parent.generateShare("locals.r1", borrowed.type()))
      .orElseGet(() -> boxedResult(firstResultExpr, "locals.r1"));

    sb.append("if (frame_idx_opt) |frame_idx| {\n");
    sb.append("    if (shadow_stack_mod.popAndClaim(frame_idx)) |obligation| {\n");
    sb.append("        if (!shadow_stack_mod.reclaimPromotion(frame_idx, obligation)) {\n");
    sb.append("        shadow_stack_mod.fulfillChildObligation(frame_idx, ").append(published).append(");\n");
    sb.append("        const wait_result = obligation.wait(worker_mod.getCurrentWorker().?);\n");
    sb.append("        shadow_stack_mod.freeObligation(obligation);\n");
    sb.append("        if (error_rt.tagOf(wait_result) != .none) errors.joinFailed(frame_idx, wait_result);\n");
    sb.append("        return ").append(declaredResult(fun, Optional.empty(), "wait_result")).append(";\n");
    sb.append("        }\n");
    sb.append("    }\n");
    sb.append("}\n");

    // No thief took the frame: compute the remaining operands here.
    var borrowedRest = new HashSet<Integer>();
    for (int i = 1; i < frameAddingExprs.size(); i++) {
      var expr = frameAddingExprs.get(i);
      var borrowed = parent.borrowedCaptureRead(expr.expr, parent, true);
      if (borrowed.isPresent()) {
        sb.append("const r").append(i + 1).append(": rt.FatPtr = ").append(borrowed.get().read()).append(";\n");
        borrowedRest.add(i);
        continue;
      }
      var code = expr.expr.accept(parent, true);
      sb.append("const r").append(i + 1).append(" = ").append(nativeResult(expr.expr, code, parent)).append(";\n");
    }

    var resultMap = new HashMap<Integer, String>();
    var owned = new ArrayList<ZigSingleCodegen.Drop>();
    for (int i = 0; i < frameAddingExprs.size(); i++) {
      var resultRef = i == 0 ? firstResult : "r" + (i + 1);
      var resultExpr = frameAddingExprs.get(i).expr;
      resultMap.put(frameAddingExprs.get(i).index, resultRef);
      var isBorrowed = i == 0 ? borrowedFirst.isPresent() : borrowedRest.contains(i);
      if (!parent.isRcFree(resultExpr) && !isBorrowed) {
        owned.add(new ZigSingleCodegen.Drop(resultRef, resultExpr.t()));
      }
    }
    for (var sub : vpf.plainExprs) {
      if (sub.expr instanceof MIR.X || parent.isRcFree(sub.expr)) {
        resultMap.put(sub.index, sub.expr.accept(parent, true));
        continue;
      }
      var tmp = parent.freshName("fear_vpf_arg_");
      sb.append("const ").append(tmp).append(" = ").append(sub.expr.accept(parent, true)).append(";\n");
      resultMap.put(sub.index, tmp);
      owned.add(new ZigSingleCodegen.Drop(tmp, sub.expr.t()));
    }
    var combined = parent.freshName("fear_vpf_res_");
    sb.append("const ").append(combined).append(" = ")
      .append(emitCombinerFromMap(vpf, resultMap, parent)).append(";\n");
    for (var drop : owned) {
      sb.append(parent.generateDecrement(drop.name(), drop.t())).append(";\n");
    }
    sb.append("return ")
      .append(declaredResult(fun, parent.scalarSumOf(vpf.vpfCall().t()), combined))
      .append(";\n");

    sb.append("}");
    parent.currentState().functions.add(sb.toString());
  }

  /// Converts an owned value to the declared result representation of `fun`.
  /// `source` is the scalar that the value holds, or empty for a boxed value.
  private String declaredResult(MIR.Fun fun, Optional<ZigSingleCodegen.Scalar> source, String code) {
    var target = parent.funResultShape(fun.name());
    if (target.isEmpty()) {
      return source.map(scalar -> parent.toBoxedOwned(scalar, code)).orElse(code);
    }
    return source
      .map(scalar -> parent.reshapeScalarOwned(scalar, target.orElseThrow(), code))
      .orElseGet(() -> parent.toScalarOwned(target.orElseThrow(), code));
  }

  private String earlyReturnResult(MIR.Fun fun, MIR.FName arm, String code, boolean deInlined) {
    if (deInlined) { return declaredResult(fun, parent.funResultShape(arm), code); }
    var body = parent.funMap.get(arm).body();
    var expression = body instanceof MIR.Block block ? block.original() : body;
    return parent.funResultShape(fun.name())
      .map(target -> parent.scalarOwned(target, expression, code, parent, true))
      .orElseGet(() -> parent.boxedOwned(expression, code));
  }

  private VPFCallInfo findVPFCallInner(MIR.E body) {
    if (body instanceof MIR.Box(MIR.E inner)) {
      return findVPFCallInner(inner);
    }
    if (body instanceof MIR.MCall call &&
        call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
      return buildVPFInfo(call, Optional.empty());
    }
    if (body instanceof MIR.DirectCall(MIR.MCall original, DecId concreteType) &&
        original.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
      return buildVPFInfo(original, Optional.of(concreteType));
    }
    if (body instanceof MIR.GuardedCall gc &&
        gc.original().variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
      return buildGuardedVPFInfo(gc);
    }
    return null;
  }

  private VPFCallInfo buildGuardedVPFInfo(MIR.GuardedCall call) {
    var info = buildVPFInfo(call.original(), Optional.empty());
    return new VPFCallInfo(
      info.vpfCall(),
      info.boolExpr(),
      info.subExprs(),
      info.plainExprs(),
      info.hashName(),
      info.directTarget(),
      Optional.of(call.concreteType())
    );
  }

  private VPFCallInfo buildVPFInfo(MIR.MCall call, Optional<DecId> directTarget) {
    var subExprs = new ArrayList<SubExprInfo>();
    subExprs.add(new SubExprInfo(call.recv(), isFrameAddingExpr(call.recv()), 0));
    for (int i = 0; i < call.args().size(); i++) {
      var arg = call.args().get(i);
      subExprs.add(new SubExprInfo(arg, isFrameAddingExpr(arg), i + 1));
    }

    var sig = new MIR.Sig(
      call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet()
    );
    var hashExpr = parent.sigBuilder.inlineHash(sig);

    var plainExprs = subExprs.stream().filter(s -> !s.isFrameAdding).toList();
    plainExprs.stream()
      .map(SubExprInfo::expr)
      .filter(e -> !isInfallibleExpr(e))
      .findFirst()
      .ifPresent(e -> { throw Bug.of("VPF sub-expression " + e.getClass().getSimpleName()
        + " can fail but is not frame-adding, so it would be reordered past the parts to its right"); });
    return new VPFCallInfo(call, null, subExprs, plainExprs, hashExpr, directTarget, Optional.empty());
  }

  private boolean isFrameAddingExpr(MIR.E expr) {
    if (expr instanceof MIR.Box box) {
      return isFrameAddingExpr(box.inner());
    }
    return expr instanceof MIR.MCall || expr instanceof MIR.DirectCall
      || expr instanceof MIR.GuardedCall || expr instanceof MIR.BoolExpr
      || expr instanceof MIR.SumMatch;
  }

  /// True when `expr` cannot fail. The emitted code computes an operand that adds no frame after the
  /// operands that add one, so a failure there would lose its left-to-right position.
  private static boolean isInfallibleExpr(MIR.E expr) {
    if (expr instanceof MIR.Box(MIR.E inner)) { return isInfallibleExpr(inner); }
    return expr instanceof MIR.X || expr instanceof MIR.CreateObj;
  }

  /// Emits a thief that computes one operand and pushes a frame for the operands after it.
  /// It then joins the earlier results and calls the combiner.
  private void emitVPFThiefFunction(
      String thiefName,
      String localsName,
      MIR.Fun fun,
      VPFCallInfo vpf,
      List<SubExprInfo> allFrameAddingExprs,
      List<SubExprInfo> remainingFrameAdding,
      Map<String, String> funParamExprs,
      List<String> forwardedChildOblFields
  ) {
    int innerVpfId = vpfCounter++;
    var innerLocalsName = thiefName + "_" + innerVpfId + "_Locals";
    var innerThiefName = thiefName + "_" + innerVpfId + "_thief";

    var thiefGen = new ThiefCodegen(parent, funParamExprs);

    var myExpr = remainingFrameAdding.getFirst();
    int myGlobalIdx = allFrameAddingExprs.indexOf(myExpr);

    var innerFields = new StringBuilder(localsFieldDecls(fun));
    var newForwardedFields = new ArrayList<>(forwardedChildOblFields);
    for (var fwdField : forwardedChildOblFields) {
      innerFields.append(fwdField).append(": usize,\n");
    }
    var newFwdFieldName = "fwd_child_obl_" + forwardedChildOblFields.size();
    innerFields.append(newFwdFieldName).append(": usize,\n");
    newForwardedFields.add(newFwdFieldName);
    innerFields.append("r_thief: ").append(resultType(myExpr.expr())).append(",\n");

    parent.currentState().captureStructs.put(
      new DecId(innerLocalsName, 0),
      "const " + innerLocalsName + " = extern struct {\n" + innerFields + "};"
    );
    emitLocalsHooks(innerLocalsName, fun);

    var innerRemaining = remainingFrameAdding.subList(1, remainingFrameAdding.size());
    if (innerRemaining.size() >= 2 && canDeepenVPF(fun, newForwardedFields.size())) {
      emitVPFThiefFunction(
        innerThiefName,
        innerLocalsName,
        fun,
        vpf,
        allFrameAddingExprs,
        innerRemaining,
        funParamExprs,
        newForwardedFields
      );
    } else {
      emitSimpleThiefFunction(
        innerThiefName,
        innerLocalsName,
        fun,
        vpf,
        allFrameAddingExprs,
        funParamExprs,
        newForwardedFields
      );
    }

    int fwdCount = forwardedChildOblFields.size();

    var sb = new StringBuilder();
    sb.append("fn ").append(thiefName).append("(locals_ptr: *anyopaque, child_obl_opt: ?*JoinObligation) rt.FatPtr {\n");
    sb.append("const locals: *const ").append(localsName).append(" = @ptrCast(@alignCast(locals_ptr));\n");
    emitOwedObligations(sb, forwardedChildOblFields);

    sb.append("var thief_locals = ").append(innerLocalsName).append("{ ");
    var shape = parent.funShape(fun.name());
    for (int i = 0; i < fun.args().size(); i++) {
      if (i < shape.size() && shape.get(i).elided()) { continue; }
      var vn = parent.id.varName(fun.args().get(i).name());
      sb.append(".").append(vn).append(" = locals.").append(vn).append(", ");
    }
    for (var fwdField : forwardedChildOblFields) {
      sb.append(".").append(fwdField).append(" = locals.").append(fwdField).append(", ");
    }
    sb.append(".").append(newFwdFieldName).append(" = @intFromPtr(child_obl_opt), ");
    sb.append(".r_thief = undefined };\n");

    sb.append("asm volatile (\"\" ::: .{ .memory = true });\n");

    emitPushFrame(sb, vpf.hashName, "thief_locals", innerLocalsName, innerThiefName);
    // A promotion of this frame gives the waits to the next thief.
    sb.append("worker_mod.thiefOwes(&owed, frame_idx_opt);\n");

    var borrowedMine = parent.borrowedCaptureRead(myExpr.expr, thiefGen, true);
    if (borrowedMine.isPresent()) {
      sb.append("thief_locals.r_thief = ").append(borrowedMine.get().read()).append(";\n");
    } else {
      var myExprCode = myExpr.expr.accept(thiefGen, true);
      sb.append("thief_locals.r_thief = ").append(nativeResult(myExpr.expr, myExprCode, thiefGen)).append(";\n");
    }
    // The next thief consumes a count on the published result, so a borrowed result gets one here.
    var published = borrowedMine
      .map(borrowed -> parent.generateShare("thief_locals.r_thief", borrowed.type()))
      .orElseGet(() -> boxedResult(myExpr.expr, "thief_locals.r_thief"));
    var borrowedIndexes = new HashSet<Integer>();
    if (borrowedMine.isPresent()) { borrowedIndexes.add(myExpr.index()); }

    sb.append("if (frame_idx_opt) |frame_idx| {\n");
    sb.append("    if (shadow_stack_mod.popAndClaim(frame_idx)) |inner_obl| {\n");
    sb.append("        if (!shadow_stack_mod.reclaimPromotion(frame_idx, inner_obl)) {\n");
    sb.append("        worker_mod.thiefOwes(&.{}, null);\n");
    sb.append("        shadow_stack_mod.fulfillChildObligation(frame_idx, ").append(published).append(");\n");
    sb.append("        const wait_result = inner_obl.wait(worker_mod.getCurrentWorker().?);\n");
    sb.append("        shadow_stack_mod.freeObligation(inner_obl);\n");
    sb.append("        if (error_rt.tagOf(wait_result) != .none) errors.joinFailed(frame_idx, wait_result);\n");
    sb.append("        return wait_result;\n");
    sb.append("        }\n");
    sb.append("    }\n");
    sb.append("}\n");
    sb.append("worker_mod.thiefOwes(&owed, null);\n");

    emitThiefTail(
      sb,
      thiefGen,
      remainingFrameAdding.subList(1, remainingFrameAdding.size()),
      allFrameAddingExprs,
      vpf,
      fwdCount,
      myGlobalIdx,
      forwardedChildOblFields,
      borrowedIndexes
    );

    sb.append("}");
    parent.currentState().functions.add(sb.toString());
  }

  private record LocalsField(String name, RcFreeTypes.Strategy strategy) {}

  private void emitLocalsHooks(String localsName, MIR.Fun fun) {
    var args = fun.args();
    var shape = parent.funShape(fun.name());
    var fatPtrFields = IntStream.range(0, args.size())
      .filter(i -> i >= shape.size() || !shape.get(i).elided())
      .mapToObj(i -> new LocalsField(parent.id.varName(args.get(i).name()),
        parent.paramStrategy(fun.name(), i, args.get(i).t())))
      .filter(field -> field.strategy() != RcFreeTypes.Strategy.NONE)
      .toList();
    var retain = new StringBuilder();
    retain.append("fn ").append(localsName).append("_retain(copy_ptr: *anyopaque, parent_ptr: *anyopaque) void {\n");
    retain.append("const copy: *").append(localsName).append(" = @ptrCast(@alignCast(copy_ptr));\n");
    retain.append("const parent: *").append(localsName).append(" = @ptrCast(@alignCast(parent_ptr));\n");
    if (fatPtrFields.isEmpty()) {
      retain.append("_ = .{ copy, parent };\n");
    } else {
      for (var field : fatPtrFields) {
        // A transient field lives on the parent stack, so the copy gets a box. The copy owns that box.
        retain.append("copy.").append(field.name()).append(" = if (parent.").append(field.name())
          .append(".is_transient()) parent.").append(field.name()).append(".box_transient() else ")
          .append(parent.generateShare("parent." + field.name(), field.strategy())).append(";\n");
      }
    }
    retain.append("}");
    parent.currentState().functions.add(retain.toString());

    var drop = new StringBuilder();
    drop.append("fn ").append(localsName).append("_drop(locals_ptr: *anyopaque, releasing_worker_id: u32) void {\n");
    drop.append("const locals: *const ").append(localsName).append(" = @ptrCast(@alignCast(locals_ptr));\n");
    if (fatPtrFields.isEmpty()) {
      drop.append("_ = locals;\n");
      drop.append("_ = releasing_worker_id;\n");
    } else {
      for (var field : fatPtrFields) {
        drop.append(parent.generateDecrementAs("locals." + field.name(), field.strategy(), "releasing_worker_id")).append(";\n");
      }
    }
    drop.append("}");
    parent.currentState().functions.add(drop.toString());
  }

  /// Emits the last thief. It computes the remaining operands, waits on all obligations, then combines.
  private void emitSimpleThiefFunction(
      String thiefName,
      String localsName,
      MIR.Fun fun,
      VPFCallInfo vpf,
      List<SubExprInfo> frameAddingExprs,
      Map<String, String> funParamExprs,
      List<String> forwardedChildOblFields
  ) {
    var thiefGen = new ThiefCodegen(parent, funParamExprs);
    int fwdCount = forwardedChildOblFields.size();

    var body = new StringBuilder();
    emitOwedObligations(body, forwardedChildOblFields);
    body.append("worker_mod.thiefOwes(&owed, null);\n");
    emitThiefTail(
      body,
      thiefGen,
      frameAddingExprs.subList(fwdCount + 1, frameAddingExprs.size()),
      frameAddingExprs,
      vpf,
      fwdCount,
      -1,
      forwardedChildOblFields,
      new HashSet<>()
    );

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

  /// Emits the operands that this thief computes, the joins, the combiner call and the drops.
  /// `borrowedIndexes` holds the indexes of the operands with a borrowed result, which gets no drop.
  private void emitThiefTail(
      StringBuilder sb,
      ThiefCodegen thiefGen,
      List<SubExprInfo> exprsToCompute,
      List<SubExprInfo> allFrameAdding,
      VPFCallInfo vpf,
      int fwdCount,
      int myGlobalIdx,
      List<String> forwardedChildOblFields,
      Set<Integer> borrowedIndexes
  ) {
    for (var expr : exprsToCompute) {
      int globalIdx = allFrameAdding.indexOf(expr);
      var borrowed = parent.borrowedCaptureRead(expr.expr, thiefGen, true);
      if (borrowed.isPresent()) {
        sb.append("const r").append(globalIdx + 1).append(": rt.FatPtr = ").append(borrowed.get().read()).append(";\n");
        borrowedIndexes.add(expr.index());
        continue;
      }
      var code = expr.expr.accept(thiefGen, true);
      sb.append("const r").append(globalIdx + 1).append(" = ")
        .append(nativeResult(expr.expr, code, thiefGen)).append(";\n");
    }
    sb.append("const r1_boxed = shadow_stack_mod.joinOwed(&owed[0]);\n");
    sb.append("if (error_rt.tagOf(r1_boxed) != .none) errors.feart_unwind(r1_boxed);\n");
    var parentExpr = allFrameAdding.get(fwdCount).expr();
    sb.append("const r1 = ").append(fromObligation(parentExpr.t(), "r1_boxed")).append(";\n");
    emitWaitForwardedObligations(sb, forwardedChildOblFields, allFrameAdding);
    var resultMap = buildThiefCombinerMap(allFrameAdding, fwdCount, myGlobalIdx);
    var owned = allFrameAdding.stream()
      .filter(sub -> !parent.isRcFree(sub.expr()) && !borrowedIndexes.contains(sub.index()))
      .map(sub -> new ZigSingleCodegen.Drop(resultMap.get(sub.index()), sub.expr().t()))
      .collect(Collectors.toCollection(ArrayList::new));
    for (var sub : vpf.plainExprs) {
      if (sub.expr instanceof MIR.X || parent.isRcFree(sub.expr)) { continue; }
      var tmp = parent.freshName("fear_vpf_arg_");
      sb.append("const ").append(tmp).append(" = ").append(sub.expr.accept(thiefGen, true)).append(";\n");
      resultMap.put(sub.index, tmp);
      owned.add(new ZigSingleCodegen.Drop(tmp, sub.expr.t()));
    }
    var combined = parent.freshName("fear_vpf_res_");
    sb.append("const ").append(combined).append(" = ")
      .append(emitCombinerFromMap(vpf, resultMap, thiefGen)).append(";\n");
    for (var drop : owned) {
      sb.append(parent.generateDecrement(drop.name(), drop.t())).append(";\n");
    }
    sb.append("return ").append(boxedResult(vpf.vpfCall(), combined)).append(";\n");
  }

  private void emitPushFrame(StringBuilder sb, String hashName, String localsVar, String localsTypeName, String thiefFnName) {
    sb.append("const frame_idx_opt = shadow_stack_mod.pushFrame(.{\n");
    sb.append("    .target_method = ").append(hashName).append(",\n");
    sb.append("    .join_obligation = null,\n");
    sb.append("    .child_obligation = std.atomic.Value(?*JoinObligation).init(null),\n");
    sb.append("    .locals = @ptrCast(&").append(localsVar).append("),\n");
    sb.append("    .locals_size = @sizeOf(").append(localsTypeName).append("),\n");
    sb.append("    .retain_fn = &").append(localsTypeName).append("_retain,\n");
    sb.append("    .drop_fn = &").append(localsTypeName).append("_drop,\n");
    sb.append("    .thief_fn = &").append(thiefFnName).append(",\n");
    sb.append("});\n");
  }

  /// Emits `owed`, the obligations that the thief must wait on: its child obligation, then each forwarded
  /// obligation.
  private void emitOwedObligations(StringBuilder sb, List<String> forwardedChildOblFields) {
    sb.append("var owed = [_]?*JoinObligation{ child_obl_opt");
    for (var field : forwardedChildOblFields) {
      sb.append(", @ptrFromInt(locals.").append(field).append(")");
    }
    sb.append(" };\n");
  }

  private void emitWaitForwardedObligations(StringBuilder sb, List<String> forwardedChildOblFields, List<SubExprInfo> frameAddingExprs) {
    // An error tag here comes from the owner of an earlier operand, which raises that error itself and does not
    // wait for this thief. The wait order thus does not select the error that the program sees.
    for (int i = forwardedChildOblFields.size() - 1; i >= 0; i--) {
      sb.append("const fwd_r").append(i).append("_boxed = shadow_stack_mod.joinOwed(&owed[").append(i + 1).append("]);\n");
      sb.append("if (error_rt.tagOf(fwd_r").append(i).append("_boxed) != .none) errors.feart_unwind(fwd_r")
        .append(i).append("_boxed);\n");
      sb.append("const fwd_r").append(i).append(" = ")
        .append(fromObligation(frameAddingExprs.get(i).expr().t(), "fwd_r" + i + "_boxed")).append(";\n");
    }
  }

  private Map<Integer, String> buildThiefCombinerMap(List<SubExprInfo> frameAddingExprs, int fwdCount, int myGlobalIdx) {
    var resultMap = new HashMap<Integer, String>();

    for (int i = 0; i < fwdCount; i++) {
      resultMap.put(frameAddingExprs.get(i).index, "fwd_r" + i);
    }

    resultMap.put(frameAddingExprs.get(fwdCount).index, "r1");

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
    var nativeSums = new boolean[vpf.subExprs.size()];
    for (var sub : vpf.subExprs) {
      if (resultMap.containsKey(sub.index)) {
        allArgs[sub.index] = resultMap.get(sub.index);
        // A frame-adding result and a variable hold the native sum. Each other operand holds a box.
        nativeSums[sub.index] = (sub.isFrameAdding || sub.expr instanceof MIR.X)
          && parent.scalarSumOf(sub.expr.t()).isPresent();
      } else {
        allArgs[sub.index] = sub.expr.accept(codegen, true);
      }
    }

    String recvStr = allArgs[0];
    var argStrs = new ArrayList<String>();
    for (int i = 1; i < allArgs.length; i++) {
      argStrs.add(allArgs[i]);
    }
    if (vpf.directTarget.isPresent()) {
      var target = vpf.directTarget.get();
      var methName = parent.id.getMName(vpf.vpfCall.mdf(), vpf.vpfCall.name());
      var all = new ArrayList<String>();
      all.add(recvStr);
      all.addAll(argStrs);
      var prelude = new ArrayList<String>();
      var direct = parent.methWrapperRef(target, methName) + "("
        + String.join(
          ", ",
          parent.marshalWrapperArgs(target, vpf.vpfCall(), all, nativeSums, codegen, prelude)
        )
        + ")";
      return parent.withTransientPrelude(
        prelude,
        parent.guardedArm(target, vpf.vpfCall(), parent.scalarSumOf(vpf.vpfCall().t()), direct)
      );
    }
    if (vpf.guardTarget.isPresent()) {
      return emitGuardedCombiner(vpf, recvStr, argStrs, nativeSums, codegen);
    }
    var prelude = new ArrayList<String>();
    var boxedArgs = new ArrayList<String>();
    for (int i = 0; i < argStrs.size(); i++) {
      boxedArgs.add(boxCombinerOperand(
        vpf.vpfCall().args().get(i),
        argStrs.get(i),
        nativeSums[i + 1],
        prelude
      ));
    }
    var argsTuple = boxedArgs.isEmpty() ? ".{}" : ".{ " + String.join(", ", boxedArgs) + " }";
    var result = "rt.call("
      + boxCombinerOperand(vpf.vpfCall().recv(), recvStr, nativeSums[0], prelude) + ", "
      + vpf.hashName + ", " + argsTuple + ", @src())";
    var scalar = parent.scalarSumOf(vpf.vpfCall().t());
    return parent.withTransientPrelude(prelude,
      scalar.map(value -> parent.toScalarOwned(value, result)).orElse(result));
  }

  private String boxCombinerOperand(
      MIR.E expression,
      String code,
      boolean nativeSum,
      List<String> prelude
  ) {
    if (!nativeSum) { return parent.boxedBorrowed(expression, code, prelude); }
    return parent.borrowedBox(parent.scalarSumOf(expression.t()).orElseThrow(), code, prelude);
  }

  private String emitGuardedCombiner(
      VPFCallInfo vpf,
      String recvStr,
      List<String> argStrs,
      boolean[] nativeSums,
      MIRVisitor<String> codegen
  ) {
    var target = vpf.guardTarget.get();
    var block = parent.freshName("fear_blk_");
    var names = new ArrayList<String>();
    var sb = new StringBuilder(block + ": {\n");
    var recvName = parent.freshName("fear_guard_");
    names.add(recvName);
    sb.append("const ").append(recvName).append(" = ").append(recvStr).append(";\n");
    for (var arg : argStrs) {
      var argName = parent.freshName("fear_guard_");
      names.add(argName);
      sb.append("const ").append(argName).append(" = ").append(arg).append(";\n");
    }
    var argNames = names.subList(1, names.size());
    var boxPrelude = new ArrayList<String>();
    var boxedArgs = new ArrayList<String>();
    for (int i = 0; i < argNames.size(); i++) {
      boxedArgs.add(boxCombinerOperand(
        vpf.vpfCall().args().get(i),
        argNames.get(i),
        nativeSums[i + 1],
        boxPrelude
      ));
    }
    var argsTuple = boxedArgs.isEmpty() ? ".{}" : ".{ " + String.join(", ", boxedArgs) + " }";
    var boxedRecv = boxCombinerOperand(vpf.vpfCall().recv(), recvName, nativeSums[0], boxPrelude);
    var methName = parent.id.getMName(vpf.vpfCall.mdf(), vpf.vpfCall.name());
    var fallback = "rt.call(" + boxedRecv + ", " + vpf.hashName + ", " + argsTuple + ", @src())";
    var resultScalar = parent.scalarSumOf(vpf.vpfCall().t());
    if (resultScalar.isPresent()) {
      fallback = parent.toScalarOwned(resultScalar.orElseThrow(), fallback);
    }
    var arm = parent.methWrapperRef(target, methName) + "("
      + String.join(
        ", ",
        parent.marshalWrapperArgs(target, vpf.vpfCall(), names, nativeSums, codegen, boxPrelude)
      ) + ")";
    boxPrelude.forEach(line -> sb.append(line).append("\n"));
    sb.append("break :").append(block)
      .append(" if (").append(parent.guardTest(boxedRecv, target)).append(") ")
      .append(parent.guardedArm(target, vpf.vpfCall(), resultScalar, arm))
      .append(" else ").append(fallback).append(";\n}");
    return sb.toString();
  }

  private String resultType(MIR.E expr) {
    return expr == null ? "rt.FatPtr"
      : parent.scalarSumOf(expr.t()).map(ZigSingleCodegen.Scalar::zigType).orElse("rt.FatPtr");
  }

  private String nativeResult(MIR.E expr, String code, MIRVisitor<String> gen) {
    if (expr == null) { return code; }
    return parent.scalarSumOf(expr.t())
      .map(shape -> parent.scalarOwned(shape, expr, code, gen, true))
      .orElse(code);
  }

  private String boxedResult(MIR.E expr, String code) {
    if (expr == null) { return code; }
    var shape = parent.scalarSumOf(expr.t());
    return shape.map(value -> parent.toBoxedOwned(value, code)).orElse(code);
  }

  private String fromObligation(MIR.MT type, String code) {
    return parent.scalarSumOf(type).map(value -> parent.toScalarOwned(value, code)).orElse(code);
  }

  private boolean canDeepenVPF(MIR.Fun fun, int nextFwdCount) {
    return localsSize(fun, nextFwdCount) <= LOCALS_COPY_LIMIT;
  }

  int localsSize(MIR.Fun fun, int nextFwdCount) {
    var shape = parent.funShape(fun.name());
    int size = 0;
    for (int i = 0; i < fun.args().size(); i++) {
      var slot = i < shape.size() ? shape.get(i) : ZigSingleCodegen.ArgSlot.BOXED;
      if (slot.elided()) { continue; }
      size = align(size, slot.align()) + slot.bytes();
    }
    // 16 bytes for `r1` or `r_thief`, and 8 bytes for each forwarded obligation.
    size = align(size, 8) + nextFwdCount * 8 + 16;
    return size;
  }

  private static int align(int offset, int to) {
    return (offset + to - 1) / to * to;
  }

  private String localsFieldDecls(MIR.Fun fun) {
    var shape = parent.funShape(fun.name());
    var out = new StringBuilder();
    for (int i = 0; i < fun.args().size(); i++) {
      var slot = i < shape.size() ? shape.get(i) : ZigSingleCodegen.ArgSlot.BOXED;
      if (slot.elided()) { continue; }
      out.append(parent.id.varName(fun.args().get(i).name())).append(": ")
        .append(slot.scalar().map(sc -> sc.zigType()).orElse("rt.FatPtr")).append(",\n");
    }
    return out.toString();
  }

  private Map<String, String> thiefParamExprs(MIR.Fun fun) {
    var shape = parent.funShape(fun.name());
    var out = new LinkedHashMap<String, String>();
    for (int i = 0; i < fun.args().size(); i++) {
      var arg = fun.args().get(i);
      var slot = i < shape.size() ? shape.get(i) : ZigSingleCodegen.ArgSlot.BOXED;
      var field = "locals." + parent.id.varName(arg.name());
      out.put(arg.name(), slot.elided()
        ? parent.standInSelf()
        : slot.scalar().map(sc -> sc.isSum() ? field : sc.rtModule() + ".make(" + field + ")")
          .orElse(field));
    }
    return out;
  }

  static class ThiefCodegen implements MIRVisitor<String> {
    private final ZigSingleCodegen delegate;
    private final Map<String, String> paramExprs;

    ThiefCodegen(ZigSingleCodegen delegate, Map<String, String> paramExprs) {
      this.delegate = delegate;
      this.paramExprs = paramExprs;
    }

    @Override public String visitX(MIR.X x, boolean checkMagic) {
      var param = paramExprs.get(x.name());
      if (param != null) { return param; }
      return delegate.visitX(x, checkMagic);
    }
    @Override public String visitMCall(MIR.MCall call, boolean checkMagic) {
      return delegate.emitMCall(call, this, checkMagic);
    }
    @Override public String visitDirectCall(MIR.DirectCall call, boolean checkMagic) {
      return delegate.emitDirectCall(call, this, checkMagic);
    }
    @Override public String visitGuardedCall(MIR.GuardedCall call, boolean checkMagic) {
      return delegate.emitGuardedCall(call, this, checkMagic);
    }
    @Override public String visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
      return delegate.createObj(createObj, this, checkMagic);
    }
    @Override public String visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
      return delegate.boolExpr(expr, this, checkMagic, false);
    }
    @Override public String visitSumMatch(MIR.SumMatch e, boolean checkMagic) {
      return delegate.sumMatch(e, this, checkMagic);
    }
    @Override public String visitBox(MIR.Box box, boolean checkMagic) {
      return delegate.boxExpr(box.inner(), this, checkMagic);
    }

  }
}
