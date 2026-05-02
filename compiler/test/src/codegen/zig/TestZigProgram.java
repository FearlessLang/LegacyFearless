package codegen.zig;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

public class TestZigProgram {
  @Test void emptyProgram() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> {} }
    """, Base.mutBaseAliases);}

  @Test void printAndPrintln() { okBase(new Res("HelloWorld", "", 0), """
    package test
    Test:Main{ sys -> mut Block[Void]
      .do{ sys.io.print("Hello") }
      .return{ sys.io.println("World") }
      }
    """, Base.mutBaseAliases);}

  @Test void varGetAndSet() { okBase(new Res("42", "", 0), """
    package test
    Test:Main{ sys -> mut Block[Void]
      .let[mut Var[Str]] v = { Vars#[Str]("hello") }
      .do{ v.set("42") }
      .return{ sys.io.println(v.get) }
      }
    """, Base.mutBaseAliases);}

  @Test void listIter() { okBase(new Res("350,350,350,140,140,140", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let l1 = { List#[Nat](35, 52, 84, 14) }
      .assert{l1.iter
        .map{n -> n * 10}
        .find{n -> n == 140}
        .isSome}
      .let[Str] msg = {l1.iter
        .filter{n -> n < 40}
        .flatMap{n -> List#(n, n, n).iter}
        .map{n -> n * 10}
        .str({n -> n.str}, ",")}
      .let io = {sys.io}
      .return {io.println(msg)}
      // prints 350,350,350,140,140,140
    }
    """, Base.mutBaseAliases);}

  @Test void isoPod1() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .return{ Assert!(Usage#(a!) == +0) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod1Consume() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .return{ Assert!(a.consume{.some(n) -> Usage#n, .empty -> +500} == +0) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod2() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .do{ a.next(MutThingy'#(Count.int(+5))) }
      .return{ Assert!(Usage#(a!) == +5) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod3() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .do{ Block#(a.mutate{ mt -> Block#(mt.n++) }!) }
      .return{ Assert!(Usage#(a!) == +1) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPodNoImmFromPeekOk() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .let[Int] ok = { a.peek[Int]{ .some(m) -> m.rn*.int + +0, .empty -> base.Abort! } }
      .return{Void}
      }
    MutThingy:{ mut .n: mut Count[Int], read .rn: read Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { .n -> n, .rn -> n } }
    """, Base.mutBaseAliases); }

  @Test void flowMap() { okBase(new Res("300", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+5, +10, +15)
        .map{n -> n * +10}
        #(Flow.sum)
        .str
      )}
    """, Base.mutBaseAliases); }

  @Test void flowList() { okBase(new Res("4", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .list
        .size
        .str
      )}
    """, Base.mutBaseAliases); }

  @Test void flowFindMapRightHalf() { okBase(new Res("True", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .findMap[Int]{n -> n == +4 ? {.then -> Opts#n, .else -> {}}}
        .isSome
        .str
      )}
    """, Base.mutBaseAliases); }

  // (.for is analytically correct: both recursive args evaluate before merge,
  // so the callback fires on every leaf; merge returns Void either way.
  // Skipped here because verifying side-effects requires capturing `sys` in a
  // `read F[E,Void]` closure, which isn't permitted.)

  @Test void flowFirst() { okBase(new Res("1", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .first
        .match{
          .some(n) -> n.str,
          .empty -> "none",
          }
      )}
    """, Base.mutBaseAliases); }

  // Predicated terminals expand through Fearless defaults onto .findMap
  // (ordered) or .unorderedFindMap (cancel-safe). Each test exercises a
  // 4-elem splittable list so the parallel split fires in driveU/findMap.

  // .any: target in left half. Default body uses .unorderedFindMap; left fork
  // matches on +2, calls scope.request(); right fork (+3,+4) gets cancelled
  // before or during its run_chunk. Either way, isSome -> True.
  @Test void flowAnyEarlyExit() { okBase(new Res("True", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .any{n -> n == +2}
        .str
      )}
    """, Base.mutBaseAliases); }

  // .all: predicate true for every elem. Goes through .unorderedFindMap; no
  // match is found so cancel never fires; both halves run to completion;
  // findMapReducer combines two empty Opts -> empty -> .isEmpty -> True.
  @Test void flowAll() { okBase(new Res("True", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .all{n -> n > +0}
        .str
      )}
    """, Base.mutBaseAliases); }

  // .none: no match. Same shape as .all (no cancel fires), Opt empty, isEmpty -> True.
  @Test void flowNone() { okBase(new Res("True", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .none{n -> n == +99}
        .str
      )}
    """, Base.mutBaseAliases); }

  // .find: ordered semantics, leftmost match wins. Target +4 in right half,
  // expands to ordered .findMap (no cancel), left half returns empty, right
  // half returns Some(+4), merge picks left-or-right via firstSomeReducer.
  @Test void flowFindRightHalf() { okBase(new Res("4", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .find{n -> n == +4}
        .match{
          .some(n) -> n.str,
          .empty -> "none",
          }
      )}
    """, Base.mutBaseAliases); }

  // .max: exercises driveMax's maxReducer on all 3 merge positions (the +4
  // peak is in the second-quarter, so left-half merge sees Some(+4) vs
  // Some(+1), then root merge sees Some(+4) vs Some(+3)).
  @Test void flowMax() { okBase(new Res("4", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +4, +2, +3)
        .max{a, b -> a > b ? {
          .then -> FOrdering.greater,
          .else -> a < b ? {.then -> FOrdering.less, .else -> FOrdering.equal}
          }}
        .match{
          .some(n) -> n.str,
          .empty -> "none",
          }
      )}
    """, Base.mutBaseAliases); }

  // Variable-fan-out flatMap. Outer is a 4-elem list (splittable); each n
  // maps to Flow.range(+0, n) producing 1, 2, 3, 4 inner elements
  // respectively. Total = 10. Confirms process_through walks varying-length
  // inner flows correctly when the outer source is split for parallel driveReduce.
  @Test void flowFlatMapVariableFanout() { okBase(new Res("10", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +2, +3, +4)
        .flatMap[Int]{n -> Flow.range(+0, n)}
        .list
        .size
        .str
      )}
    """, Base.mutBaseAliases); }

  // Mid-pipeline .limit short-circuits before exhausting source. .limit is
  // stateful so split_flow refuses; sequential run_chunk fires Applied.done
  // after the 5th element clears the upstream map. Confirms the short-circuit
  // signal propagates through a non-trivial op chain.
  @Test void flowLimitMidPipeline() { okBase(new Res("5", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow.range(+0, +1000)
        .map[Int]{n -> n + +1}
        .limit(5)
        .list
        .size
        .str
      )}
    """, Base.mutBaseAliases); }

  // .scan accumulator state across elements. Desugars to .actor (stateful)
  // so split_flow refuses; sequential ActorState/OpDesc.state cell survives
  // across run_chunk iterations. Three +1 inputs against +0 seed produce
  // running sums [+1, +2, +3]; folding those gives +6.
  @Test void flowScan() { okBase(new Res("6", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+1, +1, +1)
        .scan[Int](+0, {acc, e -> acc + e})
        .fold[Int]({+0}, {a, b -> a + b})
        .str
      )}
    """, Base.mutBaseAliases); }

  // Parallel driveReduce on a multi-op pipeline. List source is splittable,
  // all ops (filter, map) are stateless, so driveReduce splits. Evens of
  // [+0..+3] are [+0, +2]; *10 gives [+0, +20]; sum = +20. Confirms multi-op
  // pipelines compose cleanly under VPF promotion.
  @Test void flowMapFilterSum() { okBase(new Res("20", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow#[Int](+0, +1, +2, +3)
        .filter{n -> (n % +2) == +0}
        .map[Int]{n -> n * +10}
        .fold[Int]({+0}, {a, b -> a + b})
        .str
      )}
    """, Base.mutBaseAliases); }

  @Test void flowDumbPrimeFinder1() { okBase(new Res("", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow.range(+2, +30_000)
        .filter{n -> Flow.range(+2, n).all{m -> n % m != +0}}
        .map({n -> n.str})
        .join ", "
      )}
    """, Base.mutBaseAliases); }

  @Disabled("Broken due to bad VTable construction.") // TODO: Fix
  @Test void flowSieveOfEratosthenes2() { okBase(new Res("", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(
      Primes#
        .limit(1000)
        .map{n -> n.str}
        .join ", "
      )}
    
    Primes: {#: mut Flow[Int] -> Flow.range(+2)
      .actor[mut UList[F[Int,Bool]],Int](UList#{_ -> True}, {downstream, preds, n -> Block#
        .if {preds.flow.any{p -> p#n.not}} .return {{}}
        .do {downstream#n}
        .do {preds.add{n' -> n' % n != +0}}
        .return {{}}
        })
      }
    """, Base.mutBaseAliases); }
}
