package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import id.Mdf;
import magic.Magic;
import magic.MagicImpls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/// Turns a short-circuiting boolean operator into the same branch `.if` becomes.
///
/// Every `&&` and `||` implementation in `base` is `.if`-shaped, so the call costs two dispatches
/// where a branch on the receiver's vtable does the same work. The thunk is a literal at the call
/// site, so its `#` serves as one arm directly. The other arm is a constant and needs a function
/// to hold it.
///
/// Correct on the same grounds as {@link BoolIfOptimisation}: the thunk is a `CreateObj` written
/// at the call site, so its captures are the enclosing scope's own names and an inlined body
/// reads bindings that exist.
public class BoolShortCircuitOptimisation implements MIRCloneVisitor {
  private static final Id.MethName APPLY = new Id.MethName("#", 0);
  private static final Id.DecId TRUE = new Id.DecId("base.True", 0);
  private static final Id.DecId FALSE = new Id.DecId("base.False", 0);
  private static final Id.MethName TRUE_ARM = new Id.MethName(".boolConstTrue", 0);
  private static final Id.MethName FALSE_ARM = new Id.MethName(".boolConstFalse", 0);

  private final MagicImpls<?> magic;
  /// The constant arms made for the package being visited.
  private List<MIR.Fun> added;
  private String pkgName;

  public BoolShortCircuitOptimisation(MagicImpls<?> magic) { this.magic = magic; }

  @Override public MIR.Package visitPackage(MIR.Package pkg) {
    this.pkgName = pkg.name();
    this.added = new ArrayList<>();
    var visited = MIRCloneVisitor.super.visitPackage(pkg);
    if (added.isEmpty()) { return visited; }
    var funs = new ArrayList<>(visited.funs());
    funs.addAll(added);
    return new MIR.Package(visited.name(), visited.defs(), List.copyOf(funs));
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    if (magic.isMagic(Magic.Bool, call.recv())) {
      var res = shortCircuit(call);
      if (res.isPresent()) { return res.get(); }
    }
    return MIRCloneVisitor.super.visitMCall(call, checkMagic);
  }

  private Optional<MIR.BoolExpr> shortCircuit(MIR.MCall original) {
    boolean isAnd = original.name().equals(new Id.MethName("&&", 1));
    boolean isOr = original.name().equals(new Id.MethName("||", 1));
    if (!isAnd && !isOr) { return Optional.empty(); }
    // A VPF promotion site: a branch takes the combining call away from the instrumentation
    // that looks for it.
    if (original.variant().contains(MIR.MCall.CallVariant.VPFParallelisable)) {
      return Optional.empty();
    }
    if (original.args().size() != 1) { return Optional.empty(); }
    if (!(original.args().getFirst() instanceof MIR.CreateObj thunk)) { return Optional.empty(); }

    // Only a bare thunk: one method, which is the `#` the operator would have called. Anything
    // else is a value with behaviour of its own.
    if (thunk.meths().size() != 1) { return Optional.empty(); }
    var apply = thunk.meths().getFirst();
    if (!apply.sig().name().equals(APPLY)) { return Optional.empty(); }
    if (apply.capturesSelf()) { return Optional.empty(); }
    var applyName = apply.fName();
    if (applyName.isEmpty()) { return Optional.empty(); }

    // `True.&&(b) -> b#` and `False.&&(b) -> this`; `||` is the mirror.
    var constant = constantFun(isAnd ? FALSE : TRUE, isAnd ? FALSE_ARM : TRUE_ARM);
    var then = isAnd ? applyName.get() : constant;
    var else_ = isAnd ? constant : applyName.get();
    return Optional.of(new MIR.BoolExpr(original, original.recv(), then, else_));
  }

  /// A function with no parameters whose body is the boolean singleton `value`. The name carries
  /// the package, so no two packages write the same one, and it belongs to no type the package
  /// declares, so code generation never emits it: only the arm inlining reads its body.
  private MIR.FName constantFun(Id.DecId value, Id.MethName arm) {
    var owner = new Id.DecId(pkgName + ".$BoolConst", 0);
    var name = new MIR.FName(owner, arm, false, Mdf.imm);
    if (added.stream().anyMatch(f -> f.name().equals(name))) { return name; }
    var t = new MIR.MT.Plain(Mdf.imm, value);
    var body = new MIR.CreateObj(t, "$boolConst", List.of(), List.of(), new TreeSet<>());
    added.add(new MIR.Fun(name, List.of(), t, body));
    return name;
  }
}
