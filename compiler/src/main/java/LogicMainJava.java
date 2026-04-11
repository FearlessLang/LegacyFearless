package main.java;

import ast.Program;
import codegen.MIR;
import codegen.MIRInjectionVisitor;
import codegen.java.JavaCompiler;
import codegen.java.JavaMagicImpls;
import codegen.java.JavaProgram;
import codegen.optimisations.OptimisationBuilder;
import main.CompilerFrontEnd.Verbosity;
import main.FullLogicMain;
import main.InputOutput;
import program.typesystem.TsT;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public interface LogicMainJava extends FullLogicMain<JavaProgram> {
  @Override default String backendName() { return "java"; }
  @Override default void cachePackageTypes(ast.Program program) {
    HDCache.cachePackageTypes(this, program);
  }

  @Override default MIR.Program lower(Program program, ConcurrentHashMap<Long, TsT> resolvedCalls) {
    var mir = new MIRInjectionVisitor(cachedPkg(),program, resolvedCalls).visitProgram();
    var magic = new JavaMagicImpls(null, null, mir.p());
    return new OptimisationBuilder(magic)
      .withAsIdFnOptimisation()
      .withBoolIfOptimisation()
      .withBlockOptimisation()
      .run(mir);
  }
  default JavaProgram codeGeneration(MIR.Program mir) {
    var res= new JavaProgram(this,mir);

    if (verbosity().printCodegen()) {
      var tmp = utils.IoErr.of(()->java.nio.file.Files.createTempDirectory("fgen"));
      res.writeJavaFiles(tmp);
      System.out.println("saved to "+tmp);
    }
    return res;
  }

  @Override default void compileBackEnd(JavaProgram src) {
    var c= new JavaCompiler(verbosity(),io());
    c.compile(src.files());
  }

  default ProcessBuilder execution(JavaProgram exe){
    return MakeJavaProcess.of(io());
  }
  static LogicMainJava of(
		  InputOutput io, Verbosity verbosity){
    var cachedPkg=new HashSet<String>();
    return new LogicMainJava(){
      public InputOutput io(){ return io; }
      public HashSet<String> cachedPkg(){ return cachedPkg; }
      public Verbosity verbosity(){ return verbosity; }
    };
  }
}
class MakeJavaProcess{
  static public ProcessBuilder of(InputOutput io) {
    var command= makeJavaCommand(io);
    //System.out.println(List.of(command));
    return new ProcessBuilder(command);
  }
  static private String[] makeJavaCommand(InputOutput io) {
    Path fearlessMainPath = io.cachedBase()
            .resolve("base/FearlessMain.class");
    var jrePath = Path.of(System.getProperty("java.home"), "bin", "java")
            .toAbsolutePath().toString();
    String entryPoint = "base."
            + fearlessMainPath.getFileName().toString().split("\\.class")[0];
    String classpath = io.output().toAbsolutePath()
            + File.pathSeparator
            + io.cachedBase().toAbsolutePath();
    var baseCommand = Stream.of(
      jrePath,
      "-cp", classpath,
      "--enable-preview",
      "--enable-native-access=ALL-UNNAMED",
      "-ea",
      entryPoint,
      io.entry()
    );
    return Stream.concat(
      baseCommand,
      io.commandLineArguments().stream()
    ).toArray(String[]::new);
  }
}