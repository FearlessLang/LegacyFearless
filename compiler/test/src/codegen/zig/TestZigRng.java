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

  /// The seed must never be zero: `FRandom` errors on a zero seed, so the
  /// capability redraws until it gets one.
  @Test void seedIsNonZero() { okBase(new Res("ok", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[Nat] seed = { sys.rng# }
      .do{ Assert!(seed != 0, "seed was zero", {{}}) }
      .return{ sys.io.println("ok") }
      }
    """, Base.mutBaseAliases);}

  /// The seed feeds the pure-Fearless LCG, which must then advance.
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

  /// The seed is a plain `Nat`, so it prints without a magic-specific path.
  @Test void seedPrints() { okBase(new Res("[###]", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(sys.rng#.str) }
    """, Base.mutBaseAliases);}

  /// Two draws from the same seeder are independent seeds.
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

  /// A fixed seed is fully deterministic, matching the Java backend.
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

  /// `.nat(min, max)` goes through `.float`, exercising the Float path.
  @Test void fixedSeedRange() { okBase(new Res("10 18 17 16 7 14 15 18 17 6", "", 0), """
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

  /// A zero seed is rejected by `FRandom` itself, so the program crashes.
  @Test void zeroSeedErrors() { okBase(new Res("", "Program crashed with: Seed may not be zero[###]", 1), """
    package test
    Test:Main{ sys -> sys.io.println((FRandom#0).nat.str) }
    """, Base.mutBaseAliases, RNG_ALIASES);}

  /// `.iso` snapshots the generator's position: the copy continues the sequence
  /// from where the original stood, and the two then advance independently.
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

  /// `.self` is the identity, so draws through it share the one generator.
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
