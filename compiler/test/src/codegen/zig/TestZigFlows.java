package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/** Flow transformations and terminals (map/filter/flatMap/scan/limit, find/any/
 * all/none/max/first, list/sum/fold) plus the prime/sieve integration programs.
 * Error propagation through flows lives in {@link TestZigFlowErrors}. */
public class TestZigFlows {
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

  @Test void flowSieveOfEratosthenes2() { okBase(new Res("2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109, 113, 127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191, 193, 197, 199, 211, 223, 227, 229, 233, 239, 241, 251, 257, 263, 269, 271, 277, 281, 283, 293, 307, 311, 313, 317, 331, 337, 347, 349, 353, 359, 367, 373, 379, 383, 389, 397, 401, 409, 419, 421, 431, 433, 439, 443, 449, 457, 461, 463, 467, 479, 487, 491, 499, 503, 509, 521, 523, 541, 547, 557, 563, 569, 571, 577, 587, 593, 599, 601, 607, 613, 617, 619, 631, 641, 643, 647, 653, 659, 661, 673, 677, 683, 691, 701, 709, 719, 727, 733, 739, 743, 751, 757, 761, 769, 773, 787, 797, 809, 811, 821, 823, 827, 829, 839, 853, 857, 859, 863, 877, 881, 883, 887, 907, 911, 919, 929, 937, 941, 947, 953, 967, 971, 977, 983, 991, 997, 1009, 1013, 1019, 1021, 1031, 1033, 1039, 1049, 1051, 1061, 1063, 1069, 1087, 1091, 1093, 1097, 1103, 1109, 1117, 1123, 1129, 1151, 1153, 1163, 1171, 1181, 1187, 1193, 1201, 1213, 1217, 1223, 1229, 1231, 1237, 1249, 1259, 1277, 1279, 1283, 1289, 1291, 1297, 1301, 1303, 1307, 1319, 1321, 1327, 1361, 1367, 1373, 1381, 1399, 1409, 1423, 1427, 1429, 1433, 1439, 1447, 1451, 1453, 1459, 1471, 1481, 1483, 1487, 1489, 1493, 1499, 1511, 1523, 1531, 1543, 1549, 1553, 1559, 1567, 1571, 1579, 1583, 1597, 1601, 1607, 1609, 1613, 1619, 1621, 1627, 1637, 1657, 1663, 1667, 1669, 1693, 1697, 1699, 1709, 1721, 1723, 1733, 1741, 1747, 1753, 1759, 1777, 1783, 1787, 1789, 1801, 1811, 1823, 1831, 1847, 1861, 1867, 1871, 1873, 1877, 1879, 1889, 1901, 1907, 1913, 1931, 1933, 1949, 1951, 1973, 1979, 1987, 1993, 1997, 1999, 2003, 2011, 2017, 2027, 2029, 2039, 2053, 2063, 2069, 2081, 2083, 2087, 2089, 2099, 2111, 2113, 2129, 2131, 2137, 2141, 2143, 2153, 2161, 2179, 2203, 2207, 2213, 2221, 2237, 2239, 2243, 2251, 2267, 2269, 2273, 2281, 2287, 2293, 2297, 2309, 2311, 2333, 2339, 2341, 2347, 2351, 2357, 2371, 2377, 2381, 2383, 2389, 2393, 2399, 2411, 2417, 2423, 2437, 2441, 2447, 2459, 2467, 2473, 2477, 2503, 2521, 2531, 2539, 2543, 2549, 2551, 2557, 2579, 2591, 2593, 2609, 2617, 2621, 2633, 2647, 2657, 2659, 2663, 2671, 2677, 2683, 2687, 2689, 2693, 2699, 2707, 2711, 2713, 2719, 2729, 2731, 2741, 2749, 2753, 2767, 2777, 2789, 2791, 2797, 2801, 2803, 2819, 2833, 2837, 2843, 2851, 2857, 2861, 2879, 2887, 2897, 2903, 2909, 2917, 2927, 2939, 2953, 2957, 2963, 2969, 2971, 2999, 3001, 3011, 3019, 3023, 3037, 3041, 3049, 3061, 3067, 3079, 3083, 3089, 3109, 3119, 3121, 3137, 3163, 3167, 3169, 3181, 3187, 3191, 3203, 3209, 3217, 3221, 3229, 3251, 3253, 3257, 3259, 3271, 3299, 3301, 3307, 3313, 3319, 3323, 3329, 3331, 3343, 3347, 3359, 3361, 3371, 3373, 3389, 3391, 3407, 3413, 3433, 3449, 3457, 3461, 3463, 3467, 3469, 3491, 3499, 3511, 3517, 3527, 3529, 3533, 3539, 3541, 3547, 3557, 3559, 3571, 3581, 3583, 3593, 3607, 3613, 3617, 3623, 3631, 3637, 3643, 3659, 3671, 3673, 3677, 3691, 3697, 3701, 3709, 3719, 3727, 3733, 3739, 3761, 3767, 3769, 3779, 3793, 3797, 3803, 3821, 3823, 3833, 3847, 3851, 3853, 3863, 3877, 3881, 3889, 3907, 3911, 3917, 3919, 3923, 3929, 3931, 3943, 3947, 3967, 3989, 4001, 4003, 4007, 4013, 4019, 4021, 4027, 4049, 4051, 4057, 4073, 4079, 4091, 4093, 4099, 4111, 4127, 4129, 4133, 4139, 4153, 4157, 4159, 4177, 4201, 4211, 4217, 4219, 4229, 4231, 4241, 4243, 4253, 4259, 4261, 4271, 4273, 4283, 4289, 4297, 4327, 4337, 4339, 4349, 4357, 4363, 4373, 4391, 4397, 4409, 4421, 4423, 4441, 4447, 4451, 4457, 4463, 4481, 4483, 4493, 4507, 4513, 4517, 4519, 4523, 4547, 4549, 4561, 4567, 4583, 4591, 4597, 4603, 4621, 4637, 4639, 4643, 4649, 4651, 4657, 4663, 4673, 4679, 4691, 4703, 4721, 4723, 4729, 4733, 4751, 4759, 4783, 4787, 4789, 4793, 4799, 4801, 4813, 4817, 4831, 4861, 4871, 4877, 4889, 4903, 4909, 4919, 4931, 4933, 4937, 4943, 4951, 4957, 4967, 4969, 4973, 4987, 4993, 4999, 5003, 5009, 5011, 5021, 5023, 5039, 5051, 5059, 5077, 5081, 5087, 5099, 5101, 5107, 5113, 5119, 5147, 5153, 5167, 5171, 5179, 5189, 5197, 5209, 5227, 5231, 5233, 5237, 5261, 5273, 5279, 5281, 5297, 5303, 5309, 5323, 5333, 5347, 5351, 5381, 5387, 5393, 5399, 5407, 5413, 5417, 5419, 5431, 5437, 5441, 5443, 5449, 5471, 5477, 5479, 5483, 5501, 5503, 5507, 5519, 5521, 5527, 5531, 5557, 5563, 5569, 5573, 5581, 5591, 5623, 5639, 5641, 5647, 5651, 5653, 5657, 5659, 5669, 5683, 5689, 5693, 5701, 5711, 5717, 5737, 5741, 5743, 5749, 5779, 5783, 5791, 5801, 5807, 5813, 5821, 5827, 5839, 5843, 5849, 5851, 5857, 5861, 5867, 5869, 5879, 5881, 5897, 5903, 5923, 5927, 5939, 5953, 5981, 5987, 6007, 6011, 6029, 6037, 6043, 6047, 6053, 6067, 6073, 6079, 6089, 6091, 6101, 6113, 6121, 6131, 6133, 6143, 6151, 6163, 6173, 6197, 6199, 6203, 6211, 6217, 6221, 6229, 6247, 6257, 6263, 6269, 6271, 6277, 6287, 6299, 6301, 6311, 6317, 6323, 6329, 6337, 6343, 6353, 6359, 6361, 6367, 6373, 6379, 6389, 6397, 6421, 6427, 6449, 6451, 6469, 6473, 6481, 6491, 6521, 6529, 6547, 6551, 6553, 6563, 6569, 6571, 6577, 6581, 6599, 6607, 6619, 6637, 6653, 6659, 6661, 6673, 6679, 6689, 6691, 6701, 6703, 6709, 6719, 6733, 6737, 6761, 6763, 6779, 6781, 6791, 6793, 6803, 6823, 6827, 6829, 6833, 6841, 6857, 6863, 6869, 6871, 6883, 6899, 6907, 6911, 6917, 6947, 6949, 6959, 6961, 6967, 6971, 6977, 6983, 6991, 6997, 7001, 7013, 7019, 7027, 7039, 7043, 7057, 7069, 7079, 7103, 7109, 7121, 7127, 7129, 7151, 7159, 7177, 7187, 7193, 7207, 7211, 7213, 7219, 7229, 7237, 7243, 7247, 7253, 7283, 7297, 7307, 7309, 7321, 7331, 7333, 7349, 7351, 7369, 7393, 7411, 7417, 7433, 7451, 7457, 7459, 7477, 7481, 7487, 7489, 7499, 7507, 7517, 7523, 7529, 7537, 7541, 7547, 7549, 7559, 7561, 7573, 7577, 7583, 7589, 7591, 7603, 7607, 7621, 7639, 7643, 7649, 7669, 7673, 7681, 7687, 7691, 7699, 7703, 7717, 7723, 7727, 7741, 7753, 7757, 7759, 7789, 7793, 7817, 7823, 7829, 7841, 7853, 7867, 7873, 7877, 7879, 7883, 7901, 7907, 7919", "", 0), """
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
