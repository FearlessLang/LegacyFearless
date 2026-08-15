package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// Codepoint-indexed `Str` surface for the Zig backend: `.substring`/`.charAt`
/// (incl. out-of-bounds `FearlessError`s), `.graphemes` clustering, NFC
/// `.normalise`, `UTF8.fromBytes` validation, and the `Regexs` factory (`.str` +
/// compile-error path). Multibyte round-trips are exercised more heavily by
/// `TestZigContainers#simpleJson`.
///
/// The accented-e cases use explicit unicode escapes (processed by javac
/// before the text reaches Fearless): `e` + U+0301 is the decomposed form
/// (two codepoints, one grapheme) and U+00E9 the composed NFC form.
public class TestZigStrings {
  // === UTF8.fromBytes ===
  @Test void utf8RoundTrip() { okBase(new Res("ok", "", 0), """
    package test
    alias base.UTF8 as UTF8,
    
    Test:Main{ sys -> UTF8.fromBytes("Hello".utf8).run{
      .ok(s) -> sys.io.println(s == "Hello" ? { .then -> "ok", .else -> "mismatch" }),
      .info(_) -> sys.io.println("bad"),
      }}
    """, Base.mutBaseAliases); }

  @Test void utf8RejectsInvalidBytes() { okBase(new Res("Invalid UTF-8 byte sequence", "", 0), """
    package test
    alias base.UTF8 as UTF8,
    alias base.Byte as Byte,
    
    Test:Main{ sys -> UTF8.fromBytes(List#[Byte](255 .byte)).run{
      .ok(_) -> sys.io.println("unexpectedly ok"),
      .info(i) -> sys.io.println(i.msg),
      }}
    """, Base.mutBaseAliases); }

  // === substring / charAt ===
  @Test void substringAndCharAt() { okBase(new Res("el/H", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(("Hello".substring(1, 3)) + "/" + ("Hello".charAt(0))) }
    """, Base.mutBaseAliases); }

  @Test void substringEndOutOfBounds() {
    okBase(new Res("", "Program crashed with: End index must be less than the size of the string[###]", 1), """
    package test
    Test:Main{ sys -> sys.io.println("Hi".substring(0, 5)) }
    """, Base.mutBaseAliases); }

  @Test void substringStartAfterEnd() {
    okBase(new Res("", "Program crashed with: Start index must be less than end index[###]", 1), """
    package test
    Test:Main{ sys -> sys.io.println("Hello".substring(3, 1)) }
    """, Base.mutBaseAliases); }

  @Test void charAtOutOfBounds() {
    okBase(new Res("", "Program crashed with: End index must be less than the size of the string[###]", 1), """
    package test
    Test:Main{ sys -> sys.io.println("Hi".charAt(5)) }
    """, Base.mutBaseAliases); }

  // === graphemes: "e" + combining acute (U+0301) is two codepoints but one cluster ===
  @Test void graphemesClusterCombiningMark() { okBase(new Res("1/2", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(("e\\u{769}".graphemes.count.str) + "/" + ("e\\u{769}".size.str)) }
    """, Base.mutBaseAliases); }

  // === normalise: decomposed "e"+U+0301 -> composed NFC U+00E9 (one codepoint) ===
  @Test void normaliseDecomposedToComposed() { okBase(new Res("1/yes", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(
      ("e\\u{769}".normalise.size.str) + "/" + ("e\\u{769}".normalise == "\\u{233}" ? { .then -> "yes", .else -> "no" }))
      }
    """, Base.mutBaseAliases); }

  // === Regex ===
  @Test void regexStrReturnsPattern() { okBase(new Res("[0-9]+", "", 0), """
    package test
    alias base.Regexs as Regexs,
    alias base.Regex as Regex,
    
    Test:Main{ sys -> sys.io.println((Regexs#"[0-9]+").str) }
    """, Base.mutBaseAliases); }

  @Test void regexCompileErrorDies() { okBase(new Res("", "Program crashed with: [###]", 1), """
    package test
    alias base.Regexs as Regexs,
    alias base.Regex as Regex,
    
    Test:Main{ sys -> sys.io.println((Regexs#"[").str) }
    """, Base.mutBaseAliases); }

  // === .assertEq, lowered to the pure-Fearless _StrHelpers ===
  @Test void strAssertEq() { okBase(new Res("ok", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .do{ "a" .assertEq "a" }
      .return{ sys.io.println("ok") }
      }
    """, Base.mutBaseAliases); }

  @Test void strAssertEqFails() { okBase(
    new Res("", "Program crashed with: Expected: a[###]Actual: b[###]", 1), """
    package test
    Test:Main{ sys -> "a" .assertEq "b" }
    """, Base.mutBaseAliases); }

  @Test void strAssertEqFailsWithMessage() { okBase(
    new Res("", "Program crashed with: nope[###]Expected: a[###]Actual: b[###]", 1), """
    package test
    Test:Main{ sys -> "a" .assertEq("b", "nope") }
    """, Base.mutBaseAliases); }

  @Test void mutStrAssertEq() { okBase(new Res("ok", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut Str] s = { mut "" }
      .do{ s.append "ab" }
      .do{ s.str .assertEq "ab" }
      .return{ sys.io.println("ok") }
      }
    """, Base.mutBaseAliases); }
}
