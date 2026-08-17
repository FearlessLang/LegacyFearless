package flows;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import utils.Base;

import static codegen.java.RunJavaProgramTests.ok;
import static utils.RunOutput.Res;

public class TestFlowSemantics {
  @Test void flowSum() {ok(new Res("60", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[mut Flow[Nat]] x = {Flow#[Nat](1,2,3).map{x->x * 10}}
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Test void flowReuse() {ok(new Res("", "Program crashed with: \"This flow cannot be reused. Consider collecting it to a list first.\"[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let[mut Flow[Nat]] x = {Flow#[Nat](1,2,3).map{x->x * 10}}
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut Flow[Nat]] bigSum = {x.map{y->y * 10}}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .do {io.println(bigSum#(Flow.uSum).str)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  /*
   * Fearless errors in flow before a stop are propagated. Fearless errors after the "stop" are ignored.
   */
  @Test void throwInAFlowBeforeStopPar() {ok(new Res("", "Program crashed with: \"2\"[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @Test void throwInAFlowAfterStopPar() {ok(new Res("10", "", 0), """
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
    """, Base.mutBaseAliases);}
  @Test void throwMultiplePar() {ok(new Res("", "Program crashed with: \"2\"[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @Test void throwMultipleActor() {ok(new Res("", "Program crashed with: \"2\"[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @Test void throwMultipleActorFromDP() {ok(new Res("", "Program crashed with: \"5\"[###]", 1), """
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
    """, Base.mutBaseAliases);}

  @Test void throwInAFlowBeforeStopSeq() {ok(new Res("", "Program crashed with: \"2\"[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @Test void throwInAFlowAfterStopSeq() {ok(new Res("10", "", 0), """
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
    """, Base.mutBaseAliases);}

  @Test void throwInAFlowBeforeStopDP() {ok(new Res("", "Program crashed with: \"2\"[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @Test void throwInAFlowAfterStopDP() {ok(new Res("10", "", 0), """
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
    """, Base.mutBaseAliases);}

  /*
   * Non-deterministic errors bubble up, even if the flow isn't listening anymore because they're only catchable
   * in Fearless code with a capability anyway.
   */
  @Disabled("Obviously, nondeterministic")
  @Test void throwInAFlowBeforeStopDP_ND() {ok(new Res("", "Program crashed with: Stack overflowed[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow.range(+1, +50).map{n->n.nat}
        .map{x->Block#
          .if {x == 2} .do {StackOverflow#}
          .return {x * 10}
          }
        }
      .let[Nat] sum = {x#(Flow.uSum)}
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(sum.str)}
      .return {{}}
      }
    StackOverflow: {#[R]: R -> this#}
    """, Base.mutBaseAliases);}
  @Test void throwInAFlowBeforeStopParND() {ok(new Res("", "Program crashed with: \"Stack overflowed\"[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @DisabledOnOs(OS.WINDOWS)//LOOP
  @Test void throwInAFlowAfterStopParND() {ok(new Res("", "Program crashed with: Stack overflowed[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
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
    """, Base.mutBaseAliases);}
  @DisabledOnOs(OS.WINDOWS)//LOOP
  @Test void throwInAFlowBeforeStopSeqND() {ok(new Res("", "Program crashed with: Stack overflowed[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @Test void throwInAFlowAfterStopSeqND() {ok(new Res("10", "", 0), """
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
    """, Base.mutBaseAliases);}

  @Test void mutableStringsGraphemes() {ok(new Res("""
    abc
    yodabcyoeabcyofabc
    """, "", 0), """
    package test
    P: {#(a: Str, mutyA: Str): Str -> mut "yo" + a + mutyA}
    Test: Main{sys -> Block#
      .let[Str] mutyA = {"abc".graphemes.join ""}
      .let[Str] mutyB = {"def".graphemes.map{a->P#(a,mutyA)}.join ""}
      .do {sys.io.println(mutyA)}
      .do {sys.io.println(mutyB)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Test void mutableStringsCodepoints() {ok(new Res("""
    abc
    yodabcyoeabcyofabc
    """, "", 0), """
    package test
    P: {#(a: Str, mutyA: Str): Str -> mut "yo" + a + mutyA}
    Test: Main{sys -> Block#
      .let[Str] mutyA = {"abc".codepoints.join ""}
      .let[Str] mutyB = {"def".codepoints.map{a->P#(a,mutyA)}.join ""}
      .do {sys.io.println(mutyA)}
      .do {sys.io.println(mutyB)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  /// The codepoints flow must emit every codepoint of a string, in order, for all
  /// UTF-8 lengths from 1 to 4 bytes. The string is long enough that the
  /// data-parallel machinery splits it, so the split path is covered too.
  @Test void mutableStringsCodepointsMixedWidths() {ok(new Res("""
    1024
    1024
    """, "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Str] s1 = {"a\\u{233}\\u{8364}\\u{119070}z\\u{223}\\u{28450}\\u{127881}"}
      .let[Str] s2 = {s1 + s1}
      .let[Str] s3 = {s2 + s2}
      .let[Str] s4 = {s3 + s3}
      .let[Str] s5 = {s4 + s4}
      .let[Str] s6 = {s5 + s5}
      .let[Str] s7 = {s6 + s6}
      .let[Str] s = {s7 + s7}
      .do {s.codepoints.join("").assertEq(s)}
      .do {sys.io.println(s.codepoints.count.str)}
      .do {sys.io.println(s.size.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Test void pushErrorAndThrowSeq() {ok(new Res("", "Program crashed with: \"hello\"[###]", 1), """
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
    """, Base.mutBaseAliases);}
  @Test void pushErrorAndThrow() {ok(new Res("", "Program crashed with: \"hello\"[###]", 1), """
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
    """, Base.mutBaseAliases);}

  @Test void dataParallelAllThrowsMustGetFirst() {ok(new Res("", "Program crashed with: \"0\"[###]", 1), """
    package test
    Test: Main{sys -> Block#(
      Flow.range(+0, +100_000)
        .map{i -> Error.msg[Int] (i.str)}
        .list
      )}
    """, Base.mutBaseAliases);}

  @Test void ctxDoesNotExposeOrderSeq() {ok(new Res("01 12 23 34", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Str] x = {Flow#[mut Nat](mut 1, mut 2, mut 3, mut 4)
        .map[Ctx,Str](Ctxs#(Count.nat 0), {ctx, x -> ctx.n.str + (x.str)})
        .join " "
        }
      .return {sys.io.println x}
      }
    Ctxs: F[mut Count[Nat], mut Ctx]{cs -> Block#
      .return {mut Ctx: base.ToIso[Ctx]{'ctx
        .iso -> Ctxs#(Count.nat(cs.update{c -> c + 1})),
        .self -> ctx,
        read .n: Nat -> cs.get,
        }}
      }
    """, Base.mutBaseAliases);}
  @Test void ctxDoesNotExposeOrderPP() {ok(new Res("01 12 23 34", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Str] x = {Flow#[Nat](1, 2, 3, 4)
        .limit(100)
        .map[Ctx,Str](Ctxs#(Count.nat 0), {ctx, x -> ctx.n.str + (x.str)})
        .join " "
        }
      .return {sys.io.println x}
      }
    Ctxs: F[mut Count[Nat], mut Ctx]{cs -> Block#
      .return {mut Ctx: base.ToIso[Ctx]{'ctx
        .iso -> Ctxs#(Count.nat(cs.update{c -> c + 1})),
        .self -> ctx,
        read .n: Nat -> cs.get,
        }}
      }
    """, Base.mutBaseAliases);}
  // Not actually DP, downgraded to PP
  @Test void ctxDoesNotExposeOrderDP() {ok(new Res("01 12 23 34", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Str] x = {Flow#[Nat](1, 2, 3, 4)
        .map[Ctx,Str](Ctxs#(Count.nat 0), {ctx, x -> ctx.n.str + (x.str)})
        .join " "
        }
      .return {sys.io.println x}
      }
    Ctxs: F[mut Count[Nat], mut Ctx]{cs -> Block#
      .return {mut Ctx: base.ToIso[Ctx]{'ctx
        .iso -> Ctxs#(Count.nat(cs.update{c -> c + 1})),
        .self -> ctx,
        read .n: Nat -> cs.get,
        }}
      }
    """, Base.mutBaseAliases);}

  @Test void ctxDoesNotExposeOrderImmSeq() {ok(new Res("11 12 13 14", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Str] x = {Flow#[mut Nat](mut 1, mut 2, mut 3, mut 4)
        .map[Ctx,Str](Ctxs#0, {ctx, x -> ctx.n.str + (x.str)})
        .join " "
        }
      .return {sys.io.println x}
      }
    Ctxs: F[Nat, mut Ctx]{n -> Block#
      .return {mut Ctx: base.ToIso[Ctx]{'ctx
        .iso -> Ctxs#(n + 1),
        .self -> ctx,
        read .n: Nat -> n,
        }}
      }
    """, Base.mutBaseAliases);}
  @Test void ctxDoesNotExposeOrderImmPP() {ok(new Res("11 12 13 14", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Str] x = {Flow#[Nat](1, 2, 3, 4)
        .limit(100)
        .map[Ctx,Str](Ctxs#0, {ctx, x -> ctx.n.str + (x.str)})
        .join " "
        }
      .return {sys.io.println x}
      }
    Ctxs: F[Nat, mut Ctx]{n -> Block#
      .return {mut Ctx: base.ToIso[Ctx]{'ctx
        .iso -> Ctxs#(n + 1),
        .self -> ctx,
        read .n: Nat -> n,
        }}
      }
    """, Base.mutBaseAliases);}
  // Not actually DP, downgraded to PP
  @Test void ctxDoesNotExposeOrderImmDP() {ok(new Res("11 12 13 14", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Str] x = {Flow#[Nat](1, 2, 3, 4)
        .map[Ctx,Str](Ctxs#0, {ctx, x -> ctx.n.str + (x.str)})
        .join " "
        }
      .return {sys.io.println x}
      }
    Ctxs: F[Nat, mut Ctx]{n -> Block#
      .return {mut Ctx: base.ToIso[Ctx]{'ctx
        .iso -> Ctxs#(n + 1),
        .self -> ctx,
        read .n: Nat -> n,
        }}
      }
    """, Base.mutBaseAliases);}

  @Test void throwInTerminalSeq() {ok(new Res("", "Program crashed with: \"1\"[###]", 1), """
    package test
    Test: Main{sys -> Block#(
      Flow#[mut Int](mut +1, mut +2, mut +3)
        .map{e -> e}
        .first{e -> Error.msg (e.str)}
      )}
    """, Base.mutBaseAliases);}
  @Test void throwInTerminalDP() {ok(new Res("", "Program crashed with: \"31\"[###]", 1), """
    package test
    Test: Main{sys -> Block#(
      Flow.range(+1, +500)
        .map{e -> e}
        .first{e -> e > +30 ? {
          .then -> Error.msg (e.str),
          .else -> False
          }}
      )}
    """, Base.mutBaseAliases);}
}
