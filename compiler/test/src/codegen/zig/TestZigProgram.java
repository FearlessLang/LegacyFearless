package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

public class TestZigProgram {
  @Test void emptyProgram() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> {} }
    """, Base.mutBaseAliases);}

  @Test void printAndPrintln() { okBase(new Res("HelloWorld", "", 0), """
    package test
    Test:Main{ sys -> mut Block[Void]
      .do{ sys.io.print("Hello") }
      .return{ sys.io.println("World") }
      }
    """, Base.mutBaseAliases);}

  @Test void varGetAndSet() { okBase(new Res("42", "", 0), """
    package test
    Test:Main{ sys -> mut Block[Void]
      .let[mut Var[Str]] v = { Vars#[Str]("hello") }
      .do{ v.set("42") }
      .return{ sys.io.println(v.get) }
      }
    """, Base.mutBaseAliases);}

  @Test void listIter() { okBase(new Res("350,350,350,140,140,140", "", 0), """
    package test
    Test: Main{sys -> Block#
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
      .let io = {sys.io}
      .return {io.println(msg)}
      // prints 350,350,350,140,140,140
    }
    """, Base.mutBaseAliases);}
}
