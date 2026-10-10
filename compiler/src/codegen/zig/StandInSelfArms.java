package codegen.zig;

import codegen.MIR;
import visitors.MIRVisitor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/// A map from Mearless functions used as conditional arms to the index of the receiver parameter,
/// which takes a stand-in singleton when `BoolIf`/`SumMatch` call the arm without building its
/// matcher literal.
///
/// `BoolIfOptimisation` replaces a call to `Bool.if` with a test on the condition's vtable and a
/// call to the selected arm, and `SumMatchOptimisation` does the same for a matcher literal. No
/// arm reads its receiver, so the call site passes a singleton in that position instead of
/// building the object the arm literal stands for.
///
/// The receiver that arrives is therefore a singleton, while the parameter's static type is the
/// arm literal, which captures and is thus heap or transient. Reference-count code chosen from
/// that static type does not hold for the value that arrives, so the receiver slot of an arm
/// needs the general storage-mode dispatch. Every other slot keeps its specialised form.
///
/// The slot is the arm's own arity, because a function takes its declared parameters, then its
/// receiver, then its captures. An arm of a `BoolExpr` declares none and holds its receiver
/// first; an arm such as `.some(x)` holds one parameter in front of it.
public final class StandInSelfArms implements MIRVisitor<Void> {
  private final Map<MIR.FName, MIR.Fun> funMap;
  private final Map<MIR.FName, Integer> selfSlots = new HashMap<>();
  private final Set<MIR.FName> walked = new HashSet<>();

  public StandInSelfArms(MIR.Program p) {
    this.funMap = p.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f -> f, (a, b) -> a, HashMap::new));
    funMap.values().forEach(f -> walk(f.body()));
  }

  /// The parameter slot a branch fills with a stand-in singleton, or empty where `f` is never
  /// reached as such an arm.
  public OptionalInt selfSlot(MIR.FName f) {
    var slot = selfSlots.get(f);
    return slot == null ? OptionalInt.empty() : OptionalInt.of(slot);
  }

  private void walkArm(MIR.FName name, int selfSlot) {
    // A function reached as an arm of two branches keeps the widest reading. The slot is fixed
    // by the arm's own arity, so two readings of one function agree.
    selfSlots.merge(name, selfSlot, Math::min);
    if (!walked.add(name)) { return; }
    var fun = funMap.get(name);
    if (fun != null) { walk(fun.body()); }
  }

  private void walk(MIR.E e) {
    e.accept(this, true);
  }

  @Override public Void visitX(MIR.X x, boolean checkMagic) {
    return null;
  }

  // The methods of a literal are visited as functions.
  @Override public Void visitCreateObj(MIR.CreateObj obj, boolean checkMagic) {
    return null;
  }

  @Override public Void visitBlockExpr(MIR.Block block, boolean checkMagic) {
    walk(block.original());
    block.stmts().forEach(stmt -> walk(stmt.e()));
    return null;
  }

  @Override public Void visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    walk(expr.condition());
    walkArm(expr.then(), 0);
    walkArm(expr.else_(), 0);
    return null;
  }

  @Override public Void visitSumMatch(MIR.SumMatch expr, boolean checkMagic) {
    walk(expr.receiver());
    expr.arms().forEach(arm -> walkArm(arm.arm(), arm.arm().m().num()));
    return null;
  }

  @Override public Void visitMCall(MIR.MCall call, boolean checkMagic) {
    walk(call.recv());
    call.args().forEach(this::walk);
    return null;
  }
}
