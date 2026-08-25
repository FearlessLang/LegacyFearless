package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import codegen.optimisations.RapidTypeAnalysis;
import codegen.optimisations.RcFreeTypes;
import codegen.optimisations.ReturnShapeAnalysis;
import id.Id;
import id.Id.DecId;
import magic.Magic;
import main.CompilationUnit;
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

    // Recorded at CreateObj emission: the box hook needs the exact set, in order, and a
    // second derivation from the AST is not reliable.
    final LinkedHashMap<DecId, SortedSet<MIR.X>> captureLists = new LinkedHashMap<>();

    PackageState(String packageName) { this.packageName = packageName; }
  }

  public final Map<String, PackageState> packageStates = new LinkedHashMap<>();
  public final LinkedHashMap<DecId, Boolean> emittedTypes = new LinkedHashMap<>();

  final Map<DecId, String> typeToPackage = new HashMap<>();

  private String emitTargetPkg;
  private String pkg;

  private final boolean vpfEnabled;
  /// Only for {@link VPFCodegen#containsVPFCall}, which keeps no scope. A function's
  /// instrumentation state lives on the throwaway instance {@link #visitFun} makes.
  private final VPFCodegen vpf;
  final Map<MIR.FName, Boolean> vpfBranchCache = new HashMap<>();
  final ReturnShapeAnalysis shapes;
  private final RcFreeTypes rcFree;
  private final Map<MIR.FName, Boolean> transientVariantCache = new HashMap<>();

  public ZigSingleCodegen(MIR.Program p, boolean vpfEnabled, RapidTypeAnalysis rta, Set<String> cachedPkg) {
    this.vpfEnabled = vpfEnabled;
    this.rcFree = new RcFreeTypes(rta, (ast.Program) p.p(), cachedPkg);
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

  /// Takes the root.pkg_ prefix when it points at a different package.
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

  /// Takes the root.pkg_ prefix when it points at a different package.
  public String capturesRef(DecId objId) {
    var typeName = id.getSimpleName(objId);
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + typeName + "_Captures";
    }
    return typeName + "_Captures";
  }

  /// Takes the root.pkg_ prefix when it points at a different package.
  public String funRef(MIR.FName fName) {
    var zigName = id.getFName(fName);
    var owningPkg = typeToPackage.get(fName.d());
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + zigName;
    }
    return zigName;
  }

  /// The per-literal method wrapper of `objId`, with the root.pkg_ prefix when it points at a
  /// different package. It takes `(receiver, args...)`, so a caller needs no captures.
  public String methWrapperRef(DecId objId, String methName) {
    var name = "MF_" + id.getSimpleName(objId) + "_" + methName;
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + name;
    }
    return name;
  }

  /// The package that holds `objId`, or null when none declares it. An anonymous literal
  /// takes the package it is emitted into.
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

  /// True when the static type proves that reference counting the value is a no-op, so
  /// codegen omits the operation rather than emitting a runtime storage-mode test.
  ///
  /// A package a {@link main.CompilationUnit} holds gets the answer a later compilation cannot
  /// take away, because its generated text is read as it stands by compilations holding
  /// packages this one never saw. Any other package is generated again by every program that
  /// names it, so the answer of this compilation is the whole answer.
  boolean isRcFree(MIR.E e) {
    if (emitTargetPkg == null || CompilationUnit.isCached(emitTargetPkg)) {
      return rcFree.isRcFreeForever(e);
    }
    return rcFree.isRcFree(e);
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
  /// is one the variant emitter covers. A VPF-instrumented function keeps its variant, which
  /// is the plain sequential form of the body emitted beside the instrumented one.
  ///
  /// The variant emitters and every call site consult this one predicate, so a reference to a
  /// `_transient` symbol and its definition cannot go out of sync.
  boolean hasTransientVariant(MIR.FName fName) {
    var cached = transientVariantCache.get(fName);
    if (cached != null) { return cached; }
    // A cycle of tail forwards never bottoms out in a fresh literal.
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

  /// A tail forward binds its operands to temporaries and returns the callee-variant call, so
  /// no operand may need a prelude: a prelude holds a transient whose drop must outlive the
  /// call.
  private boolean operandsSlotFree(MIR.E recv, List<? extends MIR.E> args) {
    if (recv != null && (isTransientCreateObj(recv) || receiverNeedsOwner(recv))) { return false; }
    return args.stream().noneMatch(this::isTransientCreateObj);
  }

  /// The `drop` a slot call needs, as the statement that runs when the value dies.
  ///
  /// A guarded call fills the slot on the arm the test takes and returns an owned value on the
  /// other, so which drop is right is only known at run time and the test result decides.
  private record SlotPieces(String slotVar, String caps, List<String> operandPrelude, String call,
                            String drop, String extraDecl) {}

  /// The pieces of a callee-fills-caller-slot call for `e`, when `e` is a `DirectCall` or
  /// `StaticCall` whose callee has a `_transient` variant: the stack-slot variable, its
  /// Captures type, the prelude its operands need, and the variant call that fills the slot.
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
    } else if (e instanceof MIR.GuardedCall g) {
      var callee = shapes.calleeOf(g).filter(f -> hasTransientVariant(f.name()));
      if (System.getenv("FEART_DBG_SLOT") != null) {
        System.err.println("[gslot] " + g.concreteType() + " " + g.original().name()
          + " callee=" + shapes.calleeOf(g).map(f -> f.name().toString()).orElse("none")
          + " transient=" + callee.isPresent());
      }
      if (callee.isEmpty()) { return Optional.empty(); }
      summaryObj = shapes.freshObj(callee.get().name()).orElseThrow();
      var original = g.original();
      // The generic operand forms, not the ones the tested type would allow: the other arm
      // calls a method this call site does not know, which can keep an argument past the call.
      var ops = slottedCallOperands(original, gen, checkMagic, Optional.empty());
      operandPrelude.addAll(ops.prelude());
      var recvName = "fear_guard_" + blockCounter++;
      operandPrelude.add("const " + recvName + " = " + ops.recv() + ";");
      var argNames = new ArrayList<String>();
      for (var arg : ops.args()) {
        var argName = "fear_guard_" + blockCounter++;
        operandPrelude.add("const " + argName + " = " + arg + ";");
        argNames.add(argName);
      }
      var takenName = "fear_guard_" + blockCounter++;
      operandPrelude.add(takenName + " = " + guardTest(recvName, g.concreteType()) + ";");
      var sig = new MIR.Sig(original.name(),
        original.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
        original.originalRet());
      var slotVarName = "fear_slot_" + transientCounter++;
      var hot = methWrapperRef(g.concreteType(), id.getMName(original.mdf(), original.name()))
        + "_transient(&" + slotVarName + "_obj, " + recvName
        + (argNames.isEmpty() ? "" : ", " + String.join(", ", argNames)) + ")";
      var argsTuple = argNames.isEmpty() ? ".{}" : ".{ " + String.join(", ", argNames) + " }";
      var ownedName = slotVarName + "_owned";
      var coldBlock = "fear_blk_" + blockCounter++;
      // The other arm returns a value this frame owns, and the drop can sit in a wider scope
      // than the call, so the value is kept in a name declared beside the slot.
      var cold = coldBlock + ": { const " + coldBlock + "_v = rt.call(" + recvName + ", "
        + sigBuilder.inlineHash(sig) + ", " + argsTuple + ", @src()); " + ownedName + " = "
        + coldBlock + "_v; break :" + coldBlock + " " + coldBlock + "_v; }";
      emitCreateObj(summaryObj, true);
      var guardedCaps = capturesRef(summaryObj.concreteT().id());
      return Optional.of(new SlotPieces(slotVarName, guardedCaps, operandPrelude,
        "if (" + takenName + ") " + hot + " else " + cold,
        "if (" + takenName + ") rt.drop_transient_obj(" + guardedCaps + ", &" + slotVarName
          + "_obj) else " + ownedName + ".rc_decrement()",
        // The drop can sit in a wider scope than the operands, so the test result is declared
        // beside the slot rather than where it is worked out.
        "var " + takenName + ": bool = false;\nvar " + ownedName + ": rt.FatPtr = undefined;\n"));
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
    return Optional.of(new SlotPieces(slotVar, caps, operandPrelude, call,
      "rt.drop_transient_obj(" + caps + ", &" + slotVar + "_obj)", ""));
  }

  /// `e` computed through a caller-provided stack slot. The slot's drop registers after every
  /// operand transient's, so it runs first and the captures it decrements are still alive.
  Optional<Materialised> materialiseSlotCall(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
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

  record VPFResultSlot(String decl, String dropDefer, String operandStatements, String call) {}

  /// The slot form of the first frame-adding sub-expr of a VPF fun, or empty. The declaration
  /// goes before the compiler fence and the drop-defer at function scope, so the slot outlives
  /// the combiner and the wait on a stolen frame.
  Optional<VPFResultSlot> vpfResultSlot(MIR.E e) {
    return slotPieces(e, this, true).map(sp -> new VPFResultSlot(
      "var " + sp.slotVar() + "_obj: rt.GenObjectLayoutType(" + sp.caps() + ") = undefined;\n" + sp.extraDecl(),
      "defer " + sp.drop() + ";\n",
      sp.operandPrelude().isEmpty() ? "" : String.join("\n", sp.operandPrelude()) + "\n",
      sp.call()));
  }

  String ownedExpr(MIR.E e, boolean checkMagic) {
    return ownedExpr(e, this, checkMagic);
  }

  String ownedExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    if (e instanceof MIR.X) {
      var code = e.accept(gen, checkMagic);
      return isRcFree(e) ? code : code + ".share()";
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, gen, checkMagic, true);
    }
    return e.accept(gen, checkMagic);
  }

  String returnExpr(MIR.E e, boolean checkMagic) {
    if (e instanceof MIR.X x) {
      var code = visitX(x, checkMagic);
      return isRcFree(x) ? code : code + ".share()";
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, this, checkMagic, true);
    }
    return e.accept(this, checkMagic);
  }

  /// Wraps `e` so a transient cannot leave the frame that holds its storage.
  ///
  /// A call result needs no wrapping: a generated function boxes its own return, and a runtime
  /// intrinsic never builds a transient, a transient being a stack slot only generated code
  /// creates. So the boxing hook would be dead code, and the storage-mode load it tests would
  /// sit between the call and the return, keeping the call out of tail position.
  String boxExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    return switch (e) {
      case MIR.Box box -> boxExpr(box.inner(), gen, checkMagic);
      case MIR.X x -> isRcFree(x) ? x.accept(gen, checkMagic) : boxBorrowedCode(x.accept(gen, checkMagic));
      case MIR.CreateObj createObj -> boxCreateObj(createObj, gen, checkMagic);
      case MIR.BoolExpr boolExpr -> boxBoolExpr(boolExpr, gen, checkMagic);
      case MIR.MCall ignored -> e.accept(gen, checkMagic);
      case MIR.DirectCall ignored -> e.accept(gen, checkMagic);
      case MIR.GuardedCall ignored -> e.accept(gen, checkMagic);
      case MIR.StaticCall ignored -> e.accept(gen, checkMagic);
      case MIR.UpdatableListAsIdFnCall ignored -> boxOwnedCode(e.accept(gen, checkMagic));
      case MIR.Block ignored -> boxOwnedCode(e.accept(gen, checkMagic));
    };
  }

  /// The body as statements, with each param's drop before the tail-position call instead of
  /// after it.
  ///
  /// A `defer param.rc_decrement()` runs once the return value is known, so the call that
  /// produced it is not the last thing the frame does and the backend must keep the frame
  /// alive across it. Fearless writes a loop as a self-recursive call, so a loop of N steps
  /// holds N frames and a long one walks its stack out of L1. Once the operands are in
  /// temporaries the params are dead, which is where the drops belong: the call becomes a tail
  /// call, and a self-recursive one becomes a loop.
  ///
  /// Every path emitted ends in a `return` or `unreachable`, and drops each param once.
  private String tailStatements(MIR.E e, List<String> dropNames, boolean checkMagic) {
    return switch (e) {
      case MIR.Box box -> tailStatements(box.inner(), dropNames, checkMagic);
      case MIR.BoolExpr boolExpr -> {
        var cond = boolExpr.condition().accept(this, checkMagic);
        yield "if (" + cond + ".vt == &" + vtableRef(new DecId("base.True", 0)) + ") {\n"
          + armTailStatements(boolExpr.then(), dropNames, checkMagic)
          + "}\n"
          + armTailStatements(boolExpr.else_(), dropNames, checkMagic);
      }
      case MIR.DirectCall call when operandWantsSlot(call.original(), shapes.calleeOf(call))
          || dropsBorrowedRecv(call.original(), dropNames) ->
        valueTailStatements(boxExpr(e, this, checkMagic), dropNames);
      case MIR.DirectCall call -> {
        var ops = callOperands(call.original(), this, checkMagic);
        var methName = id.getMName(call.original().mdf(), call.original().name());
        var target = methWrapperRef(call.concreteType(), methName);
        yield tailCallStatements(ops, refs -> target + "(" + String.join(", ", refs) + ")", dropNames)
          .orElseGet(() -> valueTailStatements(boxExpr(e, this, checkMagic), dropNames));
      }
      case MIR.MCall call when operandWantsSlot(call, Optional.empty())
          || dropsBorrowedRecv(call, dropNames) ->
        valueTailStatements(boxExpr(e, this, checkMagic), dropNames);
      case MIR.MCall call -> {
        var ops = callOperands(call, this, checkMagic);
        yield tailCallStatements(ops, refs -> mCallOn(call, refs, checkMagic), dropNames)
          .orElseGet(() -> valueTailStatements(boxExpr(e, this, checkMagic), dropNames));
      }
      default -> valueTailStatements(boxExpr(e, this, checkMagic), dropNames);
    };
  }

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
  /// generic form: its operands are already inside the emitted call, so there is nothing left
  /// to hoist the drops above.
  private String armTailStatements(MIR.FName arm, List<String> dropNames, boolean checkMagic) {
    var deInlined = deInlinedBranch(arm, this, checkMagic);
    if (deInlined.isPresent()) { return valueTailStatements(deInlined.get(), dropNames); }
    var body = funMap.get(arm).body();
    return tailStatements(body instanceof MIR.Block block ? block.original() : body, dropNames, checkMagic);
  }

  /// `target(operands...)` in tail position, or empty when the operands need a prelude. A
  /// prelude holds a transient stored in this frame whose drop must outlive the call, which
  /// puts the call out of tail position.
  private Optional<String> tailCallStatements(CallOperands ops, Function<List<String>, String> build,
                                              List<String> dropNames) {
    if (!ops.prelude().isEmpty()) { return Optional.empty(); }
    var sb = new StringBuilder();
    var refs = new ArrayList<String>();
    for (var operand : Stream.concat(Stream.of(ops.recv()), ops.args().stream()).toList()) {
      var tmp = "fear_op_" + blockCounter++;
      sb.append("const ").append(tmp).append(" = ").append(operand).append(";\n");
      refs.add(tmp);
    }
    appendDrops(sb, dropNames);
    sb.append("return ").append(build.apply(refs)).append(";\n");
    return Optional.of(sb.toString());
  }

  private String valueTailStatements(String expr, List<String> dropNames) {
    if (expr.equals("unreachable")) { return "unreachable;\n"; }
    var sb = new StringBuilder();
    var tmp = "fear_ret_" + blockCounter++;
    sb.append("const ").append(tmp).append(" = ").append(expr).append(";\n");
    appendDrops(sb, dropNames);
    sb.append("return ").append(tmp).append(";\n");
    return sb.toString();
  }

  private void appendDrops(StringBuilder sb, List<String> dropNames) {
    for (var dropName : dropNames) {
      sb.append(dropName).append(".rc_decrement();\n");
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
      if (isRcFree(x)) {
        boxedFields.add("." + field + " = " + x.accept(gen, checkMagic));
        continue;
      }
      var tmp = field + "_boxed";
      prelude.add("const " + field + "_shared = " + x.accept(gen, checkMagic) + ".share();");
      prelude.add("const " + tmp + " = " + field + "_shared.box_transient();");
      prelude.add("defer " + tmp + ".rc_decrement();");
      boxedFields.add("." + field + " = " + tmp);
    }
    return withTransientPrelude(prelude,
      "rt.obj_k(" + capturesRef(objId) + ", &" + vtableRef(objId) + ", .{ " + String.join(", ", boxedFields) + " })");
  }

  /// A `.then` or `.else` arm whose subtree holds a VPF call becomes a call to the arm's
  /// function rather than an inlined body, because `visitFun` gives that function its own VPF
  /// instrumentation, which an inlined body generates but never calls. Nothing counts nesting
  /// depth: `visitFun` emits each de-inlined arm in turn and de-inlines again, until it reaches
  /// the level where `VPFCodegen#findVPFCall` finds and instruments the call.
  ///
  /// Empty when VPF is off, so a `--no-vpf` build keeps full inlining.
  Optional<String> deInlinedBranch(MIR.FName fName, MIRVisitor<String> gen, boolean checkMagic) {
    if (!vpfEnabled || !vpf.containsVPFCall(fName)) { return Optional.empty(); }
    var fun = funMap.get(fName);
    if (fun == null || fun.args().isEmpty()) { return Optional.empty(); }
    // An arm's args are [self, captures...]. `BoolIfOptimisation` makes a `BoolExpr` only when
    // no arm captures self, so nothing reads the self param and any singleton serves. The
    // captures are the same `MIR.X`s as in the enclosing scope, which is what makes the
    // inlining correct, so they emit correctly here.
    var args = new ArrayList<String>();
    args.add("rt.obj_k_singleton(&" + vtableRef(new DecId("base.True", 0)) + ")");
    // Lent, not given, the same as every other capture: the arm reads the enclosing scope's
    // own names, which stay alive across the call, and the arm does not drop them.
    fun.args().stream().skip(1).forEach(x -> args.add(x.accept(gen, checkMagic)));
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

    return ""; // The output collects in the package state.
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
        + fields + "\n" + rcFreeFieldsDecl(createObj.captures()) + "};");
      currentState().captureLists.put(objId, createObj.captures());
    }

    for (var meth : createObj.meths()) {
      emitMeth(meth, objId, false, leastSpecific);
    }
    for (var meth : createObj.unreachableMs()) {
      emitMeth(meth, objId, true, leastSpecific);
    }

    // The inherited TypeDef methods the CreateObj does not hold.
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
            simpleArgs.add("self_m"); // No captures, so self is a placeholder.
          } else {
            simpleArgs.add(
              "rt.deref(" + capturesRef(objId) + ", self_m)." + id.varName(capture));
          }
        }

        var tracePush = "shadow_stack_mod.tracePush(&" + vtableRef(objId) + ", " + sigBuilder.inlineHash(sig) + ");\n"
          + "defer shadow_stack_mod.tracePop();\n";

        currentState().functions.add("pub fn " + mfName + "(" + paramStr + ") callconv(.c) rt.FatPtr {\n"
          + paramDiscard
          + tracePush
          + "return " + fRef + "(" + String.join(", ", simpleArgs) + ");\n"
          + "}");

        // The `_transient` wrapper: the same capture plumbing, with the caller slot passed
        // through.
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

  /// The `rc_free_fields` declaration of a capture struct, naming the captures the runtime
  /// may retain and release as plain bytes. Empty when every capture is counted, so the
  /// runtime's default of counting each `FatPtr` field stands.
  private String rcFreeFieldsDecl(Collection<MIR.X> captures) {
    var free = captures.stream()
      .filter(this::isRcFree)
      .map(x -> "\"" + id.varName(x.name()) + "\"")
      .collect(Collectors.joining(", "));
    if (free.isEmpty()) { return ""; }
    return "pub const rc_free_fields = [_][]const u8{ " + free + " };\n";
  }

  private boolean isSingletonType(DecId objId) {
    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst().orElse(null);
    if (typeDef != null && typeDef.singletonInstance().isPresent()) return true;
    return !createObjHasCaptures(objId);
  }

  /// The test a guarded call makes on its receiver.
  ///
  /// A type with a transient form has two vtables, one for a value on the heap and one for a
  /// value in a caller slot, so both addresses answer for the same type and the test names
  /// both. Which vtables exist follows the same rule {@link #emitVTable} uses.
  String guardTest(String recvName, DecId target) {
    var plain = recvName + ".vt == &" + vtableRef(target);
    if (!isTransientEligibleType(target) || !createObjHasCaptures(target)) { return plain; }
    return "(" + plain + " or " + recvName + ".vt == &" + vtableRef(target, true) + ")";
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
    // A param whose static type carries no reference count needs no drop, so it never
    // reaches the drop list and no storage-mode test is emitted for it. The receiver is
    // out of the list as well, because a caller lends it rather than gives it away. See
    // `receiverOperand`.
    // Everything from the receiver onwards is lent: the receiver by its caller, and each
    // capture by the receiver that holds it. Captures are final, so a lent capture cannot be
    // replaced while the call runs. Only the declared params are owned, and only they drop.
    var selfIdx = selfArgIndex(fun);
    var dropNames = java.util.stream.IntStream.range(0, fun.args().size())
      .filter(i -> i < selfIdx)
      .mapToObj(fun.args()::get)
      .filter(x -> !isRcFree(x))
      .map(x -> id.varName(x.name()))
      .toList();
    var params = fun.args().stream()
      .map(x -> id.varName(x.name()) + ": rt.FatPtr")
      .collect(Collectors.joining(", "));

    var vpfCodegen = new VPFCodegen(this);
    var vpfInfo = vpfEnabled ? vpfCodegen.findVPFCall(fun.body()) : null;
    int topLevelLocalsSize = (fun.args().size() + 1) * 16;
    if (vpfInfo != null && topLevelLocalsSize <= VPFCodegen.LOCALS_COPY_LIMIT) {
      vpfCodegen.emitVPFFun(fun, name, paramNames, dropNames, params, vpfInfo);
      // The variant is the plain sequential shape of the body and sits beside the
      // instrumented form, so a slot-filling call site takes the sequential path.
      emitTransientVariant(fun, name, paramNames, dropNames, params);
      return;
    }

    var statements = new StringBuilder();
    if (fun.body() instanceof MIR.Box outer) {
      statements.append(tailStatements(outer.inner(), dropNames, true));
    } else {
      var body = returnExpr(fun.body(), true);
      for (var paramName : dropNames) {
        statements.append("defer ").append(paramName).append(".rc_decrement();\n");
      }
      statements.append(body.equals("unreachable") ? "unreachable;\n" : "return " + body + ";\n");
    }

    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("(").append(params).append(") callconv(.c) rt.FatPtr {\n");
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }
    sb.append("heartbeat.tryPromote();\n");
    sb.append(statements);
    sb.append("}");
    currentState().functions.add(sb.toString());

    emitTransientVariant(fun, name, paramNames, dropNames, params);
  }

  /// The callee-fills-caller-slot variant, `<name>_transient(fear_out, params...)`. The caller
  /// owns the slot behind `fear_out` and drops it in its own frame, which makes this the one
  /// generated function whose return value is a transient. It must stay unreachable from every
  /// vtable entry and plain `MF_` wrapper: only a slot-owning call site and the `_transient`
  /// wrapper may reference it.
  private void emitTransientVariant(MIR.Fun fun, String name, List<String> paramNames,
                                    List<String> dropNames, String params) {
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
      // The params arrive owned. `init_transient_obj` shares each capture and the param drops
      // consume the passed-in refs, so the slot holds one ref per capture.
      case MIR.CreateObj k -> {
        var captures = k.captures().stream()
          .map(x -> "." + id.varName(x.name()) + " = " + visitX(x, true))
          .collect(Collectors.joining(", "));
        sb.append("const fear_slot = rt.init_transient_obj(").append(caps).append(", fear_out, &")
          .append(vtableRef(k.concreteT().id(), true)).append(", .{ ").append(captures).append(" });\n");
        appendDrops(sb, dropNames);
        sb.append("return fear_slot;\n");
      }
      case MIR.DirectCall d -> {
        var original = d.original();
        var ops = callOperands(original, this, true);
        var target = methWrapperRef(d.concreteType(), id.getMName(original.mdf(), original.name())) + "_transient";
        appendTailForward(sb, ops, target, dropNames, !dropsBorrowedRecv(original, dropNames));
      }
      case MIR.StaticCall s -> {
        var prelude = new ArrayList<String>();
        var args = staticCallArgs(s, this, true, prelude, false);
        var ops = new CallOperands(null, args, prelude);
        appendTailForward(sb, ops, funRef(s.fun()) + "_transient", dropNames,
          !staticCallLendsDroppedName(s, dropNames));
      }
      default -> throw Bug.unreachable();
    }
    sb.append("}");
    currentState().functions.add(sb.toString());
  }

  /// The tail-forward form of a `_transient` variant: bind the operands, drop the params, then
  /// return the callee-variant call with the slot passed through. `hasTransientVariant` accepts
  /// only a body whose operands need no prelude, so those operands are plain expressions.
  private void appendTailForward(StringBuilder sb, CallOperands ops, String target,
                                 List<String> dropNames, boolean hoistDrops) {
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
    var call = new StringBuilder(target).append("(fear_out");
    for (var ref : refs) { call.append(", ").append(ref); }
    call.append(")");
    if (hoistDrops) {
      appendDrops(sb, dropNames);
      sb.append("return ").append(call).append(";\n");
      return;
    }
    var res = "fear_fwd_" + blockCounter++;
    sb.append("const ").append(res).append(" = ").append(call).append(";\n");
    appendDrops(sb, dropNames);
    sb.append("return ").append(res).append(";\n");
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

  /// The intrinsic module of a receiver whose every runtime value is a `.primitive`, so a call
  /// on it reaches the comptime-resolved intrinsic `dispatch` and skips the storage-mode switch
  /// and inline cache of `rt.call`. The key is a subtype test on the declared type, the same
  /// direction the rest of the magic machinery uses: a declared `base.Nat`, or a Nat literal,
  /// only ever holds a Nat, whereas a supertype of Nat is not in the map.
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

  private record CallOperands(String recv, List<String> args, List<String> prelude) {}

  /// The index of the receiver in a generated function's argument list.
  ///
  /// `MIRInjectionVisitor` builds that list as the declared params, then the receiver, then
  /// the captures, so the receiver sits at the method's arity.
  private int selfArgIndex(MIR.Fun fun) { return fun.name().m().num(); }

  /// The receiver of a call, which the callee borrows instead of owning.
  ///
  /// A receiver is alive for the whole call by construction: the caller has to hold it to
  /// make the call at all. So the retain and release pair that a shared receiver costs is
  /// dead, and both halves go: this emits no `share`, and the callee leaves the receiver out
  /// of its drop list.
  ///
  /// Borrowing stays safe because it never escapes. Every other position that can outlive
  /// the call already asks for an owned value: an argument and a capture go through
  /// `ownedExpr`, and a returned name goes through `returnExpr`. A borrowed receiver is
  /// therefore only ever borrowed again as the receiver of a nested call, which holds for
  /// the same reason one level up.
  ///
  /// A receiver the caller builds on the spot is the one case with no owner. The caller
  /// becomes that owner: the value binds to a temporary and drops when the enclosing block
  /// ends, which is after the call.
  private String receiverOperand(MIR.E e, MIRVisitor<String> gen, boolean checkMagic,
                                 List<String> prelude) {
    if (isTransientCreateObj(e)) {
      var materialised = materialiseTransient((MIR.CreateObj) e, gen, checkMagic);
      prelude.addAll(materialised.prelude());
      return materialised.ref();
    }
    if (e instanceof MIR.X || isRcFree(e)) { return e.accept(gen, checkMagic); }
    // A receiver the callee can build straight into a caller slot stays on the stack, which
    // beats owning a heap object for the length of the call.
    var slotted = materialiseSlotCall(e, gen, checkMagic);
    if (slotted.isPresent()) {
      prelude.addAll(slotted.get().prelude());
      return slotted.get().ref();
    }
    var tmp = "fear_recv_" + blockCounter++;
    prelude.add("const " + tmp + " = " + ownedExpr(e, gen, checkMagic) + ";");
    prelude.add("defer " + tmp + ".rc_decrement();");
    return tmp;
  }

  /// Whether the frame drops the very name it lends in a borrowed operand position.
  ///
  /// A tail forward binds the operands, drops the params and then calls, because the params
  /// are dead once the operands are bound. A borrowed operand breaks that: it is the frame's
  /// own reference rather than a share of it, so a drop that runs first frees the object the
  /// call is about to use. Such a call drops after the call returns instead.
  private boolean lendsDroppedName(MIR.E e, List<String> dropNames) {
    return e instanceof MIR.X x && dropNames.contains(id.varName(x.name()));
  }

  /// Whether the frame drops the very name it lends as the receiver.
  private boolean dropsBorrowedRecv(MIR.MCall call, List<String> dropNames) {
    return lendsDroppedName(call.recv(), dropNames);
  }

  /// Whether a `StaticCall` lends a dropped name. The receiver sits at the method's arity and
  /// the captures follow it, so every position from there on is lent.
  private boolean staticCallLendsDroppedName(MIR.StaticCall call, List<String> dropNames) {
    var selfIdx = call.fun().m().num();
    return java.util.stream.IntStream.range(0, call.args().size())
      .anyMatch(i -> i >= selfIdx && lendsDroppedName(call.args().get(i), dropNames));
  }

  /// Whether a receiver needs the caller to own it, which needs a prelude.
  private boolean receiverNeedsOwner(MIR.E e) {
    return !(e instanceof MIR.X) && !isRcFree(e) && !isTransientCreateObj(e);
  }

  /// One owned operand. A syntactic transient `CreateObj` materialises into a stack slot.
  /// With `slotEligible`, a `DirectCall`/`StaticCall` whose callee has a `_transient` variant
  /// also fills a caller stack slot instead of a heap object.
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

  /// True when an operand of `call` can fill a caller stack slot instead of a heap object.
  ///
  /// A slot needs a prelude and a prelude puts the call out of tail position, so the two are
  /// exclusive and the caller must pick one. The slot wins: it removes a heap allocation and
  /// its reference counting from every execution of the call, where the tail forward saves a
  /// stack frame only for a recursion deep enough to care. The positions match
  /// {@link #slottedCallOperands}, which does the emitting, so a call this admits is one that
  /// path really does slot.
  private boolean operandWantsSlot(MIR.MCall call, Optional<MIR.Fun> knownCallee) {
    if (receiverNeedsOwner(call.recv())) { return true; }
    if (canFillSlot(call.recv())) { return true; }
    for (int i = 0; i < call.args().size(); i++) {
      var slotOk = knownCallee.isPresent()
        && knownCallee.get().args().size() > call.args().size()
        && !shapes.paramMayEscape(knownCallee.get().name(), i);
      if (slotOk && canFillSlot(call.args().get(i))) { return true; }
    }
    return false;
  }

  /// Whether `e` names a fresh object a slot can hold: a transient literal, or a call whose
  /// callee has a `_transient` variant to fill one.
  private boolean canFillSlot(MIR.E e) {
    while (e instanceof MIR.Box box) { e = box.inner(); }
    if (isTransientCreateObj(e)) { return true; }
    return switch (e) {
      case MIR.DirectCall d -> shapes.calleeOf(d).map(f -> hasTransientVariant(f.name())).orElse(false);
      case MIR.GuardedCall g -> shapes.calleeOf(g).map(f -> hasTransientVariant(f.name())).orElse(false);
      case MIR.StaticCall s -> funMap.containsKey(s.fun()) && hasTransientVariant(s.fun());
      default -> false;
    };
  }

  /// Tail position, so no result slots: a slot needs a prelude, and a prelude keeps the call
  /// out of tail position, which costs a self-recursive call more than the slot saves.
  private CallOperands callOperands(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var prelude = new ArrayList<String>();
    var recv = receiverOperand(call.recv(), gen, checkMagic, prelude);
    var args = call.args().stream()
      .map(a -> operand(a, gen, checkMagic, prelude, false))
      .toList();
    return new CallOperands(recv, args, prelude);
  }

  /// Non-tail, so result slots are allowed. The receiver position needs no escape analysis,
  /// because a callee that stores its receiver boxes it first. An argument position gets a slot
  /// only when the callee is known and its summary shows the position does not escape.
  private CallOperands slottedCallOperands(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic,
                                           Optional<MIR.Fun> knownCallee) {
    var prelude = new ArrayList<String>();
    var recv = receiverOperand(call.recv(), gen, checkMagic, prelude);
    var args = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      var slotOk = knownCallee.isPresent()
        && knownCallee.get().args().size() > call.args().size()
        && !shapes.paramMayEscape(knownCallee.get().name(), i);
      args.add(operand(call.args().get(i), gen, checkMagic, prelude, slotOk));
    }
    return new CallOperands(recv, args, prelude);
  }

  /// The args of a `StaticCall`, positional to the callee fun. With `allowSlots`, an arg the
  /// callee summary shows as non-escaping may fill a caller stack slot.
  private List<String> staticCallArgs(MIR.StaticCall call, MIRVisitor<String> gen, boolean checkMagic,
                                      List<String> prelude, boolean allowSlots) {
    var callee = funMap.get(call.fun());
    var selfIdx = call.fun().m().num();
    var args = new ArrayList<String>();
    for (int i = 0; i < call.args().size(); i++) {
      // The receiver sits at the method's arity, and the captures follow it. The callee
      // borrows all of them, so each takes the same path a receiver takes in
      // `slottedCallOperands`.
      if (i >= selfIdx) {
        args.add(receiverOperand(call.args().get(i), gen, checkMagic, prelude));
        continue;
      }
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

  /// The per-literal wrapper of the one concrete receiver type, called directly. It reads its
  /// captures out of the receiver, so the operands are those of the virtual form.
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

  /// A guarded call: a vtable test, the wrapper of the guessed type, and the virtual call.
  ///
  /// Receiver and arguments bind to a name first. Both arms name them and only one arm runs, so
  /// a bare expression would be built twice, and an argument the callee owns would be built for
  /// a call that never happens.
  @Override
  public String visitGuardedCall(MIR.GuardedCall call, boolean checkMagic) {
    return emitGuardedCall(call, this, checkMagic);
  }

  /// A name no other emitted declaration in this file uses.
  String freshName(String prefix) { return prefix + blockCounter++; }

  String emitGuardedCall(MIR.GuardedCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var original = call.original();
    var ops = slottedCallOperands(original, gen, checkMagic, Optional.empty());
    var sig = new MIR.Sig(original.name(),
      original.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      original.originalRet());
    var hashExpr = sigBuilder.inlineHash(sig);

    var recvName = "fear_guard_" + blockCounter++;
    var block = "fear_blk_" + blockCounter++;
    var names = new ArrayList<String>();
    names.add(recvName);
    var sb = new StringBuilder(block + ": {\nconst " + recvName + " = " + ops.recv() + ";\n");
    for (var arg : ops.args()) {
      var argName = "fear_guard_" + blockCounter++;
      names.add(argName);
      sb.append("const ").append(argName).append(" = ").append(arg).append(";\n");
    }
    var argNames = names.subList(1, names.size());
    var argsTuple = argNames.isEmpty() ? ".{}" : ".{ " + String.join(", ", argNames) + " }";
    var methName = id.getMName(original.mdf(), original.name());
    sb.append("break :").append(block)
      .append(" if (").append(guardTest(recvName, call.concreteType())).append(") ")
      .append(methWrapperRef(call.concreteType(), methName)).append("(").append(String.join(", ", names)).append(")")
      .append(" else rt.call(").append(recvName).append(", ").append(hashExpr).append(", ")
      .append(argsTuple).append(", @src());\n}");
    return withTransientPrelude(ops.prelude(), sb.toString());
  }

  /// No magic check: the pass that makes a `DirectCall` does so only for a receiver with no
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
      // The MIR has no such type, so it becomes a singleton with an empty vtable.
      emitCreateObj(createObj, checkMagic);
      return "rt.obj_k_singleton(&" + vtableRef(objId) + ")";
    }
    var singleton = typeDef.singletonInstance().isPresent();

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


  public String visitProgram(DecId entry) { throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg) { throw Bug.unreachable(); }
}
