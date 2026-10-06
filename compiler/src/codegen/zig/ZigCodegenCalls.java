package codegen.zig;

import codegen.MIR;
import visitors.MIRVisitor;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import utils.Bug;

import codegen.zig.ZigCodegenOwnership.Drop;
import codegen.zig.ZigCodegenValues.Scalar;

/// Call emission: operand preparation, tail calls, and turning a final share into a move.
interface ZigCodegenCalls extends ZigCodegenContext {
  /// Closes a function body: conditionals emit both arms, calls try the tail form, the rest binds a temp, drops, and returns.
  default String tailStatements(MIR.E expression, List<Drop> drops, boolean checkMagic) {
    return switch (expression) {
      case MIR.Box box -> tailStatements(box.inner(), drops, checkMagic);
      case MIR.BoolExpr boolExpr -> {
        var condition = boolExpr.condition().accept(this, checkMagic);
        yield "if (" + boolCondition(boolExpr.condition(), condition) + ") {\n"
          + armTailStatements(boolExpr.then(), drops, checkMagic)
          + "}\n"
          + armTailStatements(boolExpr.else_(), drops, checkMagic);
      }
      case MIR.DirectCall call when operandWantsSlot(
            call.original(),
            shapes().calleeOf(call),
            operandTargets(call.concreteType(), call.original())
          ) || dropsBorrowedRecv(call.original(), drops) ->
        valueTailStatements(currentReturn(expression, checkMagic), drops);
      case MIR.DirectCall call -> {
        var operands = callOperands(
          call.original(),
          this,
          checkMagic,
          operandTargets(call.concreteType(), call.original())
        );
        var callee = shapes().calleeOf(call).map(MIR.Fun::name).orElse(null);
        yield tailCallStatements(operands,
            refs -> currentReturn(call,
              methCallRef(call.concreteType(), call.original(), callee, refs, this), checkMagic),
            drops)
          .orElseGet(() -> valueTailStatements(currentReturn(expression, checkMagic), drops));
      }
      case MIR.MCall call when operandWantsSlot(call, Optional.empty())
          || dropsBorrowedRecv(call, drops) ->
        valueTailStatements(currentReturn(expression, checkMagic), drops);
      case MIR.MCall call -> {
        var operands = callOperands(call, this, checkMagic);
        yield tailCallStatements(operands, refs -> mCallOn(call, refs, checkMagic), drops)
          .orElseGet(() -> valueTailStatements(currentReturn(expression, checkMagic), drops));
      }
      default -> valueTailStatements(currentReturn(expression, checkMagic), drops);
    };
  }

  /// A method call over pre-bound operand temps, for tail positions.
  default String mCallOn(MIR.MCall call, List<String> refs, boolean checkMagic) {
    var sig = new MIR.Sig(
      call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet()
    );
    var hashExpr = sigBuilder().inlineHash(sig);
    var prelude = new ArrayList<String>();
    var recv = boxedBorrowed(call.recv(), refs.getFirst(), prelude);
    var rest = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      rest.add(boxedBorrowed(call.args().get(i), refs.get(i + 1), prelude));
    }
    var argsTuple = rest.isEmpty() ? ".{}" : ".{ " + String.join(", ", rest) + " }";
    var intrinsic = checkMagic ? primitiveIntrinsic(call.recv()) : Optional.<String>empty();
    var result = intrinsic
      .map(module -> "rt.dispatch_primitive(" + module + ", " + hashExpr + ", " + recv + ", " + argsTuple + ")")
      .orElseGet(() -> "rt.call(" + recv + ", " + hashExpr + ", " + argsTuple + ", @src())");
    return currentReturn(call, withTransientPrelude(prelude, result), checkMagic);
  }

  /// Closes an arm: a called arm reshapes to the caller's result, a body recurses.
  default String armTailStatements(MIR.FName arm, List<Drop> drops, boolean checkMagic) {
    var deInlined = deInlinedBranch(arm, this, checkMagic);
    if (deInlined.isPresent()) {
      var code = deInlined.orElseThrow();
      var converted = funResultShape(currentFun())
        .flatMap(target -> funResultShape(arm)
          .map(source -> reshapeScalarOwned(source, target, code)))
        .orElse(code);
      return valueTailStatements(converted, drops);
    }
    var body = funMap().get(arm).body();
    return tailStatements(body instanceof MIR.Block block ? block.original() : body, drops, checkMagic);
  }

  default Optional<String> tailCallStatements(
      CallOperands operands,
      Function<List<String>, String> build,
      List<Drop> drops
  ) {
    // A deferred drop runs after the call returns, so the frame survives; transient storage lives in it too.
    if (operands.prelude().stream().anyMatch(line -> line.startsWith("defer "))) {
      return Optional.empty();
    }
    var values = Stream.concat(Stream.of(operands.recv()), operands.args().stream()).toList();
    var moved = moveLastShares(values, drops);
    var sb = new StringBuilder();
    operands.prelude().forEach(line -> sb.append(line).append('\n'));
    var refs = new ArrayList<String>();
    for (var operand : moved.operands()) {
      var tmp = "fear_op_" + nextBlock();
      sb.append("const ").append(tmp).append(" = ").append(operand).append(";\n");
      refs.add(tmp);
    }
    var call = build.apply(refs);
    appendUnusedOperandDiscards(sb, refs, call);
    appendDrops(sb, drops.stream().filter(drop -> !moved.cancelled().contains(drop)).toList());
    sb.append("return ").append(call).append(";\n");
    return Optional.of(sb.toString());
  }

  // Shaping can drop an operand; Zig still needs every temp acknowledged.
  default void appendUnusedOperandDiscards(StringBuilder sb, List<String> refs, String call) {
    var unused = refs.stream().filter(ref -> !call.contains(ref)).toList();
    if (unused.isEmpty()) { return; }
    sb.append("_ = .{ ").append(String.join(", ", unused)).append(" };\n");
  }

  record MovedShares(List<String> operands, Set<Drop> cancelled) {}

  /// Turns a final share-then-drop into a move: when every use of a dropped name is a share, the last one takes the name itself.
  default MovedShares moveLastShares(List<String> operands, List<Drop> drops) {
    var working = new ArrayList<>(operands);
    var cancelled = new LinkedHashSet<Drop>();
    for (var drop : drops) {
      var name = drop.name();
      var share = generateShare(name, drop.t());
      var uses = 0;
      var shares = 0;
      var lastIndex = -1;
      var literalClash = false;
      for (var i = 0; i < working.size(); i++) {
        var raw = working.get(i);
        var bare = withoutStringLiterals(raw);
        var here = countOccurrences(bare, name);
        // A name inside a string literal is not a use.
        if (countOccurrences(raw, name) != here) { literalClash = true; }
        if (here == 0) { continue; }
        uses += here;
        shares += countOccurrences(bare, share);
        lastIndex = i;
      }
      if (literalClash || lastIndex < 0 || uses != shares) { continue; }
      var target = working.get(lastIndex);
      var at = target.lastIndexOf(share);
      working.set(lastIndex, target.substring(0, at) + name + target.substring(at + share.length()));
      cancelled.add(drop);
    }
    return new MovedShares(List.copyOf(working), cancelled);
  }

  Pattern STRING_LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"");

  default String withoutStringLiterals(String code) {
    return STRING_LITERAL.matcher(code).replaceAll("\"\"");
  }

  default int countOccurrences(String code, String text) {
    var count = 0;
    for (var at = code.indexOf(text); at >= 0; at = code.indexOf(text, at + text.length())) {
      var before = at == 0 || !isNameChar(code.charAt(at - 1));
      var afterAt = at + text.length();
      var after = afterAt == code.length() || !isNameChar(code.charAt(afterAt));
      if (before && after) { count++; }
    }
    return count;
  }

  default boolean isNameChar(char character) {
    return Character.isLetterOrDigit(character) || character == '_';
  }

  default String valueTailStatements(String expression, List<Drop> drops) {
    if (expression.equals("unreachable")) { return "unreachable;\n"; }
    var sb = new StringBuilder();
    var tmp = "fear_ret_" + nextBlock();
    sb.append("const ").append(tmp).append(" = ").append(expression).append(";\n");
    appendDrops(sb, drops);
    sb.append("return ").append(tmp).append(";\n");
    return sb.toString();
  }

  default void appendDrops(StringBuilder sb, List<Drop> drops) {
    for (var drop : drops) {
      sb.append(generateDecrement(drop.name(), drop.t())).append(";\n");
    }
  }

  /// Emits the temps of operands that need no drop. A line that is not a plain `const` would be a drop or a frame-bound slot.
  default void appendForwardPrelude(StringBuilder sb, List<String> prelude) {
    for (var line : prelude) {
      if (!line.startsWith("const ")) { throw Bug.unreachable(); }
      sb.append(line).append("\n");
    }
  }

  /// Tail-forwards into a slot, dropping enclosing temps first unless the result borrows them.
  default void appendTailForward(
      StringBuilder sb,
      CallOperands operands,
      String target,
      List<Drop> drops,
      boolean hoistDrops
  ) {
    assert operands.prelude().isEmpty();
    if (!operands.prelude().isEmpty()) { throw Bug.unreachable(); }
    var refs = new ArrayList<String>();
    var values = new ArrayList<String>();
    if (operands.recv() != null) { values.add(operands.recv()); }
    values.addAll(operands.args());
    for (var value : values) {
      var tmp = "fear_op_" + nextBlock();
      sb.append("const ").append(tmp).append(" = ").append(value).append(";\n");
      refs.add(tmp);
    }
    var call = new StringBuilder(target).append("(fear_out");
    for (var ref : refs) { call.append(", ").append(ref); }
    call.append(")");
    appendUnusedOperandDiscards(sb, refs, call.toString());
    if (hoistDrops) {
      appendDrops(sb, drops);
      sb.append("return ").append(call).append(";\n");
      return;
    }
    var result = "fear_fwd_" + nextBlock();
    sb.append("const ").append(result).append(" = ").append(call).append(";\n");
    appendDrops(sb, drops);
    sb.append("return ").append(result).append(";\n");
  }

  default String visitX(MIR.X x, boolean checkMagic) {
    return id().varName(x.name());
  }

  default String visitMCall(MIR.MCall call, boolean checkMagic) {
    var magicImpl = magicImpls().get(call.recv());
    if (checkMagic && magicImpl.isPresent()) {
      var impl = magicImpl.get().call(call.name(), call.args(), call.variant(), call.t());
      if (impl.isPresent()) { return impl.get(); }
    }
    return emitMCall(call, this, checkMagic);
  }

  /// The runtime module that implements the methods of a primitive receiver, if the receiver is a primitive.
  default Optional<String> primitiveIntrinsic(MIR.E receiver) {
    return magicImpls().primitiveModule(receiver);
  }

  record CallOperands(String recv, List<String> args, List<String> prelude) {}

  default int selfArgIndex(MIR.Fun fun) {
    return fun.name().m().num();
  }

  /// Prepares a call operand: stack and slot values in place, inline values as-is, else an owned temp dropped after the call.
  default String borrowedOperand(
      MIR.E expression,
      MIRVisitor<String> gen,
      boolean checkMagic,
      List<String> prelude,
      boolean slotEligible,
      String tmpPrefix
  ) {
    if (isTransientCreateObj(expression)) {
      var materialised = materialiseTransient((MIR.CreateObj) expression, gen, checkMagic);
      prelude.addAll(materialised.prelude());
      return materialised.ref();
    }
    if (isPureInline(expression)) { return expression.accept(gen, checkMagic); }
    if (slotEligible) {
      var slotted = materialiseSlotCall(expression, gen, checkMagic);
      if (slotted.isPresent()) {
        prelude.addAll(slotted.get().prelude());
        return slotted.get().ref();
      }
    }
    var tmp = tmpPrefix + nextBlock();
    prelude.add("const " + tmp + " = " + ownedExpr(expression, gen, checkMagic) + ";");
    if (!isRcFree(expression)) { prelude.add("defer " + generateDecrement(tmp, expression.t()) + ";"); }
    return tmp;
  }

  /// An operand that stays in place and needs no temp or drop: a variable, a box, an RC-free literal, or a primitive operation on such operands.
  /// An operand in place runs after every operand that moves to a prelude, so it must not have an effect that a program can observe, and it must always give a result.
  /// A primitive operation qualifies only when its method is in the in-place list of its receiver type (see `ZigMagicImpls.isInPlacePrimitiveCall`).
  /// Every other call gets a temp, in operand order.
  default boolean isPureInline(MIR.E expression) {
    return switch (expression) {
      case MIR.X ignored -> true;
      case MIR.Box box -> isPureInline(box.inner());
      case MIR.CreateObj ignored -> isRcFree(expression);
      case MIR.MCall call -> isRcFree(expression)
        && magicImpls().isInPlacePrimitiveCall(call)
        && isPureInline(call.recv())
        && call.args().stream().allMatch(this::isPureInline);
      default -> false;
    };
  }

  default String receiverOperand(
      MIR.E expression,
      MIRVisitor<String> gen,
      boolean checkMagic,
      List<String> prelude
  ) {
    return borrowedOperand(expression, gen, checkMagic, prelude, true, "fear_recv_");
  }

  default boolean lendsDroppedName(MIR.E expression, List<Drop> drops) {
    return expression instanceof MIR.X x
      && drops.stream().anyMatch(drop -> drop.name().equals(id().varName(x.name())));
  }

  default boolean dropsBorrowedRecv(MIR.MCall call, List<Drop> drops) {
    return lendsDroppedName(call.recv(), drops);
  }

  default boolean staticCallLendsDroppedName(MIR.StaticCall call, List<Drop> drops) {
    var selfIndex = call.fun().m().num();
    return java.util.stream.IntStream.range(0, call.args().size())
      .anyMatch(i -> i >= selfIndex && lendsDroppedName(call.args().get(i), drops));
  }

  default boolean receiverNeedsOwner(MIR.E expression) {
    return !(expression instanceof MIR.X) && !isRcFree(expression) && !isTransientCreateObj(expression);
  }

  default String operand(
      MIR.E expression,
      MIRVisitor<String> gen,
      boolean checkMagic,
      List<String> prelude,
      boolean slotEligible
  ) {
    return borrowedOperand(expression, gen, checkMagic, prelude, slotEligible, "fear_arg_");
  }

  /// A call worth routing through slots: its receiver needs a temp owner, or an argument can fill a slot.
  default boolean operandWantsSlot(MIR.MCall call, Optional<MIR.Fun> knownCallee) {
    return operandWantsSlot(call, knownCallee, List.of());
  }

  /// As above, where `targets` is the list from [#operandTargets]. An argument that its parameter folds needs no slot.
  default boolean operandWantsSlot(
      MIR.MCall call,
      Optional<MIR.Fun> knownCallee,
      List<Optional<Scalar>> targets
  ) {
    if (receiverNeedsOwner(call.recv())) { return true; }
    if (canFillSlot(call.recv())) { return true; }
    for (int i = 0; i < call.args().size(); i++) {
      var slotOk = knownCallee.isPresent()
        && knownCallee.get().args().size() > call.args().size()
        && !shapes().paramMayEscape(knownCallee.get().name(), i);
      var arg = call.args().get(i);
      if (slotOk && canFillSlot(arg) && !foldsAtOperand(argTarget(targets, i), arg)) { return true; }
    }
    return false;
  }

  /// The target of the argument at `index` in a list from [#operandTargets], which has the receiver first. Empty when the list has no entry.
  default Optional<Scalar> argTarget(List<Optional<Scalar>> targets, int index) {
    return index + 1 < targets.size() ? targets.get(index + 1) : Optional.<Scalar>empty();
  }

  /// An expression that can fill a caller slot: a transient literal or a call with a transient variant.
  default boolean canFillSlot(MIR.E expression) {
    while (expression instanceof MIR.Box box) { expression = box.inner(); }
    if (isTransientCreateObj(expression)) { return true; }
    return switch (expression) {
      case MIR.DirectCall call -> shapes().calleeOf(call).map(f -> hasTransientVariant(f.name())).orElse(false);
      case MIR.GuardedCall call -> shapes().calleeOf(call).map(f -> hasTransientVariant(f.name())).orElse(false);
      case MIR.StaticCall call -> funMap().containsKey(call.fun()) && hasTransientVariant(call.fun());
      default -> false;
    };
  }

  default CallOperands callOperands(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic) {
    return callOperands(call, gen, checkMagic, List.of());
  }

  /// As above, where `targets` is the list from [#operandTargets]. An argument that its parameter folds is not prepared.
  default CallOperands callOperands(
      MIR.MCall call,
      MIRVisitor<String> gen,
      boolean checkMagic,
      List<Optional<Scalar>> targets
  ) {
    var prelude = new ArrayList<String>();
    var recv = receiverOperand(call.recv(), gen, checkMagic, prelude);
    var args = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      args.add(argOperand(call.args().get(i), argTarget(targets, i), gen, checkMagic, prelude, false));
    }
    return new CallOperands(recv, args, prelude);
  }

  default CallOperands slottedCallOperands(
      MIR.MCall call,
      MIRVisitor<String> gen,
      boolean checkMagic,
      Optional<MIR.Fun> knownCallee
  ) {
    return slottedCallOperands(call, gen, checkMagic, knownCallee, List.of());
  }

  /// As above, where `targets` is the list from [#operandTargets]. An argument that its parameter folds is not prepared.
  default CallOperands slottedCallOperands(
      MIR.MCall call,
      MIRVisitor<String> gen,
      boolean checkMagic,
      Optional<MIR.Fun> knownCallee,
      List<Optional<Scalar>> targets
  ) {
    var prelude = new ArrayList<String>();
    var recv = receiverOperand(call.recv(), gen, checkMagic, prelude);
    var args = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      var slotOk = knownCallee.isPresent()
        && knownCallee.get().args().size() > call.args().size()
        && !shapes().paramMayEscape(knownCallee.get().name(), i);
      args.add(argOperand(call.args().get(i), argTarget(targets, i), gen, checkMagic, prelude, slotOk));
    }
    return new CallOperands(recv, args, prelude);
  }

  /// Prepares one argument. A literal that its parameter folds to its capture is that capture, held in place: it gets no object, temp or prelude line.
  /// `target` is the scalar that the call converts the argument to; [#foldsAtOperand] and [#scalar] decide the fold with one test.
  default String argOperand(
      MIR.E arg,
      Optional<Scalar> target,
      MIRVisitor<String> gen,
      boolean checkMagic,
      List<String> prelude,
      boolean slotEligible
  ) {
    if (!foldsAtOperand(target, arg)) { return operand(arg, gen, checkMagic, prelude, slotEligible); }
    return nicheBorrowedFold(target.orElseThrow(), arg, gen, true).orElseThrow();
  }

  /// Static calls have no receiver, so arguments from the self index on borrow like one; earlier ones fill slots only when they cannot escape.
  default List<String> staticCallArgs(
      MIR.StaticCall call,
      MIRVisitor<String> gen,
      boolean checkMagic,
      List<String> prelude,
      boolean allowSlots
  ) {
    var callee = funMap().get(call.fun());
    var selfIndex = call.fun().m().num();
    var shape = funShape(call.fun());
    var receiverArgs = new ArrayList<String>();
    for (int i = selfIndex; i < call.args().size(); i++) {
      if (i < shape.size() && shape.get(i).elided()) { continue; }
      receiverArgs.add(receiverOperand(call.args().get(i), gen, checkMagic, prelude));
    }
    var args = new ArrayList<String>();
    var receiverIndex = 0;
    for (int i = 0; i < call.args().size(); i++) {
      if (i < shape.size() && shape.get(i).elided()) { continue; }
      if (i >= selfIndex) {
        var receiver = receiverArgs.get(receiverIndex++);
        if (i < shape.size() && shape.get(i).scalar().isPresent()) {
          args.add(scalar(shape.get(i).scalar().orElseThrow(), call.args().get(i), receiver, gen));
        } else {
          args.add(boxedBorrowed(call.args().get(i), receiver, prelude));
        }
        continue;
      }
      var slotOk = allowSlots && callee != null && callee.args().size() == call.args().size()
        && !shapes().paramMayEscape(call.fun(), i);
      var arg = operand(call.args().get(i), gen, checkMagic, prelude, slotOk);
      if (i < shape.size() && shape.get(i).scalar().isPresent()) {
        args.add(scalar(shape.get(i).scalar().orElseThrow(), call.args().get(i), arg, gen));
      } else {
        args.add(boxedBorrowed(call.args().get(i), arg, prelude));
      }
    }
    return args;
  }

  default String emitMCall(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var operands = slottedCallOperands(call, gen, checkMagic, Optional.empty());
    var sig = new MIR.Sig(
      call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet()
    );
    var hashExpr = sigBuilder().inlineHash(sig);
    var boxedArgs = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      boxedArgs.add(boxedBorrowed(call.args().get(i), operands.args().get(i), operands.prelude()));
    }
    var argsTuple = boxedArgs.isEmpty() ? ".{}" : ".{ " + String.join(", ", boxedArgs) + " }";
    var boxedRecv = boxedBorrowed(call.recv(), operands.recv(), operands.prelude());
    var intrinsic = checkMagic ? primitiveIntrinsic(call.recv()) : Optional.<String>empty();
    var target = intrinsic
      .map(module -> "rt.dispatch_primitive(" + module + ", " + hashExpr + ", " + boxedRecv + ", " + argsTuple + ")")
      .orElseGet(() -> "rt.call(" + boxedRecv + ", " + hashExpr + ", " + argsTuple + ", @src())");
    return withTransientPrelude(operands.prelude(), target);
  }

  default String emitDirectCall(MIR.DirectCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var original = call.original();
    var operands = slottedCallOperands(
      original,
      gen,
      checkMagic,
      shapes().calleeOf(call),
      operandTargets(call.concreteType(), original)
    );
    var all = new ArrayList<String>();
    all.add(operands.recv());
    all.addAll(operands.args());
    var callee = shapes().calleeOf(call).map(MIR.Fun::name).orElse(null);
    return withTransientPrelude(
      operands.prelude(),
      methCallRef(call.concreteType(), original, callee, all, gen)
    );
  }

  default String visitGuardedCall(MIR.GuardedCall call, boolean checkMagic) {
    return emitGuardedCall(call, this, checkMagic);
  }

  default String freshName(String prefix) {
    return prefix + nextBlock();
  }

  /// A guarded call: exact-type fast path, alternate-type path, then a full `rt.call` fallback.
  default String emitGuardedCall(MIR.GuardedCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var original = call.original();
    var operands = slottedCallOperands(original, gen, checkMagic, Optional.empty());
    var sig = new MIR.Sig(
      original.name(),
      original.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      original.originalRet()
    );
    var hashExpr = sigBuilder().inlineHash(sig);

    var recvName = "fear_guard_" + nextBlock();
    var block = "fear_blk_" + nextBlock();
    var names = new ArrayList<String>();
    names.add(recvName);
    var sb = new StringBuilder(block + ": {\nconst " + recvName + " = " + operands.recv() + ";\n");
    for (var arg : operands.args()) {
      var argName = "fear_guard_" + nextBlock();
      names.add(argName);
      sb.append("const ").append(argName).append(" = ").append(arg).append(";\n");
    }
    var argNames = names.subList(1, names.size());
    var boxPrelude = new ArrayList<String>();
    var boxedArgs = new ArrayList<String>();
    for (int i = 0; i < argNames.size(); i++) {
      boxedArgs.add(boxedBorrowed(original.args().get(i), argNames.get(i), boxPrelude));
    }
    var argsTuple = boxedArgs.isEmpty() ? ".{}" : ".{ " + String.join(", ", boxedArgs) + " }";
    var boxedRecv = boxedBorrowed(original.recv(), recvName, boxPrelude);
    boxPrelude.forEach(line -> sb.append(line).append("\n"));
    var siteScalar = scalarSumOf(call.t());
    sb.append("break :").append(block)
      .append(" if (").append(guardTest(boxedRecv, call.concreteType())).append(") ")
      .append(guardedArm(
        call.concreteType(),
        original,
        siteScalar,
        methCallRef(
          call.concreteType(),
          original,
          shapes().calleeOf(call).map(MIR.Fun::name).orElse(null),
          names,
          gen
        )
      ));
    call.altType().ifPresent(alt -> sb.append(" else if (").append(guardTest(boxedRecv, alt))
      .append(") ").append(guardedArm(
        alt,
        original,
        siteScalar,
        methCallRef(
          alt,
          original,
          shapes().calleeOfAlt(call).map(MIR.Fun::name).orElse(null),
          names,
          gen
        )
      )));
    var fallback = "rt.call(" + boxedRecv + ", " + hashExpr + ", " + argsTuple + ", @src())";
    if (siteScalar.isPresent()) { fallback = toScalarOwned(siteScalar.orElseThrow(), fallback); }
    sb.append(" else ").append(fallback).append(";\n}");
    return withTransientPrelude(operands.prelude(), sb.toString());
  }

  default String visitDirectCall(MIR.DirectCall call, boolean checkMagic) {
    return emitDirectCall(call, this, checkMagic);
  }

  default String visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
    var prelude = new ArrayList<String>();
    var args = staticCallArgs(call, this, checkMagic, prelude, true);
    return withTransientPrelude(prelude, callRef(call.fun(), funRef(call.fun()), args));
  }
}
