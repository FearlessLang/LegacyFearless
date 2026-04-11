package codegen.zig;

import codegen.java.TestInputOutputs;
import main.CompilerFrontEnd;
import main.InputOutput;
import main.Main;
import main.zig.LogicMainZig;
import utils.IoErr;
import utils.ResolveResource;

import java.nio.file.Files;
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
    var verbosity = new CompilerFrontEnd.Verbosity(true, false, CompilerFrontEnd.ProgressVerbosity.None);
    var logicMain = LogicMainZig.of(TestInputOutputs.programmaticImm(Arrays.asList(content), args), verbosity);
    assertResMatch(logicMain.run(), expected);
  }
  public static void okBase(Res expected, String content, String aliases) {
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(true, false, CompilerFrontEnd.ProgressVerbosity.None);
    var workingDir = ResolveResource.freshTmpPath();
    IoErr.of(() -> Files.createDirectories(workingDir));
    var io = InputOutput.programmatic("test.Test", List.of(),
      List.of(content, aliases), workingDir, ResolveResource.artefact("/cachedBase"));
    var logicMain = LogicMainZig.of(io, verbosity);
    assertResMatch(logicMain.run(), expected);
  }
}
