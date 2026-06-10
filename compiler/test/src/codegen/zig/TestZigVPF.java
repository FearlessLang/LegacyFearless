package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/** M3: VPF join-point behaviour under forced promotion. RSum is a
 * divide-and-conquer range sum whose combiner {@code this#(lo, mid) + (this#(mid, hi))}
 * is VPF-parallelisable (two recursive sub-calls). Forcing a low promotion
 * threshold via {@code okBase(16, ...)} makes thieves steal subtrees, so an
 * {@code Error!} thrown deep in a stolen branch must travel back through a
 * work-stealing join (obligation wait) and re-unwind on the waiter's fiber.
 * The leaf {@code lo == 127} throws only when the range includes 127. */
public class TestZigVPF {
  // Control: range excludes 127, so no throw. Confirms forced promotion still
  // produces the correct sum (0..126 = 8001).
  @Test void vpfSumUnderForcedPromotion() { okBase(16, new Res("8001", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 127).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  // Deterministic Error! thrown in a (likely stolen) branch, caught by Try and
  // routed to the .info arm.
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

  // Same Error! left uncaught: it unwinds past the VPF joins to the top-level
  // boundary and crashes.
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
}
