package codegen;

import utils.Mapper;
import visitors.MIRVisitor;

import java.util.Collections;
import java.util.List;
import java.util.EnumSet;
import java.util.stream.Collectors;

public interface MIRCloneVisitor extends MIRVisitor<MIR.E> {
  default MIR.Program visitProgram(MIR.Program p) {
    return new MIR.Program(p.p(), p.pkgs().stream().map(this::visitPackage).toList());
  }
  default MIR.Package visitPackage(MIR.Package pkg) {
    return new MIR.Package(
      pkg.name(),
      Mapper.of(res->pkg.defs().forEach((id,def) -> res.put(id, this.visitTypeDef(def)))),
      pkg.funs().stream().map(this::visitFun).toList()
    );
  }
  default MIR.TypeDef visitTypeDef(MIR.TypeDef def) {
    return new MIR.TypeDef(
      def.name(),
      def.impls().stream().map(this::visitPlain).toList(),
      def.sigs().stream().map(this::visitSig).toList(),
      def.singletonInstance().map(k->this.visitCreateObj(k, true))
    );
  }
  default MIR.Sig visitSig(MIR.Sig sig) {
    return new MIR.Sig(
      sig.name(),
      sig.xs().stream().map(x->(MIR.X)this.visitX(x, true)).toList(),
      this.visitMT(sig.rt())
    );
  }
  default MIR.Meth visitMeth(MIR.Meth meth) {
    return new MIR.Meth(meth.origin(), this.visitSig(meth.sig()), meth.capturesSelf(), meth.captures(), meth.fName());
  }
  default MIR.Fun visitFun(MIR.Fun fun) {
    return new MIR.Fun(
      fun.name(),
      fun.args().stream().map(x->(MIR.X)this.visitX(x, true)).toList(),
      this.visitMT(fun.ret()), fun.body().accept(this, true));
  }

  default MIR.MT visitMT(MIR.MT t) {
    return switch (t) {
      case MIR.MT.Any any -> this.visitAny(any);
      case MIR.MT.Plain plain -> this.visitPlain(plain);
      case MIR.MT.Usual usual -> this.visitUsual(usual);
    };
  }
  default EnumSet<MIR.MCall.CallVariant> visitCallVariant(EnumSet<MIR.MCall.CallVariant> cv) {
    return cv;
  }
  default MIR.MT.Any visitAny(MIR.MT.Any t) {
    return t;
  }
  default MIR.MT.Plain visitPlain(MIR.MT.Plain t) {
    return t;
  }
  default MIR.MT.Usual visitUsual(MIR.MT.Usual t) {
    return t;
  }

  @Override default MIR.CreateObj visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
    return new MIR.CreateObj(
      this.visitMT(createObj.t()),
      createObj.selfName(),
      createObj.meths().stream().map(this::visitMeth).toList(),
      createObj.unreachableMs().stream().map(this::visitMeth).toList(),
      Collections.unmodifiableSortedSet(createObj.captures().stream().map(x->(MIR.X)this.visitX(x, checkMagic)).collect(Collectors.toCollection(MIR::createCapturesSet)))
    );
  }

  @Override default MIR.E visitX(MIR.X x, boolean checkMagic) {
    return new MIR.X(x.name(), this.visitMT(x.t()));
  }

  @Override default MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    return new MIR.MCall(
      call.recv().accept(this, checkMagic),
      call.name(),
      call.args().stream().map(e->e.accept(this, checkMagic)).toList(),
      this.visitMT(call.t()),
      this.visitMT(call.originalRet()),
      call.mdf(),
      this.visitCallVariant(call.variant())
    );
  }

  /// The call this holds is rebuilt from its visited parts rather than visited itself, so a
  /// later pass cannot devirtualise it. The arms already replace the dispatch, and a guard in
  /// front of the call behind them would test a receiver no arm had claimed.
  ///
  /// A pass that rewrites the matcher into something other than a literal takes the call as it
  /// was written: the arms name methods of the literal, so there is nothing to run without it.
  @Override default MIR.E visitSumMatch(MIR.SumMatch expr, boolean checkMagic) {
    var call = expr.original();
    var receiver = expr.receiver().accept(this, checkMagic);
    var matcher = expr.matcher().accept(this, checkMagic);
    if (!(matcher instanceof MIR.CreateObj literal)) {
      return call.accept(this, checkMagic);
    }
    var original = new MIR.MCall(
      receiver,
      call.name(),
      List.of(literal),
      this.visitMT(call.t()),
      this.visitMT(call.originalRet()),
      call.mdf(),
      this.visitCallVariant(call.variant())
    );
    return new MIR.SumMatch(original, receiver, literal, expr.arms());
  }

  @Override default MIR.E visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    return new MIR.BoolExpr(
      expr.original().accept(this, checkMagic),
      expr.condition().accept(this, checkMagic),
      expr.then(),
      expr.else_()
    );
  }

  @Override default MIR.E visitBlockExpr(MIR.Block expr, boolean checkMagic) {
    return new MIR.Block(
      expr.original().accept(this, checkMagic),
      expr.stmts().stream().map(stmt -> stmt.withE(stmt.e().accept(this, checkMagic))).toList(),
      this.visitMT(expr.expectedT())
    );
  }

  @Override default MIR.E visitStaticCall(MIR.StaticCall call, boolean checkMagic) {
    return new MIR.StaticCall(
      call.original().accept(this, checkMagic),
      call.fun(),
      call.args().stream().map(arg -> arg.accept(this, checkMagic)).toList(),
      call.castTo().map(this::visitMT)
    );
  }

  @Override default MIR.E visitDirectCall(MIR.DirectCall call, boolean checkMagic) {
    var original = call.original().accept(this, checkMagic);
    if (original instanceof MIR.MCall mCall) {
      return new MIR.DirectCall(mCall, call.concreteType());
    }
    return original;
  }

  @Override default MIR.E visitGuardedCall(MIR.GuardedCall call, boolean checkMagic) {
    var original = call.original().accept(this, checkMagic);
    if (original instanceof MIR.MCall mCall) {
      return new MIR.GuardedCall(mCall, call.concreteType(), call.altType());
    }
    return original;
  }

  @Override default MIR.E visitUpdatableListAsIdFnCall(MIR.UpdatableListAsIdFnCall call, boolean checkMagic) {
    var e = call.e().accept(this, checkMagic);
    if (e instanceof MIR.MCall mCall) {
      return new MIR.UpdatableListAsIdFnCall(mCall);
    }
    return e.accept(this, checkMagic);
  }

  @Override default MIR.E visitBox(MIR.Box box, boolean checkMagic) {
    return new MIR.Box(box.inner().accept(this, checkMagic));
  }
}
