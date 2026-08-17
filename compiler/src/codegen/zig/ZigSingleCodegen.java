package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import codegen.optimisations.ReturnShapeAnalysis;
import id.Id;
import id.Id.DecId;
import magic.Magic;
import utils.Bug;
import visitors.MIRVisitor;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ZigSingleCodegen implements MIRVisitor<String> {
  protected final MIR.Program p;
  protected final Map<MIR.FName, MIR.Fun> funMap;
  private final ZigMagicImpls magic;
  public final ZigStringIds id = new ZigStringIds();
  final ZigSigStringBuilder sigBuilder;
  private int transientCounter = 0;
  private int blockCounter = 0;

  static class PackageState {
    final String packageName;
    final List<String> functions = new ArrayList<>();
    final LinkedHashMap<DecId, String> captureStructs = new LinkedHashMap<>();
    final LinkedHashMap<String, String> vtableDefs = new LinkedHashMap<>();

    // The capture list, as recorded at CreateObj emission. The box hook needs the exact set, in
    // order, and a second derivation from the AST is not reliable.
    final LinkedHashMap<DecId, SortedSet<MIR.X>> captureLists = new LinkedHashMap<>();

    PackageState(String packageName) { this.packageName = packageName; }
  }

  public final Map<String, PackageState> packageStates = new LinkedHashMap<>();
  public final LinkedHashMap<DecId, Boolean> emittedTypes = new LinkedHashMap<>();

  final Map<DecId, String> typeToPackage = new HashMap<>();

  private String emitTargetPkg;
  private String pkg;

  private final boolean vpfEnabled;
  /// Only for {@link VPFCodegen#containsVPFCall}, which keeps no scope. The instrumentation state
  /// of a function lives on the throwaway instance that {@link #visitFun} makes.
  private final VPFCodegen vpf;
  final Map<MIR.FName, Boolean> vpfBranchCache = new HashMap<>();
  final ReturnShapeAnalysis shapes;
  private final Map<MIR.FName, Boolean> transientVariantCache = new HashMap<>();

  public ZigSingleCodegen(MIR.Program p, boolean vpfEnabled) {
    this.vpfEnabled = vpfEnabled;
    magic = new ZigMagicImpls(this, t -> "rt.FatPtr", p.p());
    sigBuilder = new ZigSigStringBuilder(p.p());
    this.p = p;
    this.funMap = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f));

    for (var mpkg : p.pkgs()) {
      for (var defId : mpkg.defs().keySet()) {
        typeToPackage.put(defId, mpkg.name());
      }
    }

    this.vpf = new VPFCodegen(this);
    this.shapes = new ReturnShapeAnalysis(p, this::isTransientCreateObj);
  }

  PackageState getOrCreatePackageState(String pkgName) {
    return packageStates.computeIfAbsent(pkgName, PackageState::new);
  }

  PackageState currentState() {
    return getOrCreatePackageState(emitTargetPkg);
  }

  /// A VTable reference. It gets the root.pkg_ prefix when it points to a different package.
  public String vtableRef(DecId objId) {
    return vtableRef(objId, false);
  }

  public String vtableRef(DecId objId, boolean transientVt) {
    var typeName = id.getSimpleName(objId);
    var vtName = "VT_" + typeName + (transientVt ? "_transient" : "");
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + vtName;
    }
    return vtName;
  }

  /// A Captures struct reference. It gets the root.pkg_ prefix when it points to a different
  /// package.
  public String capturesRef(DecId objId) {
    var typeName = id.getSimpleName(objId);
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + typeName + "_Captures";
    }
    return typeName + "_Captures";
  }

  /// A static function reference. It gets the root.pkg_ prefix when it points to a different
  /// package.
  public String funRef(MIR.FName fName) {
    var zigName = id.getFName(fName);
    var owningPkg = typeToPackage.get(fName.d());
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + zigName;
    }
    return zigName;
  }

  /// A reference to the per-literal method wrapper of `objId`. It gets the root.pkg_ prefix when
  /// it points to a different package. The wrapper takes `(receiver, args...)`, so a caller needs
  /// no captures.
  public String methWrapperRef(DecId objId, String methName) {
    var name = "MF_" + id.getSimpleName(objId) + "_" + methName;
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + name;
    }
    return name;
  }

  /// The package that holds `objId`, or null when no package declares it (an anonymous literal
  /// takes the package it is emitted into).
  public String packageOf(DecId objId) { return typeToPackage.get(objId); }

  public boolean isLiteral(DecId d) {
    return id.getLiteral(p.p(), d).isPresent();
  }

  boolean hasIdentityType(DecId d) {
    return p.p().superDecIds(d).contains(Magic.HasIdentity);
  }

  boolean isTransientEligibleType(DecId d) {
    return !hasIdentityType(d);
  }

  boolean isTransientCreateObj(MIR.E e) {
    return e instanceof MIR.CreateObj obj && isTransientEligibleType(obj.concreteT().id()) && !obj.captures().isEmpty();
  }

  record Materialised(String ref, List<String> prelude) {}

  Materialised materialiseTransient(MIR.CreateObj createObj, MIRVisitor<String> gen, boolean checkMagic) {
    emitCreateObj(createObj, checkMagic);
    var objId = createObj.concreteT().id();
    var tmp = "fear_transient_" + transientCounter++;
    var captures = createObj.captures().stream()
      .map(x -> "." + id.varName(x.name()) + " = " + gen.visitX(x, checkMagic))
      .collect(Collectors.joining(", "));
    var prelude = new ArrayList<String>();
    prelude.add("var " + tmp + "_obj: rt.GenObjectLayoutType(" + capturesRef(objId) + ") = undefined;");
    prelude.add("const " + tmp + " = rt.init_transient_obj(" + capturesRef(objId) + ", &" + tmp + "_obj, &" + vtableRef(objId, true) + ", .{ " + captures + " });");
    prelude.add("defer rt.drop_transient_obj(" + capturesRef(objId) + ", &" + tmp + "_obj);");
    return new Materialised(tmp, prelude);
  }

  String withTransientPrelude(List<String> prelude, String expr) {
    if (prelude.isEmpty()) { return expr; }
    var label = "fear_blk_" + blockCounter++;
    return label + ": {\n" + String.join("\n", prelude) + "\nbreak :" + label + " " + expr + ";\n}";
  }

  /// True when codegen emits a `_transient` variant of `fName`: the function returns a fresh
  /// transient-eligible literal, some `DirectCall`/`StaticCall` site targets it, and its shape
  /// is one the variant emitter covers. A VPF-instrumented function keeps its variant: the
  /// variant is the plain sequential form of the body, emitted beside the instrumented one.
  /// This is the one predicate both the variant emitters and every call site consult, so a
  /// reference to a `_transient` symbol and its definition cannot go out of sync.
  boolean hasTransientVariant(MIR.FName fName) {
    var cached = transientVariantCache.get(fName);
    if (cached != null) { return cached; }
    // A cycle of tail forwards never bottoms out in a fresh literal, so a self-reference is false
    transientVariantCache.put(fName, false);
    var res = computeHasTransientVariant(fName);
    transientVariantCache.put(fName, res);
    return res;
  }

  private boolean computeHasTransientVariant(MIR.FName fName) {
    var fun = funMap.get(fName);
    if (fun == null) { return false; }
    if (shapes.freshObj(fName).isEmpty() || !shapes.slotWanted(fName)) { return false; }
    var body = ReturnShapeAnalysis.unwrap(fun.body());
    return switch (body) {
      case MIR.CreateObj k -> isTransientCreateObj(k);
      case MIR.DirectCall d -> shapes.calleeOf(d).map(f -> hasTransientVariant(f.name())).orElse(false)
        && operandsSlotFree(d.original().recv(), d.original().args());
      case MIR.StaticCall s -> funMap.containsKey(s.fun()) && hasTransientVariant(s.fun())
        && operandsSlotFree(null, s.args());
      default -> false;
    };
  }

  /// A tail forward binds its operands to temporaries and returns the callee-variant call, so no
  /// operand may need a prelude of its own: a prelude holds a transient whose drop has to
  /// outlive the call.
  private boolean operandsSlotFree(MIR.E recv, List<? extends MIR.E> args) {
    if (recv != null && isTransientCreateObj(recv)) { return false; }
    return args.stream().noneMatch(this::isTransientCreateObj);
  }

  private record SlotPieces(String slotVar, String caps, List<String> operandPrelude, String call) {}

  /// The pieces of a callee-fills-caller-slot call for `e`, when `e` is a `DirectCall` or
  /// `StaticCall` whose callee has a `_transient` variant: a stack-slot variable, its Captures
  /// type, the prelude its operands need, and the variant call that fills the slot.
  private Optional<SlotPieces> slotPieces(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    while (e instanceof MIR.Box box) { e = box.inner(); }
    String target;
    MIR.CreateObj summaryObj;
    var operandPrelude = new ArrayList<String>();
    var operands = new ArrayList<String>();
    if (e instanceof MIR.DirectCall d) {
      var callee = shapes.calleeOf(d).filter(f -> hasTransientVariant(f.name()));
      if (callee.isEmpty()) { return Optional.empty(); }
      summaryObj = shapes.freshObj(callee.get().name()).orElseThrow();
      var original = d.original();
      target = methWrapperRef(d.concreteType(), id.getMName(original.mdf(), original.name())) + "_transient";
      var ops = slottedCallOperands(original, gen, checkMagic, callee);
      operandPrelude.addAll(ops.prelude());
      operands.add(ops.recv());
      operands.addAll(ops.args());
    } else if (e instanceof MIR.StaticCall s && funMap.containsKey(s.fun()) && hasTransientVariant(s.fun())) {
      summaryObj = shapes.freshObj(s.fun()).orElseThrow();
      target = funRef(s.fun()) + "_transient";
      operands.addAll(staticCallArgs(s, gen, checkMagic, operandPrelude, true));
    } else {
      return Optional.empty();
    }
    emitCreateObj(summaryObj, true);
    var caps = capturesRef(summaryObj.concreteT().id());
    var slotVar = "fear_slot_" + transientCounter++;
    var call = target + "(&" + slotVar + "_obj"
      + (operands.isEmpty() ? "" : ", " + String.join(", ", operands)) + ")";
    return Optional.of(new SlotPieces(slotVar, caps, operandPrelude, call));
  }

  /// `e` computed through a caller-provided stack slot. The slot outlives the drop of every
  /// operand transient: the drop of the slot registers after theirs, so it runs first and the
  /// captures it decrements are still alive.
  Optional<Materialised> materialiseSlotCall(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    return slotPieces(e, gen, checkMagic).map(sp -> {
      var lines = new ArrayList<String>();
      lines.add("var " + sp.slotVar() + "_obj: rt.GenObjectLayoutType(" + sp.caps() + ") = undefined;");
      lines.addAll(sp.operandPrelude());
      lines.add("const " + sp.slotVar() + " = " + sp.call() + ";");
      lines.add("defer rt.drop_transient_obj(" + sp.caps() + ", &" + sp.slotVar() + "_obj);");
      return new Materialised(sp.slotVar(), lines);
    });
  }

  record VPFResultSlot(String decl, String dropDefer, String operandStatements, String call) {}

  /// The slot form of the first frame-adding sub-expr of a VPF fun, or empty. The declaration
  /// goes before the compiler fence and the drop-defer at function scope, so the slot outlives
  /// the combiner and the wait on a stolen frame.
  Optional<VPFResultSlot> vpfResultSlot(MIR.E e) {
    return slotPieces(e, this, true).map(sp -> new VPFResultSlot(
      "var " + sp.slotVar() + "_obj: rt.GenObjectLayoutType(" + sp.caps() + ") = undefined;\n",
      "defer rt.drop_transient_obj(" + sp.caps() + ", &" + sp.slotVar() + "_obj);\n",
      sp.operandPrelude().isEmpty() ? "" : String.join("\n", sp.operandPrelude()) + "\n",
      sp.call()));
  }

  String ownedExpr(MIR.E e, boolean checkMagic) {
    return ownedExpr(e, this, checkMagic);
  }

  String ownedExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    if (e instanceof MIR.X) {
      return e.accept(gen, checkMagic) + ".share()";
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, gen, checkMagic, true);
    }
    return e.accept(gen, checkMagic);
  }

  String returnExpr(MIR.E e, boolean checkMagic) {
    if (e instanceof MIR.X) {
      return visitX((MIR.X) e, checkMagic) + ".share()";
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, this, checkMagic, true);
    }
    return e.accept(this, checkMagic);
  }

  /// Wraps `e` so that a transient value cannot leave the frame that holds its storage.
  ///
  /// A call result needs no wrapping. A generated function boxes its own return, and a runtime
  /// intrinsic never builds a transient, because a transient is a stack slot that only generated
  /// code creates. So the boxing hook of a call result is dead code, and the load of the storage
  /// mode it tests sits between the call and the return, where it keeps the call out of tail
  /// position.
  String boxExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    return switch (e) {
      case MIR.Box box -> boxExpr(box.inner(), gen, checkMagic);
      case MIR.X x -> boxBorrowedCode(x.accept(gen, checkMagic));
      case MIR.CreateObj createObj -> boxCreateObj(createObj, gen, checkMagic);
      case MIR.BoolExpr boolExpr -> boxBoolExpr(boolExpr, gen, checkMagic);
      case MIR.MCall ignored -> e.accept(gen, checkMagic);
      case MIR.DirectCall ignored -> e.accept(gen, checkMagic);
      case MIR.StaticCall ignored -> e.accept(gen, checkMagic);
      case MIR.UpdatableListAsIdFnCall ignored -> boxOwnedCode(e.accept(gen, checkMagic));
      case MIR.Block ignored -> boxOwnedCode(e.accept(gen, checkMagic));
    };
  }

  /// The body of a function as statements, with the drop of each param placed before the call in
  /// tail position instead of after it.
  ///
  /// A `defer param.rc_decrement()` is code that runs once the return value is known, so the call
  /// that produced it is not the last thing the frame does and the backend has to keep the frame
  /// alive across it. Fearless writes a loop as a self-recursive call, so a loop of N steps then
  /// holds N frames, and a long one walks its stack out of the first-level cache. Once the
  /// operands of the call are in temporaries the params are dead, which is where the drops
  /// belong: the call becomes a tail call, and a self-recursive one becomes a loop.
  ///
  /// Every path this emits ends in a `return` or in `unreachable`, and drops each param exactly
  /// once.
  private String tailStatements(MIR.E e, List<String> paramNames, boolean checkMagic) {
    return switch (e) {
      case MIR.Box box -> tailStatements(box.inner(), paramNames, checkMagic);
      case MIR.BoolExpr boolExpr -> {
        var cond = boolExpr.condition().accept(this, checkMagic);
        yield "if (" + cond + ".vt == &" + vtableRef(new DecId("base.True", 0)) + ") {\n"
          + armTailStatements(boolExpr.then(), paramNames, checkMagic)
          + "}\n"
          + armTailStatements(boolExpr.else_(), paramNames, checkMagic);
      }
      case MIR.DirectCall call -> {
        var ops = callOperands(call.original(), this, checkMagic);
        var methName = id.getMName(call.original().mdf(), call.original().name());
        var target = methWrapperRef(call.concreteType(), methName);
        yield tailCallStatements(ops, refs -> target + "(" + String.join(", ", refs) + ")", paramNames)
          .orElseGet(() -> valueTailStatements(boxExpr(e, this, checkMagic), paramNames));
      }
      case MIR.MCall call -> {
        var ops = callOperands(call, this, checkMagic);
        yield tailCallStatements(ops, refs -> mCallOn(call, refs, checkMagic), paramNames)
          .orElseGet(() -> valueTailStatements(boxExpr(e, this, checkMagic), paramNames));
      }
      default -> valueTailStatements(boxExpr(e, this, checkMagic), paramNames);
    };
  }

  /// The emitted form of `call`, with its receiver and args already bound to `refs`.
  private String mCallOn(MIR.MCall call, List<String> refs, boolean checkMagic) {
    var sig = new MIR.Sig(call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet());
    var hashExpr = sigBuilder.inlineHash(sig);
    var recv = refs.getFirst();
    var rest = refs.subList(1, refs.size());
    var argsTuple = rest.isEmpty() ? ".{}" : ".{ " + String.join(", ", rest) + " }";
    var intrinsic = checkMagic ? primitiveIntrinsic(call.recv()) : Optional.<String>empty();
    return intrinsic
      .map(module -> "rt.dispatch_primitive(" + module + ", " + hashExpr + ", " + recv + ", " + argsTuple + ")")
      .orElseGet(() -> "rt.call(" + recv + ", " + hashExpr + ", " + argsTuple + ", @src())");
  }

  /// The `.then` or `.else` arm of a `BoolExpr` in tail position. A de-inlined arm keeps the
  /// generic form: its operands are already inside the emitted call, so there is nothing left to
  /// hoist the drops above.
  private String armTailStatements(MIR.FName arm, List<String> paramNames, boolean checkMagic) {
    var deInlined = deInlinedBranch(arm, this, checkMagic);
    if (deInlined.isPresent()) { return valueTailStatements(deInlined.get(), paramNames); }
    var body = funMap.get(arm).body();
    return tailStatements(body instanceof MIR.Block block ? block.original() : body, paramNames, checkMagic);
  }

  /// The call `target(operands...)` in tail position, or empty when the operands need a prelude.
  /// A prelude holds a transient whose storage is this frame, and whose drop has to outlive the
  /// call, so such a call is never in tail position.
  private Optional<String> tailCallStatements(CallOperands ops, Function<List<String>, String> build,
                                              List<String> paramNames) {
    if (!ops.prelude().isEmpty()) { return Optional.empty(); }
    var sb = new StringBuilder();
    var refs = new ArrayList<String>();
    for (var operand : Stream.concat(Stream.of(ops.recv()), ops.args().stream()).toList()) {
      var tmp = "fear_op_" + blockCounter++;
      sb.append("const ").append(tmp).append(" = ").append(operand).append(";\n");
      refs.add(tmp);
    }
    appendDrops(sb, paramNames);
    sb.append("return ").append(build.apply(refs)).append(";\n");
    return Optional.of(sb.toString());
  }

  private String valueTailStatements(String expr, List<String> paramNames) {
    if (expr.equals("unreachable")) { return "unreachable;\n"; }
    var sb = new StringBuilder();
    var tmp = "fear_ret_" + blockCounter++;
    sb.append("const ").append(tmp).append(" = ").append(expr).append(";\n");
    appendDrops(sb, paramNames);
    sb.append("return ").append(tmp).append(";\n");
    return sb.toString();
  }

  private void appendDrops(StringBuilder sb, List<String> paramNames) {
    for (var paramName : paramNames) {
      sb.append(paramName).append(".rc_decrement();\n");
    }
  }

  private String boxBorrowedCode(String expr) {
    return boxOwnedCode(expr + ".share()");
  }

  private String boxOwnedCode(String expr) {
    var label = "fear_blk_" + blockCounter++;
    var tmp = "fear_box_" + blockCounter++;
    return label + ": {\nconst " + tmp + " = " + expr + ";\nbreak :" + label + " " + tmp + ".box_transient();\n}";
  }

  private String boxCreateObj(MIR.CreateObj createObj, MIRVisitor<String> gen, boolean checkMagic) {
    var magicImpl = magic.get(createObj);
    if (checkMagic && magicImpl.isPresent()) {
      var res = magicImpl.get().instantiate();
      if (res.isPresent()) { return boxOwnedCode(res.get()); }
    }

    var objId = createObj.concreteT().id();
    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
    if (typeDef == null || typeDef.singletonInstance().isPresent() || createObj.captures().isEmpty()) {
      return visitCreateObj(createObj, checkMagic);
    }

    emitCreateObj(createObj, checkMagic);
    var prelude = new ArrayList<String>();
    var boxedFields = new ArrayList<String>();
    for (var x : createObj.captures()) {
      var field = id.varName(x.name());
      var tmp = field + "_boxed";
      prelude.add("const " + field + "_shared = " + x.accept(gen, checkMagic) + ".share();");
      prelude.add("const " + tmp + " = " + field + "_shared.box_transient();");
      prelude.add("defer " + tmp + ".rc_decrement();");
      boxedFields.add("." + field + " = " + tmp);
    }
    return withTransientPrelude(prelude,
      "rt.obj_k(" + capturesRef(objId) + ", &" + vtableRef(objId) + ", .{ " + String.join(", ", boxedFields) + " })");
  }

  /// A `.then` or `.else` arm whose subtree holds a VPF call becomes a call to the function of
  /// the arm, and not an inlined body. `visitFun` gives that function its own VPF
  /// instrumentation, which an inlined body generates but never calls. This works at each
  /// nesting depth, and no code counts the depth: `visitFun` emits each de-inlined arm in turn,
  /// and de-inlines again, until it reaches the level where `VPFCodegen#findVPFCall` finds and
  /// instruments the call.
  ///
  /// Returns empty when VPF is off. Thus a `--no-vpf` build keeps full inlining and stays the
  /// fastest sequential build.
  Optional<String> deInlinedBranch(MIR.FName fName, MIRVisitor<String> gen, boolean checkMagic) {
    if (!vpfEnabled || !vpf.containsVPFCall(fName)) { return Optional.empty(); }
    var fun = funMap.get(fName);
    if (fun == null || fun.args().isEmpty()) { return Optional.empty(); }
    // The args of an arm are [self, captures...]. `BoolIfOptimisation` makes a `BoolExpr` only
    // when no arm captures self, so no code reads the self param and any singleton is sufficient.
    // The captures are the same `MIR.X`s as in the enclosing scope, which is also what makes the
    // inlining correct, so they emit correctly here.
    var args = new ArrayList<String>();
    args.add("rt.obj_k_singleton(&" + vtableRef(new DecId("base.True", 0)) + ")");
    fun.args().stream().skip(1).forEach(x -> args.add(ownedExpr(x, gen, checkMagic)));
    return Optional.of(funRef(fName) + "(" + String.join(", ", args) + ")");
  }

  private String boxBoolExpr(MIR.BoolExpr expr, MIRVisitor<String> gen, boolean checkMagic) {
    String recv = expr.condition().accept(gen, checkMagic);

    String thenBody = deInlinedBranch(expr.then(), gen, checkMagic)
      .map(this::boxOwnedCode)
      .orElseGet(() -> switch (this.funMap.get(expr.then()).body()) {
        case MIR.Block b -> boxExpr(b.original(), gen, checkMagic);
        case MIR.E e -> boxExpr(e, gen, checkMagic);
      });
    String elseBody = deInlinedBranch(expr.else_(), gen, checkMagic)
      .map(this::boxOwnedCode)
      .orElseGet(() -> switch (this.funMap.get(expr.else_()).body()) {
        case MIR.Block b -> boxExpr(b.original(), gen, checkMagic);
        case MIR.E e -> boxExpr(e, gen, checkMagic);
      });

    return "(if (" + recv + ".vt == &" + vtableRef(new DecId("base.True", 0)) + ") " + thenBody + " else " + elseBody + ")";
  }

  String boolExpr(MIR.BoolExpr expr, MIRVisitor<String> gen, boolean checkMagic, boolean ownedBranches) {
    String recv = expr.condition().accept(gen, checkMagic);

    String thenBody = deInlinedBranch(expr.then(), gen, checkMagic)
      .orElseGet(() -> switch (this.funMap.get(expr.then()).body()) {
        case MIR.Block b -> inlineBlock(b, gen, ownedBranches);
        case MIR.E e -> ownedBranches ? ownedExpr(e, gen, checkMagic) : e.accept(gen, checkMagic);
      });
    String elseBody = deInlinedBranch(expr.else_(), gen, checkMagic)
      .orElseGet(() -> switch (this.funMap.get(expr.else_()).body()) {
        case MIR.Block b -> inlineBlock(b, gen, ownedBranches);
        case MIR.E e -> ownedBranches ? ownedExpr(e, gen, checkMagic) : e.accept(gen, checkMagic);
      });

    return "(if (" + recv + ".vt == &" + vtableRef(new DecId("base.True", 0)) + ") " + thenBody + " else " + elseBody + ")";
  }

  public String visitTypeDef(String pkg, MIR.TypeDef def, List<MIR.Fun> funs) {
    this.pkg = pkg;
    this.emitTargetPkg = pkg;
    var isMagic = pkg.equals("base") && def.name().name().endsWith("Instance");
    var isLiteral = isLiteral(def.name());
    if (isMagic || isLiteral) { return ""; }

    var leastSpecific = ParentWalker.leastSpecificSigs(p, def);

    def.singletonInstance().ifPresent(objK -> {
      emitCreateObj(objK, true);
    });

    for (var fun : funs) {
      visitFun(fun);
    }

    return ""; // The output collects in the package state
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

    var savedEmitTarget = this.emitTargetPkg;
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null) {
      this.emitTargetPkg = owningPkg;
    } else {
      // An anonymous or literal type is in no package defs, so record the package used here
      typeToPackage.put(objId, this.emitTargetPkg);
    }

    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
    var leastSpecific = typeDef != null
      ? ParentWalker.leastSpecificSigs(p, typeDef)
      : java.util.Map.<Id.MethName, MIR.Sig>of();

    if (!createObj.captures().isEmpty()) {
      var fields = createObj.captures().stream()
        .map(x -> id.varName(x.name()) + ": rt.FatPtr,")
        .collect(Collectors.joining("\n"));
      currentState().captureStructs.put(objId,
        "const " + id.getSimpleName(objId) + "_Captures = extern struct {\n"
        + fields + "\n};");
      currentState().captureLists.put(objId, createObj.captures());
    }

    for (var meth : createObj.meths()) {
      emitMeth(meth, objId, false, leastSpecific);
    }
    for (var meth : createObj.unreachableMs()) {
      emitMeth(meth, objId, true, leastSpecific);
    }

    // Add the inherited methods of the TypeDef that the CreateObj does not hold
    var allMeths = new ArrayList<>(createObj.meths());
    allMeths.addAll(createObj.unreachableMs());
    var coveredNames = allMeths.stream()
      .map(m -> m.sig().name())
      .collect(Collectors.toCollection(HashSet::new));

    if (typeDef != null) {
      for (var sig : leastSpecific.values()) {
        if (coveredNames.contains(sig.name())) { continue; }
        var fName = findFunForSig(objId, sig, typeDef);
        if (fName != null) {
          var meth = new MIR.Meth(objId, sig, fName.capturesSelf(),
            new TreeSet<>(), Optional.of(fName));
          emitMeth(meth, objId, false, leastSpecific);
          allMeths.add(meth);
          coveredNames.add(sig.name());
        }
      }
    }

    emitVTable(allMeths, objId);

    this.emitTargetPkg = savedEmitTarget;
  }

  private MIR.FName findFunForSig(DecId objId, MIR.Sig sig, MIR.TypeDef typeDef) {
    for (boolean capturesSelf : new boolean[]{false, true}) {
      var fName = new MIR.FName(objId, sig.name(), capturesSelf, sig.mdf());
      if (funMap.containsKey(fName)) { return fName; }
    }
    for (var parent : ParentWalker.of(p, typeDef).skip(1).toList()) {
      for (boolean capturesSelf : new boolean[]{false, true}) {
        var fName = new MIR.FName(parent.name(), sig.name(), capturesSelf, sig.mdf());
        if (funMap.containsKey(fName)) { return fName; }
      }
    }
    return null;
  }

  private void emitMeth(MIR.Meth meth, DecId objId, boolean isUnreachable,
                         Map<Id.MethName, MIR.Sig> leastSpecific) {
    var sig = meth.sig();

    var methName = id.getMName(sig.mdf(), sig.name());
    var typeName = id.getSimpleName(objId);
    var mfName = "MF_" + typeName + "_" + methName;
    var tName = "T_" + typeName + "_" + methName;

    var params = new ArrayList<String>();
    params.add("self_m: rt.FatPtr");
    for (var x : sig.xs()) {
      params.add(id.varName(x.name()) + ": rt.FatPtr");
    }
    var paramStr = String.join(", ", params);

    var paramDiscard = "_ = .{ " + params.stream().map(p -> p.split(":")[0].trim()).collect(Collectors.joining(", ")) + " };\n";
    if (isUnreachable || meth.fName().isEmpty()) {
      currentState().functions.add("pub fn " + mfName + "(" + paramStr + ") callconv(.c) rt.FatPtr {\n"
        + paramDiscard
        + "unreachable;\n"
        + "}");
    } else {
      var fRef = funRef(meth.fName().get());
      var fun = funMap.get(meth.fName().get());
      if (fun != null) {
        var simpleArgs = new ArrayList<String>();
        for (var x : sig.xs()) {
          simpleArgs.add(id.varName(x.name()));
        }
        simpleArgs.add("self_m");
        for (var capture : meth.captures()) {
          if (!createObjHasCaptures(objId)) {
            simpleArgs.add("self_m.share()"); // No captures, so self is a placeholder
          } else {
            simpleArgs.add(
              "rt.deref(" + capturesRef(objId) + ", self_m)." + id.varName(capture) + ".share()");
          }
        }

        var tracePush = "shadow_stack_mod.tracePush(&" + vtableRef(objId) + ", " + sigBuilder.inlineHash(sig) + ");\n"
          + "defer shadow_stack_mod.tracePop();\n";

        currentState().functions.add("pub fn " + mfName + "(" + paramStr + ") callconv(.c) rt.FatPtr {\n"
          + paramDiscard
          + tracePush
          + "return " + fRef + "(" + String.join(", ", simpleArgs) + ");\n"
          + "}");

        // The `_transient` wrapper: same capture plumbing, with the caller slot passed through
        if (hasTransientVariant(meth.fName().get())) {
          var summaryObj = shapes.freshObj(meth.fName().get()).orElseThrow();
          emitCreateObj(summaryObj, true);
          var caps = capturesRef(summaryObj.concreteT().id());
          currentState().functions.add("pub fn " + mfName + "_transient(fear_out: *rt.GenObjectLayoutType(" + caps + "), " + paramStr + ") callconv(.c) rt.FatPtr {\n"
            + paramDiscard
            + tracePush
            + "return " + fRef + "_transient(fear_out, " + String.join(", ", simpleArgs) + ");\n"
            + "}");
        }
      } else {
        currentState().functions.add("pub fn " + mfName + "(" + paramStr + ") callconv(.c) rt.FatPtr {\n"
          + "_ = .{ " + params.stream().map(p -> p.split(":")[0].trim()).collect(Collectors.joining(", ")) + " };\n"
          + "unreachable;\n"
          + "}");
      }
    }

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

    currentState().functions.add("fn " + tName + "(" + String.join(", ", thunkParams) + ") callconv(.c) rt.FatPtr {\n"
      + "return " + mfName + "(" + String.join(", ", thunkCallArgs) + ");\n"
      + "}");
  }

  private boolean createObjHasCaptures(DecId objId) {
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null) {
      var state = packageStates.get(owningPkg);
      if (state != null) { return state.captureStructs.containsKey(objId); }
    }
    return false;
  }

  private boolean isSingletonType(DecId objId) {
    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst().orElse(null);
    if (typeDef != null && typeDef.singletonInstance().isPresent()) return true;
    return !createObjHasCaptures(objId);
  }

  private void emitVTable(List<MIR.Meth> allMeths, DecId objId) {
    emitVTable(allMeths, objId, false);
    if (isTransientEligibleType(objId) && createObjHasCaptures(objId)) {
      emitBoxHook(objId);
      emitVTable(allMeths, objId, true);
    }
  }

  private void emitVTable(List<MIR.Meth> allMeths, DecId objId, boolean transientVt) {
    var typeName = id.getSimpleName(objId);
    var hashes = new ArrayList<String>();
    var methods = new ArrayList<String>();
    var methodNames = new ArrayList<String>();

    allMeths.forEach(meth -> {
      var sig = meth.sig();
      hashes.add(sigBuilder.hashExpr(sig));
      methods.add("&T_" + typeName + "_" + id.getMName(sig.mdf(), sig.name()));
      methodNames.add(sigBuilder.sigString(sig));
    });

    var hashesStr = hashes.isEmpty() ? "&.{}" :
      "&[_]u64{ " + String.join(", ", hashes) + " }";
    var methodsStr = methods.isEmpty() ? "&.{}" :
      "&[_]*const anyopaque{ " + String.join(", ", methods) + " }";
    var methodNamesStr = methodNames.isEmpty() ? "&.{}" :
      "&[_][]const u8{ " + String.join(", ", methodNames) + " }";

    var isTransient = transientVt && !isSingletonType(objId);
    var storageModeLine = isTransient
      ? ".storage_mode = .transient,\n"
      : isSingletonType(objId) ? ".storage_mode = .singleton,\n" : "";
    var boxLine = isTransient ? ".box_fn = &box_" + typeName + ",\n" : "";

    var vtKey = typeName + (isTransient ? "_transient" : "");
    currentState().vtableDefs.put(vtKey,
      "pub const VT_" + typeName + (isTransient ? "_transient" : "") + ": rt.VTable = .{\n"
      + ".type_name = \"" + objId.name() + "/" + objId.gen() + "\",\n"
      + ".hashes = " + hashesStr + ",\n"
      + ".methods = " + methodsStr + ",\n"
      + ".method_names = " + methodNamesStr + ",\n"
      + storageModeLine
      + boxLine
      + "};");
  }

  private void emitBoxHook(DecId objId) {
    var typeName = id.getSimpleName(objId);
    var capturesName = capturesRef(objId);
    var hookName = "box_" + typeName;
    if (currentState().functions.stream().anyMatch(f -> f.startsWith("fn " + hookName + "("))) { return; }

    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst()
      .orElse(null);
    if (typeDef == null || typeDef.singletonInstance().isPresent()) { return; }

    var sb = new StringBuilder();
    sb.append("fn ").append(hookName).append("(self_m: rt.FatPtr) callconv(.c) rt.FatPtr {\n");
    sb.append("const captures = rt.deref(").append(capturesName).append(", self_m);\n");
    var captureNames = currentState().captureLists.getOrDefault(objId, MIR.createCapturesSet());
    var boxedFields = new ArrayList<String>();
    for (var x : captureNames) {
      var field = id.varName(x.name());
      sb.append("const ").append(field).append("_shared = captures.").append(field).append(".share();\n");
      sb.append("const ").append(field).append("_boxed = ").append(field).append("_shared.box_transient();\n");
      sb.append("defer ").append(field).append("_boxed.rc_decrement();\n");
      boxedFields.add("." + field + " = " + field + "_boxed");
    }
    sb.append("return rt.obj_k(").append(capturesName).append(", &").append(vtableRef(objId)).append(", .{ ")
      .append(String.join(", ", boxedFields)).append(" });\n");
    sb.append("}");
    currentState().functions.add(sb.toString());
  }

  public void visitFun(MIR.Fun fun) {
    var name = id.getFName(fun.name());
    var paramNames = fun.args().stream()
      .map(x -> id.varName(x.name()))
      .toList();
    var params = fun.args().stream()
      .map(x -> id.varName(x.name()) + ": rt.FatPtr")
      .collect(Collectors.joining(", "));

    var vpfCodegen = new VPFCodegen(this);
    var vpfInfo = vpfEnabled ? vpfCodegen.findVPFCall(fun.body()) : null;
    // The top-level locals struct holds N params and r1, each one a 16-byte FatPtr
    int topLevelLocalsSize = (fun.args().size() + 1) * 16;
    if (vpfInfo != null && topLevelLocalsSize <= VPFCodegen.LOCALS_COPY_LIMIT) {
      vpfCodegen.emitVPFFun(fun, name, paramNames, params, vpfInfo);
      // The variant is the plain sequential shape of the body, so it exists beside the
      // instrumented form: a slot-filling call site takes the sequential path.
      emitTransientVariant(fun, name, paramNames, params);
      return;
    }

    var statements = new StringBuilder();
    if (fun.body() instanceof MIR.Box outer) {
      statements.append(tailStatements(outer.inner(), paramNames, true));
    } else {
      var body = returnExpr(fun.body(), true);
      for (var paramName : paramNames) {
        statements.append("defer ").append(paramName).append(".rc_decrement();\n");
      }
      statements.append(body.equals("unreachable") ? "unreachable;\n" : "return " + body + ";\n");
    }

    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("(").append(params).append(") callconv(.c) rt.FatPtr {\n");
    // Discard each param, to prevent an unused-parameter error
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }
    sb.append("heartbeat.tryPromote();\n");
    sb.append(statements);
    sb.append("}");
    currentState().functions.add(sb.toString());

    emitTransientVariant(fun, name, paramNames, params);
  }

  /// The callee-fills-caller-slot variant: `<name>_transient(fear_out, params...)`. The caller
  /// owns the slot behind `fear_out` and drops it in its own frame, so this is the one kind of
  /// generated function whose return value is a transient. It must stay unreachable from any
  /// vtable entry and from every plain `MF_` wrapper: only a slot-owning call site and the
  /// `_transient` wrapper may reference it.
  private void emitTransientVariant(MIR.Fun fun, String name, List<String> paramNames, String params) {
    if (!hasTransientVariant(fun.name())) { return; }
    var summaryObj = shapes.freshObj(fun.name()).orElseThrow();
    emitCreateObj(summaryObj, true);
    var caps = capturesRef(summaryObj.concreteT().id());

    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("_transient(fear_out: *rt.GenObjectLayoutType(")
      .append(caps).append(")").append(params.isEmpty() ? "" : ", " + params)
      .append(") callconv(.c) rt.FatPtr {\n");
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ").append(String.join(", ", paramNames)).append(" };\n");
    }
    sb.append("heartbeat.tryPromote();\n");

    var body = ReturnShapeAnalysis.unwrap(fun.body());
    switch (body) {
      // The params arrive owned. `init_transient_obj` shares each capture, the param drops
      // consume the passed-in refs, so the slot holds exactly one ref per capture.
      case MIR.CreateObj k -> {
        var captures = k.captures().stream()
          .map(x -> "." + id.varName(x.name()) + " = " + visitX(x, true))
          .collect(Collectors.joining(", "));
        sb.append("const fear_slot = rt.init_transient_obj(").append(caps).append(", fear_out, &")
          .append(vtableRef(k.concreteT().id(), true)).append(", .{ ").append(captures).append(" });\n");
        appendDrops(sb, paramNames);
        sb.append("return fear_slot;\n");
      }
      case MIR.DirectCall d -> {
        var original = d.original();
        var ops = callOperands(original, this, true);
        var target = methWrapperRef(d.concreteType(), id.getMName(original.mdf(), original.name())) + "_transient";
        appendTailForward(sb, ops, target, paramNames);
      }
      case MIR.StaticCall s -> {
        var prelude = new ArrayList<String>();
        var args = staticCallArgs(s, this, true, prelude, false);
        var ops = new CallOperands(null, args, prelude);
        appendTailForward(sb, ops, funRef(s.fun()) + "_transient", paramNames);
      }
      default -> throw Bug.unreachable();
    }
    sb.append("}");
    currentState().functions.add(sb.toString());
  }

  /// The tail-forward form of a `_transient` variant: bind the operands, drop the params, then
  /// return the callee-variant call with the slot passed through. `hasTransientVariant` only
  /// accepts a body whose operands need no prelude, so the bound operands are plain expressions.
  private void appendTailForward(StringBuilder sb, CallOperands ops, String target, List<String> paramNames) {
    if (!ops.prelude().isEmpty()) { throw Bug.unreachable(); }
    var refs = new ArrayList<String>();
    var operands = new ArrayList<String>();
    if (ops.recv() != null) { operands.add(ops.recv()); }
    operands.addAll(ops.args());
    for (var op : operands) {
      var tmp = "fear_op_" + blockCounter++;
      sb.append("const ").append(tmp).append(" = ").append(op).append(";\n");
      refs.add(tmp);
    }
    appendDrops(sb, paramNames);
    sb.append("return ").append(target).append("(fear_out");
    for (var ref : refs) { sb.append(", ").append(ref); }
    sb.append(");\n");
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

    return emitMCall(call, this, checkMagic);
  }

  /// The intrinsic module of a receiver whose every runtime value is a `.primitive`, so that a
  /// call on it can reach the comptime-resolved intrinsic `dispatch` and skip the storage-mode
  /// switch and the inline cache of `rt.call`. The key is a subtype test on the *declared* type,
  /// which is the same direction the rest of the magic machinery uses: a declared `base.Nat`, or a
  /// Nat literal, only ever holds a Nat, whereas a supertype of Nat is not in the map.
  private static final Map<DecId, String> PRIMITIVE_INTRINSICS = Map.of(
    Magic.Nat, "nat_rt",
    Magic.Int, "int_rt",
    Magic.Float, "float_rt",
    Magic.Byte, "byte_rt");

  private Optional<String> primitiveIntrinsic(MIR.E recv) {
    return PRIMITIVE_INTRINSICS.entrySet().stream()
      .filter(e -> magic.isMagic(e.getKey(), recv))
      .map(Map.Entry::getValue)
      .findFirst();
  }

  /// The receiver and the args of a call, each owned, with the prelude that any transient among
  /// them needs.
  private record CallOperands(String recv, List<String> args, List<String> prelude) {}

  /// One owned operand. A syntactic transient `CreateObj` materialises into a stack slot. With
  /// `slotEligible`, a `DirectCall`/`StaticCall` whose callee has a `_transient` variant also
  /// fills a caller stack slot instead of a heap object.
  private String operand(MIR.E e, MIRVisitor<String> gen, boolean checkMagic,
                         List<String> prelude, boolean slotEligible) {
    if (isTransientCreateObj(e)) {
      var materialised = materialiseTransient((MIR.CreateObj) e, gen, checkMagic);
      prelude.addAll(materialised.prelude());
      return materialised.ref();
    }
    if (slotEligible) {
      var slotted = materialiseSlotCall(e, gen, checkMagic);
      if (slotted.isPresent()) {
        prelude.addAll(slotted.get().prelude());
        return slotted.get().ref();
      }
    }
    return ownedExpr(e, gen, checkMagic);
  }

  /// The tail-position form: no result slots. A slot needs a prelude, and a prelude keeps the
  /// call out of tail position, which costs more on a self-recursive call than the slot saves.
  private CallOperands callOperands(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var prelude = new ArrayList<String>();
    var recv = operand(call.recv(), gen, checkMagic, prelude, false);
    var args = call.args().stream()
      .map(a -> operand(a, gen, checkMagic, prelude, false))
      .toList();
    return new CallOperands(recv, args, prelude);
  }

  /// The non-tail form: result slots are allowed. The receiver position needs no escape
  /// analysis, because a callee that stores its receiver boxes it first. An argument position
  /// gets a slot only when the callee is known and its summary shows the position does not
  /// escape.
  private CallOperands slottedCallOperands(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic,
                                           Optional<MIR.Fun> knownCallee) {
    var prelude = new ArrayList<String>();
    var recv = operand(call.recv(), gen, checkMagic, prelude, true);
    var args = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      var slotOk = knownCallee.isPresent()
        && knownCallee.get().args().size() > call.args().size()
        && !shapes.paramMayEscape(knownCallee.get().name(), i);
      args.add(operand(call.args().get(i), gen, checkMagic, prelude, slotOk));
    }
    return new CallOperands(recv, args, prelude);
  }

  /// The args of a `StaticCall`, positional to the callee fun. With `allowSlots`, an arg whose
  /// position the callee summary shows as non-escaping may fill a caller stack slot.
  private List<String> staticCallArgs(MIR.StaticCall call, MIRVisitor<String> gen, boolean checkMagic,
                                      List<String> prelude, boolean allowSlots) {
    var callee = funMap.get(call.fun());
    var args = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      var slotOk = allowSlots && callee != null && callee.args().size() == call.args().size()
        && !shapes.paramMayEscape(call.fun(), i);
      args.add(operand(call.args().get(i), gen, checkMagic, prelude, slotOk));
    }
    return args;
  }

  String emitMCall(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var ops = slottedCallOperands(call, gen, checkMagic, Optional.empty());
    var sig = new MIR.Sig(call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet());
    var hashExpr = sigBuilder.inlineHash(sig);

    var argsTuple = ops.args().isEmpty() ? ".{}" : ".{ " + String.join(", ", ops.args()) + " }";
    var intrinsic = checkMagic ? primitiveIntrinsic(call.recv()) : Optional.<String>empty();
    var target = intrinsic
      .map(module -> "rt.dispatch_primitive(" + module + ", " + hashExpr + ", " + ops.recv() + ", " + argsTuple + ")")
      .orElseGet(() -> "rt.call(" + ops.recv() + ", " + hashExpr + ", " + argsTuple + ", @src())");
    return withTransientPrelude(ops.prelude(), target);
  }

  /// A devirtualised call: the per-literal wrapper of the one concrete receiver type, called
  /// directly. The wrapper reads its captures out of the receiver, so the operands are exactly
  /// those of the virtual form.
  String emitDirectCall(MIR.DirectCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var original = call.original();
    var ops = slottedCallOperands(original, gen, checkMagic, shapes.calleeOf(call));
    var methName = id.getMName(original.mdf(), original.name());
    var all = new ArrayList<String>();
    all.add(ops.recv());
    all.addAll(ops.args());
    return withTransientPrelude(ops.prelude(),
      methWrapperRef(call.concreteType(), methName) + "(" + String.join(", ", all) + ")");
  }

  /// No magic check here: the pass that makes a `DirectCall` only does so for a receiver with no
  /// magic implementation, so the wrapper is always the code that runs.
  @Override
  public String visitDirectCall(MIR.DirectCall call, boolean checkMagic) {
    return emitDirectCall(call, this, checkMagic);
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
      // The MIR has no such type, so it becomes a singleton with an empty vtable
      emitCreateObj(createObj, checkMagic);
      return "rt.obj_k_singleton(&" + vtableRef(objId) + ")";
    }
    var singleton = typeDef.singletonInstance().isPresent();

    // Emit the struct, the vtable and the methods of this type, if that did not occur before
    emitCreateObj(createObj, checkMagic);

    if (singleton) {
      return "rt.obj_k_singleton(&" + vtableRef(objId) + ")";
    }

    if (createObj.captures().isEmpty()) {
      return "rt.obj_k_singleton(&" + vtableRef(objId) + ")";
    }

    var captures = createObj.captures().stream()
      .map(x -> "." + id.varName(x.name()) + " = " + visitX(x, checkMagic))
      .collect(Collectors.joining(", "));
    return "rt.obj_k(" + capturesRef(objId) + ", &" + vtableRef(objId) + ", .{ " + captures + " })";
  }

  @Override
  public String visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    return boolExpr(expr, this, checkMagic, false);
  }

  @Override
  public String visitBox(MIR.Box box, boolean checkMagic) {
    return boxExpr(box.inner(), this, checkMagic);
  }

  private String inlineBlock(MIR.Block block) {
    return inlineBlock(block, this, false);
  }

  private String inlineBlock(MIR.Block block, MIRVisitor<String> gen, boolean ownedBranches) {
    return ownedBranches ? ownedExpr(block.original(), gen, true) : block.original().accept(gen, true);
  }

  @Override
  public String visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
    var fRef = funRef(call.fun());
    var prelude = new ArrayList<String>();
    var args = staticCallArgs(call, this, checkMagic, prelude, true);
    return withTransientPrelude(prelude, fRef + "(" + String.join(", ", args) + ")");
  }


  // No caller: the output collects in the package state
  public String visitProgram(DecId entry) { throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg) { throw Bug.unreachable(); }
}
