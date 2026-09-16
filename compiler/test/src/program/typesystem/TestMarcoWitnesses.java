package program.typesystem;

import org.junit.jupiter.api.Test;

/// Runs the minimised witnesses from Marco's `Frontend/TypeSystemBugs.md`
/// through this frontend and prints whether each is accepted or rejected.
public class TestMarcoWitnesses {
  static void probe(String label, String expectation, String... content){
    System.out.println("\n#### " + label);
    System.out.println("EXPECT: " + expectation);
    try {
      RunTypeSystem.ok(content);
      System.out.println("RESULT: ACCEPTED");
    } catch (Throwable t) {
      var msg = t.toString();
      var firstLines = msg.lines().limit(6).reduce("", (a,b)->a.isEmpty()?b:a+"\n        "+b);
      System.out.println("RESULT: REJECTED (" + t.getClass().getSimpleName() + ")");
      System.out.println("        " + firstLines);
    }
  }

  @Test void witness1_minimalTypeNotUnique() { probe(
    "Bug 1 - minimal type of a call is not unique (his: crash, now first-in-order)",
    "either accepts with some result type, or reports ambiguity; a crash would mirror his bug",
    """
    package test
    A[X:imm,mut]: { read .get: X }
    Test: { #[X:imm,mut](a: A[X]): X -> a.get }
    """); }

  @Test void witness2_genericArgCapabilityIgnored() { probe(
    "Bug 2 - subtyping ignores the capability of a generic argument (his: UNSOUND)",
    "REJECTED; accepting means mut Box[mut C] <: mut Box[imm C] and the same hole exists here",
    """
    package test
    C: {}
    Box[T:*]: { mut .get: T }
    Test: { #(b: mut Box[mut C]): mut Box[imm C] -> b }
    """); }

  @Test void witness2b_genericArgCapabilityIgnoredConverse() { probe(
    "Bug 2 (converse) - mut Box[imm C] <: mut Box[mut C]",
    "REJECTED",
    """
    package test
    C: {}
    Box[T:*]: { mut .get: T }
    Test: { #(b: mut Box[imm C]): mut Box[mut C] -> b }
    """); }

  @Test void witness3_typeAsExpressionSkipsBoundCheck() { probe(
    "Bug 3 - a type used directly as an expression skipped its own bound check (his: UNSOUND)",
    "REJECTED at the bound: Thaw[X:imm] instantiated with mut Cell",
    """
    package test
    Cell: { mut .set: Cell }
    Thaw[X:imm]: { .apply(x: imm X): X -> x }
    Test: { #(cell: Cell): Cell -> Thaw[mut Cell].apply(cell).set }
    """); }

  @Test void witness3b_boundCheckInSignature() { probe(
    "Bug 3 (control) - same bad instantiation written in a signature instead",
    "REJECTED; this is the site his implementation always checked",
    """
    package test
    Cell: { mut .set: Cell }
    Thaw[X:imm]: { .apply(x: imm X): X -> x }
    Test: { #(t: Thaw[mut Cell]): Cell -> t.apply(Cell) }
    """); }

  @Test void witness4_isoLiteralSelfName() { probe(
    "Bug 4 - the self binding of an iso literal discarded as a capture (his: rejects valid)",
    "ACCEPTED; rejecting means the same over-restriction exists here",
    """
    package test
    Counter: { mut .inc: mut Counter }
    Test: { #: iso Counter -> iso Counter{'self mut .inc: mut Counter -> self} }
    """); }

  @Test void witness5_nestedDecUsesEnclosingTypeParam() { probe(
    "Bug 5 - a named declaration inside a method body uses the enclosing type parameters (his: crash)",
    "REJECTED with a real message; an internal exception would mirror his crash",
    """
    package test
    A[Z:mut]: {}
    BreakOuter[Z:mut]: { #: BreakInner -> BreakInner: A[Z]{} }
    """); }

  @Test void open1_nestedLiteralSelfDefaultsToImm() { probe(
    "Open 1 - a call on the self name of a nested literal with no [rc] reaches the checker as imm",
    "ACCEPTED; rejecting means the same inference gap exists here",
    """
    package test
    A: { mut .m: mut A }
    B: { #: mut A -> mut A{'self mut .m: mut A -> self} }
    """); }

  @Test void open1b_nestedLiteralSelfCallChain() { probe(
    "Open 1 (harder) - a mut method called through the nested literal's own self name",
    "ACCEPTED",
    """
    package test
    A: { mut .m: mut A, mut .n: mut A }
    B: { #: mut A -> mut A{'self mut .m: mut A -> self.n, mut .n: mut A -> self} }
    """); }
}
