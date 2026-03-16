package codegen.js;

import main.CompilerFrontEnd;
import main.InputOutput;
import main.Main;
import main.js.LogicMainJs;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;
import utils.Err;
import utils.RunOutput;

import java.util.Arrays;
import java.util.List;

import static codegen.js.RunJsProgramTests.ok;

public class TestJsCodegen {
  JsProgram getCode(String... content) {
    Main.resetAll();
    var vb = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var main = LogicMainJs.of(InputOutput.programmaticAuto(Arrays.asList(content)), vb);
    var fullProgram = main.parse(); // builds the AST from source files (base + test)
    main.wellFormednessFull(fullProgram); // checks semantic correctness of the high-level AST
    var program = main.inference(fullProgram); // runs type inference, producing a typed AST
    main.wellFormednessCore(program); // validates the core typed program
    var resolvedCalls = main.typeSystem(program); // resolves method calls, producing a mapping of call sites
    var mir = main.lower(program, resolvedCalls); // AST → MIR
    var code = main.codeGeneration(mir);
    return code;
  }
  void ok(String expected, String fileName, String... content) {
    assert content.length > 0 : "Content must not be empty";
    JsProgram code = getCode(content);
    var fileCode = code.files().stream()
      .filter(f -> f.toUri().toString().endsWith(fileName))
      .map(JsFile::code)
      .findFirst().orElseThrow();
    Err.strCmp(expected, fileCode);
//    Err.strCmp(normalizeWhitespace(expected), normalizeWhitespace(fileCode));
  }
//  String normalizeWhitespace(String str) {
//    // Remove all newlines and any surrounding whitespace, but preserve other spaces
//    return str.replaceAll("\\s*\\n+\\s*", "\n").trim();
//  }
  void okList(List<String> expected, List<String> fileName, String... content) {
    assert content.length > 0 : "Content must not be empty";
    assert expected.size() == fileName.size() : "Expected and fileName lists must have the same size";
    JsProgram code = getCode(content);
    for (int i = 0; i < expected.size(); i++) {
      String exp = expected.get(i);
      String fName = fileName.get(i);
      var fileCode = code.files().stream()
        .filter(f -> f.toUri().toString().endsWith(fName))
        .map(JsFile::code)
        .findFirst().orElseThrow();
      Err.strCmp(exp, fileCode);
    }
  }
  @Test void emptyProgram() {
    ok("""
      import { base$$Void_0 } from "../base/Void_0.js";
      
      export class test$$Test_0 {
        static $hash$imm$2$fun(fear31$_m$, $this) {
          return base$$Void_0.$self;
        }
      }
      
      export class test$$Test_0Impl {
        $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
      }
      
      test$$Test_0.$self = new test$$Test_0Impl();
      """,
      "test/Test_0.js",
      """
      package test
      alias base.Void as Void,
      Test:base.Main{ _ -> {} }
      """
    );
  }

  @Test void extendsClass() {
    okList(List.of("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$A_0 {
      static ma$imm$1$fun($this) {
        return rt$$Str.fromJsStr("A");
      }
    }
    
    export class test$$A_0Impl {
      ma$imm$0() { return test$$A_0.ma$imm$1$fun(this); }
    }
    
    test$$A_0.$self = new test$$A_0Impl();
    """, """
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$A_0 } from "../test/A_0.js";

    export class test$$B_0 {
      static mb$imm$1$fun($this) {
        return rt$$Str.fromJsStr("B").$plus$imm$1($this.ma$imm$0());
      }
    }

    export class test$$B_0Impl {
      mb$imm$0() { return test$$B_0.mb$imm$1$fun(this); }
      ma$imm$0() { return test$$A_0.ma$imm$1$fun(this); }
    }

    test$$B_0.$self = new test$$B_0Impl();
    """),
      List.of("test/A_0.js", "test/B_0.js"),
      """
      package test
      alias base.Str as Str,
      A:{.ma:Str -> "A" }
      B:A {.mb:Str-> "B" + (this.ma) }
      """);
  }

  @Test void extendsClass2() {
    okList(List.of("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$A_0 {
      static ma$imm$1$fun($this) {
        return rt$$Str.fromJsStr("A");
      }
    }
    """, """
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$B_0 {
      static mb$imm$1$fun($this) {
        return rt$$Str.fromJsStr("B").$plus$imm$1($this.ma$imm$0());
      }
    }
    """, """
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$A_0 } from "../test/A_0.js";
    import { test$$B_0 } from "../test/B_0.js";
    
    export class test$$C_0 {
      static mc$imm$1$fun($this) {
        return rt$$Str.fromJsStr("C").$plus$imm$1($this.mb$imm$0());
      }
      static mk$imm$1$fun($this) {
        return $this.mk$imm$0();
      }
    }
    
    export class test$$C_0Impl {
      mk$imm$0() { return test$$C_0.mk$imm$1$fun(this); }
      mb$imm$0() { return test$$B_0.mb$imm$1$fun(this); }
      ma$imm$0() { return test$$A_0.ma$imm$1$fun(this); }
      mc$imm$0() { return test$$C_0.mc$imm$1$fun(this); }
    }
    
    test$$C_0.$self = new test$$C_0Impl();
    """, """
    import { test$$C_0 } from "../test/C_0.js";
    
    export class test$$User_0 {
      static foo$imm$1$fun($this) {
        return test$$C_0.$self.mc$imm$0();
      }
    }
    
    export class test$$User_0Impl {
      foo$imm$0() { return test$$User_0.foo$imm$1$fun(this); }
    }
    
    test$$User_0.$self = new test$$User_0Impl();
    """),
      List.of("test/A_0.js", "test/B_0.js", "test/C_0.js", "test/User_0.js"),
      """
      package test
      alias base.Str as Str,
      A:{.ma:Str -> "A", .mk:Str }
      B:A {.mb:Str-> "B" + (this.ma) }
      C:B{ .mc:Str-> "C" + (this.mb), .mk->this.mk}
      User: {
        .foo:Str->C.mc()
      }
      """);
  }

  @Test void bool() {
    okList(List.of("""
    import { base$$False_0 } from "../base/False_0.js";
    import { base$$True_0 } from "../base/True_0.js";
    
    export class test$$Test_0 {
      static a$imm$1$fun($this) {
        return base$$True_0.$self;
      }
      static b$imm$1$fun($this) {
        return base$$False_0.$self;
      }
    }
    
    export class test$$Test_0Impl {
      b$imm$0() { return test$$Test_0.b$imm$1$fun(this); }
      a$imm$0() { return test$$Test_0.a$imm$1$fun(this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, """
    import { base$$Bool_0 } from "../base/Bool_0.js";
    import { base$$False_0 } from "../base/False_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class base$$True_0 {
      static and$imm$2$fun(b_m$, $this) {
        return b_m$;
      }
      static $ampersand$ampersand$imm$2$fun(b_m$, $this) {
        return b_m$.$hash$mut$0();
      }
      static or$imm$2$fun(b_m$, $this) {
        return $this;
      }
      static $pipe$pipe$imm$2$fun(b_m$, $this) {
        return $this;
      }
      static not$imm$1$fun($this) {
        return base$$False_0.$self;
      }
      static if$imm$2$fun(f_m$, $this) {
        return f_m$.then$mut$0();
      }
      static str$read$1$fun($this) {
        return rt$$Str.fromJsStr("True");
      }
      static toImm$read$1$fun($this) {
        return base$$True_0.$self;
      }
    }
    
    export class base$$True_0Impl {
      str$read$0() { return base$$True_0.str$read$1$fun(this); }
      if$imm$1(f_m$) { return base$$True_0.if$imm$2$fun(f_m$, this); }
      $ampersand$ampersand$imm$1(b_m$) { return base$$True_0.$ampersand$ampersand$imm$2$fun(b_m$, this); }
      $question$imm$1(f_m$) { return base$$Bool_0.$question$imm$2$fun(f_m$, this); }
      $pipe$pipe$imm$1(b_m$) { return base$$True_0.$pipe$pipe$imm$2$fun(b_m$, this); }
      $pipe$imm$1(b_m$) { return base$$Bool_0.$pipe$imm$2$fun(b_m$, this); }
      match$imm$1(m_m$) { return base$$Bool_0.match$imm$2$fun(m_m$, this); }
      not$imm$0() { return base$$True_0.not$imm$1$fun(this); }
      or$imm$1(b_m$) { return base$$True_0.or$imm$2$fun(b_m$, this); }
      $ampersand$imm$1(b_m$) { return base$$Bool_0.$ampersand$imm$2$fun(b_m$, this); }
      and$imm$1(b_m$) { return base$$True_0.and$imm$2$fun(b_m$, this); }
      toImm$read$0() { return base$$True_0.toImm$read$1$fun(this); }
    }
    
    base$$True_0.$self = new base$$True_0Impl();
    """),
    List.of("test/Test_0.js", "base/True_0.js"),
    """
    package test
    alias base.Bool as Bool, alias base.True as True, alias base.False as False,
    Test:{ .a:Bool-> True, .b:Bool-> False }
    """);
  }


  @Test void strUtf8() {
    ok("""
    import { rt$$Str } from "../rt-js/Str.js";
    import { rt$$UTF8 } from "../rt-js/UTF8.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(sys_m$, $this) {
        return sys_m$.io$mut$0().println$mut$1(rt$$UTF8.$self.fromBytes$imm$1(rt$$Str.fromJsStr("Hello!").utf8$imm$0()).$exclamation$mut$0());
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, "test/Test_0.js", """
    package test
    alias base.Main as Main, alias base.UTF8 as UTF8,
    Test: Main{sys -> sys.io.println(UTF8.fromBytes("Hello!".utf8)!)}
    """);
  }

  @Test void numberStr() {
    ok("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$A_0 {
      static a$imm$1$fun($this) {
        return rt$$Str.numToStr(-5n);
      }
    }
    
    export class test$$A_0Impl {
      a$imm$0() { return test$$A_0.a$imm$1$fun(this); }
    }
    
    test$$A_0.$self = new test$$A_0Impl();
    """, "test/A_0.js",
    """
    package test
    alias base.Main as Main, alias base.Str as Str,
    A:{.a:Str -> -5.str }
    """);
  }
  @Test void veryLongLongToStr() { ok("""
    import { base$$Assert_0 } from "../base/Assert_0.js";
    import { base$$False_0 } from "../base/False_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$Fear718$_0 } from "../test/Fear718$_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear31$_m$, $this) {
        return base$$Assert_0.$self.$exclamation$imm$3(base$$False_0.$self,rt$$Str.numToStr(9223372036854775808n),test$$Fear718$_0.$self);
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, "test/Test_0.js", """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, 9223372036854775808 .str, { Void }) }
    """);
  }

  @Test void numberStrAbs() {
    ok("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(sys_m$, $this) {
        return sys_m$.io$mut$0().println$mut$1(rt$$Str.numToStr(-999n < 0n ? -(-999n) : -999n));
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, "test/Test_0.js",
      """
      package test
      alias base.Main as Main, alias base.Str as Str,
      Test:Main{sys -> sys.io.println(-999 .abs.str) }
      """);
  }

  @Test void numArithmeticOps() {
    ok("""
    import { rt$$Num } from "../rt-js/Num.js";
    
    export class test$$Test_0 {
      static a$imm$1$fun($this) {
        return rt$$Num.toNat64((rt$$Num.toNat64(rt$$Num.toNat64(1n + 2n) * 3n) / 1n) - 4n);
      }
      static b$imm$1$fun($this) {
        return rt$$Num.toNat64(1n + rt$$Num.toNat64(2n * 3n));
      }
      static mod$imm$1$fun($this) {
        return (10n % 3n);
      }
      static pow$imm$1$fun($this) {
        return rt$$Num.toNat64(2n ** 5n);
      }
      static divInt$imm$1$fun($this) {
        return (-7n / 2n);
      }
      static divFloat$imm$1$fun($this) {
        return (7.0 / 2.0);
      }
    }
    
    export class test$$Test_0Impl {
      divInt$imm$0() { return test$$Test_0.divInt$imm$1$fun(this); }
      mod$imm$0() { return test$$Test_0.mod$imm$1$fun(this); }
      divFloat$imm$0() { return test$$Test_0.divFloat$imm$1$fun(this); }
      pow$imm$0() { return test$$Test_0.pow$imm$1$fun(this); }
      b$imm$0() { return test$$Test_0.b$imm$1$fun(this); }
      a$imm$0() { return test$$Test_0.a$imm$1$fun(this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
    "test/Test_0.js",
    """
    package test
    alias base.Nat as Nat, alias base.Int as Int, alias base.Float as Float, alias base.Bool as Bool,
    Test:{
      .a:Nat-> 1 + 2 * 3 / 1 - 4,
      .b:Nat-> 1 + (2 * 3),
      .mod:Nat -> 10 % 3,
      .pow:Nat -> 2 ** 5,
      .divInt:Int-> -7 / +2,
      .divFloat:Float -> 7.0 / 2.0,
    }
    """);
  }

  @Test void subtractionUnderflow() { ok("""
    import { rt$$Num } from "../rt-js/Num.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static a$imm$1$fun($this) {
        return rt$$Str.numToStr(rt$$Num.toNat64(rt$$Num.toNat64(0n - 2n) - 9223372036854775807n));
      }
    }
    
    export class test$$Test_0Impl {
      a$imm$0() { return test$$Test_0.a$imm$1$fun(this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, "test/Test_0.js", """
    package test
    alias base.Str as Str,
    Test:{ .a:Str -> ((0 - 2) - 9223372036854775807) .str }
    """);}

  @Test void numOps() {
    ok("""
    import { rt$$Num } from "../rt-js/Num.js";
    
    export class test$$Test_0 {
      static a$imm$1$fun($this) {
        return 1n;
      }
      static abs$imm$1$fun($this) {
        return -5n < 0n ? -(-5n) : -5n;
      }
      static sqrt$imm$1$fun($this) {
        return Math.sqrt(9.0);
      }
      static isNaN$imm$1$fun($this) {
        return rt$$Num.toBool(isNaN(parseFloat((0.0 / 0.0))));
      }
      static isNegInf$imm$1$fun($this) {
        return rt$$Num.toBool(0.0 === -Infinity);
      }
      static round$imm$1$fun($this) {
        return Math.round(2.7);
      }
    }
    
    export class test$$Test_0Impl {
      isNegInf$imm$0() { return test$$Test_0.isNegInf$imm$1$fun(this); }
      abs$imm$0() { return test$$Test_0.abs$imm$1$fun(this); }
      isNaN$imm$0() { return test$$Test_0.isNaN$imm$1$fun(this); }
      round$imm$0() { return test$$Test_0.round$imm$1$fun(this); }
      sqrt$imm$0() { return test$$Test_0.sqrt$imm$1$fun(this); }
      a$imm$0() { return test$$Test_0.a$imm$1$fun(this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
    "test/Test_0.js",
    """
    package test
    alias base.Nat as Nat, alias base.Int as Int, alias base.Bool as Bool, alias base.Float as Float, alias base.Void as Void,
    Test:{
      .a:Int-> 1 .int,
      .abs:Int -> (-5) .abs,
      .sqrt:Float -> 9.0 .sqrt,
      .isNaN:Bool  -> (0.0 / 0.0) .isNaN,
      .isNegInf:Bool -> 0.0 .isNegInfinity,
      .round:Int -> 2.7 .round,
    }
    """);
  }

  @Test void numAssert() {
    okList(List.of("""
    import { base$$_NatAssertionHelper_0 } from "../base/_NatAssertionHelper_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear31$_m$, $this) {
        return base$$_NatAssertionHelper_0.assertEq$imm$3$fun(5n, 5n, null);
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, """
    import { base$$Assert_0 } from "../base/Assert_0.js";
    import { base$$Fear2252$_0 } from "../base/Fear2252$_0.js";
    import { base$$Fear2257$_0 } from "../base/Fear2257$_0.js";
    import { rt$$Num } from "../rt-js/Num.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class base$$_NatAssertionHelper_0 {
      static assertEq$imm$3$fun(expected_m$, actual_m$, $this) {
        return base$$Assert_0.$self.$exclamation$imm$3(rt$$Num.toBool((expected_m$) === (actual_m$)),rt$$Str.fromJsStr("Expected: ").$plus$imm$1(rt$$Str.numToStr(expected_m$)).$plus$imm$1(rt$$Str.fromJsStr("\\nActual: ")).$plus$imm$1(rt$$Str.numToStr(actual_m$)),base$$Fear2252$_0.$self);
      }
      static assertEq$imm$4$fun(expected_m$, actual_m$, message_m$, $this) {
        return base$$Assert_0.$self.$exclamation$imm$3(rt$$Num.toBool((expected_m$) === (actual_m$)),message_m$.$plus$imm$1(rt$$Str.fromJsStr("\\nExpected: ")).$plus$imm$1(rt$$Str.numToStr(expected_m$)).$plus$imm$1(rt$$Str.fromJsStr("\\nActual: ")).$plus$imm$1(rt$$Str.numToStr(actual_m$)),base$$Fear2257$_0.$self);
      }
    }
    
    export class base$$_NatAssertionHelper_0Impl {
      assertEq$imm$2(expected_m$, actual_m$) { return base$$_NatAssertionHelper_0.assertEq$imm$3$fun(expected_m$, actual_m$, this); }
      assertEq$imm$3(expected_m$, actual_m$, message_m$) { return base$$_NatAssertionHelper_0.assertEq$imm$4$fun(expected_m$, actual_m$, message_m$, this); }
    }
    
    base$$_NatAssertionHelper_0.$self = new base$$_NatAssertionHelper_0Impl();
    """),
    List.of("test/Test_0.js", "base/_NatAssertionHelper_0.js"),
    """
    package test
    alias base.Main as Main, alias base.Void as Void,
    Test: Main{_ -> 5.assertEq(5)}
    A: {
      .assertOk:Void -> (5 .assertEq 5),
      .assertFail:Void -> (3 .assertEq(4, "should fail")),
    }
    """);
  }
  @Test void byteEq() {
    ok("""
    import { base$$_ByteAssertionHelper_0 } from "../base/_ByteAssertionHelper_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear[###]$_m$, $this) {
        return base$$_ByteAssertionHelper_0.assertEq$imm$3$fun((rt$$Str.fromJsStr("Hello!").utf8$imm$0().get$imm$1(0n) & 0xFF), (Number(72n) & 0xFF), null);
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, "test/Test_0.js", """
    package test
    alias base.Main as Main,
    Test: Main{_ -> "Hello!".utf8.get(0).assertEq(72 .byte)}
    """);
  }

  @Test void numOpsCompBit() {
    ok("""
    import { rt$$Num } from "../rt-js/Num.js";
    
    export class test$$Test_0 {
      static gt$imm$1$fun($this) {
        return rt$$Num.toBool((5n) > (3n));
      }
      static lt$imm$1$fun($this) {
        return rt$$Num.toBool((2n) < (7n));
      }
      static and$imm$1$fun($this) {
        return 6n & 3n;
      }
      static or$imm$1$fun($this) {
        return 6n | 3n;
      }
      static xor$imm$1$fun($this) {
        return 6n ^ 3n;
      }
      static shl$imm$1$fun($this) {
        return ((1n << 3n) & ((1n<<64n)-1n));
      }
      static shr$imm$1$fun($this) {
        return ((8n & ((1n<<64n)-1n)) >> 2n);
      }
    }

    export class test$$Test_0Impl {
      gt$imm$0() { return test$$Test_0.gt$imm$1$fun(this); }
      xor$imm$0() { return test$$Test_0.xor$imm$1$fun(this); }
      shl$imm$0() { return test$$Test_0.shl$imm$1$fun(this); }
      or$imm$0() { return test$$Test_0.or$imm$1$fun(this); }
      shr$imm$0() { return test$$Test_0.shr$imm$1$fun(this); }
      and$imm$0() { return test$$Test_0.and$imm$1$fun(this); }
      lt$imm$0() { return test$$Test_0.lt$imm$1$fun(this); }
    }

    test$$Test_0.$self = new test$$Test_0Impl();
    """,
      "test/Test_0.js",
      """
      package test
      alias base.Nat as Nat, alias base.Int as Int, alias base.Bool as Bool, alias base.Float as Float, alias base.Void as Void,
      Test:{
        .gt:Bool -> 5 > 3,
        .lt:Bool -> 2 < 7,
        .and:Int -> +6 .bitwiseAnd +3,
        .or:Int -> +6 .bitwiseOr +3,
        .xor:Nat -> 6 .xor 3,
        .shl:Nat -> 1 .shiftLeft 3,
        .shr:Nat -> 8 .shiftRight 2,
      }
      """);
  }

  @Test void direction() {
    okList(List.of("""
    export class test$$Direction_0 {
      static reverse$imm$1$fun($this) {
        return $this.turn$imm$0().turn$imm$0();
      }
    }
    """, """
    import { test$$Direction_0 } from "../test/Direction_0.js";
    import { test$$East_0 } from "../test/East_0.js";
    
    export class test$$North_0 {
      static turn$imm$1$fun($this) {
        return test$$East_0.$self;
      }
    }
    
    export class test$$North_0Impl {
      reverse$imm$0() { return test$$Direction_0.reverse$imm$1$fun(this); }
      turn$imm$0() { return test$$North_0.turn$imm$1$fun(this); }
    }
    
    test$$North_0.$self = new test$$North_0Impl();
    """),
      List.of("test/Direction_0.js", "test/North_0.js"),
      """
      package test
      Direction: {
        .turn: Direction,
        .reverse: Direction -> this.turn.turn,
      }
      North: Direction {.turn -> East, }
      East : Direction {.turn -> South,}
      South: Direction {.turn -> West, }
      West : Direction {.turn -> North,}
      """);
  }

  @Test void tanks() {
    okList(List.of("""
      import { test$$Tank_0Impl } from "../test/Tank_0.js";
      
      export class test$$Tanks_0 {
        static of$imm$3$fun(heading_m$, aiming_m$, $this) {
          return new test$$Tank_0Impl(aiming_m$, heading_m$);
        }
      }
      
      export class test$$Tanks_0Impl {
        of$imm$2(heading_m$, aiming_m$) { return test$$Tanks_0.of$imm$3$fun(heading_m$, aiming_m$, this); }
      }
      
      test$$Tanks_0.$self = new test$$Tanks_0Impl();
      """, """
      export class test$$Tank_0 {
        static heading$imm$2$fun(fear[###]$_m$, heading_m$) {
          return heading_m$;
        }
        static aiming$imm$2$fun(fear[###]$_m$, aiming_m$) {
          return aiming_m$;
        }
      }
      
      export class test$$Tank_0Impl {
        constructor(aiming_m$, heading_m$) {
          this.aiming_m$ = aiming_m$;
          this.heading_m$ = heading_m$;
        }
        aiming$imm$0() { return test$$Tank_0.aiming$imm$2$fun(this, this.aiming_m$); }
        heading$imm$0() { return test$$Tank_0.heading$imm$2$fun(this, this.heading_m$); }
      }
      """),
      List.of("test/Tanks_0.js", "test/Tank_0.js"),
      """
      package test
      Tanks: { .of(heading: Direction, aiming: Direction): Tank ->
        Tank: { .heading: Direction -> heading, .aiming: Direction -> aiming, }
      }
      Direction: {}
      """);
  }

  @Test void tanksAnonymous() {
    okList(List.of("""
      import { test$$Fear714$_0Impl } from "../test/Fear714$_0.js";
      
      export class test$$Tanks_0 {
        static of$imm$3$fun(heading_m$, aiming_m$, $this) {
          return new test$$Fear714$_0Impl(aiming_m$, heading_m$);
        }
      }
      
      export class test$$Tanks_0Impl {
        of$imm$2(heading_m$, aiming_m$) { return test$$Tanks_0.of$imm$3$fun(heading_m$, aiming_m$, this); }
      }
      
      test$$Tanks_0.$self = new test$$Tanks_0Impl();
      """, """
      export class test$$Fear714$_0 {
        static heading$imm$2$fun(fear[###]$_m$, heading_m$) {
          return heading_m$;
        }
        static aiming$imm$2$fun(fear[###]$_m$, aiming_m$) {
          return aiming_m$;
        }
      }
      
      export class test$$Fear714$_0Impl {
        constructor(aiming_m$, heading_m$) {
          this.aiming_m$ = aiming_m$;
          this.heading_m$ = heading_m$;
        }
        aiming$imm$0() { return test$$Fear714$_0.aiming$imm$2$fun(this, this.aiming_m$); }
        heading$imm$0() { return test$$Fear714$_0.heading$imm$2$fun(this, this.heading_m$); }
      }
      """, """
      export class test$$Tank_0 {
      }
      """),
      List.of("test/Tanks_0.js", "test/Fear714$_0.js", "test/Tank_0.js"),
      """
      package test
      Tank: { .heading: Direction, .aiming: Direction, }
      Tanks: { .of(heading: Direction, aiming: Direction): Tank ->
        { .heading -> heading, .aiming -> aiming, }
        }
      Direction: {}
      """);
  }

  @Test void tanksWithSugar() {
    okList(List.of("""
      import { test$$Direction_0 } from "../test/Direction_0.js";
      import { test$$East_0 } from "../test/East_0.js";
      
      export class test$$North_0 {
        static turn$imm$1$fun($this) {
          return test$$East_0.$self;
        }
      }
      
      export class test$$North_0Impl {
        reverse$imm$0() { return test$$Direction_0.reverse$imm$1$fun(this); }
        turn$imm$0() { return test$$North_0.turn$imm$1$fun(this); }
      }
      
      test$$North_0.$self = new test$$North_0Impl();
      """, """
      import { test$$Fear[###]$_0Impl } from "../test/Fear[###]$_0.js";
      
      export class test$$Tanks_0 {
        static $hash$imm$3$fun(heading_m$, aiming_m$, $this) {
          return new test$$Fear[###]$_0Impl(aiming_m$, heading_m$);
        }
      }
      
      export class test$$Tanks_0Impl {
        $hash$imm$2(heading_m$, aiming_m$) { return test$$Tanks_0.$hash$imm$3$fun(heading_m$, aiming_m$, this); }
      }
      
      test$$Tanks_0.$self = new test$$Tanks_0Impl();
      """),
      List.of("test/North_0.js", "test/Tanks_0.js"),
      """
      package test
      Direction: {
        .turn: Direction,
        .reverse: Direction -> this.turn.turn,
        }
      North: Direction { East  }
      East : Direction { South }
      South: Direction { West  }
      West : Direction { North }
      
      Tank: {.heading: Direction, .aiming: Direction, }
      Tanks: { #(heading: Direction, aiming: Direction): Tank ->
        { .heading -> heading, .aiming -> aiming, }
      }
      """);
  }

  @Test void rotation() {
    okList(List.of("""
    export class test$$Rotation_0 {
    }
    """, """
    export class test$$Turn270_0 {
      static $hash$imm$2$fun(fear34$_m$, $this) {
        return fear34$_m$.turn$imm$0().turn$imm$0().turn$imm$0();
      }
    }
    
    export class test$$Turn270_0Impl {
      $hash$imm$1(fear34$_m$) { return test$$Turn270_0.$hash$imm$2$fun(fear34$_m$, this); }
    }
    
    test$$Turn270_0.$self = new test$$Turn270_0Impl();
    """),
      List.of("test/Rotation_0.js", "test/Turn270_0.js"),
      """
      package test
      Direction: { .turn: Direction }
      Rotation: { #(d: Direction):Direction }
      Turn0: Rotation{::}
      Turn90: Rotation{::turn}
      Turn180: Rotation{::turn.turn}
      Turn270: Rotation{::turn.turn.turn}
      """);
  }

  @Test void rotationPlus() {
    okList(List.of("""
    import { test$$Fear714$_0Impl } from "../test/Fear714$_0.js";
    
    export class test$$Rotation_0 {
      static $plus$imm$2$fun(r_m$, $this) {
        return new test$$Fear714$_0Impl(r_m$, $this);
      }
    }
    """),
      List.of("test/Rotation_0.js"),
      """
        package test
        Direction: { .turn: Direction }
        Rotation: {
          #(d: Direction):Direction,
          +(r: Rotation): Rotation-> { d -> this#( r#(d) ) }
        }
        """);
  }

  @Test void tankTurn() {
    okList(List.of("""
    import { test$$Fear714$_0Impl } from "../test/Fear714$_0.js";
    
    export class test$$Tanks_0 {
      static $hash$imm$3$fun(heading_m$, aiming_m$, $this) {
        return new test$$Fear714$_0Impl(aiming_m$, heading_m$);
      }
    }
    
    export class test$$Tanks_0Impl {
      $hash$imm$2(heading_m$, aiming_m$) { return test$$Tanks_0.$hash$imm$3$fun(heading_m$, aiming_m$, this); }
    }
    
    test$$Tanks_0.$self = new test$$Tanks_0Impl();
    """, """
    import { test$$Tanks_0 } from "../test/Tanks_0.js";
    
    export class test$$Tank_0 {
      static turnTurret$imm$2$fun(r_m$, $this) {
        return test$$Tanks_0.$self.$hash$imm$2($this.heading$imm$0(),r_m$.$hash$imm$1($this.aiming$imm$0()));
      }
    }
    """),
      List.of("test/Tanks_0.js", "test/Tank_0.js"),
      """
      package test
      Direction: { .turn: Direction }
      Rotation: { #(d: Direction):Direction }
      Tanks: { #(heading: Direction, aiming: Direction): Tank ->
        { .heading -> heading, .aiming -> aiming, }
      }
      Tank: {
        .heading: Direction,
        .aiming: Direction,
        .turnTurret(r: Rotation): Tank ->
          Tanks#(this.heading, r#(this.aiming))
      }
      """);
  }

  @Test void directionWithPoint() {
    okList(List.of("""
    import { test$$East_0 } from "../test/East_0.js";
    import { test$$Points_0 } from "../test/Points_0.js";
    
    export class test$$North_0 {
      static turn$imm$1$fun($this) {
        return test$$East_0.$self;
      }
      static point$imm$1$fun($this) {
        return test$$Points_0.$self.$hash$imm$2(-1n,0n);
      }
    }
    
    export class test$$North_0Impl {
      point$imm$0() { return test$$North_0.point$imm$1$fun(this); }
      turn$imm$0() { return test$$North_0.turn$imm$1$fun(this); }
    }
    
    test$$North_0.$self = new test$$North_0Impl();
    """, """
    import { test$$Fear715$_0Impl } from "../test/Fear715$_0.js";
    
    export class test$$Points_0 {
      static $hash$imm$3$fun(x_m$, y_m$, $this) {
        return new test$$Fear715$_0Impl(x_m$, y_m$);
      }
    }
    
    export class test$$Points_0Impl {
      $hash$imm$2(x_m$, y_m$) { return test$$Points_0.$hash$imm$3$fun(x_m$, y_m$, this); }
    }
    
    test$$Points_0.$self = new test$$Points_0Impl();
    """, """
    import { rt$$Num } from "../rt-js/Num.js";
    import { test$$Points_0 } from "../test/Points_0.js";
    
    export class test$$Point_0 {
      static $plus$imm$2$fun(other_m$, $this) {
        return test$$Points_0.$self.$hash$imm$2(rt$$Num.toInt64(other_m$.x$imm$0() + $this.x$imm$0()),rt$$Num.toInt64(other_m$.y$imm$0() + $this.y$imm$0()));
      }
      static move$imm$2$fun(d_m$, $this) {
        return $this.$plus$imm$1(d_m$.point$imm$0());
      }
    }
    """),
    List.of("test/North_0.js", "test/Points_0.js", "test/Point_0.js"),
    """
    package test
    alias base.Int as Int,
    Point: {
      .x: Int, .y: Int,
      +(other: Point): Point ->
        Points#(other.x + (this.x), other.y + (this.y)),
      .move(d: Direction): Point -> this + ( d.point ),
      }
    Points: {#(x: Int, y: Int): Point -> { .x -> x, .y -> y } }
    
    Direction: { .turn: Direction, .point: Point, }
    North: Direction {.turn -> East,  .point -> Points#(-1, +0), }
    East : Direction {.turn -> South, .point -> Points#(+0, +1), }
    South: Direction {.turn -> West,  .point -> Points#(+1, +0), }
    West : Direction {.turn -> North, .point -> Points#(+0, -1), }
    """);
  }

  @Test void stack() {
    okList(List.of("""
    import { test$$Fear717$_1Impl } from "../test/Fear717$_1.js";
    
    export class test$$Stack_1 {
      static match$imm$2$fun(m_m$, $this) {
        return m_m$.empty$imm$0();
      }
      static fold$imm$3$fun(start_m$, f_m$, $this) {
        return start_m$;
      }
      static map$imm$2$fun(f_m$, $this) {
        return test$$Stack_1.$self;
      }
      static filter$imm$2$fun(f_m$, $this) {
        return test$$Stack_1.$self;
      }
      static $plus$imm$2$fun(e_m$, $this) {
        return new test$$Fear717$_1Impl(e_m$, $this);
      }
    }
    
    export class test$$Stack_1Impl {
      fold$imm$2(start_m$, f_m$) { return test$$Stack_1.fold$imm$3$fun(start_m$, f_m$, this); }
      $plus$imm$1(e_m$) { return test$$Stack_1.$plus$imm$2$fun(e_m$, this); }
      match$imm$1(m_m$) { return test$$Stack_1.match$imm$2$fun(m_m$, this); }
      filter$imm$1(f_m$) { return test$$Stack_1.filter$imm$2$fun(f_m$, this); }
      map$imm$1(f_m$) { return test$$Stack_1.map$imm$2$fun(f_m$, this); }
    }
    
    test$$Stack_1.$self = new test$$Stack_1Impl();
    """),
    List.of("test/Stack_1.js"),
    """
    package test
    alias base.Int as Int, alias base.F as F, alias base.Bool as Bool,
    StackMatch[T,R]: {
      .empty: R,
      .elem(top:T, tail: Stack[T]): R,
    }
    Stack[T]: {
      .match[R](m: StackMatch[T,R]): R -> m.empty,
      .fold[R](start:R, f: F[R,T,R]): R -> start,
      .map[R](f: F[T, R]): Stack[R] -> {},
      .filter(f: F[T,Bool]): Stack[T]-> {},
      +(e: T): Stack[T] -> {
        .match(m) -> m.elem(e, this),
        .fold(start, f) -> f#(this.fold(start, f), e),
        .map(f) -> this.map(f) + ( f#(e) ),
        .filter(f) -> f#(e).if{
          .then -> this.filter(f) + e,
          .else -> this.filter(f),
        },
      },
    }
    """);
  }



  @Test void tankGameExample() {
    okList(List.of("""
    import { rt$$Num } from "../rt-js/Num.js";
    import { test$$Points_0 } from "../test/Points_0.js";
    
    export class test$$Point_0 {
      static x$imm$2$fun(self_m$, x_m$) {
        return x_m$;
      }
      static y$imm$2$fun(self_m$, y_m$) {
        return y_m$;
      }
      static $plus$imm$4$fun(other_m$, self_m$, x_m$, y_m$) {
        return test$$Points_0.$self.$hash$imm$2(rt$$Num.toInt64(other_m$.x$imm$0() + x_m$),rt$$Num.toInt64(other_m$.y$imm$0() + y_m$));
      }
      static move$imm$2$fun(d_m$, self_m$) {
        return self_m$.$plus$imm$1(d_m$.point$imm$0());
      }
      static $equals$equals$imm$2$fun(other_m$, self_m$) {
        return rt$$Num.toBool((self_m$.x$imm$0()) === (other_m$.x$imm$0())).and$imm$1(rt$$Num.toBool((self_m$.y$imm$0()) === (other_m$.y$imm$0())));
      }
    }
    
    export class test$$Point_0Impl {
      constructor(x_m$, y_m$) {
        this.x_m$ = x_m$;
        this.y_m$ = y_m$;
      }
      move$imm$1(d_m$) { return test$$Point_0.move$imm$2$fun(d_m$, this); }
      $equals$equals$imm$1(other_m$) { return test$$Point_0.$equals$equals$imm$2$fun(other_m$, this); }
      y$imm$0() { return test$$Point_0.y$imm$2$fun(this, this.y_m$); }
      $plus$imm$1(other_m$) { return test$$Point_0.$plus$imm$4$fun(other_m$, this, this.x_m$, this.y_m$); }
      x$imm$0() { return test$$Point_0.x$imm$2$fun(this, this.x_m$); }
    }
    """, """
    import { test$$Fear732$_0Impl } from "../test/Fear732$_0.js";
    
    export class test$$Tanks_0 {
      static $hash$imm$4$fun(heading_m$, aiming_m$, position_m$, $this) {
        return new test$$Fear732$_0Impl(aiming_m$, heading_m$, position_m$);
      }
    }
    
    export class test$$Tanks_0Impl {
      $hash$imm$3(heading_m$, aiming_m$, position_m$) { return test$$Tanks_0.$hash$imm$4$fun(heading_m$, aiming_m$, position_m$, this); }
    }
    
    test$$Tanks_0.$self = new test$$Tanks_0Impl();
    """, """
    import { test$$Tank_0 } from "../test/Tank_0.js";
    
    export class test$$Fear732$_0 {
      static heading$imm$2$fun(fear[###]$_m$, heading_m$) {
        return heading_m$;
      }
      static aiming$imm$2$fun(fear[###]$_m$, aiming_m$) {
        return aiming_m$;
      }
      static position$imm$2$fun(fear[###]$_m$, position_m$) {
        return position_m$;
      }
    }
    
    export class test$$Fear732$_0Impl {
      constructor(aiming_m$, heading_m$, position_m$) {
        this.aiming_m$ = aiming_m$;
        this.heading_m$ = heading_m$;
        this.position_m$ = position_m$;
      }
      move$imm$0() { return test$$Tank_0.move$imm$1$fun(this); }
      aiming$imm$0() { return test$$Fear732$_0.aiming$imm$2$fun(this, this.aiming_m$); }
      position$imm$0() { return test$$Fear732$_0.position$imm$2$fun(this, this.position_m$); }
      heading$imm$0() { return test$$Fear732$_0.heading$imm$2$fun(this, this.heading_m$); }
    }
    """),
    List.of("test/Point_0.js", "test/Tanks_0.js", "test/Fear732$_0.js"),
    """
    package test
    alias base.Int as Int, alias base.True as True, alias base.Bool as Bool,
    Points:{ #(x: Int, y: Int): Point -> Point:{'self
      .x: Int -> x,
      .y: Int -> y,
      +(other: Point): Point -> Points#(other.x + x, other.y + y),
      .move(d: Direction): Point -> self + ( d.point ),
      ==(other: Point): Bool -> self.x == (other.x)  .and (self.y == (other.y) ),
      }}
    Direction: { .turn: Direction, .point:Point, }
    North: Direction {.turn -> East,  .point -> Points#(-1, +0), }
    East : Direction {.turn -> South, .point -> Points#(+0, +1), }
    South: Direction {.turn -> West,  .point -> Points#(+1, +0), }
    West : Direction {.turn -> North, .point -> Points#(+0, -1), }
    Tank: {
      .heading: Direction,
      .aiming: Direction,
      .position:Point,
      .move:Tank -> Tanks#(
        this.heading,
        this.aiming,
        this.position.move(this.heading)
        ),
      }
    Tanks: { #(heading: Direction, aiming: Direction, position: Point): Tank->
      { .heading -> heading, .aiming -> aiming, .position -> position } }
    """);
  }

  @Test void genericMethod() {
    okList(List.of("""
    export class test$$Left_0 {
      static choose$imm$3$fun(l_m$, r_m$, $this) {
        return l_m$;
      }
    }
    
    export class test$$Left_0Impl {
      choose$imm$2(l_m$, r_m$) { return test$$Left_0.choose$imm$3$fun(l_m$, r_m$, this); }
    }
    
    test$$Left_0.$self = new test$$Left_0Impl();
    """, """
    export class test$$Fork_0 {
    }
    """),
    List.of("test/Left_0.js", "test/Fork_0.js"),
    """
    package test
    Fork : { .choose[Val](leftVal: Val, rightVal: Val): Val, }
    Left : Fork{ l,r -> l }
    Right: Fork{ l,r -> r }
    """);
  }

  @Test void genericType() {
    okList(List.of("""
    export class test$$Right_0 {
      static choose$imm$2$fun(fear32$_m$, $this) {
        return fear32$_m$.right$imm$0();
      }
    }
    
    export class test$$Right_0Impl {
      choose$imm$1(fear32$_m$) { return test$$Right_0.choose$imm$2$fun(fear32$_m$, this); }
    }
    
    test$$Right_0.$self = new test$$Right_0Impl();
    """, """
    export class test$$Fork_0 {
    }
    """, """
    export class test$$LeftRight_1 {
    }
    """),
      List.of("test/Right_0.js",  "test/Fork_0.js", "test/LeftRight_1.js"),
      """
      package test
      Fork : { .choose[Val](leftRight: LeftRight[Val]): Val, }
      LeftRight[LR]: { .left: LR, .right: LR }
      Left : Fork{::left}
      Right: Fork{::right}
      """);
  }

  @Test void ifThenElse() {
    ok("""
    import { base$$True_0 } from "../base/True_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Bot_0 {
      static message$imm$2$fun(s_m$, $this) {
        return (s_m$.$equals$equals$imm$1(rt$$Str.fromJsStr("hello")) == base$$True_0.$self ? rt$$Str.fromJsStr("Hi, I'm Bot; how can I help you?") : (s_m$.$equals$equals$imm$1(rt$$Str.fromJsStr("bye")) == base$$True_0.$self ? rt$$Str.fromJsStr("goodbye!") : rt$$Str.fromJsStr("I don't understand")));
      }
    }
    
    export class test$$Bot_0Impl {
      message$imm$1(s_m$) { return test$$Bot_0.message$imm$2$fun(s_m$, this); }
    }
    
    test$$Bot_0.$self = new test$$Bot_0Impl();
    """,
      "test/Bot_0.js",
      """
      package test
      alias base.Str as Str,
      Bot: {
        .message(s: Str): Str ->
          // Outer Check: Is the message `hello`?
          (s == `hello`).if {
            .then -> `Hi, I'm Bot; how can I help you?`,
            .else ->
              // Inner Check: Is the message "bye"?
              (s == `bye`).if {
                .then -> `goodbye!`,
                .else -> `I don't understand`
              } //End of inner ThenElse
          } //End of outer ThenElse
      }
      """);
  }

  @Test void questionMark() {
    ok("""
    import { base$$True_0 } from "../base/True_0.js";
    
    export class test$$Test_0 {
      static m$imm$2$fun(b_m$, $this) {
        return (b_m$ == base$$True_0.$self ? $this : $this.m$imm$1(b_m$));
      }
    }
    
    export class test$$Test_0Impl {
      m$imm$1(b_m$) { return test$$Test_0.m$imm$2$fun(b_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
      "test/Test_0.js",
      """
      package test
      Test:{ .m(b: base.Bool):Test-> b ? {.then->this, .else->this.m(b), } }
      """);
  }

  @Test void questionMarkStr() {
    ok("""
    import { base$$True_0 } from "../base/True_0.js";
    import { rt$$Num } from "../rt-js/Num.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static m$imm$1$fun($this) {
        return (rt$$Num.toBool((5n) > (2n)) == base$$True_0.$self ? rt$$Str.fromJsStr("True") : rt$$Str.fromJsStr("False"));
      }
    }
    
    export class test$$Test_0Impl {
      m$imm$0() { return test$$Test_0.m$imm$1$fun(this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
    "test/Test_0.js",
    """
    package test
    alias base.Str as Str,
    Test:{ .m:Str-> (5 > 2) ?[Str] {.then -> "True", .else -> "False", } }
    """);
  }

  @Test void optional() {
    okList(List.of("""
      import { test$$Fear715$_0 } from "../test/Fear715$_0.js";
      
      export class test$$Test_0 {
        static getAge$imm$2$fun(p_m$, $this) {
          return p_m$.match$imm$1(test$$Fear715$_0.$self);
        }
      }
      
      export class test$$Test_0Impl {
        getAge$imm$1(p_m$) { return test$$Test_0.getAge$imm$2$fun(p_m$, this); }
      }
      
      test$$Test_0.$self = new test$$Test_0Impl();
      """, """
      export class test$$Fear715$_0 {
        static empty$mut$1$fun(fear[###]$_m$) {
          return 0n;
        }
        static some$mut$2$fun(p$_m$, fear[###]$_m$) {
          return p$_m$.age$imm$0();
        }
      }

      export class test$$Fear715$_0Impl {
        some$mut$1(p$_m$) { return test$$Fear715$_0.some$mut$2$fun(p$_m$, this); }
        empty$mut$0() { return test$$Fear715$_0.empty$mut$1$fun(this); }
      }

      test$$Fear715$_0.$self = new test$$Fear715$_0Impl();
      """),
      List.of("test/Test_0.js", "test/Fear715$_0.js"),
      """
      package test
      alias base.Nat as Nat,
      alias base.Opt as Opt,
      Person: { .age: Nat }
      Test:{
        .getAge(p: Opt[Person]): Nat-> p.match{  // age or zero
          .empty    -> 0,
          .some(p') -> p'.age, //Yes, p' is a valid parameter name
        }
      }
      """);
  }

  @Test void blockLetDoRet() {
    ok("""
    import { base$$Void_0 } from "../base/Void_0.js";
    import { test$$ForceGen_0 } from "../test/ForceGen_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear31$_m$, $this) {
        let n_m$ = 5n;
    var doRes1 = test$$ForceGen_0.$self.$hash$imm$0();
    return base$$Void_0.$self;
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
      "test/Test_0.js",
      """
      package test
      alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
      Test:base.Main {_ -> Block#
       .let[Int] n = {+5}
       .do {ForceGen#}
       .return {Void}
       }
      ForceGen: {#: Void -> {}}
      """);
  }

  @Test void blockVarDoRet() {
    ok("""
    import { base$$Vars_0 } from "../base/Vars_0.js";
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$ForceGen_0 } from "../test/ForceGen_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear31$_m$, $this) {
        var n_m$ = base$$Vars_0.$self.$hash$imm$1(rt$$Str.fromJsStr("Hi"));
    var doRes1 = test$$ForceGen_0.$self.$hash$imm$0();
    return base$$Void_0.$self;
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
    "test/Test_0.js",
    """
    package test
    alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test:base.Main {_ -> Block#
     .var[Str] n = {"Hi"}
     .do {ForceGen#}
     .return {Void}
    }
    ForceGen: {#: Void -> {}}
    """);
  }

  @Test void blockError() {
    ok("""
    import { base$$Infos_0 } from "../base/Infos_0.js";
    import { base$$True_0 } from "../base/True_0.js";
    import { base$$Vars_0 } from "../base/Vars_0.js";
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$Num } from "../rt-js/Num.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$ForceGen_0 } from "../test/ForceGen_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear31$_m$, $this) {
        let n_m$ = 5n;
    let n2_m$ = 10n;
    var n3_m$ = base$$Vars_0.$self.$hash$imm$1(15n);
    if (rt$$Num.toBool((n3_m$.get$mut$0()) === (rt$$Num.toInt64(n_m$ + n2_m$))) == base$$True_0.$self) { r$$Error.throwFearlessError(base$$Infos_0.$self.msg$imm$1(rt$$Str.fromJsStr("oh no")));
     }
    var doRes1 = test$$ForceGen_0.$self.$hash$imm$0();
    return base$$Void_0.$self;
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
    "test/Test_0.js",
    """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test:base.Main {_ -> Block#
     .openIso[Int] n = (iso +5)
     .let[Int] n2 = {+10}
     .var[Int] n3 = {+15}
     .if {n3.get == (n.int + n2)} .error {base.Infos.msg "oh no"}
     .do {ForceGen#}
     .return {Void}
     }
    ForceGen: {#: Void -> {}}
    """);
  }

  @Test void boolExprBlock() {
    ok("""
    import { base$$True_0 } from "../base/True_0.js";
    import { base$$Void_0 } from "../base/Void_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear31$_m$, $this) {
        return (base$$True_0.$self == base$$True_0.$self ? (() => {
    return base$$Void_0.$self})() : (() => {
    return base$$Void_0.$self})());
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
      "test/Test_0.js",
      """
      package test
      alias base.Block as Block, alias base.Void as Void,
      alias base.True as True,
      Test: base.Main{_ -> True ? {
        .then -> Block#.return {Void},
        .else -> Block#.return {Void},
      }}
      """);
  }

  @Test void sysIOPrint() {
    ok("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(sys_m$, $this) {
        return sys_m$.io$mut$0().println$mut$1(rt$$Str.fromJsStr("Hello World"));
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
  "test/Test_0.js",
  """
    package test
    alias base.Main as Main,
    Test: Main{sys -> sys.io.println("Hello World") }
    """);
  }

  @Test void listAndFlowMap() {
    okList(List.of("""
    import { rt$$flows } from "../rt-js/flows.js";
    import { test$$Fear714$_0 } from "../test/Fear714$_0.js";
    
    export class test$$Names_0 {
      static $hash$imm$2$fun(ps_m$, $this) {
        return rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, ps_m$.flow$imm$0()).map$mut$1(test$$Fear714$_0.$self).list$mut$0();
      }
    }
    
    export class test$$Names_0Impl {
      $hash$imm$1(ps_m$) { return test$$Names_0.$hash$imm$2$fun(ps_m$, this); }
    }
    
    test$$Names_0.$self = new test$$Names_0Impl();
    """),
      List.of("test/Names_0.js"),
      """
      package test
      alias base.List as List, alias base.Str as Str,
      
      Person:{  .name:Str,  }
      Names:{  #(ps: List[Person]): List[Str] -> ps.flow.map{::name }.list   }
      """);
  }

  @Test void listAndFlowFilter() {
    okList(List.of("""
    import { rt$$flows } from "../rt-js/flows.js";
    import { test$$Fear715$_0 } from "../test/Fear715$_0.js";
    
    export class test$$Doctors_0 {
      static $hash$imm$2$fun(ps_m$, $this) {
        return rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, ps_m$.flow$imm$0()).filter$mut$1(test$$Fear715$_0.$self).list$mut$0();
      }
    }
    
    export class test$$Doctors_0Impl {
      $hash$imm$1(ps_m$) { return test$$Doctors_0.$hash$imm$2$fun(ps_m$, this); }
    }
    
    test$$Doctors_0.$self = new test$$Doctors_0Impl();
    """),
      List.of("test/Doctors_0.js"),
      """
      package test
      alias base.List as List, alias base.Str as Str,
      
      Person:{  .name:Str,  }
      Doctors:{ #(ps: List[Person]): List[Person] ->ps.flow.filter{::name.startsWith `Dr.` }.list }
      """);
  }

  @Test void listAndFlowFlatMapAny() {
    okList(List.of("""
    import { rt$$flows } from "../rt-js/flows.js";
    import { test$$Fear714$_0 } from "../test/Fear714$_0.js";
    
    export class test$$AllCats_0 {
      static $hash$imm$2$fun(ps_m$, $this) {
        return rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, ps_m$.flow$imm$0()).flatMap$mut$1(test$$Fear714$_0.$self).list$mut$0();
      }
    }
    
    export class test$$AllCats_0Impl {
      $hash$imm$1(ps_m$) { return test$$AllCats_0.$hash$imm$2$fun(ps_m$, this); }
    }
    
    test$$AllCats_0.$self = new test$$AllCats_0Impl();
    """, """
    import { rt$$flows } from "../rt-js/flows.js";
    import { test$$Fear715$_0 } from "../test/Fear715$_0.js";

    export class test$$Sad_0 {
      static $hash$imm$2$fun(ps_m$, $this) {
        return rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, ps_m$.flow$imm$0()).any$mut$1(test$$Fear715$_0.$self);
      }
    }

    export class test$$Sad_0Impl {
      $hash$imm$1(ps_m$) { return test$$Sad_0.$hash$imm$2$fun(ps_m$, this); }
    }

    test$$Sad_0.$self = new test$$Sad_0Impl();
    """),
      List.of("test/AllCats_0.js", "test/Sad_0.js"),
      """
      package test
      alias base.List as List, alias base.Str as Str, alias base.Bool as Bool,
      
      Person:{  .name:Str, .cats: List[Cat],  }
      Cat:{ .name: Str, }
      AllCats:{  #(ps: List[Person]): List[Cat] -> ps.flow.flatMap{::cats.flow }.list  }
      Sad :{ #(ps: List[Person]): Bool -> ps.flow.any{::cats.isEmpty  } }
      """);
  }

  @Test void listNatAndStr() {
    ok("""
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$ListK } from "../rt-js/ListK.js";
    import { rt$$Str } from "../rt-js/Str.js";

    export class test$$Test_0 {
      static $hash$imm$2$fun(s_m$, $this) {
        let l_m$ = rt$$ListK.$self.$hash$imm$5(rt$$Str.fromJsStr("A"),rt$$Str.fromJsStr("B"),rt$$Str.fromJsStr("C"),rt$$Str.fromJsStr("D"),rt$$Str.fromJsStr("E"));
    let l2_m$ = rt$$ListK.$self.$hash$imm$3(12n,13n,14n);
    return base$$Void_0.$self;
      }
    }

    export class test$$Test_0Impl {
      $hash$imm$1(s_m$) { return test$$Test_0.$hash$imm$2$fun(s_m$, this); }
    }

    test$$Test_0.$self = new test$$Test_0Impl();
    """,
      "test/Test_0.js",
      """
      package test
      alias base.List as List, alias base.Int as Int, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str,
      Test:Main{ s -> Block#
        .let[List[Str]] l = {List#("A", "B", "C", "D", "E")}
        .let[List[Int]] l2 = { List#(+12, +13, +14) }
        .return {{}}
      }
      """);
  }

  @Test void getListByF() {
    ok("""
    import { rt$$ListK } from "../rt-js/ListK.js";
    
    export class test$$GetList_0 {
      static $hash$read$1$fun($this) {
        return rt$$ListK.$self.$hash$imm$0().$plus$mut$1(1n).$plus$mut$1(2n).$plus$mut$1(3n).$plus$mut$1(4n);
      }
    }
    
    export class test$$GetList_0Impl {
      $hash$read$0() { return test$$GetList_0.$hash$read$1$fun(this); }
    }
    
    test$$GetList_0.$self = new test$$GetList_0Impl();
    """,
      "test/GetList_0.js",
      """
      package test
      alias base.List as List, alias base.F as F, alias base.Nat as Nat,
      
      GetList: F[mut List[Nat]]{List# + 1 + 2 + 3 + 4}
      """);
  }

  @Test void listAs() {
    ok("""
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$ListK } from "../rt-js/ListK.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { rt$$flows } from "../rt-js/flows.js";
    import { test$$Fear720$_0 } from "../test/Fear720$_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(sys_m$, $this) {
        let l_m$ = rt$$ListK.$self.$hash$imm$3(1n,2n,3n);
    let l2_m$ = l_m$.as$read$1(test$$Fear720$_0.$self);
    var doRes1 = l_m$.add$mut$1(4n);
    var doRes2 = sys_m$.io$mut$0().println$mut$1(rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, l2_m$.flow$imm$0()).join$mut$1(rt$$Str.fromJsStr(",")));
    return base$$Void_0.$self;
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
      "test/Test_0.js",
      """
      package test
      alias base.List as List, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str, alias base.Nat as Nat,
      Test: Main{sys -> Block#
        .let[mut List[Nat]] l = {List#(1, 2, 3)}
        .let[List[Str]] l2 = {l.as{::str}}
        .do {l.add(4)}
        .do {sys.io.println(l2.flow.join ",")}
        .return {{}}
      }
      """);
  }

  @Test void listBlockElem() {
    okList(List.of("""
    import { rt$$ListK } from "../rt-js/ListK.js";
    import { test$$Fear718$_0 } from "../test/Fear718$_0.js";
    import { test$$Fear721$_0 } from "../test/Fear721$_0.js";
    import { test$$Fear722$_0 } from "../test/Fear722$_0.js";

    export class test$$BadMutation_0 {
      static $hash$imm$1$fun($this) {
        let l_m$ = rt$$ListK.$self.$hash$imm$0();
    var doRes1 = l_m$.add$mut$1(test$$Fear718$_0.$self);
    return l_m$.flow$mut$0().fold$mut$2(test$$Fear721$_0.$self,test$$Fear722$_0.$self);
      }
    }

    export class test$$BadMutation_0Impl {
      $hash$imm$0() { return test$$BadMutation_0.$hash$imm$1$fun(this); }
    }

    test$$BadMutation_0.$self = new test$$BadMutation_0Impl();
    """),
      List.of("test/BadMutation_0.js"),
      """
      package test
      alias base.List as List, alias base.Block as Block, alias base.Nat as Nat,
      
      Elem: { read .n: Nat, }
      BadMutation: {#: Nat -> Block#
        .let[mut List[mut Elem]] l = {List#}
        .do {l.add(mut Elem{.n -> 1, })}
        .return {l.flow
          .fold[Nat]({0}, {acc, e -> acc + (e.n)})
        }
      }
      """);
  }

  @Test void listIter() {
    ok("""
    import { base$$caps$$UnrestrictedIO_0 } from "../base/caps/UnrestrictedIO_0.js";
    import { rt$$ListK } from "../rt-js/ListK.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$Fear722$_0 } from "../test/Fear722$_0.js";
    import { test$$Fear724$_0 } from "../test/Fear724$_0.js";
    import { test$$Fear726$_0 } from "../test/Fear726$_0.js";
    import { test$$Fear727$_0 } from "../test/Fear727$_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(sys_m$, $this) {
        let l1_m$ = rt$$ListK.$self.$hash$imm$4(35n,52n,84n,14n);
    let msg_m$ = l1_m$.iter$mut$0().filter$mut$1(test$$Fear722$_0.$self).flatMap$mut$1(test$$Fear724$_0.$self).map$mut$1(test$$Fear726$_0.$self).str$mut$2(test$$Fear727$_0.$self,rt$$Str.fromJsStr(","));
    let io_m$ = base$$caps$$UnrestrictedIO_0.$self.$hash$read$1(sys_m$);
    return io_m$.println$mut$1(msg_m$);
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
      "test/Test_0.js",
      """
      package test
      alias base.List as List, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str, alias base.Nat as Nat,
      alias base.caps.UnrestrictedIO as UnrestrictedIO,
  
      Test :base.Main{ sys -> Block#
        .let l1 = { List#[Nat](35, 52, 84, 14) }
        .let[Str] msg = {l1.iter
          .filter{n -> n < 40}
          .flatMap{n -> List#(n, n, n).iter}
          .map{n -> n * 10}
          .str({n -> n.str}, ",")}
        .let io = {UnrestrictedIO#sys}
        .return {io.println(msg)}
      }
      """);
  }

  @Test void listAssert() {
    okList(List.of("""
    import { base$$Block_0 } from "../base/Block_0.js";
    import { test$$Fear720$_0 } from "../test/Fear720$_0.js";
    import { test$$Fear728$_0 } from "../test/Fear728$_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(sys_m$, $this) {
        return base$$Block_0.$self.$hash$imm$0().let$mut$2(test$$Fear720$_0.$self,test$$Fear728$_0.$self);
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """, """
    import { rt$$ListK } from "../rt-js/ListK.js";
    
    export class test$$Fear720$_0 {
      static $hash$mut$1$fun(fear[###]$_m$) {
        return rt$$ListK.$self.$hash$imm$4(35n,52n,84n,14n);
      }
    }
    
    export class test$$Fear720$_0Impl {
      $hash$mut$0() { return test$$Fear720$_0.$hash$mut$1$fun(this); }
    }
    
    test$$Fear720$_0.$self = new test$$Fear720$_0Impl();
    """, """
    import { test$$Fear725$_0Impl } from "../test/Fear725$_0.js";
    import { test$$Fear727$_0 } from "../test/Fear727$_0.js";
    
    export class test$$Fear728$_0 {
      static $hash$mut$3$fun(l1_m$, fear31$_m$, fear[###]$_m$) {
        return fear31$_m$.assert$mut$1(new test$$Fear725$_0Impl(l1_m$)).return$mut$1(test$$Fear727$_0.$self);
      }
    }
    
    export class test$$Fear728$_0Impl {
      $hash$mut$2(l1_m$, fear31$_m$) { return test$$Fear728$_0.$hash$mut$3$fun(l1_m$, fear31$_m$, this); }
    }
    
    test$$Fear728$_0.$self = new test$$Fear728$_0Impl();
    """),
    List.of("test/Test_0.js", "test/Fear720$_0.js", "test/Fear728$_0.js"),
    """
    package test
    alias base.List as List, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str, alias base.Nat as Nat,

    Test :base.Main{ sys -> Block#
      .let l1 = { List#[Nat](35, 52, 84, 14) }
      .assert{l1.iter
        .map{n -> n * 10}
        .find{n -> n == 140}
        .isSome}
      .return {{}}
    }
    """);
  }

  @Test void assertTrue() {
    okList(List.of("""
      import { base$$Assert_0 } from "../base/Assert_0.js";
      import { base$$True_0 } from "../base/True_0.js";
      import { test$$Fear717$_0 } from "../test/Fear717$_0.js";
      
      export class test$$Test_0 {
        static $hash$imm$2$fun(fear31$_m$, $this) {
          return base$$Assert_0.$self.$exclamation$imm$2(base$$True_0.$self,test$$Fear717$_0.$self);
        }
      }
      
      export class test$$Test_0Impl {
        $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
      }
      
      test$$Test_0.$self = new test$$Test_0Impl();
      """, """
      import { base$$Fear1655$_0 } from "../base/Fear1655$_0.js";
      import { base$$True_0 } from "../base/True_0.js";
      import { rt$$IO } from "../rt-js/IO.js";
      
      export class base$$Assert_0 {
        static $exclamation$imm$2$fun(assertion_m$, $this) {
          return $this.$exclamation$imm$2(assertion_m$,base$$Fear1655$_0.$self);
        }
        static $exclamation$imm$3$fun(assertion_m$, cont_m$, $this) {
          return (assertion_m$ == base$$True_0.$self ? cont_m$.$hash$mut$0() :   (() => {
          console.error("Assertion failed :(");
          if (typeof process !== "undefined") process.exit(1);
          else throw new Error("Assertion failed :(");
        })()
      );
        }
        static $exclamation$imm$4$fun(assertion_m$, msg_m$, cont_m$, $this) {
          return (assertion_m$ == base$$True_0.$self ? cont_m$.$hash$mut$0() :   (() => {
          rt$$IO.$self.printlnErr$mut$1(msg_m$);
          if (typeof process !== "undefined") process.exit(1);
          else throw new Error(msg_m$);
        })()
      );
        }
        static _fail$imm$1$fun($this) {
          return (function() {
          console.error("No magic code was found at:\\n" + new Error().stack);
          if (typeof process !== "undefined") process.exit(1);
          else throw new Error("No magic code was found");
        })()
      ;
        }
        static _fail$imm$2$fun(msg_m$, $this) {
          return (function() {
          console.error("No magic code was found at:\\n" + new Error().stack);
          if (typeof process !== "undefined") process.exit(1);
          else throw new Error("No magic code was found");
        })()
      ;
        }
      }
      
      export class base$$Assert_0Impl {
        $exclamation$imm$1(assertion_m$) { return base$$Assert_0.$exclamation$imm$2$fun(assertion_m$, this); }
        $exclamation$imm$2(assertion_m$, cont_m$) { return base$$Assert_0.$exclamation$imm$3$fun(assertion_m$, cont_m$, this); }
        $exclamation$imm$3(assertion_m$, msg_m$, cont_m$) { return base$$Assert_0.$exclamation$imm$4$fun(assertion_m$, msg_m$, cont_m$, this); }
        _fail$imm$0() { return base$$Assert_0._fail$imm$1$fun(this); }
        _fail$imm$1(msg_m$) { return base$$Assert_0._fail$imm$2$fun(msg_m$, this); }
      }
      
      base$$Assert_0.$self = new base$$Assert_0Impl();
      """),
      List.of("test/Test_0.js", "base/Assert_0.js"),
      """
      package test
      alias base.Assert as Assert, alias base.True as True, alias base.Void as Void, alias base.Main as Main,
      Test:Main{ _ -> Assert!(True, { Void }) }
      """);
  }


  @Test void bytesToStr() {
    ok("""
      import { rt$$Str } from "../rt-js/Str.js";
      import { rt$$UTF8 } from "../rt-js/UTF8.js";

      export class test$$Test_0 {
        static $hash$imm$2$fun(sys_m$, $this) {
          return sys_m$.io$mut$0().println$mut$1(rt$$UTF8.$self.fromBytes$imm$1(rt$$Str.fromJsStr("Hello!").utf8$imm$0()).$exclamation$mut$0());
        }
      }

      export class test$$Test_0Impl {
        $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
      }

      test$$Test_0.$self = new test$$Test_0Impl();
      """, "test/Test_0.js", """
      package test
      alias base.UTF8 as UTF8, alias base.Main as Main,
      Test: Main{sys -> sys.io.println(UTF8.fromBytes("Hello!".utf8)!)}
      """);
  }
  @Test void strToBytes() {
    okList(List.of("""
      import { rt$$Str } from "../rt-js/Str.js";
      import { rt$$flows } from "../rt-js/flows.js";
      import { test$$Fear[###]$_0 } from "../test/Fear[###]$_0.js";
      
      export class test$$Test_0 {
        static $hash$imm$2$fun(sys_m$, $this) {
          return sys_m$.io$mut$0().println$mut$1(rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, rt$$Str.fromJsStr("Hello!").utf8$imm$0().flow$imm$0()).map$mut$1(test$$Fear[###]$_0.$self).join$mut$1(rt$$Str.fromJsStr(",")));
        }
      }
      
      export class test$$Test_0Impl {
        $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
      }
      
      test$$Test_0.$self = new test$$Test_0Impl();
      """), List.of("test/Test_0.js"),
      """
      package test
      Test: Main{sys -> sys.io.println("Hello!".utf8.flow.map{b -> b.str}.join ",")}
      """, Base.mutBaseAliases);
  }

  @Test void animalType() {
    okList(List.of("""
      import { rt$$Str } from "../rt-js/Str.js";
      
      export class test$$Animal_0 {
        static name$imm$1$fun($this) {
          return rt$$Str.fromJsStr("animal");
        }
        static name$imm$2$fun(a_m$, $this) {
          return a_m$.$plus$imm$1(rt$$Str.fromJsStr("animal"));
        }
      }
      """, """
      import { rt$$Str } from "../rt-js/Str.js";
      
      export class test$$Bear_0 {
        static name$imm$1$fun($this) {
          return rt$$Str.fromJsStr("bear");
        }
      }
      """, """
      import { rt$$Str } from "../rt-js/Str.js";
      import { test$$Animal_0 } from "../test/Animal_0.js";
      import { test$$Bear_0 } from "../test/Bear_0.js";
      
      export class test$$BrownBear_0 {
        static run$imm$1$fun($this) {
          return rt$$Str.fromJsStr("BrownBear runs fast");
        }
      }
      
      export class test$$BrownBear_0Impl {
        name$imm$0() { return test$$Bear_0.name$imm$1$fun(this); }
        name$imm$1(a_m$) { return test$$Animal_0.name$imm$2$fun(a_m$, this); }
        run$imm$0() { return test$$BrownBear_0.run$imm$1$fun(this); }
      }
      
      test$$BrownBear_0.$self = new test$$BrownBear_0Impl();
      """), List.of("test/Animal_0.js", "test/Bear_0.js", "test/BrownBear_0.js"),
      """
      package test
      alias base.Str as Str,
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
      """);
  }

  @Test void animalTypes2() { okList(List.of("""
    export class test$$Foo_0 {
    }
    """, """
    export class test$$Beer_0 {
      static beer$imm$2$fun(f_m$, $this) {
        return f_m$.bar$imm$0();
      }
    }
    
    export class test$$Beer_0Impl {
      beer$imm$1(f_m$) { return test$$Beer_0.beer$imm$2$fun(f_m$, this); }
    }
    
    test$$Beer_0.$self = new test$$Beer_0Impl();
    """, """
    import { test$$Beer_0 } from "../test/Beer_0.js";
    import { test$$Fear716$_0 } from "../test/Fear716$_0.js";
    
    export class test$$User_0 {
      static go$imm$1$fun($this) {
        return test$$Beer_0.$self.beer$imm$1(test$$Fear716$_0.$self);
      }
    }
    
    export class test$$User_0Impl {
      go$imm$0() { return test$$User_0.go$imm$1$fun(this); }
    }
    
    test$$User_0.$self = new test$$User_0Impl();
    """), List.of("test/Foo_0.js", "test/Beer_0.js", "test/User_0.js"), """
    package test
    Foo:{.bar:Beer}
    Beer:{.beer(f:Foo):Beer->f.bar}
    User:{.go:Beer->Beer.beer{Beer} }
    """);
  }

  @Test void animalTypeHierarchy() { okList(List.of("""
    import { rt$$Num } from "../rt-js/Num.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Animal_0 {
      static name$imm$1$fun($this) {
        return rt$$Str.fromJsStr("animal");
      }
      static name$imm$2$fun(a_m$, $this) {
        return a_m$.$plus$imm$1(rt$$Str.fromJsStr(" animal"));
      }
      static speed$imm$1$fun($this) {
        return 0n;
      }
      static isFast$imm$1$fun($this) {
        return rt$$Num.toBool(($this.speed$imm$0()) > (10n));
      }
    }
    """, """
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$Animal_0 } from "../test/Animal_0.js";
    import { test$$Bear_0 } from "../test/Bear_0.js";
    
    export class test$$BrownBear_0 {
      static speed$imm$1$fun($this) {
        return 12n;
      }
      static speedStr$imm$1$fun($this) {
        return rt$$Str.numToStr($this.speed$imm$0()).$plus$imm$1(rt$$Str.fromJsStr(" km/h"));
      }
      static run$imm$1$fun($this) {
        return $this.name$imm$0().$plus$imm$1(rt$$Str.fromJsStr(" runs"));
      }
    }
    
    export class test$$BrownBear_0Impl {
      name$imm$0() { return test$$Bear_0.name$imm$1$fun(this); }
      speedStr$imm$0() { return test$$BrownBear_0.speedStr$imm$1$fun(this); }
      name$imm$1(a_m$) { return test$$Animal_0.name$imm$2$fun(a_m$, this); }
      isFast$imm$0() { return test$$Animal_0.isFast$imm$1$fun(this); }
      run$imm$0() { return test$$BrownBear_0.run$imm$1$fun(this); }
      growl$imm$0() { return test$$Bear_0.growl$imm$1$fun(this); }
      speed$imm$0() { return test$$BrownBear_0.speed$imm$1$fun(this); }
    }
    
    test$$BrownBear_0.$self = new test$$BrownBear_0Impl();
    """), List.of("test/Animal_0.js", "test/BrownBear_0.js"), """
    package test
    alias base.Str as Str, alias base.Nat as Nat, alias base.Bool as Bool,
    Animal: {
      .name: Str -> "animal",
      .name(a: Str): Str -> a + " animal",
      .run: Str,                // abstract, not implemented
      .speed: Nat -> 0,
      .isFast: Bool -> this.speed > 10,
    }
    Bear: Animal {
      .name: Str -> "bear",
      .growl: Str -> "Grr!",
    }
    // BrownBear completes the hierarchy and overrides .run and .speed
    BrownBear: Bear {
      .speed: Nat -> 12,
      .speedStr: Str -> (this.speed).str + " km/h",
      .run: Str -> this.name + " runs",
    }
    """);
  }

  @Test void listFlowJoin() {
    ok("""
      import { base$$flows$$_SafeSource_0 } from "../base/flows/_SafeSource_0.js";
      import { rt$$ListK } from "../rt-js/ListK.js";
      import { rt$$Str } from "../rt-js/Str.js";
      import { rt$$flows } from "../rt-js/flows.js";
      
      export class test$$Test_0 {
        static $hash$imm$2$fun(s_m$, $this) {
          return s_m$.io$mut$0().println$mut$1(rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, base$$flows$$_SafeSource_0.$self.fromList$imm$1(rt$$ListK.$self.$hash$imm$3(rt$$Str.fromJsStr("A"),rt$$Str.fromJsStr("B"),rt$$Str.fromJsStr("C")))).join$mut$1(rt$$Str.fromJsStr(",")));
        }
      }
      
      export class test$$Test_0Impl {
        $hash$imm$1(s_m$) { return test$$Test_0.$hash$imm$2$fun(s_m$, this); }
      }
      
      test$$Test_0.$self = new test$$Test_0Impl();
      """, "test/Test_0.js",
      """
      package test
      alias base.List as List, alias base.Int as Int, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str,
      Test:Main{ s -> s.io.println(List#("A", "B", "C").flow.join ",")}
      """);
  }

  @Test void listFlowLimitJoin() {
    ok("""
      import { base$$flows$$_SafeSource_0 } from "../base/flows/_SafeSource_0.js";
      import { rt$$ListK } from "../rt-js/ListK.js";
      import { rt$$Str } from "../rt-js/Str.js";
      import { rt$$flows } from "../rt-js/flows.js";

      export class test$$Test_0 {
        static $hash$imm$2$fun(s_m$, $this) {
          return s_m$.io$mut$0().println$mut$1(rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, base$$flows$$_SafeSource_0.$self.fromList$imm$1(rt$$ListK.$self.$hash$imm$5(rt$$Str.fromJsStr("A"),rt$$Str.fromJsStr("B"),rt$$Str.fromJsStr("C"),rt$$Str.fromJsStr("D"),rt$$Str.fromJsStr("E")))).limit$mut$1(2n).join$mut$1(rt$$Str.fromJsStr(",")));
        }
      }

      export class test$$Test_0Impl {
        $hash$imm$1(s_m$) { return test$$Test_0.$hash$imm$2$fun(s_m$, this); }
      }

      test$$Test_0.$self = new test$$Test_0Impl();
      """, "test/Test_0.js",
      """
      package test
      alias base.List as List, alias base.Int as Int, alias base.Block as Block, alias base.Main as Main, alias base.Str as Str,
      Test:Main{ s -> s.io.println(List#("A", "B", "C", "D", "E").flow.limit(2).join ",")}
      """);
  }


  @Test void listFlowLimitJoinExample() {
    ok("""
      import { base$$flows$$_SafeSource_0 } from "../base/flows/_SafeSource_0.js";
      import { rt$$ListK } from "../rt-js/ListK.js";
      import { rt$$Str } from "../rt-js/Str.js";
      import { rt$$flows } from "../rt-js/flows.js";
      
      export class test$$Test_0 {
        static s$imm$1$fun($this) {
          return rt$$flows.FlowCreator.fromFlow(rt$$flows.dataParallel.DataParallelFlowK.$self, base$$flows$$_SafeSource_0.$self.fromList$imm$1(rt$$ListK.$self.$hash$imm$4(rt$$Str.fromJsStr("A"),rt$$Str.fromJsStr("B"),rt$$Str.fromJsStr("C"),rt$$Str.fromJsStr("D")))).limit$mut$1(2n).join$mut$1(rt$$Str.fromJsStr(","));
        }
      }
      
      export class test$$Test_0Impl {
        s$imm$0() { return test$$Test_0.s$imm$1$fun(this); }
      }
      
      test$$Test_0.$self = new test$$Test_0Impl();
      """, "test/Test_0.js",
      """
      package test
      alias base.List as List, alias base.Str as Str,
      Test:{ .s:Str -> List#("A", "B", "C", "D").flow.limit(2).join ","}
      """);
  }

  @Test void tryRun() {
    ok("""
      import { base$$caps$$UnrestrictedIO_0 } from "../base/caps/UnrestrictedIO_0.js";
      import { rt$$Try } from "../rt-js/Try.js";
      import { test$$Fear717$_0 } from "../test/Fear717$_0.js";
      import { test$$Fear718$_0 } from "../test/Fear718$_0.js";
      
      export class test$$Test_0 {
        static $hash$imm$2$fun(s_m$, $this) {
          return base$$caps$$UnrestrictedIO_0.$self.$hash$read$1(s_m$).println$mut$1(rt$$Try.$self.$hash$imm$1(test$$Fear717$_0.$self).run$mut$1(test$$Fear718$_0.$self));
        }
      }
      
      export class test$$Test_0Impl {
        $hash$imm$1(s_m$) { return test$$Test_0.$hash$imm$2$fun(s_m$, this); }
      }
      
      test$$Test_0.$self = new test$$Test_0Impl();
      """, "test/Test_0.js",
      """
      package test
      alias base.Try as Try, alias base.Main as Main, alias base.Str as Str,
      alias base.caps.UnrestrictedIO as UnrestrictedIO,
      Test:Main{s ->
        UnrestrictedIO#s.println(Try#[Str]{"Happy"}.run{
          .ok(res) -> res,
          .info(err) -> err.str,
          })
        }
      """);
  }

  /** Paper **/
  @Test void shapeTypes() {
    okList(List.of("""
      export class test$$Shape_0 {
      }
      """, """
      export class test$$Square_0 {
        static area$imm$1$fun($this) {
          return 25n;
        }
      }
      
      export class test$$Square_0Impl {
        area$imm$0() { return test$$Square_0.area$imm$1$fun(this); }
      }
      
      test$$Square_0.$self = new test$$Square_0Impl();
      """), List.of("test/Shape_0.js", "test/Square_0.js"),
      """
      package test
      alias base.Nat as Nat,
      Shape: {
        .area: Nat   // abstract, not implemented
      }
      Square: Shape {
        .area: Nat -> 25
      }
      """);
  }

  @Test void shapeAnonymous() {
    okList(List.of("""
    import { test$$Fear715$_0 } from "../test/Fear715$_0.js";
    
    export class test$$Shapes_0 {
      static square$imm$1$fun($this) {
        return test$$Fear715$_0.$self;
      }
    }
    
    export class test$$Shapes_0Impl {
      square$imm$0() { return test$$Shapes_0.square$imm$1$fun(this); }
    }
    
    test$$Shapes_0.$self = new test$$Shapes_0Impl();
    """, """
    export class test$$Fear715$_0 {
      static area$imm$1$fun(fear[###]$_m$) {
        return 25n;
      }
    }
    
    export class test$$Fear715$_0Impl {
      area$imm$0() { return test$$Fear715$_0.area$imm$1$fun(this); }
    }
    
    test$$Fear715$_0.$self = new test$$Fear715$_0Impl();
    """, """
    export class test$$Shape_0 {
    }
    """),
    List.of("test/Shapes_0.js", "test/Fear715$_0.js", "test/Shape_0.js"),
    """
    package test
    Shape: { .area: base.Nat }
    Shapes: {
      .square: Shape -> { .area -> 25 }
    }
    """);
  }

  @Test void shapeConstructor() {
    okList(List.of("""
    import { test$$Fear714$_0Impl } from "../test/Fear714$_0.js";
    
    export class test$$Shapes_0 {
      static rectangle$imm$3$fun(width_m$, height_m$, $this) {
        return new test$$Fear714$_0Impl(height_m$, width_m$);
      }
    }
    
    export class test$$Shapes_0Impl {
      rectangle$imm$2(width_m$, height_m$) { return test$$Shapes_0.rectangle$imm$3$fun(width_m$, height_m$, this); }
    }
    
    test$$Shapes_0.$self = new test$$Shapes_0Impl();
    """, """
    import { rt$$Num } from "../rt-js/Num.js";
    
    export class test$$Fear714$_0 {
      static area$imm$3$fun(fear[###]$_m$, height_m$, width_m$) {
        return rt$$Num.toNat64(width_m$ * height_m$);
      }
    }
    
    export class test$$Fear714$_0Impl {
      constructor(height_m$, width_m$) {
        this.height_m$ = height_m$;
        this.width_m$ = width_m$;
      }
      area$imm$0() { return test$$Fear714$_0.area$imm$3$fun(this, this.height_m$, this.width_m$); }
    }
    """, """
    export class test$$Shape_0 {
    }
    """),
    List.of("test/Shapes_0.js", "test/Fear714$_0.js", "test/Shape_0.js"),
    """
    package test
    Shape: { .area: base.Nat }
    Shapes: {
      .rectangle(width: base.Nat, height: base.Nat): Shape ->
        { .area -> width * height }
    }
    """);
  }

  @Test void shapeOverload() {
    okList(List.of("""
    import { rt$$Num } from "../rt-js/Num.js";
    
    export class test$$Shape_0 {
      static area$imm$1$fun($this) {
        return 0n;
      }
      static area$imm$2$fun(side_m$, $this) {
        return rt$$Num.toNat64(side_m$ ** 2n);
      }
    }

    export class test$$Shape_0Impl {
      area$imm$1(side_m$) { return test$$Shape_0.area$imm$2$fun(side_m$, this); }
      area$imm$0() { return test$$Shape_0.area$imm$1$fun(this); }
    }

    test$$Shape_0.$self = new test$$Shape_0Impl();
    """),
    List.of("test/Shape_0.js"),
    """
    package test
    Shape: {
      .area: base.Nat -> 0,
      .area(side: base.Nat): base.Nat -> side ** 2,
    }
    """);
  }

  @Test void stringExample() {
    okList(List.of("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static concat$imm$1$fun($this) {
        return rt$$Str.fromJsStr("Fear").$plus$imm$1(rt$$Str.fromJsStr("less"));
      }
      static substr$imm$1$fun($this) {
        return rt$$Str.fromJsStr("Fearless").substring$imm$2(4n,8n);
      }
    }
    
    export class test$$Test_0Impl {
      substr$imm$0() { return test$$Test_0.substr$imm$1$fun(this); }
      concat$imm$0() { return test$$Test_0.concat$imm$1$fun(this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """),
    List.of("test/Test_0.js"),
    """
    package test
    alias base.Str as Str,
    Test: {
      .concat: Str -> "Fear" + "less",
      .substr: Str -> "Fearless".substring(4, 8)
    }
    """);
  }

  @Test void pkgExample() {
    okList(List.of("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$A_0 {
      static ma$imm$1$fun($this) {
        return rt$$Str.fromJsStr("A");
      }
    }
    
    export class test$$A_0Impl {
      ma$imm$0() { return test$$A_0.ma$imm$1$fun(this); }
    }
    
    test$$A_0.$self = new test$$A_0Impl();
    """, """
    import { base$$True_0 } from "../base/True_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$A_0 } from "../test/A_0.js";
    
    export class test$$B_0 {
      static mb$imm$1$fun($this) {
        return rt$$Str.fromJsStr("B").$plus$imm$1($this.ma$imm$0());
      }
      static t$imm$1$fun($this) {
        return base$$True_0.$self;
      }
    }
    
    export class test$$B_0Impl {
      mb$imm$0() { return test$$B_0.mb$imm$1$fun(this); }
      ma$imm$0() { return test$$A_0.ma$imm$1$fun(this); }
      t$imm$0() { return test$$B_0.t$imm$1$fun(this); }
    }
    
    test$$B_0.$self = new test$$B_0Impl();
    """),
    List.of("test/A_0.js", "test/B_0.js"),
    """
    package test
    alias base.Str as Str, alias base.Bool as Bool, alias base.True as True, alias base.False as False,
    A:{
      .ma:Str -> "A",
    }
    B:A {
      .mb:Str-> "B" + (this.ma),
      .t: Bool -> True,
    }
    """);
  }

  @Test void addFloat() {
    okList(List.of("""
    import { base$$Assert_0 } from "../base/Assert_0.js";
    import { base$$False_0 } from "../base/False_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$Fear719$_0 } from "../test/Fear719$_0.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(fear31$_m$, $this) {
        return base$$Assert_0.$self.$exclamation$imm$3(base$$False_0.$self,rt$$Str.numToStr(parseFloat((0.1 + 0.2))),test$$Fear719$_0.$self);
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(fear31$_m$) { return test$$Test_0.$hash$imm$2$fun(fear31$_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """),
    List.of("test/Test_0.js"),
    """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test:Main{ _ -> Assert!(False, (0.1 + 0.2) .str, { Void }) }
    """);
  }

  @Test void packageTest() {
    okList(List.of("""
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(sys_m$, $this) {
        return sys_m$.io$mut$0().println$mut$1(rt$$Str.fromJsStr("check if it will import rt$$NativeRuntime"));
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(sys_m$) { return test$$Test_0.$hash$imm$2$fun(sys_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """),
    List.of("test/Test_0.js"),
    """
    package test
    alias base.Main as Main, alias base.Assert as Assert, alias base.True as True, alias base.False as False,
    alias base.Void as Void,
    Test: Main{sys -> sys.io.println("check if it will import rt$$NativeRuntime")}
    """);
  }

}