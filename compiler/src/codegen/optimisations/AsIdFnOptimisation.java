package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import id.Mdf;
import magic.Magic;
import magic.MagicImpls;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AsIdFnOptimisation implements MIRCloneVisitor {
  private final MagicImpls<?> magic;
  private Map<MIR.FName, MIR.Fun> funs;
  public AsIdFnOptimisation(MagicImpls<?> magic) {
    this.magic = magic;
  }

  @Override public MIR.Package visitPackage(MIR.Package pkg) {
    this.funs = pkg.funs().stream().collect(Collectors.toMap(MIR.Fun::name, Function.identity()));
    return MIRCloneVisitor.super.visitPackage(pkg);
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    if (!call.name().equals(new Id.MethName(Optional.of(Mdf.read), ".as", 1))) {
      return MIRCloneVisitor.super.visitMCall(call, checkMagic);
    }

    if (magic.isMagic(Magic.UList, call.recv())) {
      if (isIdentityFunction(call.args().getFirst())) {
        return new MIR.UpdatableListAsIdFnCall(call);
      }
    }

    if (magic.isMagic(Magic.FList, call.recv())) {
      if (isIdentityFunction(call.args().getFirst())) {
        return call.recv();
      }
    }
    return MIRCloneVisitor.super.visitMCall(call, checkMagic);
  }

  private boolean isIdentityFunction(MIR.E e) {
    if (!(e instanceof MIR.CreateObj k)) { return false; }
    if (k.meths().size() != 1) { return false; }
    var meth = k.meths().getFirst();
    if (meth.sig().xs().size() != 1) { return false; }
    if (meth.fName().isEmpty()) { return false; }
    if (!meth.sig().name().equals(new Id.MethName("#", 1))) { return false; }
    var impl = funs.get(meth.fName().get());
    assert impl != null;
    var bodyIsArg = impl.body().equals(meth.sig().xs().getFirst());
    return bodyIsArg;
  }
}
