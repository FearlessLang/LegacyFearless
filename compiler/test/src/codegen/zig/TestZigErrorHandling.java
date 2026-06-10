package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/** Error handling outside of flows: uncaught crashes, stack traces, Try/catch,
 * non-deterministic errors (div-by-zero), Abort, and standalone stack overflow.
 * Errors arising inside flows live in {@link TestZigFlowErrors}. */
public class TestZigErrorHandling {
  @Test void errorUncaught() { okBase(new Res("", "Program crashed with: oh no[###]", 1), """
    package test
    Test:Main {sys -> Error.msg[Void] "oh no"}
    """, Base.mutBaseAliases); }

  @Test void traceSimpleDeterministic() { okBase(new Res("", "Program crashed with: oh no[###]#0 test.Test imm #/1[###]", 1), """
    package test
    Test:Main {sys -> Error.msg[Void] "oh no"}
    """, Base.mutBaseAliases); }

  @Test void traceMultiFrame() { okBase(new Res("", "Program crashed with: boom[###]#0 test.Helper imm .boom/0[###]#1 test.Test imm #/1[###]", 1), """
    package test
    Test:Main {sys -> Helper.boom}
    Helper: {.boom: Void -> Error.msg[Void] "boom"}
    """, Base.mutBaseAliases); }

  @Test void traceCrossFiberBoundary() { okBase(new Res("", "Program crashed with: / by zero[###]--- fibre boundary ---[###]#1 test.Test imm #/1[###]", 1), """
    package test
    Test:Main{sys ->
      sys.io.println(Try#[Int]{+12 / +0}.run{
        .ok(n) -> n.str,
        .info(i) -> i.msg,
        })
      }
    """, Base.mutBaseAliases); }

  @Test void tryCatchExplicitError() { okBase(new Res("Sad", "", 0), """
    package test
    Test:Main{s ->
      s.io.println(Try#[Str]{Error.msg "Sad"}.run{
        .ok(res) -> res,
        .info(err) -> err.msg,
        })
      }
    """, Base.mutBaseAliases); }

  @Test void tryCatchNothing() { okBase(new Res("Happy", "", 0), """
    package test
    Test:Main{s ->
      s.io.println(Try#[Str]{"Happy"}.run{
        .ok(res) -> res,
        .info(err) -> err.msg,
        })
      }
    """, Base.mutBaseAliases); }

  @Test void tryCatchViaActionDefaults() { okBase(new Res("", "oh no", 0), """
    package test
    Test:Main {sys -> sys.io.printlnErr(Try#[Int]{Error.msg "oh no"}.info!.msg)}
    """, Base.mutBaseAliases); }

  @Test void capTryCatchesNd() { okBase(new Res("", "/ by zero", 0), """
    package test
    Test:Main {sys -> sys.io.printlnErr(sys.try#[Int]{+12 / +0}.info!.msg)}
    """, Base.mutBaseAliases); }

  @Test void plainTryDoesNotCatchNd() { okBase(new Res("", "Program crashed with: / by zero[###]", 1), """
    package test
    Test:Main{sys ->
      sys.io.println(Try#[Int]{+12 / +0}.run{
        .ok(n) -> n.str,
        .info(i) -> i.msg,
        })
      }
    """, Base.mutBaseAliases); }

  @Test void abortCrashes() { okBase(new Res("", "Program aborted[###]", 1), """
    package test
    Test:Main {sys -> base.Abort![Void]}
    """, Base.mutBaseAliases); }

  @Test void stackOverflowUncaught() { okBase(new Res("", "Program crashed with: Stack overflowed[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .do {StackOverflow#}
      .return {{}}
      }
    StackOverflow: {#[R]: R -> this#}
    """, Base.mutBaseAliases); }

  @Test void stackOverflowCaughtByCapTry() { okBase(new Res("", "Stack overflowed", 0), """
    package test
    Test:Main {sys -> sys.io.printlnErr(sys.try#[Nat]{StackOverflow#}.info!.msg)}
    StackOverflow: {#[R]: R -> this#}
    """, Base.mutBaseAliases); }
}
