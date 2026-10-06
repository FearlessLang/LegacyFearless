package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import id.Id;
import id.Id.DecId;
import id.Mdf;

/// Zig names for Fearless entities. References into other packages go through `root.pkg_*`.
interface ZigCodegenNames extends ZigCodegenContext {
  default String vtableRef(DecId objId) {
    return vtableRef(objId, false);
  }

  default String vtableRef(DecId objId, boolean transientVt) {
    var typeName = id().getSimpleName(objId);
    var vtName = "VT_" + typeName + (transientVt ? "_transient" : "");
    var owningPkg = owningPackageOf(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg())) {
      return "root.pkg_" + ZigStringIds.manglePkg(owningPkg) + "." + vtName;
    }
    return vtName;
  }

  default String capturesRef(DecId objId) {
    var typeName = id().getSimpleName(objId);
    var owningPkg = owningPackageOf(objId);
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg())) {
      return "root.pkg_" + ZigStringIds.manglePkg(owningPkg) + "." + typeName + "_Captures";
    }
    return typeName + "_Captures";
  }

  default String funRef(MIR.FName fName) {
    var zigName = id().getFName(fName);
    var owningPkg = owningPackageOf(fName.d());
    if (owningPkg != null && !owningPkg.equals(emitTargetPkg())) {
      return "root.pkg_" + ZigStringIds.manglePkg(owningPkg) + "." + zigName;
    }
    return zigName;
  }

  default String methWrapperRef(DecId objId, String methName) {
    return declOfType(objId, "MF_" + id().getSimpleName(objId) + "_" + methName);
  }

  default String methInlineWrapperRef(DecId objId, String methName) {
    return declOfType(objId, "MFI_" + id().getSimpleName(objId) + "_" + methName);
  }

  /// True when a type is compiled now, so its inline wrapper can be called directly.
  default boolean inlineHere(DecId objId) {
    var owningPkg = owningPackageOf(objId);
    return owningPkg != null && !cachedPkg().contains(owningPkg);
  }

  /// Calls a function, inlining it when [RecursionHotness] marks it as an inline target.
  default String callRef(MIR.FName callee, String target, java.util.List<String> args) {
    var argList = String.join(", ", args);
    if (callee == null || !inlineHere(callee.d()) || !hotness().inlineTarget(currentFun(), callee)) {
      return target + "(" + argList + ")";
    }
    return "@call(.always_inline, " + target + ", .{ " + argList + " })";
  }

  default String packageOf(DecId objId) {
    return owningPackageOf(objId);
  }

  default String owningPackageOf(DecId objId) {
    var known = typeToPackage().get(objId);
    if (known != null) { return known; }
    var pkg = objId.pkg();
    return cachedPkg().contains(pkg) ? pkg : null;
  }

  default boolean isLiteral(DecId decId) {
    return id().getLiteral(program().p(), decId).isPresent();
  }

  default boolean hasIdentityType(DecId decId) {
    if (!typeToPackage().containsKey(decId) && cachedPkg().contains(decId.pkg())) {
      return cachedImpls().get(decId).map(main.java.ImplInfo.Entry::hasIdentity).orElse(true);
    }
    return program().p().superDecIds(decId).contains(magic.Magic.HasIdentity);
  }

  /// A type without identity can live on the stack as a transient object.
  default boolean isTransientEligibleType(DecId decId) {
    return !hasIdentityType(decId);
  }

  /// Finds a function, preferring the variant that does not capture self, then searching parents.
  default MIR.FName findFun(DecId objId, Id.MethName method, Mdf mdf, MIR.TypeDef typeDef) {
    for (boolean capturesSelf : new boolean[]{false, true}) {
      var name = new MIR.FName(objId, method, capturesSelf, mdf);
      if (funMap().containsKey(name)) { return name; }
    }
    if (typeDef == null) { return null; }
    for (var parent : ParentWalker.of(program(), typeDef).skip(1).toList()) {
      for (boolean capturesSelf : new boolean[]{false, true}) {
        var name = new MIR.FName(parent.name(), method, capturesSelf, mdf);
        if (funMap().containsKey(name)) { return name; }
      }
    }
    return null;
  }
}
