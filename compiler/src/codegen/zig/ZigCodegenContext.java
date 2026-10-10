package codegen.zig;

import codegen.MIR;
import codegen.optimisations.RcFreeTypes;
import codegen.optimisations.RecursionHotness;
import codegen.optimisations.ReturnShapeAnalysis;
import codegen.optimisations.ScalarSumTypes;
import id.Id;
import id.Id.DecId;
import visitors.MIRVisitor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The state and the cross-trait methods that the codegen traits share. [ZigSingleCodegen] implements it.
interface ZigCodegenContext extends MIRVisitor<String> {
  MIR.Program program();
  Map<MIR.FName, MIR.Fun> funMap();
  ZigMagicImpls magicImpls();
  ZigStringIds id();
  ScalarSumTypes scalarSums();
  Set<String> cachedPkg();
  boolean vpfEnabled();
  VPFCodegen vpf();
  Map<DecId, String> typeToPackage();
  main.java.ImplInfo cachedImpls();
  RcFreeTypes rcFree();
  StandInSelfArms standInArms();
  Map<MIR.FName, Boolean> transientVariantCache();
  Map<ZigCodegenShapes.WrapperKey, ZigCodegenShapes.WrapperShape> wrapperShapeCache();
  Map<MIR.FName, List<ZigCodegenShapes.ArgSlot>> funShapeCache();
  MIR.FName currentFun();
  String emitTargetPkg();
  ZigSigStringBuilder sigBuilder();
  RecursionHotness hotness();
  ReturnShapeAnalysis shapes();
  int nextTransient();
  int nextBlock();
  String vtableRef(DecId id);
  String vtableRef(DecId id, boolean transientVt);
  String capturesRef(DecId id);
  String methWrapperRef(DecId id, String name);
  String methInlineWrapperRef(DecId id, String name);
  String funRef(MIR.FName name);
  String owningPackageOf(DecId id);
  MIR.TypeDef typeDefOf(DecId id);
  String declOfType(DecId id, String name);
  MIR.FName findFun(DecId id, Id.MethName name, id.Mdf mdf, MIR.TypeDef typeDef);
  boolean hasTransientVariant(MIR.FName name);
  boolean isTransientEligibleType(DecId id);
  boolean isPureInline(MIR.E expr);
  boolean isRcFree(MIR.E expr);
  java.util.OptionalInt standInSelfSlot(MIR.FName name);
  int selfArgIndex(MIR.Fun fun);
  String generateBoxBorrowed(String value, MIR.MT type);
  String withTransientPrelude(List<String> prelude, String expr);
  boolean inlineHere(DecId id);
  void emitCreateObj(MIR.CreateObj expr, boolean checkMagic);
  String boxExpr(MIR.E expr, MIRVisitor<String> gen, boolean checkMagic);
  String boolExpr(MIR.BoolExpr expr, MIRVisitor<String> gen, boolean checkMagic, boolean ownedBranches);
  String generateBoxOwned(String value);
  String boxCreateObj(MIR.CreateObj expr, MIRVisitor<String> gen, boolean checkMagic);
  String createObj(MIR.CreateObj expr, MIRVisitor<String> gen, boolean checkMagic);
  String boxBoolExpr(MIR.BoolExpr expr, MIRVisitor<String> gen, boolean checkMagic);
  String currentReturn(MIR.E expr, String code, boolean checkMagic);
  String currentReturn(MIR.E expr, boolean checkMagic);
  String boolCondition(MIR.E condition, String code);
  String methCallRef(DecId objId, MIR.MCall original, MIR.FName callee, List<String> args, MIRVisitor<String> gen);
  String callRef(MIR.FName callee, String target, List<String> args);
  String receiverOperand(
      MIR.E expression,
      MIRVisitor<String> gen,
      boolean checkMagic,
      List<String> prelude
  );
  String standInSelf();
  List<String> reshapeArgs(
      MIR.FName name,
      List<Optional<ZigCodegenValues.Scalar>> sources,
      List<String> args,
      List<String> prelude
  );
  String withBorrowedBox(
      ZigCodegenValues.Scalar scalar,
      String value,
      java.util.function.Function<String, String> use
  );
  List<ZigCodegenShapes.ArgSlot> funShape(MIR.FName name);
  String scalar(ZigCodegenValues.Scalar target, MIR.E expression, String code, MIRVisitor<String> gen);
  boolean foldsAtOperand(Optional<ZigCodegenValues.Scalar> target, MIR.E expression);
  Optional<String> nicheBorrowedFold(
      ZigCodegenValues.Scalar target,
      MIR.E expression,
      MIRVisitor<String> gen,
      boolean checkMagic
  );
  List<Optional<ZigCodegenValues.Scalar>> operandTargets(DecId objId, MIR.MCall call);
  String reshapeScalarOwned(
      ZigCodegenValues.Scalar source,
      ZigCodegenValues.Scalar target,
      String code
  );
  String scalarOwned(
      ZigCodegenValues.Scalar target,
      MIR.E expression,
      String code,
      MIRVisitor<String> gen,
      boolean checkMagic
  );
  String guardedArm(
      DecId target,
      MIR.MCall original,
      Optional<ZigCodegenValues.Scalar> siteScalar,
      String code
  );
  boolean isTransientCreateObj(MIR.E expression);
  ZigCodegenOwnership.Materialised materialiseTransient(
      MIR.CreateObj createObj,
      MIRVisitor<String> gen,
      boolean checkMagic
  );
  Optional<ZigCodegenOwnership.Materialised> materialiseSlotCall(
      MIR.E expression,
      MIRVisitor<String> gen,
      boolean checkMagic
  );
  String ownedExpr(MIR.E expression, MIRVisitor<String> gen, boolean checkMagic);
  Optional<ZigSingleCodegen.BorrowedCapture> borrowedCaptureRead(MIR.E expression, MIRVisitor<String> gen, boolean checkMagic);
  Optional<String> deInlinedBranch(MIR.FName name, MIRVisitor<String> gen, boolean checkMagic);
  String visitCreateObj(MIR.CreateObj expression, boolean checkMagic);
  ZigCodegenCalls.CallOperands slottedCallOperands(
      MIR.MCall call,
      MIRVisitor<String> gen,
      boolean checkMagic,
      java.util.Optional<MIR.Fun> knownCallee
  );
  String freshName(String prefix);
  String guardTest(String value, DecId target);
  String generateShare(String value, MIR.MT type);
  String generateShare(String value, RcFreeTypes.Strategy strategy);
  String generateDecrement(String value, MIR.MT type);
  String generateDecrement(String value, RcFreeTypes.Strategy strategy);

  Optional<ZigCodegenValues.Scalar> scalarOf(MIR.MT type);
  Optional<ZigCodegenValues.Scalar> scalarSumOf(MIR.MT type);
  String toScalar(ZigCodegenValues.Scalar scalar, String boxed);
  String toScalarOwned(ZigCodegenValues.Scalar scalar, String boxed);
  String ownedNiche(String code);
  String toBoxed(ZigCodegenValues.Scalar scalar, String value);
  String toBoxedOwned(ZigCodegenValues.Scalar scalar, String value);
  String borrowedBox(ZigCodegenValues.Scalar scalar, String value, List<String> prelude);

  Optional<ZigCodegenValues.Scalar> funResultShape(MIR.FName name);
  List<String> unboxArgs(DecId objId, Id.MethName name, id.Mdf mdf, List<String> args);
  List<String> marshalWrapperArgs(
      DecId objId,
      MIR.MCall call,
      List<String> args,
      MIRVisitor<String> gen,
      List<String> prelude
  );
  Optional<ZigCodegenValues.Scalar> emittedScalar(MIR.E expression);
  String boxed(MIR.E expression, String code);
  String boxedOwned(MIR.E expression, String code);
  String boxedBorrowed(MIR.E expression, String code, List<String> prelude);
}
