package program.typesystem;

import failure.CompileError;
import id.Mdf;
import main.Main;
import org.junit.jupiter.api.Test;
import parser.Parser;
import program.TypeSystemFeatures;
import program.inference.InferBodies;
import wellFormedness.WellFormednessFullShortCircuitVisitor;
import wellFormedness.WellFormednessShortCircuitVisitor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestNarrowedGammaOverloads {
  private static final class ResolvedCalls extends ConcurrentHashMap<Long, TsT> {
    private final Map<Long, List<TsT>> writes = new ConcurrentHashMap<>();

    @Override public TsT put(Long callId, TsT resolved) {
      writes.computeIfAbsent(callId, _ -> new CopyOnWriteArrayList<>()).add(resolved);
      return super.put(callId, resolved);
    }

    List<TsT> writes(long callId) {
      return writes.get(callId);
    }
  }

  private static ResolvedCalls typeCheck(String source) {
    return typeCheck(source, new TypeSystemFeatures());
  }

  private static ResolvedCalls typeCheck(String source, TypeSystemFeatures features) {
    Main.resetAll();
    var program = Parser.parseAll(
      List.of(new Parser(Path.of("Dummy.fear"), source)), features
    );
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

  private static Map.Entry<Long, TsT> singleCall(ResolvedCalls resolvedCalls, String name) {
    var calls = resolvedCalls.entrySet().stream()
      .filter(call -> call.getValue().original().name().name().equals(name))
      .toList();
    assertEquals(1, calls.size());
    return calls.getFirst();
  }

  @Test void shouldReplaceFailedImmutableLiteralOverloadWhenReadLiteralSucceeds() {
    var resolvedCalls = typeCheck("""
      package test
      A:{}
      Source:{}
      Reader:{
        imm .pick: A -> {},
        read .pick: A -> {},
        read .first: A,
        read .last: read Source,
      }
      Main:{
        #(r: read Source): read Reader -> read Reader{ 'self
          .first -> self.pick,
          .last -> r,
        }
      }
      """);

    var pickCall = singleCall(resolvedCalls, ".pick");
    var writes = resolvedCalls.writes(pickCall.getKey());
    assertEquals(List.of(Mdf.imm, Mdf.read), writes.stream().map(call -> call.original().mdf()).toList());
    assertSame(writes.getLast(), pickCall.getValue());
  }

  @Test void shouldKeepSuccessfulIsoMethodPromotionCallRecords() {
    var resolvedCalls = typeCheck("""
      package test
      A:{}
      Source:{
        mut .pick: A -> {},
        read .pick: A -> {},
      }
      Builder:{ mut .build(_: A): mut A -> {} }
      Main:{
        .make(source: mut Source): iso A -> (mut Builder{}).build(source.pick)
      }
      """, new TypeSystemFeatures().literalPromotions(false));

    var pickCall = singleCall(resolvedCalls, ".pick");
    var writes = resolvedCalls.writes(pickCall.getKey());
    assertEquals(List.of(Mdf.mut, Mdf.mut), writes.stream().map(call -> call.original().mdf()).toList());
    assertEquals(List.of(Mdf.mut, Mdf.mutH), writes.stream().map(TsT::recv).toList());
    assertSame(writes.getLast(), pickCall.getValue());
    assertEquals(Mdf.imm, pickCall.getValue().t().mdf());
  }

  @Test void shouldReplaceFailedIsoMethodPromotionCallRecordsWhenImmPromotionSucceeds() {
    var resolvedCalls = typeCheck("""
      package test
      A:{
        mut .pick: mut A -> this,
        read .pick: read A -> this,
        read .get: read A -> this,
      }
      Main:{ .make: A -> ((mut A{}).pick).get }
      """, new TypeSystemFeatures().literalPromotions(false));

    var pickCall = singleCall(resolvedCalls, ".pick");
    var pickWrites = resolvedCalls.writes(pickCall.getKey());
    assertEquals(List.of(Mdf.mut, Mdf.mut, Mdf.mut, Mdf.mut), pickWrites.stream().map(call -> call.original().mdf()).toList());
    assertSame(pickWrites.getLast(), pickCall.getValue());
    var getCall = singleCall(resolvedCalls, ".get");
    var getWrites = resolvedCalls.writes(getCall.getKey());
    assertEquals(List.of(Mdf.read, Mdf.read), getWrites.stream().map(call -> call.original().mdf()).toList());
    assertSame(getWrites.getLast(), getCall.getValue());
    assertEquals(Mdf.read, getCall.getValue().t().mdf());
  }

  @Test void shouldRejectImmMethodPromotionWhenResultAliasesMutableArgument() {
    assertThrows(CompileError.class, () -> typeCheck("""
      package test
      A:{
        mut .pick: mut A -> this,
        read .pick: read A -> this,
        read .get: read A -> this,
      }
      Main:{ .make(a: mut A): A -> (a.pick).get }
      """));
  }
}
