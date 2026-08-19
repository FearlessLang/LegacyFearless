package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import magic.Magic;
import magic.MagicImpls;
import program.typesystem.XBs;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Inlines a boolean `.if` or `?` call as a ternary when the ThenElse/1 argument is a
/// literal that does not capture itself. The inlining is shallow.
///
/// Correct because the `ThenElse` is a `CreateObj` written at the call site, so the arm's
/// capture names are the same `MIR.X` objects as in the enclosing scope and an inlined arm
/// body reads bindings that exist. {@link DevirtualiseByRTA} is the general case.
public class BoolIfOptimisation implements MIRCloneVisitor {
  private final MagicImpls<?> magic;
  private Map<MIR.FName, MIR.Fun> funs;
  public BoolIfOptimisation(MagicImpls<?> magic) {
    this.magic = magic;
  }

  @Override public MIR.Package visitPackage(MIR.Package pkg) {
    this.funs = pkg.funs().stream().collect(Collectors.toMap(MIR.Fun::name, Function.identity()));
    return MIRCloneVisitor.super.visitPackage(pkg);
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    if (magic.isMagic(Magic.Bool, call.recv()) && (call.name().equals(new Id.MethName(".if", 1)) || call.name().equals(new Id.MethName("?", 1)))) {
      var res = boolIfOptimisation(call);
      if (res.isPresent()) { return res.get(); }
    }
    return MIRCloneVisitor.super.visitMCall(call, checkMagic);
  }

  private Optional<MIR.BoolExpr> boolIfOptimisation(MIR.MCall original) {
    // A canonical ThenElse literal: no extra methods, and the lambda made inline here.
    // The inline restriction is what lets the captures be assumed present.
    assert original.args().size() == 1;
    var thenElse_ = original.args().getFirst();
    if (!(thenElse_ instanceof MIR.CreateObj thenElse)) { return Optional.empty(); }
    var thenElseDec = this.magic.p().of(thenElse.t().name().orElseThrow());
    var thenElseMs = this.magic.p().meths(XBs.empty(), thenElse.t().mdf(), thenElseDec.toIT(), 0);
    // .then and .else are mandatory, so under 2 is impossible and over 2 is out of scope.
    assert thenElseMs.size() >= 2;
    if (thenElseMs.size() != 2) { return Optional.empty(); }

    var then = thenElse.meths().stream().filter(m->m.sig().name().equals(new Id.MethName(".then", 0))).findFirst().orElseThrow();
    var else_ = thenElse.meths().stream().filter(m->m.sig().name().equals(new Id.MethName(".else", 0))).findFirst().orElseThrow();
    if (then.capturesSelf() || else_.capturesSelf()) {
      return Optional.empty();
    }

    // One level only. A nested `.if` can be optimised in turn, but never inlined here.
    return Optional.of(new MIR.BoolExpr(original, original.recv(), then.fName().orElseThrow(), else_.fName().orElseThrow()));
  }
}
