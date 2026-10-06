package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// E2E programs for the Zig names of Fearless declarations. Different Fearless names must give
/// different Zig names. A name or a string literal that the generated Zig code holds as text must
/// keep its text, whatever characters it has.
public class TestZigNames {
  @Test void typeNamesWithTheSameEscapedZigNameKeepTheirOwnMethods() {
    // `'` is legal at the end of a Fearless type name. The Zig name of a `'` is the text `_Zapos`,
    // and the type name `Foo_Zapos` spells that text. The two types are different, and each must
    // keep its own method.
    okBase(new Res("1\n2", "", 0), """
      package test
      Foo':{ .x: Nat -> 1 }
      Foo_Zapos:{ .x: Nat -> 2 }
      Test:Main{sys -> Block#
        .do{sys.io.println(Foo'.x.str)}
        .return{sys.io.println(Foo_Zapos.x.str)}}
      """, Base.mutBaseAliases);
  }

  @Test void typeNamesThatStartWithADigitKeepTheirOwnMethods() {
    // A Zig name cannot start with a digit, so the Zig name of `5Foo` has the prefix `_N`. The type
    // name `_N5Foo` spells that prefix and the rest of the name. The two types are different, and
    // each must keep its own method.
    okBase(new Res("1\n2", "", 0), """
      package test
      5Foo:{ .x: Nat -> 1 }
      _N5Foo:{ .x: Nat -> 2 }
      Test:Main{sys -> Block#
        .do{sys.io.println(5Foo.x.str)}
        .return{sys.io.println(_N5Foo.x.str)}}
      """, Base.mutBaseAliases);
  }

  @Test void typeNamesWithTheSameHexEscapedZigNameKeepTheirOwnMethods() {
    // A type name can hold a string fragment between backticks. The Zig name of a `(` is an escape
    // that starts with `_U`, and the fragment `_U28` holds the text `_U` and the hex digits of `(`.
    // The two types are different, and each must keep its own method.
    okBase(new Res("1\n2", "", 0), """
      package test
      Foo`(`:{ .x: Nat -> 1 }
      Foo`_U28`:{ .x: Nat -> 2 }
      Test:Main{sys -> Block#
        .do{sys.io.println(Foo`(`.x.str)}
        .return{sys.io.println(Foo`_U28`.x.str)}}
      """, Base.mutBaseAliases);
  }

  @Test void packagesWhoseNamesDifferOnlyByDotAndUnderscoreKeepTheirOwnTypes() {
    // A package name can hold `.` and `_`. The Zig file of a package and the reference to a package
    // in Zig code both come from the package name. The packages `a.b` and `a_b` are different, so
    // they must not give the same file name or the same reference. Each must keep its own type.
    okBase(new Res("1\n2", "", 0), """
      package test
      Test:Main{sys -> Block#
        .do{sys.io.println(a.b.Foo.x.str)}
        .return{sys.io.println(a_b.Foo.x.str)}}
      """, """
      package a.b
      Foo:{ .x: base.Nat -> 1 }
      """, """
      package a_b
      Foo:{ .x: base.Nat -> 2 }
      """, Base.mutBaseAliases);
  }

  @Test void methodNamesWithTheSameEscapedZigNameKeepTheirOwnBodies() {
    // `'` is legal at the end of a Fearless method name. The Zig name of a `'` is the text
    // `_Zapos`, and the method name `.a_Zapos` spells that text. The two methods are different, and
    // each must keep its own body.
    okBase(new Res("1\n2", "", 0), """
      package test
      Foo:{ .a': Nat -> 1, .a_Zapos: Nat -> 2 }
      Test:Main{sys -> Block#
        .do{sys.io.println(Foo.a'.str)}
        .return{sys.io.println(Foo.a_Zapos.str)}}
      """, Base.mutBaseAliases);
  }

  @Test void parameterNamesWithTheSameEscapedZigNameKeepTheirOwnValues() {
    // `'` is legal at the end of a Fearless parameter name. The Zig name of a `'` is the text
    // `_Zapos`, and the parameter name `x_Zapos` spells that text. The two parameters are
    // different, and each must keep its own value.
    okBase(new Res("1\n2", "", 0), """
      package test
      Foo:{
        .first(x': Nat, x_Zapos: Nat): Nat -> x',
        .second(x': Nat, x_Zapos: Nat): Nat -> x_Zapos }
      Test:Main{sys -> Block#
        .do{sys.io.println(Foo.first(1, 2).str)}
        .return{sys.io.println(Foo.second(1, 2).str)}}
      """, Base.mutBaseAliases);
  }

  @Test void aParameterNamedSelfDoesNotClashWithTheReceiver() {
    // The Zig wrapper of a method declares its receiver with a fixed name. A parameter named `self`
    // must not get that name. The parameter must keep its own value.
    okBase(new Res("7", "", 0), """
      package test
      Foo:{ .id(self: Nat): Nat -> self }
      Test:Main{sys -> sys.io.println(Foo.id(7).str)}
      """, Base.mutBaseAliases);
  }

  @Test void typeNamesWithAQuoteInAFragmentKeepTheirText() {
    // A type name can hold a quote inside a backtick fragment. The vtable of a type holds the type
    // name as text, and `Debug.identify` returns that text. The quote must not end the Zig string
    // that holds the name, and the name must keep its text.
    okBase(new Res("test.Foo`a\"b`/0", "", 0), """
      package test
      Foo`a"b`:{}
      Test:Main{sys -> sys.io.println(Debug.identify(Foo`a"b`))}
      """, Base.mutBaseAliases);
  }

  @Test void typeNamesWithABackslashInAFragmentKeepTheirText() {
    // A type name can hold the two characters of a backslash escape inside a backtick fragment.
    // The text of the name has both backslashes. Zig must not read the pair as one backslash.
    okBase(new Res("test.Foo`a\\\\b`/0", "", 0), """
      package test
      Foo`a\\\\b`:{}
      Test:Main{sys -> sys.io.println(Debug.identify(Foo`a\\\\b`))}
      """, Base.mutBaseAliases);
  }

  @Test void typeNamesWithAnEscapedQuoteInAFragmentKeepTheirText() {
    // A backtick fragment can hold a backslash before a quote. The text of the name has the
    // backslash and the quote. Zig must not read the pair as the quote alone.
    okBase(new Res("test.Foo`a\\\"b`/0", "", 0), """
      package test
      Foo`a\\\"b`:{}
      Test:Main{sys -> sys.io.println(Debug.identify(Foo`a\\\"b`))}
      """, Base.mutBaseAliases);
  }

  @Test void typeNamesWithAnEscapedBacktickInAFragmentKeepTheirText() {
    // A backtick fragment can hold a backslash before a backtick. Zig has no escape for that pair.
    // The text of the name has the backslash and the backtick, and the Zig code must build.
    okBase(new Res("test.Foo`a\\`b`/0", "", 0), """
      package test
      Foo`a\\`b`:{}
      Test:Main{sys -> sys.io.println(Debug.identify(Foo`a\\`b`))}
      """, Base.mutBaseAliases);
  }

  @Test void stringLiteralsKeepTheDeleteCharacter() {
    // A unicode escape can put the control character U+007F in a string literal. A Zig string
    // literal cannot hold the raw byte, so the generated code must write it in another way. The
    // program must print the character.
    okBase(new Res("a" + (char) 127 + "b", "", 0), """
      package test
      Test:Main{sys -> sys.io.println("a\\u{127}b")}
      """, Base.mutBaseAliases);
  }
}
