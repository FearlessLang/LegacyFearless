package codegen.zig;

import codegen.MIR;
import codegen.optimisations.ReturnShapeAnalysis;
import visitors.MIRVisitor;

import java.util.*;
import java.util.stream.Collectors;
import codegen.zig.ZigCodegenValues.Scalar;

/// Stack-allocated transient objects and caller-provided result slots.
interface ZigCodegenOwnership extends ZigCodegenContext {
  record Drop(String name, MIR.MT t) {}

  /// An eligible literal with captures; captureless literals are singletons.
  default boolean isTransientCreateObj(MIR.E e) {
    return e instanceof MIR.CreateObj obj && isTransientEligibleType(obj.concreteT().id()) && !obj.captures().isEmpty();
  }

  record Materialised(String ref, List<String> prelude) {}

  default Materialised materialiseTransient(MIR.CreateObj createObj, MIRVisitor<String> gen, boolean checkMagic) {
    emitCreateObj(createObj, checkMagic);
    var objId = createObj.concreteT().id();
    var tmp = "fear_transient_" + nextTransient();
    var prelude = new ArrayList<String>();
    var captures = createObj.captures().stream()
      .map(x -> "." + id().varName(x.name()) + " = "
        + boxedBorrowed(x, gen.visitX(x, checkMagic), prelude))
      .collect(Collectors.joining(", "));
    prelude.add("var " + tmp + "_obj: rt.GenObjectLayoutType(" + capturesRef(objId) + ") = undefined;");
    prelude.add("const " + tmp + " = rt.init_transient_obj(" + capturesRef(objId) + ", &" + tmp + "_obj, &" + vtableRef(objId, true) + ", .{ " + captures + " });");
    prelude.add("defer rt.drop_transient_obj(" + capturesRef(objId) + ", &" + tmp + "_obj);");
    return new Materialised(tmp, prelude);
  }

  default String withTransientPrelude(List<String> prelude, String expr) {
    if (prelude.isEmpty()) { return expr; }
    var label = "fear_blk_" + nextBlock();
    return label + ": {\n" + String.join("\n", prelude) + "\nbreak :" + label + " " + expr + ";\n}";
  }

  default boolean hasTransientVariant(MIR.FName fName) {
    var cached = transientVariantCache().get(fName);
    if (cached != null) { return cached; }
    // Pre-seeds false: variant computation recurses over callees and must not cycle.
    transientVariantCache().put(fName, false);
    var res = computeHasTransientVariant(fName);
    transientVariantCache().put(fName, res);
    return res;
  }

  /// True when a function builds its result in the caller's slot: directly, or by forwarding to such a callee.
  default boolean computeHasTransientVariant(MIR.FName fName) {
    var fun = funMap().get(fName);
    if (fun == null) { return false; }
    if (funResultShape(fName).filter(Scalar::isNiche).isPresent()) { return false; }
    if (shapes().freshObj(fName).isEmpty() || !shapes().slotWanted(fName)) { return false; }
    var body = ReturnShapeAnalysis.unwrap(fun.body());
    return switch (body) {
      case MIR.CreateObj k -> isTransientCreateObj(k);
      case MIR.DirectCall d -> shapes().calleeOf(d).map(f -> hasTransientVariant(f.name())).orElse(false)
        && operandsSlotFree(d.original().recv(), d.original().args());
      case MIR.StaticCall s -> funMap().containsKey(s.fun()) && hasTransientVariant(s.fun())
        && operandsSlotFree(null, s.args());
      default -> false;
    };
  }

  // Only setup-free operands forward: the forwarder emits no drops for them, and stack-backed ones would outlive the frame.
  // RC-free temps need no drop, yet this test rejects them while the tail-call test accepts them.
  default boolean operandsSlotFree(MIR.E recv, List<? extends MIR.E> args) {
    if (recv != null && needsPrelude(recv)) { return false; }
    return args.stream().noneMatch(this::needsPrelude);
  }

  default boolean needsPrelude(MIR.E e) {
    return isTransientCreateObj(e) || !isPureInline(e);
  }

  /// A call that writes its result into a caller-owned slot: setup, the call, and the drop.
  record SlotPieces(
    String slotVar,
    String caps,
    List<String> operandPrelude,
    String call,
    String drop,
    String extraDecl
  ) {}

  default Optional<SlotPieces> slotPieces(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    while (e instanceof MIR.Box(MIR.E inner)) { e = inner; }
    String target;
    MIR.CreateObj summaryObj;
    var operandPrelude = new ArrayList<String>();
    var operands = new ArrayList<String>();
    switch (e) {
      case MIR.DirectCall d -> {
        var callee = shapes().calleeOf(d).filter(f -> hasTransientVariant(f.name()));
        if (callee.isEmpty()) {return Optional.empty();}
        summaryObj = shapes().freshObj(callee.get().name()).orElseThrow();
        var original = d.original();
        target = methWrapperRef(d.concreteType(), id().getMName(original.mdf(), original.name())) + "_transient";
        var ops = slottedCallOperands(original, gen, checkMagic, callee);
        operandPrelude.addAll(ops.prelude());
        var recvAndArgs = new ArrayList<String>();
        recvAndArgs.add(ops.recv());
        recvAndArgs.addAll(ops.args());
        operands.addAll(marshalWrapperArgs(d.concreteType(), original, recvAndArgs, gen, operandPrelude));
      }
      case MIR.GuardedCall g -> {
        var callee = shapes().calleeOf(g).filter(f -> hasTransientVariant(f.name()));
        if (callee.isEmpty()) {return Optional.empty();}
        summaryObj = shapes().freshObj(callee.get().name()).orElseThrow();
        var original = g.original();
        var ops = slottedCallOperands(original, gen, checkMagic, Optional.empty());
        operandPrelude.addAll(ops.prelude());
        // The guard test, the transient wrapper and the dynamic call all take boxed operands.
        var recvBoxed = boxedBorrowed(original.recv(), ops.recv(), operandPrelude);
        var recvName = "fear_guard_" + nextBlock();
        operandPrelude.add("const " + recvName + " = " + recvBoxed + ";");
        var argNames = new ArrayList<String>();
        for (int i = 0; i < ops.args().size(); i++) {
          var argBoxed = boxedBorrowed(original.args().get(i), ops.args().get(i), operandPrelude);
          var argName = "fear_guard_" + nextBlock();
          operandPrelude.add("const " + argName + " = " + argBoxed + ";");
          argNames.add(argName);
        }
        var takenName = "fear_guard_" + nextBlock();
        operandPrelude.add(takenName + " = " + guardTest(recvName, g.concreteType()) + ";");
        var sig = new MIR.Sig(
          original.name(),
          original.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
          original.originalRet()
        );
        var slotVarName = "fear_slot_" + nextTransient();
        var hotOperands = new ArrayList<String>();
        hotOperands.add(recvName);
        hotOperands.addAll(argNames);
        var hotArgs = String.join(
          ", ",
          unboxArgs(g.concreteType(), original.name(), original.mdf(), hotOperands)
        );
        var hot = methWrapperRef(g.concreteType(), id().getMName(original.mdf(), original.name()))
          + "_transient(&" + slotVarName + "_obj"
          + (hotArgs.isEmpty() ? "" : ", " + hotArgs) + ")";
        var argsTuple = argNames.isEmpty() ? ".{}" : ".{ " + String.join(", ", argNames) + " }";
        var ownedName = slotVarName + "_owned";
        var coldBlock = "fear_blk_" + nextBlock();
        var cold = coldBlock + ": { const " + coldBlock + "_v = rt.call(" + recvName + ", "
          + sigBuilder().inlineHash(sig) + ", " + argsTuple + ", @src()); " + ownedName + " = "
          + coldBlock + "_v; break :" + coldBlock + " " + coldBlock + "_v; }";
        emitCreateObj(summaryObj, true);
        var guardedCaps = capturesRef(summaryObj.concreteT().id());
        // One branch runs hot into the slot, the other calls normally; the drop covers both.
        return Optional.of(new SlotPieces(
          slotVarName,
          guardedCaps,
          operandPrelude,
          "if (" + takenName + ") " + hot + " else " + cold,
          "if (" + takenName + ") rt.drop_transient_obj(" + guardedCaps + ", &" + slotVarName
            + "_obj) else " + generateDecrement(ownedName, e.t()),
          "var " + takenName + ": bool = false;\nvar " + ownedName + ": rt.FatPtr = undefined;\n"
        ));
      }
      case MIR.StaticCall s when funMap().containsKey(s.fun()) && hasTransientVariant(s.fun()) -> {
        summaryObj = shapes().freshObj(s.fun()).orElseThrow();
        target = funRef(s.fun()) + "_transient";
        operands.addAll(staticCallArgs(s, gen, checkMagic, operandPrelude, true));
      }
      case null, default -> {
        return Optional.empty();
      }
    }
    emitCreateObj(summaryObj, true);
    var caps = capturesRef(summaryObj.concreteT().id());
    var slotVar = "fear_slot_" + nextTransient();
    var call = target + "(&" + slotVar + "_obj"
      + (operands.isEmpty() ? "" : ", " + String.join(", ", operands)) + ")";
    return Optional.of(new SlotPieces(
      slotVar,
      caps,
      operandPrelude,
      call,
      "rt.drop_transient_obj(" + caps + ", &" + slotVar + "_obj)",
      ""
    ));
  }

  default Optional<Materialised> materialiseSlotCall(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    return slotPieces(e, gen, checkMagic).map(sp -> {
      var lines = new ArrayList<String>();
      lines.add("var " + sp.slotVar() + "_obj: rt.GenObjectLayoutType(" + sp.caps() + ") = undefined;");
      if (!sp.extraDecl().isEmpty()) { lines.add(sp.extraDecl().strip()); }
      lines.addAll(sp.operandPrelude());
      lines.add("const " + sp.slotVar() + " = " + sp.call() + ";");
      lines.add("defer " + sp.drop() + ";");
      return new Materialised(sp.slotVar(), lines);
    });
  }

  /// A slot call split for VPF: declarations and drops the parent emits around its join.
  record VPFResultSlot(String decl, String dropDefer, String operandStatements, String call) {}

  default Optional<VPFResultSlot> vpfResultSlot(MIR.E e) {
    return slotPieces(e, this, true).map(sp -> new VPFResultSlot(
      "var " + sp.slotVar() + "_obj: rt.GenObjectLayoutType(" + sp.caps() + ") = undefined;\n" + sp.extraDecl(),
      "defer " + sp.drop() + ";\n",
      sp.operandPrelude().isEmpty() ? "" : String.join("\n", sp.operandPrelude()) + "\n",
      sp.call()));
  }

  default String ownedExpr(MIR.E e, boolean checkMagic) {
    return ownedExpr(e, this, checkMagic);
  }

  /// An owned value: shares a variable, consumes any other expression as-is.
  default String ownedExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    if (e instanceof MIR.X) {
      var code = e.accept(gen, checkMagic);
      return generateShare(code, e.t());
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, gen, checkMagic, true);
    }
    return e.accept(gen, checkMagic);
  }

  /// A returned value: shares a variable, consumes any other expression as-is.
  default String returnExpr(MIR.E e, boolean checkMagic) {
    if (e instanceof MIR.X x) {
      var code = visitX(x, checkMagic);
      return generateShare(code, x.t());
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, this, checkMagic, true);
    }
    return e.accept(this, checkMagic);
  }

  /// Boxes an expression for a boxed use: shares variables, boxes calls, leaves method calls as `rt.call` results.
  default String boxExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    return switch (e) {
      case MIR.Box box -> boxExpr(box.inner(), gen, checkMagic);
      case MIR.X x -> emittedScalar(x).map(sc -> toBoxed(sc, x.accept(gen, checkMagic)))
        .orElseGet(() -> isRcFree(x) ? x.accept(gen, checkMagic) : generateBoxBorrowed(x.accept(gen, checkMagic), x.t()));
      case MIR.CreateObj createObj -> boxCreateObj(createObj, gen, checkMagic);
      case MIR.BoolExpr boolExpr -> boxBoolExpr(boolExpr, gen, checkMagic);
      case MIR.SumMatch ignored -> boxedOwned(e, e.accept(gen, checkMagic));
      case MIR.MCall ignored -> e.accept(gen, checkMagic);
      case MIR.DirectCall ignored -> boxedOwned(e, e.accept(gen, checkMagic));
      case MIR.GuardedCall ignored -> boxedOwned(e, e.accept(gen, checkMagic));
      case MIR.StaticCall ignored -> boxedOwned(e, e.accept(gen, checkMagic));
      case MIR.UpdatableListAsIdFnCall ignored -> generateBoxOwned(e.accept(gen, checkMagic));
      case MIR.Block ignored ->
        generateBoxOwned(boxedOwned(e, e.accept(gen, checkMagic)));
    };
  }
}
