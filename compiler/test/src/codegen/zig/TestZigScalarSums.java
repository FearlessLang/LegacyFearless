package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static codegen.zig.RunZigProgramTests.okBaseNoVpf;
import static utils.RunOutput.Res;

/// E2E programs for tag and niche sums. Generated and virtual calls must agree on the ABI.
public class TestZigScalarSums {
  private static final String SUBTYPE_SUM = """
    package test
    Root:Sealed{ .str: Str }
    Sub:Root{}
    A:Root{ .str -> "a" }
    B:Sub{ .str -> "b" }
    C:Sub{ .str -> "c" }
    Narrow:{ #: Sub -> C }
    Widen:{ #: Root -> Narrow# }
    Pass:{ #(r: Root): Str -> r.str }
    """;

  @Test void aSumReturnPreservesItsVariantWhenWidened() {
    okBase(new Res("c", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Widen#.str)}
      """, Base.mutBaseAliases, SUBTYPE_SUM);
  }

  @Test void aSumArgumentPreservesItsVariantWhenWidened() {
    okBase(new Res("c", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Pass#(Narrow#))}
      """, Base.mutBaseAliases, SUBTYPE_SUM);
  }

  @Test void aVpfSumReturnPreservesItsVariantWhenWidened() {
    okBase(new Res("c", "", 0), """
      package test
      Pair:{ #(x: Sub, y: Sub): Sub -> y }
      VpfWiden:{ #: Root -> Pair#(Narrow#, Narrow#) }
      Test:Main{sys -> sys.io.println(VpfWiden#.str)}
      """, Base.mutBaseAliases, SUBTYPE_SUM);
  }

  @Test void aDeInlinedSumArmPreservesItsVariantWhenWidened() {
    okBase(new Res("c", "", 0), """
      package test
      Pair:{ #(x: Sub, y: Sub): Sub -> y }
      ConditionalWiden:{ #(flag: Bool): Root -> flag.if[Sub]{
        .then -> Pair#(Narrow#, Narrow#),
        .else -> Pair#(Narrow#, Narrow#),
        } }
      Test:Main{sys -> sys.io.println(ConditionalWiden#(True).str)}
      """, Base.mutBaseAliases, SUBTYPE_SUM);
  }

  @Test void aCovariantDeInlinedSumArmPreservesItsVariantWhenWidened() {
    okBase(new Res("c", "", 0), """
      package test
      Pair:{ #(x: Sub, y: Sub): Sub -> y }
      CovariantWiden:{ #(flag: Bool): Root -> flag.if[Root](
        mut Matcher:base.ThenElse[Root]{
          mut .then: Sub -> Pair#(Narrow#, Narrow#),
          mut .else: Sub -> Narrow#,
          }) }
      Test:Main{sys -> sys.io.println(CovariantWiden#(True).str)}
      """, Base.mutBaseAliases, SUBTYPE_SUM);
  }

  @Test void aCovariantDeInlinedSumArgumentPreservesItsVariantWhenWidened() {
    okBase(new Res("c", "", 0), """
      package test
      Pair:{ #(x: Sub, y: Sub): Sub -> y }
      CovariantUse:{ #(flag: Bool): Str -> Pass#(
        flag.if[Root](mut Matcher:base.ThenElse[Root]{
          mut .then: Sub -> Pair#(Narrow#, Narrow#),
          mut .else: Sub -> Narrow#,
          })) }
      Test:Main{sys -> sys.io.println(CovariantUse#(True))}
      """, Base.mutBaseAliases, SUBTYPE_SUM);
  }

  private static final String SUM = """
    package test
    Choice:Sealed{
      .next: Choice,
      .pick(x: Choice): Choice,
      .match[R:*](m: mut ChoiceMatch[R]): R,
      .str: Str,
      }
    ChoiceMatch[R:*]:{ mut .a: R, mut .b: R, mut .c: R }
    A:Choice{ .next -> B, .pick(x) -> x, .match(m) -> m.a, .str -> "a" }
    B:Choice{ .next -> C, .pick(x) -> this, .match(m) -> m.b, .str -> "b" }
    C:Choice{ .next -> A, .pick(x) -> A, .match(m) -> m.c, .str -> "c" }
    Id:{ #[T:*](x: T): T -> x }
    ChoiceStrings:{ #: mut ChoiceMatch[Str] -> {
      .a -> "wrong", .b -> "wrong", .c -> A.pick(C).str,
      }}
    """;

  @Test void recursiveSumRoundTripsThroughDirectCalls() {
    okBase(new Res("c", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(A.next.pick(C).next.str)}
      """, Base.mutBaseAliases, SUM);
  }

  @Test void semanticBoundariesAndMatchKeepVariants() {
    okBase(new Res("b\nc\nc", "", 0), """
      package test
      Test:Main{sys -> Block#
        .let[Choice] x = {Id#[Choice](B.next)}
        .do{sys.io.println(A.next.str)}
        .do{sys.io.println(x.str)}
        .return{sys.io.println(C.match[Str](ChoiceStrings#))}
        }
      """, Base.mutBaseAliases, SUM);
  }

  @Test void cachedBooleanChainSurvivesWrapperAbi() {
    // Chained `.not` avoids the branch rewrites, so the cached base wrappers and these call
    // sites must agree on the result shape.
    okBase(new Res("True", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(True.not.not.not.not.str)}
      """, Base.mutBaseAliases);
  }

  @Test void vpfJoinOfScalarSumsIsCorrectForSmallAndLargeRanges() {
    okBase(16, new Res("c\nb", "", 0), """
      package test
      Test:Main{sys -> Block#
        .do{sys.io.println(Pick#(0, 2).str)}
        .return{sys.io.println(Pick#(0, 128).str)}}
      Pick:{
        #(lo: Nat, hi: Nat): Choice -> (hi - lo) == 1 ? {
          .then -> lo == 0 ? { .then -> A, .else -> lo == 1 ? { .then -> C, .else -> B } },
          .else -> this#(lo, (lo + hi) / 2).pick(this#((lo + hi) / 2, hi))
          }
        }
      """, Base.mutBaseAliases, SUM);
  }

  @Test void aStoredConditionalConvertsANativeArmResultToAnObject() {
    // `Vars#` stores its argument, so the result of the conditional must be an object. The
    // receiver `A.next` and the argument `B.next` in the `.else` arm each add a frame. So the arm
    // becomes a separate function that returns a native tag, and the tag converts to an object
    // before the store.
    okBase(new Res("a\nb", "", 0), """
      package test
      Choice:Sealed{ .next: Choice, .pick(c: Choice): Choice, .str: Str }
      A:Choice{ .next -> B, .pick(c) -> c, .str -> "a" }
      B:Choice{ .next -> A, .pick(c) -> this, .str -> "b" }
      Probe:{ #(n: Nat): mut Var[Choice] -> Vars#[Choice]((n == 0) ?[Choice] {
        .then -> A,
        .else -> A.next.pick(B.next)}) }
      Test:Main{sys -> Block#
        .do{sys.io.println(Probe#(0).get.str)}
        .return{sys.io.println(Probe#(1).get.str)}}
      """, Base.mutBaseAliases);
  }

  @Test void aWidenedReturnConvertsEachVpfReturnPathToAnObject() {
    // `Outer` is open, so it is not a sum and `Widen` returns an object. The body has the type
    // `Choice`, which is a native tag. The receiver `A.next` and the argument `B.next` each add a
    // frame, so VPF instruments `Widen`. Each return path of `Widen` converts the tag to an object.
    okBase(new Res("a", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Widen#(1).str)}
      Outer:{ .str: Str }
      Choice:Outer,Sealed{ .next: Choice, .pick(x: Choice): Choice }
      A:Choice{ .next -> B, .pick(x) -> x, .str -> "a" }
      B:Choice{ .next -> A, .pick(x) -> x, .str -> "b" }
      Widen:{ #(n: Nat): Outer -> A.next.pick(B.next) }
      """, Base.mutBaseAliases);
  }

  @Test void aNativeSumCapturedByAnOperandLiteralResolvesInTheThief() {
    // `{.on -> b}` captures `b`, which `Join` holds as a native tag. A capture block holds boxed
    // fields, so the tag converts at the capture site. The operands `"a" + "b"` and `"c" + "d"`
    // each add a frame, so VPF emits a thief function for `Join`. A thief reads the variables of
    // `Join` from a locals struct, so the captured `b` must resolve there too.
    okBase(new Res("abcd", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Join#(True))}
      Flag:{ .on: Bool }
      Combine:{ #(x: Str, y: Str, f: Flag): Str -> f.on ? { .then -> x + y, .else -> "off" } }
      Join:{ #(b: Bool): Str -> Combine#("a" + "b", "c" + "d", {.on -> b}) }
      """, Base.mutBaseAliases);
  }

  @Test void aNicheLiteralCapturedByAnOperandResolvesInTheThief() {
    // `{.get -> s}` is the only variant of `Choice` that holds a value, so `Combine` takes it as a
    // native niche value. The literal captures `s`, which `Join` holds as a parameter. The operands
    // `"a" + "b"` and `"c" + "d"` each add a frame, so VPF emits a thief function for `Join`. A thief
    // reads the parameters of `Join` from a locals struct, so the captured `s` must resolve there
    // when the literal becomes a native value at the call of `Combine`.
    okBase(new Res("abcde", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Join#("e"))}
      Choice:Sealed{ .get: Str }
      Empty:Choice{ .get -> "empty" }
      Combine:{ #(x: Str, y: Str, c: Choice): Str -> x + y + (c.get) }
      Join:{ #(s: Str): Str -> Combine#("a" + "b", "c" + "d", {.get -> s}) }
      """, Base.mutBaseAliases);
  }

  @Test void aNicheLiteralPassedToADirectCallInAnOperandResolvesInTheThief() {
    // `{.get -> s}` is the only variant of `Choice` that holds a value, so `Unwrap` takes it as a
    // native niche value. The operands of `Pair#` are two calls, so VPF emits a thief function for
    // `Join`. The first operand runs in `Join`. The thief runs the second operand, which is the
    // direct call of `Unwrap` because its receiver is a literal. A thief reads the parameters of
    // `Join` from a locals struct, so the captured `s` must resolve there when the literal becomes
    // a native value at the call of `Unwrap`.
    okBase(new Res("abe", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Join#("e"))}
      Choice:Sealed{ .get: Str }
      Empty:Choice{ .get -> "empty" }
      Unwrap:{ #(c: Choice): Str -> c.get }
      Pair:{ #(x: Str, y: Str): Str -> x + y }
      Join:{ #(s: Str): Str -> Pair#("a" + "b", Unwrap#({.get -> s})) }
      """, Base.mutBaseAliases);
  }

  @Test void aNicheLiteralPassedToAGuardedCallInAnOperandResolvesInTheThief() {
    // As above, but the second operand is a guarded call. `Sink` is sealed and has two
    // implementations, so the call of `.take` tests the receiver and then calls one of them
    // directly. The literal becomes a native value in each of the two direct calls. The thief must
    // resolve the captured `s` in both.
    okBase(new Res("abe", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Join#(SinkA, "e"))}
      Choice:Sealed{ .get: Str }
      Empty:Choice{ .get -> "empty" }
      Sink:Sealed{ .take(c: Choice): Str }
      SinkA:Sink{ .take(c) -> c.get }
      SinkB:Sink{ .take(c) -> "b" }
      Pair:{ #(x: Str, y: Str): Str -> x + y }
      Join:{ #(k: Sink, s: Str): Str -> Pair#("a" + "b", k.take({.get -> s})) }
      """, Base.mutBaseAliases);
  }

  @Test void aNicheLiteralPassedToADirectCallNeedsNoTemporaryObject() {
    // `{.get -> s}` is the only variant of `Choice` that holds a value, so `Unwrap` takes it as a
    // native niche value. That value is the captured string `s` itself. The receiver of `Unwrap#`
    // is a literal, so the call is a direct call. `Join` has one call only, so VPF does not apply
    // and the generated code has a single path. The call must pass `s` and must not leave behind
    // a temporary object for the literal that nothing reads.
    okBaseNoVpf(new Res("e", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Join#("e"))}
      Choice:Sealed{ .get: Str }
      Empty:Choice{ .get -> "empty" }
      Unwrap:{ #(c: Choice): Str -> c.get }
      Join:{ #(s: Str): Str -> Unwrap#({.get -> s}) }
      """, Base.mutBaseAliases);
  }

  @Test void aReturnedParameterComesBackOwned() {
    // `Id#` returns its parameter, so the result must hold its own reference. The value must be
    // a heap Str built at run time, because literals and singletons have no reference count.
    okBase(new Res("abc", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Id#[Str]("a" + "b") + "c")}
      """, Base.mutBaseAliases, SUM);
  }

  @Test void aReturnedParameterSurvivesAMatcherArm() {
    // As above, but the arm `.b -> s` returns a parameter of the enclosing literal.
    okBase(new Res("ab", "", 0), """
      package test
      Echo:{ #(s: Str): Str -> B.match[Str]({ .a -> "wrong", .b -> s, .c -> "wrong" }) }
      Test:Main{sys -> sys.io.println(Echo#("a" + "b"))}
      """, Base.mutBaseAliases, SUM);
  }

  @Test void aCapturedVariantSurvivesBoxing() {
    // The capture block holds every field boxed, so the tag must convert at the capture site.
    okBase(new Res("True\nfalse", "", 0), """
      package test
      Flags:{ #(b: Bool, s: Str): Flag -> {.on -> b, .tag -> s} }
      Flag:{ .on: Bool, .tag: Str }
      Test:Main{sys -> Block#
        .let[Flag] f = {Flags#(True, "f" + "alse")}
        .do{sys.io.println(f.on.str)}
        .return{sys.io.println(f.tag)}}
      """, Base.mutBaseAliases);
  }

  @Test void aNativeSumParameterConvertsAtACallThatBuildsInTheCallerSlot() {
    // `Use` holds `b` as a native tag. The object that `Flags#` returns does not leave `Use`, so
    // the call builds it in a stack slot of `Use`. The slot form of the callee takes each
    // parameter boxed, so the tag converts to an object at this call.
    okBase(new Res("True", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Use#(True, "f" + "alse").str)}
      Flags:{ #(b: Bool, s: Str): Flag -> {.on -> b, .tag -> s} }
      Flag:{ .on: Bool, .tag: Str }
      Use:{ #(b: Bool, s: Str): Bool -> Flags#(b, s).on }
      """, Base.mutBaseAliases);
  }

  /// `None` is a local `{}`, because a package outside `base` writes the empty variant so.
  private static final String OPTS = """
    package test
    None:{ #[T:*]: mut Opt[T] -> {} }
    NatOpt:{ #(n: Nat): mut Opt[Nat] -> n == 0 ? { .then -> None#[Nat], .else -> Opts#n } }
    StrOpt:{ #(s: Str): mut Opt[Str] -> s.isEmpty ? { .then -> None#[Str], .else -> Opts#s } }
    Show:{ #(o: mut Opt[Str]): Str -> o.match[Str]{ .some(x) -> x, .empty -> "none" } }
    """;

  @Test void anOptReturnedFromAMethodAndMatched() {
    okBase(new Res("none\nab", "", 0), """
      package test
      Test:Main{sys -> Block#
        .do{sys.io.println(Show#(StrOpt#("")))}
        .return{sys.io.println(Show#(StrOpt#("a" + "b")))}}
      """, Base.mutBaseAliases, OPTS);
  }

  @Test void anOptStoredInACaptureReadsBack() {
    // The capture block holds every field boxed, so the niche builds its container here. The
    // payload is a heap `Str`, so a missed share or drop causes a use after free or a leak.
    okBase(new Res("ab\nnone", "", 0), """
      package test
      Holder:{ mut .get: mut Opt[Str] }
      Hold:{ #(o: mut Opt[Str]): mut Holder -> { .get -> o } }
      Test:Main{sys -> Block#
        .let[mut Holder] some = {Hold#(StrOpt#("a" + "b"))}
        .let[mut Holder] none = {Hold#(StrOpt#(""))}
        .do{sys.io.println(Show#(some.get))}
        .return{sys.io.println(Show#(none.get))}}
      """, Base.mutBaseAliases, OPTS);
  }

  @Test void aNestedOptKeepsSomeOfNoneApart() {
    // The niche payload is always boxed, so the inner `Opt` is an object and not the sentinel.
    okBase(new Res("none\nsome(none)\nsome(ab)", "", 0), """
      package test
      Nest:{
        #(s: Str): mut Opt[mut Opt[Str]] -> s.isEmpty ?
          { .then -> None#[mut Opt[Str]], .else -> Opts#[mut Opt[Str]](StrOpt#(s)) },
        }
      Flat:{
        #(o: mut Opt[mut Opt[Str]]): Str ->
          o.match[Str]{ .some(inner) -> "some(" + (Show#(inner)) + ")", .empty -> "none" },
        }
      Test:Main{sys -> Block#
        .do{sys.io.println(Flat#(Nest#("")))}
        .do{sys.io.println(Flat#(Opts#[mut Opt[Str]](None#[Str])))}
        .return{sys.io.println(Flat#(Nest#("a" + "b")))}}
      """, Base.mutBaseAliases, OPTS);
  }

  @Test void anOptOfAPrimitivePayloadRoundTrips() {
    // A `Nat` is inline in the `FatPtr`, so the payload has no reference count.
    okBase(new Res("none\n7\n12", "", 0), """
      package test
      Total:{ #(o: mut Opt[Nat], n: Nat): Nat -> o.match[Nat]{ .some(x) -> x + n, .empty -> n } }
      Test:Main{sys -> Block#
        .do{sys.io.println(NatOpt#(0).match[Str]{ .some(x) -> x.str, .empty -> "none" })}
        .do{sys.io.println(NatOpt#(7).match[Str]{ .some(x) -> x.str, .empty -> "none" })}
        .return{sys.io.println(Total#(NatOpt#(5), Total#(NatOpt#(0), 7)).str)}}
      """, Base.mutBaseAliases, OPTS);
  }

  @Test void anOptCrossesAVirtualCall() {
    // Three implementations keep the call virtual.
    okBase(new Res("none\nab\ncd", "", 0), """
      package test
      Choice:Sealed{ .pick: mut Opt[Str] }
      Empty:Choice{ .pick -> None#[Str] }
      Filled:Choice{ .pick -> Opts#("a" + "b") }
      Other:Choice{ .pick -> Opts#("c" + "d") }
      Ask:{ #(c: Choice): Str -> c.pick.match[Str]{ .some(x) -> x, .empty -> "none" } }
      Test:Main{sys -> Block#
        .do{sys.io.println(Ask#(Empty).str)}
        .do{sys.io.println(Ask#(Filled).str)}
        .return{sys.io.println(Ask#(Other).str)}}
      """, Base.mutBaseAliases, OPTS);
  }

  @Test void ineligibleSumsStillRunCorrectly() {
    okBase(new Res("1\n1\nok", "", 0), """
      package test
      Open:{ .id: Open }
      OpenA:Open{ .id -> this }
      Captured:Sealed{
        .id: Captured -> this, .n: Nat -> 0,
        #(x: Nat): Captured -> { .id -> this, .n -> x },
        }
      CapturedA:Captured{ .id -> this, .n -> 0 }
      Payload:Sealed{
        .value: Nat -> 0,
        #(x: Nat): Payload -> { .value -> x },
        }
      Test:Main{sys -> Block#
        .do{sys.io.println(Captured#(1).n.str)}
        .do{sys.io.println(Payload#(1).value.str)}
        .return{sys.io.println("ok")}}
      """, Base.mutBaseAliases);
  }
}
