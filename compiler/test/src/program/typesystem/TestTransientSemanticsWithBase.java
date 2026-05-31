package program.typesystem;

import failure.CompileError;
import main.CompilerFrontEnd;
import main.InputOutput;
import main.Main;
import main.java.LogicMainJava;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import utils.Err;

import java.util.Arrays;

/**
 * Transient-semantics tests that run against the real {@code assets/base} library
 * (autoloaded by {@link InputOutput#programmaticAuto}). Unlike {@link TestTransientSemantics},
 * which hand-writes a minimal BASE, these exercise the base-loaded magic containers
 * ({@code Var}/{@code Vars}, {@code List}) that {@code isStorageLikeCall} guards against.
 */
public class TestTransientSemanticsWithBase {
  void ok(String... content){
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var logicMain = LogicMainJava.of(InputOutput.programmaticAuto(Arrays.asList(content)), verbosity);
    logicMain.check();
  }
  void fail(String expectedErr, String... content){
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var logicMain = LogicMainJava.of(InputOutput.programmaticAuto(Arrays.asList(content)), verbosity);
    try {
      logicMain.check();
      Assertions.fail("Did not fail!\n");
    } catch (CompileError e) {
      Err.strCmp(expectedErr, e.toString());
    }
  }
  void expectFail(String... content){
    Main.resetAll();
    var verbosity = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var logicMain = LogicMainJava.of(InputOutput.programmaticAuto(Arrays.asList(content)), verbosity);
    try {
      logicMain.check();
      Assertions.fail("Did not fail!\n");
    } catch (CompileError ignored) { }
  }

  @Test void transientStoredInVarRejected() {
    fail("""
      In position [###]/Dummy0.fear:4:25
      [E76 transientStorage]
      Transient value imm test.T[] cannot be stored by call base.Vars[], #/1[X$1](imm test.T[]): mut base.Var[imm test.T[]].
      """, """
      package test
      alias base.Transient as Transient, alias base.Vars as Vars, alias base.Void as Void,
      T: Transient{}
      A:{ #(t: T): Void -> Vars#t }
      """);
  }

  @Test void indirectTransientStoredInVarRejected() {
    expectFail("""
      package test
      alias base.Transient as Transient, alias base.Vars as Vars, alias base.Void as Void,
      Tmp: Transient{}
      T: Tmp{}
      A:{ #(t: T): Void -> Vars#t }
      """);
  }

  @Test void transientAddedToListRejected() {
    fail("""
      In position [###]/Dummy0.fear:4:25
      [E76 transientStorage]
      Transient value imm test.T[] cannot be stored by call base.List[], #/1[E$1](imm test.T[]): mut base.List[imm test.T[]].
      """, """
      package test
      alias base.Transient as Transient, alias base.List as List, alias base.Void as Void,
      T: Transient{}
      A:{ #(t: T): Void -> List#(t) }
      """);
  }

  @Test void indirectTransientAddedToListRejected() {
    expectFail("""
      package test
      alias base.Transient as Transient, alias base.List as List, alias base.Void as Void,
      Tmp: Transient{}
      T: Tmp{}
      A:{ #(t: T): Void -> List#(t) }
      """);
  }

  @Test void transientPassedToTransientParameterAccepted() {
    ok("""
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      T: Transient{}
      Cb: Transient{ mut #(t: T): Void }
      A:{ #(cb: mut Cb, t: T): Void -> cb#t }
      """);
  }

  @Test void stdlibTransientMatchersCanCaptureTransientValues() {
    ok("""
      package test
      alias base.Transient as Transient, alias base.Void as Void, alias base.True as True,
      alias base.Opts as Opts, alias base.LList as LList, alias base.Actions as Actions,
      T: Transient{}
      Sink: { #(t: T): Void -> Void }
      A:{ #(t: T): Void -> base.Block#
        .do { True.match[Void]{ .true -> Sink#t, .false -> Void } }
        .do { (Opts#Void).match[Void]{ .some(_) -> Sink#t, .empty -> Void } }
        .do { LList#[Void].match[Void]{ .elem(_, _) -> Sink#t, .empty -> Void } }
        .return { Actions.ok[Void](Void).run[Void]{ .ok(_) -> Sink#t, .info(_) -> Void } }
        }
      """);
  }

  @Test void infoVisitorRecursesWithoutCapturingTransientReceiver() {
    ok("""
      package test
      alias base.Infos as Infos, alias base.List as List, alias base.Str as Str,
      A:{ #: Str -> Infos.list(List#(Infos.msg "nested")).str }
      """);
  }
}
