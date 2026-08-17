package visitors;

import codegen.MIR;

public interface MIRVisitor<R> {
//  R visitProgram(MIR.Program p, Id.DecId entry);
//  R visitPackage(MIR.Package pkg);
//  R visitTypeDef(String pkg, MIR.TypeDef def);
//  R visitMeth(String pkg, MIR.Meth meth, boolean signatureOnly);
//  R visitObjLit(String pkg, MIR.ObjLit lit, boolean checkMagic);
  R visitCreateObj(MIR.CreateObj createObj, boolean checkMagic);
  R visitX(MIR.X x, boolean checkMagic);
  R visitMCall(MIR.MCall call, boolean checkMagic);

  // Optimisations fall-back to their original expressions by default
  default R visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    return expr.original().accept(this, checkMagic);
  }
  default R visitBlockExpr(MIR.Block expr, boolean checkMagic) {
    return expr.original().accept(this, checkMagic);
  }
  default R visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
    return call.original().accept(this, checkMagic);
  }
  default R visitDirectCall(MIR.DirectCall call, boolean checkMagic) {
    return call.original().accept(this, checkMagic);
  }
  default R visitUpdatableListAsIdFnCall(MIR.UpdatableListAsIdFnCall call, boolean checkMagic) {
    return call.e().accept(this, checkMagic);
  }
  default R visitBox(MIR.Box box, boolean checkMagic) {
    return box.inner().accept(this, checkMagic);
  }
}
