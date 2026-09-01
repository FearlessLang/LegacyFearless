package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import codegen.optimisations.StandInSelfArms;
import codegen.optimisations.RapidTypeAnalysis;
import codegen.optimisations.RecursionHotness;
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

    // Recorded at CreateObj emission: the box hook needs the exact set, in order.
    final LinkedHashMap<DecId, SortedSet<MIR.X>> captureLists = new LinkedHashMap<>();

    PackageState(String packageName) { this.packageName = packageName; }
  }

  public final Map<String, PackageState> packageStates = new LinkedHashMap<>();
  public final LinkedHashMap<DecId, Boolean> emittedTypes = new LinkedHashMap<>();

  final Map<DecId, String> typeToPackage = new HashMap<>();

  private String emitTargetPkg;
  private String pkg;

  private final boolean vpfEnabled;
  /// For the scope-free queries only. A function's instrumentation state lives on the
  /// throwaway instance {@link #visitFun} makes.
  private final VPFCodegen vpf;
  final Map<MIR.FName, Boolean> vpfBranchCache = new HashMap<>();
  final ReturnShapeAnalysis shapes;
  private final RecursionHotness hotness;
  private final StandInSelfArms standInArms;
  /// The packages read back from a cache. Their code was written by an earlier compilation
  /// and holds only the inline wrappers that compilation chose, so nothing here may name one.
  private final Set<String> cachedPkg;
  /// The function whose body is being emitted, which is what `hotness` asks about.
  private MIR.FName currentFun;
  private final RcFreeTypes rcFree;
  /// What every package read back from a cache counted about the types it declares. It is the
  /// only account of an object literal such a package wrote: `pkgInfo` keeps method headers and
  /// top level declarations, so an inline declaration never reaches the program read back here.
  private final main.java.ImplInfo cachedImpls;
  private final Map<MIR.FName, Boolean> transientVariantCache = new HashMap<>();

  public ZigSingleCodegen(MIR.Program p, boolean vpfEnabled, RapidTypeAnalysis rta,
                          Set<String> cachedPkg, main.java.ImplInfo cachedImpls) {
    this.vpfEnabled = vpfEnabled;
    this.cachedPkg = cachedPkg;
    this.cachedImpls = cachedImpls;
    this.rcFree = new RcFreeTypes(rta, (ast.Program) p.p(), cachedPkg, cachedImpls);
    magic = new ZigMagicImpls(this, t -> "rt.FatPtr", p.p(), this::shareCode);
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
    this.hotness = new RecursionHotness(p, shapes);
    this.standInArms = new StandInSelfArms(p);
  }

  PackageState getOrCreatePackageState(String pkgName) {
    return packageStates.computeIfAbsent(pkgName, PackageState::new);
  }

  PackageState currentState() {
    return getOrCreatePackageState(emitTargetPkg);
  }

  public String vtableRef(DecId objId) {
    return vtableRef(objId, false);
  }

  public String vtableRef(DecId objId, boolean transientVt) {
    var typeName = id.getSimpleName(objId);
    var vtName = "VT_" + typeName + (transientVt ? "_transient" : "");
    var owningPkg = owningPackageOf(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + vtName;
    }
    return vtName;
  }

  public String capturesRef(DecId objId) {
    var typeName = id.getSimpleName(objId);
    var owningPkg = owningPackageOf(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + typeName + "_Captures";
    }
    return typeName + "_Captures";
  }

  public String funRef(MIR.FName fName) {
    var zigName = id.getFName(fName);
    var owningPkg = owningPackageOf(fName.d());
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + zigName;
    }
    return zigName;
  }

  /// The per-literal method wrapper of `objId`. It takes `(receiver, args...)`, so a caller
  /// needs no captures.
  public String methWrapperRef(DecId objId, String methName) {
    return declOfType(objId, "MF_" + id.getSimpleName(objId) + "_" + methName);
  }

  /// The inline-only twin of the per-literal method wrapper. It exists so a hint reaches the
  /// method body: a hint on the plain wrapper folds in the wrapper alone and leaves the call
  /// to the body behind it, which costs a frame and buys nothing.
  public String methInlineWrapperRef(DecId objId, String methName) {
    return declOfType(objId, "MFI_" + id.getSimpleName(objId) + "_" + methName);
  }

  /// A declaration that belongs beside `objId`, qualified when that is another package.
  private String declOfType(DecId objId, String name) {
    var owningPkg = owningPackageOf(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + name;
    }
    return name;
  }

  /// A call to the per-literal wrapper of `objId`, taking the inline-only wrapper when the
  /// body being emitted is hot and the callee is not in the same component. That is the
  /// hotness a profile would supply; LLVM holds every other part of the decision.
  String methCallRef(DecId objId, String methName, MIR.FName callee, List<String> args) {
    var argList = String.join(", ", args);
    var plain = methWrapperRef(objId, methName) + "(" + argList + ")";
    if (!hotness.inlineTarget(currentFun, callee)) { return plain; }
    if (inlineHere(objId)) {
      return methInlineWrapperRef(objId, methName) + "(" + argList + ")";
    }
    var owningPkg = owningPackageOf(objId);
    if (owningPkg == null) { return plain; }
    // A cached package published an inline wrapper only for the bodies it judged small enough,
    // and a package built before this compilation published none at all. `@hasDecl` asks for
    // one and takes the plain wrapper when there is none, so the hint never decides whether the
    // program links.
    var inlineName = "MFI_" + id.getSimpleName(objId) + "_" + methName;
    var pkgRef = "root.pkg_" + owningPkg.replace(".", "_");
    return "(if (@hasDecl(" + pkgRef + ", \"" + inlineName + "\")) "
      + pkgRef + "." + inlineName + "(" + argList + ") else " + plain + ")";
  }

  /// Whether the package being emitted is cached and `f` is small enough to publish an inline
  /// wrapper for. The wrapper is written whether or not this compilation calls it, because the
  /// compilations that read this package back hold only the text it writes now.
  private boolean publishesInlineWrapper(MIR.FName f) {
    return emitTargetPkg != null && CompilationUnit.isCached(emitTargetPkg)
      && hotness.fitsInlineBudget(f);
  }

  /// Whether this compilation writes the code of `objId`, which is what decides if it can
  /// name an inline wrapper of that type. A cached package answers no.
  private boolean inlineHere(DecId objId) {
    var owningPkg = owningPackageOf(objId);
    return owningPkg != null && !cachedPkg.contains(owningPkg);
  }

  /// A call straight to a generated function, with an inline hint under the same rule.
  String callRef(MIR.FName callee, String target, List<String> args) {
    var argList = String.join(", ", args);
    if (callee == null || !inlineHere(callee.d()) || !hotness.inlineTarget(currentFun, callee)) {
      return target + "(" + argList + ")";
    }
    return "@call(.always_inline, " + target + ", .{ " + argList + " })";
  }

  /// The package that holds `objId`, or null when none declares it. An anonymous literal
  /// takes the package it is emitted into.
  public String packageOf(DecId objId) { return owningPackageOf(objId); }

  /// The package that declares `objId`, or null where nothing this compilation holds says.
  ///
  /// The lowered program answers for every type it holds. A type only the implInfo of a cached
  /// package names is absent from it, and the name of such a type carries the package that wrote
  /// it, which is the package whose generated file holds its vtable and its wrappers.
  String owningPackageOf(DecId objId) {
    var known = typeToPackage.get(objId);
    if (known != null) { return known; }
    var pkg = objId.pkg();
    return cachedPkg.contains(pkg) ? pkg : null;
  }

  public boolean isLiteral(DecId d) {
    return id.getLiteral(p.p(), d).isPresent();
  }

  boolean hasIdentityType(DecId d) {
    // A cached package keeps no declaration of an object literal it wrote, so asking the program
    // about one raises `traitNotFound`. Its implInfo is the account that stands. A type that file
    // does not name answers yes, which only ever costs a transient vtable test.
    if (!typeToPackage.containsKey(d) && cachedPkg.contains(d.pkg())) {
      return cachedImpls.get(d).map(main.java.ImplInfo.Entry::hasIdentity).orElse(true);
    }
    return p.p().superDecIds(d).contains(Magic.HasIdentity);
  }

  boolean isTransientEligibleType(DecId d) {
    return !hasIdentityType(d);
  }

  /// The storage-mode strategy that is safe for generated code in the current target package.
  /// Cached package text uses only facts that a later compilation cannot invalidate.
  RcFreeTypes.Strategy rcStrategy(MIR.MT t) {
    if (emitTargetPkg == null || CompilationUnit.isCached(emitTargetPkg)) {
      return rcFree.strategyForever(t);
    }
    return rcFree.strategy(t);
  }

  RcFreeTypes.Strategy rcStrategy(MIR.E e) { return rcStrategy(e.t()); }

  boolean isRcFree(MIR.E e) { return rcStrategy(e) == RcFreeTypes.Strategy.NONE; }

  /// The parameter slot `f` receives a stand-in receiver in, which only the general storage-mode
  /// dispatch describes. See {@link StandInSelfArms}.
  java.util.OptionalInt standInSelfSlot(MIR.FName f) { return standInArms.selfSlot(f); }

  /// The strategy for the parameter at `index` of `f`. A branch arm receives a singleton standing
  /// in for its literal, so that slot takes the general dispatch whatever the declared type of
  /// the parameter says.
  RcFreeTypes.Strategy paramStrategy(MIR.FName f, int index, MIR.MT t) {
    var standIn = standInSelfSlot(f);
    return standIn.isPresent() && standIn.getAsInt() == index
      ? RcFreeTypes.Strategy.DYNAMIC
      : rcStrategy(t);
  }

  String shareCode(String expr, MIR.MT t) { return shareCode(expr, rcStrategy(t)); }

  String shareCode(String expr, RcFreeTypes.Strategy strategy) {
    return switch (strategy) {
      case NONE -> expr;
      case HEAP -> expr + ".share_heap()";
      case HEAP_OR_TRANSIENT -> expr + ".share_heap_or_transient()";
      case DYNAMIC -> expr + ".share()";
    };
  }

  String decrementCode(String expr, MIR.MT t) {
    var method = switch (rcStrategy(t)) {
      case NONE -> null;
      case HEAP -> "rc_decrement_heap";
      case HEAP_OR_TRANSIENT -> "rc_decrement_heap_or_transient";
      case DYNAMIC -> "rc_decrement";
    };
    return method == null ? "" : expr + "." + method + "()";
  }

  String decrementAsCode(String expr, MIR.MT t, String releasingWorkerId) {
    return decrementAsCode(expr, rcStrategy(t), releasingWorkerId);
  }

  String decrementAsCode(String expr, RcFreeTypes.Strategy strategy, String releasingWorkerId) {
    var method = switch (strategy) {
      case NONE -> null;
      case HEAP -> "rc_decrement_heap_as";
      case HEAP_OR_TRANSIENT -> "rc_decrement_heap_or_transient_as";
      case DYNAMIC -> "rc_decrement_as";
    };
    return method == null ? "" : expr + "." + method + "(" + releasingWorkerId + ")";
  }

  record Drop(String name, MIR.MT t) {}

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
          + "_obj) else " + decrementCode(ownedName, e.t()),
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
      return shareCode(code, e.t());
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, gen, checkMagic, true);
    }
    return e.accept(gen, checkMagic);
  }

  String returnExpr(MIR.E e, boolean checkMagic) {
    if (e instanceof MIR.X x) {
      var code = visitX(x, checkMagic);
      return shareCode(code, x.t());
    }
    if (e instanceof MIR.BoolExpr b) {
      return boolExpr(b, this, checkMagic, true);
    }
    return e.accept(this, checkMagic);
  }

  /// Wraps `e` so a transient cannot leave the frame that holds its storage.
  ///
  /// A call result needs no wrapping: a generated function boxes its own return, and only
  /// generated code makes a transient, so a runtime intrinsic never returns one. The boxing hook
  /// would be dead code, and the storage-mode load it tests would sit between the call and the
  /// return, keeping the call out of tail position.
  String boxExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    return switch (e) {
      case MIR.Box box -> boxExpr(box.inner(), gen, checkMagic);
      case MIR.X x -> isRcFree(x) ? x.accept(gen, checkMagic) : boxBorrowedCode(x.accept(gen, checkMagic), x.t());
      case MIR.CreateObj createObj -> boxCreateObj(createObj, gen, checkMagic);
      case MIR.BoolExpr boolExpr -> boxBoolExpr(boolExpr, gen, checkMagic);
      case MIR.SumMatch ignored -> e.accept(gen, checkMagic);
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
  /// A `defer param.rc_decrement()` runs once the return value is known, so the backend must
  /// keep the frame alive across the call. Fearless writes a loop as a self-recursive call, so a
  /// loop of N steps then holds N frames and walks its stack out of L1. The params are dead once
  /// the operands are in temporaries, which is where the drops belong: the call becomes a tail
  /// call, and a self-recursive one becomes a loop.
  ///
  /// Every path emitted ends in a `return` or `unreachable`, and drops each param once.
  private String tailStatements(MIR.E e, List<Drop> dropNames, boolean checkMagic) {
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
        var callee = shapes.calleeOf(call).map(MIR.Fun::name).orElse(null);
        yield tailCallStatements(ops,
            refs -> methCallRef(call.concreteType(), methName, callee, refs), dropNames)
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
  private String armTailStatements(MIR.FName arm, List<Drop> dropNames, boolean checkMagic) {
    var deInlined = deInlinedBranch(arm, this, checkMagic);
    if (deInlined.isPresent()) { return valueTailStatements(deInlined.get(), dropNames); }
    var body = funMap.get(arm).body();
    return tailStatements(body instanceof MIR.Block block ? block.original() : body, dropNames, checkMagic);
  }

  /// `target(operands...)` in tail position, or empty when the operands need a prelude. A
  /// prelude holds a transient stored in this frame whose drop must outlive the call, which
  /// puts the call out of tail position.
  private Optional<String> tailCallStatements(CallOperands ops, Function<List<String>, String> build,
                                              List<Drop> dropNames) {
    if (!ops.prelude().isEmpty()) { return Optional.empty(); }
    var operands = Stream.concat(Stream.of(ops.recv()), ops.args().stream()).toList();
    var moved = moveLastShares(operands, dropNames);
    var sb = new StringBuilder();
    var refs = new ArrayList<String>();
    for (var operand : moved.operands()) {
      var tmp = "fear_op_" + blockCounter++;
      sb.append("const ").append(tmp).append(" = ").append(operand).append(";\n");
      refs.add(tmp);
    }
    appendDrops(sb, dropNames.stream().filter(drop -> !moved.cancelled().contains(drop)).toList());
    sb.append("return ").append(build.apply(refs)).append(";\n");
    return Optional.of(sb.toString());
  }

  /// The operands with each cancelled share rewritten, and the names whose drop that retires.
  record MovedShares(List<String> operands, Set<Drop> cancelled) {}

  /// Retires a share and the drop of the same parameter, which together are a no-op.
  ///
  /// A parameter this frame owns is shared into an operand and then dropped at the end of the
  /// path. The count returns to where it started and nothing in between reads it, so the
  /// operand may take the reference this frame already holds and the drop goes away.
  ///
  /// Only the last use may become the move. Giving this frame's reference to an earlier operand
  /// would let that callee release the value while a later operand still reads the name. Every
  /// operand binds to a temporary before the drops and the call run, and Zig evaluates them
  /// left to right, so the textually last use is the last one in time.
  ///
  /// A name is refused unless every use of it across the operands is a share. A use that is not
  /// a share is a borrow, and a borrow after the move would read a reference this frame has
  /// given away. Text inside a string literal does not count as a use, but a name that appears
  /// in one is refused rather than reasoned about, because the rewrite works on the raw text.
  private MovedShares moveLastShares(List<String> operands, List<Drop> dropNames) {
    var working = new ArrayList<>(operands);
    var cancelled = new LinkedHashSet<Drop>();
    for (var drop : dropNames) {
      var name = drop.name();
      var share = shareCode(name, drop.t());
      var uses = 0;
      var shares = 0;
      var lastIdx = -1;
      var literalClash = false;
      for (var i = 0; i < working.size(); i++) {
        var raw = working.get(i);
        var bare = withoutStringLiterals(raw);
        var here = countOccurrences(bare, name);
        if (countOccurrences(raw, name) != here) { literalClash = true; }
        if (here == 0) { continue; }
        uses += here;
        shares += countOccurrences(bare, share);
        lastIdx = i;
      }
      if (literalClash || lastIdx < 0 || uses != shares) { continue; }
      var target = working.get(lastIdx);
      var at = target.lastIndexOf(share);
      working.set(lastIdx, target.substring(0, at) + name + target.substring(at + share.length()));
      cancelled.add(drop);
    }
    return new MovedShares(List.copyOf(working), cancelled);
  }

  private static final java.util.regex.Pattern STRING_LITERAL =
    java.util.regex.Pattern.compile("\"(\\\\.|[^\"\\\\])*\"");

  private static String withoutStringLiterals(String code) {
    return STRING_LITERAL.matcher(code).replaceAll("\"\"");
  }

  /// Occurrences of `text` in `code` that are whole identifiers, so a name is not found inside
  /// a longer one.
  private static int countOccurrences(String code, String text) {
    var count = 0;
    for (var at = code.indexOf(text); at >= 0; at = code.indexOf(text, at + text.length())) {
      var before = at == 0 || !isNameChar(code.charAt(at - 1));
      var afterAt = at + text.length();
      var after = afterAt == code.length() || !isNameChar(code.charAt(afterAt));
      if (before && after) { count++; }
    }
    return count;
  }

  private static boolean isNameChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

  private String valueTailStatements(String expr, List<Drop> dropNames) {
    if (expr.equals("unreachable")) { return "unreachable;\n"; }
    var sb = new StringBuilder();
    var tmp = "fear_ret_" + blockCounter++;
    sb.append("const ").append(tmp).append(" = ").append(expr).append(";\n");
    appendDrops(sb, dropNames);
    sb.append("return ").append(tmp).append(";\n");
    return sb.toString();
  }

  private void appendDrops(StringBuilder sb, List<Drop> dropNames) {
    for (var drop : dropNames) {
      sb.append(decrementCode(drop.name(), drop.t())).append(";\n");
    }
  }

  private String boxBorrowedCode(String expr, MIR.MT t) {
    return boxOwnedCode(shareCode(expr, t));
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
      prelude.add("const " + field + "_shared = " + shareCode(x.accept(gen, checkMagic), x.t()) + ";");
      prelude.add("const " + tmp + " = " + field + "_shared.box_transient();");
      prelude.add("defer " + decrementCode(tmp, x.t()) + ";");
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

  /// The arms of a `SumMatch` as a chain of vtable tests, with the call as written behind them.
  ///
  /// The receiver binds to a name first: every arm reads it, and the test that selects an arm
  /// reads it again, so a bare expression would be built more than once.
  String sumMatch(MIR.SumMatch expr, MIRVisitor<String> gen, boolean checkMagic) {
    // The receiver takes the path it takes at any other call site, which is what gives it its
    // temporary and the drop that retires it. Reading it here without that leaks every value the
    // call was given.
    var prelude = new ArrayList<String>();
    var recvName = receiverOperand(expr.receiver(), gen, checkMagic, prelude);
    var block = "fear_blk_" + blockCounter++;
    var sb = new StringBuilder(block + ": {\n");
    sb.append("break :").append(block).append(" ");
    for (var arm : expr.arms()) {
      sb.append("if (").append(guardTest(recvName, arm.impl())).append(") ")
        .append(sumArmCall(recvName, arm, gen, checkMagic))
        .append(" else ");
    }
    sb.append(sumMatchFallback(recvName, expr, gen, checkMagic));
    sb.append(";\n}");
    return withTransientPrelude(prelude, sb.toString());
  }

  /// The arm's own call: the matcher method the receiver's implementation forwards to.
  ///
  /// An arm's args are [parameters..., self, captures...]. The parameters are what the forward
  /// passes, which it takes off the receiver, so they are read from the receiver's capture
  /// struct here. Nothing reads the self param, because an arm that captures self is never made
  /// into an arm, so a singleton stands in for the matcher that is never built. The captures are
  /// the same `MIR.X`s as in the enclosing scope, lent rather than given, exactly as a
  /// `BoolExpr` arm takes them.
  private String sumArmCall(String recvName, MIR.SumArm arm, MIRVisitor<String> gen,
                            boolean checkMagic) {
    var fun = funMap.get(arm.arm());
    var arity = arm.arm().m().num();
    var args = new ArrayList<String>();
    for (var i = 0; i < arm.captures().size(); i++) {
      var read = "rt.deref(" + capturesRef(arm.impl()) + ", " + recvName + ")."
        + id.varName(arm.captures().get(i));
      // The arm owns what it is given, so the capture is shared out of the receiver. The
      // parameter it fills is written at this call site, so its declared type is known here and
      // names a strategy narrower than the general storage-mode dispatch.
      args.add(shareCode(read, rcStrategy(fun.args().get(i).t())));
    }
    args.add("rt.obj_k_singleton(&" + vtableRef(new DecId("base.True", 0)) + ")");
    fun.args().stream().skip(arity + 1).forEach(x -> args.add(x.accept(gen, checkMagic)));
    return callRef(arm.arm(), funRef(arm.arm()), args);
  }

  /// The call as written, for a receiver no arm tests. The matcher is built here and only here.
  private String sumMatchFallback(String recvName, MIR.SumMatch expr, MIRVisitor<String> gen,
                                  boolean checkMagic) {
    var original = expr.original();
    var sig = new MIR.Sig(original.name(),
      original.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      original.originalRet());
    return "rt.call(" + recvName + ", " + sigBuilder.inlineHash(sig) + ", .{ "
      + expr.matcher().accept(gen, checkMagic) + " }, @src())";
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
      // `pub`, because a guarded devirtualisation in another package names this struct to
      // size the stack slot it calls through.
      currentState().captureStructs.put(objId,
        "pub const " + id.getSimpleName(objId) + "_Captures = extern struct {\n"
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

        // A call site of this compilation asked for it, or this package is cached and a later
        // compilation may ask: it cannot see this body, so the size is judged here.
        if (hotness.inlineWanted(meth.fName().get()) || publishesInlineWrapper(meth.fName().get())) {
          currentState().functions.add("pub inline fn MFI_" + typeName + "_" + methName
            + "(" + paramStr + ") rt.FatPtr {\n"
            + paramDiscard
            + tracePush
            + "return @call(.always_inline, " + fRef + ", .{ " + String.join(", ", simpleArgs) + " });\n"
            + "}");
        }

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
    // A cached package writes no state here, so its implInfo answers. A value that captures
    // nothing is the one the runtime gives a static header, which is what `singleton` reports,
    // so anything else carries a capture struct and a transient vtable beside its heap one.
    if (cachedPkg.contains(objId.pkg())) {
      return cachedImpls.get(objId).map(entry -> !entry.singleton()).orElse(false);
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
  /// both. The condition matches the one {@link #emitVTable} emits by.
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
      if (isRcFree(x)) {
        boxedFields.add("." + field + " = captures." + field);
        continue;
      }
      sb.append("const ").append(field).append("_shared = ").append(shareCode("captures." + field, x.t())).append(";\n");
      sb.append("const ").append(field).append("_boxed = ").append(field).append("_shared.box_transient();\n");
      sb.append("defer ").append(decrementCode(field + "_boxed", x.t())).append(";\n");
      boxedFields.add("." + field + " = " + field + "_boxed");
    }
    sb.append("return rt.obj_k(").append(capturesName).append(", &").append(vtableRef(objId)).append(", .{ ")
      .append(String.join(", ", boxedFields)).append(" });\n");
    sb.append("}");
    currentState().functions.add(sb.toString());
  }

  public void visitFun(MIR.Fun fun) {
    var savedFun = currentFun;
    currentFun = fun.name();
    try { emitFun(fun); } finally { currentFun = savedFun; }
  }

  private void emitFun(MIR.Fun fun) {
    var name = id.getFName(fun.name());
    var paramNames = fun.args().stream()
      .map(x -> id.varName(x.name()))
      .toList();
    // Only the declared params are owned, so only they drop. Everything from the receiver
    // onwards is lent: the receiver by its caller (see `receiverOperand`), and each capture
    // by the receiver that holds it. Captures are final, so a lent capture cannot be
    // replaced while the call runs.
    var selfIdx = selfArgIndex(fun);
    var dropNames = java.util.stream.IntStream.range(0, fun.args().size())
      .filter(i -> i < selfIdx)
      .mapToObj(fun.args()::get)
      .filter(x -> !isRcFree(x))
      .map(x -> new Drop(id.varName(x.name()), x.t()))
      .toList();
    var params = fun.args().stream()
      .map(x -> id.varName(x.name()) + ": rt.FatPtr")
      .collect(Collectors.joining(", "));

    var vpfCodegen = new VPFCodegen(this);
    var vpfInfo = vpfEnabled ? vpfCodegen.findVPFCall(fun.body()) : null;
    int topLevelLocalsSize = (fun.args().size() + 1) * 16;
    if (vpfInfo != null && topLevelLocalsSize <= VPFCodegen.LOCALS_COPY_LIMIT) {
      vpfCodegen.emitVPFFun(fun, name, paramNames, dropNames, params, vpfInfo);
      // The variant is the plain sequential shape of the body, so a slot-filling call site
      // takes the sequential path.
      emitTransientVariant(fun, name, paramNames, dropNames, params);
      return;
    }

    var statements = new StringBuilder();
    if (fun.body() instanceof MIR.Box outer) {
      statements.append(tailStatements(outer.inner(), dropNames, true));
    } else {
      var body = returnExpr(fun.body(), true);
      for (var drop : dropNames) {
        statements.append("defer ").append(decrementCode(drop.name(), drop.t())).append(";\n");
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
  /// wrapper may name it, or the transient escapes its frame.
  private void emitTransientVariant(MIR.Fun fun, String name, List<String> paramNames,
                                    List<Drop> dropNames, String params) {
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
                                 List<Drop> dropNames, boolean hoistDrops) {
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
  /// A receiver is alive for the whole call by construction: the caller has to hold it to make
  /// the call at all. The retain and release pair a shared receiver costs is therefore dead, and
  /// both halves go: this emits no `share`, and the callee leaves the receiver out of its drop
  /// list.
  ///
  /// A borrowed receiver never escapes. Every position that can outlive the call already asks
  /// for an owned value: an argument and a capture go through `ownedExpr`, and a returned name
  /// goes through `returnExpr`. So a borrowed receiver is only ever borrowed again as the
  /// receiver of a nested call, which holds for the same reason one level up.
  ///
  /// A receiver the caller builds on the spot has no owner, so the caller becomes one: the value
  /// binds to a temporary and drops when the enclosing block ends, after the call.
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
    prelude.add("defer " + decrementCode(tmp, e.t()) + ";");
    return tmp;
  }

  /// Whether the frame drops the very name it lends in a borrowed operand position.
  ///
  /// A tail forward binds the operands, drops the params and then calls, because the params
  /// are dead once the operands are bound. A borrowed operand breaks that: it is the frame's
  /// own reference rather than a share of it, so a drop that runs first frees the object the
  /// call is about to use. Such a call drops after the call returns instead.
  private boolean lendsDroppedName(MIR.E e, List<Drop> dropNames) {
    return e instanceof MIR.X x && dropNames.stream().anyMatch(drop -> drop.name().equals(id.varName(x.name())));
  }

  private boolean dropsBorrowedRecv(MIR.MCall call, List<Drop> dropNames) {
    return lendsDroppedName(call.recv(), dropNames);
  }

  /// Whether a `StaticCall` lends a dropped name. The receiver sits at the method's arity and
  /// the captures follow it, so every position from there on is lent.
  private boolean staticCallLendsDroppedName(MIR.StaticCall call, List<Drop> dropNames) {
    var selfIdx = call.fun().m().num();
    return java.util.stream.IntStream.range(0, call.args().size())
      .anyMatch(i -> i >= selfIdx && lendsDroppedName(call.args().get(i), dropNames));
  }

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
  /// {@link #slottedCallOperands}, which does the emitting.
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
      // The callee borrows the receiver and the captures, so each takes the path a receiver
      // takes in `slottedCallOperands`.
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
    var callee = shapes.calleeOf(call).map(MIR.Fun::name).orElse(null);
    return withTransientPrelude(ops.prelude(),
      methCallRef(call.concreteType(), methName, callee, all));
  }

  @Override
  public String visitGuardedCall(MIR.GuardedCall call, boolean checkMagic) {
    return emitGuardedCall(call, this, checkMagic);
  }

  /// A name no other emitted declaration in this file uses.
  String freshName(String prefix) { return prefix + blockCounter++; }

  /// A guarded call: a vtable test, the wrapper of the guessed type, and the virtual call.
  ///
  /// Receiver and arguments bind to a name first. Both arms name them and only one arm runs, so
  /// a bare expression would be built twice, and an argument the callee owns would be built for
  /// a call that never happens.
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
      .append(methCallRef(call.concreteType(), methName,
        shapes.calleeOf(call).map(MIR.Fun::name).orElse(null), names));
    // The second arm of a type with two implementations. The virtual call stays behind it, so
    // the arm only ever removes a dispatch and never decides one.
    call.altType().ifPresent(alt -> sb.append(" else if (").append(guardTest(recvName, alt))
      .append(") ").append(methCallRef(alt, methName,
        shapes.calleeOfAlt(call).map(MIR.Fun::name).orElse(null), names)));
    sb.append(" else rt.call(").append(recvName).append(", ").append(hashExpr).append(", ")
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
  public String visitSumMatch(MIR.SumMatch expr, boolean checkMagic) {
    return sumMatch(expr, this, checkMagic);
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
    return withTransientPrelude(prelude, callRef(call.fun(), fRef, args));
  }


  public String visitProgram(DecId entry) { throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg) { throw Bug.unreachable(); }
}
