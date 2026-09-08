package program.typesystem;

import ast.T;
import id.Mdf;

import java.util.Set;

/// Whether every value a type admits is `imm`.
///
/// The cycle collector asks this of an object literal's captures: an object that holds only
/// `imm` data can never come to name itself. The question has to be answered here rather than
/// in code generation, because lowering drops a generic and keeps its use-site modifier, and
/// the bound is what carries the answer: every `X` in `A[X]` is `imm` however the use site
/// writes it, while an `X` of `A[X: mut, imm]` is not.
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
