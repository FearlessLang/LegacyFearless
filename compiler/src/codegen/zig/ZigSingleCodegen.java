package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import codegen.optimisations.RapidTypeAnalysis;
import codegen.optimisations.RecursionHotness;
import codegen.optimisations.AcyclicTypes;
import codegen.optimisations.RcFreeTypes;
import codegen.optimisations.ReturnShapeAnalysis;
import codegen.optimisations.ScalarSumTypes;
import id.Id;
import id.Id.DecId;
import magic.Magic;
import utils.Bug;

import java.util.*;
import java.util.stream.Collectors;

/// The FeaRT Zig backend: MIR to Zig text. Holds all shared state; the `ZigCodegen*` traits compute from it.
public class ZigSingleCodegen implements ZigCodegen {
  protected final MIR.Program p;
  protected final Map<MIR.FName, MIR.Fun> funMap;
  private final ZigMagicImpls magic;
  public final ZigStringIds id = new ZigStringIds();
  final ZigSigStringBuilder sigBuilder;
  private int transientCounter = 0;
  private int blockCounter = 0;

  /// One package's Zig output: functions, capture structs, vtables, and per-type capture metadata.
  static class PackageState {
    final String packageName;
    final List<String> functions = new ArrayList<>();
    final LinkedHashMap<DecId, String> captureStructs = new LinkedHashMap<>();
    final LinkedHashMap<String, String> vtableDefs = new LinkedHashMap<>();

    final LinkedHashMap<DecId, SortedSet<MIR.X>> captureLists = new LinkedHashMap<>();
    final LinkedHashMap<DecId, Set<String>> immCaptures = new LinkedHashMap<>();

    PackageState(String packageName) { this.packageName = packageName; }
  }

  public final Map<String, PackageState> packageStates = new LinkedHashMap<>();
  public final LinkedHashMap<DecId, Boolean> emittedTypes = new LinkedHashMap<>();

  final Map<DecId, String> typeToPackage = new HashMap<>();

  private String emitTargetPkg;
  private String pkg;

  private final boolean vpfEnabled;
  private final VPFCodegen vpf;
  final Map<MIR.FName, Boolean> vpfBranchCache = new HashMap<>();
  final ReturnShapeAnalysis shapes;
  private final RecursionHotness hotness;
  private final StandInSelfArms standInArms;
  private final Set<String> cachedPkg;
  private MIR.FName currentFun;
  private final RcFreeTypes rcFree;
  private final ScalarSumTypes scalarSums;
  private final main.java.ImplInfo cachedImpls;
  private final Map<MIR.FName, Boolean> transientVariantCache = new HashMap<>();
  private final Map<ZigCodegenShapes.WrapperKey, ZigCodegenShapes.WrapperShape> wrapperShapeCache = new HashMap<>();
  private final Map<MIR.FName, List<ZigCodegenShapes.ArgSlot>> funShapeCache = new HashMap<>();

  public ZigSingleCodegen(
      MIR.Program p,
      boolean vpfEnabled,
      RapidTypeAnalysis rta,
      Set<String> cachedPkg,
      main.java.ImplInfo cachedImpls
  ) {
    this.vpfEnabled = vpfEnabled;
    this.cachedPkg = cachedPkg;
    this.cachedImpls = cachedImpls;
    this.rcFree = new RcFreeTypes(rta, p.p(), cachedPkg, cachedImpls);
    this.scalarSums = new ScalarSumTypes(rta, p.p(), cachedPkg, cachedImpls);
    magic = new ZigMagicImpls(this, t -> "rt.FatPtr", p.p(), this::generateShare);
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
    this.shapes = new ReturnShapeAnalysis(p, this::isTransientCreateObj, cachedPkg);
    this.hotness = new RecursionHotness(p, shapes);
    this.standInArms = new StandInSelfArms(p);
  }

  public MIR.Program program() { return p; }
  public Map<MIR.FName, MIR.Fun> funMap() { return funMap; }
  public ZigMagicImpls magicImpls() { return magic; }
  public ZigStringIds id() { return id; }
  public ScalarSumTypes scalarSums() { return scalarSums; }
  public Set<String> cachedPkg() { return cachedPkg; }
  public boolean vpfEnabled() { return vpfEnabled; }
  public VPFCodegen vpf() { return vpf; }
  public Map<DecId, String> typeToPackage() { return typeToPackage; }
  public main.java.ImplInfo cachedImpls() { return cachedImpls; }
  public RcFreeTypes rcFree() { return rcFree; }
  public StandInSelfArms standInArms() { return standInArms; }
  public Map<MIR.FName, Boolean> transientVariantCache() { return transientVariantCache; }
  public Map<ZigCodegenShapes.WrapperKey, ZigCodegenShapes.WrapperShape> wrapperShapeCache() { return wrapperShapeCache; }
  public Map<MIR.FName, List<ZigCodegenShapes.ArgSlot>> funShapeCache() { return funShapeCache; }
  public MIR.FName currentFun() { return currentFun; }
  public String emitTargetPkg() { return emitTargetPkg; }
  public ZigSigStringBuilder sigBuilder() { return sigBuilder; }
  public RecursionHotness hotness() { return hotness; }
  public ReturnShapeAnalysis shapes() { return shapes; }
  public int nextTransient() { return transientCounter++; }
  public int nextBlock() { return blockCounter++; }
  PackageState getOrCreatePackageState(String pkgName) {
    return packageStates.computeIfAbsent(pkgName, PackageState::new);
  }

  PackageState currentState() {
    return getOrCreatePackageState(emitTargetPkg);
  }

  /// Emits a type: its singleton, its functions. Magic instances and literals emit nothing.
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

    return "";
  }

  /// Emits a type once: capture struct, methods, inherited signatures, vtable. Switches package while emitting.
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

    // Types emit into their owning package, then restore the target.
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
        "pub const " + id.getSimpleName(objId) + "_Captures = extern struct {\n"
        + fields + "\n" + rcFreeFieldsDecl(createObj.captures()) + "};");
      currentState().captureLists.put(objId, createObj.captures());
      currentState().immCaptures.put(objId, createObj.immCaptures());
    }

    for (var meth : createObj.meths()) {
      emitMeth(meth, objId, false, leastSpecific);
    }
    for (var meth : createObj.unreachableMs()) {
      emitMeth(meth, objId, true, leastSpecific);
    }

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
          var meth = new MIR.Meth(
            objId,
            sig,
            fName.capturesSelf(),
            new TreeSet<>(),
            Optional.of(fName)
          );
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
    return findFun(objId, sig.name(), sig.mdf(), typeDef);
  }

  /// Emits a method's wrappers: its Mearless function's generated code, the vtable thunk, plus inline and transient variants when wanted.
  private void emitMeth(
      MIR.Meth meth,
      DecId objId,
      boolean isUnreachable,
      Map<Id.MethName, MIR.Sig> leastSpecific
  ) {
    var sig = meth.sig();

    var methName = id.getMName(sig.mdf(), sig.name());
    var typeName = id.getSimpleName(objId);
    var mfName = "MF_" + typeName + "_" + methName;
    var tName = "T_" + typeName + "_" + methName;

    var shape = wrapperShape(objId, sig.name(), sig.mdf());
    var unboxed = shape.params();
    var params = new ArrayList<String>();
    if (!shape.elideRecv()) { params.add("fear_self: rt.FatPtr"); }
    for (int i = 0; i < sig.xs().size(); i++) {
      params.add(id.varName(sig.xs().get(i).name()) + ": " + paramType(unboxed, i));
    }
    var paramStr = String.join(", ", params);
    var returnType = zigReturnType(shape.result());
    var recvDecl = shape.elideRecv()
      ? "const fear_self = rt.obj_k_singleton(&" + vtableRef(objId) + ");\n"
      : "";

    var paramNames = params.stream().map(p -> p.split(":")[0].trim()).collect(Collectors.toCollection(ArrayList::new));
    var paramDiscard = paramNames.isEmpty() ? "" : "_ = .{ " + String.join(", ", paramNames) + " };\n";
    var bodyNames = new ArrayList<>(paramNames);
    if (shape.elideRecv()) { bodyNames.add("fear_self"); }
    var bodyDiscard = "_ = .{ " + String.join(", ", bodyNames) + " };\n";
    if (isUnreachable || meth.fName().isEmpty()) {
      currentState().functions.add("pub fn " + mfName + "(" + paramStr + ") callconv(.c) " + returnType + " {\n"
        + paramDiscard
        + "unreachable;\n"
        + "}");
    } else {
      var fRef = funRef(meth.fName().get());
      var fun = funMap.get(meth.fName().get());
      if (fun != null) {
        var calleeShape = funShape(meth.fName().get());
        var paramPrelude = new ArrayList<String>();
        var simpleArgs = new ArrayList<String>();
        for (int i = 0; i < sig.xs().size(); i++) {
          var n = id.varName(sig.xs().get(i).name());
          var from = i < unboxed.size() ? unboxed.get(i) : Optional.<Scalar>empty();
          var to = i < calleeShape.size() ? calleeShape.get(i).scalar() : Optional.<Scalar>empty();
          simpleArgs.add(reshapeArg(from, to, n, paramPrelude));
        }
        simpleArgs.add("fear_self");
        for (var capture : meth.captures()) {
          if (!createObjHasCaptures(objId)) {
            simpleArgs.add("fear_self");
          } else {
            simpleArgs.add(
              "rt.deref(" + capturesRef(objId) + ", fear_self)." + id.varName(capture));
          }
        }

        var tracePush = "shadow_stack_mod.tracePush(&" + vtableRef(objId) + ", " + sigBuilder.inlineHash(sig) + ");\n"
          + "defer shadow_stack_mod.tracePop();\n";
        var forwarded = String.join(
          ", ",
          shapeArgs(meth.fName().get(), simpleArgs, sig.xs().size())
        );
        var paramDecls = paramPrelude.isEmpty()
          ? "" : String.join("\n", paramPrelude) + "\n";

        currentState().functions.add("pub fn " + mfName + "(" + paramStr + ") callconv(.c) " + returnType + " {\n"
          + recvDecl
          + bodyDiscard
          + tracePush
          + paramDecls
          + "return " + fRef + "(" + forwarded + ");\n"
          + "}");

        if (hotness.inlineWanted(meth.fName().get()) || publishesInlineWrapper(meth.fName().get())) {
          currentState().functions.add("pub inline fn MFI_" + typeName + "_" + methName
            + "(" + paramStr + ") " + returnType + " {\n"
            + recvDecl
            + bodyDiscard
            + tracePush
            + paramDecls
            + "return @call(.always_inline, " + fRef + ", .{ " + forwarded + " });\n"
            + "}");
        }

        if (hasTransientVariant(meth.fName().get())) {
          var summaryObj = shapes.freshObj(meth.fName().get()).orElseThrow();
          emitCreateObj(summaryObj, true);
          var caps = capturesRef(summaryObj.concreteT().id());
          currentState().functions.add("pub fn " + mfName + "_transient(fear_out: *rt.GenObjectLayoutType(" + caps + ")"
            + (paramStr.isEmpty() ? "" : ", " + paramStr) + ") callconv(.c) rt.FatPtr {\n"
            + recvDecl
            + bodyDiscard
            + tracePush
            + paramDecls
            + "return " + fRef + "_transient(fear_out"
            + (forwarded.isEmpty() ? "" : ", " + forwarded) + ");\n"
            + "}");
        }
      } else {
        currentState().functions.add("pub fn " + mfName + "(" + paramStr + ") callconv(.c) " + returnType + " {\n"
          + paramDiscard
          + "unreachable;\n"
          + "}");
      }
    }

    var thunkParams = new ArrayList<String>();
    thunkParams.add("fear_self: rt.FatPtr");
    for (var x : sig.xs()) {
      thunkParams.add(id.varName(x.name()) + ": rt.FatPtr");
    }
    var thunkCallArgs = new ArrayList<String>();
    if (!shape.elideRecv()) { thunkCallArgs.add("fear_self"); }
    for (int i = 0; i < sig.xs().size(); i++) {
      var name = id.varName(sig.xs().get(i).name());
      thunkCallArgs.add(i < unboxed.size() && unboxed.get(i).isPresent()
        ? toScalar(unboxed.get(i).orElseThrow(), name)
        : name);
    }

    var thunkCall = mfName + "(" + String.join(", ", thunkCallArgs) + ")";
    var thunkResult = shape.result().map(sc -> toBoxedOwned(sc, thunkCall)).orElse(thunkCall);
    currentState().functions.add("fn " + tName + "(" + String.join(", ", thunkParams) + ") callconv(.c) rt.FatPtr {\n"
      + (shape.elideRecv() ? "_ = .{ fear_self };\n" : "")
      + "return " + thunkResult + ";\n"
      + "}");
  }

  private boolean isGreenType(DecId objId) {
    var captures = captureListFor(objId);
    if (captures == null) {
      return !createObjHasCaptures(objId);
    }
    return AcyclicTypes.isGreen(captures, immCapturesFor(objId), this::isRcFree);
  }

  private Set<String> immCapturesFor(DecId objId) {
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null) {
      var state = packageStates.get(owningPkg);
      if (state != null && state.immCaptures.containsKey(objId)) {
        return state.immCaptures.get(objId);
      }
    }
    return currentState().immCaptures.getOrDefault(objId, Set.of());
  }

  private SortedSet<MIR.X> captureListFor(DecId objId) {
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null) {
      var state = packageStates.get(owningPkg);
      if (state != null && state.captureLists.containsKey(objId)) {
        return state.captureLists.get(objId);
      }
    }
    return currentState().captureLists.get(objId);
  }

  /// Whether a type stores captures, looking into its owning package when cached.
  private boolean createObjHasCaptures(DecId objId) {
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null) {
      var state = packageStates.get(owningPkg);
      if (state != null) { return state.captureStructs.containsKey(objId); }
    }
    if (cachedPkg.contains(objId.pkg())) {
      return cachedImpls.get(objId).map(entry -> !entry.singleton()).orElse(false);
    }
    return false;
  }

  /// Names the capture fields that need no counting, so the runtime skips them.
  private String rcFreeFieldsDecl(Collection<MIR.X> captures) {
    var free = captures.stream()
      .filter(this::isRcFree)
      .map(x -> "\"" + id.varName(x.name()) + "\"")
      .collect(Collectors.joining(", "));
    if (free.isEmpty()) { return ""; }
    return "pub const rc_free_fields = [_][]const u8{ " + free + " };\n";
  }

  /// A type with one value: a declared singleton, or any captureless literal.
  private boolean isSingletonType(DecId objId) {
    var typeDef = p.pkgs().stream()
      .filter(pkg -> pkg.defs().containsKey(objId))
      .map(pkg -> pkg.defs().get(objId))
      .findFirst().orElse(null);
    if (typeDef != null && typeDef.singletonInstance().isPresent()) return true;
    return !createObjHasCaptures(objId);
  }

  /// Tests an object's type, accepting the transient vtable twin too.
  public String guardTest(String recvName, DecId target) {
    var plain = recvName + ".vt == &" + vtableRef(target);
    if (!isTransientEligibleType(target) || !createObjHasCaptures(target)) { return plain; }
    return "(" + plain + " or " + recvName + ".vt == &" + vtableRef(target, true) + ")";
  }

  /// Emits the vtable, plus a transient twin and box hook when the type can live on the stack and has captures.
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
    var traceLine = createObjHasCaptures(objId)
      ? ".trace_fn = rt.captureTraceFn(" + capturesRef(objId) + "),\n" : "";
    var greenLine = isGreenType(objId) ? ".green = true,\n" : "";

    var vtKey = typeName + (isTransient ? "_transient" : "");
    currentState().vtableDefs.put(vtKey,
      "pub const VT_" + typeName + (isTransient ? "_transient" : "") + ": rt.VTable = .{\n"
      + ".type_name = " + ZigStringIds.zigString(objId.name() + "/" + objId.gen()) + ",\n"
      + ".hashes = " + hashesStr + ",\n"
      + ".methods = " + methodsStr + ",\n"
      + ".method_names = " + methodNamesStr + ",\n"
      + storageModeLine
      + boxLine
      + traceLine
      + greenLine
      + "};");
  }

  /// Boxes a stack object to the heap, sharing each capture first.
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
    sb.append("fn ").append(hookName).append("(fear_self: rt.FatPtr) callconv(.c) rt.FatPtr {\n");
    sb.append("const captures = rt.deref(").append(capturesName).append(", fear_self);\n");
    var captureNames = currentState().captureLists.getOrDefault(objId, MIR.createCapturesSet());
    var boxedFields = new ArrayList<String>();
    for (var x : captureNames) {
      var field = id.varName(x.name());
      if (isRcFree(x)) {
        boxedFields.add("." + field + " = captures." + field);
        continue;
      }
      sb.append("const ").append(field).append("_shared = ").append(generateShare("captures." + field, x.t())).append(";\n");
      sb.append("const ").append(field).append("_boxed = ").append(field).append("_shared.box_transient();\n");
      sb.append("defer ").append(generateDecrement(field + "_boxed", x.t())).append(";\n");
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

  /// Emits a function: the VPF variant when certified and within the locals copy limit, else sequential. Boxed and transient entries follow where wanted.
  private void emitFun(MIR.Fun fun) {
    var name = id.getFName(fun.name());
    var dropNames = List.<Drop>of();
    var signature = funSignature(fun);
    var paramNames = signature.discardNames();
    var params = signature.params();

    var vpfCodegen = new VPFCodegen(this);
    var vpfInfo = vpfEnabled ? vpfCodegen.findVPFCall(fun.body()) : null;
    int topLevelLocalsSize = vpfCodegen.localsSize(fun, 0);
    if (vpfInfo != null && topLevelLocalsSize <= VPFCodegen.LOCALS_COPY_LIMIT) {
      vpfCodegen.emitVPFFun(fun, name, signature, dropNames, vpfInfo);
      emitBoxedResultEntry(fun, name, signature);
      emitTransientVariant(fun, name, signature, dropNames);
      return;
    }

    var statements = new StringBuilder();
    if (fun.body() instanceof MIR.Box outer) {
      statements.append(tailStatements(outer.inner(), dropNames, true));
    } else {
      var body = currentReturn(fun.body(), returnExpr(fun.body(), true), true);
      for (var drop : dropNames) {
        statements.append("defer ").append(generateDecrement(drop.name(), drop.t())).append(";\n");
      }
      statements.append(body.equals("unreachable") ? "unreachable;\n" : "return " + body + ";\n");
    }

    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("(").append(params).append(") callconv(.c) ")
      .append(zigReturnType(funResultShape(fun.name()))).append(" {\n");
    sb.append(signature.prologue());
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }
    sb.append("heartbeat.tryPromote();\n");
    sb.append(statements);
    sb.append("}");
    currentState().functions.add(sb.toString());

    emitBoxedResultEntry(fun, name, signature);
    emitTransientVariant(fun, name, signature, dropNames);
  }

  /// A boxed entry point over a scalar function: calls it, then boxes the result.
  private void emitBoxedResultEntry(MIR.Fun fun, String name, FunSignature signature) {
    var result = funResultShape(fun.name());
    if (result.isEmpty()) { return; }
    var args = signature.scalarNames().stream()
      .filter(Objects::nonNull)
      .collect(Collectors.joining(", "));
    currentState().functions.add("pub fn " + name + "_boxed(" + signature.params()
      + ") callconv(.c) rt.FatPtr {\n"
      + "return " + toBoxedOwned(result.orElseThrow(), name + "(" + args + ")") + ";\n"
      + "}");
  }

  /// Zig parameters for a function: unboxed scalars plus prologue code, discard list, and scalar names for the boxed entry.
  record FunSignature(
    String params,
    String prologue,
    List<String> discardNames,
    List<String> scalarNames
  ) {}

  /// Builds a function's Zig parameters: elided self becomes a constant, sums pass directly, primitives pass raw with a boxing prologue.
  FunSignature funSignature(MIR.Fun fun) {
    var shape = funShape(fun.name());
    var params = new ArrayList<String>();
    var discardNames = new ArrayList<String>();
    var scalarNames = new ArrayList<String>();
    var prologue = new StringBuilder();
    for (int i = 0; i < fun.args().size(); i++) {
      var n = id.varName(fun.args().get(i).name());
      var slot = i < shape.size() ? shape.get(i) : ArgSlot.BOXED;
      if (slot.elided()) {
        prologue.append("const ").append(n).append(" = ").append(standInSelf()).append(";\n");
        discardNames.add(n);
        scalarNames.add(null);
        continue;
      }
      if (slot.scalar().isPresent()) {
        var sc = slot.scalar().orElseThrow();
        if (sc.isSum()) {
          params.add(n + ": " + sc.zigType());
          discardNames.add(n);
          scalarNames.add(n);
        } else {
          params.add(n + "_s: " + sc.zigType());
          prologue.append("const ").append(n).append(" = ").append(sc.rtModule())
            .append(".make(").append(n).append("_s);\n");
          discardNames.add(n);
          scalarNames.add(n + "_s");
        }
        continue;
      }
      params.add(n + ": rt.FatPtr");
      discardNames.add(n);
      scalarNames.add(n);
    }
    return new FunSignature(
      String.join(", ", params),
      prologue.toString(),
      List.copyOf(discardNames),
      scalarNames
    );
  }

  /// Emits the slot-writing variant: builds a literal in place, or tail-forwards to a callee that does.
  /// Only bodies accepted by [computeHasTransientVariant] reach here.
  private void emitTransientVariant(
      MIR.Fun fun,
      String name,
      FunSignature signature,
      List<Drop> dropNames
  ) {
    if (!hasTransientVariant(fun.name())) { return; }
    var summaryObj = shapes.freshObj(fun.name()).orElseThrow();
    emitCreateObj(summaryObj, true);
    var caps = capturesRef(summaryObj.concreteT().id());
    var params = signature.params();
    var paramNames = signature.discardNames();

    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("_transient(fear_out: *rt.GenObjectLayoutType(")
      .append(caps).append(")").append(params.isEmpty() ? "" : ", " + params)
      .append(") callconv(.c) rt.FatPtr {\n");
    sb.append(signature.prologue());
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ").append(String.join(", ", paramNames)).append(" };\n");
    }
    sb.append("heartbeat.tryPromote();\n");

    var body = ReturnShapeAnalysis.unwrap(fun.body());
    switch (body) {
      case MIR.CreateObj k -> {
        var capturePrelude = new ArrayList<String>();
        var captures = k.captures().stream()
          .map(x -> "." + id.varName(x.name()) + " = "
            + boxedBorrowed(x, visitX(x, true), capturePrelude))
          .collect(Collectors.joining(", "));
        capturePrelude.forEach(line -> sb.append(line).append("\n"));
        sb.append("const fear_slot = rt.init_transient_obj(").append(caps).append(", fear_out, &")
          .append(vtableRef(k.concreteT().id(), true)).append(", .{ ").append(captures).append(" });\n");
        appendDrops(sb, dropNames);
        sb.append("return fear_slot;\n");
      }
      case MIR.DirectCall d -> {
        var original = d.original();
        var ops = callOperands(original, this, true);
        var target = methWrapperRef(d.concreteType(), id.getMName(original.mdf(), original.name())) + "_transient";
        var operands = new ArrayList<String>();
        operands.add(ops.recv());
        operands.addAll(ops.args());
        var boxPrelude = new ArrayList<String>();
        var shaped = new CallOperands(
          null,
          marshalWrapperArgs(d.concreteType(), original, operands, this, boxPrelude),
          ops.prelude()
        );
        boxPrelude.forEach(line -> sb.append(line).append("\n"));
        appendTailForward(sb, shaped, target, dropNames, !dropsBorrowedRecv(original, dropNames));
      }
      case MIR.StaticCall s -> {
        var prelude = new ArrayList<String>();
        var args = staticCallArgs(s, this, true, prelude, false);
        var ops = new CallOperands(null, args, prelude);
        appendTailForward(
          sb,
          ops,
          funRef(s.fun()) + "_transient",
          dropNames,
          !staticCallLendsDroppedName(s, dropNames)
        );
      }
      default -> throw Bug.unreachable();
    }
    sb.append("}");
    currentState().functions.add(sb.toString());
  }

  public String visitProgram(DecId entry) { throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg) { throw Bug.unreachable(); }
}
