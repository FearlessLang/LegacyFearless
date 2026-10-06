package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// `System.rng` and the generator it feeds. The capability supplies only a seed;
/// the generator itself is the pure Fearless LCG in `assets/base/rng.fear`, so
/// every seeded sequence here must match the Java backend's output exactly.
public class TestZigRng {
  static final String RNG_ALIASES = """
    package test
    alias base.rng.FRandom as FRandom,
    alias base.rng.Random as Random,
    """;

  @Test void seedIsNonZero() { okBase(new Res("ok", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[Nat] seed = { sys.rng# }
      .do{ Assert!(seed != 0, "seed was zero", {{}}) }
      .return{ sys.io.println("ok") }
      }
    """, Base.mutBaseAliases);}

  @Test void drawsAdvance() { okBase(new Res("ok", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut base.rng.Random] r = { base.rng.FRandom#(sys.rng#) }
      .let[Nat] a = { r.nat }
      .let[Nat] b = { r.nat }
      .do{ Assert!(a != b, "two draws should differ", {{}}) }
      .return{ sys.io.println("ok") }
      }
    """, Base.mutBaseAliases);}

  @Test void seedPrints() { okBase(new Res("[###]", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(sys.rng#.str) }
    """, Base.mutBaseAliases);}

  @Test void repeatedSeedsDiffer() { okBase(new Res("ok", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut RandomSeed] seeder = { sys.rng }
      .let[mut Random] a = { FRandom#(seeder#) }
      .let[mut Random] b = { FRandom#(seeder#) }
      .do{ Assert!((a.nat == (b.nat)).not, "two seeders should differ", {{}}) }
      .return{ sys.io.println("ok") }
      }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void fixedSeedNat() { okBase(new Res("570564682", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((FRandom#1337).nat.str) }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void fixedSeedFloat() { okBase(new Res("0.26568988443617236", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((FRandom#1337).float.str) }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void fixedSeedSequence() { okBase(new Res("570564682\n1499355484\n1372376065\n1209872585\n241596240", "", 0), """
    package test
    Test:Main{ sys -> Rng#(FRandom#1337, sys.io, Count.nat(5)) }
    Rng: {#(rng: mut Random, io: mut IO, n: mut Count[Nat]): Void -> Block#
      .loop {n.get == 0 ? {.then -> ControlFlow.break, .else -> Block#(io.println(rng.nat.str), n--, ControlFlow.continue)}}
      .return {{}}
      }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void fixedSeedRangeGoesThroughFloat() { okBase(new Res("10 18 17 16 7 14 15 18 17 6", "", 0), """
    package test
    Test:Main{ sys -> Rng#(FRandom#1337, sys.io, Count.nat(10)) }
    Rng: {#(rng: mut Random, io: mut IO, n: mut Count[Nat]): Void -> Block#
      .loop {n.get == 0 ? {.then -> ControlFlow.break, .else -> Block#(io.print(rng.nat(5, 25).str+" "), n--, ControlFlow.continue)}}
      .return {{}}
      }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void differentSeedsDiverge() { okBase(new Res("570564682\n1717387703", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .do{ sys.io.println((FRandom#1337).nat.str) }
      .do{ sys.io.println((FRandom#50000000).nat.str) }
      .return{ {} }
      }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void zeroSeedErrors() { okBase(new Res("", "Program crashed with: Seed may not be zero[###]", 1), """
    package test
    Test:Main{ sys -> sys.io.println((FRandom#0).nat.str) }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void isoSnapshotsPosition() { okBase(new Res("1499355484\n1499355484", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut Random] a = { FRandom#1337 }
      .let[Nat] burn = { a.nat }
      .let[mut Random] b = { a.iso }
      .do{ sys.io.println(a.nat.str) }
      .do{ sys.io.println(b.nat.str) }
      .return{ {} }
      }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  @Test void selfSharesState() { okBase(new Res("570564682\n1499355484", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut Random] a = { FRandom#1337 }
      .do{ sys.io.println(a.self.nat.str) }
      .do{ sys.io.println(a.nat.str) }
      .return{ {} }
      }
    """, Base.mutBaseAliases, RNG_ALIASES);}
}
