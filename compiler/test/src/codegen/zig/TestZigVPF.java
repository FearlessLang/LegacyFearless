package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import java.util.regex.Pattern;

import static codegen.zig.RunZigProgramTests.okBase;
import static codegen.zig.RunZigProgramTests.okBaseNoVpf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.RunOutput.Res;

/// VPF join points under forced promotion. {@code okBase(16, ...)} makes thieves steal subtrees
/// of RSum. An {@code Error!} from a stolen branch must unwind again on the fiber of the waiter.
/// Leaf 127 throws.
public class TestZigVPF {
  @Test void vpfSumWithoutTheThrowingLeafUnderForcedPromotion() { okBase(16, new Res("8001", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 127).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  @Test void vpfErrorCaughtUnderForcedPromotion() { okBase(16, new Res("boom", "", 0), """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{RSum#(0, 128)}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  @Test void vpfErrorUncaughtUnderForcedPromotion() { okBase(16, new Res("", "Program crashed with: boom[###]", 1), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  // `BoolIfOptimisation` inlines one level only, so the split is in an arm function. VPF must
  // instrument that arm.
  @Test void vpfNestedIf() { okBase(16, new Res("8128", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> lo >= hi ? {
        .then -> 0,
        .else -> (hi - lo) == 1 ? {
          .then -> lo,
          .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
          }
        }
      }
    """, Base.mutBaseAliases); assertInstrumented(); }

  @Test void vpfCallInThenBranch() { okBase(16, new Res("8128", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) > 1 ? {
        .then -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi)),
        .else -> lo
        }
      }
    """, Base.mutBaseAliases); assertInstrumented(); }

  @Test void vpfFourNestedIfsWithNonParallelisableLayersBetween() { okBase(16, new Res("8128", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> lo >= hi ? {
        .then -> 0,
        .else -> (hi - lo) == 1 ? {
          .then -> lo,
          .else -> hi > 100000 ? {
            .then -> 0,
            .else -> lo > 100000 ? {
              .then -> 0,
              .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
              }
            }
          }
        }
      }
    """, Base.mutBaseAliases); assertInstrumented(); }

  // An arm frame is between the stolen branch and the `Try`. The unwind must go through it.
  @Test void vpfNestedIfErrorCaught() { okBase(16, new Res("boom", "", 0), """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{RSum#(0, 128)}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> lo >= hi ? {
        .then -> 0,
        .else -> (hi - lo) == 1 ? {
          .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
          .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
          }
        }
      }
    """, Base.mutBaseAliases); }

  // The leftmost failure wins. Leaves 3 and 100 throw in different subtrees, so a thief usually
  // runs the two failures in parallel.
  @Test void vpfLeftmostErrorWins() { okBase(16, new Res("left", "", 0), """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{RSum#(0, 128)}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 3 ? {
          .then -> Error.msg[Nat] "left",
          .else -> lo == 100 ? { .then -> Error.msg[Nat] "right", .else -> lo }
          },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  // As above, for a receiver that `SumMatchOptimisation` rewrote. Receiver and argument throw.
  private static final String SUM_MATCH_BOTH_THROW = """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{Both#}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    Choice:Sealed{ .match[R:*](m: mut ChoiceMatch[R]): R }
    ChoiceMatch[R:*]:{ mut .a: R, mut .b: R }
    A:Choice{ .match(m) -> m.a }
    B:Choice{ .match(m) -> m.b }
    Boom:{ #: Nat -> Error.msg[Nat] "right" }
    Both:{ #: Nat -> A.match[Nat]{ .a -> Error.msg[Nat] "left", .b -> 0 } + (Boom#) }
    """;

  @Test void vpfSumMatchReceiverKeepsLeftmostError() {
    okBase(16, new Res("left", "", 0), SUM_MATCH_BOTH_THROW, Base.mutBaseAliases);
  }

  @Test void sumMatchReceiverKeepsLeftmostErrorWithoutVpf() {
    okBaseNoVpf(new Res("left", "", 0), SUM_MATCH_BOTH_THROW, Base.mutBaseAliases);
  }

  @Test void vpfSumMatchArgumentLosesToReceiverError() {
    okBase(16, new Res("left", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Try#[Nat]{Both#}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        })}
      Choice:Sealed{ .match[R:*](m: mut ChoiceMatch[R]): R }
      ChoiceMatch[R:*]:{ mut .a: R, mut .b: R }
      A:Choice{ .match(m) -> m.a }
      B:Choice{ .match(m) -> m.b }
      Boom:{ #: Nat -> Error.msg[Nat] "left" }
      Both:{ #: Nat -> Boom# + (A.match[Nat]{ .a -> Error.msg[Nat] "right", .b -> 0 }) }
      """, Base.mutBaseAliases);
    assertMatchArmInThief();
  }

  /// Output cannot show if a thief takes the rewritten matcher or the victim runs it early. A
  /// thief function must call a match arm of `ChoiceMatch` (mangled `Zdota`, `Zdotb`).
  private static void assertMatchArmInThief() {
    var zig = RunZigProgramTests.generatedZig("test");
    var thief = Pattern.compile("^fn \\w+_thief\\(.*?^\\}$", Pattern.MULTILINE | Pattern.DOTALL);
    var matcher = thief.matcher(zig);
    while (matcher.find()) {
      if (matcher.group().contains("Zdota")) { return; }
    }
    throw new AssertionError("no thief function computes a match arm:\n" + zig);
  }

  /// A sequential program passes the output assertions. A thief and `_Locals` that an arm owns
  /// show that VPF instrumented the arm. The patterns do not match `vpfCounter` numbers, which
  /// are not stable.
  private static void assertInstrumented() {
    var zig = RunZigProgramTests.generatedZig("test");
    var thief = Pattern.compile("^fn \\w+_Zdot(then|else)\\w*_thief\\(", Pattern.MULTILINE);
    var locals = Pattern.compile("^const \\w+_Zdot(then|else)\\w*_Locals = ", Pattern.MULTILINE);
    assertTrue(thief.matcher(zig).find(), "no VPF thief was emitted for a nested `.if` arm:\n" + zig);
    assertTrue(locals.matcher(zig).find(), "no VPF locals struct was emitted for a nested `.if` arm:\n" + zig);
  }
}
