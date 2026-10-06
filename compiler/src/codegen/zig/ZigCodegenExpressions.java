package codegen.zig;

import codegen.MIR;
import id.Id.DecId;
import visitors.MIRVisitor;

import java.util.*;
import java.util.function.Function;

import codegen.zig.ZigCodegenValues.Scalar;

/// Object literals, sum matches, and conditionals.
interface ZigCodegenExpressions extends ZigCodegenContext {
  /// Heap-allocates a literal, boxing each capture: niche lends, RC-free copies, else share and box transiently.
  default String boxCreateObj(MIR.CreateObj createObj, MIRVisitor<String> gen, boolean checkMagic) {
    var magicImpl = magicImpls().get(createObj);
    if (checkMagic && magicImpl.isPresent()) {
      var result = magicImpl.get().instantiate();
      if (result.isPresent()) { return generateBoxOwned(result.get()); }
    }

    var objId = createObj.concreteT().id();
    var typeDef = program().pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
    if (typeDef == null || typeDef.singletonInstance().isPresent() || createObj.captures().isEmpty()) {
      return createObj(createObj, gen, checkMagic);
    }

    emitCreateObj(createObj, checkMagic);
    var prelude = new ArrayList<String>();
    var boxedFields = new ArrayList<String>();
    for (var x : createObj.captures()) {
      var field = id().varName(x.name());
      var niche = emittedScalar(x).filter(Scalar::isNiche);
      if (niche.isPresent()) {
        boxedFields.add("." + field + " = "
          + borrowedBox(niche.orElseThrow(), x.accept(gen, checkMagic), prelude));
        continue;
      }
      if (isRcFree(x)) {
        boxedFields.add("." + field + " = " + boxed(x, x.accept(gen, checkMagic)));
        continue;
      }
      var tmp = field + "_boxed";
      prelude.add("const " + field + "_shared = "
        + generateShare(boxed(x, x.accept(gen, checkMagic)), x.t()) + ";");
      prelude.add("const " + tmp + " = " + field + "_shared.box_transient();");
      prelude.add("defer " + generateDecrement(tmp, x.t()) + ";");
      boxedFields.add("." + field + " = " + tmp);
    }
    return withTransientPrelude(prelude,
      "rt.obj_k(" + capturesRef(objId) + ", &" + vtableRef(objId) + ", .{ "
        + String.join(", ", boxedFields) + " })");
  }

  /// Calls an arm as a function instead of inlining it. Required when the arm holds a VPF join, which cannot inline.
  default Optional<String> deInlinedBranch(MIR.FName name, MIRVisitor<String> gen, boolean checkMagic) {
    if (!vpfEnabled() || !vpf().containsVPFCall(name)) { return Optional.empty(); }
    var fun = funMap().get(name);
    if (fun == null || fun.args().isEmpty()) { return Optional.empty(); }
    var args = new ArrayList<String>();
    var sources = new ArrayList<Optional<Scalar>>();
    args.add(standInSelf());
    sources.add(Optional.empty());
    fun.args().stream().skip(1).forEach(x -> {
      args.add(x.accept(gen, checkMagic));
      sources.add(emittedScalar(x));
    });
    var prelude = new ArrayList<String>();
    var shaped = reshapeArgs(name, sources, args, prelude);
    return Optional.of(withTransientPrelude(prelude,
      funRef(name) + "(" + String.join(", ", shaped) + ")"));
  }

  /// Tests each arm in order, falling back to a full `rt.call` when no scalar test matches.
  default String sumMatch(MIR.SumMatch expression, MIRVisitor<String> gen, boolean checkMagic) {
    var prelude = new ArrayList<String>();
    var recvName = receiverOperand(expression.receiver(), gen, checkMagic, prelude);
    var block = "fear_blk_" + nextBlock();
    var sb = new StringBuilder(block + ": {\n");
    sb.append("break :").append(block).append(" ");
    var recvScalar = emittedScalar(expression.receiver());
    for (var arm : expression.arms()) {
      String test;
      if (recvScalar.isPresent() && recvScalar.orElseThrow().isNiche()) {
        test = nicheArmTest(recvScalar.orElseThrow(), recvName, arm.impl());
      } else if (recvScalar.isPresent() && recvScalar.orElseThrow().isSum()) {
        var tag = recvScalar.orElseThrow().sum().tag(arm.impl());
        test = tag < 0 ? "false" : recvName + " == @as(u8, " + tag + ")";
      } else {
        test = guardTest(recvName, arm.impl());
      }
      sb.append("if (").append(test).append(") ")
        .append(sumArmCall(recvName, arm, gen, checkMagic, recvScalar))
        .append(" else ");
    }
    var resultScalar = scalarSumOf(expression.t());
    Function<String, String> fallbackOf = boxedReceiver -> {
      var call = sumMatchFallback(boxedReceiver, expression, gen, checkMagic);
      return resultScalar.map(sc -> toScalarOwned(sc, call)).orElse(call);
    };
    sb.append(recvScalar
      .map(sc -> withBorrowedBox(sc, recvName, fallbackOf))
      .orElseGet(() -> fallbackOf.apply(recvName)));
    sb.append(";\n}");
    return withTransientPrelude(prelude, sb.toString());
  }

  default String sumArmCall(
      String recvName,
      MIR.SumArm arm,
      MIRVisitor<String> gen,
      boolean checkMagic,
      Optional<Scalar> recvScalar
  ) {
    var fun = funMap().get(arm.arm());
    var arity = arm.arm().m().num();
    var niche = recvScalar.filter(Scalar::isNiche).isPresent();
    var args = new ArrayList<String>();
    var sources = new ArrayList<Optional<Scalar>>();
    for (var i = 0; i < arm.captures().size(); i++) {
      // A niche payload is the capture itself, so it passes directly with no deref.
      if (niche) {
        args.add(recvName);
        sources.add(Optional.empty());
        continue;
      }
      var receiver = recvScalar.map(sc -> toBoxed(sc, recvName)).orElse(recvName);
      var read = "rt.deref(" + capturesRef(arm.impl()) + ", " + receiver + ")."
        + id().varName(arm.captures().get(i));
      args.add(read);
      sources.add(Optional.empty());
    }
    args.add(standInSelf());
    sources.add(Optional.empty());
    fun.args().stream().skip(arity + 1).forEach(x -> {
      args.add(x.accept(gen, checkMagic));
      sources.add(emittedScalar(x));
    });
    var prelude = new ArrayList<String>();
    var shaped = reshapeArgs(arm.arm(), sources, args, prelude);
    return withTransientPrelude(prelude,
      callRef(arm.arm(), funRef(arm.arm()), shaped));
  }

  default String nicheArmTest(Scalar recv, String recvName, DecId impl) {
    if (impl.equals(recv.sum().capturelessVariant().id())) {
      return "rt.is_sum_0captures(" + recvName + ")";
    }
    if (impl.equals(recv.sum().capturingVariant().id())) {
      return "!rt.is_sum_0captures(" + recvName + ")";
    }
    return "false";
  }

  default String sumMatchFallback(
      String recvName,
      MIR.SumMatch expression,
      MIRVisitor<String> gen,
      boolean checkMagic
  ) {
    var original = expression.original();
    var sig = new MIR.Sig(
      original.name(),
      original.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      original.originalRet()
    );
    return "rt.call(" + recvName + ", " + sigBuilder().inlineHash(sig) + ", .{ "
      + expression.matcher().accept(gen, checkMagic) + " }, @src())";
  }

  default String boxBoolExpr(MIR.BoolExpr expression, MIRVisitor<String> gen, boolean checkMagic) {
    var recv = expression.condition().accept(gen, checkMagic);
    var thenBody = deInlinedBranch(expression.then(), gen, checkMagic)
      .map(call -> generateBoxOwned(boxedArmResult(expression.then(), call)))
      .orElseGet(() -> switch (funMap().get(expression.then()).body()) {
        case MIR.Block block -> boxExpr(block.original(), gen, checkMagic);
        case MIR.E value -> boxExpr(value, gen, checkMagic);
      });
    var elseBody = deInlinedBranch(expression.else_(), gen, checkMagic)
      .map(call -> generateBoxOwned(boxedArmResult(expression.else_(), call)))
      .orElseGet(() -> switch (funMap().get(expression.else_()).body()) {
        case MIR.Block block -> boxExpr(block.original(), gen, checkMagic);
        case MIR.E value -> boxExpr(value, gen, checkMagic);
      });
    return "(if (" + boolCondition(expression.condition(), recv) + ") "
      + thenBody + " else " + elseBody + ")";
  }

  /// An owned object from the call of a de-inlined arm: a native result converts to its object.
  default String boxedArmResult(MIR.FName armName, String call) {
    return funResultShape(armName).map(source -> toBoxedOwned(source, call)).orElse(call);
  }

  /// A conditional. With a scalar sum type, the result is an owned scalar in both modes.
  /// With any other type, `ownedBranches` shares an arm that is a variable, and otherwise leaves it borrowed.
  default String boolExpr(
      MIR.BoolExpr expression,
      MIRVisitor<String> gen,
      boolean checkMagic,
      boolean ownedBranches
  ) {
    var recv = expression.condition().accept(gen, checkMagic);
    var thenBody = deInlinedBranch(expression.then(), gen, checkMagic)
      .orElseGet(() -> switch (funMap().get(expression.then()).body()) {
        case MIR.Block block -> inlineBlock(block, gen, ownedBranches);
        case MIR.E value -> ownedBranches ? ownedExpr(value, gen, checkMagic) : value.accept(gen, checkMagic);
      });
    var elseBody = deInlinedBranch(expression.else_(), gen, checkMagic)
      .orElseGet(() -> switch (funMap().get(expression.else_()).body()) {
        case MIR.Block block -> inlineBlock(block, gen, ownedBranches);
        case MIR.E value -> ownedBranches ? ownedExpr(value, gen, checkMagic) : value.accept(gen, checkMagic);
      });
    var target = scalarSumOf(expression.t());
    if (target.isEmpty()) {
      return "(if (" + boolCondition(expression.condition(), recv) + ") "
        + thenBody + " else " + elseBody + ")";
    }
    var scalar = target.orElseThrow();
    return "(if (" + boolCondition(expression.condition(), recv) + ") "
      + boolArm(scalar, expression.then(), gen, checkMagic, thenBody) + " else "
      + boolArm(scalar, expression.else_(), gen, checkMagic, elseBody) + ")";
  }

  /// Converts the code of a conditional arm to the scalar sum of the conditional. The result is owned.
  /// An arm that is a `Box` yields an owned box, which the conversion releases.
  default String boolArm(
      Scalar target,
      MIR.FName armName,
      MIRVisitor<String> gen,
      boolean checkMagic,
      String code
  ) {
    if (deInlinedBranch(armName, gen, checkMagic).isPresent()) {
      return funResultShape(armName)
        .map(source -> reshapeScalarOwned(source, target, code))
        .orElse(code);
    }
    var body = funMap().get(armName).body();
    var expression = body instanceof MIR.Block block ? block.original() : body;
    return scalarOwned(target, expression, code, gen, checkMagic);
  }

  default String inlineBlock(MIR.Block block) {
    return inlineBlock(block, this, false);
  }

  default String inlineBlock(MIR.Block block, MIRVisitor<String> gen, boolean ownedBranches) {
    return ownedBranches ? ownedExpr(block.original(), gen, true) : block.original().accept(gen, true);
  }

  default String visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
    return createObj(createObj, this, checkMagic);
  }

  /// Allocates a literal with borrowed captures; the caller keeps ownership. `gen` resolves each capture.
  default String createObj(MIR.CreateObj createObj, MIRVisitor<String> gen, boolean checkMagic) {
    var magicImpl = magicImpls().get(createObj);
    if (checkMagic && magicImpl.isPresent()) {
      var result = magicImpl.get().instantiate();
      if (result.isPresent()) { return result.get(); }
    }

    var objId = createObj.concreteT().id();
    var typeDef = program().pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
    if (typeDef == null) {
      emitCreateObj(createObj, checkMagic);
      return "rt.obj_k_singleton(&" + vtableRef(objId) + ")";
    }

    emitCreateObj(createObj, checkMagic);
    if (typeDef.singletonInstance().isPresent() || createObj.captures().isEmpty()) {
      return "rt.obj_k_singleton(&" + vtableRef(objId) + ")";
    }

    var prelude = new ArrayList<String>();
    var captures = createObj.captures().stream()
      .map(x -> "." + id().varName(x.name()) + " = "
        + boxedBorrowed(x, gen.visitX(x, checkMagic), prelude))
      .collect(java.util.stream.Collectors.joining(", "));
    return withTransientPrelude(prelude,
      "rt.obj_k(" + capturesRef(objId) + ", &" + vtableRef(objId) + ", .{ " + captures + " })");
  }

  default String visitBoolExpr(MIR.BoolExpr expression, boolean checkMagic) {
    return boolExpr(expression, this, checkMagic, false);
  }

  default String visitSumMatch(MIR.SumMatch expression, boolean checkMagic) {
    return sumMatch(expression, this, checkMagic);
  }

  default String visitBox(MIR.Box box, boolean checkMagic) {
    return boxExpr(box.inner(), this, checkMagic);
  }
}
