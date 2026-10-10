package codegen.zig;

import codegen.MIR;
import codegen.optimisations.RcFreeTypes;
import id.Id;
import id.Id.DecId;
import id.Mdf;
import main.CompilationUnit;
import visitors.MIRVisitor;

import java.util.*;

import codegen.zig.ZigCodegenValues.Scalar;

/// How functions take and return unboxed values: per-function shapes, wrapper shapes, and conversions between them.
interface ZigCodegenShapes extends ZigCodegenContext {
  default WrapperShape wrapperShape(DecId objId, Id.MethName mName, Mdf mdf) {
    return wrapperShapeCache().computeIfAbsent(new WrapperKey(objId, mName, mdf), k -> {
      var fun = findFun(k.objId(), k.mName(), k.mdf(), typeDefOf(k.objId()));
      return new WrapperShape(singletonReceiver(k.objId()), shapeOf(fun), resultShapeOf(fun));
    });
  }

  /// How a method wrapper takes and returns values: elided singleton receiver, scalar params, scalar result.
  record WrapperShape(
    boolean elideRecv,
    List<Optional<Scalar>> params,
    Optional<Scalar> result
  ) {}

  default boolean singletonReceiver(DecId objId) {
    var owningPkg = owningPackageOf(objId);
    var pkg = owningPkg != null ? owningPkg : emitTargetPkg();
    if (pkg == null || CompilationUnit.isCached(pkg)) { return false; }
    var typeDef = typeDefOf(objId);
    return typeDef != null && typeDef.singletonInstance()
      .filter(k -> k.captures().isEmpty()).isPresent();
  }

  record WrapperKey(DecId objId, Id.MethName mName, Mdf mdf) {}

  default List<Optional<Scalar>> shapeOf(MIR.FName f) {
    if (f == null) { return List.of(); }
    var fun = funMap().get(f);
    // Transient variants skip this table and stay boxed at the wrapper; [computeFunShape] does not skip them.
    if (fun == null || hasTransientVariant(f)) { return List.of(); }
    int arity = f.m().num();
    if (arity > fun.args().size()) { return List.of(); }
    var res = fun.args().subList(0, arity).stream().map(x -> scalarOf(x.t())).toList();
    return res.stream().anyMatch(Optional::isPresent) ? res : List.of();
  }

  default Optional<Scalar> resultShapeOf(MIR.FName f) {
    if (f == null || hasTransientVariant(f)) { return Optional.empty(); }
    var fun = funMap().get(f);
    return fun == null ? Optional.empty() : scalarSumOf(fun.ret());
  }

  default Optional<Scalar> funResultShape(MIR.FName f) {
    var fun = funMap().get(f);
    return fun == null ? Optional.empty() : scalarSumOf(fun.ret());
  }

  default String zigReturnType(Optional<Scalar> shape) {
    return shape.map(Scalar::zigType).orElse("rt.FatPtr");
  }

  default MIR.TypeDef typeDefOf(DecId objId) {
    return program().pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
  }

  default String paramType(List<Optional<Scalar>> unboxed, int i) {
    return i < unboxed.size() && unboxed.get(i).isPresent()
      ? unboxed.get(i).orElseThrow().zigType()
      : "rt.FatPtr";
  }

  default String declOfType(DecId objId, String name) {
    var owningPkg = owningPackageOf(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg())) {
      return "root.pkg_" + ZigStringIds.manglePkg(owningPkg) + "." + name;
    }
    return name;
  }

  default List<String> unboxArgs(DecId objId, Id.MethName mName, Mdf mdf, List<String> args) {
    var shape = wrapperShape(objId, mName, mdf);
    var scalarArgs = new ArrayList<>(args);
    for (int i = 0; i < shape.params().size() && i + 1 < scalarArgs.size(); i++) {
      int at = i + 1;
      shape.params().get(i).ifPresent(sc -> scalarArgs.set(at, toScalar(sc, scalarArgs.get(at))));
    }
    if (shape.elideRecv() && !scalarArgs.isEmpty()) { scalarArgs.removeFirst(); }
    return scalarArgs;
  }

  /// One function parameter slot: elided, boxed, or a scalar of known size.
  record ArgSlot(boolean elided, Optional<Scalar> scalar) {
    static final ArgSlot BOXED = new ArgSlot(false, Optional.empty());
    static final ArgSlot ELIDED = new ArgSlot(true, Optional.empty());
    boolean isBoxed() { return !elided && scalar.isEmpty(); }
    int bytes() { return scalar.map(Scalar::bytes).orElse(16); }
    int align() { return scalar.map(Scalar::bytes).orElse(8); }
  }

  default List<ArgSlot> funShape(MIR.FName f) {
    return funShapeCache().computeIfAbsent(f, this::computeFunShape);
  }

  default Optional<Scalar> scalarX(MIR.X x) {
    if (currentFun() == null) { return Optional.empty(); }
    var fun = funMap().get(currentFun());
    if (fun == null) { return Optional.empty(); }
    var shape = funShape(currentFun());
    for (int i = 0; i < fun.args().size(); i++) {
      if (!fun.args().get(i).name().equals(x.name()) || i >= shape.size()) { continue; }
      return shape.get(i).scalar().filter(Scalar::isSum);
    }
    return Optional.empty();
  }

  /// The scalar an expression already holds, if it holds one.
  default Optional<Scalar> emittedScalar(MIR.E e) {
    return switch (e) {
      case MIR.X x -> scalarX(x);
      case MIR.Block block -> emittedScalar(block.original());
      case MIR.DirectCall call -> wrapperShape(
        call.concreteType(),
        call.original().name(),
        call.original().mdf()
      ).result();
      case MIR.GuardedCall call -> scalarSumOf(call.t());
      case MIR.BoolExpr expr -> scalarSumOf(expr.t());
      case MIR.SumMatch expr -> scalarSumOf(expr.t());
      default -> Optional.empty();
    };
  }

  default String boxed(MIR.E e, String code) {
    return emittedScalar(e).map(sc -> toBoxed(sc, code)).orElse(code);
  }

  default String boxedOwned(MIR.E e, String code) {
    return emittedScalar(e).map(sc -> toBoxedOwned(sc, code)).orElse(code);
  }

  default String boxedBorrowed(MIR.E e, String code, List<String> prelude) {
    return emittedScalar(e).map(sc -> borrowedBox(sc, code, prelude)).orElse(code);
  }

  /// Converts a returned value to the current function's result shape.
  default String currentReturn(MIR.E e, String code, boolean checkMagic) {
    return funResultShape(currentFun())
      .map(target -> scalarOwned(target, e, code, this, checkMagic))
      .orElseGet(() -> boxedOwned(e, code));
  }

  default String currentReturn(MIR.E e, boolean checkMagic) {
    var target = funResultShape(currentFun());
    if (target.isEmpty()) { return boxExpr(e, this, checkMagic); }
    return scalarOwned(target.orElseThrow(), e, e.accept(this, checkMagic), this, checkMagic);
  }

  /// Produces a borrowed scalar: reshapes an existing one, folds a literal to its tag, else unboxes.
  /// `gen` prints the captures of a folded niche literal. It must be the code generator that prints `e`.
  default String scalar(Scalar target, MIR.E e, String code, MIRVisitor<String> gen) {
    var source = emittedScalar(e);
    if (source.isPresent()) { return reshapeScalar(source.orElseThrow(), target, code); }
    if (!target.isSum()) { return toScalar(target, code); }
    if (target.isNiche()) {
      var folded = nicheBorrowedFold(target, e, gen, true);
      if (folded.isPresent()) { return folded.orElseThrow(); }
    }
    if (!target.isNiche() && e instanceof MIR.CreateObj obj) {
      var tag = target.sum().tag(obj.concreteT().id());
      if (tag >= 0) { return "@as(u8, " + tag + ")"; }
    }
    return toScalar(target, code);
  }

  /// Retags between tag sums, or round-trips through a box for mixed representations.
  default String reshapeScalar(Scalar source, Scalar target, String code) {
    if (source.equals(target) || source.isSum() && target.isSum()
        && source.sum().repr() == target.sum().repr()
        && source.sum().variants().equals(target.sum().variants())) { return code; }
    if (source.isSum() && target.isSum() && !source.isNiche() && !target.isNiche()) {
      var out = new StringBuilder("switch (").append(code).append(") {");
      for (int i = 0; i < source.sum().variantIds().size(); i++) {
        var tag = target.sum().tag(source.sum().variantIds().get(i));
        if (tag < 0) {
          throw new IllegalStateException("Sum variant is absent from target: "
            + source.sum().variantIds().get(i) + " in " + target.sum().declared());
        }
        out.append(i).append(" => @as(u8, ").append(tag).append("), ");
      }
      out.append("else => { std.debug.assert(false); unreachable; }, }");
      return out.toString();
    }
    return withBorrowedBox(source, code, box -> toScalar(target, box));
  }

  /// Reshapes with ownership: boxes through `FatPtr` when the representations differ, then drops the box.
  default String reshapeScalarOwned(Scalar source, Scalar target, String code) {
    if (source.equals(target) || source.isSum() && target.isSum()
        && source.sum().repr() == target.sum().repr()
        && source.sum().variants().equals(target.sum().variants())
        || source.isSum() && target.isSum() && !source.isNiche() && !target.isNiche()) {
      return reshapeScalar(source, target, code);
    }
    var box = freshName("fear_sum_reshape_box_");
    var result = freshName("fear_sum_reshape_value_");
    var block = freshName("fear_sum_reshape_");
    var encoded = toScalar(target, box);
    return block + ": {\nconst " + box + " = " + toBoxedOwned(source, code) + ";\n"
      + "const " + result + " = " + (target.isNiche() ? ownedNiche(encoded) : encoded) + ";\n"
      + generateDecrement(box, RcFreeTypes.Strategy.DYNAMIC) + ";\n"
      + "break :" + block + " " + result + ";\n}";
  }

  /// An owned scalar. Lent variables reshape without boxing; literals fold; any other value is consumed.
  /// The owned box of a `Box` converts through [#toScalarOwned], which releases the box.
  default String scalarOwned(
      Scalar target,
      MIR.E e,
      String code,
      MIRVisitor<String> gen,
      boolean checkMagic
  ) {
    var source = emittedScalar(e);
    if (source.isPresent()) {
      if (!lendsValue(e)) { return reshapeScalarOwned(source.orElseThrow(), target, code); }
      var converted = reshapeScalar(source.orElseThrow(), target, code);
      return target.isNiche() ? ownedNiche(converted) : converted;
    }
    if (!target.isNiche()) { return scalar(target, e, code, gen); }
    var folded = nicheOwnedFold(target, e, gen, checkMagic);
    if (folded.isPresent()) { return folded.orElseThrow(); }
    return lendsValue(e) ? ownedNiche(toScalar(target, code)) : toScalarOwned(target, code);
  }

  /// True when a niche target takes the literal `e` as its capture, with no object: the captureless variant with no captures, or the capturing variant with one capture.
  /// [#nicheOwnedFold] and [#nicheBorrowedFold] fold the literals that this accepts and no others.
  default boolean foldsToNiche(Scalar target, MIR.E e, boolean checkMagic) {
    if (!(e instanceof MIR.CreateObj obj)) { return false; }
    if (checkMagic && magicImpls().get(obj).isPresent()) { return false; }
    var objId = obj.concreteT().id();
    if (objId.equals(target.sum().capturelessVariant().id()) && obj.captures().isEmpty()) { return true; }
    return objId.equals(target.sum().capturingVariant().id()) && obj.captures().size() == 1;
  }

  /// True when a call folds the operand `e` at a parameter that converts to `target`, so `e` needs no object, temp or prelude line.
  /// [#scalar] folds a borrowed operand with the same test and passes `true` for `checkMagic`, so this passes `true` too.
  default boolean foldsAtOperand(Optional<Scalar> target, MIR.E e) {
    return target.filter(Scalar::isNiche).map(niche -> foldsToNiche(niche, e, true)).orElse(false);
  }

  /// Folds a sum literal to its niche value at compile time: no runtime test, no allocation.
  default Optional<String> nicheOwnedFold(
      Scalar target,
      MIR.E e,
      MIRVisitor<String> gen,
      boolean checkMagic
  ) {
    if (!(e instanceof MIR.CreateObj obj) || !foldsToNiche(target, e, checkMagic)) { return Optional.empty(); }
    if (obj.captures().isEmpty()) { return Optional.of("rt.sum_0captures()"); }
    emitCreateObj(obj, checkMagic);
    var x = obj.captures().first();
    var code = x.accept(gen, checkMagic);
    var nested = emittedScalar(x);
    if (nested.isPresent()) { return Optional.of(toBoxed(nested.orElseThrow(), code)); }
    return Optional.of(isRcFree(x) ? code : generateBoxBorrowed(code, x.t()));
  }

  /// Folds a sum literal to its niche value without taking ownership of its capture. `gen` prints the capture.
  default Optional<String> nicheBorrowedFold(
      Scalar target,
      MIR.E e,
      MIRVisitor<String> gen,
      boolean checkMagic
  ) {
    if (!(e instanceof MIR.CreateObj obj) || !foldsToNiche(target, e, checkMagic)) { return Optional.empty(); }
    if (obj.captures().isEmpty()) { return Optional.of("rt.sum_0captures()"); }
    emitCreateObj(obj, checkMagic);
    var x = obj.captures().first();
    return Optional.of(boxed(x, x.accept(gen, checkMagic)));
  }

  /// A value borrowed from a variable rather than freshly computed.
  /// A `Box` is never lent: its code is an owned box, even when the boxed value is a variable.
  default boolean lendsValue(MIR.E e) {
    return e instanceof MIR.X;
  }

  default String scalarArmResult(MIR.MT resultT, MIR.FName armName, String code, boolean deInlined) {
    var shape = scalarSumOf(resultT);
    if (shape.isEmpty()) { return code; }
    if (deInlined) {
      return funResultShape(armName)
        .map(source -> reshapeScalarOwned(source, shape.orElseThrow(), code))
        .orElse(code);
    }
    var body = funMap().get(armName).body();
    var e = body instanceof MIR.Block b ? b.original() : body;
    return scalarOwned(shape.orElseThrow(), e, code, this, true);
  }

  /// Converts a guarded-call result to the call-site shape: reshapes a scalar arm, unboxes a boxed one.
  default String guardedArm(DecId target, MIR.MCall original, Optional<Scalar> siteScalar, String code) {
    var armShape = wrapperShape(target, original.name(), original.mdf()).result();
    if (siteScalar.isPresent()) {
      return armShape.isPresent()
        ? reshapeScalarOwned(armShape.orElseThrow(), siteScalar.orElseThrow(), code)
        : toScalarOwned(siteScalar.orElseThrow(), code);
    }
    return armShape.map(sc -> toBoxedOwned(sc, code)).orElse(code);
  }

  /// Marshals the operands of a wrapper call. `gen` is the code generator that printed `args`.
  default List<String> marshalWrapperArgs(
      DecId objId,
      MIR.MCall call,
      List<String> args,
      MIRVisitor<String> gen,
      List<String> prelude
  ) {
    return marshalWrapperArgs(objId, call, args, null, gen, prelude);
  }

  /// Marshals the operands of a wrapper call. `nativeSums[i]` marks an operand that is already an unboxed sum.
  /// `gen` is the code generator that printed `args`.
  default List<String> marshalWrapperArgs(
      DecId objId,
      MIR.MCall call,
      List<String> args,
      boolean[] nativeSums,
      MIRVisitor<String> gen,
      List<String> prelude
  ) {
    var shape = wrapperShape(objId, call.name(), call.mdf());
    var targets = operandTargets(objId, call);
    var out = new ArrayList<String>();
    if (!shape.elideRecv()) {
      out.add(marshalWrapperArg(
        targets.getFirst(),
        call.recv(),
        args.getFirst(),
        nativeSums != null && nativeSums[0],
        gen,
        prelude
      ));
    }
    for (int i = 0; i < call.args().size(); i++) {
      out.add(marshalWrapperArg(
        targets.get(i + 1),
        call.args().get(i),
        args.get(i + 1),
        nativeSums != null && nativeSums[i + 1],
        gen,
        prelude
      ));
    }
    return out;
  }

  /// The scalar that a wrapper call converts each operand to, receiver first. An empty entry is an operand that stays boxed.
  /// Each operand that a call prepares or marshals reads its target from this list.
  default List<Optional<Scalar>> operandTargets(DecId objId, MIR.MCall call) {
    var params = wrapperShape(objId, call.name(), call.mdf()).params();
    var targets = new ArrayList<Optional<Scalar>>();
    targets.add(Optional.<Scalar>empty());
    for (int i = 0; i < call.args().size(); i++) {
      targets.add(i < params.size() ? params.get(i) : Optional.<Scalar>empty());
    }
    return targets;
  }

  /// Marshals one wrapper argument: scalar-convert, borrow-box, or reshape a value already held unboxed.
  /// `gen` is the code generator that printed `code`.
  default String marshalWrapperArg(
      Optional<Scalar> target,
      MIR.E expression,
      String code,
      boolean nativeSum,
      MIRVisitor<String> gen,
      List<String> prelude
  ) {
    if (!nativeSum) {
      return target.map(value -> scalar(value, expression, code, gen))
        .orElseGet(() -> boxedBorrowed(expression, code, prelude));
    }
    var source = scalarSumOf(expression.t()).orElseThrow();
    return target.isPresent()
      ? reshapeScalar(source, target.orElseThrow(), code)
      : borrowedBox(source, code, prelude);
  }

  /// Slots for a function: declared parameters unbox sums only; the receiver and captures unbox everything.
  /// A bare primitive changes only the ABI width: the prologue re-boxes it, and the body never sees it.
  default List<ArgSlot> computeFunShape(MIR.FName f) {
    var fun = funMap().get(f);
    if (fun == null) { return List.of(); }
    var selfIdx = selfArgIndex(fun);
    var standIn = standInSelfSlot(f);
    var slots = new ArrayList<ArgSlot>();
    for (int i = 0; i < fun.args().size(); i++) {
      if (i < selfIdx) {
        slots.add(scalarSumOf(fun.args().get(i).t())
          .map(sc -> new ArgSlot(false, Optional.of(sc)))
          .orElse(ArgSlot.BOXED));
        continue;
      }
      if (i == selfIdx && standIn.isPresent() && standIn.getAsInt() == i) {
        slots.add(ArgSlot.ELIDED);
        continue;
      }
      slots.add(scalarOf(fun.args().get(i).t())
        .map(sc -> new ArgSlot(false, Optional.of(sc)))
        .orElse(ArgSlot.BOXED));
    }
    return slots;
  }

  default List<String> shapeArgs(MIR.FName f, List<String> args) {
    return shapeArgs(f, args, 0);
  }

  default List<String> shapeArgs(MIR.FName f, List<String> args, int preshaped) {
    var shape = funShape(f);
    if (shape.size() != args.size()) { return args; }
    var res = new ArrayList<String>();
    for (int i = 0; i < args.size(); i++) {
      var slot = shape.get(i);
      if (slot.elided()) { continue; }
      var arg = args.get(i);
      res.add(i < preshaped ? arg : slot.scalar().map(sc -> toScalar(sc, arg)).orElse(arg));
    }
    return res;
  }

  /// Converts an argument between shapes: identical passes, boxed unboxes, scalar re-boxes first.
  default String reshapeArg(
      Optional<Scalar> from,
      Optional<Scalar> to,
      String name,
      List<String> prelude
  ) {
    if (from.equals(to)) { return name; }
    if (from.isEmpty()) { return toScalar(to.orElseThrow(), name); }
    var box = borrowedBox(from.orElseThrow(), name, prelude);
    return to.isEmpty() ? box : toScalar(to.orElseThrow(), box);
  }

  default List<String> reshapeArgs(
      MIR.FName f,
      List<Optional<Scalar>> sources,
      List<String> args,
      List<String> prelude
  ) {
    var targets = funShape(f);
    if (targets.size() != args.size() || sources.size() != args.size()) {
      throw new IllegalStateException("Argument shape mismatch for " + f);
    }
    var result = new ArrayList<String>();
    for (int i = 0; i < args.size(); i++) {
      var target = targets.get(i);
      if (target.elided()) { continue; }
      result.add(reshapeArg(sources.get(i), target.scalar(), args.get(i), prelude));
    }
    return result;
  }

  /// Tests a condition: tag compare for scalar bools, vtable compare otherwise.
  default String boolCondition(MIR.E condition, String code) {
    var scalar = emittedScalar(condition);
    if (scalar.isPresent() && scalar.get().isSum()) {
      var trueTag = scalar.get().sum().tag(new DecId("base.True", 0));
      if (trueTag >= 0) { return code + " == @as(u8, " + trueTag + ")"; }
    }
    return boxed(condition, code) + ".vt == &" + vtableRef(new DecId("base.True", 0));
  }

  /// A stand-in self for elided receivers: the `True` singleton.
  default String standInSelf() {
    return "rt.obj_k_singleton(&" + vtableRef(new DecId("base.True", 0)) + ")";
  }

  /// Calls a method wrapper: inlines what [RecursionHotness] selects, probing for cross-package inline wrappers at compile time.
  /// `gen` is the code generator that printed `args`.
  default String methCallRef(
      DecId objId,
      MIR.MCall original,
      MIR.FName callee,
      List<String> args,
      MIRVisitor<String> gen
  ) {
    var methName = id().getMName(original.mdf(), original.name());
    var prelude = new ArrayList<String>();
    args = marshalWrapperArgs(objId, original, args, gen, prelude);
    var argList = String.join(", ", args);
    var plain = methWrapperRef(objId, methName) + "(" + argList + ")";
    if (!hotness().inlineTarget(currentFun(), callee)) {
      return withTransientPrelude(prelude, plain);
    }
    if (inlineHere(objId)) {
      return withTransientPrelude(prelude,
        methInlineWrapperRef(objId, methName) + "(" + argList + ")");
    }
    var owningPkg = owningPackageOf(objId);
    if (owningPkg == null) { return withTransientPrelude(prelude, plain); }
    var inlineName = "MFI_" + id().getSimpleName(objId) + "_" + methName;
    var pkgRef = "root.pkg_" + ZigStringIds.manglePkg(owningPkg);
    return withTransientPrelude(prelude, "(if (@hasDecl(" + pkgRef + ", \"" + inlineName + "\")) "
      + pkgRef + "." + inlineName + "(" + argList + ") else " + plain + ")");
  }

  /// Cached packages publish inline wrappers for hot functions, probed by callers with `@hasDecl`.
  default boolean publishesInlineWrapper(MIR.FName f) {
    return emitTargetPkg() != null && CompilationUnit.isCached(emitTargetPkg())
      && hotness().fitsInlineBudget(f);
  }
}
