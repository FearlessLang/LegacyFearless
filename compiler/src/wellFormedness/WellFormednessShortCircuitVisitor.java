package wellFormedness;

import ast.E;
import ast.Program;
import ast.T;
import failure.CompileError;
import failure.Fail;
import id.Id;
import id.Mdf;
import magic.Magic;
import program.CM;
import program.TypeTable;
import program.typesystem.XBs;
import utils.Bug;
import visitors.ShortCircuitVisitor;
import visitors.ShortCircuitVisitorWithEnv;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static magic.LiteralKind.isLiteral;

// TODO: Sealed and _C/_m restrictions
public class WellFormednessShortCircuitVisitor extends ShortCircuitVisitorWithEnv<CompileError> {
  private Optional<String> pkg = Optional.empty();
  public WellFormednessShortCircuitVisitor(Program p) { super(p); }

  @Override public Optional<CompileError> visitDec(T.Dec d) {
    pkg = Optional.of(d.name().pkg());
    return ShortCircuitVisitor.visitAll(
        d.lambda().its(),
        it->noRecMdfInImpls(it).map(err->err.pos(d.lambda().pos()))
        )
      .or(()->super.visitDec(d))
      .map(err->err.parentPos(d.pos()));
  }

  @Override public Optional<CompileError> visitLambda(E.Lambda e) {
    return ShortCircuitVisitor.visitAll(e.its(), it->noPrivateTraitOutsidePkg(it.name()))
      .or(()->validLambdaMdf(e))
      .or(()->noSealedOutsidePkg(e))
      .or(()->noImplInlineDec(e))
      .or(()->noAbsMethods(e))
      .or(()->noFreeGensInLambda(e))
      .or(()->validBoundsForLambdaGens(e))
      .or(()->super.visitLambda(e))
      .map(err->err.parentPos(e.pos()));
  }

  @Override public Optional<CompileError> visitMCall(E.MCall e) {
    return noPrivateMethCallOutsideTrait(e)
      .or(()->super.visitMCall(e))
      .map(err->err.parentPos(e.pos()));
  }

  @Override public Optional<CompileError> visitX(E.X e) {
    return super.visitX(e)
      .map(err->err.parentPos(e.pos()));
  }

  @Override public Optional<CompileError> visitMeth(E.Meth e) {
    return norecMdfInNonRecMdf(e.sig(), e.name()).map(err->err.pos(e.pos()))
      .or(()->super.visitMeth(e))
      .map(err->err.parentPos(e.pos()));
  }

  @Override public Optional<CompileError> visitT(T t) {
    assert !(t.mdf().isMdf() && t.isIt());
    return super.visitT(t);
  }

  @Override public Optional<CompileError> visitIT(Id.IT<T> t) {
    return super.visitIT(t);
  }

  @Override public Optional<CompileError> visitMdf(Mdf mdf) {
    if (!this.p.tsf().recMdf() && mdf.isRecMdf()) {
      return Optional.of(CompileError.of("recMdf has been disabled in this compilation"));
    }
    if (!this.p.tsf().hygienics() && mdf.isHyg()) {
      return Optional.of(CompileError.of("Hygienics have been disabled in this compilation"));
    }
    return super.visitMdf(mdf);
  }

  private Optional<CompileError> norecMdfInNonRecMdf(E.Sig s, Id.MethName name) {
    var mdf = name.mdf().orElseThrow();
    if (mdf.isRecMdf()) { return Optional.empty(); }
    return new ShortCircuitVisitor<CompileError>(){
      @Override public Optional<CompileError> visitT(T t) {
        if (t.mdf().isRecMdf()) {
          return Optional.of(Fail.recMdfInNonRecMdf(mdf, name, t).pos(s.pos()));
        }
        return ShortCircuitVisitor.super.visitT(t);
      }
    }.visitSig(s);
  }

  private Optional<CompileError> noRecMdfInImpls(Id.IT<T> it) {
    return new ShortCircuitVisitor<CompileError>(){
      public Optional<CompileError> visitT(T t) {
        if (t.mdf().isRecMdf()) { return Optional.of(Fail.recMdfInImpls(t)); }
        return ShortCircuitVisitor.super.visitT(t);
      }
    }.visitIT(it);
  }

  private Optional<CompileError> noPrivateMethCallOutsideTrait(E.MCall e) {
    // TODO: I can just define a method with the same name and call a """"private"""" thing
    if (!e.name().name().startsWith("._")) { return Optional.empty(); }
    var isInScope = env.ms().stream().anyMatch(m->m.equals(e.name()));
    return isInScope ? Optional.empty() : Optional.of(Fail.privateMethCall(e.name()));
  }

  private Optional<CompileError> noPrivateTraitOutsidePkg(Id.DecId dec) {
    if (isLiteral(dec.name()) || !dec.shortName().startsWith("_")) { return Optional.empty(); }
    var pkg = this.pkg.orElseThrow();
    if (dec.pkg().equals(pkg)) { return Optional.empty(); }
    return Optional.of(Fail.privateTraitImplementation(dec));
  }

  private Optional<CompileError> noSealedOutsidePkg(E.Lambda e) {
    var pkg = this.pkg.orElseThrow();
    var sealedDecs = getSealedDecs(e.id().toIT(), e.its(), pkg);
    if (sealedDecs.isEmpty()) {
      return Optional.empty();
    }
    if (e.isTopLevel()) {
      return Optional.of(Fail.sealedCreation(sealedDecs.getFirst(), pkg).pos(e.pos()));
    }
    if (e.its().size() > 1) {
      var allSealed = Stream.concat(sealedDecs.stream(), e.its().stream().map(Id.IT::name))
        .distinct()
        .map(p::of)
        .toList();
      return Optional.of(Fail.conflictingSealedImpl(allSealed).pos(e.pos()));
    }
    if (e.meths().isEmpty()) { return Optional.empty(); }
    return Optional.of(Fail.sealedCreation(sealedDecs.getFirst(), pkg).pos(e.pos()));
  }

  private List<Id.DecId> getSealedDecs(Id.IT<T> base, List<Id.IT<T>> its, String pkg) {
    if (its.isEmpty()) { return List.of(); }
    return Stream.concat(its.stream(), Stream.of(base))
      .map(Id.IT::name)
      .filter(dec->isLiteral(dec.name()) || !dec.pkg().equals(pkg))
      .filter(dec->p.superDecIds(dec).contains(Magic.Sealed))
      .filter(dec->!dec.equals(Magic.Sealed))
      .toList();
  }

  private Optional<CompileError> noImplInlineDec(E.Lambda e) {
    if (e.its().stream().noneMatch(it->p.isInlineDec(it.name()) && !e.id().id().equals(it.name()))) {
      return Optional.empty();
    }
    return Optional.of(Fail.implInlineDec(
      e.its().stream().map(Id.IT::name).filter(d->p.isInlineDec(d) && !e.id().id().equals(d)).toList()
    ));
  }

  private Optional<CompileError> noFreeGensInLambda(E.Lambda e) {
    if (this.env.gxs().isEmpty()) { return Optional.empty(); }
    var visitor = new UndefinedGXsVisitor(Set.copyOf(e.id().gens()));
    visitor.visitLambda(e);
    var vres= visitor.res(); 
    if (vres.isEmpty()) { return Optional.empty(); }
    return Optional.of(Fail.freeGensInLambda(e.id().toIT().toString(), vres).pos(e.pos()));
  }

  private Optional<CompileError> validBoundsForLambdaGens(E.Lambda e) {
    if (e.id().gens().isEmpty()) { return Optional.empty(); }
    List<String> invalidBounds = new ArrayList<>();
    for (var gx : e.id().gens()) {
      var boundsOpt = env.xbs().getO(gx);
      if (boundsOpt.isEmpty()) { continue; }
      var bounds = boundsOpt.get();
      if (!bounds.equals(e.id().bounds().get(gx))) {
        invalidBounds.add(gx+": "+bounds.stream().sorted().map(Mdf::toString).collect(Collectors.joining(", ")));
      }
    }
    if (invalidBounds.isEmpty()) { return Optional.empty(); }
    throw Fail.invalidLambdaNameMdfBounds(invalidBounds).pos(e.pos());
  }

  private Optional<CompileError> validLambdaMdf(E.Lambda e) {
    if (e.mdf().is(Mdf.readImm, Mdf.mutH, Mdf.readH)) { return Optional.of(Fail.invalidLambdaMdf(e.mdf())); }
    return Optional.empty();
  }

  private Optional<CompileError> noAbsMethods(E.Lambda e) {
    // Ignore top-level
    if (e.mdf().isMdf()) { return Optional.empty(); }

    var implMs = e.meths().stream()
      .filter(m->!m.isAbs())
      .map(E.Meth::name)
      .collect(Collectors.toSet());
    var unimplemented = p.meths(XBs.empty().addBounds(e.id().gens(), e.id().bounds()), e.mdf(), e, 0).stream()
      .filter(CM::isAbs)
      .filter(m->TypeTable.filterByMdf(e.mdf(), m.mdf()))
      .map(CM::name)
      .filter(name->!implMs.contains(name))
      .toList();
    if (!unimplemented.isEmpty()) {
      return Optional.of(Fail.noUnimplementedMethods(unimplemented));
    }
    return Optional.empty();
  }
}
