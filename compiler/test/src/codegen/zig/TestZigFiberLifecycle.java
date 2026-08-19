package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// Stresses the fiber lifecycle in the scheduler, not any language feature. A promotion
/// threshold of 1 promotes at nearly every frame, so a wide divide-and-conquer makes many
/// thousands of thief fibers, each parking its parent on an obligation and waking it again.
/// That park/fulfill pair is the window where a fiber can be enqueued while it still runs,
/// which is what exercises the resume gate and the destroy after it.
///
/// Codegen tests build the runtime in Debug ({@code ZigBuildOpts.forTests}), so runtime safety
/// is on and a scheduler abort shows as a stderr and exit-code mismatch against {@code Res}.
public class TestZigFiberLifecycle {
  // Sum of 0..511 = 130816.
  @Test void manyThievesPlainSplit() { okBase(1, new Res("130816", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 512).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo,
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  // The split sits under a guard `.if`, so its arm body becomes its own thief function.
  @Test void manyThievesNestedIf() { okBase(1, new Res("130816", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 512).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> lo >= hi ? {
        .then -> 0,
        .else -> (hi - lo) == 1 ? {
          .then -> lo,
          .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
          }
        }
      }
    """, Base.mutBaseAliases); }

  // Repeated top-level rounds, so the pool tears fibers down and rebuilds them many times
  // over rather than in one burst. Sum of 0..255 = 32640, four times = 130560.
  @Test void repeatedRounds() { okBase(1, new Res("130560", "", 0), """
    package test
    Test:Main {sys -> sys.io.println((RSum#(0, 256) + (RSum#(0, 256)) + (RSum#(0, 256)) + (RSum#(0, 256))).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo,
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }
}
