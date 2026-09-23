package flows;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import utils.Base;

import static codegen.java.RunJavaProgramTests.fail;
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

  /// An error before a stop propagates. An error after the stop is ignored.
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

  /// A non-deterministic error propagates also after a stop, because only code with a capability
  /// can catch it.
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

  /// All UTF-8 lengths from 1 to 4 bytes, in a string that is long enough to split.
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

  private static final String mutPersons = """
    Person: {mut .visits: mut Count[Nat], read .id: Nat}
    Persons: {#(n: Nat): mut Person -> Block#
      .let[mut Count[Nat]] c = {Count.nat 0}
      .return {mut Person{.visits -> c, .id -> n}}
      }
    Spin: {#(n: Nat): Nat -> n == 0 ? {.then -> 0, .else -> this#(n - 1)}}
    Bump: {#(p: mut Person): Nat -> p.visits.update{v -> Block#(Spin#200, v + 1)}}
    """;
  @Test void mutDuplicatedByChainFilterDP() {ok(new Res("10000 True", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .let[mut List[mut Person]] res = {numbers.flow
        .map{n -> Persons#n}
        .chain{p -> List#(p, p)}
        .filter{p -> Bump#p == 0}
        .list}
      .return {sys.io.println(res.size.str + " " + (res.flow.all{p -> p.visits.get == 2}.str))}
      }
    """+mutPersons, Base.mutBaseAliases);}
  @Test void mutDuplicatedByChainFilterPP() {ok(new Res("10000 True", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .let[mut List[mut Person]] res = {numbers.flow
        .limit(1_000_000)
        .map{n -> Persons#n}
        .chain{p -> List#(p, p)}
        .filter{p -> Bump#p == 0}
        .list}
      .return {sys.io.println(res.size.str + " " + (res.flow.all{p -> p.visits.get == 2}.str))}
      }
    """+mutPersons, Base.mutBaseAliases);}
  @Test void mutDuplicatedByChainTwoFiltersPP() {ok(new Res("20000 True", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .let[mut List[mut Person]] res = {numbers.flow
        .limit(1_000_000)
        .map{n -> Persons#n}
        .chain{p -> List#(p, p)}
        .filter{p -> Bump#p >= 0}
        .filter{p -> Bump#p >= 0}
        .list}
      .return {sys.io.println(res.size.str + " " + (res.flow.all{p -> p.visits.get == 4}.str))}
      }
    """+mutPersons, Base.mutBaseAliases);}
  // In element order the fold sees 1 then 2 for each person, so 30000.
  @Test void mutDuplicatedByChainFilterThenFoldDP() {ok(new Res("30000", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .let[Nat] res = {numbers.flow
        .map{n -> Persons#n}
        .chain{p -> List#(p, p)}
        .filter{p -> Bump#p >= 0}
        .fold[Nat]({0}, {acc, p -> acc + (p.visits.get)})}
      .return {sys.io.println(res.str)}
      }
    """+mutPersons, Base.mutBaseAliases);}
  // `.limit` makes the inner flow PP. The ids must arrive as 0, 1, 2, ..., so the result checks order and count.
  @Test void chainInnerFlowConvertedToPP() {ok(new Res("100000 100000 True", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] outer = {Flow.range(+0, +100).map{i -> i.nat}.list}
      .let[List[Nat]] inner = {Flow.range(+0, +1000).map{i -> i.nat}.list}
      .let[mut List[mut Person]] res = {outer.flow
        .chain{n -> inner.flow.map{m -> Persons#(n * 1000 + m)}.limit(1000).list}
        .filter{p -> Bump#p == 0}
        .list}
      .let[Nat] inOrder = {res.flow.fold[Nat]({0}, {next, p -> p.id == next ? {.then -> next + 1, .else -> next}})}
      .return {sys.io.println(res.size.str + " " + (inOrder.str) + " " + (res.flow.all{p -> p.visits.get == 1}.str))}
      }
    """+mutPersons, Base.mutBaseAliases);}
  @Test void chainInnerFlowConvertedToPPDuplicates() {ok(new Res("100000 True", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] outer = {Flow.range(+0, +100).map{i -> i.nat}.list}
      .let[List[Nat]] inner = {Flow.range(+0, +500).map{i -> i.nat}.list}
      .let[mut List[mut Person]] res = {outer.flow
        .chain{n -> inner.flow
          .map{m -> Persons#(n * 1000 + m)}
          .chain{p -> List#(p, p)}
          .filter{p -> Bump#p >= 0}
          .limit(1000)
          .list}
        .filter{p -> Bump#p >= 0}
        .list}
      .return {sys.io.println(res.size.str + " " + (res.flow.all{p -> p.visits.get == 4}.str))}
      }
    """+mutPersons, Base.mutBaseAliases);}
  @Test void chainImmElements() {ok(new Res("99990000", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .let[Nat] res = {numbers.flow.chain{n -> List#(n, n)}.fold[Nat]({0}, {a, n -> a + n})}
      .return {sys.io.println(res.str)}
      }
    """, Base.mutBaseAliases);}
  @Test void flatMapRejectsMutElements() {fail("""
    In position [###]/Dummy0.fear:4:58
    [E5 invalidMdfBound]
    Type bound related to .flatMap/1:
    The type mut test.Person[] is not valid because its capability is not in the required bounds. The allowed modifiers are: imm.
    """, """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .return {sys.io.println(numbers.flow.map{n -> Persons#n}.flatMap{p -> List#(p, p).flow}.list.size.str)}
      }
    """+mutPersons, Base.mutBaseAliases);}
  // `downstream` is `mutH`, so its argument must be `iso`.
  @Test void actorMutCannotSendElementTwice() {fail("""
    In position [###]/Dummy0.fear:6:95
    [E66 invalidMethodArgumentTypes]
    Method #/1 called in position [###]/Dummy0.fear:6:95 cannot be called with current parameters of types:
    [mut test.Person[] ()]
    Attempted signatures:
    (iso test.Person[]):imm base.Void[] kind: MutHPromRec
    """, """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .return {sys.io.println(numbers.flow
        .map{n -> Persons#n}
        .actorMut[mut Var[Nat], mut Person](Vars#[Nat]0, {downstream, state, p -> Block#(downstream#p, downstream#p, base.flows.ActorRes.continue)})
        .list.size.str)}
      }
    """+mutPersons, Base.mutBaseAliases);}
  // A `.chain` with `read` elements can put one object at many positions. No op after the chain
  // can mutate it.
  @Test void chainReadCannotMutate() {fail("""
    In position [###]/Dummy0.fear:4:125
    [E66 invalidMethodArgumentTypes]
    Method #/1 called in position [###]/Dummy0.fear:4:125 cannot be called with current parameters of types:
    [read test.Person[] ()]
    Attempted signatures:
    (iso test.Person[]):imm base.Nat[] kind: IsoHProm
    (iso test.Person[]):imm base.Nat[] kind: IsoProm
    (mut test.Person[]):imm base.Nat[] kind: Base
    (iso test.Person[]):imm base.Nat[] kind: ReadHProm
    (mutH test.Person[]):imm base.Nat[] kind: MutHPromPar(0)
    """, """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .return {sys.io.println(numbers.flow.map{n -> Persons#n}.chain[read Person]{p -> List#[read Person](p, p)}.filter{p -> Bump#p >= 0}.list.size.str)}
      }
    """+mutPersons, Base.mutBaseAliases);}
  @Test void chainReadCannotReachMutField() {fail("""
    In position [###]/Dummy0.fear:4:119
    [E36 undefinedMethod]
    Method <.visits> with 0 args does not exist in <read test.Person[]>
    Did you mean <test.Person.visits()>

    Other candidates:
    test.Person[].id(): imm base.Nat[]
    base.Infos[].list(imm base.List[imm base.Info[]]): imm base.Info[]
    base.Bytes[].list(): imm base.List[imm base.Byte[]]
    base.json._LexerModes[].digits(): mut base.json._LexerMode[]
    base.iter.Iter[E].list(): mut base.List[E]
    base.Info[].list(): imm base.List[imm base.Info[]]
    base.json._LexerCtx[].isDigit(imm base.Str[]): imm base.Bool[]
    base.json._LexerCtx[].isWhitespace(imm base.Str[]): imm base.Bool[]
    base.InfoVisitor[R].list(imm base.Info[]): R
    base._InfoToJson[].list(imm base.Info[]): imm base.json.Json[]
    """, """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .return {sys.io.println(numbers.flow.map{n -> Persons#n}.chain[read Person]{p -> List#[read Person](p, p)}.map{p -> p.visits}.list.size.str)}
      }
    """+mutPersons, Base.mutBaseAliases);}
  @Test void chainReadFoldCannotMutate() {fail("""
    In position [###]/Dummy0.fear:4:146
    [E66 invalidMethodArgumentTypes]
    Method #/1 called in position [###]/Dummy0.fear:4:146 cannot be called with current parameters of types:
    [read test.Person[] ()]
    Attempted signatures:
    (iso test.Person[]):imm base.Nat[] kind: IsoHProm
    (iso test.Person[]):imm base.Nat[] kind: IsoProm
    (mut test.Person[]):imm base.Nat[] kind: Base
    (iso test.Person[]):imm base.Nat[] kind: ReadHProm
    (mutH test.Person[]):imm base.Nat[] kind: MutHPromPar(0)
    """, """
    package test
    Test: Main{sys -> Block#
      .let[List[Nat]] numbers = {Flow.range(+0, +10000).map{i -> i.nat}.list}
      .return {sys.io.println(numbers.flow.map{n -> Persons#n}.chain[read Person]{p -> List#[read Person](p, p)}.fold[Nat]({0}, {acc, p -> acc + (Bump#p)}).str)}
      }
    """+mutPersons, Base.mutBaseAliases);}

  @Test void flatMapSkewedInnerSplit() {ok(new Res("200000", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Nat] res = {Flow#[Nat](0)
        .flatMap{_ -> Flow.range(+0, +200000).map{i -> i.nat}}
        .fold[Nat]({0}, {next, x -> x == next ? {.then -> next + 1, .else -> next}})}
      .return {sys.io.println(res.str)}
      }
    """, Base.mutBaseAliases);}
  @Test void flatMapSkewedTwoOuter() {ok(new Res("200000", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Nat] res = {Flow.range(+0, +2).map{i -> i.nat}
        .flatMap{n -> Flow.range(+0, +100000).map{i -> i.nat + (n * 100000)}}
        .fold[Nat]({0}, {next, x -> x == next ? {.then -> next + 1, .else -> next}})}
      .return {sys.io.println(res.str)}
      }
    """, Base.mutBaseAliases);}
  @Test void flatMapPrefixFilterDropsSingle() {ok(new Res("0", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(Flow#[Nat](1)
      .filter{n -> n == 0}
      .flatMap{_ -> Flow.range(+0, +1000).map{i -> i.nat}}
      .list.size.str)}
    """, Base.mutBaseAliases);}
  @Test void flatMapPrefixMapSingle() {ok(new Res("3499500", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Nat] res = {Flow#[Nat](3)
        .map{n -> n * 1000}
        .flatMap{b -> Flow.range(+0, +1000).map{i -> i.nat + b}}
        .fold[Nat]({0}, {a, x -> a + x})}
      .return {sys.io.println(res.str)}
      }
    """, Base.mutBaseAliases);}
  @Test void flatMapThenLimitNotSplit() {ok(new Res("10", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(Flow#[Nat](0)
      .flatMap{_ -> Flow.range(+0, +200000).map{i -> i.nat}}
      .limit(10)
      .list.size.str)}
    """, Base.mutBaseAliases);}
  @Test void chainReadAliasedSplit() {ok(new Res("40000 20000", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Nat] ordered = {Flow.range(+0, +2).map{i -> Persons#(i.nat)}
        .chain[read Person]{p -> Flow.range(+0, +20000).map[read Person]{_ -> p}.list}
        .fold[Nat]({0}, {acc, p -> p.id == (acc / 20000) ? {.then -> acc + 1, .else -> acc}})}
      .let[Nat] ids = {Flow.range(+0, +2).map{i -> Persons#(i.nat)}
        .chain[read Person]{p -> Flow.range(+0, +20000).map[read Person]{_ -> p}.list}
        .fold[Nat]({0}, {acc, p -> acc + (p.id)})}
      .return {sys.io.println(ordered.str + " " + (ids.str))}
      }
    """+mutPersons, Base.mutBaseAliases);}
  @Test void chainImmAliasedObjectSplit() {ok(new Res("200000", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Nat] res = {Flow.range(+0, +4).map{i -> i.nat}
        .chain[Str]{n -> Block#
          .let[Str] s = {n.str}
          .return {Flow.range(+0, +50000).map[Str]{_ -> s}.list}
          }
        .fold[Nat]({0}, {a, s -> a + (s.size)})}
      .return {sys.io.println(res.str)}
      }
    """, Base.mutBaseAliases);}
}
