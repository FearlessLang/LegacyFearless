package program.typesystem;

import org.junit.jupiter.api.Test;

import static program.typesystem.RunTypeSystem.expectFail;
import static program.typesystem.RunTypeSystem.fail;
import static program.typesystem.RunTypeSystem.ok;

public class TestTransientSemantics {
  private static final String BASE = """
    package base
    Transient:{}
    HasIdentity:{}
    Void:{}
    """;

  @Test void transientReturnTypeRejected() {
    fail("""
      In position [###]/Dummy1.fear:4:4
      [E72 transientReturn]
      Transient values may not be returned from methods. The returned type was imm test.T[].
      """, BASE, """
      package test
      alias base.Transient as Transient,
      T: Transient{}
      A:{ .bad: T -> T }
      """);
  }

  @Test void transientActualAcceptedByExplicitTransientFormal() {
    ok(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      T: Transient{}
      Cb: Transient{ mut #(t: T): Void }
      A:{ #(cb: mut Cb, t: T): Void -> cb#t }
      """);
  }

  @Test void transientAcceptedAcrossMultipleOverloadAttempts() {
    // A `read` receiver method yields several multi-sig attempts during overload
    // resolution. Passing a transient argument to a transient-accepting formal must
    // still select an attempt now that ok() no longer re-checks transient legality
    // per attempt -- the batch check in selectResult is what enforces the rule.
    ok(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      T: Transient{}
      Cb:{ read #(t: T): Void }
      A:{ #(cb: read Cb, t: T): Void -> cb#t }
      """);
  }

  @Test void transientActualRejectedByGenericFormal() {
    fail("""
      In position [###]/Dummy1.fear:5:27
      [E73 transientArgument]
      A transient value of type imm test.T[] may only be passed to a type whose concrete type implements base.Transient. The formal type was X.
      """, BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      T: Transient{}
      Box[X]:{ #(x: X): Void -> Void }
      A:{ #(t: T): Void -> Box[T]#t }
      """);
  }

  @Test void indirectTransientReturnTypeRejected() {
    expectFail(BASE, """
      package test
      alias base.Transient as Transient,
      Tmp: Transient{}
      T: Tmp{}
      A:{ .bad: T -> T }
      """);
  }

  @Test void indirectTransientActualRejectedByGenericFormal() {
    expectFail(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      Tmp: Transient{}
      T: Tmp{}
      Box[X]:{ #(x: X): Void -> Void }
      A:{ #(t: T): Void -> Box[T]#t }
      """);
  }

  @Test void normalObjectCannotCaptureTransient() {
    fail("""
      In position [###]/Dummy1.fear:4:22
      [E74 transientCapture]
      The transient value 't' of type imm test.T[] cannot be captured by non-transient object literal imm test.Fear[###]$[].
      
      In position [###]/Dummy1.fear:5:6
      [E72 transientReturn]
      Transient values may not be returned from methods. The returned type was imm test.T[].
      """, BASE, """
      package test
      alias base.Transient as Transient,
      T: Transient{}
      Box:{ #(t: T): Any -> Any{ t } }
      Any:{ .get: T }
      """);
  }

  @Test void normalObjectCannotCaptureIndirectTransient() {
    expectFail(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      Tmp: Transient{}
      T: Tmp{}
      Helper:{ #(t: T): Void -> Void }
      Box:{ #(t: T): Any -> Any{ .use -> Helper#t } }
      Any:{ .use: Void }
      """);
  }

  @Test void transientObjectCanCaptureTransient() {
    ok(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      T: Transient{}
      Sink:{ mut #(a: mut Any): Void -> Void }
      Helper:{ #(t: T): Void -> Void }
      Box: Transient{ #(sink: mut Sink, t: T): Void -> sink#mut Any{ # -> Helper#t } }
      Any: Transient{ mut #: Void -> Void }
      """);
  }

  @Test void indirectTransientObjectCanCaptureTransient() {
    ok(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      Tmp: Transient{}
      T: Tmp{}
      Helper:{ #(t: T): Void -> Void }
      Box: Tmp{ #(t: T): Void -> mut Any{ # -> Helper#t }# }
      Any: Tmp{ mut #: Void -> Void }
      """);
  }

  @Test void transientWithIdentityRejected() {
    fail("""
      In position [###]/Dummy1.fear:3:0
      [E77 transientWithIdentity]
      The type test.T/0 may not implement both base.Transient and base.HasIdentity.
      """, BASE, """
      package test
      alias base.Transient as Transient, alias base.HasIdentity as HasIdentity,
      T: Transient, HasIdentity{}
      """);
  }

  @Test void indirectTransientWithIdentityRejected() {
    expectFail(BASE, """
      package test
      alias base.Transient as Transient, alias base.HasIdentity as HasIdentity,
      Tmp: Transient{}
      Ident: HasIdentity{}
      T: Tmp, Ident{}
      """);
  }

  @Test void callOnWrapperContainingTransientRejected() {
    fail("""
      In position [###]/Dummy1.fear:5:4
      [E72 transientReturn]
      Transient values may not be returned from methods. The returned type was imm test.T[].
      
      In position [###]/Dummy1.fear:3:14
      [E72 transientReturn]
      Transient values may not be returned from methods. The returned type was imm test.T[].
      """, BASE, """
      package test
      alias base.Transient as Transient,
      T: Transient{ .use: T -> this }
      Wrap[X]:{ .get: X }
      A:{ #(w: Wrap[T]): T -> w.get.use }
      """);
  }

  @Test void callOnWrapperContainingIndirectTransientRejected() {
    expectFail(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      Tmp: Transient{}
      T: Tmp{}
      Wrap[X]:{ .use: Void -> Void }
      A:{ #(w: Wrap[T]): Void -> w.use }
      """);
  }

  @Test void indirectTransientActualAcceptedByIndirectTransientFormal() {
    ok(BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      Tmp: Transient{}
      T: Tmp{}
      Cb: Tmp{ mut #(t: T): Void }
      A:{ #(cb: mut Cb, t: T): Void -> cb#t }
      """);
  }

  @Test void shouldNotBeAbleToSmuggleTransientThroughGenerics() {
    fail("""
      In position [###]/Dummy1.fear:6:31
      [E73 transientArgument]
      A transient value of type imm test.T[] may only be passed to a type whose concrete type implements base.Transient. The formal type was X.
      """, BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      T: Transient{}
      Inner:{ .take[X](x: X): Void -> Void }
      Consume[X]:{ #(x: X): Void -> Inner.take[X](x) }
      A:{ #(t: T): Void -> Consume[T]#t }
      """);
  }

  @Test void shouldNotBeAbleToSmuggleTransientThroughSubtyping() {
    fail("""
      In position [###]/Dummy1.fear:10:21
      [E73 transientArgument]
      A transient value of type imm test.Fear[###][] may only be passed to a type whose concrete type implements base.Transient. The formal type was imm test.ABMatch[X0/0$].
      """, BASE, """
      package test
      alias base.Transient as Transient, alias base.Void as Void,
      ABMatch[R]: {.a: R, .b: R}
      AB: { .match[R](m: ABMatch[R]): R, }
      ABs: {
        .a: AB -> {::a},
        .b: AB -> {::b},
        }
      Smuggle: {
        .bad: Void -> ABs.a.match(_SmuggleMatch[Void]{.a -> Void, .b -> Void}),
        }
      _SmuggleMatch[R]: ABMatch[R], Transient{}
      """);
  }
}
