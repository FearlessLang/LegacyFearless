package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import java.util.regex.Pattern;

import static codegen.zig.RunZigProgramTests.okBase;
import static codegen.zig.RunZigProgramTests.okBaseNoVpf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.RunOutput.Res;

/// VPF programs at chosen promotion rates. The first argument of `okBase` is the token threshold:
/// a low value forces promotion and `Integer.MAX_VALUE` prevents it.
public class TestZigVPF {
  @Test void vpfSumWithoutTheThrowingLeafUnderForcedPromotion() { okBase(16, new Res("8001", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 127).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  @Test void vpfErrorCaughtUnderForcedPromotion() { okBase(16, new Res("boom", "", 0), """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{RSum#(0, 128)}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  @Test void vpfErrorUncaughtUnderForcedPromotion() { okBase(16, new Res("", "Program crashed with: boom[###]", 1), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  // `BoolIfOptimisation` inlines one level only, so the VPF call is in an arm function. VPF must
  // instrument that arm.
  @Test void vpfNestedIf() { okBase(16, new Res("8128", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> lo >= hi ? {
        .then -> 0,
        .else -> (hi - lo) == 1 ? {
          .then -> lo,
          .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
          }
        }
      }
    """, Base.mutBaseAliases); assertInstrumented(); }

  @Test void vpfCallInThenBranch() { okBase(16, new Res("8128", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) > 1 ? {
        .then -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi)),
        .else -> lo
        }
      }
    """, Base.mutBaseAliases); assertInstrumented(); }

  @Test void vpfFourNestedIfsWithNonParallelisableLayersBetween() { okBase(16, new Res("8128", "", 0), """
    package test
    Test:Main {sys -> sys.io.println(RSum#(0, 128).str)}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> lo >= hi ? {
        .then -> 0,
        .else -> (hi - lo) == 1 ? {
          .then -> lo,
          .else -> hi > 100000 ? {
            .then -> 0,
            .else -> lo > 100000 ? {
              .then -> 0,
              .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
              }
            }
          }
        }
      }
    """, Base.mutBaseAliases); assertInstrumented(); }

  // An arm frame is between the stolen branch and the `Try`. The unwind must go through it.
  @Test void vpfNestedIfErrorCaught() { okBase(16, new Res("boom", "", 0), """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{RSum#(0, 128)}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> lo >= hi ? {
        .then -> 0,
        .else -> (hi - lo) == 1 ? {
          .then -> lo == 127 ? { .then -> Error.msg[Nat] "boom", .else -> lo },
          .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
          }
        }
      }
    """, Base.mutBaseAliases); }

  // The leftmost failure wins. Leaves 3 and 100 throw in different subtrees.
  @Test void vpfLeftmostErrorWins() { okBase(16, new Res("left", "", 0), """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{RSum#(0, 128)}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    RSum: {
      #(lo: Nat, hi: Nat): Nat -> (hi - lo) == 1 ? {
        .then -> lo == 3 ? {
          .then -> Error.msg[Nat] "left",
          .else -> lo == 100 ? { .then -> Error.msg[Nat] "right", .else -> lo }
          },
        .else -> this#(lo, (lo + hi) / 2) + (this#((lo + hi) / 2, hi))
        }
      }
    """, Base.mutBaseAliases); }

  // The leftmost failure wins when `SumMatchOptimisation` rewrites the receiver. Receiver and argument throw.
  private static final String SUM_MATCH_BOTH_THROW = """
    package test
    Test:Main{sys -> sys.io.println(Try#[Nat]{Both#}.run{
      .ok(n) -> n.str,
      .info(err) -> err.msg,
      })}
    Choice:Sealed{ .match[R:*](m: mut ChoiceMatch[R]): R }
    ChoiceMatch[R:*]:{ mut .a: R, mut .b: R }
    A:Choice{ .match(m) -> m.a }
    B:Choice{ .match(m) -> m.b }
    Boom:{ #: Nat -> Error.msg[Nat] "right" }
    Both:{ #: Nat -> A.match[Nat]{ .a -> Error.msg[Nat] "left", .b -> 0 } + (Boom#) }
    """;

  @Test void vpfSumMatchReceiverKeepsLeftmostError() {
    okBase(16, new Res("left", "", 0), SUM_MATCH_BOTH_THROW, Base.mutBaseAliases);
  }

  @Test void sumMatchReceiverKeepsLeftmostErrorWithoutVpf() {
    okBaseNoVpf(new Res("left", "", 0), SUM_MATCH_BOTH_THROW, Base.mutBaseAliases);
  }

  @Test void vpfSumMatchArgumentLosesToReceiverError() {
    okBase(16, new Res("left", "", 0), """
      package test
      Test:Main{sys -> sys.io.println(Try#[Nat]{Both#}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        })}
      Choice:Sealed{ .match[R:*](m: mut ChoiceMatch[R]): R }
      ChoiceMatch[R:*]:{ mut .a: R, mut .b: R }
      A:Choice{ .match(m) -> m.a }
      B:Choice{ .match(m) -> m.b }
      Boom:{ #: Nat -> Error.msg[Nat] "left" }
      Both:{ #: Nat -> Boom# + (A.match[Nat]{ .a -> Error.msg[Nat] "right", .b -> 0 }) }
      """, Base.mutBaseAliases);
    assertMatchArmInThief();
  }

  private static final String BOOL_ARM_CAPTURED_NAT = """
    package test
    Test:Main {sys -> sys.io.println(Searches#(Vars#[Nat]0).crossRoad(1, Roads#(2, 3, True)).str)}
    Road: { read .to: Nat, read .cost: Nat, read .open: Bool }
    Roads: { #(to: Nat, cost: Nat, open: Bool): Road -> {.to -> to, .cost -> cost, .open -> open} }
    Searches: { #(value: mut Var[Nat]): mut Search -> {.value -> value} }
    Search: {
      mut .value: mut Var[Nat],
      mut .crossRoad(node: Nat, road: read Road): Nat -> road.open.if[Nat]{
        .then -> this.relax(node, road.to, road.cost),
        .else -> node,
        },
      mut .relax(node: Nat, to: Nat, cost: Nat): Nat ->
        Block#(this.value.set((node * 100) + (to * 10) + cost), this.value.get),
      }
    """;

  @Test void vpfBoolArmCapturedNatAndTwoReadGetters() {
    okBase(16, new Res("123", "", 0), BOOL_ARM_CAPTURED_NAT, Base.mutBaseAliases);
    assertInstrumented();
  }

  @Test void boolArmCapturedNatAndTwoReadGettersWithoutVpf() {
    okBaseNoVpf(new Res("123", "", 0), BOOL_ARM_CAPTURED_NAT, Base.mutBaseAliases);
  }

  @Test void vpfDeepenedBoolArmPreservesPrimitiveAndSumCaptures() {
    okBase(16, new Res("ok", "", 0), """
      package test
      Test:Main {sys -> sys.io.println(Mixed.branch(1, +2, 3.0, 255.byte, "text", True, TagB, Niches#("held")))}
      Tag:Sealed { .str: Str }
      TagA:Tag { .str -> "a" }
      TagB:Tag { .str -> "b" }
      Niche:Sealed { .str: Str }
      Empty:Niche { .str -> "empty" }
      Niches: { #(s: Str): Niche -> {.str -> s} }
      Mixed: {
        .branch(n: Nat, i: base.Int, f: base.Float, b: base.Byte, s: Str, flag: Bool, tag: Tag, niche: Niche): Str ->
          True.if[Str]{
            .then -> this.check(n, i, f, b, s, flag, tag, niche, this.left, this.middle, this.right),
            .else -> "wrong arm",
            },
        .left: Nat -> this.slow(64),
        .middle: Nat -> 5,
        .right: Nat -> 6,
        .slow(n: Nat): Nat -> (n == 0).if[Nat]{.then -> 4, .else -> this.slow(n - 1)},
        .check(n: Nat, i: base.Int, f: base.Float, b: base.Byte, s: Str, flag: Bool, tag: Tag, niche: Niche,
          left: Nat, middle: Nat, right: Nat): Str ->
          (n == 1).and(i == +2).and(f == 3.0).and(b == (255.byte)).and(s == "text")
            .and(flag).and(tag.str == "b").and(niche.str == "held")
            .and(left == 4).and(middle == 5).and(right == 6)
            .if[Str]{.then -> "ok", .else -> "wrong capture"},
        }
      """, Base.mutBaseAliases);
    assertInstrumented();
    var zig = RunZigProgramTests.generatedZig("test");
    assertTrue(Pattern.compile("^fn \\w+_Zdotthen\\w*_thief_\\d+_thief\\(", Pattern.MULTILINE)
      .matcher(zig).find(), "no deepened thief was emitted for the capture checks:\n" + zig);
  }

  @Test void vpfBoolArmPreservesBoxedGenericCapture() {
    okBase(16, new Res("1:4:5", "", 0), """
      package test
      Test:Main {sys -> sys.io.println(Generic.branch[Nat](1, {n -> n.str}))}
      Generic: {
        .branch[X](value: imm X, render: read F[imm X, Str]): Str -> True.if[Str]{
          .then -> this.join[X](value, render, this.left, this.right),
          .else -> "wrong arm",
          },
        .left: Nat -> this.slow(64),
        .right: Nat -> 5,
        .slow(n: Nat): Nat -> (n == 0).if[Nat]{.then -> 4, .else -> this.slow(n - 1)},
        .join[X](value: imm X, render: read F[imm X, Str], left: Nat, right: Nat): Str ->
          render#value + ":" + (left.str) + ":" + (right.str),
        }
      """, Base.mutBaseAliases);
    assertInstrumented();
  }

  @Test void vpfBoolArmPreservesCapturedTagReceiver() {
    okBase(16, new Res("abcd\nb", "", 0), """
      package test
      Test:Main {sys -> Block#(sys.io.println(Probe.branch(A)), sys.io.println(Probe.branch(B)))}
      Value:Sealed { .join(left: Str, right: Str): Str }
      A:Value { .join(left, right) -> left + right }
      B:Value { .join(left, right) -> "b" }
      Probe: {
        .branch(value: Value): Str -> True.if[Str]{
          .then -> value.join("a" + "b", "c" + "d"),
          .else -> "wrong arm",
          },
        }
      """, Base.mutBaseAliases);
    assertInstrumented();
  }

  @Test void vpfBoolArmPreservesCapturedNicheReceiver() {
    okBase(16, new Res("abcde\nempty", "", 0), """
      package test
      Test:Main {sys -> Block#(sys.io.println(Probe.branch(Values#("e"))), sys.io.println(Probe.branch(Empty)))}
      Value:Sealed { .join(left: Str, right: Str): Str }
      Empty:Value { .join(left, right) -> "empty" }
      Values: { #(suffix: Str): Value -> {.join(left, right) -> left + right + suffix} }
      Probe: {
        .branch(value: Value): Str -> True.if[Str]{
          .then -> value.join("a" + "b", "c" + "d"),
          .else -> "wrong arm",
          },
        }
      """, Base.mutBaseAliases);
    assertInstrumented();
  }

  private static final String HEAP_FIRST_RECEIVER = """
    package test
    Test:Main {sys -> sys.io.println(Views#(List#[Nat](3, 7, 11), 1).at(0).str)}
    Views: {
      #(data: List[Nat], start: Nat): View -> View: {'view
        .data: List[Nat] -> data,
        .start: Nat -> start,
        .at(i: Nat): Nat -> view.data.get(view.start + i),
        },
      }
    """;

  @Test void vpfHeapReceiverWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("7", "", 0), HEAP_FIRST_RECEIVER, Base.mutBaseAliases);
  }

  @Test void vpfHeapReceiverUnderForcedPromotion() {
    okBase(1, new Res("7", "", 0), HEAP_FIRST_RECEIVER, Base.mutBaseAliases);
  }

  private static final String SLOT_FIRST_RESULT = """
    package test
    Test:Main {sys -> sys.io.println(Probe.run(List#[Nat](7)).str)}
    Payloads: { #(data: List[Nat]): Payload -> { .value -> data.get(0) } }
    Payload: { .value: Nat }
    Probe: {
      .run(data: List[Nat]): Nat -> this.join(Payloads#data, this.slow(64)),
      .slow(depth: Nat): Nat -> (depth == 0).if[Nat]{
        .then -> 5,
        .else -> this.slow(depth - 1),
        },
      .join(payload: Payload, n: Nat): Nat -> payload.value + n,
      }
    """;

  @Test void vpfSlotResultWithOwnedCaptureWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("12", "", 0), SLOT_FIRST_RESULT, Base.mutBaseAliases);
  }

  @Test void vpfSlotResultWithOwnedCaptureUnderForcedPromotion() {
    okBase(1, new Res("12", "", 0), SLOT_FIRST_RESULT, Base.mutBaseAliases);
  }

  @Test void vpfThreeHeapArgumentsUnderForcedPromotion() {
    okBase(1, new Res("21", "", 0), """
      package test
      Test:Main {sys -> sys.io.println(Probe.run.str)}
      Probe: {
        .run: Nat -> this.join(this.row(3), this.row(7), this.row(11)),
        .row(n: Nat): List[Nat] -> List#[Nat](this.slow(64, n)),
        .slow(depth: Nat, n: Nat): Nat -> (depth == 0).if[Nat]{
          .then -> n,
          .else -> this.slow(depth - 1, n),
          },
        .join(a: List[Nat], b: List[Nat], c: List[Nat]): Nat ->
          a.get(0) + (b.get(0)) + (c.get(0)),
        }
      """, Base.mutBaseAliases);
  }

  // The receiver of `.get` is the getter of a capture on the parameter `view`. `data` is read after
  // the last `.at`, so each path must leave its count as it was.
  private static final String CAPTURE_RECEIVER_LOOP = """
    package test
    Test:Main {sys -> sys.io.println(Probe.run(List#[Nat](3, 4, 5)).str)}
    Views: {
      #(data: List[Nat], start: Nat): View -> View: {'view
        .data: List[Nat] -> data,
        .start: Nat -> start,
        .at(i: Nat): Nat -> view.data.get(view.start + i),
        },
      }
    Probe: {
      .run(data: List[Nat]): Nat -> this.sum(Views#(data, 1), 300) + (data.get(0)),
      .sum(view: View, n: Nat): Nat -> (n == 0).if[Nat]{
        .then -> 0,
        .else -> view.at(n % 2) + (this.sum(view, n - 1)),
        },
      }
    """;

  @Test void vpfCaptureReceiverWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("1353", "", 0), CAPTURE_RECEIVER_LOOP, Base.mutBaseAliases);
  }

  @Test void vpfCaptureReceiverUnderForcedPromotion() {
    okBase(1, new Res("1353", "", 0), CAPTURE_RECEIVER_LOOP, Base.mutBaseAliases);
  }

  @Test void captureReceiverWithoutVpf() {
    okBaseNoVpf(new Res("1353", "", 0), CAPTURE_RECEIVER_LOOP, Base.mutBaseAliases);
  }

  // The list holds the view, so the owner of the capture is a heap object.
  @Test void vpfCaptureReceiverOnHeapOwnerUnderForcedPromotion() {
    okBase(1, new Res("1353", "", 0), """
      package test
      Test:Main {sys -> sys.io.println(Probe.run(List#[Nat](3, 4, 5)).str)}
      Views: {
        #(data: List[Nat], start: Nat): View -> View: {'view
          .data: List[Nat] -> data,
          .start: Nat -> start,
          .at(i: Nat): Nat -> view.data.get(view.start + i),
          },
        }
      Probe: {
        .run(data: List[Nat]): Nat -> this.held(List#[View](Views#(data, 1))) + (data.get(0)),
        .held(views: List[View]): Nat -> this.sum(views.get(0), 300),
        .sum(view: View, n: Nat): Nat -> (n == 0).if[Nat]{
          .then -> 0,
          .else -> view.at(n % 2) + (this.sum(view, n - 1)),
          },
        }
      """, Base.mutBaseAliases);
  }

  // The operand after the capture receiver throws. `data` is read after the catch.
  private static final String CAPTURE_RECEIVER_ERROR = """
    package test
    Test:Main {sys -> sys.io.println(Probe.run(List#[Nat](3, 4, 5)))}
    Views: {
      #(data: List[Nat], start: Nat): View -> View: {'view
        .data: List[Nat] -> data,
        .start: Nat -> start,
        .at(i: Nat): Nat -> view.data.get(Checks#(view.start + i)),
        },
      }
    Checks: {
      #(i: Nat): Nat -> (i == 2).if[Nat]{
        .then -> Error.msg[Nat] "boom",
        .else -> i,
        },
      }
    Probe: {
      .run(data: List[Nat]): Str -> Try#[Nat]{this.sum(Views#(data, 1), 300)}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg + (data.get(0).str),
        },
      .sum(view: View, n: Nat): Nat -> (n == 0).if[Nat]{
        .then -> 0,
        .else -> view.at(n % 2) + (this.sum(view, n - 1)),
        },
      }
    """;

  @Test void vpfCaptureReceiverErrorWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("boom3", "", 0), CAPTURE_RECEIVER_ERROR, Base.mutBaseAliases);
  }

  @Test void vpfCaptureReceiverErrorUnderForcedPromotion() {
    okBase(1, new Res("boom3", "", 0), CAPTURE_RECEIVER_ERROR, Base.mutBaseAliases);
  }

  // The combiner returns its receiver, and the result is read after the view is gone.
  private static final String CAPTURE_RECEIVER_ESCAPES = """
    package test
    Test:Main {sys -> sys.io.println(Probe.pick(List#[Nat](3, 4, 5)).value.str)}
    Items: {
      #(data: List[Nat]): Item -> Item: {'item
        .value: Nat -> data.get(1),
        .keep(n: Nat): Item -> item,
        },
      }
    Views: {
      #(item: Item, start: Nat): View -> View: {'view
        .item: Item -> item,
        .start: Nat -> start,
        .pick(i: Nat): Item -> view.item.keep(view.start + i),
        },
      }
    Probe: { .pick(data: List[Nat]): Item -> Views#(Items#data, 0).pick(0) }
    """;

  @Test void vpfCaptureReceiverEscapesWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("4", "", 0), CAPTURE_RECEIVER_ESCAPES, Base.mutBaseAliases);
  }

  @Test void vpfCaptureReceiverEscapesUnderForcedPromotion() {
    okBase(1, new Res("4", "", 0), CAPTURE_RECEIVER_ESCAPES, Base.mutBaseAliases);
  }

  // `.size` starts with a getter of a scalar capture and `.first` starts with a getter that builds its result.
  @Test void vpfScalarAndComputedGetterReceiversUnderForcedPromotion() {
    okBase(1, new Res("7", "", 0), """
      package test
      Test:Main {sys -> sys.io.println(Probe.run(Views#(2, 7)).str)}
      Views: {
        #(start: Nat, end: Nat): View -> View: {'view
          .start: Nat -> start,
          .end: Nat -> end,
          .size: Nat -> view.end - (view.start),
          .fresh: List[Nat] -> List#[Nat](start, end),
          .first: Nat -> view.fresh.get(view.start - (view.start)),
          },
        }
      Probe: { .run(view: View): Nat -> view.size + (view.first) }
      """, Base.mutBaseAliases);
  }

  @Test void vpfCaptureReceiverOnReadMethodsUnderForcedPromotion() {
    okBase(1, new Res("7", "", 0), """
      package test
      Test:Main {sys -> sys.io.println(Views#(List#[Nat](3, 7, 11), 1).at(0).str)}
      Views: {
        #(data: List[Nat], start: Nat): View -> View: {'view
          read .data: List[Nat] -> data,
          read .start: Nat -> start,
          read .at(i: Nat): Nat -> view.data.get(view.start + i),
          },
        }
      """, Base.mutBaseAliases);
  }

  // The capture is an argument of the combiner, and the combiner stores it in a new view.
  private static final String CAPTURE_ARGUMENT_STORED = """
    package test
    Test:Main {sys -> sys.io.println(Probe.run(Views#(List#[Nat](3, 4, 5), 1)).str)}
    Views: {
      #(data: List[Nat], start: Nat): View -> View: {'view
        .data: List[Nat] -> data,
        .start: Nat -> start,
        .at(i: Nat): Nat -> view.data.get(view.start + i),
        .shift(n: Nat): View -> Views#(view.data, view.start + n),
        },
      }
    Probe: {
      .run(view: View): Nat -> this.sum(view, 100) + (view.at(0)),
      .sum(view: View, n: Nat): Nat -> (n == 0).if[Nat]{
        .then -> 0,
        .else -> view.shift(n % 2).at(0) + (this.sum(view, n - 1)),
        },
      }
    """;

  @Test void vpfCaptureArgumentStoredWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("454", "", 0), CAPTURE_ARGUMENT_STORED, Base.mutBaseAliases);
  }

  @Test void vpfCaptureArgumentStoredUnderForcedPromotion() {
    okBase(1, new Res("454", "", 0), CAPTURE_ARGUMENT_STORED, Base.mutBaseAliases);
  }

  @Test void captureArgumentStoredWithoutVpf() {
    okBaseNoVpf(new Res("454", "", 0), CAPTURE_ARGUMENT_STORED, Base.mutBaseAliases);
  }

  @Test void captureReceiverErrorWithoutVpf() {
    okBaseNoVpf(new Res("boom3", "", 0), CAPTURE_RECEIVER_ERROR, Base.mutBaseAliases);
  }

  @Test void captureReceiverEscapesWithoutVpf() {
    okBaseNoVpf(new Res("4", "", 0), CAPTURE_RECEIVER_ESCAPES, Base.mutBaseAliases);
  }

  // The owner and the capture are `mut`, and a `mut` method reads the capture.
  private static final String CAPTURE_RECEIVER_MUT = """
    package test
    Test:Main {sys -> sys.io.println(Probe.run.str)}
    Cells: {
      #(data: mut List[Nat]): mut Cell -> mut Cell: {'cell
        mut .data: mut List[Nat] -> data,
        mut .index: Nat -> 1,
        mut .at: Nat -> cell.data.get(cell.index),
        },
      }
    Probe: { .run: Nat -> Cells#(List#[Nat](3, 7, 11)).at }
    """;

  @Test void vpfMutCaptureReceiverUnderForcedPromotion() {
    okBase(1, new Res("7", "", 0), CAPTURE_RECEIVER_MUT, Base.mutBaseAliases);
  }

  @Test void mutCaptureReceiverWithoutVpf() {
    okBaseNoVpf(new Res("7", "", 0), CAPTURE_RECEIVER_MUT, Base.mutBaseAliases);
  }

  // Builds a program whose combiner call is `combine`, which takes the getter of a capture as an operand after the first.
  // With four operands, one thief publishes the capture to the next thief.
  private static String captureLaterOperand(String combine) {
    return """
      package test
      Test:Main {sys -> sys.io.println(Probe.run(Views#(List#[Nat](3, 4, 5), 1)).str)}
      Views: {
        #(data: List[Nat], start: Nat): View -> View: {'view
          .data: List[Nat] -> data,
          .start: Nat -> start,
          },
        }
      Adder: {
        .two(a: Nat, xs: List[Nat]): Nat -> a + (xs.get(1)),
        .three(a: Nat, xs: List[Nat], b: Nat): Nat -> a + (xs.get(2)) + b,
        .four(a: Nat, xs: List[Nat], b: Nat, ys: List[Nat]): Nat -> a + (xs.get(2)) + b + (ys.get(0)),
        }
      Probe: {
        .run(view: View): Nat -> this.sum(view, 100) + (view.data.get(0)),
        .sum(view: View, n: Nat): Nat -> (n == 0).if[Nat]{
          .then -> 0,
          .else -> %s,
          },
        .leaf(n: Nat): Nat -> n %% 2,
        }
      """.formatted(combine);
  }
  private static final String CAPTURE_LAST_OF_TWO = captureLaterOperand(
    "Adder.two(this.sum(view, n - 1), view.data)");
  private static final String CAPTURE_MIDDLE_OF_THREE = captureLaterOperand(
    "Adder.three(this.sum(view, n - 1), view.data, this.leaf(n))");
  private static final String CAPTURE_TWO_OF_FOUR = captureLaterOperand(
    "Adder.four(this.sum(view, n - 1), view.data, this.leaf(n), view.data)");

  @Test void vpfCaptureLastOfTwoOperandsUnderForcedPromotion() {
    okBase(1, new Res("403", "", 0), CAPTURE_LAST_OF_TWO, Base.mutBaseAliases);
  }

  @Test void vpfCaptureLastOfTwoOperandsWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("403", "", 0), CAPTURE_LAST_OF_TWO, Base.mutBaseAliases);
  }

  @Test void vpfCaptureMiddleOfThreeOperandsUnderForcedPromotion() {
    okBase(1, new Res("553", "", 0), CAPTURE_MIDDLE_OF_THREE, Base.mutBaseAliases);
  }

  @Test void vpfCaptureMiddleOfThreeOperandsWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("553", "", 0), CAPTURE_MIDDLE_OF_THREE, Base.mutBaseAliases);
  }

  @Test void vpfCaptureTwoOfFourOperandsUnderForcedPromotion() {
    okBase(1, new Res("853", "", 0), CAPTURE_TWO_OF_FOUR, Base.mutBaseAliases);
  }

  @Test void vpfCaptureTwoOfFourOperandsWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("853", "", 0), CAPTURE_TWO_OF_FOUR, Base.mutBaseAliases);
  }

  @Test void captureTwoOfFourOperandsWithoutVpf() {
    okBaseNoVpf(new Res("853", "", 0), CAPTURE_TWO_OF_FOUR, Base.mutBaseAliases);
  }

  // The combiner has four frame-adding operands, so the last thief waits on two forwarded obligations.
  // Each case fails in a different set of operands, and the leftmost failure must win.
  private static final String FOUR_OPERAND_ERRORS = """
    package test
    Test:Main {sys -> sys.io.println(
      Probe.slowLeft + " " + (Probe.second) + " " + (Probe.fastLeft) + " " + (Probe.third) + " " + (Probe.last) + " " + (Probe.none))}
    Probe: {
      .slowLeft: Str -> Try#[Nat]{this.join(this.fail(64, "a"), this.ok(0), this.fail(0, "c"), this.fail(0, "d"))}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        },
      .second: Str -> Try#[Nat]{this.join(this.ok(64), this.fail(64, "b"), this.ok(0), this.fail(0, "d"))}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        },
      .fastLeft: Str -> Try#[Nat]{this.join(this.fail(0, "a"), this.fail(64, "b"), this.fail(64, "c"), this.fail(64, "d"))}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        },
      .third: Str -> Try#[Nat]{this.join(this.ok(0), this.ok(0), this.fail(64, "c"), this.fail(0, "d"))}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        },
      .last: Str -> Try#[Nat]{this.join(this.ok(64), this.ok(0), this.ok(64), this.fail(0, "d"))}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        },
      .none: Str -> Try#[Nat]{this.join(this.ok(64), this.ok(0), this.ok(64), this.ok(0))}.run{
        .ok(n) -> n.str,
        .info(err) -> err.msg,
        },
      .join(a: Nat, b: Nat, c: Nat, d: Nat): Nat -> a + b + c + d,
      .ok(depth: Nat): Nat -> (depth == 0).if[Nat]{
        .then -> 1,
        .else -> this.ok(depth - 1),
        },
      .fail(depth: Nat, msg: Str): Nat -> (depth == 0).if[Nat]{
        .then -> Error.msg[Nat] msg,
        .else -> this.fail(depth - 1, msg),
        },
      }
    """;

  @Test void vpfFourOperandLeftmostErrorWinsUnderForcedPromotion() {
    okBase(1, new Res("a b a c d 4", "", 0), FOUR_OPERAND_ERRORS, Base.mutBaseAliases);
    var zig = RunZigProgramTests.generatedZig("test");
    assertTrue(zig.contains("fwd_r1_boxed"), "no thief waits on two forwarded obligations:\n" + zig);
  }

  @Test void vpfFourOperandLeftmostErrorWinsWithSparsePromotion() {
    okBase(16, new Res("a b a c d 4", "", 0), FOUR_OPERAND_ERRORS, Base.mutBaseAliases);
  }

  @Test void vpfFourOperandLeftmostErrorWinsWithoutPromotion() {
    okBase(Integer.MAX_VALUE, new Res("a b a c d 4", "", 0), FOUR_OPERAND_ERRORS, Base.mutBaseAliases);
  }

  @Test void fourOperandLeftmostErrorWinsWithoutVpf() {
    okBaseNoVpf(new Res("a b a c d 4", "", 0), FOUR_OPERAND_ERRORS, Base.mutBaseAliases);
  }

  /// Output cannot show if a thief takes the rewritten matcher or the victim runs it early. A
  /// thief function must call a match arm of `ChoiceMatch` (mangled `Zdota`).
  private static void assertMatchArmInThief() {
    var zig = RunZigProgramTests.generatedZig("test");
    var thief = Pattern.compile("^fn \\w+_thief\\(.*?^\\}$", Pattern.MULTILINE | Pattern.DOTALL);
    var matcher = thief.matcher(zig);
    while (matcher.find()) {
      if (matcher.group().contains("Zdota")) { return; }
    }
    throw new AssertionError("no thief function computes a match arm:\n" + zig);
  }

  /// A sequential program passes the output assertions. A thief and `_Locals` that an arm owns
  /// show that VPF instrumented the arm.
  private static void assertInstrumented() {
    var zig = RunZigProgramTests.generatedZig("test");
    var thief = Pattern.compile("^fn \\w+_Zdot(then|else)\\w*_thief\\(", Pattern.MULTILINE);
    var locals = Pattern.compile("^const \\w+_Zdot(then|else)\\w*_Locals = ", Pattern.MULTILINE);
    assertTrue(thief.matcher(zig).find(), "no VPF thief was emitted for a nested `.if` arm:\n" + zig);
    assertTrue(locals.matcher(zig).find(), "no VPF locals struct was emitted for a nested `.if` arm:\n" + zig);
  }
}
