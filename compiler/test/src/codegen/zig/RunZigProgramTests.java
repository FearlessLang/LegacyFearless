package codegen.zig;

import codegen.java.TestInputOutputs;
import main.CompilerFrontEnd;
import main.Main;
import main.zig.LogicMainZig;

import java.util.Arrays;
import java.util.List;

import static utils.RunOutput.Res;
import static utils.RunOutput.assertResMatch;

public class RunZigProgramTests {
  public static void ok(Res expected, String... content) {
    okWithArgs(expected, List.of(), content);
  }
  public static void okWithArgs(Res expected, List<String> args, String... content) {
    assert content.length > 0;
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var logicMain = LogicMainZig.of(TestInputOutputs.programmaticImm(Arrays.asList(content), args), verbosity);
    assertResMatch(logicMain.run(), expected);
  }
}
