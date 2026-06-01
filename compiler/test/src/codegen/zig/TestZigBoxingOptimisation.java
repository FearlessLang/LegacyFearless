package codegen.zig;

import codegen.MIR;
import id.Id;
import magic.Magic;
import main.CompilerFrontEnd;
import main.InputOutput;
import main.Main;
import main.zig.LogicMainZig;
import org.junit.jupiter.api.Test;
import utils.Base;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestZigBoxingOptimisation {
  private MIR.Program lower(String content) {
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var main = LogicMainZig.of(InputOutput.programmaticAuto(Arrays.asList(content, Base.mutBaseAliases)), verbosity);
    var fullProgram = main.parse();
    main.wellFormednessFull(fullProgram);
    var program = main.inference(fullProgram);
    main.wellFormednessCore(program);
    var resolvedCalls = main.typeSystem(program);
    return main.lower(program, resolvedCalls);
  }

  @Test void methodBodiesAreBoxed() {
    var mir = lower("""
      package test
      T:{}
      A:{ #(t: T): T -> t }
      """);

    assertTrue(mir.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .allMatch(fun -> fun.body() instanceof MIR.Box));
  }

  @Test void storageArgumentsAreBoxed() {
    var mir = lower("""
      package test
      T:{}
      A:{ #(t: T): iso Var[T] -> Vars#t }
      """);

    assertTrue(exprs(mir)
      .filter(MIR.MCall.class::isInstance)
      .map(MIR.MCall.class::cast)
      .filter(call -> isSubtypeOf(mir, call.recv(), Magic.Vars))
      .anyMatch(call -> call.args().getFirst() instanceof MIR.Box));
  }

  @Test void identityTypedCallResultsAreBoxed() {
    var mir = lower("""
      package test
      Ident: HasIdentity{ mut .idEq(other: readH HasIdentity): Bool -> False }
      Id:{ #(i: Ident): Ident -> i }
      Sink:{ #(i: Ident): Void -> Void }
      A:{ #(i: Ident): Void -> Sink#(Id#i) }
      """);

    assertTrue(exprs(mir)
      .filter(MIR.Box.class::isInstance)
      .map(MIR.Box.class::cast)
      .map(MIR.Box::inner)
      .filter(MIR.MCall.class::isInstance)
      .map(MIR.MCall.class::cast)
      .anyMatch(call -> call.t().name()
        .map(name -> mir.p().superDecIds(name).contains(Magic.HasIdentity))
        .orElse(false)));
  }

  private static boolean isSubtypeOf(MIR.Program mir, MIR.E e, Id.DecId parent) {
    return e.t().name()
      .map(name -> mir.p().superDecIds(name).contains(parent))
      .orElse(false);
  }

  private static Stream<MIR.E> exprs(MIR.Program mir) {
    return mir.pkgs().stream()
      .flatMap(pkg -> pkg.funs().stream())
      .flatMap(fun -> exprs(fun.body()));
  }

  private static Stream<MIR.E> exprs(MIR.E e) {
    return Stream.concat(Stream.of(e), children(e).flatMap(TestZigBoxingOptimisation::exprs));
  }

  private static Stream<MIR.E> children(MIR.E e) {
    return switch (e) {
      case MIR.CreateObj ignored -> Stream.empty();
      case MIR.X ignored -> Stream.empty();
      case MIR.MCall call -> Stream.concat(Stream.of(call.recv()), call.args().stream());
      case MIR.BoolExpr expr -> Stream.of(expr.original(), expr.condition());
      case MIR.Block block -> Stream.concat(Stream.of(block.original()), block.stmts().stream().map(MIR.Block.BlockStmt::e));
      case MIR.StaticCall call -> Stream.concat(Stream.of(call.original()), call.args().stream());
      case MIR.UpdatableListAsIdFnCall call -> Stream.of(call.e());
      case MIR.Box box -> Stream.of(box.inner());
    };
  }
}
