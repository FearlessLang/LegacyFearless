package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import magic.Magic;
import magic.MagicImpls;
import program.typesystem.XBs;
import utils.Streams;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Inlines all boolean `.if` and `?` method calls as ternaries if the ThenElse/1 argument is a
/// literal and does not capture itself. The inlining is shallow.
///
/// It is correct because the `ThenElse` is a `CreateObj` written at the call site: the capture
/// names of the arm are then the same `MIR.X` objects as the ones in the enclosing scope, so an
/// inlined arm body reads bindings that exist. See {@link DevirtualiseByRTA} for the general case,
/// where the receiver comes from anywhere and only the receiver expression may cross the boundary.
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
    // We need to make sure that this is a canonical ThenElse literal (i.e. no extra methods and the lambda is made inline here)
    // The restriction about the lambda being created inline here allows us to assume any captures are present
    assert original.args().size() == 1;
    var thenElse_ = original.args().getFirst();
    if (!(thenElse_ instanceof MIR.CreateObj thenElse)) { return Optional.empty(); }
    var thenElseDec = this.magic.p().of(thenElse.t().name().orElseThrow());
    var thenElseMs = this.magic.p().meths(XBs.empty(), thenElse.t().mdf(), thenElseDec.toIT(), 0);
    // just checking size here is fine because it must have .then and .else, so less than 2 is impossible and >2 is not covered by this optimisation
    assert thenElseMs.size() >= 2;
    if (thenElseMs.size() != 2) { return Optional.empty(); }

    var then = thenElse.meths().stream().filter(m->m.sig().name().equals(new Id.MethName(".then", 0))).findFirst().orElseThrow();
    var else_ = thenElse.meths().stream().filter(m->m.sig().name().equals(new Id.MethName(".else", 0))).findFirst().orElseThrow();
    if (then.capturesSelf() || else_.capturesSelf()) {
      return Optional.empty();
    }

    // This optimisation is shallow, it will only inline one level of a .if
    // If there is a nested .if, that nested if could also be optimised, but it will not be inlined into this one.
    return Optional.of(new MIR.BoolExpr(original, original.recv(), then.fName().orElseThrow(), else_.fName().orElseThrow()));
  }
}
