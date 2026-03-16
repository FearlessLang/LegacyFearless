package main.js;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import ast.Program;
import codegen.MIR;
import codegen.MIRInjectionVisitor;
import codegen.js.JsMagicImpls;
import codegen.js.JsProgram;
import codegen.optimisations.OptimisationBuilder;
import main.CompilerFrontEnd.Verbosity;
import main.InputOutput;
import main.FullLogicMain;
import program.typesystem.TsT;

public interface LogicMainJs extends FullLogicMain<JsProgram> {
  @Override
  default void cachePackageTypes(Program program) {
    // No-op for JavaScript (no Java-style caching needed)
  }

  @Override
  default MIR.Program lower(Program program, ConcurrentHashMap<Long, TsT> resolvedCalls) {
    var mir = new MIRInjectionVisitor(cachedPkg(), program, resolvedCalls).visitProgram();
    var magic = new JsMagicImpls(null, mir.p());
    return new OptimisationBuilder(magic)
      .withAsIdFnOptimisation()
      .withBoolIfOptimisation()
      .withBlockOptimisation()
      .run(mir);
  }

  @Override
  default JsProgram codeGeneration(MIR.Program mir) {
    var res = new JsProgram(this, mir);

    if (verbosity().printCodegen()) {
      var tmp = utils.IoErr.of(()->java.nio.file.Files.createTempDirectory("fgenjs"));
      res.writeJsFiles(tmp);
      System.out.println("saved to file://" + tmp.toAbsolutePath());
    }
    return res;
  }

  @Override
  default void compileBackEnd(JsProgram src) {
    src.writeJsFiles(io().output());
  }

  @Override default ProcessBuilder execution(JsProgram exe) {
    InputOutput io = io();
    // Generate a main.js entry file in the output directory
    Path mainJs = io.output().resolve("main.js");

    // Write the main.js content
    try {
      String entry = io.entry();           // e.g. "test.Test"
      String packagePath = entry.replace(".", "/") + "_0"; // -> "test/Test_0"
      String entryTypeName = entry.replace(".", "$$") + "_0"; // -> "test$$Test_0"
      String mainJsContent = """
      import { %s } from './%s.js';
      import {RealSystem} from './rt-js/RealSystem.js';
      import {rt$$NativeRuntime} from './rt-js/NativeRuntime.js';

      async function main() {
        const program = %s.$self;
        try {
          rt$$NativeRuntime.ensureWasm();
          await program.$hash$imm$1(new RealSystem());
        } catch (err) {
          if (err.getMessage) {
            console.error('Program crashed with:', err.toString());
          } else {
            console.error(err);
          }
          process.exit(1);
        }
      }

      main();
      """.formatted(entryTypeName, packagePath, entryTypeName);

      java.nio.file.Files.writeString(mainJs, mainJsContent);
    } catch (Exception e) {
      throw new RuntimeException("Failed to write main.js entry", e);
    }

    // Return a ProcessBuilder to execute Node
    ProcessBuilder pb = new ProcessBuilder("node", mainJs.toString())
      .directory(io().output().toFile())
      .redirectError(ProcessBuilder.Redirect.PIPE)
      .redirectOutput(ProcessBuilder.Redirect.PIPE);
    return pb;
  }

  static LogicMainJs of(InputOutput io, Verbosity verbosity) {
    var cachedPkg = new HashSet<String>();
    return new LogicMainJs() {
      public InputOutput io() { return io; }
      public HashSet<String> cachedPkg() { return cachedPkg; }
      public Verbosity verbosity() { return verbosity; }
    };
  }
}