package codegen;

import ast.E;
import ast.FreeVariables;
import ast.Program;
import ast.T;
import id.Id;
import id.Mdf;
import magic.Magic;
import program.CM;
import program.typesystem.ImmGuaranteed;
import program.typesystem.TsT;
import program.typesystem.XBs;
import vpf.VPFCallMode;
import utils.*;
import visitors.CtxVisitor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static program.TypeTable.filterByMdf;

public class MIRInjectionVisitor implements CtxVisitor<MIRInjectionVisitor.Ctx, MIRInjectionVisitor.Res<? extends MIR.E>> {
  private final Program p;
  private final ConcurrentHashMap<Long, TsT> resolvedCalls;
  private final Map<String, String> freshRenames = new HashMap<>();
  private int freshCount = 0;
  private String mintX() { return "fear"+(freshCount++)+"$"; }
  private String normX(String x) {
    if (!astFull.E.X.isFresh(x)) { return x; }
    return freshRenames.computeIfAbsent(x, k->mintX());
  }

  public record Res<EE extends MIR.E>(EE e, List<MIR.TypeDef> defs, List<MIR.Fun> funs) {}
  public record TopLevelRes(List<MIR.TypeDef> defs, List<MIR.Fun> funs) {
    public static TopLevelRes EMPTY = new TopLevelRes(List.of(), List.of());
    public TopLevelRes merge(TopLevelRes other) {
      return new TopLevelRes(Push.of(defs(), other.defs()), Push.of(funs(), other.funs()));
    }
    public TopLevelRes mergeAsTopLevel(Res<?> other) {
      return new TopLevelRes(Push.of(defs(), other.defs()), Push.of(funs(), other.funs()));
    }
  }

  /// `declaredTs` and `bounds` are what the cycle collector's green analysis needs and what
  /// {@link MIR.MT} cannot hold: the type a name was declared with, and the bounds of the
  /// generics in scope. Lowering keeps a generic's use-site modifier and drops the generic, so
  /// the answer has to be taken here, where both are still present. Both are keyed by the
  /// source-level name, the way `xXs` is.
  public record Ctx(Map<String, MIR.X> xXs, Map<String, T> declaredTs, XBs bounds) {
    public static Ctx EMPTY = new Ctx();
    public Ctx {
      xXs = Collections.unmodifiableMap(xXs);
      declaredTs = Collections.unmodifiableMap(declaredTs);
    }
    public Ctx withXXs(Map<String, MIR.X> xXs) {
      return new Ctx(xXs, declaredTs, bounds);
    }
    public Ctx with(Map<String, MIR.X> xXs, Map<String, T> declaredTs, XBs bounds) {
      return new Ctx(xXs, declaredTs, bounds);
    }
    private Ctx() { this(Map.of(), Map.of(), XBs.empty()); }

    /// Whether the name `x` was declared with a type that admits only `imm` values.
    public boolean isImm(String x) {
      return ImmGuaranteed.of(declaredTs.get(x), bounds);
    }
  }

  public MIRInjectionVisitor(Collection<String>cached, Program p, ConcurrentHashMap<Long, TsT> resolvedCalls) {
    this.p = p;
    this.resolvedCalls = resolvedCalls;
  }

  public MIR.Program visitProgram() {
    var pkgs = p.ds().values().parallelStream()
      .collect(Collectors.groupingBy(t->t.name().pkg()))
      .entrySet().stream()
      .sorted(Map.Entry.comparingByKey())
      .map(kv->visitPackage(kv.getKey(), kv.getValue()))
      .toList();

    return new MIR.Program(p, pkgs);
  }

  public MIR.Package visitPackage(String pkg, List<T.Dec> ds) {
    var allTDefs = new ArrayList<MIR.TypeDef>(ds.size());
    var allFuns = new ArrayList<MIR.Fun>(ds.size());
    ds.stream()
      .sorted(Comparator.comparing(d->d.name().toString()))
      .map(d->visitTopDec(d.withLambda(d.lambda().withMdf(Mdf.mut)), Ctx.EMPTY))
      .forEach(res->{
        allTDefs.addAll(res.defs());
        allFuns.addAll(res.funs());
      });
    return new MIR.Package(pkg, Mapper.of(defs->allTDefs.forEach(def->defs.put(def.name(), def))), Collections.unmodifiableList(allFuns));
  }

  public TopLevelRes visitTopDec(T.Dec dec, Ctx ctx) {
    if (getTransparentSource(dec).isPresent()) {
      return TopLevelRes.EMPTY;
    }

    var it = dec.toIT();
    var freshTops = dec.lambda().meths().stream()
      .filter(m->!m.isAbs())
      .map(m->{
        var g = new HashMap<>(ctx.xXs());
        var selfT = new T(m.mdf(), it);
        g.put(dec.lambda().selfName(), new MIR.X(normX(dec.lambda().selfName()), MIR.MT.of(selfT)));
        var ts = new HashMap<>(ctx.declaredTs());
        ts.put(dec.lambda().selfName(), selfT);
        var ctx_ = ctx.with(g, ts, ctx.bounds().addBounds(dec.gxs(), dec.bounds()));
        return function(new CM.CoreCM(it, m, m.sig()), ctx_);
      })
      .reduce(TopLevelRes::merge).orElse(TopLevelRes.EMPTY);

    var allConcrete = new Box<>(true);
    var sigs = p.meths(XBs.empty().addBounds(dec.gxs(), dec.bounds()), Mdf.recMdf, dec.lambda(), 0).stream()
      .peek(cm->{
        if (cm.isAbs()) { allConcrete.set(false); }
      })
      .map(cm->visitSig((CM.CoreCM)cm))
      .toList();
    var impls = dec.lambda().its().stream().map(it_->new MIR.MT.Plain(Mdf.mdf, it_.name())).toList();
    var canSingleton = allConcrete.get() && freeVariables(dec.lambda()).isEmpty();
    var singleton = canSingleton ? Optional.of(constr(dec.lambda(), ctx)) : Optional.<MIR.CreateObj>empty();
    var tDef = new MIR.TypeDef(dec.name(), impls, sigs, singleton);

    return freshTops.merge(new TopLevelRes(List.of(tDef), List.of()));
  }

  public MIR.CreateObj constr(E.Lambda e, Ctx ctx) {
    var ms =  p.meths(XBs.empty(), Mdf.recMdf, e, 0).stream()
      .filter(cm->filterByMdf(e.mdf(), cm.mdf()))
      .map(cm->(CM.CoreCM)cm)
      .map(cm->visitMeth(cm, visitSig(cm)))
      .peek(m->{assert m.fName().isPresent();})
      .toList();

    var uncallableMs =  p.meths(XBs.empty(), Mdf.recMdf, e, 0).stream()
      .filter(cm->!filterByMdf(e.mdf(), cm.mdf()))
      .map(cm->(CM.CoreCM)cm)
      .map(cm->visitMeth(cm, visitSig(cm)))
      .toList();

    var fv = new FreeVariables();
    fv.visitLambda(e);
    return new MIR.CreateObj(
      MIR.MT.of(new T(e.mdf(), e.id().toIT())),
      normX(e.selfName()),
      ms,
      uncallableMs,
      captures(fv.res(), ctx),
      immCaptures(fv.res(), ctx)
    );
  }

  public MIR.Sig visitSig(CM.CoreCM cm) {
    return new MIR.Sig(
      cm.name(),
      Streams.zip(cm.xs(), cm.sig().ts()).map((x,t)->{
        x = x.equals("_") ? mintX() : normX(x);
        return new MIR.X(x, MIR.MT.of(t));
      }).toList(),
      MIR.MT.of(cm.ret())
    );
  }

  public TopLevelRes function(CM.CoreCM cm, Ctx ctx) {
    var sig = visitSig(cm);
    var captures = captures(cm.m(), ctx);

    // Gamma uses source-level names, because bodies refer to params by their name before the
    // renaming. A "_" param has no name, so it stays out of Gamma.
    var mCtx = ctx.with(
      Mapper.of(xXs->{
        xXs.putAll(ctx.xXs());
        Streams.zip(cm.xs(), sig.xs()).forEach((srcX,x)->{
          if (!srcX.equals("_")) { xXs.put(srcX, x); }
        });
      }),
      Mapper.of(ts->{
        ts.putAll(ctx.declaredTs());
        Streams.zip(cm.xs(), cm.sig().ts()).forEach((srcX,t)->{
          if (!srcX.equals("_")) { ts.put(srcX, t); }
        });
      }),
      ctx.bounds().addBounds(cm.sig().gens(), cm.sig().bounds()));

    var x = ctx.xXs().get(selfNameOf(cm.c().name()));
    // The self-arg is always present, also when it is not captured, to keep the signatures equal
    Stream<MIR.X> selfArg =Stream.of(x);
    var args = Streams.of(sig.xs().stream(), selfArg, captures.stream().filter(xi->!xi.name().equals(x.name()))).toList();

    var rawBody = cm.m().body().orElseThrow();
    var bodyRes = rawBody.accept(this, mCtx);
    var fun = new MIR.Fun(new MIR.FName(cm, captures.contains(x)), args, sig.rt(), bodyRes.e());
    return new TopLevelRes(bodyRes.defs(), Push.of(bodyRes.funs(), fun));
  }
  public MIR.Meth visitMeth(CM.CoreCM cm, MIR.Sig sig) {
    // An uncallable method can be abstract
    if (cm.isAbs()) {
      return new MIR.Meth(cm.c().name(), sig, false, Collections.emptySortedSet(), Optional.empty());
    }
    assert !cm.isAbs();
    var x = selfNameOf(cm.c().name());

    var fv = new FreeVariables();
    fv.visitMeth(cm.m());
    var xs = fv.res();
    var capturesSelf = xs.remove(x);
    var captures = xs.stream().map(this::normX).collect(Collectors.toCollection(TreeSet::new));

    return new MIR.Meth(cm.c().name(), sig, capturesSelf, Collections.unmodifiableSortedSet(captures), Optional.of(new MIR.FName(cm, capturesSelf)));
  }

  @Override public Res<MIR.MCall> visitMCall(E.MCall e, Ctx ctx) {
    var recvRes = e.receiver().accept(this, ctx);
    var tst = this.resolvedCalls.get(e.callId());
    var args = e.es().stream().map(ei->ei.accept(this, ctx)).toList();
    var topLevel = Stream.concat(Stream.of(recvRes), args.stream())
      .reduce(TopLevelRes.EMPTY, TopLevelRes::mergeAsTopLevel, TopLevelRes::merge);

    var call = new MIR.MCall(
      recvRes.e(),
      e.name().withMdf(Optional.of(tst.original().mdf())),
      args.stream().map(Res::e).toList(),
      MIR.MT.of(tst.t()),
      MIR.MT.of(((CM.CoreCM) tst.original()).m().sig().ret()),
      tst.original().mdf(),
      getVariants(recvRes.e(), e)
    );

    return new Res<>(call, topLevel.defs(), topLevel.funs());
  }

  @Override public Res<MIR.X> visitX(E.X e, Ctx ctx) {
    return new Res<>(visitX(e.name(), ctx), List.of(), List.of());
  }
  public MIR.X visitX(String x, Ctx ctx) {
    var fullX = ctx.xXs.get(x);
    if (fullX == null) {
      throw new NotInGammaException(x);
    }
    return fullX;
  }

  @Override public Res<MIR.CreateObj> visitLambda(E.Lambda e, Ctx ctx) {
    var dec = p.of(e.id().id());
    assert dec.lambda() == e;
    var transparentSource = getTransparentSource(p.of(e.id().id()));
    if (transparentSource.isPresent()) {
      var realDec = transparentSource.get();
      var k = new MIR.CreateObj(
        MIR.MT.of(new T(e.mdf(), realDec.toIT())),
        normX(realDec.lambda().selfName()),
        List.of(),
        List.of(),
        MIR.createCapturesSet()
      );
      return new Res<>(k, List.of(), List.of());
    }

    var k = constr(e, ctx);
    var topLevel = visitTopDec(dec, ctx);
    return new Res<>(k, topLevel.defs(), topLevel.funs());
  }

  private Optional<T.Dec> getTransparentSource(T.Dec d) {
    if (d.name().isFresh() && d.lambda().meths().isEmpty()) {
      var nonSelfImpls = d.lambda().its().stream().filter(it->!it.name().equals(d.name())).toList();
      if (nonSelfImpls.size() != 1) { return Optional.empty(); }
      var realIT = nonSelfImpls.getFirst();
      return Optional.of(p.of(realIT.name()));
    }
    return Optional.empty();
  }

  private String selfNameOf(Id.DecId d) {
    return p.of(d).lambda().selfName();
  }

  private EnumSet<MIR.MCall.CallVariant> getVariants(MIR.E recv, E.MCall e) {
    var recvT = (MIR.MT.Usual) recv.t();
    var recvIT = recvT.it();
    Optional<String> literal = Magic.getLiteral(p, recvIT.name());
    var isStrFlowSource = e.name().name().equals(".codepoints") || e.name().name().equals(".graphemes");
    if (isStrFlowSource && (literal.map(Magic::isStringLiteral).orElse(recvIT.name().equals(Magic.Str)))) {
      return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow);
    }
    if (e.name().name().equals(".flow")) {
      if (recvIT.name().equals(new Id.DecId("base.LList", 1))) {
        var flowElem = recvIT.ts().getFirst();
        if (recvT.mdf().is(Mdf.read, Mdf.imm)) {
          return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow);
        }
        if (flowElem.mdf().is(Mdf.read, Mdf.imm, Mdf.readImm)) {
          return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow);
        }
        return EnumSet.of(MIR.MCall.CallVariant.Standard);
      }
      if (recvIT.name().equals(Magic.UList)) {
        var flowElem = recvIT.ts().getFirst();
        if (recvT.mdf().is(Mdf.read, Mdf.imm)) {
          return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow);
        }
        if (flowElem.mdf().is(Mdf.read, Mdf.imm)) {
          return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow, MIR.MCall.CallVariant.SafeMutSourceFlow);
        }
        return EnumSet.of(MIR.MCall.CallVariant.Standard);
      }
      if (recvIT.name().equals(Magic.FList)) {
        var flowElem = recvIT.ts().getFirst();
        if (recvT.mdf().is(Mdf.read, Mdf.imm)) {
          return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow);
        }
        if (flowElem.mdf().is(Mdf.read, Mdf.imm)) {
          return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow);
        }
        return EnumSet.of(MIR.MCall.CallVariant.Standard);
      }
    }
    if (recvIT.name().equals(Magic.FlowK) && e.name().name().equals("#")) {
      var flowElem = e.ts().getFirst();
      if (flowElem.mdf().is(Mdf.read, Mdf.imm)) {
        return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow, MIR.MCall.CallVariant.SafeMutSourceFlow);
      }
    }
    if (recvIT.name().equals(Magic.FlowK) && (e.name().name().equals(".ofIso") || e.name().name().equals(".ofIsos"))) {
      return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow, MIR.MCall.CallVariant.SafeMutSourceFlow);
    }
    if (recvIT.name().equals(Magic.FlowK) && e.name().equals(new Id.MethName(".range", 2))) {
      return EnumSet.of(MIR.MCall.CallVariant.DataParallelFlow, MIR.MCall.CallVariant.PipelineParallelFlow, MIR.MCall.CallVariant.SafeMutSourceFlow);
    }
    if (recvIT.name().equals(Magic.FlowK) && e.name().equals(new Id.MethName(".range", 1))) {
      return EnumSet.of(MIR.MCall.CallVariant.PipelineParallelFlow, MIR.MCall.CallVariant.SafeMutSourceFlow);
    }

    // `.merge` and `.mergeFold` are always parallelisable. `ComputeVPFMode` refuses them on its
    // own, because their args are `mut` methods on `this`. See `base.flows._FeartDriver`.
    if (recvIT.name().equals(Magic.FeartDriver) && (e.name().equals(new Id.MethName(".merge", 2))
      || e.name().equals(new Id.MethName(".merge", 3))
      || e.name().equals(new Id.MethName(".mergeFold", 3)))) {
      return EnumSet.of(MIR.MCall.CallVariant.VPFParallelisable);
    }

    var tst = this.resolvedCalls.get(e.callId());
    if (tst != null && tst.vpfMode() == VPFCallMode.Parallel) {
      return EnumSet.of(MIR.MCall.CallVariant.VPFParallelisable);
    }

    return EnumSet.of(MIR.MCall.CallVariant.Standard);
  }

  private SortedSet<String> freeVariables(E.Lambda e) {
    var fv = new FreeVariables();
    fv.visitLambda(e);
    return Collections.unmodifiableSortedSet(fv.res());
  }
  private SortedSet<String> freeVariables(E.Meth m) {
    var fv = new FreeVariables();
    fv.visitMeth(m);
    return Collections.unmodifiableSortedSet(fv.res());
  }
  /// The captures the type system answered `imm` for, by the name they carry in the capture
  /// set. A name it has no answer for stays out, which reads as "not proved" downstream.
  private Set<String> immCaptures(Collection<String> freeVariables, Ctx ctx) {
    return freeVariables.stream()
      .filter(ctx::isImm)
      .map(x->ctx.xXs().get(x))
      .filter(Objects::nonNull)
      .map(MIR.X::name)
      .collect(Collectors.toUnmodifiableSet());
  }

  private SortedSet<MIR.X> captures(Collection<String> freeVariables, Ctx ctx) {
    return Collections.unmodifiableSortedSet(freeVariables.stream()
        .map(x->visitX(x, ctx))
        .collect(Collectors.toCollection(MIR::createCapturesSet)));
  }
  private SortedSet<MIR.X> captures(E.Meth m, Ctx ctx) {
    var fv = new FreeVariables();
    fv.visitMeth(m);
    return Collections.unmodifiableSortedSet(fv.res().stream()
      .map(x->visitX(x, ctx))
      .collect(Collectors.toCollection(MIR::createCapturesSet)));
  }

  private static class NotInGammaException extends RuntimeException {
    public NotInGammaException(String x) { super(x); }
  }
}
