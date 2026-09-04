package program.typesystem;

import ast.T;
import id.Mdf;

import java.util.Set;

/// Whether every value a type admits is `imm`.
///
/// The cycle collector asks this of an object literal's captures: an object that holds only
/// `imm` data can never come to name itself, so it is never a candidate cycle root. The
/// question has to be answered here rather than in code generation, because lowering keeps a
/// generic's use-site modifier and drops the generic itself, and the bound is what carries the
/// answer: `A[X]` declares `X` with the default bound `imm`, so every `X` in it is `imm`
/// however the use site writes it, while `A[X: mut, imm]` declares one that is not.
public final class ImmGuaranteed {
  private ImmGuaranteed() {}

  private static final Set<Mdf> IMM_ONLY = Set.of(Mdf.imm);

  /// A `null` type is one no answer was found for, and answers "no": the caller loses an
  /// optimisation and never correctness.
  public static boolean of(T t, XBs bounds) {
    if (t == null) { return false; }
    if (t.mdf().isImm()) { return true; }
    // A generic bounded to `imm` alone admits only `imm` instantiations, and a reference to an
    // `imm` value is `imm` whatever modifier reaches it.
    return t.match(gx->bounds.get(gx).equals(IMM_ONLY), it->false);
  }
}
