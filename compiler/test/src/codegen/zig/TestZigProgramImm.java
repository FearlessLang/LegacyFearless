package codegen.zig;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.ok;
import static utils.RunOutput.Res;

@Disabled("Experimental & Slow, run these tests explicitly if you need to")
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

  @Disabled("A bit computationally heavy, but the test should always pass")
  @Test void ternaryVPF() { ok(new Res("1300483311", "", 0), """
    package test
    alias base.Main as Main, alias base.Nat as Nat,
    Test:Main{ _ -> Tri#(43, 43, 43).str }
    Tri: {
      #(a: Nat, b: Nat, c: Nat): Nat -> this.combine((this.work(a)), (this.work(b)), (this.work(c))),
      .work(n: Nat): Nat -> n <= 1 ? { .then -> n, .else -> (this.work(n - 1)) + (this.work(n - 2)) },
      .combine(x: Nat, y: Nat, z: Nat): Nat -> x + y + z
      }
    """);}

  @Disabled("A bit computationally heavy, but the test should always pass")
  @Test void quaternaryVPF() { ok(new Res("1733977748", "", 0), """
    package test
    alias base.Main as Main, alias base.Nat as Nat,
    Test:Main{ _ -> Quad#(43, 43, 43, 43).str }
    Quad: {
      #(a: Nat, b: Nat, c: Nat, d: Nat): Nat ->
        this.combine((this.work(a)), (this.work(b)), (this.work(c)), (this.work(d))),
      .work(n: Nat): Nat -> n <= 1 ? { .then -> n, .else -> (this.work(n - 1)) + (this.work(n - 2)) },
      .combine(w: Nat, x: Nat, y: Nat, z: Nat): Nat -> w + x + y + z
      }
    """);}
}
