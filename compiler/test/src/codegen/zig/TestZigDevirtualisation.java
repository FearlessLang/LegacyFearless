package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// E2E programs for devirtualised calls. A direct call must select the same method as a virtual
/// call.
public class TestZigDevirtualisation {
  @Test void aCallThroughAnOpenTypeSelectsTheMethodOfTheReceiver() {
    // The backend turns a virtual call into a direct call when only one implementation can be the
    // receiver. `B` leaves `mut .set` abstract, but an immutable `B{}` is still a valid object,
    // because no caller can use a `mut` method on it. So `A` and `B` both reach `Ask`, and `x.n`
    // must select the method of the receiver.
    okBase(new Res("2", "", 0), """
      package test
      Open:{ .n: Nat, mut .set(n: Nat): Void }
      A:Open{ .n -> 1, .set(n) -> {} }
      B:Open{ .n -> 2, mut .set(n: Nat): Void }
      Factory:{ #: Open -> B{} }
      Ask:{ #(x: Open): Nat -> x.n }
      Test:Main{sys -> sys.io.println(Ask#(Factory#).str)}
      """, Base.mutBaseAliases);
  }
}
