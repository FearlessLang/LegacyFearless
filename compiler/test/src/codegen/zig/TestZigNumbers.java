package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/** Float and Byte primitive coverage for the Zig backend. Float {@code .str} is
 * routed through the Rust capi (`frt_f64_to_str`) so the output is byte-identical
 * to the Java backend's `f64::to_string`. */
public class TestZigNumbers {
  // === Float literals & .str ===
  @Test void floatLiteral() { okBase(new Res("1.5", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(1.5 .str) }
    """, Base.mutBaseAliases);}

  @Test void floatWhole() { okBase(new Res("1", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(1.0 .str) }
    """, Base.mutBaseAliases);}

  @Test void floatNegative() { okBase(new Res("-2.25", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(-2.25 .str) }
    """, Base.mutBaseAliases);}

  @Test void floatPointOne() { okBase(new Res("0.1", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(0.1 .str) }
    """, Base.mutBaseAliases);}

  // === Float arithmetic ===
  @Test void floatAdd() { okBase(new Res("3.5", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((1.25 + 2.25).str) }
    """, Base.mutBaseAliases);}

  @Test void floatDiv() { okBase(new Res("0.5", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((1.0 / 2.0).str) }
    """, Base.mutBaseAliases);}

  @Test void floatPow() { okBase(new Res("8", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((2.0 ** 3.0).str) }
    """, Base.mutBaseAliases);}

  @Test void floatSqrt() { okBase(new Res("3", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(9.0 .sqrt .str) }
    """, Base.mutBaseAliases);}

  // === Float rounding (returns Int) ===
  @Test void floatRound() { okBase(new Res("3", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(2.5 .round .str) }
    """, Base.mutBaseAliases);}

  @Test void floatCeil() { okBase(new Res("3", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(2.1 .ceil .str) }
    """, Base.mutBaseAliases);}

  @Test void floatFloor() { okBase(new Res("2", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(2.9 .floor .str) }
    """, Base.mutBaseAliases);}

  // === Float comparisons ===
  @Test void floatCompare() { okBase(new Res("Yay!", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(1.5 < 2.5 ? { .then -> "Yay!", .else -> "no" }) }
    """, Base.mutBaseAliases);}

  // === Float NaN / infinity ===
  @Test void floatNaN() { okBase(new Res("NaN", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((0.0 / 0.0).str) }
    """, Base.mutBaseAliases);}

  @Test void floatInf() { okBase(new Res("inf", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((1.0 / 0.0).str) }
    """, Base.mutBaseAliases);}

  @Test void floatNegInf() { okBase(new Res("-inf", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((-1.0 / 0.0).str) }
    """, Base.mutBaseAliases);}

  @Test void floatIsNaN() { okBase(new Res("yes", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((0.0 / 0.0).isNaN ? { .then -> "yes", .else -> "no" }) }
    """, Base.mutBaseAliases);}

  @Test void floatIsInfinite() { okBase(new Res("yes", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((1.0 / 0.0).isInfinite ? { .then -> "yes", .else -> "no" }) }
    """, Base.mutBaseAliases);}

  @Test void floatIsPosInfinity() { okBase(new Res("yes", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((1.0 / 0.0).isPosInfinity ? { .then -> "yes", .else -> "no" }) }
    """, Base.mutBaseAliases);}

  @Test void floatIsNegInfinity() { okBase(new Res("yes", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((-1.0 / 0.0).isNegInfinity ? { .then -> "yes", .else -> "no" }) }
    """, Base.mutBaseAliases);}

  // === Conversions to/from Float ===
  @Test void natToFloat() { okBase(new Res("7", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(7 .float .str) }
    """, Base.mutBaseAliases);}

  @Test void floatToInt() { okBase(new Res("3", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(3.9 .int .str) }
    """, Base.mutBaseAliases);}

  // === Byte (no literal syntax; created via conversions) ===
  @Test void byteFromNat() { okBase(new Res("255", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(255 .byte .str) }
    """, Base.mutBaseAliases);}

  @Test void byteWraps() { okBase(new Res("0", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(256 .byte .str) }
    """, Base.mutBaseAliases);}

  @Test void byteAdd() { okBase(new Res("2", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((255 .byte + (3 .byte)).str) }
    """, Base.mutBaseAliases);}

  @Test void byteToNat() { okBase(new Res("200", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(200 .byte .nat .str) }
    """, Base.mutBaseAliases);}

  @Test void byteToFloat() { okBase(new Res("200", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(200 .byte .float .str) }
    """, Base.mutBaseAliases);}

  @Test void byteBitwiseOr() { okBase(new Res("14", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println((10 .byte .bitwiseOr (4 .byte)).str) }
    """, Base.mutBaseAliases);}

  @Test void byteCompare() { okBase(new Res("yes", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(200 .byte > (100 .byte) ? { .then -> "yes", .else -> "no" }) }
    """, Base.mutBaseAliases);}
}
