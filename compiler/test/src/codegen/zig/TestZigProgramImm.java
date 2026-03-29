package codegen.zig;

import org.junit.jupiter.api.Test;

import static codegen.zig.RunZigProgramTests.ok;
import static utils.RunOutput.Res;

public class TestZigProgramImm {
  @Test void emptyProgram() { ok(new Res("", "", 0), """
    package test
    alias base.Main as Main,
    Test:Main{ _ -> "" }
    """);}

  @Test void stringLiteral() { ok(new Res("hello", "", 0), """
    package test
    alias base.Main as Main,
    Test:Main{ _ -> "hello" }
    """);}

  @Test void natToString() { ok(new Res("42", "", 0), """
    package test
    alias base.Main as Main,
    Test:Main{ _ -> 42.str }
    """);}

  @Test void arithmetic() { ok(new Res("3", "", 0), """
    package test
    alias base.Main as Main,
    Test:Main{ _ -> (1 + 2).str }
    """);}

  @Test void booleanLogic() { ok(new Res("Yay!", "", 0), """
    package test
    alias base.Main as Main,
    Test:Main{ _ -> (1 + 2) > 7 ? { .then -> "uh oh", .else -> "Yay!" } }
    """);}

  @Test void fib40() { ok(new Res("102334155", "", 0), """
    package test
    alias base.Main as Main, alias base.Nat as Nat,
    Test:Main{ _ -> Fib#(40).str }
    Fib: {
      #(n: Nat): Nat -> n <= 1 ? {
        .then -> n,
        .else -> this#(n - 1) + (this#(n - 2))
        }
      }
    """);}
}
