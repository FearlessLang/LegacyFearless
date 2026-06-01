package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import magic.Magic;
import magic.MagicImpls;
import program.typesystem.TransientSemantics;

public class BoxingOptimisation implements MIRCloneVisitor {
  private final MagicImpls<?> magic;

  public BoxingOptimisation(MagicImpls<?> magic) {
    this.magic = magic;
  }

  @Override public MIR.Fun visitFun(MIR.Fun fun) {
    var cloned = MIRCloneVisitor.super.visitFun(fun);
    return cloned.withBody(box(cloned.body()));
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    var cloned = (MIR.MCall) MIRCloneVisitor.super.visitMCall(call, checkMagic);
    if (!isStorageLikeCall(cloned)) {
      return maybeBox(cloned);
    }
    return maybeBox(new MIR.MCall(
      cloned.recv(),
      cloned.name(),
      cloned.args().stream().map(this::box).toList(),
      cloned.t(),
      cloned.originalRet(),
      cloned.mdf(),
      cloned.variant()
    ));
  }

  @Override public MIR.E visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    return maybeBox(MIRCloneVisitor.super.visitBoolExpr(expr, checkMagic));
  }

  @Override public MIR.E visitBlockExpr(MIR.Block expr, boolean checkMagic) {
    return maybeBox(MIRCloneVisitor.super.visitBlockExpr(expr, checkMagic));
  }

  @Override public MIR.E visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
    return maybeBox(MIRCloneVisitor.super.visitStaticCall(call, checkMagic));
  }

  @Override public MIR.E visitUpdatableListAsIdFnCall(MIR.UpdatableListAsIdFnCall call, boolean checkMagic) {
    return maybeBox(MIRCloneVisitor.super.visitUpdatableListAsIdFnCall(call, checkMagic));
  }

  @Override public MIR.E visitBox(MIR.Box box, boolean checkMagic) {
    return box(box.inner().accept(this, checkMagic));
  }

  private MIR.E maybeBox(MIR.E e) {
    return hasIdentity(e) ? box(e) : e;
  }

  private MIR.E box(MIR.E e) {
    if (e instanceof MIR.Box) { return e; }
    return new MIR.Box(e);
  }

  private boolean hasIdentity(MIR.E e) {
    return e.t().name()
      .map(name -> magic.p().superDecIds(name).contains(Magic.HasIdentity))
      .orElse(false);
  }

  private boolean isStorageLikeCall(MIR.MCall call) {
    return call.recv().t().name()
      .map(owner -> TransientSemantics.isStorageLikeCall(owner, call.name()))
      .orElse(false);
  }
}
