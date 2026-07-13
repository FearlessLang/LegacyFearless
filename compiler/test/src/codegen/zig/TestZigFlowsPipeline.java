package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// End-to-end tests for the staged-fiber pipeline engine: chains containing a
/// serial-work op (actor / scan / ctx ops) are partitioned into stage fibers
/// connected by SPSC rings, with the terminal consuming the last ring.
public class TestZigFlowsPipeline {
  @Test void actorPipelineFold() { okBase(new Res("100", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3, 4)
        .actor[Void,Nat](iso Void,{next,_,x->Block#
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

  @Test void actorFanOut() { okBase(new Res("200", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3, 4)
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .do {next#(x.nat * 10)}
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

  @Test void actorChainTwoStages() { okBase(new Res("18", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .map{n -> n.nat + 1}
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .do {next#(x.nat)}
          .return {{}}
          })
        .map{x -> x.nat * 2}
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .do {next#(x.nat)}
          .return {{}}
          })
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  // 1000 elements overflows the 256-slot rings, exercising the park-on-full
  // producer path and the half-drain refill wake with ordering verified.
  @Test void scanPipelineBig() { okBase(new Res("167167000", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow.range(+1, +1001)
        .map{n -> n.nat}
        .scan[Nat](0, {a, x -> a + x})
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  @Test void limitAfterScanInfiniteSource() { okBase(new Res("35", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow.range(+1)
        .map{n -> n.nat}
        .scan[Nat](0, {a, x -> a + x})
        .limit(5)
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  @Test void speculativeThrowSwallowedByStop() { okBase(new Res("1", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .map{x -> Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat}
          }
        .scan[Nat](0, {a, x -> a + x})
        .limit(1)
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  @Test void errorAfterActorWins() { okBase(new Res("", "Program crashed with: 2[###]", 1), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow#[Nat](1, 2, 3)
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .do {next#(x.nat)}
          .return {{}}
          })
        .map{x -> Block#
          .if {x.nat == 2} .do {Error.msg (x.str)}
          .return {x.nat}
          }
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  @Test void anyOnInfiniteActorChain() { okBase(new Res("True", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow.range(+1)
        .map{n -> n.nat}
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .do {next#(x.nat)}
          .return {{}}
          })
        .any{x -> x.nat == 30}
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }

  @Test void actorPipelineWithVpfInside() { okBase(4, new Res("20100", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let x = {Flow.range(+1, +201)
        .map{n -> n.nat}
        .actor[Void,Nat](iso Void,{next,_,x->Block#
          .do {next#(x.nat)}
          .return {{}}
          })
        .fold[Nat]({0}, {a, x -> a + x})
        }
      .let[mut IO] io = {UnrestrictedIO#sys}
      .do {io.println(x.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases); }
}
