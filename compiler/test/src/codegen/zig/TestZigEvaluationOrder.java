package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// E2E programs for the evaluation order of call operands. Operands run from left to right, also
/// when one of them is a primitive operation. A primitive operation that fails, or that changes
/// an object, takes effect before every operand to its right.
public class TestZigEvaluationOrder {
  @Test void aPrimitiveOperationOperandRunsBeforeTheArgumentsToItsRight() {
    // Fearless evaluates a receiver and its arguments from left to right. `Next#` stores the
    // increment and returns the value from before the store. The first argument of `Pair#` is a
    // primitive operation (`0 + ...`) whose operand has a side effect. Left to right, `a` is 0 and
    // `b` is 1, so the result is 1. The reverse order gives 10.
    okBase(new Res("1", "", 0), """
      package test
      Next:{ #(v: mut Var[Nat]): Nat -> v.swap((v.get) + 1) }
      Pair:{ #(a: Nat, b: Nat): Nat -> (a * 10) + b }
      Probe:{ #(v: mut Var[Nat]): Nat -> Pair#(0 + (Next#v), Next#v) }
      Test:Main{sys -> sys.io.println(Probe#(Vars#0).str)}
      """, Base.mutBaseAliases);
  }

  @Test void aPrimitiveOperationThatPanicsRunsBeforeTheArgumentsToItsRight() {
    // `1 / d` panics when `d` is 0. The panic is the first event, so the prints in the second and
    // third arguments must not run and stdout stays empty. The divisor is a parameter, so it is
    // not a constant at the call. Both prints use the `mut` value `sys`, so no operand can run in
    // parallel with another and the operands run in sequence.
    okBase(new Res("", "Program crashed with: / by zero[###]", 1), """
      package test
      Pair:{ #(a: Nat, b: Void, c: Void): Void -> c }
      Probe:{ #(sys: mut System, d: Nat): Void -> Pair#(1 / d, sys.io.println("second"), sys.io.println("third")) }
      Test:Main{sys -> Probe#(sys, 0)}
      """, Base.mutBaseAliases);
  }

  @Test void aPrimitiveOperationThatReportsAnErrorRunsBeforeTheArgumentsToItsRight() {
    // `n.assertEq(1)` reports an error when `n` is not 1. The error comes from Fearless code, not
    // from a runtime panic. It is the first event, so the prints in the second and third
    // arguments must not run and stdout stays empty. Both prints use the `mut` value `sys`, so no
    // operand can run in parallel with another and the operands run in sequence.
    okBase(new Res("", "Program crashed with: Expected: 2[###]Actual: 1[###]", 1), """
      package test
      Pair:{ #(a: Void, b: Void, c: Void): Void -> c }
      Probe:{ #(sys: mut System, n: Nat): Void -> Pair#(n.assertEq(1), sys.io.println("second"), sys.io.println("third")) }
      Test:Main{sys -> Probe#(sys, 2)}
      """, Base.mutBaseAliases);
  }

  @Test void aPrimitiveOperationThatChangesAnObjectRunsBeforeTheArgumentsToItsRight() {
    // `n.hash(h)` changes `h`, and `h.compute` reads `h`. A new hasher holds 1, and hashing 5
    // gives 1 * 31 + 5 = 36. The hash must run before the read, so the result is 36. The reverse
    // order gives 1. Both operands use `h`, so no operand can run in parallel with another and the
    // operands run in sequence.
    okBase(new Res("36", "", 0), """
      package test
      alias base.CheapHash as H,
      Pair:{ #(a: Void, b: Nat): Nat -> b }
      Probe:{ #(n: Nat, h: mut H): Nat -> Pair#(n.hash(h), h.compute) }
      Test:Main{sys -> sys.io.println(Probe#(5, mut H).str)}
      """, Base.mutBaseAliases);
  }
}
