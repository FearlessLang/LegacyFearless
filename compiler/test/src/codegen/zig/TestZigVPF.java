package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import java.util.regex.Pattern;

import static codegen.zig.RunZigProgramTests.okBase;
import static codegen.zig.RunZigProgramTests.okBaseNoVpf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.RunOutput.Res;

/// VPF join points under forced promotion. {@code okBase(16, ...)} makes thieves steal subtrees
/// of RSum. An {@code Error!} from a stolen branch must unwind again on the fiber of the waiter.
/// Leaf 127 throws.
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

  // `BoolIfOptimisation` inlines one level only, so the split is in an arm function. VPF must
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

  // The leftmost failure wins. Leaves 3 and 100 throw in different subtrees, so a thief usually
  // runs the two failures in parallel.
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

  // As above, for a receiver that `SumMatchOptimisation` rewrote. Receiver and argument throw.
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

  /// Output cannot show if a thief takes the rewritten matcher or the victim runs it early. A
  /// thief function must call a match arm of `ChoiceMatch` (mangled `Zdota`, `Zdotb`).
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
  /// show that VPF instrumented the arm. The patterns do not match `vpfCounter` numbers, which
  /// are not stable.
  private static void assertInstrumented() {
    var zig = RunZigProgramTests.generatedZig("test");
    var thief = Pattern.compile("^fn \\w+_Zdot(then|else)\\w*_thief\\(", Pattern.MULTILINE);
    var locals = Pattern.compile("^const \\w+_Zdot(then|else)\\w*_Locals = ", Pattern.MULTILINE);
    assertTrue(thief.matcher(zig).find(), "no VPF thief was emitted for a nested `.if` arm:\n" + zig);
    assertTrue(locals.matcher(zig).find(), "no VPF locals struct was emitted for a nested `.if` arm:\n" + zig);
  }
}
