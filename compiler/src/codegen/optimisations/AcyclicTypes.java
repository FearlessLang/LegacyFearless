package codegen.optimisations;

import codegen.MIR;
import id.Mdf;

import java.util.Collection;
import java.util.Set;

/// Which object literals are acyclic by construction, so the cycle collector can
/// skip them.
///
/// Bacon and Rajan colour such objects green and never treat one as a candidate
/// cycle root (ECOOP 2001, section 3.2). They drive the colour from the Java
/// class loader, which can only see that a class holds no reference fields at
/// all. Fearless answers a stronger question from the type system.
///
/// An object literal is green when every capture it holds is `imm`. An object
/// literal fixes its captures when it is built, so of two objects the one built
/// first cannot name the other; `imm` data is therefore acyclic by construction,
/// and an object whose captures are all `imm` can never come to name itself.
///
/// `read` does not serve here. A `read` reference may name a `mut` object that
/// something else still mutates, and that object can come to name this one.
///
/// The answer depends on the declaration alone, never on what the current
/// compilation happens to contain, so it is safe to write into cached package
/// code the way {@link RcFreeTypes#strategyForever} is.
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
