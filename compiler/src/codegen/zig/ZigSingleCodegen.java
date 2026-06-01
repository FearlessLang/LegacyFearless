package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import id.Id;
import id.Id.DecId;
import magic.Magic;
import utils.Bug;
import visitors.MIRVisitor;

import java.util.*;
import java.util.stream.Collectors;

public class ZigSingleCodegen implements MIRVisitor<String> {
  protected final MIR.Program p;
  protected final Map<MIR.FName, MIR.Fun> funMap;
  private final ZigMagicImpls magic;
  public final ZigStringIds id = new ZigStringIds();
  final ZigSigStringBuilder sigBuilder;
  private int transientCounter = 0;
  private int blockCounter = 0;

  // Per-package accumulated output
  static class PackageState {
    final String packageName;
    final List<String> functions = new ArrayList<>();
    final LinkedHashMap<DecId, String> captureStructs = new LinkedHashMap<>();
    final LinkedHashMap<String, String> vtableDefs = new LinkedHashMap<>();

    // Capture list recorded verbatim at CreateObj emission time. The box hook needs the
    // exact, ordered capture set; re-deriving it from the AST is fragile, so we keep it here.
    final LinkedHashMap<DecId, SortedSet<MIR.X>> captureLists = new LinkedHashMap<>();

    PackageState(String packageName) { this.packageName = packageName; }
  }

  public final Map<String, PackageState> packageStates = new LinkedHashMap<>();
  // freshRecords equivalent: tracks which CreateObj types we've already emitted
  public final LinkedHashMap<DecId, Boolean> emittedTypes = new LinkedHashMap<>();

  // Map from type DecId -> owning package name
  final Map<DecId, String> typeToPackage = new HashMap<>();

  // The package currently being emitted into
  private String emitTargetPkg;
  private String pkg;

  public ZigSingleCodegen(MIR.Program p) {
    magic = new ZigMagicImpls(this, t -> "rt.FatPtr", p.p());
    sigBuilder = new ZigSigStringBuilder(p.p());
    this.p = p;
    this.funMap = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f));

    // Build typeToPackage map
    for (var mpkg : p.pkgs()) {
      for (var defId : mpkg.defs().keySet()) {
        typeToPackage.put(defId, mpkg.name());
      }
    }
  }

  PackageState getOrCreatePackageState(String pkgName) {
    return packageStates.computeIfAbsent(pkgName, PackageState::new);
  }

  PackageState currentState() {
    return getOrCreatePackageState(emitTargetPkg);
  }

  /** Get a VTable reference, qualified with root.pkg_ prefix if cross-package. */
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

  /** Get a Captures struct reference, qualified with root.pkg_ prefix if cross-package. */
  public String capturesRef(DecId objId) {
    var typeName = id.getSimpleName(objId);
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + typeName + "_Captures";
    }
    return typeName + "_Captures";
  }

  /** Get a static function reference, qualified with root.pkg_ prefix if cross-package. */
  public String funRef(MIR.FName fName) {
    var zigName = id.getFName(fName);
    var owningPkg = typeToPackage.get(fName.d());
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg)) {
      return "root.pkg_" + owningPkg.replace(".", "_") + "." + zigName;
    }
    return zigName;
  }

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

  String boxExpr(MIR.E e, MIRVisitor<String> gen, boolean checkMagic) {
    return switch (e) {
      case MIR.Box box -> boxExpr(box.inner(), gen, checkMagic);
      case MIR.X x -> boxBorrowedCode(x.accept(gen, checkMagic));
      case MIR.CreateObj createObj -> boxCreateObj(createObj, gen, checkMagic);
      case MIR.BoolExpr boolExpr -> boxBoolExpr(boolExpr, gen, checkMagic);
      case MIR.MCall ignored -> boxOwnedCode(e.accept(gen, checkMagic));
      case MIR.StaticCall ignored -> boxOwnedCode(e.accept(gen, checkMagic));
      case MIR.UpdatableListAsIdFnCall ignored -> boxOwnedCode(e.accept(gen, checkMagic));
      case MIR.Block ignored -> boxOwnedCode(e.accept(gen, checkMagic));
    };
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

  private String boxBoolExpr(MIR.BoolExpr expr, MIRVisitor<String> gen, boolean checkMagic) {
    String recv = expr.condition().accept(gen, checkMagic);

    String thenBody = switch (this.funMap.get(expr.then()).body()) {
      case MIR.Block b -> boxExpr(b.original(), gen, checkMagic);
      case MIR.E e -> boxExpr(e, gen, checkMagic);
    };
    String elseBody = switch (this.funMap.get(expr.else_()).body()) {
      case MIR.Block b -> boxExpr(b.original(), gen, checkMagic);
      case MIR.E e -> boxExpr(e, gen, checkMagic);
    };

    return "(if (" + recv + ".vt == &" + vtableRef(new DecId("base.True", 0)) + ") " + thenBody + " else " + elseBody + ")";
  }

  String boolExpr(MIR.BoolExpr expr, MIRVisitor<String> gen, boolean checkMagic, boolean ownedBranches) {
    String recv = expr.condition().accept(gen, checkMagic);

    String thenBody = switch (this.funMap.get(expr.then()).body()) {
      case MIR.Block b -> inlineBlock(b, gen, ownedBranches);
      case MIR.E e -> ownedBranches ? ownedExpr(e, gen, checkMagic) : e.accept(gen, checkMagic);
    };
    String elseBody = switch (this.funMap.get(expr.else_()).body()) {
      case MIR.Block b -> inlineBlock(b, gen, ownedBranches);
      case MIR.E e -> ownedBranches ? ownedExpr(e, gen, checkMagic) : e.accept(gen, checkMagic);
    };

    return "(if (" + recv + ".vt == &" + vtableRef(new DecId("base.True", 0)) + ") " + thenBody + " else " + elseBody + ")";
  }

  public String visitTypeDef(String pkg, MIR.TypeDef def, List<MIR.Fun> funs) {
    this.pkg = pkg;
    this.emitTargetPkg = pkg;
    var isMagic = pkg.equals("base") && def.name().name().endsWith("Instance");
    var isLiteral = isLiteral(def.name());
    if (isMagic || isLiteral) { return ""; }

    // Emit VTable for singleton types
    var leastSpecific = ParentWalker.leastSpecificSigs(p, def);

    // If this type has a singleton instance, emit it
    def.singletonInstance().ifPresent(objK -> {
      emitCreateObj(objK, true);
    });

    // Emit static functions
    for (var fun : funs) {
      visitFun(fun);
    }

    return ""; // All output accumulated in state
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

    // Route emission to the owning package
    var savedEmitTarget = this.emitTargetPkg;
    var owningPkg = typeToPackage.get(objId);
    if (owningPkg != null) {
      this.emitTargetPkg = owningPkg;
    } else {
      // Type not in any package's defs (anonymous/literal) — record where we emit it
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

    // Emit captures struct
    if (!createObj.captures().isEmpty()) {
      var fields = createObj.captures().stream()
        .map(x -> id.varName(x.name()) + ": rt.FatPtr,")
        .collect(Collectors.joining("\n"));
      currentState().captureStructs.put(objId,
        "const " + id.getSimpleName(objId) + "_Captures = extern struct {\n"
        + fields + "\n};");
      // Record the authoritative capture list for the box hook.
      currentState().captureLists.put(objId, createObj.captures());
    }

    // Emit MF_ and T_ functions for each method
    for (var meth : createObj.meths()) {
      emitMeth(meth, objId, false, leastSpecific);
    }
    for (var meth : createObj.unreachableMs()) {
      emitMeth(meth, objId, true, leastSpecific);
    }

    // Supplement with inherited methods from TypeDef that aren't in the CreateObj
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

    // Emit VTable
    emitVTable(allMeths, objId);

    // Restore emit target
    this.emitTargetPkg = savedEmitTarget;
  }

  private MIR.FName findFunForSig(DecId objId, MIR.Sig sig, MIR.TypeDef typeDef) {
    // Try current type with both capturesSelf values
    for (boolean capturesSelf : new boolean[]{false, true}) {
      var fName = new MIR.FName(objId, sig.name(), capturesSelf, sig.mdf());
      if (funMap.containsKey(fName)) { return fName; }
    }
    // Walk parent types
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

    // Build parameter list
    var params = new ArrayList<String>();
    params.add("self_m: rt.FatPtr");
    for (var x : sig.xs()) {
      params.add(id.varName(x.name()) + ": rt.FatPtr");
    }
    var paramStr = String.join(", ", params);

    var paramDiscard = "_ = .{ " + params.stream().map(p -> p.split(":")[0].trim()).collect(Collectors.joining(", ")) + " };\n";
    if (isUnreachable || meth.fName().isEmpty()) {
      // Unreachable method
      currentState().functions.add("fn " + mfName + "(" + paramStr + ") rt.FatPtr {\n"
        + paramDiscard
        + "unreachable;\n"
        + "}");
    } else {
      // Real method: delegate to the static Fun
      var fRef = funRef(meth.fName().get());
      var fun = funMap.get(meth.fName().get());
      if (fun != null) {
        var simpleArgs = new ArrayList<String>();
        for (var x : sig.xs()) {
          simpleArgs.add(id.varName(x.name()));
        }
        simpleArgs.add("self_m");
        for (var capture : meth.captures()) {
          // Captures need to be extracted from self if it's an object with captures
          if (!createObjHasCaptures(objId)) {
            simpleArgs.add("self_m.share()"); // no captures, pass self as placeholder
          } else {
            simpleArgs.add(
              "rt.deref(" + capturesRef(objId) + ", self_m)." + id.varName(capture) + ".share()");
          }
        }

        currentState().functions.add("fn " + mfName + "(" + paramStr + ") rt.FatPtr {\n"
          + paramDiscard
          + "return " + fRef + "(" + String.join(", ", simpleArgs) + ");\n"
          + "}");
      } else {
        // Fun not found, make unreachable
        currentState().functions.add("fn " + mfName + "(" + paramStr + ") rt.FatPtr {\n"
          + "_ = .{ " + params.stream().map(p -> p.split(":")[0].trim()).collect(Collectors.joining(", ")) + " };\n"
          + "unreachable;\n"
          + "}");
      }
    }

    // C-ABI thunk
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

    for (var meth : allMeths) {
      var sig = meth.sig();
      hashes.add(sigBuilder.hashExpr(sig));
      methods.add("&T_" + typeName + "_" + id.getMName(sig.mdf(), sig.name()));
    }

    var hashesStr = hashes.isEmpty() ? "&.{}" :
      "&[_]u64{ " + String.join(", ", hashes) + " }";
    var methodsStr = methods.isEmpty() ? "&.{}" :
      "&[_]*const anyopaque{ " + String.join(", ", methods) + " }";

    var isTransient = transientVt && !isSingletonType(objId);
    var storageModeLine = isTransient
      ? "    .storage_mode = .transient,\n"
      : isSingletonType(objId) ? "    .storage_mode = .singleton,\n" : "";
    var boxLine = isTransient ? "    .box_fn = &box_" + typeName + ",\n" : "";

    var vtKey = typeName + (isTransient ? "_transient" : "");
    currentState().vtableDefs.put(vtKey,
      "pub const VT_" + typeName + (isTransient ? "_transient" : "") + ": rt.VTable = .{\n"
      + "    .type_name = \"" + objId.name() + "/" + objId.gen() + "\",\n"
      + "    .hashes = " + hashesStr + ",\n"
      + "    .methods = " + methodsStr + ",\n"
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
    // Capture list recorded by emitCreateObj when the captures struct was built.
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

    // Check if this function body contains a VPF-parallelisable call
    var vpfCodegen = new VPFCodegen(this);
    var vpfInfo = vpfCodegen.findVPFCall(fun.body());
    // Top-level locals struct: N params (FatPtr=16) + r1 (FatPtr=16)
    int topLevelLocalsSize = (fun.args().size() + 1) * 16;
    if (vpfInfo != null && topLevelLocalsSize <= VPFCodegen.LOCALS_COPY_LIMIT) {
      vpfCodegen.emitVPFFun(fun, name, paramNames, params, vpfInfo);
      return;
    }

    var body = returnExpr(fun.body(), true);
    var sb = new StringBuilder();
    sb.append("pub fn ").append(name).append("(").append(params).append(") rt.FatPtr {\n");
    // Discard all params to avoid unused-parameter errors
    if (!paramNames.isEmpty()) {
      sb.append("_ = .{ ");
      sb.append(String.join(", ", paramNames));
      sb.append(" };\n");
    }
    sb.append("heartbeat.tryPromote();\n");
    for (var paramName : paramNames) {
      sb.append("defer ").append(paramName).append(".rc_decrement();\n");
    }
    if (body.equals("unreachable")) {
      sb.append("unreachable;\n");
    } else {
      sb.append("return ").append(body).append(";\n");
    }
    sb.append("}");
    currentState().functions.add(sb.toString());
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

  String emitMCall(MIR.MCall call, MIRVisitor<String> gen, boolean checkMagic) {
    var prelude = new ArrayList<String>();
    String recv;
    if (isTransientCreateObj(call.recv())) {
      var materialised = materialiseTransient((MIR.CreateObj) call.recv(), gen, checkMagic);
      prelude.addAll(materialised.prelude());
      recv = materialised.ref();
    } else {
      recv = ownedExpr(call.recv(), gen, checkMagic);
    }
    var sig = new MIR.Sig(call.name(),
      call.args().stream().map(a -> new MIR.X("_", a.t())).toList(),
      call.originalRet());
    var hashExpr = sigBuilder.inlineHash(sig);

    var args = call.args().stream()
      .map(a -> {
        if (isTransientCreateObj(a)) {
          var materialised = materialiseTransient((MIR.CreateObj) a, gen, checkMagic);
          prelude.addAll(materialised.prelude());
          return materialised.ref();
        }
        return ownedExpr(a, gen, checkMagic);
      })
      .collect(Collectors.joining(", "));

    var argsTuple = args.isEmpty() ? ".{}" : ".{ " + args + " }";
    return withTransientPrelude(prelude, "rt.call(" + recv + ", " + hashExpr + ", " + argsTuple + ", @src())");
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
      // Type not found in MIR — treat as singleton with empty vtable
      emitCreateObj(createObj, checkMagic);
      return "rt.obj_k_singleton(&" + vtableRef(objId) + ")";
    }
    var singleton = typeDef.singletonInstance().isPresent();

    // Make sure this type's struct/vtable/methods have been emitted
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

  // TODO: the block optimisation impl here is not correct, will clean up later.
  /*
  @Override
  public String visitBlockExpr(MIR.Block expr, boolean checkMagic) {
    var stmts = new ArrayDeque<>(expr.stmts());
    var sb = new StringBuilder();
    sb.append("blk: {\n");
    var doIdx = 0;
    while (!stmts.isEmpty()) {
      var stmt = stmts.poll();
      switch (stmt) {
        case MIR.Block.BlockStmt.Return ret ->
          sb.append("break :blk ").append(ret.e().accept(this, true)).append(";\n");
        case MIR.Block.BlockStmt.Do do_ -> {
          sb.append("_ = ").append(do_.e().accept(this, true)).append(";\n");
          doIdx++;
        }
        case MIR.Block.BlockStmt.Throw throw_ ->
          sb.append("@panic(\"Fearless error\");\n");
        case MIR.Block.BlockStmt.Loop loop ->
          sb.append("while (true) { _ = ").append(loop.e().accept(this, true)).append("; }\n");
        case MIR.Block.BlockStmt.If if_ -> {
          var nextStmt = stmts.poll();
          var body = nextStmt != null ? visitBlockStmt(nextStmt) : "unreachable";
          sb.append("if (").append(if_.pred().accept(this, true))
            .append(".vt == &VT_True_0) { ").append(body).append(" }\n");
        }
        case MIR.Block.BlockStmt.Let let -> {
          var vn = id.varName(let.name());
          sb.append("const ").append(vn).append(" = ")
            .append(let.value().accept(this, true)).append(";\n");
          sb.append("_ = .{ ").append(vn).append(" };\n");
        }
        case MIR.Block.BlockStmt.Var var_ -> {
          var vn = id.varName(var_.name());
          sb.append("const ").append(vn).append(" = ")
            .append(var_.value().accept(this, true)).append(";\n");
          sb.append("_ = .{ ").append(vn).append(" };\n");
        }
      }
    }
    sb.append("}");
    return sb.toString();
  }

  private String visitBlockStmt(MIR.Block.BlockStmt stmt) {
    return switch (stmt) {
      case MIR.Block.BlockStmt.Return ret -> "break :blk " + ret.e().accept(this, true) + ";";
      case MIR.Block.BlockStmt.Do do_ -> "_ = " + do_.e().accept(this, true) + ";";
      case MIR.Block.BlockStmt.Throw throw_ -> "@panic(\"Fearless error\");";
      case MIR.Block.BlockStmt.Loop loop -> "while (true) { _ = " + loop.e().accept(this, true) + "; }";
      case MIR.Block.BlockStmt.If if_ -> "if (" + if_.pred().accept(this, true) + ".vt == &VT_True_0)";
      case MIR.Block.BlockStmt.Let let -> "const " + id.varName(let.name()) + " = " + let.value().accept(this, true) + ";";
      case MIR.Block.BlockStmt.Var var_ -> "const " + id.varName(var_.name()) + " = " + var_.value().accept(this, true) + ";";
    };
  }
   */

  @Override
  public String visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
    var fRef = funRef(call.fun());
    var prelude = new ArrayList<String>();
    var args = call.args().stream()
      .map(a -> {
        if (isTransientCreateObj(a)) {
          var materialised = materialiseTransient((MIR.CreateObj) a, this, checkMagic);
          prelude.addAll(materialised.prelude());
          return materialised.ref();
        }
        return ownedExpr(a, checkMagic);
      })
      .collect(Collectors.joining(", "));
    return withTransientPrelude(prelude, fRef + "(" + args + ")");
  }


  // Not used directly - output is accumulated in state
  public String visitProgram(DecId entry) { throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg) { throw Bug.unreachable(); }
}
