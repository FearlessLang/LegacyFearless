package visitors;

import codegen.MIR;

public interface MIRVisitor<R> {
  R visitCreateObj(MIR.CreateObj createObj, boolean checkMagic);
  R visitX(MIR.X x, boolean checkMagic);
  R visitMCall(MIR.MCall call, boolean checkMagic);

  // An optimisation falls back to its original expression by default.
  default R visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    return expr.original().accept(this, checkMagic);
  }
  default R visitSumMatch(MIR.SumMatch expr, boolean checkMagic) {
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
  default R visitGuardedCall(MIR.GuardedCall call, boolean checkMagic) {
    return call.original().accept(this, checkMagic);
  }
  default R visitUpdatableListAsIdFnCall(MIR.UpdatableListAsIdFnCall call, boolean checkMagic) {
    return call.e().accept(this, checkMagic);
  }
  default R visitBox(MIR.Box box, boolean checkMagic) {
    return box.inner().accept(this, checkMagic);
  }
}
