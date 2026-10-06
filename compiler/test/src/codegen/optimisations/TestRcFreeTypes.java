package codegen.optimisations;

import codegen.MIR;
import codegen.MIRInjectionVisitor;
import id.Id;
import main.Main;
import main.java.ImplInfo;
import org.junit.jupiter.api.Test;
import parser.Parser;
import program.TypeSystemFeatures;
import program.inference.InferBodies;
import program.typesystem.TsT;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestRcFreeTypes {
  private static final Id.DecId OPEN = new Id.DecId("cached.Open", 0);
  private static final Id.DecId SINGLETON = new Id.DecId("cached.Singleton", 0);

  @Test void cachedSingletonsAndCurrentCapturesRequireDynamicReferences() {
    var mir = program("v");
    var cached = new ImplInfo(Map.of(
      OPEN, entry(false, Set.of(SINGLETON), null),
      SINGLETON, entry(true, Set.of(SINGLETON), List.of())));

    assertEquals(RcFreeTypes.Strategy.DYNAMIC, analysis(mir, cached).strategy(OPEN));
  }

  @Test void cachedCapturesAndCurrentSingletonsRequireDynamicReferences() {
    var mir = program("cached.Value");
    var capturing = new Id.DecId("cached.Capturing", 0);
    var cached = new ImplInfo(Map.of(
      OPEN, entry(false, Set.of(capturing), null),
      capturing, entry(false, Set.of(capturing), List.of("v"))));

    assertEquals(RcFreeTypes.Strategy.DYNAMIC, analysis(mir, cached).strategy(OPEN));
  }

  @Test void missingCachedImplementationMetadataKeepsReferencesDynamic() {
    var mir = program("cached.Value");

    assertEquals(RcFreeTypes.Strategy.DYNAMIC, analysis(mir, ImplInfo.EMPTY).strategy(OPEN));
  }

  @Test void cachedCaptureMetadataExcludesErasedSingletonPlaceholders() {
    var mir = programWithMaker("#(): cached.Open -> cached.Singleton");
    var capturing = new Id.DecId("cached.Capturing", 0);
    var cached = new ImplInfo(Map.of(
      OPEN, entry(false, Set.of(capturing), null),
      capturing, entry(false, Set.of(capturing), List.of("v"))));

    assertEquals(RcFreeTypes.Strategy.HEAP_OR_TRANSIENT, analysis(mir, cached).strategy(OPEN));
  }

  private static ImplInfo.Entry entry(
      boolean singleton, Set<Id.DecId> impls, List<String> captures) {
    return ImplInfo.Entry.of(false, false, singleton, false, impls, List.of(), captures);
  }

  private static RcFreeTypes analysis(MIR.Program mir, ImplInfo cached) {
    return new RcFreeTypes(new RapidTypeAnalysis(mir), mir.p(), Set.of("cached"), cached);
  }

  private static MIR.Program program(String value) {
    return programWithMaker("#(v: cached.Value): cached.Open -> { .get -> %s }".formatted(value));
  }

  private static MIR.Program programWithMaker(String method) {
    Main.resetAll();
    var full = Parser.parseAll(List.of(
      new Parser(Path.of("cached.fear"), """
        package cached
        Value:{}
        Open:{ .get: Value }
        Singleton:Open{ .get -> Value }
        """),
      new Parser(Path.of("app.fear"), """
        package app
        Maker:{ %s }
        """.formatted(method))), new TypeSystemFeatures());
    var inferred = InferBodies.inferAll(full);
    var calls = new ConcurrentHashMap<Long, TsT>();
    inferred.typeCheck(calls);
    return new MIRInjectionVisitor(List.of(), inferred, calls).visitProgram();
  }
}
