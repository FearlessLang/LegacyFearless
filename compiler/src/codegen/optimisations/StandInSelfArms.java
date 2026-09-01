package codegen.optimisations;

import codegen.MIR;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/// The functions codegen reaches as an arm of a branch that never builds the literal the arm
/// belongs to, and the parameter slot that stands in for its receiver.
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
public final class StandInSelfArms {
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
    switch (e) {
      case MIR.X ignored -> {}
      // The methods of a literal are funs of their own, so they are walked as such.
      case MIR.CreateObj ignored -> {}
      case MIR.Box box -> walk(box.inner());
      // Both views of a block: `original` is the expression it came from and `stmts` the
      // statements codegen emits, and an arm can sit in either one alone.
      case MIR.Block block -> {
        walk(block.original());
        block.stmts().forEach(stmt -> walk(stmt.e()));
      }
      case MIR.BoolExpr b -> {
        walk(b.condition());
        walkArm(b.then(), 0);
        walkArm(b.else_(), 0);
      }
      case MIR.SumMatch s -> {
        walk(s.receiver());
        s.arms().forEach(arm -> walkArm(arm.arm(), arm.arm().m().num()));
      }
      case MIR.MCall call -> {
        walk(call.recv());
        call.args().forEach(this::walk);
      }
      case MIR.UpdatableListAsIdFnCall u -> walk(u.e());
      case MIR.GuardedCall g -> walk(g.original());
      case MIR.DirectCall d -> walk(d.original());
      case MIR.StaticCall s -> s.args().forEach(this::walk);
    }
  }
}
