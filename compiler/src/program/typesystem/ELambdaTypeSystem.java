package program.typesystem;

import ast.E;
import ast.E.Sig;
import ast.T;
import ast.T.Dec;
import failure.CompileError;
import failure.Fail;
import failure.FailOr;
import files.Pos;
import id.Mdf;
import utils.Box;
import utils.Bug;
import utils.Streams;
import visitors.ShortCircuitVisitor;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static program.TypeTable.filterByMdf;


interface ELambdaTypeSystem extends ETypeSystem{
  default FailOr<T> visitLambda(E.Lambda lit){
    if (!p().tsf().literalPromotions()) {
      return litT(lit);
    }
    if (lit.mdf().isMut()) {
      return litT(lit.withMdf(Mdf.iso))
        .or(()->litT(lit));
    }
    if (lit.mdf().isRead()) {
      return litT(lit.withMdf(Mdf.imm))
        .or(()->litT(lit));
    }
    return litT(lit);
  }

  private FailOr<T> litT(E.Lambda lit) {
    return cache().litT(p(), lit, ()->{
      Dec d= new Dec(lit);
      var self= (ELambdaTypeSystem) withProgram(p().withDec(d));
      var err1= self.implMethErrors(lit);
      return err1.flatMap(_ ->self.bothT(d));
    });
  }
  private List<E.Meth> filteredByMdf(E.Lambda b){
    return b.meths().stream()
    .filter(m->filterByMdf(b.mdf(), m.mdf()))
    .toList();
  }
  private List<E.Meth> excludedByMdf(E.Lambda b){
    return b.meths().stream()
    .filter(m->!filterByMdf(b.mdf(), m.mdf()))
    .toList();
  }
  private FailOr<Void> implMethErrors(E.Lambda b){
    Mdf mdf= b.mdf();
    var validMethods = filteredByMdf(b);
    var validMethNames = validMethods.stream()
      .map(E.Meth::name).collect(Collectors.toSet());
    var hasUncallable= b.meths().stream()
      .anyMatch(m -> !validMethNames.contains(m.name()));
    if (hasUncallable) { return FailOr.err(()->
      Fail.uncallableMeths(mdf,excludedByMdf(b)).pos(b.pos()));
    }
    var sadlyAbs= validMethods.stream().filter(E.Meth::isAbs).toList();
    if (!sadlyAbs.isEmpty()) { return FailOr.err(()->
      Fail.unimplementedInLambda(sadlyAbs).pos(b.pos()));
    }
    return FailOr.ok();
  }
  default FailOr<T> bothT(Dec d){
    var b = d.lambda();
    var xbs = xbs().addBounds(d.gxs(),d.bounds());
    var invalidGens = GenericBounds.validGenericLambda(p(), xbs, b);
    if (invalidGens instanceof FailOr.Fail<Void> fail) {
      return fail.mapErr(err->()->err.get().pos(b.pos())).cast();
    }
    T selfT= new T(b.mdf(), d.toIT());
    var selfName=b.selfName();
    var mRes= FailOr.fold(b.meths(),
      mi->methOkOuter(xbs, selfT, selfName, mi));
    return mRes.map(_ ->selfT);
  }
  private FailOr<Void> sigOk(String context, Sig sig, Optional<Pos> p){
    var ts= Stream.concat(sig.ts().stream(),Stream.of(sig.ret()));
    var badBounds= ts
      .map(t->t.accept(new KindingJudgement(p(), t.toString(), xbs(), GenericBounds.ALL_RCS, true)))
      .filter(FailOr::isErr)
      .<FailOr<Void>>map(FailOr::cast)
      .map(res->res.mapErr(err->()->err.get().pos(p)))
      .findFirst();
    return badBounds.orElseGet(FailOr::ok);
  }
  private FailOr<Void> methOkOuter(XBs xbs, T litT, String selfName, E.Meth mi) {
    var selfT = switch (litT.mdf()) {
      case mdf,iso -> litT.withMdf(Mdf.mut);
      default -> litT;
    };
    litT = litT.mdf().isMdf() ? litT.withMdf(Mdf.mut) : litT;
    var boundedTypeSys =(ELambdaTypeSystem) ETypeSystem.of(
        p(), g(), xbs,
        expectedT(), resolvedCalls(), cache(), depth());
    try { return boundedTypeSys.mOk(selfName, selfT, litT, mi); }
    catch (CompileError err) { return err.fail(); }
    //TODO: will die soon since we avoid all the throws?
  }
  private FailOr<Void> mOk(String selfName, T selfT, T litT, E.Meth m){
    var xbs = xbs().addBounds(m.sig().gens(),m.sig().bounds());
    var withXBs = (ELambdaTypeSystem) withXBs(xbs);
    var sigOk = withXBs.sigOk(selfT.toString(), m.sig(), m.pos());
    if (sigOk.isErr()) { return sigOk; }
    if(m.isAbs()){ return FailOr.ok(); }
    return withXBs.mOkEntry(selfName, selfT, litT, m, m.sig());
  }
  default FailOr<Void> mOkEntry(String selfName, T selfT, T litT, E.Meth m, E.Sig sig) {
    var e   = m.body().orElseThrow();
    var mMdf = m.mdf();

    var args = sig.ts();
    var ret = sig.ret();
    assert !selfT.mdf().isMdf() || g().dom().isEmpty();
    var g0  = g().add(selfName, selfT).ctxAwareGamma(p(), xbs(), litT, mMdf);
    var gg = Streams.zip(m.xs(), args).fold(Gamma::add, g0);

    var baseCase = isoAwareJudgment(gg, m, e, ret);
    var baseDestiny = baseCase.isEmpty() || !ret.mdf().is(Mdf.imm, Mdf.iso);
    if (baseDestiny) { return FailOr.opt(baseCase); }
    //res is iso or imm, thus is promotable

    var criticalFailure = isoAwareJudgment(gg, m, e, ret.withMdf(Mdf.readH));
    if (criticalFailure.isPresent()) { return FailOr.opt(baseCase); }

//    var readPromotion = mOkReadPromotion(selfName, selfT, litT, m, sig);
//    if (readPromotion.isEmpty() || !ret.mdf().is(Mdf.imm, Mdf.iso)) {
//      return FailOr.opt(readPromotion.flatMap(ignored->baseCase));
//    }

    var isoPromotion = mOkIsoPromotion(selfName, selfT, litT, m, sig);
    if(ret.mdf().isIso() || isoPromotion.isEmpty()){
      return FailOr.opt(isoPromotion);
    }

    var selfTRes = gg.get(selfName);
    if (!selfTRes.isRes()) { return selfTRes.cast(); }
    Mdf selfTMdf = selfTRes.get().mdf();
    return FailOr.opt(mOkImmPromotion(selfName, selfT, litT, m, sig, selfTMdf)
      .flatMap(ignored->baseCase));
  }

  // Currently not used, need to think more about soundness:
  default Optional<Supplier<CompileError>> mOkReadPromotion(String selfName, T selfT, T litT, E.Meth m, E.Sig sig) {
    var readOnlyAsReadG = new Gamma() {
      @Override public FailOr<Optional<T>> getO(String x) {
        return g().getO(x).map(res->res
          .filter(t->!t.mdf().isMdf())
          .map(t->t.mdf().isReadH() ? t.withMdf(Mdf.read) : t)
        );
      }
      @Override public List<String> dom() { return g().dom(); }
      @Override public String toString() { return "readOnlyAsReadG"+g().toString(); }
      @Override public String toStr(){ throw Bug.unreachable(); }
    };

    var g0 = readOnlyAsReadG.add(selfName, selfT).ctxAwareGamma(p(), xbs(), litT, m.mdf());
    var gg  = Streams.zip(
      m.xs(),
      sig.ts().stream().map(t->t.mdf().isReadH() ? t.withMdf(Mdf.read) : t).toList()
    ).fold(Gamma::add, g0);
    return isoAwareJudgment(gg, m, m.body().orElseThrow(), sig.ret());
  }

  default Optional<Supplier<CompileError>> mOkIsoPromotion(String selfName, T selfT, T litT, E.Meth m, E.Sig sig) {
    Function<T, T> mdfTransform = t->{
      if (t.mdf().isMut()) { return t.withMdf(Mdf.mutH); }
      if (t.mdf().is(Mdf.read, Mdf.readImm)) { return t.withMdf(Mdf.readH); }
      return t;
    };
    var mutAsLentG = new Gamma() {
      @Override public FailOr<Optional<T>> getO(String x) {
        return g().getO(x).map(res->res.filter(t->!t.mdf().isMdf()).map(mdfTransform));
      }
      @Override public List<String> dom() { return g().dom(); }
      @Override public String toString() { return "readOnlyAsReadG"+g().toString(); }
      @Override public String toStr(){ throw Bug.unreachable(); }
    };
    // TODO: relaxation, before we captured gamma as if it were mutH, but because we don't have mutH methods we rely on an explicit gamma transform here.
//    var mMdf = mdfTransform.apply(selfT.withMdf(m.mdf())).mdf();
    var methSelfTRes = g().add(selfName, selfT).ctxAwareGamma(p(), xbs(), litT, m.mdf()).get("this");
    if (!methSelfTRes.isRes()) {
      return methSelfTRes.asOpt();
    }
    var g0 = mutAsLentG.ctxAwareGamma(p(), xbs(), litT, m.mdf()).add(selfName, mdfTransform.apply(methSelfTRes.get()));
    var gg  = Streams.zip(
      m.xs(),
      sig.ts().stream().map(mdfTransform).toList()
    ).filter((_, t)->!t.mdf().isMdf()).fold(Gamma::add, g0);
    return isoAwareJudgment(gg, m, m.body().orElseThrow(), sig.ret().withMdf(Mdf.mut));
  }

  default Optional<Supplier<CompileError>> mOkImmPromotion(String selfName, T selfT, T litT, E.Meth m, E.Sig sig, Mdf selfTMdf) {
    var noMutyG = new Gamma() {
      @Override public FailOr<Optional<T>> getO(String x) {
        return g().getO(x).map(res->res.filter(t->!(t.mdf().isLikeMut() || t.mdf().isRecMdf() || t.mdf().isMdf())));
      }
      @Override public List<String> dom() { return g().dom(); }
      @Override public String toString() { return "noMutyG"+g().toString(); }
      @Override public String toStr(){ throw Bug.unreachable(); }
    };
    var mMdf = m.mdf();
    var selfG = selfTMdf.isLikeMut() || selfTMdf.isRecMdf() ? Gamma.empty() : noMutyG.add(selfName, selfT);
    var g0 = Streams.zip(m.xs(), sig.ts())
      .filter((_,t)->!t.mdf().isLikeMut() && !t.mdf().isMdf() && !t.mdf().isRecMdf())
      .fold(Gamma::add, selfG);
    var gg = g0.ctxAwareGamma(p(), xbs(), litT, mMdf);
    return isoAwareJudgment(gg, m, m.body().orElseThrow(), sig.ret().withMdf(Mdf.readH));
  }

  /** G;XBs |= e : T */
  default Optional<Supplier<CompileError>> isoAwareJudgment(Gamma g, E.Meth m, E e, T expected) {//TODO: below is terrible and needs refactoring
    var res1= okWithSubType(g, m, e, expected).asOpt();
    return res1.or(()->g.dom().stream()
      .filter(x->
        switch (g.get(x).map(xT->xT.mdf().isIso() || (xT.isMdfX() && xbs().get(xT.gxOrThrow()).contains(Mdf.iso)))) {
          case FailOr.Res<Boolean> res -> res.get();
          // we cannot capture something it's not in our domain, so skip it
          case FailOr.Fail<Boolean> _ -> false;
        })
      .map(x -> countIsoUsages(e,x))
      .filter(Optional::isPresent)
      .findFirst()
      .flatMap(opt->opt)
    );
  }

  private static Optional<Supplier<CompileError>> countIsoUsages(E e, String x) {
    var nUsages = new Box<>(0);
    var hasCapturedX = new Box<>(false);
    return e.accept(new ShortCircuitVisitor<>() {
      @Override public Optional<Supplier<CompileError>> visitLambda(E.Lambda e) {
        if (hasCapturedX.get()) {return Optional.empty();}
        return new ShortCircuitVisitor<Supplier<CompileError>>() {
          @Override public Optional<Supplier<CompileError>> visitX(E.X e) {
            if (!e.name().equals(x)) {return ShortCircuitVisitor.super.visitX(e);}
            hasCapturedX.set(true);
            if (nUsages.get() > 0) {return Optional.of(() -> Fail.multipleIsoUsage(e).pos(e.pos()));}
            return Optional.empty();
          }
        }.visitLambda(e);
      }

      @Override public Optional<Supplier<CompileError>> visitX(E.X e) {
        if (!e.name().equals(x)) {return ShortCircuitVisitor.super.visitX(e);}
        if (hasCapturedX.get()) {
          return Optional.of(() -> Fail.multipleIsoUsage(e).pos(e.pos()));
        }
        int n = nUsages.update(usages -> usages + 1);
        if (n > 1) {return Optional.of(() -> Fail.multipleIsoUsage(e).pos(e.pos()));}
        return Optional.empty();
      }
    });
  }

  private FailOr<Void> okWithSubType(Gamma g, E.Meth m, E e, T expected) {
    var methodBodyTypeSystem = ETypeSystem.of(p(), g, xbs(), List.of(expected), resolvedCalls(), cache(), depth()+1);
    FailOr<T> res = e.accept(methodBodyTypeSystem);
    return res
      .flatMap(t -> methSubType(t, expected))
      .mapErr(err->()->err.get().parentPos(e.pos()));
    // We pass the expected type of the expression down because different method body promotions
    // have different expected types. We could create a new type system visitor with the updated
    // expected instead, but that feels like overkill.
  }
  private FailOr<Void> methSubType(T t, T expected){
    var resOk= p().isSubType(xbs(), t, expected);
    if (resOk){ return FailOr.ok(); }
    return FailOr.err(()->Fail.noSubTypingRelationship(t, expected));
  }
}
