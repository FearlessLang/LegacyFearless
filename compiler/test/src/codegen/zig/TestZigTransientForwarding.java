package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.RunOutput.Res;

/// E2E programs for a method that forwards to a callee which builds its result in the slot of its
/// caller.
public class TestZigTransientForwarding {
  @Test void aMethodWhoseOperandsCallMethodsStillBuildsItsResultInTheSlotOfItsCaller() {
    // The operands of `Pts#` in `.sq` and `.add` are primitive operations on results of `.x` and
    // `.y`, so each operand is a temp and not a value in place. Neither temp owns a count, so
    // the forward may run after them, and both methods must have a variant that writes into a
    // caller slot. `.sq.add` takes that slot for its receiver.
    okBase(new Res("11", "", 0), """
      package test
      Pts:{ #(x: Float, y: Float): Pt -> {.x -> x, .y -> y} }
      Pt:{
        .x: Float,
        .y: Float,
        .add(p: Pt): Pt -> Pts#(this.x + (p.x), this.y + (p.y)),
        .sq: Pt -> Pts#((this.x * (this.x)) - (this.y * (this.y)), 2.0 * (this.x) * (this.y)),
        }
      Test:Main{sys -> sys.io.println(Pts#(4.0, 3.0).sq.add(Pts#(4.0, 0.0)).x.str)}
      """, Base.mutBaseAliases);
    var zig = RunZigProgramTests.generatedZig("test");
    assertTrue(zig.contains("fn Pt_0__Zdotsq_0_imm_Zfun_transient("), zig);
    assertTrue(zig.contains("fn Pt_0__Zdotadd_1_imm_Zfun_transient("), zig);
  }
}
