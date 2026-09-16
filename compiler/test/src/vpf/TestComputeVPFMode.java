package vpf;

import id.Mdf;
import main.Main;
import org.junit.jupiter.api.Test;
import parser.Parser;
import program.TypeSystemFeatures;
import program.inference.InferBodies;
import program.typesystem.TsT;
import wellFormedness.WellFormednessFullShortCircuitVisitor;
import wellFormedness.WellFormednessShortCircuitVisitor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestComputeVPFMode {
  private static final class ResolvedCalls extends ConcurrentHashMap<Long, TsT> {
    private final Map<Long, TsT> reads = new ConcurrentHashMap<>();

    @Override public TsT get(Object callId) {
      var resolved = super.get(callId);
      if (callId instanceof Long id && resolved != null) {
        reads.put(id, resolved);
      }
      return resolved;
    }

    TsT read(long callId) {
      return reads.get(callId);
    }
  }

  private static ResolvedCalls typeCheck(String... sources) {
    assert sources.length > 0;
    Main.resetAll();
    var parsers = IntStream.range(0, sources.length)
      .mapToObj(index -> new Parser(Path.of("Dummy" + index + ".fear"), sources[index]))
      .toList();
    var program = Parser.parseAll(parsers, new TypeSystemFeatures());

    new WellFormednessFullShortCircuitVisitor().visitProgram(program).ifPresent(error -> {
      throw error;
    });
    var inferred = InferBodies.inferAll(program);
    new WellFormednessShortCircuitVisitor(inferred).visitProgram(inferred).ifPresent(error -> {
      throw error;
    });
    var resolvedCalls = new ResolvedCalls();
    inferred.typeCheck(resolvedCalls);
    return resolvedCalls;
  }

  private static List<Map.Entry<Long, TsT>> callsNamed(ResolvedCalls resolvedCalls, String name) {
    return resolvedCalls.entrySet().stream()
      .filter(call -> call.getValue().original().name().name().equals(name))
      .toList();
  }

  private static Map.Entry<Long, TsT> singleCall(ResolvedCalls resolvedCalls, String name) {
    var calls = callsNamed(resolvedCalls, name);
    assertEquals(1, calls.size());
    return calls.getFirst();
  }

  private static void assertResolvedMethodWasChecked(ResolvedCalls resolvedCalls, Map.Entry<Long, TsT> call) {
    assertSame(
      call.getValue(),
      resolvedCalls.read(call.getKey()),
      () -> "ComputeVPFMode did not check the resolved overload " + call.getValue().original()
    );
  }

  @Test void shouldUseResolvedOverloadsWhenCheckingUnderWeakenedGamma() {
    var resolvedCalls = typeCheck("""
      package test
      Main:{
        mut .pick: mut Main -> this,
        read .pick: read Main -> this,
        mut .get: mut Main -> this,
        read .get: read Main -> this,
        read .use(x: read Main): read Main -> this,
        mut .run: read Main -> (this.pick).use(this.get)
      }
      """);

    var useCall = singleCall(resolvedCalls, ".use");
    var pickCall = singleCall(resolvedCalls, ".pick");
    var getCall = singleCall(resolvedCalls, ".get");
    assertEquals(Mdf.mut, pickCall.getValue().original().mdf());
    assertEquals(Mdf.mut, getCall.getValue().original().mdf());
    assertResolvedMethodWasChecked(resolvedCalls, pickCall);
    assertResolvedMethodWasChecked(resolvedCalls, getCall);
    assertEquals(VPFCallMode.Sequential, useCall.getValue().vpfMode());
  }

  @Test void shouldUseResolvedOverloadEvenWhenBranchBAllowsParallelism() {
    var resolvedCalls = typeCheck("""
      package test
      Main:{
        mut .pick: mut Main -> this,
        read .pick: read Main -> this,
        read .pure: read Main -> this,
        read .use(x: read Main): read Main -> this,
        mut .run: read Main -> (this.pick).use(Main{}.pure)
      }
      """);

    var useCall = singleCall(resolvedCalls, ".use");
    var pickCall = singleCall(resolvedCalls, ".pick");
    assertEquals(Mdf.mut, pickCall.getValue().original().mdf());
    assertEquals(VPFCallMode.Parallel, useCall.getValue().vpfMode());
    assertResolvedMethodWasChecked(resolvedCalls, pickCall);
  }

  @Test void shouldPreserveResolvedOverloadsInLambdaMethodBodies() {
    var resolvedCalls = typeCheck("""
      package test
      Fn:{mut .call: read Main}
      Main:{
        mut .pick: mut Main -> this,
        read .pick: read Main -> this,
        read .get: read Main -> this,
        read .use(x: read Main): read Main -> this,
        mut .run(r: mut Main): read Main -> (mut Fn{.call -> r.pick}.call).use(r.get)
      }
      """);

    var pickCall = singleCall(resolvedCalls, ".pick");
    assertEquals(Mdf.mut, pickCall.getValue().original().mdf());
    assertEquals(VPFCallMode.Sequential, singleCall(resolvedCalls, ".use").getValue().vpfMode());
    assertResolvedMethodWasChecked(resolvedCalls, pickCall);
  }

  @Test void shouldParalleliseViaBranchAWhenAllCallsTypeCheckUnderWeakenedGamma() {
    var resolvedCalls = typeCheck("""
      package test
      Main:{
        read .pick: read Main -> this,
        read .get: read Main -> this,
        read .use(x: read Main): read Main -> this,
        read .run: read Main -> (this.pick).use(this.get)
      }
      """);

    assertEquals(Mdf.read, singleCall(resolvedCalls, ".pick").getValue().original().mdf());
    assertEquals(Mdf.read, singleCall(resolvedCalls, ".get").getValue().original().mdf());
    assertEquals(VPFCallMode.Parallel, singleCall(resolvedCalls, ".use").getValue().vpfMode());
  }

  @Test void shouldUseEachCallSitesResolvedOverloadWhenNestedCallsShareAMethodName() {
    var resolvedCalls = typeCheck("""
      package test
      Main:{
        read .pick: mut Main -> {},
        mut .pick: read Main -> this,
        read .get: read Main -> this,
        read .use(x: read Main): read Main -> this,
        read .run(r: read Main): read Main -> ((r.pick).pick).use(r.get)
      }
      """);

    var pickCalls = callsNamed(resolvedCalls, ".pick");
    assertEquals(2, pickCalls.size());
    assertNotEquals(pickCalls.getFirst().getKey(), pickCalls.getLast().getKey());
    var innerCall = pickCalls.stream()
      .filter(call -> call.getValue().original().mdf() == Mdf.read)
      .findFirst().orElseThrow();
    var outerCall = pickCalls.stream()
      .filter(call -> call.getValue().original().mdf() == Mdf.mut)
      .findFirst().orElseThrow();
    assertEquals(Mdf.mut, innerCall.getValue().original().sig().ret().mdf());
    assertEquals(Mdf.read, outerCall.getValue().original().sig().ret().mdf());
    assertResolvedMethodWasChecked(resolvedCalls, innerCall);
    assertResolvedMethodWasChecked(resolvedCalls, outerCall);
    assertEquals(VPFCallMode.Parallel, singleCall(resolvedCalls, ".use").getValue().vpfMode());
  }

  @Test void shouldParalleliseViaBranchBWhenOnlyOneCallUsesAnImpureBinding() {
    var resolvedCalls = typeCheck("""
      package test
      Main:{
        mut .pick: mut Main -> this,
        read .pure: read Main -> this,
        read .use(x: read Main): read Main -> this,
        mut .run: read Main -> (this.pick).use(Main{}.pure)
      }
      """);

    assertEquals(Mdf.mut, singleCall(resolvedCalls, ".pick").getValue().original().mdf());
    assertEquals(VPFCallMode.Parallel, singleCall(resolvedCalls, ".use").getValue().vpfMode());
  }

  @Test void shouldRemainSequentialWhenNeitherBranchAllowsParallelism() {
    var resolvedCalls = typeCheck("""
      package test
      Main:{
        mut .pick: mut Main -> this,
        mut .get: mut Main -> this,
        read .use(x: read Main): read Main -> this,
        mut .run: read Main -> (this.pick).use(this.get)
      }
      """);

    assertEquals(Mdf.mut, singleCall(resolvedCalls, ".pick").getValue().original().mdf());
    assertEquals(Mdf.mut, singleCall(resolvedCalls, ".get").getValue().original().mdf());
    assertEquals(VPFCallMode.Sequential, singleCall(resolvedCalls, ".use").getValue().vpfMode());
  }
}
