package main;

import astFull.E;
import codegen.zig.ZigBuildOpts;
import com.github.bogdanovmn.cmdline.CmdLineAppBuilder;
import id.Id;
import main.html.LogicMainHtml;
import main.java.LogicMainJava;
import main.zig.LogicMainZig;
import program.MethLookup;
import program.typesystem.SubTyping;
import utils.Box;
import utils.Bug;
import utils.IoErr;
import utils.ResolveResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class Main {
  public static void resetAll(){
    E.X.reset();
    Id.GX.reset();
    MethLookup.methsCache.clear();
    SubTyping.subTypeCache.clear();
    ResolveResource.cleanTmpPaths();
  }

  /// Copies the built binary out of the zig build tree to a stable, user-facing
  /// location: `<project>/out/<EntryName>`.
  private static Path installZigExecutable(Path builtExe, String entry, Path projectPath) {
    var name = entry.substring(entry.lastIndexOf('.') + 1);
    var dest = projectPath.resolve("out").resolve(name);
    IoErr.of(() -> Files.copy(builtExe, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES));
    return dest;
  }

  public static void main(String[] args) {
    args = args.length > 0 ? args : new String[]{"--help"};
    var verbosity = new Box<>(new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.Full));
//This commented code was terrible anyway, please, divide in more methods and concepts....
     var cli = new CmdLineAppBuilder(args)
      .withJarName("fearless")
      .withDescription("The compiler for the Fearless programming language. See https://fearlang.org for more information.")
      .withFlag("new", "Create a new package")
      .withFlag("check", "c", "Check that the given Fearless program is valid")
      .withFlag("build", "b", "Compile the given Fearless program")
      .withFlag("run", "r", "Compile and run the given Fearless program")
        .withArg("entry-point", "The qualified name for the entry-trait that implements base.Main")
        .withDependencies("run", "entry-point")
      .withFlag("regenerate-aliases", "ra", "Print the default alias file for a new package to standard output")
      .withFlag("generate-docs", "d", "Generate documentation for the given Fearless program")
      .withFlag("imm-base", "Use a pure version of the Fearless standard library")
      .withFlag("show-internal-stack-traces", "di", "Show stack traces within the compiler on errors for debugging purposes")
      .withFlag("print-codegen", "pc", "Print the output of the codegen stage to standard output")
      .withFlag("show-tasks", "sct", "Print progress messages showing the current task the compiler is performing (default).")
      .withFlag("show-full-progress", "sfp", "Print progress messages showing the current task and all sub-tasks the compiler is performing (default).")
      .withFlag("quiet", "q", "Do not print compilation progress messages.")
      .withFlag("feart", "Compile using the FeaRT backend")
      .withArg("vpf-threshold", "FeaRT only: heartbeat promotion token threshold; lower forces more aggressive VPF promotion")
      .withFlag("no-vpf", "nv", "FeaRT only: compile out the heartbeat/VPF automatic parallelism system")
      .withFlag("stack-traces", "st", "FeaRT only: track a per-call trace stack so an uncaught crash prints a Fearless stack trace")
      .withFlag("debug", "dbg", "FeaRT only: enable stack traces and runtime tracing, and optimise with ReleaseSafe instead of ReleaseFast")
      .withAtLeastOneRequiredOption("help", "new", "check", "build", "run", "regenerate-aliases", "generate-docs")
      .withEntryPoint(res->{
        CompilerFrontEnd.ProgressVerbosity pv = CompilerFrontEnd.ProgressVerbosity.Full;
        if (res.hasOption("show-tasks")) { pv = CompilerFrontEnd.ProgressVerbosity.Tasks; }
        if (res.hasOption("show-full-progress")) { pv = CompilerFrontEnd.ProgressVerbosity.Full; }
        if (res.hasOption("quiet")) { pv = CompilerFrontEnd.ProgressVerbosity.None; }
        verbosity.set(new CompilerFrontEnd.Verbosity(
          res.hasOption("show-internal-stack-traces"),
          res.hasOption("print-codegen"),
          pv
        ));

        if (res.hasOption("regenerate-aliases")) {
          var trashIO = InputOutput.trash(res.hasOption("imm-base"));
          System.out.println(trashIO.generateAliases());
          return;
        }

        if (res.getArgList().isEmpty()) {
          throw Bug.todo("good error about no project path existing");
        }
        var projectPath = Path.of(res.getArgList().getFirst());
        var extraArgs = res.getArgList().subList(1, res.getArgList().size());
        var io = res.hasOption("feart")
          ? InputOutput.userFolderZig(res.getOptionValue("entry-point"), extraArgs, projectPath, res.hasOption("imm-base"))
          : res.hasOption("imm-base")
          ? InputOutput.userFolderImm(res.getOptionValue("entry-point"), extraArgs, projectPath)
          : InputOutput.userFolder(res.getOptionValue("entry-point"), extraArgs, projectPath);

        if (res.hasOption("generate-docs")) {
          var main = LogicMainHtml.of(io);
          main.writeDocs();
          System.out.println("Documentation written to: "+io.output());
          return;
        }

        if (res.hasOption("feart")) {
          var zigOpts = new ZigBuildOpts(
            res.hasOption("vpf-threshold") ? Integer.parseInt(res.getOptionValue("vpf-threshold")) : null,
            !res.hasOption("no-vpf"),
            res.hasOption("stack-traces"),
            res.hasOption("debug"),
            false
          );
          var zigMain = LogicMainZig.of(io, verbosity.get(), zigOpts);
          if (res.hasOption("check")) {
            zigMain.check();
            System.out.println("All checks passed");
            return;
          }
          // FeaRT has no dynamic entry point: it is baked into the binary at build time
          if (io.entry() == null) {
            throw new RuntimeException("The FeaRT backend needs --entry-point at compile time (the entry point is baked into the executable).");
          }
          if (res.hasOption("run")) {
            var p = zigMain.run().inheritIO().start().onExit().join();
            System.exit(p.exitValue());
            return;
          }
          if (res.hasOption("build")) {
            zigMain.buildAndCache();
            var exe = installZigExecutable(zigMain.executablePath(), io.entry(), projectPath);
            System.out.println("Compilation successful (FeaRT backend)\nExecutable: " + exe);
            return;
          }
        } else if (res.hasOption("vpf-threshold") || res.hasOption("no-vpf") || res.hasOption("stack-traces") || res.hasOption("debug")) {
          System.err.println("Warning: --vpf-threshold, --no-vpf, --stack-traces and --debug only apply to the FeaRT backend (--zig); ignoring.");
        }

        var main = LogicMainJava.of(io, verbosity.get());
        if (res.hasOption("new")) {
          throw Bug.todo();
        }
        if (res.hasOption("check")) {
          main.check();
          System.out.println("All checks passed");
          return;
        }
        if (res.hasOption("build")) {
          main.buildAndCache();
          System.out.println("Compilation successful");
          return;
        }
        if (res.hasOption("run")) {
          var p = main.run().inheritIO().start().onExit().join();
          System.exit(p.exitValue());
          return;
        }

//        frontEnd = new CompilerFrontEnd(bv, verbosity.get(), new TypeSystemFeatures());
//        if (res.hasOption("new")) {
//          frontEnd.newPkg(res.getOptionValue("new"));
//          return;
//        }
//        if (res.hasOption("generate-docs")) {
//          frontEnd.generateDocs(res.getOptionValues("generate-docs"));
//          return;
//        }
        throw Bug.unreachable();
      });


    try {
      cli.build().run();
    } catch (RuntimeException e) {
      if (verbosity.get().showInternalStackTraces()) {
        throw e;
      }
      System.err.println(e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      throw Bug.of(e);
    }
  }
}
