package main;

import codegen.MIR;
import program.typesystem.TsT;

import java.util.concurrent.ConcurrentHashMap;

public interface FullLogicMain<Exe> extends LogicMain {
  CompilerFrontEnd.Verbosity verbosity();

  MIR.Program lower(ast.Program program, ConcurrentHashMap<Long, TsT> resolvedCalls);
  Exe codeGeneration(MIR.Program program);
  void compileBackEnd(Exe exe);
  ProcessBuilder execution(Exe exe);
  default Exe buildAndCache() {
    var fullProgram= parse();
    wellFormednessFull(fullProgram);
    var program = inference(fullProgram);
    wellFormednessCore(program);
    var resolvedCalls = typeSystem(program);
    var timer = new Timer();
    verbosity().progress().printTask("Running code generation 🏭");
    var mir = lower(program,resolvedCalls);
    var exe = codeGeneration(mir);
    verbosity().progress().printTask("Code generated 🥳 ("+timer.duration()+"ms)");
    verbosity().progress().printStep("Executing backend compiler 🏭");
    compileBackEnd(exe);
    verbosity().progress().printStep("Done executing backend compiler 🥳 ("+timer.duration()+"ms)");
    cachePackageTypes(program);
    return exe;
  }
  default ProcessBuilder run(){
    var executable = buildAndCache();
    return execution(executable);
  }
}