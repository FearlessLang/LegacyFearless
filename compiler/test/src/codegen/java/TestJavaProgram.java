package codegen.java;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import utils.Base;
import utils.ResolveResource;

import java.util.List;

import static codegen.java.RunJavaProgramTests.*;
import static utils.RunOutput.Res;

public class TestJavaProgram {
  @Test void emptyProgram() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main,
    alias base.Void as Void,
    Test:Main{ _ -> {} }
    """);}

  @Test void captureTest() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main,
    alias base.Void as Void,
    Test:Main{ _ -> {} }
    A:{ #: A -> A{ # -> A { # -> this } }# }
    """);}

  @Test void assertTrue() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(True, { Void }) }
    """);}
  @Test void assertFalse() { ok(new Res("", "Program crashed with: \"Assertion failed :(\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, { Void }) }
    """);}
  @Test void assertFalseMsg() { ok(new Res("", "Program crashed with: \"power level less than 9000\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, "power level less than 9000", { Void }) }
    """);}

  @Test void falseToStr() { ok(new Res("", "Program crashed with: \"False\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, Foo.bs(False), { Void }) }
    Foo:{ .bs(b: base.Bool): base.Str -> b.str }
    """);}
  @Test void trueToStr() { ok(new Res("", "Program crashed with: \"True\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, Foo.bs(True), { Void }) }
    Foo:{ .bs(s: base.Stringable): base.Str -> s.str }
    """);}

  @Test void binaryAnd1() { ok(new Res("", "Program crashed with: \"True\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True & True) .str, { Void }) }
    """);}
  @Test void binaryAnd2() { ok(new Res("", "Program crashed with: \"False\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True & False) .str, { Void }) }
    """);}
  @Test void binaryAnd3() { ok(new Res("", "Program crashed with: \"False\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (False & False) .str, { Void }) }
    """);}
  @Test void binaryOr1() { ok(new Res("", "Program crashed with: \"True\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True | True) .str, { Void }) }
    """);}
  @Test void binaryOr2() { ok(new Res("", "Program crashed with: \"True\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (True | False) .str, { Void }) }
    """);}
  @Test void binaryOr3() { ok(new Res("", "Program crashed with: \"True\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (False | True) .str, { Void }) }
    """);}
  @Test void binaryOr4() { ok(new Res("", "Program crashed with: \"False\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (False | False) .str, { Void }) }
    """);}

  @Test void conditionals1() { ok(new Res("", "Program crashed with: \"Assertion failed :(\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(420 > 9000, { Void }) }
    """);}
  @Test void conditionals2() { ok(new Res("", "Program crashed with: \"Assertion failed :(\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!("hi".size > 9000, { Void }) }
    """);}

  @Test void longToStr() { ok(new Res("", "Program crashed with: \"123456789\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 123456789 .str, { Void }) }
    """);}
  @Test void longLongToStr() { ok(new Res("", "Program crashed with: \"9223372036854775807\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 9223372036854775807 .str, { Void }) }
    """);}

  @Test void veryLongLongToStr() { ok(new Res("", "Program crashed with: \"9223372036854775808\"[###]", 1), """
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
  @Test void negativeToStr() { ok(new Res("", "Program crashed with: \"-123456789\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, -123456789 .str, { Void }) }
    """);}

  @Test void addition() { ok(new Res("", "Program crashed with: \"7\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5 + 2) .str, { Void }) }
    """);}
  @Test void addWithUnderscoreInt() { ok(new Res("", "Program crashed with: \"500002\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5_00_000 + 2) .str, { Void }) }
    """);}
  @Test void addWithUnderscoreNat() { ok(new Res("", "Program crashed with: \"500002\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5_00_000 + 2) .str, { Void }) }
    """);}
  @Test void addWithUnderscoreFloat() { ok(new Res("", "Program crashed with: \"500002.6\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5_00_000.5 + 2.1) .str, { Void }) }
    """);}
  @Test void intDivByZero() { ok(new Res("", """
    Program crashed with: / by zero
    
    Stack trace:
    <runtime java.lang.Long>
    test.Test/0
    test.Test/0
    <runtime base.FearlessMain>
    """, 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void, alias base.Block as Do,
    Test:Main{ _ -> Do#(5 / 0) }
    """);}
  @Test void subtraction() { ok(new Res("", "Program crashed with: \"3\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (5 - 2) .str, { Void }) }
    """);}
  @Test void subtractionNeg() { ok(new Res("", "Program crashed with: \"-2\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (-0 - +2) .str, { Void }) }
    """);}
  @Test void subtractionUnderflow() { ok(new Res("", "Program crashed with: \"9223372036854775807\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, ((0 - 2) - 9223372036854775807) .str, { Void }) }
    """);}

  @Test void numSqrtOne() { ok(new Res("", "Program crashed with: \"10\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 100.sqrt.str, { Void }) }
    """);}
  @Test void numSqrtMany() { ok(new Res("",
    "Program crashed with: \"10W2227255841W2227255841W2227255841W3037000499W4294967295W15\"[###]", 1), """
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
  
  @Test void strEq() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{_ -> Assert!("abc" == "abc", {Void})}
    """);}
  @Test void strEqFail() { ok(new Res("", "Program crashed with: \"Assertion failed :(\"[###]", 1), """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{_ -> Assert!("abc" == "def", {Void})}
    """);}

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
  @Disabled // TODO: no mutH lambdas, refactor for the new approach
  @Test void printlnShareLentCapture() { ok(new Res("Hello, World!", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.IO as IO, alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .return{ mutH Usage{ io }# }
      }
    Usage:{
      mutH .io: mutH IO,
      mutH #: Void -> this.io.println("Hello, World!"),
      }
    """); }

  @Test void printlnSugarInferUse() { ok(new Res("Hello, World!", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Block as Block,
    alias base.caps.UnrestrictedIO as UnrestrictedIO,
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .return{ io.println("Hello, World!") }
      }
    """); }

  @Test void nestedPkgs() { ok(new Res("", "", 0), """
    package test
    Test:base.Main{ _ -> {} }
    Bloop:{ #: test.foo.Bar -> { .a -> test.foo.Bar } }
    Foo:{ .a: Foo }
    """, """
    package test.foo
    Bar:test.Foo{ .a -> this }
    """); }

  @Test void nestedConditional() { ok(new Res("2", "", 0), """
    package test
    alias base.Main as Main, alias base.True as True, alias base.False as False, alias base.Nat as Nat,
    Test:Main {sys -> sys.io.println(True ?[Nat] {.then -> False ?[Nat] {.then -> 1, .else -> Block#[Foo,Nat](Foo, 2)}, .else -> 3}.str)}
    Foo: {}
    Block:{#[A:imm,R:imm](_: A, r: R): R -> r}
    """); }

    @Test void ref1() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Assert as Assert,
    alias base.Vars as Vars, alias base.Int as Int,
    alias base.Var as Var,
    Test:Main{ _ -> Assert!((GetVar#(+5))* == +5, { Void }) }
    GetVar:{ #(n: Int): mut Var[Int] -> Vars#n }
    """); }
  @Test void ref2() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Assert as Assert,
    alias base.Vars as Vars, alias base.Int as Int,
    alias base.Var as Var,
    Test:Main{ _ -> Assert!((GetVar#(+5)).swap(+6) == +5, { Void }) }
    GetVar:{ #(n: Int): mut Var[Int] -> Vars#n }
    """); }
  // TODO: loops if we give a broken value like `.let[mut Var[Int]](n = Vars#5)` (not a ReturnStmt)
  @Test void ref3() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main, alias base.Void as Void, alias base.Assert as Assert, alias base.Block as Block,
    alias base.Vars as Vars, alias base.Int as Int, alias base.ReturnStmt as ReturnStmt,
    Test:Main{ _ -> mut Block[Void]
      .let n = { Vars#[Int]+5 }
      .do{ Assert!(n.swap(+6) == +5) }
      .do{ Assert!(n* == +6) }
      .return{{}}
      }
    """); }

  static String cliArgsOrElseGet = """
    package test
    Test :Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let env = { FEnv.io(io) }
      .return{ io.println(ImmMain#(env.launchArgs)) }
      }
    ImmMain:{
      #(args: LList[Str]): Str -> args.tryGet(1).match{.some(arg) -> arg, .empty -> this.errMsg(args.head.isSome).get},
      .errMsg(retCounter: Bool): mut Var[Str] -> Block#
        .let res = { Vars#[mut Var[Str]](Vars#[Str]"Sad") }
        .let counter = { Count.int(+42) }
        .do{ res* := "mutability!" }
        .do{ Block#(counter++) }
        .if{ False }.return{ Vars#[Str]"Short cut" }
        .if{ True }.do{counter *= +9000} // MY POWER LEVELS ARE OVER 9000!!!!!!
        .if{ True }.do{ res* := "moar mutability" }
        .if{ retCounter.not }.return{ res* }
        .return{ Vars#(counter*.str) }
      }
    """;
  @Test void cliArgs1a() { okWithArgs(new Res("moar mutability", "", 0), List.of(), cliArgsOrElseGet, Base.mutBaseAliases); }
  @Test void cliArgs1b() { okWithArgs(new Res("387000", "", 0), List.of(
    "hi"
  ), cliArgsOrElseGet, Base.mutBaseAliases); }
  @Test void cliArgs1c() { okWithArgs(new Res("bye", "", 0), List.of(
    "hi",
    "bye"
  ), cliArgsOrElseGet, Base.mutBaseAliases); }
  String getCliArgsOrElse = """
    package test
    Test: Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let env = { FEnv#s }
      .return{ io.println(ImmMain#(env.launchArgs)) }
      }
    ImmMain:{
      #(args: LList[Str]): Str -> args.get(1) | (this.errMsg(args.head.isSome)*),
      .errMsg(retCounter: Bool): mut Var[Str] -> Block#
        .let res = { Vars#[mut Var[Str]](Vars#[Str]"Sad") }
        .let counter = { Count.int(+42) }
        .do{ res* := "mutability!" }
        .do{ Block#(counter++) }
        .if{ False }.return{ Vars#[Str]"Short cut" }
        .if{ True }.do{ Block#[Int](counter *= +9000) } // MY POWER LEVELS ARE OVER 9000!!!!!!
        .if{ True }.do{ res* := "moar mutability" }
        .if{ retCounter.not }.return{ res* }
        .return{ Vars#(counter*.str) }
      }
    """;
  @Disabled
  @Test void cliArgs2a() { okWithArgs(new Res("moar mutability", "", 0), List.of(), getCliArgsOrElse, Base.mutBaseAliases); }
  @Disabled
  @Test void cliArgs2b() { okWithArgs(new Res("387000", "", 0), List.of(
    "hi"
  ), getCliArgsOrElse, Base.mutBaseAliases); }
  @Disabled
  @Test void cliArgs2c() { okWithArgs(new Res("bye", "", 0), List.of(
    "hi",
    "bye"
  ), getCliArgsOrElse, Base.mutBaseAliases); }

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
  @Test void findClosestIntMut1() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[Int] closest = { Closest#(LList[Int] + +35 + +52 + +84 + +14, +49) }
      .return{ Assert!(closest == +52, closest.str, {{}}) }
      }
    Closest:{
      #(ns: LList[Int], target: Int): Int -> Block#
        .do{ Assert!(ns.isEmpty.not, "empty list :-(", {{}}) }
        .let[Int] closest' = { ns.get(0) }
        .let closest = { Vars#[Int](closest') }
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
  @Test void findClosestIntMut2() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[Int] closest = { Closest#(LList[Int] + +35 + +52 + +84 + +14, +49) }
      .return{ Assert!(closest == +52, closest.str, {{}}) }
      }
    Closest:{
      #(ns: LList[Int], target: Int): Int -> Block#
        .do{ Assert!(ns.isEmpty.not, "empty list :-(", {{}}) }
        .let[mut Var[Int]] closest = { Vars#[Int](ns.head!) }
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
  @Test void findClosestIntMut3() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[Int] closest = { Closest#(LList[Int] + +35 + +52 + +84 + +14, +49) }
      .return{ Assert!(closest == +52, closest.str, {{}}) }
      }
    Closest:{
      #(ns: LList[Int], target: Int): Int -> Block#
        .do{ Assert!(ns.isEmpty.not, "empty list :-(", {{}}) }
        .let[mut Var[Int]] closest = { Vars#[Int](ns.get(0)) }
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
  @Test void findClosestIntMutWithMutLList() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[Int] closest = { Closest#(mut LList[Int] + +35 + +52 + +84 + +14, +49) }
      .return{ Assert!(closest == +52, closest.str, {{}}) }
      }
    Closest:{
      #(ns: mut LList[Int], target: Int): Int -> Block#
        .do{ Assert!(ns.isEmpty.not, "empty list :-(", {{}}) }
        .let closest = { Vars#[Int](ns.get(0)) }
        .do{ mut Closest'{ 'self
          h, t -> h.match{
            .empty -> {},
            .some(n) -> (target - n).abs < ((target - (closest*[])).abs) ? {
              .then -> closest := n,
              .else -> self#(t.head, t.tail)
              }
            }
          }#(ns.tail.head, ns.tail.tail) }
        .return{ closest* }
      }
    Closest':{ mut #(h: mut Opt[Int], t: mut LList[Int]): Void }
    """, Base.mutBaseAliases); }
  @Test void findClosestIntMutWithMutList() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[Int] closest = { Closest#(mut LList[Int] + +35 + +52 + +84 + +14 .list, +49) }
      .return{ Assert!(closest == +52, closest.str, {{}}) }
      }
    Closest:{
      #(ns: mut List[Int], target: Int): Int -> Block#
        .do{ Assert!(ns.isEmpty.not, "empty list :-(", {{}}) }
        .let closest = { Vars#[Int](ns.get(0)) }
        .do{ mut Closest'{ 'self
          i -> ns.tryGet(i).match{
            .empty -> {},
            .some(n) -> (target - n).abs < ((target - (closest*)).abs) ? {
              .then -> closest := n,
              .else -> self#(i + 1)
              }
            }
          }#(1) }
        .return{ closest* }
      }
    Closest':{ mut #(i: Nat): Void }
    """, Base.mutBaseAliases); }

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
  @Test void listIterMut() { ok(new Res("", "", 0), """
    package test
    alias base.iter.Sum as Sum,
    Test:Main{ _ -> Block#
      .let[mut List[Int]] l1 = { (mut LList[Int] + +35 + +52 + +84 + +14).list }
      .assert({ l1.get(0) == (l1.iter.next!) }, "sanity") // okay, time to use this for new tests
      .do{ Assert!((l1.iter.find{n -> n > +60})! == +84, "find some", {{}}) }
      .do{ Assert!((l1.iter.find{n -> n > +100}).isEmpty, "find empty", {{}}) }
      .do{ Assert!(l1.iter
                      .map{n -> n * +10}
                      .find{n -> n == +140}
                      .isSome,
        "map", {{}})}
      .do{ Assert!(l1.iter
                      .filter{n -> n > +50}
                      .find{n -> n == +84}
                      .isSome,
        "filter", {{}})}
      .do{ Assert!(l1.iter
                      .filter{n -> n > +50}
                      .count == 2,
        "count", {{}})}
      .do{ Assert!(l1.iter
                      .filter{n -> n > +50}
                      .list.size == 2,
        "toList", {{}})}
      .do{ Assert!(l1.iter
                      .filter{n -> n > +50}
                      .llist
                      .size == 2,
        "to mut LList", {{}})}
      .do{ Assert!(l1.iter
                    .flatMap{n -> List#(n, n, n).iter}
                    .map{n -> n * +10}
                    .str({n -> n.str}, ";") == "350;350;350;520;520;520;840;840;840;140;140;140",
        "flatMap", {{}})}
      .do{ Assert!(Sum.int(l1.iter) == +185, "sum int", {{}})}
      .do{ Assert!(Sum.nat(l1.iter.map{n -> n.nat}) == 185, "sum uint", {{}})}
      .do{ Assert!(Sum.float(l1.iter.map{n -> n.float}) == 185.0, "sum float", {{}})}
      .return{{}}
      }
    """, Base.mutBaseAliases); }

  @Test void paperExamplePrintIter() { ok(new Res("350,350,350,140,140,140", "", 0), """
    package test
    alias base.Nat as Nat, alias base.Str as Str,
    alias base.List as List, alias base.Block as Block,
    alias base.caps.UnrestrictedIO as UnrestrictedIO, alias base.caps.IO as IO,
    
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
    """);}
  @Test void paperExamplePrintFlow() { ok(new Res("350,350,350,140,140,140", "", 0), """
    package test
    alias base.Nat as Nat, alias base.Str as Str,
    alias base.List as List, alias base.Block as Block,
    alias base.caps.UnrestrictedIO as UnrestrictedIO, alias base.caps.IO as IO,
    alias base.flows.Flow as Flow,
    
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
    """);}

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

  @Test void isoPod1() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .return{ Assert!(Usage#(a!) == +0) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod1Consume() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .return{ Assert!(a.consume{.some(n) -> Usage#n, .empty -> +500} == +0) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod2() { ok(new Res("", "", 0), """
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
  @Test void isoPod3() { ok(new Res("", "", 0), """
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
  @Test void isoPodNoImmFromPeekOk() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .let[Int] ok = { a.peek[Int]{ .some(m) -> m.rn*.int + +0, .empty -> base.Abort! } }
      .return{Void}
      }
    MutThingy:{ mut .n: mut Count[Int], read .rn: read Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { .n -> n, .rn -> n } }
    """, Base.mutBaseAliases); }

  @Test void envFromRootAuth() { okWithArgs(new Res("hi bye", "", 0), List.of("hi", "bye"), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let env = { FEnv#s }
      .return{ io.println(env.launchArgs.iter.str({arg -> arg.str}, " ")) }
      }
    """, Base.mutBaseAliases); }
  @Test void envFromIO() { okWithArgs(new Res("hi bye", "", 0), List.of("hi", "bye"), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .return{ io.println(base.caps.FEnv.io(io).launchArgs.iter.str({arg -> arg.str}, " ")) }
      }
    """, Base.mutBaseAliases); }

  @Test void intExp() { ok(new Res("3125", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .return{ io.println(5 ** 5 .str) }
      }
    """, Base.mutBaseAliases); }
  @Test void uintExp() { ok(new Res("3125", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .return{ io.println(5 ** 5 .str) }
      }
    """, Base.mutBaseAliases); }

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

  @Test void shouldPeekIntoIsoPod() { ok(new Res("""
    peek: help, i'm alive
    consume: help, i'm alive
    """.strip(), "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let s1 = { IsoPod#[Str](iso "help, i'm alive") }
      .do{ PrintMsg#(io, s1) }
      .return{ io.println("consume: " + (s1!)) }
      }
    PrintMsg:{
      #(io: mut IO, msg: read IsoPod[Str]): Void -> msg.peek{
        .some(str) -> io.println("peek: " + (str.str)),
        .empty -> Void
        }
      }
    """, Base.mutBaseAliases); }

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
  @Test void shouldFailOnEmptyIsoPod() { ok(new Res("", "Cannot consume an empty IsoPod.", 0), """
    package test
    Test:Main{ s ->
      Try#[Str]{ Block#
        .let[mut IsoPod[Str]] pod = { IsoPod#[Str] iso "hi" }
        .do{ Block#(pod!) }
        .return{pod!}
        }.run{
          .ok(msg) -> UnrestrictedIO#s.println(msg),
          .info(info) -> UnrestrictedIO#s.printlnErr(info.msg),
        }
      }
    """, Base.mutBaseAliases); }

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

  @Test void equality() {
    ok(new Res("", "", 0), """
      package test
      Test:Main{ _ -> Block#
        .let[Shape] s1 = {{ .x -> +5, .y -> +6 }}
        .let[Shape] s1' = {{ .x -> +5, .y -> +6 }}
        .assert({ s1 == s1 }, "shape eq same id")
        .assert({ s1 == s1' }, "shape same structure")
        .let[Shape] s2 = {{ .x -> +7, .y -> +6 }}
        .assert({ s1 != s2 }, "shape neq")
        .return{{}}
        }
      
      Shape:{
        .x: Int, .y: Int,
        ==(other: Shape): Bool -> (this.x == (other.x)) & (this.y == (other.y)),
        !=(other: Shape): Bool -> this == other .not,
        }
      """, Base.mutBaseAliases);
  }
  @Test void equalitySubtyping() {
    ok(new Res("", "", 0), """
      package test
      Test:Main{ _ -> Block#
        .let[Shape] s1 = {{ .x -> +5, .y -> +6 }}
        .let[Square] sq1 = {{ .x -> +5, .y -> +6, .size -> +12 }}
        .let[Square] sq1' = {{ .x -> +5, .y -> +6, .size -> +12 }}
        .assert({ sq1 == sq1' }, "square eq same structure")
        .assert({ s1 == sq1 }, "shape eq")
        .return{{}}
        }
      
      Shape:{
        .x: Int, .y: Int,
        ==(other: Shape): Bool -> (this.x == (other.x)) & (this.y == (other.y)),
        !=(other: Shape): Bool -> this == other .not,
        }
      Square:Shape{
        read .size: Int,
        .eqSq(other: Square): Bool -> this == other & (this.size == (other.size)),
        }
      """, Base.mutBaseAliases);
  }

  @Test void callingMultiSigAmbiguousDiffRet() { ok(new Res("", "", 0), """
    package test
    alias base.Void as Void, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.As as As,
    A:{
      read .m1: read B -> {},
      mut .m1: mut A -> Assert!(False, {{}}),
      }
    B:{}
    Test:base.Main{
      #(s) -> ToVoid#(this.aRead(mut A)),
//      read .aRead(a: mut A): read B -> a.m1,
      read .aRead(a: mut A): read B -> As[read A]#a.m1,
      }
    ToVoid:{ #[I:**](x: I): Void -> {} }
    """); }
  @Test void callingMultiSigAmbiguousDiffRetMut() { ok(new Res("", "", 0), """
    package test
    alias base.Void as Void, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    A:{
      read .m1: read B -> Assert!(False, {{}}),
      mut .m1: mut A -> this,
      }
    B:{}
    Test:base.Main{
      #(s) -> ToVoid#(this.aRead(mut A)),
      read .aRead(a: mut A): mut A -> a.m1[](),
      }
    ToVoid:{ #[I:**](x: I): Void -> {} }
    """); }
  @Test void callingMultiSigAmbiguousSameRet() { ok(new Res("", "", 0), """
    package test
    alias base.Void as Void, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    A:{
      read .m1: mut A -> Assert!(False, { mut A }),
      mut .m1: mut A -> {},
      }
    B:{}
    Test:base.Main{
      #(s) -> ToVoid#(this.aRead(mut A)),
      read .aRead(a: mut A): mut A -> a.m1[](),
      }
    ToVoid:{ #[I:**](x: I): Void -> {} }
    """); }
  @Test void optionals1() { ok(new Res("", "", 0), """
    package test
    alias base.Void as Void,
    A:{}
    Test:base.Main{
      #(s) -> (base.Opts#Void).match[base.Void](mut base.OptMatch[Void,Void]{ .some(x) -> x, .empty -> {} }),
      }
    """); }
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
  @Test void findClosestIntMultiMdf() { ok(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[Int] closest = { Closest#(LList[Int] + +35 + +52 + +84 + +14, +49) }
      .return{ Assert!(closest == +52, closest.str, {{}}) }
      }
    Closest:{
      #(ns: LList[Int], target: Int): Int -> Block#
        .do{ Assert!(ns.isEmpty.not, "empty list :-(", {{}}) }
        .let[mut Var[Int]] closest = { Vars#[Int](ns.head!) }
        .do{ mut Closest'{ 'self
          h, t -> h.match[Void] mut base.OptMatch[Int,Void]{
            .empty -> {},
            .some(n) -> (target - n).abs < ((target - (closest*[])).abs) ? {
              .then -> closest := n,
              .else -> self#(t.head, t.tail)
              }
            }
          }#(ns.head, ns.tail) }
        .return{ closest*[] }
      }
    Closest':{ mut #(h: Opt[Int], t: LList[Int]): Void }
    """, Base.mutBaseAliases); }
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

  @Test void llistFilterMultiMdfMut() { ok(new Res("13, 14", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let[mut LList[Int]] l = { LList# + +12 + +13 + +14 }
      .do { io.println(A.m1(l)) }
      .return {{}}
      }
    A:{
      .m1(l: mut LList[Int]): Str -> l.iter
                                      .filter{n -> n >= (12.5 .round)}
                                      .str({n->n.str}, ", ")
      }
    """, Base.mutBaseAliases);}
  @Test void listFilterMultiMdfMut() { ok(new Res("13, 14", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let[mut List[Int]] l = { List#[Int](+12, +13, +14) }
      .do { io.println(A.m1(l)) }
      .return {{}}
      }
    A:{
      .m1(l: mut List[Int]): Str -> l.iter
                                     .filter{n -> n >= (12.5 .round)}
                                     .str({n->n.str}, ", ")
      }
    """, Base.mutBaseAliases);}

  @Test void llistFilterMultiMdfRead() { ok(new Res("13, 14", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let[mut LList[Int]] l = { LList#[Int] + +12 + +13 + +14 }
      .do { io.println(A.m1(l)) }
      .return {{}}
      }
    A:{
      .m1(l: read LList[Int]): Str -> l.iter
                                      .filter{n -> n >= (12.5 .round)}
                                      .str({n -> n.str}, ", ")
      }
    """, Base.mutBaseAliases);}
  @Test void listFilterMultiMdfRead() { ok(new Res("13, 14", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let io = { UnrestrictedIO#s }
      .let[mut List[Int]] l = { List#[Int](+12, +13, +14) }
      .do { io.println(A.m1(l)) }
      .return {{}}
      }
    A:{
      .m1(l: read List[Int]): Str -> l.iter
                                     .filter{n -> n >= (12.5 .round)}
                                     .str({n -> n.str}, ", ")
      }
    """, Base.mutBaseAliases);}

  @Test void strMap() { ok(new Res("23\n32\n230\nhi", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .let[mut Var[mut LinkedLens[Str, Nat]]] m = { Vars#[mut LinkedLens[Str, Nat]](mut StrMap[Nat]) }
      .do{ m := (m*.put("Nick", 23)) }
      .do{ m := (m*.put("Bob", 32)) }
      .do{ io.println(m*.get("Nick")!.str) }
      .do{ io.println(m*.get("Bob")!.str) }
      .assert{ m*.get("nobody").isEmpty }
      .let[mut Var[mut LinkedLens[Str, Str]]] tm = { Vars#[mut LinkedLens[Str, Str]](m*.map(
        {k, v -> (v * 10).str },
        {k, v -> (v * 10).str },
        {k, v -> (v.nat * 10).str }
        )) }
      .do{ io.println(tm*.get("Nick")!) }
      .do{ tm := (tm*.put("Nick", "hi")) }
      .do{ io.println(tm*.get("Nick")!) }
      .return{Void}
      }
    """, Base.mutBaseAliases);}
  @Test void strMapImm() { ok(new Res("23\n32\n230\nhi", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .let[mut Var[LinkedLens[Str, Nat]]] m = { Vars#[LinkedLens[Str, Nat]](StrMap[Nat]) }
      .do{ m := (m*.put("Nick", 23)) }
      .do{ m := (m*.put("Bob", 32)) }
      .do{ io.println(m*.get("Nick")!.str) }
      .do{ io.println(m*.get("Bob")!.str) }
      .assert{ m*.get("nobody").isEmpty }
      .let[mut Var[LinkedLens[Str, Str]]] tm = { Vars#[LinkedLens[Str, Str]](m*.map(
        {k, v -> (v * 10).str },
        {k, v -> (v.nat * 10).str }
        )) }
      .do{ io.println(tm*.get("Nick")!) }
      .do{ tm := (tm*.put("Nick", "hi")) }
      .do{ io.println(tm*.get("Nick")!) }
      .return{Void}
      }
    """, Base.mutBaseAliases);}
  @Test void strMapRead() { ok(new Res("23\n32\n230\nhi", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .let[mut Var[read LinkedLens[Str, read Int]]] m = { Vars#[read LinkedLens[Str, read Int]]({k1,k2 -> k1 == k2}) }
      .do{ m := (m*.put("Nick", +23)) }
      .do{ m := (m*.put("Bob", +32)) }
      .do{ io.println(m*.get("Nick")!.str) }
      .do{ io.println(m*.get("Bob")!.str) }
      .assert{ m*.get("nobody").isEmpty }
      .let[mut Var[read LinkedLens[Str, Str]]] tm = { Vars#[read LinkedLens[Str, Str]](m*.map(
        {k, v -> (v * +10).str },
        {k, v -> (v.int * +10).str }
        )) }
      .do{ io.println(tm*.get("Nick")! .str) }
      .do{ tm := (tm*.put("Nick", "hi")) }
      .do{ io.println(tm*.get("Nick")! .str) }
      .return{Void}
      }
    """, Base.mutBaseAliases);}

  @Test void lensMap() { ok(new Res("23\n32\n230\nhi", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let[mut IO] io = { UnrestrictedIO#s }
      .let[mut Var[Lens[Str, Nat]]] m = { Vars#[Lens[Str, Nat]]({k1,k2 -> k1 == k2}) }
      .do{ m := (m*.put("Nick", 23)) }
      .do{ m := (m*.put("Bob", 32)) }
      .do{ io.println(m*.get("Nick")!.str) }
      .do{ io.println(m*.get("Bob")!.str) }
      .assert{ m*.get("nobody").isEmpty }
      .let[mut Var[Lens[Str, Str]]] tm = { Vars#[Lens[Str, Str]](m*.map{k, v -> (v * 10).str }) }
      .do{ io.println(tm*.get("Nick")!) }
      .do{ tm := (tm*.put("Nick", "hi")) }
      .do{ io.println(tm*.get("Nick")!) }
      .return{Void}
      }
    """, Base.mutBaseAliases);}
  @Test void linkedHashMap1() { ok(new Res("23\n32", "", 0), """
    package test
    Test:Main{ s -> Block#
      .let[mut IO] io = {s.io}
      .let[mut LinkedHashMap[Str,Nat]] m = {Maps.hashMap({k1,k2 -> k1 == k2}, {k->k})}
      .do {m.put("Nick", 23)}
      .do {m.put("Bob", 32)}
      .do {io.println(m.get("Nick")!.str)}
      .do {io.println(m.get("Bob")!.str)}
      .assert {m.get("nobody").isEmpty}
      .do {Block#(m.remove("Nick"))}
      .assert {m.get("Nick").isEmpty}
      .assert {m.get("Bob").isSome}
      .return {Void}
      }
    """, Base.mutBaseAliases);}

  @Test void tryCatch1() { ok(new Res("Happy", "", 0), """
    package test
//    Test:Main{s ->
//      UnrestrictedIO#s.println(Try#[Str](
//        {"Happy"},
//        {err->err.str}
//        ))
//      }
    Test:Main{s ->
      UnrestrictedIO#s.println(Try#[Str]{"Happy"}.run{
        .ok(res) -> res,
        .info(err) -> err.str,
        })
      }
    """, Base.mutBaseAliases);}
  @Test void tryCatch2() { ok(new Res("oof", "", 0), """
    package test
//    Test:Main{s ->
//      UnrestrictedIO#s.println(Try#[Str](
//        {Error.msg("oof")},
//        {err->err.str}
//        ))
//      }
    Test:Main{s ->
      UnrestrictedIO#s.println(Try#[Str]{Error.msg("oof")}.run{ .ok(a) -> a, .info(err) -> err.msg })
      }
    """, Base.mutBaseAliases);}
  @Test void error1() { ok(new Res("", "Program crashed with: \"yolo\"[###]", 1), """
    package test
    Test:Main{s -> Error.msg("yolo") }
    """, Base.mutBaseAliases);}
  @Test void emptyOptErr1() { ok(new Res("", "Program crashed with: \"Opt was empty\"[###]", 1), """
    package test
    Test:Main{s -> Block#(Opt[Str]!) }
    """, Base.mutBaseAliases);}
  @Test void emptyOptErr2() { ok(new Res("", "Opt was empty", 0), """
    package test
    Test:Main{s ->
      Try#{Opt[Str]!}.run{
        .ok(_) -> {},
        .info(info) -> UnrestrictedIO#s.printlnErr(info.msg),
        }
      }
    """, Base.mutBaseAliases);}

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

  @Test void codegenCloneMethodBodiesForAbstractMdfOverloadsRead() { ok(new Res(), """
    package test
    alias base.Void as Void, alias base.Main as Main,
    Test: Main { _ -> Void }
    
    OhNo: { #: Void -> Fun#{v -> v} }
    Fun: { #(s: read _Sink[Void, Void]): Void -> s#Void }
    _Sink[T,R]: {
      read #(x: T): Void,
      mut  #(x: T): Void,
      }
    """); }

  @Test void codegenCloneMethodBodiesForAbstractMdfOverloadsMut() { ok(new Res(), """
    package test
    alias base.Void as Void, alias base.Main as Main,
    Test:Main { _ -> Void }
    
    OhNo: { #: Void -> Fun#{v -> v} }
    Fun: { #(s: mut _Sink[Void, Void]): Void -> s#Void }
    _Sink[T,R]: {
      read #(x: T): Void,
      mut  #(x: T): Void,
      }
    """); }

  @Test void errorKToObj() { ok(new Res("", """
    Program crashed with: \"whoops\"
    
    Stack trace:
    <runtime rt.Error>
    base.Error/0
    base.Error/0
    test.Test/0
    test.Test/0
    <runtime base.FearlessMain>
    """, 1), """
    package test
    Test: Main{ _ -> A#(Error.msg "whoops") }
    A:{ #(x: Str): Void -> Void }
    """, Base.mutBaseAliases); }

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
  @Test void eagerCallEarlyExitMulti() { ok(new Res("", "hey", 0), """
    package test
    Test: Main{sys -> Block#
      .if {True} .return {Void}
      .openIso[iso Rez] x1 = (Block#(base.Debug#[Str]"hey", iso Rez))
      .openIso[iso Rez] x2 = (Block#(base.Debug#[Str]"hey2", iso Rez))
      .return {Void}
      }
    Rez: {}
    """, Base.mutBaseAliases); }
  @Test void eagerCallEarlyExitInCont() { ok(new Res("", "", 0), """
    package test
    Test: Main{sys -> Block#
      .if {True} .return {Void}
      .let[Nat] z = {54}
      .openIso[Rez] x = (Block#(base.Debug#[Str]"hey", iso Rez))
      .return {Void}
      }
    Rez: {}
    """, Base.mutBaseAliases); }

  @Test void immOptOfMut() {ok(new Res(), """
    package test
    A:{}
    Test: Main{sys -> Block#
      .let[Opt[mut A]] opt = {Opts#mut A}
      .let[imm A] a = {opt!}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Test void shadowingErr() {fail("""
    In position [###]/Dummy0.fear:5:4
    [E9 shadowingX]
    'foo' is shadowing another variable in scope.
    """, """
    package test
    Test: base.Main{_ -> {}}
    A:{
      .m1(foo: A): B -> B:{
        .m2(foo: B): B -> foo,
        }
      }
    """);}

  @Test void namedLiteral() {ok(new Res("Bob", "", 0), """
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

  @Test void stringableString() { ok(new Res("Hello, World!", "", 0), """
    package test
    Test: Main{sys -> UnrestrictedIO#sys.println(Foo.msg("Hello, World"))}
    Foo: {.msg(start: Stringable): Str -> start.str + "!"}
    """, Base.mutBaseAliases); }
  @Test void literalSubtypeNat() {ok(new Res("25", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(MyNums# .str)}
    MyNums: {#: MyNum -> MyNum: 25{}}
    """, Base.mutBaseAliases);}
  @Test void literalSubtypeStr() {ok(new Res("Nick", "", 0), """
    package test
    Test: Main{sys -> UnrestrictedIO#sys.println(MyNames#)}
    MyNames: {#: MyName -> MyName: "Nick"{}}
    """, Base.mutBaseAliases);}
  @Test void literalSubtypeMultiDiff() {fail("""
    In position [###]/Dummy0.fear:3:23
    [E34 conflictingSealedImpl]
    A sealed trait from another package may not be composed with any other traits.
    conflicts:
    ([###]) base.uStrLit."Nick"/0
    ([###]) base.natLit.123/0
    ([###]) test.Foo/0    
    """, """
    package test
    Test: Main{sys -> UnrestrictedIO#sys.println(MyNames#)}
    MyNames: {#: MyName -> MyName: "Nick", 123, Foo{}}
    Foo: {}
    """, Base.mutBaseAliases);}

  @Test void testHash() {ok(new Res("115\n18446744072677519477\n18446744072677519477\n396592873", "", 0), """
    package test
    alias base.CheapHash as H,
    Custom: ToHash{
      .hash(h) -> h
        .nat 5
        .int +5
        .float 5.0
        .str "5"
      }
    
    Test: Main{sys -> Block#
      .let io = {sys.io}
      .let h = {mut H}
      .let _ = {h.nat 5}
      .let _ = {h.int +5}
      .let _ = {h.float 5.0}
      .let _ = {h.str "5"}
      .do {io.println("5".hash(mut H).compute.str)}
      .do {io.println(h.compute.str)}
      .assert{mut H.hash(Custom).hash(Custom).compute == (Custom.hash(Custom.hash(mut H)).compute)}
      .do {io.println(Custom.hash(mut H).compute.str)}
      .let _ = {Custom.hash(h)}
      .do{io.println(h.compute.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Test void stringConcat() {ok(new Res("Hello, World! Bye!", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(Foo#(" "))}
    Foo: {#(join: Str): Str -> "Hello," + join + "World!" + join + "Bye!"}
    """, Base.mutBaseAliases);}
  @Test void stringConcatMut() {ok(new Res("Hello, World! Bye!", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(Foo#(" "))}
    Foo: {#(join: Str): Str -> mut "Hello," + join + "World!" + join + "Bye!"}
    """, Base.mutBaseAliases);}
  @Test void stringConcatMutAndMut() {ok(new Res("Hello, World! Bye!", "", 0), """
    package test
    Test: Main{sys -> sys.io.println(Foo#(mut " "))}
    Foo: {#(join: mut Str): mut Str -> mut "Hello," + join + mut "World!" + join + mut "Bye!"}
    """, Base.mutBaseAliases);}
  @DisabledOnOs(OS.WINDOWS)
  @Test void simpleJson() {ok(new Res("""
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
    """, 0), ResolveResource.test("/json/main.fear"), ResolveResource.test("/json/pkg.fear"));}

  @Test void expParser() {ok(new Res("", "", 0), """
    package test
    alias base.Int as Num,
    
    Test: Main{_ -> {}}
    
    Exp: {mut .match[R:**](l: mut ExpMatch[R]): R}
    ExpMatch[R:**]: {
      mut .sum(left: mut Var[mut Exp], right: mut Var[mut Exp]): R,
      mut .lit(n: Num): R,
    }
    Exps: {
      .sum(left: mut Var[mut Exp], right: mut Var[mut Exp]): mut Exp -> {m -> m.sum(left, right)},
      .lit(n: Num): mut Exp -> {m -> m.lit(n)},
    }
    
    Lexer: {  mut .nextToken: Token -> Magic! }
    Token: {.match(l: mutH Lexer, m: TokenMatch): iso Exp}
    TokenMatch: {
      .plus(l: mutH Lexer): iso Exp,
      .num(l: mutH Lexer, n: Num): iso Exp,
      .eof(l: mutH Lexer): iso Exp,
    }
    Tokens: { //could not implement TokenMatch
      .plus: Token -> {l, m -> m.plus(l)},
      .num(n: Num): Token -> {l, m -> m.num(l,n)},
      .eof: Token -> {l, m -> m.eof(l)},
    }
    Parser: {//simple right associative parser
      .parse(l: mutH Lexer): iso Exp -> l.nextToken.match(l, {      
        .plus(_)->Error.msg "cannot start with +",        
        .eof(_)->Error.msg "cannot start with EOF",
        .num(l', n) -> this.parse(l', n),
      }),      
      .parse(l: mutH Lexer, left: Num): iso Exp -> l.nextToken.match(l, {
        .plus(l')->Exps.sum(Vars#[mut Exp](Exps.lit(left)),Vars#[mut Exp](this.parse(l'))),
        .eof(_) -> Exps.lit(left),
        .num(l', n) -> Error.msg "unexpected num",
      })
    }
    """, Base.mutBaseAliases);}

  // TODO: more test coverage for bytes
  @Test void strToBytes() {ok(new Res("72,101,108,108,111,33", "", 0), """
    package test
    Test: Main{sys -> sys.io.println("Hello!".utf8.flow.map{b -> b.str}.join ",")}
    """, Base.mutBaseAliases);}
  @Test void byteEq() {ok(new Res(), """
    package test
    Test: Main{_ -> "Hello!".utf8.get(0).assertEq(72 .byte)}
    """, Base.mutBaseAliases);}
  @Test void bytesToStr() {ok(new Res("Hello!", "", 0), """
    package test
    alias base.UTF8 as UTF8,
    Test: Main{sys -> sys.io.println(UTF8.fromBytes("Hello!".utf8)!)}
    """, Base.mutBaseAliases);}
  @Test void bytesToStrManual() {ok(new Res("AB", "", 0), """
    package test
    alias base.UTF8 as UTF8,
    Test: Main{sys -> sys.io.println(UTF8.fromBytes(List#(65 .byte, 66 .byte))!)}
    """, Base.mutBaseAliases);}
  @Test void bytesToStrFail() {ok(new Res("", "Program crashed with: \"incomplete utf-8 byte sequence from index 0\"[###]", 1), """
    package test
    alias base.UTF8 as UTF8,
    Test: Main{sys -> sys.io.println(UTF8.fromBytes(List#(-28 .byte))!)}
    """, Base.mutBaseAliases);}

  @Test void flow() {ok(new Res("Transformed List: 6, 7, 12, 13, 18", "", 0), """
    package test
    
    alias base.Main as AppMain,
    alias base.caps.System as System,
    alias base.caps.UnrestrictedIO as UnrestrictedIO,
    alias base.List as List,
    alias base.flows.Flow as Flow,
    alias base.Int as Int,
    alias base.Str as Str,
    alias base.Block as Block,
    alias base.Void as Void,
    
    Test: AppMain{
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
    """);}
  @Test void flowNoLimit() {ok(new Res("Transformed List: 6, 7, 12, 13, 18, 19", "", 0), """
    package test
    
    alias base.Main as AppMain,
    alias base.caps.System as System,
    alias base.caps.UnrestrictedIO as UnrestrictedIO,
    alias base.List as List,
    alias base.flows.Flow as Flow,
    alias base.Int as Int,
    alias base.Str as Str,
    alias base.Block as Block,
    alias base.Void as Void,
    
    Test: AppMain{
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
    """);}

  @Test void cannotGetMagicFromMockSystem() {ok(new Res("", "Program aborted at:[###]", 1), """
    package test
    Evil: {
      .break: mut System -> {'sys
        .self -> sys,
        .iso -> Abort!,
        .rng -> Abort!,
        .try -> Abort!,
        .io -> Abort!,
        .clock -> Abort!,
        },
      }
    Test: Main{_ -> UnrestrictedIO#(Evil.break).println "oh no"}
    """, Base.mutBaseAliases);}

  @Test void noConcurrentModification() {ok(new Res("6", "", 0), """
    package test
    GetList: F[mut UList[Nat]]{UList# + 1 + 2 + 3 + 4}
    Elem: {read .n: Nat, mut .list: mut UList[mut Elem]}
    BadMutation: {#: Nat -> Block#
      .let[mut UList[mut Elem]] l = {UList#}
      .do {l.add(mut Elem{.n -> 1, .list -> l})}
      .do {l.add(mut Elem{.n -> 2, .list -> l})}
      .do {l.add(mut Elem{.n -> 3, .list -> l})}
      .return {l.iter
        .fold[Nat](0, {acc, e -> acc + (e.n)})
      }
    }
    
    Test: Main{sys -> sys.io.println(BadMutation# .str)}
    """, Base.mutBaseAliases);}
  @DisabledOnOs(OS.WINDOWS)
  @Test void concurrentModification() {ok(new Res("", "Program crashed with: Stack overflowed[###]", 1), """
    package test
    GetList: F[mut UList[Nat]]{UList# + 1 + 2 + 3 + 4}
    Elem: {read .n: Nat, mut .list: mut UList[mut Elem]}
    BadMutation: {#: Nat -> Block#
      .let[mut UList[mut Elem]] l = {UList#}
      .do {l.add(mut Elem{.n -> 1, .list -> l})}
      .do {l.add(mut Elem{.n -> 2, .list -> l})}
      .do {l.add(mut Elem{.n -> 3, .list -> l})}
      .return {l.iter
        .map{e -> Block#(e.list.add(e), e)}
        .fold[Nat](0, {acc, e -> acc + (e.n)})
      }
    }
    
    Test: Main{sys -> sys.io.println(BadMutation# .str)}
    """, Base.mutBaseAliases);}
  @Test void noConcurrentModificationFlow() {ok(new Res("6", "", 0), """
    package test
    GetList: F[mut UList[Nat]]{UList# + 1 + 2 + 3 + 4}
    Elem: {read .n: Nat, mut .list: mut UList[mut Elem]}
    BadMutation: {#: Nat -> Block#
      .let[mut UList[mut Elem]] l = {UList#}
      .do {l.add(mut Elem{.n -> 1, .list -> l})}
      .do {l.add(mut Elem{.n -> 2, .list -> l})}
      .do {l.add(mut Elem{.n -> 3, .list -> l})}
      .return {l.flow
        .map{e -> Block#(e.list.add(e), e)}
        .fold[Nat]({0}, {acc, e -> acc + (e.n)})
      }
    }
    
    Test: Main{sys -> sys.io.println(BadMutation# .str)}
    """, Base.mutBaseAliases);}

  @Test void isEmptyIsSomeOpt() {ok(new Res("Empty: Works\nSome: Works Hello", "", 0), """
    package test
    Test: Main{sys -> Block#
      .do {Opt[Str].ifEmpty{sys.io.println("Empty: Works")}}
      .do {Opts#("Hello").ifEmpty{sys.io.println("Empty: Broken")}}
      .do {Opt[Str].ifSome{x -> sys.io.println("Some: Broken " + x)}}
      .do {Opts#[Str]("Hello").ifSome{x -> sys.io.println("Some: Works " + x)}}
      .return {{}}
    }
    """, Base.mutBaseAliases);}

  @Test void expressionProblemOODecomposition() {ok(new Res("""
    55
    ((5 + 6) * 5)
    """, "", 0), """
    package test
    // Expression problem in Fearless (OO Decomposition solution)
    Expr: {.eval: Nat,}
    ExprMatch[R]: {
      .literal(l: Literal): R,
      .add(a: Add): R,
    }
    Literal: Expr{}
    Literals: F[Nat,Literal]{n -> {n}}
    
    Add: Expr{
      .a: Expr, .b: Expr,
      .eval: Nat -> (this.a.eval) + (this.b.eval),
    }
    
    // New library (B), adding a new expression (Multiplication)
    Mul: Expr{
      .a: Expr, .b: Expr,
      .eval: Nat -> (this.a.eval) * (this.b.eval),
    }
    
    // New library (C), adding a new operation (formatting)
    CExpr: Expr{.format: Str}
    LiteralC: CExpr,Literal{.format -> this.eval.str}
    AddC: CExpr,Add{
      .a: CExpr, .b: CExpr,
      .format -> "(" + (this.a.format) + " + " + (this.b.format) + ")"
    }
    MulC: CExpr,Mul{
      .a: CExpr, .b: CExpr,
      .format -> "(" + (this.a.format) + " * " + (this.b.format) + ")"
    }
    
    // User code
    Test: Main{sys -> Block#
      .let[CExpr] a = {LiteralC{5}}
      .let[CExpr] b = {LiteralC{6}}
      .let[CExpr] add = {AddC{.a -> a, .b -> b}}
      .let[CExpr] mul = {MulC{.a -> add, .b -> a}}
      .do {sys.io.println(mul.eval.str)}
      .do {sys.io.println(mul.format)}
      .return {{}}
    }
    """, Base.mutBaseAliases);}

  @Test void expressionProblemObjectAlgebras() {ok(new Res("""
    55
    ((5 + 6) * 5)
    """, "", 0), """
    package test
    // Library A
    ExprAlg[A]: {
      .literal(n: Nat): A,
      .add(a: A, b: A): A,
    }
    Expr: {#[A](alg: ExprAlg[A]): A}
    
    Eval: ExprAlg[Nat]{
      .literal(n: Nat): Nat -> n,
      .add(a: Nat, b: Nat): Nat -> a + b,
    }
    Format: ExprAlg[Str]{
      .literal(n: Nat): Str -> n.str,
      .add(a: Str, b: Str): Str -> "("+a +" + "+b+")",
    }
    
    TermLit5: Expr{::literal 5}
    TermLit6: Expr{::literal 6}
    TermAdd: Expr{alg -> alg.add(TermLit5#alg, TermLit6#alg)}
    
    // --- Library B: Adding Multiplication (New Variant) ---
    ExprAlgMul[A]: ExprAlg[A]{
      .mul(a: A, b: A): A,
    }
    ExprMul: {#[A](alg: ExprAlgMul[A]): A}
    // Update existing operations to handle the new variant
    EvalMul: Eval,ExprAlgMul[Nat]{
      .mul(a: Nat, b: Nat): Nat -> a * b,
    }
    FormatMul: Format,ExprAlgMul[Str]{
      .mul(a: Str, b: Str): Str -> "("+a+" * "+b+")",
    }
    
    FullTerm: ExprMul{alg ->
      alg.mul(
        alg.add(TermLit5#alg, TermLit6#alg),
        TermLit5#alg
      )
    }
    Test: Main{sys -> Block#
      .let[Nat] evalResult = {FullTerm#EvalMul}
      .let[Str] formatResult = {FullTerm#FormatMul}
      .do {sys.io.println(evalResult.str)}
      .do {sys.io.println(formatResult)}
      .return {{}}
    }
    """, Base.mutBaseAliases);}

  @Test void soundAsIdFnUList() { ok(new Res("1,2,3", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[mut UList[Nat]] l = {UList#(1, 2, 3)}
      .let[UList[Nat]] l2 = {l.as{::}}
      .do {l.add(4)}
      .do {sys.io.println(l2.flow.map{::str}.join ",")}
      .return {{}}
      }
    """, Base.mutBaseAliases);}
  @Test void soundAsUList() { ok(new Res("1,2,3", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[mut UList[Nat]] l = {UList#(1, 2, 3)}
      .let[UList[Str]] l2 = {l.as{::str}}
      .do {l.add(4)}
      .do {sys.io.println(l2.flow.join ",")}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Test void soundAsIdFnList() { ok(new Res("1,2,3", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[mut List[Nat]] l = {List#(1, 2, 3)}
      .let[List[Nat]] l2 = {l.as{::}}
      .do {sys.io.println(l2.flow.map{::str}.join ",")}
      .return {{}}
      }
    """, Base.mutBaseAliases);}
  @Test void soundAsList() { ok(new Res("1,2,3", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let[mut List[Nat]] l = {List#(1, 2, 3)}
      .let[List[Str]] l2 = {l.as{::str}}
      .do {sys.io.println(l2.flow.join ",")}
      .return {{}}
      }
    """, Base.mutBaseAliases);}
}
