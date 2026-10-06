package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// E2E programs for `.match` calls on sealed sums that the backend rewrites into a switch.
public class TestZigSumMatch {
  @Test void aRewrittenMatchForwardsTheReceiverToTheArm() {
    // The backend rewrites a `.match` call on a sealed sum into a switch over the variants. It
    // forwards to the arm what each variant passes to its matcher method. Here each variant
    // passes `this`, the receiver itself. `A` is a singleton with no captures, so `this` is not a
    // capture of `A`.
    okBase(new Res("a", "", 0), """
      package test
      Choice:Sealed{ .match[R:*](m: mut Match[R]): R, .str: Str }
      Match[R:*]:{ mut .a(c: Choice): R, mut .b(c: Choice): R }
      A:Choice{ .match(m) -> m.a(this), .str -> "a" }
      B:Choice{ .match(m) -> m.b(this), .str -> "b" }
      Test:Main{sys -> sys.io.println(A.match[Str]{ .a(c) -> c.str, .b(c) -> c.str })}
      """, Base.mutBaseAliases);
  }

  @Test void aMatcherWithTwoMethodsOfOneNameRunsTheOneThatTheVariantCalls() {
    // A matcher can write two methods with the same name and arity when their receiver
    // capabilities are different. `OptMatch` declares `mut .some`, and `Opts#` writes a variant
    // that calls `.some` on a `mut` matcher. So the `mut .some` must run, in whatever order the
    // matcher writes the two methods.
    okBase(new Res("10\n10", "", 0), """
      package test
      ReadFirst:{ #(o: mut Opt[Nat]): Nat -> o.match[Nat]{
        read .some(x: Nat): Nat -> 20,
        mut .some(x: Nat): Nat -> 10,
        mut .empty: Nat -> 0
        } }
      MutFirst:{ #(o: mut Opt[Nat]): Nat -> o.match[Nat]{
        mut .some(x: Nat): Nat -> 10,
        read .some(x: Nat): Nat -> 20,
        mut .empty: Nat -> 0
        } }
      Test:Main{sys -> Block#
        .do{sys.io.println(ReadFirst#(Opts#[Nat](5)).str)}
        .return{sys.io.println(MutFirst#(Opts#[Nat](5)).str)}}
      """, Base.mutBaseAliases);
  }
}
