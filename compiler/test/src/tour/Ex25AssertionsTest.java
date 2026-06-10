package tour;

import org.junit.jupiter.api.Test;
import utils.RunOutput;
import utils.RunOutput.Res;

import static codegen.java.RunJavaProgramTests.ok;

public class Ex25AssertionsTest {
  @Test void strAssertions() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,
    
    Test: Main{_ -> "a".assertEq("a")}
    """);}
  @Test void strAssertionsFail() { ok(new RunOutput.Res("", "Program crashed with: \"Expected: a\\nActual: b\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> "a".assertEq("b")}
    """);}
  @Test void strAssertionsFailWithMessage() { ok(new RunOutput.Res("", "Program crashed with: \"oh no\\nExpected: a\\nActual: b\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> "a".assertEq("b", "oh no")}
    """);}


  @Test void intAssertions() { ok(new RunOutput.Res("", "", 0), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,
    
    Test: Main{_ -> (+5).assertEq(+5)}
    """);}
  @Test void intAssertionsFail() { ok(new RunOutput.Res("", "Program crashed with: \"Expected: 5\\nActual: 10\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> (+5).assertEq(+10)}
    """);}
  @Test void intAssertionsFailWithMessage() { ok(new RunOutput.Res("", "Program crashed with: \"oh no\\nExpected: 5\\nActual: 10\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> (+5).assertEq(+10, "oh no")}
    """);}

  @Test void natAssertions() { ok(new RunOutput.Res("", "", 0), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,
    
    Test: Main{_ -> (5).assertEq(5)}
    """);}
  @Test void natAssertionsFail() { ok(new RunOutput.Res("", "Program crashed with: \"Expected: 5\\nActual: 10\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> (5).assertEq(10)}
    """);}
  @Test void natAssertionsFailWithMessage() { ok(new RunOutput.Res("", "Program crashed with: \"oh no\\nExpected: 5\\nActual: 10\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> (5).assertEq(10, "oh no")}
    """);}

  @Test void floatAssertions() { ok(new RunOutput.Res("", "", 0), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,
    
    Test: Main{_ -> (5.23).assertEq(5.23)}
    """);}
  @Test void floatAssertionsFail() { ok(new RunOutput.Res("", "Program crashed with: \"Expected: 5.23\\nActual: 5.64\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> (5.23).assertEq(5.64)}
    """);}
  @Test void floatAssertionsFailWithMessage() { ok(new RunOutput.Res("", "Program crashed with: \"oh no\\nExpected: 5.23\\nActual: 5.64\"[###]", 1), """
    package test
    alias base.Main as Main,
    alias base.Int as Int, alias base.Nat as Nat, alias base.Float as Float,
    alias base.Str as Str,

    Test: Main{_ -> (5.23).assertEq(5.64, "oh no")}
    """);}
}
