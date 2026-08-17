package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import java.util.regex.Pattern;

import static codegen.zig.RunZigProgramTests.okBase;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.RunOutput.Res;

/// The behaviour of a VPF join point under forced promotion. RSum is a divide-and-conquer range
/// sum. Its combiner {@code this#(lo, mid) + (this#(mid, hi))} has two recursive sub-calls, and
/// is thus VPF-parallelisable. The low promotion threshold of {@code okBase(16, ...)} makes the
/// thieves steal subtrees. An {@code Error!} from a stolen branch must then go back through a
/// work-stealing join, an obligation wait, and unwind again on the fiber of the waiter. The leaf
/// {@code lo == 127} throws only when the range includes 127.
public class TestZigVPF {
  // The control test. The range excludes 127, so nothing throws and forced promotion must give
  // the correct sum of 0..126.
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

  // A deterministic Error! from a branch that a thief usually steals. Try catches it and sends
  // it to the .info arm.
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

  // The same Error!, but with no Try. It unwinds through the VPF joins to the top-level boundary,
  // and the program stops.
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

  // A guard `.if` around the split, the usual form of a divide-and-conquer. `BoolIfOptimisation`
  // inlines only one level, so the split is two levels deep and its arm becomes its own
  // function. The instrumentation stays only because the code really calls that function.
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

  // The recursive case is in `.then`, not in `.else`. No code looks for a VPF call in a `.then`
  // arm, so this works only through the instrumentation of the arm.
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

  // Four `.if` levels, with non-parallelisable layers between them. Each level becomes a call in
  // turn, until the level with the split.
  @Test void vpfDeeplyNestedIf() { okBase(16, new Res("8128", "", 0), """
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

  // The de-inlining puts a stack frame between the stolen branch that unwinds and the `Try` that
  // catches it. The obligation waits must unwind through that frame.
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

  /// A correct but sequential program passes the output assertions above, with VPF or without
  /// it. This is why the nested `.if` stayed defective. Codegen emits a thief function and its
  /// locals struct only for an instrumented call, and a nested split lives in a `.then` or
  /// `.else` arm. Thus a thief that an arm owns is the proof that VPF went past the outer `.if`.
  /// The patterns match the arm-name mangling, and not a symbol with a `vpfCounter` number, so a
  /// change of the numbers does not break them.
  private static void assertInstrumented() {
    var zig = RunZigProgramTests.generatedZig("test");
    var thief = Pattern.compile("^fn \\w+_Zdot(then|else)\\w*_thief\\(", Pattern.MULTILINE);
    var locals = Pattern.compile("^const \\w+_Zdot(then|else)\\w*_Locals = ", Pattern.MULTILINE);
    assertTrue(thief.matcher(zig).find(), "no VPF thief was emitted for a nested `.if` arm:\n" + zig);
    assertTrue(locals.matcher(zig).find(), "no VPF locals struct was emitted for a nested `.if` arm:\n" + zig);
  }
}
