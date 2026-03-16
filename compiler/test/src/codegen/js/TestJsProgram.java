package codegen.js;
import codegen.java.RunJavaProgramTests;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.js.RunJsProgramTests.ok;
import static codegen.js.RunJsProgramTests.fail;
import static utils.RunOutput.Res;

public class TestJsProgram {
  @Test void emptyProgram() { ok(new Res("", "", 0), """
    package test
    alias base.Void as Void,
    Test:base.Main{ _ -> {} }
    """);}
  @Test void captureTest() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void,
    Test:Main{ _ -> {} }
    A:{ #: A -> A{ # -> A { # -> this } }# }
    """);}

  /** Assert! **/
  @Test void assertTrue() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(True, { Void }) }
    """);}
  @Test void assertFalse() { ok(new Res("", "Assertion failed :(", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, { Void }) }
    """);}
  @Test void assertFalseMsg() { ok(new Res("", "power level less than 9000", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, "power level less than 9000", { Void }) }
    """);}

  /** Boolean **/
  @Test void falseToStr() { ok(new Res("", "False", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, Foo.bs(False), { Void }) }
    Foo:{ .bs(b: base.Bool): base.Str -> b.str }
    """);}
  @Test void trueToStr() { ok(new Res("", "True", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, Foo.bs(True), { Void }) }
    Foo:{ .bs(s: base.Stringable): base.Str -> s.str }
    """);}
  @Test void binaryAnd1() { ok(new Res("", "True", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True & True) .str, { Void }) }
    """);}
  @Test void binaryAnd2() { ok(new Res("", "False", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True & False) .str, { Void }) }
    """);}
  @Test void binaryAnd3() { ok(new Res("", "False", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (False & False) .str, { Void }) }
    """);}
  @Test void binaryOr1() { ok(new Res("", "True", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True | True) .str, { Void }) }
    """);}
  @Test void binaryOr2() { ok(new Res("", "True", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True | False) .str, { Void }) }
    """);}
  @Test void binaryOr3() { ok(new Res("", "True", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (False | True) .str, { Void }) }
    """);}
  @Test void binaryOr4() { ok(new Res("", "False", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (False | False) .str, { Void }) }
    """);}
  @Test void compare1() { ok(new Res("", "Assertion failed :(", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(420 > 9000, { Void }) }
    """);}
  @Test void compare2() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!("hi".size == 2, { Void }) }
    """);}
  @Test void conditional() { ok(new Res("1", "", 0), """
    package test
    alias base.Main as Main, alias base.True as True, alias base.Nat as Nat,
    Test:Main {sys -> sys.io.println(True ?[Nat] {.then -> 1, .else -> 3}.str)}
    """); }
  @Test void nestedConditional() { ok(new Res("2", "", 0), """
    package test
    alias base.Main as Main, alias base.True as True, alias base.False as False, alias base.Nat as Nat,
    Test:Main {sys -> sys.io.println(True ?[Nat] {.then -> False ?[Nat] {.then -> 1, .else -> Block#[Foo,Nat](Foo, 2)}, .else -> 3}.str)}
    Foo: {}
    Block:{#[A:imm,R:imm](_: A, r: R): R -> r}
    """); }

  /** String **/
  // .str
  @Test void longToStr() { ok(new Res("", "123456789", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 123456789 .str, { Void }) }
    """);}
  @Test void longLongToStr() { ok(new Res("", "9223372036854775807", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 9223372036854775807 .str, { Void }) }
    """);}
  @Test void veryLongLongToStr() { ok(new Res("", "9223372036854775808", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 9223372036854775808 .str, { Void }) }
    """);}
  @Test void veryLongLongIntFail() { fail("""
    [E31 invalidNum]
    The number +9223372036854775808 is not a valid Int
    """, """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, +9223372036854775808 .str, { Void }) }
    """);}
  @Test void veryLongLongNatFail() { fail("""
    [E31 invalidNum]
    The number 10000000000000000000000 is not a valid Nat
    """, """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 10000000000000000000000 .str, { Void }) }
    """);}
  @Test void negativeToStr() { ok(new Res("", "-123456789", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, -123456789 .str, { Void }) }
    """);}
  @Test void stringableString() { ok(new Res("Hello, World!", "", 0), """
    package test
    Test: Main{sys -> UnrestrictedIO#sys.println(Foo.msg("Hello, World"))}
    Foo: {.msg(start: Stringable): Str -> start.str + "!"}
    """, Base.mutBaseAliases); }
  // literal
  @Test void literalSubtypeNat() {
    ok(new Res("25", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(MyNums# .str)}
    MyNums: {#: MyNum -> MyNum: 25{}}
    """, Base.mutBaseAliases);}
  @Test void literalSubtypeStr() {
    ok(new Res("Mei", "", 0), """
    package test
    Test: Main{sys -> UnrestrictedIO#sys.println(MyNames#)}
    MyNames: {#: MyName -> MyName: "Mei"{}}
    """, Base.mutBaseAliases);}
  @Test void stringConcatMut() {
    ok(new Res("Hello, World! Bye!", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(Foo#(" "))}
    Foo: {#(join: Str): Str -> mut "Hello," + join + "World!" + join + "Bye!"}
    """, Base.mutBaseAliases);}
  @Test void stringConcatMutAndMut() {
    ok(new Res("Hello, World! Bye!", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(Foo#(mut " "))}
    Foo: {#(join: mut Str): mut Str -> mut "Hello," + join + mut "World!" + join + mut "Bye!"}
    """, Base.mutBaseAliases);}
  @Test void substring() {
    ok(new Res("less", "", 0), """
    package test
    Test: Main{sys -> sys.io.println("Fearless".substring(4, 8))}
    """, Base.mutBaseAliases);}
  @Test void normalise() {
    ok(new Res("\\u00E9", "", 0), """
    package test
    Test: Main{sys -> sys.io.println("\\\\u00E9".normalise)}
    """, Base.mutBaseAliases);
  }

  /** utf8 **/
  @Test void strToBytes() {
    ok(new Res("72,101,108,108,111,33", "", 0), """
    package test
    Test: Main{sys -> sys.io.println("Hello!".utf8.flow.map{b -> b.str}.join ",")}
    """, Base.mutBaseAliases);}
  @Test void byteEq() {
    ok(new Res(), """
    package test
    Test: Main{_ -> "Hello!".utf8.get(0).assertEq(72 .byte)}
    """, Base.mutBaseAliases);}
  @Test void bytesToStr() {
    ok(new Res("Hello!", "", 0), """
    package test
    alias base.UTF8 as UTF8,
    Test: Main{sys -> sys.io.println(UTF8.fromBytes("Hello!".utf8)!)}
    """, Base.mutBaseAliases);}
  @Test void bytesToStrManual() {
    ok(new Res("AB", "", 0), """
    package test
    alias base.UTF8 as UTF8,
    Test: Main{sys -> sys.io.println(UTF8.fromBytes(List#(65 .byte, 66 .byte))!)}
    """, Base.mutBaseAliases);}
  @Test void bytesToStrFail() {
    ok(new Res("", "Invalid UTF-8 sequence", 1), """
    package test
    alias base.UTF8 as UTF8,
    Test: Main{sys -> sys.io.println(UTF8.fromBytes(List#(-28 .byte))!)}
    """, Base.mutBaseAliases);}
  @Test void strEq() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{_ -> Assert!("abc" == "abc", {Void})}
    """);}
  @Test void strEqFail() { ok(new Res("", "Assertion failed :(", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{_ -> Assert!("abc" == "def", {Void})}
    """);}

  /** Number **/
  @Test void addition() { ok(new Res("", "7", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5 + 2) .str, { Void }) }
    """);}
  @Test void addWithUnderscoreInt() { ok(new Res("", "500002", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5_00_000 + 2) .str, { Void }) }
    """);}
  @Test void addWithUnderscoreNat() { ok(new Res("", "500002", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5_00_000 + 2) .str, { Void }) }
    """);}
  @Test void addWithUnderscoreFloat() { ok(new Res("", "500002.6", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5_00_000.5 + 2.1) .str, { Void }) }
    """);}

  @Test void intDivByZero() { ok(new Res("", """
    RangeError: Division by zero
        at test$$Test_0.$hash$imm$2$fun ([###])
        [###]
    """, 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void, alias base.Block as Do,
    Test:Main{ _ -> Do#(5 / 0) }
    """);}
  @Test void subtraction() { ok(new Res("", "3", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5 - 2) .str, { Void }) }
    """);}
  @Test void subtractionNeg() { ok(new Res("", "-2", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (-0 - +2) .str, { Void }) }
    """);}
  @Test void subtractionUnderflow() { ok(new Res("", "9223372036854775807", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, ((0 - 2) - 9223372036854775807) .str, { Void }) }
    """);}
  @Test void modulo() {
    ok(new Res("1", "", 0), """
      package test
      alias base.Main as Main,
      Test:Main{sys -> sys.io.println((5 % 2) .str) }
      """);
  }
  @Test void exponent() {
    ok(new Res("8", "", 0), """
      package test
      alias base.Main as Main,
      Test:Main{sys -> sys.io.println((2 ** 3) .str) }
      """);
  }
  // abs
  @Test void absIntPos() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Assert!(+5 .abs == +5) }
    """, Base.mutBaseAliases); }
  @Test void absIntZero() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Assert!(+0 .abs == +0) }
    """, Base.mutBaseAliases); }
  @Test void absIntNeg() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Assert!(-5 .abs == +5) }
    """, Base.mutBaseAliases); }

  @Test void absNatPos() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Assert!(5 .abs == 5) }
    """, Base.mutBaseAliases); }
  @Test void absNatZero() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Assert!(0 .abs == 0) }
    """, Base.mutBaseAliases); }
  // sqrt
  @Test void sqrt() {
    ok(new Res("3", "", 0), """
      package test
      alias base.Main as Main,
      Test:Main{sys -> sys.io.println((9).sqrt.str) }
      """);
  }
  @Test void numSqrtOne() { ok(new Res("", "10", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 100.sqrt.str, { Void }) }
    """);}
  @Test void numSqrtMany() { ok(new Res("",
    "10W2227255841W2227255841W2227255841W3037000499W4294967295W15", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False,
      100.sqrt.str
      +"W"+(4960668585723128321.int.sqrt.str)//Why we need .str? is + not taking a ToStr?
      +"W"+(4960668585723128325.int.sqrt.str)
      +"W"+(4960668585723128399.int.sqrt.str)
      +"W"+(9223372036854775807.int.sqrt.str)
      +"W"+(18446744073709551615.nat.sqrt.str)
      +"W"+(255.byte.sqrt.str),
      { Void }) }
    """);}
  @Test void assertEq() { ok(new Res("", "", 0), """
    package test
    Test: Main{_ -> 5.assertEq(5)}
    """, Base.mutBaseAliases); }
  @Test void numOpsOrder() {
    ok(new Res("", "", 0), """
      package test
      Test:Main{ _ -> Block#
        .do{ Assert!(1 + 2 * 3 == 9, "order of ops left-associative", {{}}) }
        .do{ Assert!(1 + (2 * 3) == 7, "order of ops", {{}}) }
        .do{ Assert!(10 - 3 * 2 == 14, "subtraction left-associative", {{}}) }
        .do{ Assert!(8 / 2 + 1 == 5, "division and addition", {{}}) }
      .return{{}} }
      """, Base.mutBaseAliases); }
  @Test void comparisonOperators() {
    ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .do{ Assert!((5 > 2), "greater than", {{}}) }
      .do{ Assert!((-5000 < -1000), "less than", {{}}) }
      .do{ Assert!((0 == 0), "equality", {{}}) }
      .do{ Assert!((5.1 != 5.0), "inequality", {{}}) }
      .return{{}}
    }
    """, Base.mutBaseAliases);
  }
  @Test void bitwiseOperations() {
    ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .do{ Assert!((5.bitwiseAnd 2) == 0, "bitwise AND", {{}}) }
      .do{ Assert!((5.bitwiseOr 2) == 7, "bitwise OR", {{}}) }
      .do{ Assert!((5.xor 2) == 7, "bitwise XOR", {{}}) }
      .do{ Assert!((5.shiftLeft 2) == 20, "shift left", {{}}) }
      .do{ Assert!((5.shiftRight 2) == 1, "shift right", {{}}) }
      .return{{}}
    }
    """, Base.mutBaseAliases);
  }
  @Test void negativeNums() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .do{ Assert!(-5 == -5, "id", {{}}) }
      .do{ Assert!((-5 - -5) == +0, "subtraction 1", {{}}) }
      .do{ Assert!((-5 - +5) == -10, "subtraction 2", {{}}) }
      .do{ Assert!((-5 + +3) == -2, "addition 1", {{}}) }
      .do{ Assert!((-5 + +7) == +2, "addition 2", {{}}) }
      .do{ Assert!((+5 + -7) == -2, "addition 3", {{}}) }
      .return{{}}
      }
    """, Base.mutBaseAliases); }
  @Test void floats() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .do{ Assert!(-5.0 == -5.0, "id (neg)", {{}}) }
      .do{ Assert!((-5.0 - -5.0) == 0.0, "subtraction 1", {{}}) }
      .do{ Assert!((-5.0 - 5.0) == -10.0, "subtraction 2", {{}}) }
      .do{ Assert!((-5.0 + 3.0) == -2.0, "addition 1", {{}}) }
      .do{ Assert!((-5.0 + 7.0) == 2.0, "addition 2", {{}}) }
      .do{ Assert!((5.0 + -7.0) == -2.0, "addition 3", {{}}) }
      // pos
      .do{ Assert!(1.0 == 1.0, "id", {{}}) }
      .do{ Assert!(1.0 + 3.5 == 4.5, "addition 1 (pos)", {{}}) }
      .do{ Assert!((1.0).str == "1", "str pt 0", {{}}) }
      .do{ Assert!((1.5).str == "1.5", "str pt 5", {{}}) }
      .do{ Assert!((5.0 / 2.0) == 2.5, (5.0 / 2.0).str, {{}}) }
      .return{{}}
      }
    """, Base.mutBaseAliases); }

  /** Opt **/
  @Test void optionalMapImm() {
    ok(new Res("", "", 0), """
      package test
      Test:Main{ _ -> Block#
        .let[Opt[Int]] i = { Opts#[Int]+16 }
        .let[Opt[Int]] ix10 = { i.map{n -> n * +10} }
        .return{{}}
        }
      """, Base.mutBaseAliases);
  }
  @Test void canGetImmOptFromImmListOfImmInt() { ok(new Res("", "", 0), """
    package test
    MakeList:{ #: LList[Int] -> LList[Int] + +12 + +34 + +56 }
    Test:Main{ _ -> Block#
      .let myList = { MakeList# }
      .let[Opt[Int]] opt = { myList.head }
      .let[Int] i1 = { opt! }
      .let[Int] i2 = { myList.head! }
      .return{Void}
      }
    """, Base.mutBaseAliases); }
  @Test void findClosestInt() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[Int] closest = { Closest#(LList[Int] + +35 + +52 + +84 + +14, +49) }
      .return{ Assert!(closest == +52, closest.str, {{}}) }
      }
    Closest:{
      #(ns: LList[Int], target: Int): Int -> Block#
        .do{ Assert!(ns.isEmpty.not, "empty list :-(", {{}}) }
        .let[mut Var[Int]] closest = { Vars#(ns.head!) }
        .do{ mut Closest'{ 'self
          h, t -> h.match{
            .empty -> {},
            .some(n) -> (target - n).abs < ((target - (closest*)).abs) ? {
              .then -> closest := n,
              .else -> self#(t.head, t.tail)
              }
            }
          }#(ns.head, ns.tail) }
        .return{ closest* }
      }
    Closest':{ mut #(h: Opt[Int], t: LList[Int]): Void }
    """, Base.mutBaseAliases); }

  /** Block **/
  @Test void lazyCall() { ok(new Res("hey", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[Void] x = {UnrestrictedIO#sys.println("hey")}
      .return {Void}
      }
    """, Base.mutBaseAliases); }
  @Test void lazyCallEarlyExit() { ok(new Res("", "", 0), """
    package test
    Test: Main{sys -> Block#
      .if {True} .return {Void}
      .let[Void] x = {UnrestrictedIO#sys.println("hey")}
      .return {Void}
      }
    """, Base.mutBaseAliases); }
  @Test void eagerCallEarlyExit() { ok(new Res("", "hey", 0), """
    package test
    Test: Main{sys -> Block#
      .if {True} .return {Void}
      .openIso[iso Rez] x = (Block#(base.Debug#[Str]"hey", iso Rez))
      .return {Void}
      }
    Rez: {}
    """, Base.mutBaseAliases); }
  @Test void incrementLoop() { ok(new Res("", "", 0), """
    package test
    Test:Main {sys -> Block#
      .let n = {Count.int(+0)}
      .loop {Block#
        .if {n.get == +10} .return {ControlFlow.break}
        .do {Block#(n++)}
        .return {ControlFlow.continue}
        }
      .assert {n.get == +10}
      .return {Void}
      }
    """, Base.mutBaseAliases); }

  /** List **/
  @Test void listFlowJoin() {
    ok(new Res("A,B,C,D,E", "", 0),
      """
      package test
      alias base.List as List, alias base.Int as Int, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str,
      Test:Main{ s -> s.io.println(List#("A", "B", "C", "D", "E").flow.join ",")}
      """);
  }
  @Test void listFlowLimitJoin() {
    ok(new Res("A,B", "", 0),
      """
      package test
      alias base.List as List, alias base.Int as Int, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str,
      Test:Main{ s -> s.io.println(List#("A", "B", "C", "D", "E").flow.limit(2).join ",")}
      """);
  }
  @Test void LListItersIterImm() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[LList[Int]] l1 = { LList[Int] + +35 + +52 + +84 + +14 }
      .do{ Assert!(l1.head! == (l1.iter.next!), "sanity", {{}}) }
      .do{ Assert!((l1.iter.find{n -> n > +60})! == +84, "find some", {{}}) }
      .do{ Assert!((l1.iter.find{n -> n > +100}).isEmpty, "find empty", {{}}) }
      .do{ Assert!((l1.iter
                      .map{n -> n * +10}
                      .find{n -> n == +140})
                      .isSome,
        "map", {{}})}
      .return{{}}
      }
    """, Base.mutBaseAliases); }
  @Test void LListItersIterMut() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut LList[Int]] l1 = { mut LList[Int] +[] +35 +[] +52 +[] +84 +[] +14 }
      .do{ Assert!(l1.head! == (l1.iter.next!), "sanity", {{}}) }
      .do{ Assert!((l1.iter.find{n -> n > +60})! == +84, "find some", {{}}) }
      .do{ Assert!((l1.iter.find{n -> n > +100}).isEmpty, "find empty", {{}}) }
      .do{ Assert!(l1.iter
                      .map{n -> n * +10}
                      .find{n -> n == +140}
                      .isSome,
        "map", {{}})}
      .return{{}}
      }
    """, Base.mutBaseAliases); }

  @Test void llistFilterMultiMdf() { ok(new Res("13, 14", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let[LList[Int]] l = { LList# + +12 + +13 + +14 }
      .do { io.println(A.m1(l)) }
      .return {{}}
      }
    A:{
      .m1(l: LList[Int]): Str -> l.iter
                                 .filter{n -> n >= (12.5 .round)}
                                 .str({n->n.str}, ", ")
      }
    """, Base.mutBaseAliases);}
  @Test void listFilterMultiMdf() { ok(new Res("13, 14", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let[List[Int]] l = { List#(+12, +13, +14) }
      .do { io.println(A.m1(l)) }
      .return {{}}
      }
    A:{
      .m1(l: List[Int]): Str -> l.iter
                                 .filter{n -> n >= (12.5 .round)}
                                 .str({n->n.str}, ", ")
      }
    """, Base.mutBaseAliases);}
  @Test void paperExamplePrintIter() { ok(new Res("350,350,350,140,140,140", "", 0), """
    package test
    Test :base.Main{ sys -> Block#
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
        .let io = {UnrestrictedIO#sys}
        .return {io.println(msg)}
        // prints 350,350,350,140,140,140
    }
    """, Base.mutBaseAliases);}
  @Test void paperExamplePrintFlow() { ok(new Res("350,350,350,140,140,140", "", 0), """
    package test
    Test: base.Main{ sys -> Block#
        .let l1 = { List#[Nat](35, 52, 84, 14) }
        .assert{l1.iter
          .map{n -> n * 10}
          .find{n -> n == 140}
          .isSome}
        .let[Str] msg = {l1.flow
          .filter{n -> n < 40}
          .flatMap{n -> List#(n, n, n).flow}
          .map{n -> n * 10}
          .map{n -> n.str}
          .join(",")}
        .let io = {UnrestrictedIO#sys}
        .return {io.println(msg)}
        // prints 350,350,350,140,140,140
    }
    """, Base.mutBaseAliases);}
  // as
  @Test void soundAsUList() { ok(new Res("1,2,3", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[mut List[Nat]] l = {List#(1, 2, 3)}
      .let[List[Str]] l2 = {l.as{::str}}
      .do {l.add(4)}
      .do {sys.io.println(l2.flow.join ",")}
      .return {{}}
      }
    """, Base.mutBaseAliases);}
  @Test void canGetImmIntFromImmListOfImmInt() { ok(new Res("", "", 0), """
    package test
    MakeList:{ #: LList[Int] -> LList[Int] + +12 + +34 + +56 }
    Test:Main{ _ -> Block#
      .let myList = { MakeList# }
      .assert({ As[Int]#(myList.head!) == +12 }, myList.head!.str)
      .assert({ As[Int]#(myList.tail.head!) == +34 }, "can get 2nd tail el")
      .assert({ myList.head! == +12 }, "can get head el without cast")
      .assert({ myList.tail.head! == +34 }, "can get 2nd tail el without cast")
      .return{Void}
      }
    """, Base.mutBaseAliases); }

  /** Flow **/
  @Test void flowNoLimit() {
    ok(new Res("Transformed List: 6, 7, 12, 13, 18, 19", "", 0), """
    package test
    Test: Main{
      #(sys) -> Block#
        .let[mut List[Int]] numbers = {List#(+1, +2, +3, +4, +5, +6, +7, +8, +9, +10)}

        // Create a flow from the list
        .let[mut Flow[Int]] flow = {numbers.flow}

        // Apply various flow operations
        .let[mut Flow[Int]] transformedFlow = {flow
          .map{n -> n * +2} // Multiply each number by 2
          .filter{n -> n % +3 == +0} // Keep only numbers divisible by 3
          .flatMap{n -> Flow#(n, n + +1)} // For each number, create a flow of the number and the number + 1
        }

        // Collect the results into a list
        .let[mut List[Int]] resultList = {transformedFlow.list}

        // Print the results
        .do {sys.io.println("Transformed List: " + (resultList.flow.map{num -> num.str}.join ", "))}

        .return {Void}
    }
    """, Base.mutBaseAliases);}

  @Test void flow() {
    ok(new Res("Transformed List: 6, 7, 12, 13, 18", "", 0), """
    package test
    
    Test: Main{
      #(sys) -> Block#
        .let[mut List[Int]] numbers = {List#(+1, +2, +3, +4, +5, +6, +7, +8, +9, +10)}

        // Create a flow from the list
        .let[mut Flow[Int]] flow = {numbers.flow}

        // Apply various flow operations
        .let[mut Flow[Int]] transformedFlow = {flow
          .map{n -> n * +2} // Multiply each number by 2
          .filter{n -> n % +3 == +0} // Keep only numbers divisible by 3
          .flatMap{n -> Flow#(n, n + +1)} // For each number, create a flow of the number and the number + 1
          .limit(5) // Limit the flow to the first 5 elements
        }

        // Collect the results into a list
        .let[mut List[Int]] resultList = {transformedFlow.list}

        // Print the results
        .do {sys.io.println("Transformed List: " + (resultList.flow.map{num -> num.str}.join ", "))}

        .return {Void}
    }
    """, Base.mutBaseAliases);}

  /** sys **/
  @Test void sysPrint() { ok(new Res("Hello, World!", "", 0), """
    package test
    alias base.Main as Main, alias base.True as True, alias base.Nat as Nat,
    Test:Main {sys -> sys.io.println("Hello, World!")}
    """); }
  @Test void println() { ok(new Res("Hello, World!", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    Test:Main{ s -> Block#
      .let({ base.caps.UnrestrictedIO#s }, { io, s' -> s'.return{ io.println "Hello, World!" } })
      }
    """);}
  @Test void printlnSugar() { ok(new Res("Hello, World!", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.IO as IO, alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .return{ io.println("Hello, World!") }
      }
    """); }
  @Test void printlnDeeper() { ok(new Res("IO begets IO", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.IO as IO, alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .return{ Block#
        .let io2 = { base.caps.UnrestrictedIO#s }
        .return{ io2.println("IO begets IO") }
        }
      }
    """); }
  @Test void print() { ok(new Res("Hello, World!", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.IO as IO, alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .do{ io.print("Hello") }
      .return{ io.print(", World!") }
      }
    """); }
  @Test void printlnErr() { ok(new Res("", "Hello, World!", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.IO as IO, alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .return{ io.printlnErr("Hello, World!") }
      }
    """); }
  @Test void printErr() { ok(new Res("", "Hello, World!", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.IO as IO, alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .do{ io.printErr("Hello") }
      .return{ io.printErr(", World!") }
      }
    """); }
  @Test void printlnShareLent() { ok(new Res("Hello, World!", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.IO as IO, alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .return{ Usage#io }
      }
    Usage:{
      #(io: mutH IO): Void -> io.println("Hello, World!"),
      }
    """); }

  /** inheritance **/
  @Test void personFactory() { ok(new Res("Bob", "", 0), """
    package test
    FPerson:F[Str,Nat,Person]{ name, age -> Person:{
      .name: Str -> name,
      .age: Nat -> age,
      }}
    Test: Main{
      #(sys) -> UnrestrictedIO#sys.println(this.name(this.create)),

      .create: Person -> FPerson#("Bob", 24),
      .name(p: Person): Str -> p.name,
      }
    """, Base.mutBaseAliases);}
  @Test void overload() {ok(new Res("bear", "", 0), """
    package test
    alias base.Str as Str, alias base.Main as Main,
    Animal: {
      .name: Str -> "animal",
      .name(a:Str): Str -> a+"animal",
      .run: Str  // abstract, not implemented
    }
    Bear: Animal {
      .name: Str -> "bear"
    }
    BrownBear: Bear {
      .run: Str -> "BrownBear runs fast"
    }
    Test: Main{sys -> sys.io.println(BrownBear.name)}
    """);}

  /** mut imm read iso **/
  @Test void canCreateMutLList() { ok(new Res("", "", 0), """
    package test
    Test:base.Main{ _ -> {} }
    MutLList:{ #: mut base.LList[base.Int] -> mut base.LList[base.Int] + +35 + +52 + +84 + +14 }
    """); }

  @Test void immFromVarImmPrimitive() { ok(new Res("5", "", 0), """
    package test
    Test:Main{
      #(s) -> UnrestrictedIO#s.println(this.m2.str),
      .m1(r: read Var[Int]): Int -> r.get,
      .m2: Int -> this.m1(Vars#(+5)),
      }
    """, Base.mutBaseAliases); }
  @Test void namedLiteral() {
    ok(new Res("Bob", "", 0), """
    package test
    Test: Main{sys -> UnrestrictedIO#sys.println(CanCall# .str)}

    Bob:{read .str: Str -> "Bob"}
    Bar[X]: {.m(x: X): mut Foo[X] -> mut Foo[X]:{
      mut .get: X -> x
      }}
    CanCall: {#: Bob -> Bar[Bob].m(Bob).get}
    """, Base.mutBaseAliases);}

  @Test void immThisAsImmInReadMethod() { ok(new Res("cool", "", 0), """
    package test
    Test: Main{sys -> UnrestrictedIO#sys.println(A.m1.str)}
    A: {.m1: imm B -> B: {'self
      imm .foo: B -> self,
      read .bar: B -> self.foo,
      imm .str: "cool" -> "cool",
      }}
    """, Base.mutBaseAliases); }
  @Test void noConcurrentModification() {
    ok(new Res("6", "", 0), """
    package test
    GetList: F[mut List[Nat]]{List# + 1 + 2 + 3 + 4}
    Elem: {read .n: Nat, mut .list: mut List[mut Elem]}
    BadMutation: {#: Nat -> Block#
      .let[mut List[mut Elem]] l = {List#}
      .do {l.add(mut Elem{.n -> 1, .list -> l})}
      .do {l.add(mut Elem{.n -> 2, .list -> l})}
      .do {l.add(mut Elem{.n -> 3, .list -> l})}
      .return {l.iter
        .fold[Nat](0, {acc, e -> acc + (e.n)})
      }
    }

    Test: Main{sys -> sys.io.println(BadMutation# .str)}
    """, Base.mutBaseAliases);}

  /** Error **/
  @Test void error1() {
    ok(new Res("", "Program crashed with: yolo[###]", 1), """
      package test
      Test:Main{s -> Error.msg("yolo") }
      """, Base.mutBaseAliases);
  }
  @Test void emptyOptErr1() {
    ok(new Res("", "Program crashed with: Opt was empty[###]", 1), """
      package test
      Test:Main{s -> Block#(Opt[Str]!) }
      """, Base.mutBaseAliases);
  }
  @Test void emptyOptErr2() {
    ok(new Res("", "Opt was empty", 0), """
      package test
      Test:Main{s ->
        Try#{Opt[Str]!}.run{
          .ok(_) -> {},
          .info(info) -> UnrestrictedIO#s.printlnErr(info.msg),
          }
        }
      """, Base.mutBaseAliases);
  }
  @Test void noMagicWithManualCapability() { ok(new Res("", "No magic code was found[###]", 1), """
    package test
    Test: Main{_ -> mut FakeIO.println("oh no")}
    FakeIO: IO{
      .print(msg) -> Magic!,
      .println(msg) -> Magic!,
      .printErr(msg) -> Magic!,
      .printlnErr(msg) -> Magic!,
      .accessR(_) -> Magic!,
      .accessW(_) -> Magic!,
      .accessRW(_) -> Magic!,
      .env -> Magic!,
      .iso -> iso FakeIO,
      .self -> this,
      }
    """, Base.mutBaseAliases); }

  /** Try **/
  @Test void tryCatch1() {
    ok(new Res("Happy", "", 0), """
        package test
        Test:Main{s ->
          UnrestrictedIO#s.println(Try#[Str]{"Happy"}.run{
            .ok(res) -> res,
            .info(err) -> err.str,
            })
          }
      """, Base.mutBaseAliases);
  }
  @Test void tryCatch2() {
    ok(new Res("oof", "", 0), """
        package test
        Test:Main{s ->
          UnrestrictedIO#s.println(Try#[Str]{Error.msg("oof")}.run{ .ok(a) -> a, .info(err) -> err.msg })
          }
      """, Base.mutBaseAliases);
  }

  /** IsoPod **/
  @Test void shouldReadFullIsoPod() { ok(new Res("hi", "", 0), """
  package test
  Test:Main{ s ->
    Try#[Str]{ Block#
      .let[mut IsoPod[Str]] pod = { IsoPod#[Str] iso "hi" }
      .return{pod!}
      }.run{
        .ok(msg) -> UnrestrictedIO#s.println(msg),
        .info(info) -> UnrestrictedIO#s.printlnErr(info.str),
      }
    }
  """, Base.mutBaseAliases); }

}