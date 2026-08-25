package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import magic.Magic;

/// Turns the recursive call of a method into a {@link MIR.DirectCall}.
///
/// A call on `this` to the same method the body implements always reaches this body. A type
/// below the declaring type either writes its own body for the method, and then its wrapper
/// names that body and control never arrives here, or it inherits this one, and then this body
/// is what its wrapper calls. Either way the receiver that runs this body runs it again.
///
/// So the call needs no test and no analysis. The receiver passes through as it stands, which
/// keeps the vtable of the value for any other call the body makes.
public class DirectSelfRecursion implements MIRCloneVisitor {
  private String selfName;
  private Id.DecId owner;
  private Id.MethName method;
  private id.Mdf mdf;
  private int rewritten = 0;

  public int rewrittenCalls() { return rewritten; }

  @Override public MIR.Fun visitFun(MIR.Fun fun) {
    var savedName = selfName;
    var savedOwner = owner;
    var savedMethod = method;
    var savedMdf = mdf;
    var selfIdx = fun.name().m().num();
    if (selfIdx < fun.args().size() && !Magic.allMagicDecs().contains(fun.name().d())) {
      selfName = fun.args().get(selfIdx).name();
      owner = fun.name().d();
      method = fun.name().m();
      mdf = fun.name().mdf();
    } else {
      selfName = null;
    }
    var res = MIRCloneVisitor.super.visitFun(fun);
    selfName = savedName;
    owner = savedOwner;
    method = savedMethod;
    mdf = savedMdf;
    return res;
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    var visited = MIRCloneVisitor.super.visitMCall(call, checkMagic);
    if (!(visited instanceof MIR.MCall rewrittenCall)) { return visited; }
    if (selfName == null) { return visited; }
    if (!isPlain(rewrittenCall)) { return visited; }
    if (!rewrittenCall.name().equals(method) || rewrittenCall.mdf() != mdf) { return visited; }
    if (!isSelf(rewrittenCall.recv())) { return visited; }
    rewritten++;
    return new MIR.DirectCall(rewrittenCall, owner);
  }

  /// Whether the expression names the receiver of the method the call sits in. A `Box` is
  /// transparent: it changes how the value is carried, not which value it is.
  private boolean isSelf(MIR.E recv) {
    if (recv instanceof MIR.Box box) { return isSelf(box.inner()); }
    return recv instanceof MIR.X x && x.name().equals(selfName);
  }

  private boolean isPlain(MIR.MCall call) {
    return call.variant().contains(MIR.MCall.CallVariant.Standard)
      || call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable);
  }
}
