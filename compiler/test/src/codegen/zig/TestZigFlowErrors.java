package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/**
 * Error propagation through flows, ported from flows/TestFlowSemantics.java (Java
 * backend), with FeaRT-adjusted expectations: crash messages are UNQUOTED
 * (`.msg`, since JSON `.str` is unimplemented in this backend). Par/DP variants
 * force VPF promotion via okBase(16, ...) so thieves actually steal chunks and
 * the work-stealing join is exercised; Seq variants use the default high
 * threshold (sequential).
 *
 * Golden rule: a deterministic error in a flow is observably the same sequential
 * vs data-parallel — the flow-order-FIRST error wins; errors after a stop
 * (`.limit`) are ignored. The tail covers stack overflow raised inside a flow.
 */
public class TestZigFlowErrors {
  @Test void throwInAFlowBeforeStopSeq() { okBase(new Res("", "Program crashed with: 2[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[mut Nat](mut 1, mut 2, mut 3)
        .map{x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat * 10}
          }
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }
  @Test void throwInAFlowBeforeStopPar() { okBase(16, new Res("", "Program crashed with: 2[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .map{x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat * 10}
          }
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }
  @Test void throwInAFlowBeforeStopDP() { okBase(16, new Res("", "Program crashed with: 2[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow.range(+1, +50).map{n->n.nat}.list.flow
        .map{x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat * 10}
          }
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  // Throw after a stop: `.limit(1)` must stop pulling, so element 2 is never
  // mapped and never throws. Result: element 1 -> 10.
  @Test void throwInAFlowAfterStopSeq() { okBase(new Res("10", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[mut Nat](mut 1, mut 2, mut 3)
        .map{x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat * 10}
          }
        .limit(1)
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }
  @Test void throwInAFlowAfterStopPar() { okBase(16, new Res("10", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .map{x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat * 10}
          }
        .limit(1)
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }
  @Test void throwInAFlowAfterStopDP() { okBase(16, new Res("10", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow.range(+1, +50).map{n->n.nat}.list.flow
        .map{x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat * 10}
          }
        .limit(1)
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  // Multiple throws: leftmost (element 2) wins over a later throw (element 3).
  @Test void throwMultiplePar() { okBase(16, new Res("", "Program crashed with: 2[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .map{x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .if {x.nat == 3} .do {Error.msg (x.str)}
          .return {x.nat * 10}
          }
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  // Actor throws (stateful -> not DP-split -> sequential). Leftmost wins.
  @Test void throwMultipleActor() { okBase(new Res("", "Program crashed with: 2[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .if {x.nat == 3} .do {Error.msg (x.str)}
          .do {next#(x.nat * 10)}
          .return {{}}
          })
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }
  @Test void throwMultipleActorFromDP() { okBase(4, new Res("", "Program crashed with: 5[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] list = {List.consumeUList(UList.withCapacity(10) + 1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10)}
      .let x = {list.flow
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .if {x.nat == 5} .do {Error.msg (x.str)}
          .if {x.nat == 7} .do {Error.msg (x.str)}
          .do {next#(x.nat * 10)}
          .return {{}}
          })
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  // Actor pushError injects a deterministic error element at position 2; the
  // later Error.msg at position 3 loses. Result: "hello".
  @Test void pushErrorAndThrowSeq() { okBase(new Res("", "Program crashed with: hello[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[mut Nat](mut 1, mut 2, mut 3)
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .if {x.nat == 2} .do {next.pushError(Infos.msg "hello")}
          .if {x.nat == 3} .do {Error.msg (x.str)}
          .do {next#(x.nat * 10)}
          .return {{}}
          })
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }
  @Test void pushErrorAndThrow() { okBase(new Res("", "Program crashed with: hello[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .if {x.nat == 2} .do {next.pushError(Infos.msg "hello")}
          .if {x.nat == 3} .do {Error.msg (x.str)}
          .do {next#(x.nat * 10)}
          .return {{}}
          })
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  @Test void dataParallelAllThrowsMustGetFirst1() { okBase(16, new Res("", "Program crashed with: 0[###]", 1), """
    package test
    Test: Main{sys -> Block#(
      Flow.range(+0, +100_000)
        .map{i -> Error.msg[Int](i.str)}
        .list
      )}
    """, Base.mutBaseAliases); }

  @Test void dataParallelAllThrowsMustGetFirst2() { okBase(4096, new Res("", "Program crashed with: 80000[###]", 1), """
    package test
    Test: Main{sys -> Block#(
      Flow.range(+0, +100_000)
        .map{i -> i == +80_000 ? {.then -> Error.msg(i.str), .else -> i}}
        .list
      )}
    """, Base.mutBaseAliases); }

  @Test void dataParallelAllThrowsMustGetFirstWithCatch() { okBase(4096, new Res("80000 <-- caught it!", "", 0), """
    package test
    Test: Main{sys -> Try#{
      Block#[List[Int],Str](Flow.range(+0, +100_000)
        .map{i -> i == +80_000 ? {.then -> Error.msg(i.str), .else -> i}}
        .list, "oh no")
      }.run{
        .ok(msg) -> sys.io.println(msg),
        .info(i) -> sys.io.println(i.msg + " <-- caught it!"),
      }}
    """, Base.mutBaseAliases); }

  // Throw inside an ordered terminal predicate (.first). Leftmost throwing
  // element wins: seq -> element +1; DP -> first element > +30, i.e. +31.
  @Test void throwInTerminalSeq() { okBase(new Res("", "Program crashed with: 1[###]", 1), """
    package test
    Test: Main{sys -> Block#(
      Flow#[mut Int](mut +1, mut +2, mut +3)
        .map{e -> e}
        .first{e -> Error.msg(e.str)}
      )}
    """, Base.mutBaseAliases); }
  @Test void throwInTerminalDP() { okBase(16, new Res("", "Program crashed with: 31[###]", 1), """
    package test
    Test: Main{sys -> Block#(
      Flow.range(+1, +500)
        .map{e -> e}
        .first{e -> e > +30 ? {
          .then -> Error.msg(e.str),
          .else -> False
          }}
      )}
    """, Base.mutBaseAliases); }

  @Test void stackOverflowInAFlowSeq() { okBase(new Res("", "Program crashed with: Stack overflowed[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[mut Nat](mut 1, mut 2, mut 3)
        .map{x->Block#
          .if {x.nat == 2} .do {StackOverflow#}
          .return {x.nat * 10}
          }
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    StackOverflow: {#[R]: R -> this#}
    """, Base.mutBaseAliases); }

  @Test void stackOverflowInAFlowPar() { okBase(2, new Res("", "Program crashed with: Stack overflowed[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let[Nat] sum = {sys.try#{Flow#[Nat](1, 2, 3)
        .map{x->Block#
          .if {x.nat == 2} .do {StackOverflow#}
          .return {x.nat * 10}
          }
        #(Flow.uSum)
        }!}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    StackOverflow: {#[R]: R -> this#}
    """, Base.mutBaseAliases); }

  // Overflow after a stop is never reached: `.limit` makes the flow stateful, so
  // it never DP-splits and element 2 (the overflow) is never pulled. Holds both
  // sequentially and under forced promotion -> deterministic "10" (a stronger
  // guarantee than the parallel Java backend, which races the limit vs the SO).
  @Test void stackOverflowAfterStopSeq() { okBase(new Res("10", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[mut Nat](mut 1, mut 2, mut 3)
        .map{x->Block#
          .if {x.nat == 2} .do {StackOverflow#}
          .return {x.nat * 10}
          }
        .limit(1)
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    StackOverflow: {#[R]: R -> this#}
    """, Base.mutBaseAliases); }
  @Test void stackOverflowAfterStopPar() { okBase(2, new Res("10", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[mut Nat](mut 1, mut 2, mut 3)
        .map{x->Block#
          .if {x.nat == 2} .do {StackOverflow#}
          .return {x.nat * 10}
          }
        .limit(1)
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    StackOverflow: {#[R]: R -> this#}
    """, Base.mutBaseAliases); }
}
