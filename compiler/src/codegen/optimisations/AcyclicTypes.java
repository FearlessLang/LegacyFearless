package codegen.optimisations;

import codegen.MIR;
import id.Mdf;

import java.util.Collection;
import java.util.Set;

/// Which object literals are acyclic by construction, so the cycle collector can
/// skip them (Bacon and Rajan's green, ECOOP 2001 section 3.2).
///
/// A literal is green when every capture it holds is `imm`: a literal fixes its captures when
/// it is built, so the one built first cannot name the one built after it. `read` does not
/// serve: it may name a `mut` object that can come to name this one.
///
/// The answer depends on the declaration alone, never on what the current compilation happens
/// to contain, so it is safe to write into cached package code, the way
/// {@link RcFreeTypes#strategyForever} is.
public final class AcyclicTypes {
  private final RcFreeTypes rcFree;

  public AcyclicTypes(RcFreeTypes rcFree) { this.rcFree = rcFree; }

  /// Whether an object literal holding `captures` is acyclic by construction.
  ///
  /// An object with no captures holds no references and is trivially green.
  ///
  /// `provedImm` names the captures the type system answered `imm` for, which is the answer
  /// {@link MIR.MT} cannot carry: lowering keeps a generic's use-site modifier and drops the
  /// generic, so `X` in `A[X]` arrives here indistinguishable from `X` in `A[X: mut, imm]`
  /// even though the first admits only `imm` values. See
  /// {@link program.typesystem.ImmGuaranteed}.
  public boolean isGreen(Collection<MIR.X> captures, Set<String> provedImm) {
    return captures.stream().allMatch(x->greenCapture(x, provedImm));
  }

  /// A capture that cannot take part in a cycle.
  ///
  /// A capture the runtime never counts holds no edge for the collector to follow, so it
  /// cannot close one. Everything else has to be `imm`, whether the type system proved that
  /// through a bound or the modifier says so outright.
  private boolean greenCapture(MIR.X x, Set<String> provedImm) {
    if (rcFree.isRcFree(x)) { return true; }
    if (provedImm.contains(x.name())) { return true; }
    return x.t().mdf() == Mdf.imm;
  }
}
