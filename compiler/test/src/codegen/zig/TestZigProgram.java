package codegen.zig;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;
import utils.ResolveResource;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

//@Disabled("Experimental & Slow, run these tests explicitly if you need to")
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

  @Test void flowDumbPrimeFinder1() { okBase(new Res("2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109, 113, 127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191, 193, 197, 199, 211, 223, 227, 229, 233, 239, 241, 251, 257, 263, 269, 271, 277, 281, 283, 293, 307, 311, 313, 317, 331, 337, 347, 349, 353, 359, 367, 373, 379, 383, 389, 397, 401, 409, 419, 421, 431, 433, 439, 443, 449, 457, 461, 463, 467, 479, 487, 491, 499, 503, 509, 521, 523, 541, 547, 557, 563, 569, 571, 577, 587, 593, 599, 601, 607, 613, 617, 619, 631, 641, 643, 647, 653, 659, 661, 673, 677, 683, 691, 701, 709, 719, 727, 733, 739, 743, 751, 757, 761, 769, 773, 787, 797, 809, 811, 821, 823, 827, 829, 839, 853, 857, 859, 863, 877, 881, 883, 887, 907, 911, 919, 929, 937, 941, 947, 953, 967, 971, 977, 983, 991, 997", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(
      Flow.range(+2, +1000)
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

  @Test void errorUncaught() { okBase(new Res("", "Program crashed with: oh no[###]", 1), """
    package test
    Test:Main {sys -> Error.msg[Void] "oh no"}
    """, Base.mutBaseAliases); }

  // Try catches a deterministic Error and routes it to the .info match arm.
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

  // M3: VPF join-point error propagation. RSum is a divide-and-conquer range
  // sum whose combiner `this#(lo, mid) + (this#(mid, hi))` is VPF-parallelisable
  // (two recursive sub-calls). Forcing a low promotion threshold makes thieves
  // steal subtrees, so an Error! thrown deep in a stolen branch must travel back
  // through a work-stealing join (obligation wait) and re-unwind on the waiter's
  // fiber. The leaf `lo == 127` throws only when the range includes 127.

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

  // ==========================================================================
  // These tests are ported from from flows/TestFlowSemantics.java (Java
  // backend), with FeaRT-adjusted expectations: crash messages are UNQUOTED
  // (`.msg`, since JSON `.str` is unimplemented in this backend). Par/DP
  // variants force VPF promotion via okBase(16, ...) so thieves actually steal
  // chunks and the work-stealing join is exercised; Seq variants use the
  // default high threshold (sequential).
  //
  // Golden rule: a deterministic error in a flow is observably the same
  // sequential vs data-parallel — the flow-order-FIRST error wins; errors after
  // a stop (`.limit`) are ignored.
  // ==========================================================================

  // Throw before a stop: first error in flow order (element 2) propagates.
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

  @Disabled("Broken due to missing magic")
  @Test void simpleJson() { okBase(new Res("""
    "Hello!!!\\nHow are you?"
    "Hello!!!\\nHow 吣are吣 you?"
    "Hello!!!\\nHow 吣are吣 you? 𝄞"
    []
    [[[[]], [], true]]
    ["abc", "def", true, false, null]
    ["abc", "def", true, [false], 42.1337, null, []]
    {}
    {"single": true}
    ["ab\\\\c", "def", {}, {"a": "fearless", "b": {"a": true}}]
    {"value": 12345678901234567000}
    """, """
    Invalid string found, expected JSON.
    Unknown fragment in JSON code:
    tru at 1:6
    Invalid string found, expected JSON.
    Unexpected 'true' when parsing a JSON object at 1:6
    """, 0), ResolveResource.test("/json/main.fear"), ResolveResource.test("/json/pkg.fear")); }
}
