package codegen.zig;

import codegen.java.TestInputOutputs;
import main.CompilerFrontEnd;
import main.InputOutput;
import main.Main;
import main.zig.LogicMainZig;
import utils.IoErr;
import utils.ResolveResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    var logicMain = LogicMainZig.of(TestInputOutputs.programmaticImm(Arrays.asList(content), args), verbosity, null, true);
    assertResMatch(logicMain.run(), expected);
  }
  public static void okBase(Res expected, String... content) {
    okBaseStrings(expected, Arrays.asList(content), null);
  }
  public static void okBase(Res expected, Path... content) {
    okBaseStrings(expected, Arrays.stream(content).map(ResolveResource::read).toList(), null);
  }
  /// As {@link #okBase(Res, String...)}, but it sets the heartbeat promotion threshold. A low
  /// threshold makes VPF promote often, and thus tests the work-stealing joins.
  public static void okBase(int tokensThreshold, Res expected, String... content) {
    okBaseStrings(expected, Arrays.asList(content), tokensThreshold);
  }
  private static void okBaseStrings(Res expected, List<String> content, Integer tokensThreshold) {
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var workingDir = ResolveResource.freshTmpPath(verbosity.printCodegen());
    IoErr.of(() -> Files.createDirectories(workingDir));
    var io = InputOutput.programmatic(
      "test.Test",
      List.of(),
      content,
      workingDir,
      ResolveResource.artefact("/cachedBase")
    );
    var logicMain = LogicMainZig.of(io, verbosity, tokensThreshold, true);
    assertResMatch(logicMain.run(), expected);
  }

  /// The Zig source that the last test compile in this fork generated for `pkg`. A test can
  /// assert on the emitted code, which is necessary when the test must show that an optimisation
  /// occurred.
  public static String generatedZig(String pkg) {
    var dir = new ZigCompiler(null, null, ZigBuildOpts.forTests(null)).workDir().resolve("src/generated");
    return IoErr.of(() -> Files.readString(dir.resolve(pkg.replace(".", "_") + ".zig")));
  }

  /// As {@link #okBase(Res, String...)}, but it runs the binary in a new tmp dir that holds the
  /// `fixtures` files, and gives `args` on the command line. The Zig backend does not set
  /// `ProcessBuilder.directory`, so without this the binary uses the working directory of the
  /// JVM and a relative path finds no fixture.
  public static void okBaseInDir(Res expected, Map<String, String> fixtures, List<String> args, String... content) {
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(true, false, CompilerFrontEnd.ProgressVerbosity.None);
    var runDir = ResolveResource.freshTmpPath();
    IoErr.of(() -> Files.createDirectories(runDir));
    fixtures.forEach((name, body) -> IoErr.of(() -> Files.writeString(runDir.resolve(name), body)));
    var io = InputOutput.programmatic(
      "test.Test",
      args,
      Arrays.asList(content),
      runDir,
      ResolveResource.artefact("/cachedBase")
    );
    var logicMain = LogicMainZig.of(io, verbosity, null, true);
    var pb = logicMain.run();
    pb.directory(runDir.toFile());
    assertResMatch(pb, expected);
  }
}
