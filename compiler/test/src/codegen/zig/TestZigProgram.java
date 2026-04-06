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
}
