package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// Concurrent user-facing writes contend the per-stream FiberMutex. `io.iso`
/// mints shareable IO handles; VPF (forced via okBase(16, ...)) evaluates the two
/// operands of `+` in parallel, so leaves of the recursion call `println` from
/// fibres on different worker threads. All leaves print the same line, so the
/// expected stdout is order-independent; a torn/interleaved write would corrupt a
/// line and break the match (proving line-atomicity), and completing rather than
/// hanging proves the acquire->park->handoff + write park->resume cycle.
public class TestZigIoConcurrency {
  /// 7 levels of `left + right` recursion = 2^7 leaves, each printing this line;
  /// the outer `println` then emits the sum (128), deterministically last.
  static final String LINE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  static final String EXPECTED = (LINE + "\n").repeat(128) + "128";

  @Test void concurrentPrintlnLinesStayAtomic() { okBase(4, new Res(EXPECTED, "", 0), """
    package test
    Test:Main {sys -> sys.io.println(PT#(sys.io, 7).str)}
    PT: {
      #(io: mut IO, n: Nat): Nat -> n == 0 ? {
          .then -> Block#(io.println("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"), 1),
          .else -> this.fork(IOFactorys#(io.iso), IOFactorys#(io.iso), n),
          },

      .fork(isoF1: iso MF[iso IO], isoF2: iso MF[iso IO], n: Nat): Nat -> this#(isoF1#, n - 1) + (this#(isoF2#, n - 1)),
      }
    
    IOFactorys: {#(io: mut IO): mut MF[iso IO] -> {io.iso}}
    """, Base.mutBaseAliases); }
}
