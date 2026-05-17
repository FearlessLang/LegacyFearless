package program.typesystem;

import ast.E;
import ast.E.Sig;
import ast.T;
import failure.Fail;
import failure.FailOr;
import id.Id.IT;
import id.Mdf;
import program.CM;
import program.Program;
import program.TypeRename;
import program.TypeTable;
import utils.Push;
import utils.Range;

import vpf.ComputeVPFMode;

import java.util.*;
import java.util.stream.IntStream;

import static program.typesystem.SubTyping.isSubType;

/*//as in the paper
  Ls; Xs';G; empty |- e0 : RC0 D[Ts0]
  RC0 in {imm, iso}
  RC0 <= RC
  Xs' disjoint Xs
  RC m[Xs](x1:T1, .., xn:Tn):T -> e, in meths(RC0 D[Ts0])
  Ls; Xs'; G |- e1 : T1[Xs=Ts][⇑P] ... Xs',G |- en : Tn[Xs=Ts][⇑P]
-----------------------------------------------------------------(Prom-Call-T)
  Ls; Xs';G; Ts' |- e0 m[Ts](e1,...en): T[Xs=Ts][⇑r]
*/
public interface EMethTypeSystem extends ETypeSystem {
  /** priority for overloading over receiver modifier */
  List<Mdf> recvPriority = List.of(
    Mdf.iso, Mdf.mut, Mdf.imm, Mdf.recMdf, Mdf.read, Mdf.mutH, Mdf.readH);
  static List<Mdf> inferPriority(Mdf recvMdf) {
    var base = recvPriority.stream().filter(mdf->mdf != recvMdf).toList();
    return Push.of(recvMdf, base);
  }
  /*  
  Ls; Xs'; G; empty |- e0 : T0
  sigs= meth(Ls,T0,m/n,Ts)//overloaded
  sig= selectOverload(sigs,T0,Ts')
  Ts1..Tsn -> Ts0= multiMeth(sig,T0,Ts')
  Ls; Xs'; G; Tsi |- ei : Ti  forall i in 1..n
  T= selectResult(T1..Tn, Ts1..Tsn -> Ts0)
---------------------------------------------------------------
  Ls; Xs'; G; Ts' |- e0 m[Ts](e1..en) : T
  
  |mut A|,|read B| ->|mut C|
  |iso A| |imm B |   |iso C|
*/
  default FailOr<T> visitMCall(E.MCall e) {
    var recV= this.withExpectedTs(List.of());
    var recT= e.receiver().accept(recV);
    return recT.flatMap(t0->visitMCall(t0,e));
  }
  private FailOr<T> visitMCall(T t0, E.MCall e) {
    return t0.match(
      gx->FailOr.err(()->Fail.noMethOnX(e,t0)),
      it->visitMCall(t0.mdf(),it,e)
      );
  }
  private CM applyGenerics(CM cm, List<T> ts){
    var renamer = TypeRename.core();
    var gens= cm.sig().gens();
    // We could do a bounds check here (like below) but we don't (see the comment later in this method)
//    var boundsCheck = GenericBounds.validGenericMeth(p(), xbs(), cm, ts);
//    if (boundsCheck.isPresent()) {
//      return FailOr.err(boundsCheck.get());
//    }
    var transformer= renamer.renameFun(ts, gens);
    Sig res=renamer.renameSigOnMCall(cm.sig(),xbs(),transformer);
    // I am keeping the original generics/bounds on this renamed sig so we can validate the replacement was valid.
    // We need to do this later (rather than before/as part of the replacement) because we only want to check if
    // the replacement is valid for the specific sig we are selecting in the case of an overloaded receiver RC.
    return cm.withSig(res);
  }
  private FailOr<T> visitMCall(Mdf mdf0, IT<T> recvIT, E.MCall e) {
    var recvT = new T(mdf0, recvIT);
    var recvTransientErr = TransientSemantics.checkReceiver(p(), recvT);
    if (recvTransientErr.isPresent()) {
      return FailOr.err(() -> recvTransientErr.get().pos(e.pos()));
    }
    var sigs = p().meths(xbs(),mdf0,recvIT, e.name(),depth()).stream()
      .map(s->applyGenerics(s,e.ts()))
      .sorted(Comparator.comparingInt(cm->
          EMethTypeSystem.recvPriority.indexOf(cm.mdf())))
      .toList();
    CM selected = selectOverload(e,sigs,mdf0,recvIT);
    var boundsCheck = GenericBounds.validGenericMCall(p(), xbs(), selected, e.ts());
    if (boundsCheck instanceof FailOr.Fail<Void> fail) {
      return fail.mapErr(err->()->err.get().pos(e.pos())).cast();
    }

    var multi_ = MultiSigBuilder.multiMethod(
      p(),
      e.name(),
      xbs(),
      selected.mdf(),//bounds,formalMdf
      selected.sig().ts(),//formalTs
      selected.sig().ret(),//formalRet,
      mdf0,this.expectedT()//mdf0,expectedRes
    );
    var multiErr = multi_.asOpt();
    if (multiErr.isPresent()) {return FailOr.err(multiErr.get());}
    var multi = multi_.get();

    FailOr<List<T>> ft1n= FailOr.fold(
      Range.of(e.es()),
      i-> e.es().get(i).accept(multi.expectedT(this, i))
    );
    return ft1n.flatMap(t1n->selectResult(e, selected, multi, t1n));
  }

  private CM selectOverload(E.MCall e, List<CM> sigs, Mdf mdf0, IT<T> recvIT){
    if(sigs.size()==1){ return sigs.getFirst(); }
    return sigs.stream()
      .filter(cm->selectOverload(cm, mdf0))
      .findFirst()//Note: ok find first since ordered by mdf before
      .orElseThrow(()->Fail.undefinedMethod(e.name(), new T(mdf0, recvIT), sigs.stream()).pos(e.pos()));
  }
  private boolean selectOverload(CM cm, Mdf mdf0){
    //we want possible subtypes allowing promotions
    //mut->imm and read->imm already accounted for.
    Mdf methMdf= cm.mdf();//however, mutH/readH are relaxing the method signature
    if(methMdf.isRead()){ methMdf = Mdf.readH; }//not enriching the parameter type
    if(methMdf.isMut()){ methMdf = Mdf.mutH; }
    if (!isSubType(mdf0,methMdf)){ return false; }
    if (expectedT().isEmpty()){ return true; }
    //should we consider return types?
    return true;
  }
  private FailOr<T> selectResult(E.MCall e, CM selected, MultiSig multi, List<T> t1n){
    // Storage-like calls (Vars/List/UList magic containers) take the transient through a
    // generic parameter and hand it back inside a generic return. Both the argument check
    // and the return check would otherwise mask the more precise transientStorage error,
    // so the storage check runs first.
    var storageErr = TransientSemantics.checkStorageLikeCall(p(), selected, t1n);
    if (storageErr.isPresent()) {
      return FailOr.err(() -> storageErr.get().pos(e.pos()));
    }
    var actualErr = TransientSemantics.checkArgumentsAllowedByOriginalParameters(p(), selected, t1n);
    if (actualErr.isPresent()) {
      return FailOr.err(() -> actualErr.get().pos(e.pos()));
    }
    var selectedRetErr = TransientSemantics.checkReturn(p(), selected.sig().ret());
    if (selectedRetErr.isPresent()) {
      return FailOr.err(() -> selectedRetErr.get().pos(e.pos()));
    }
    assert multi.tss().size()==t1n.size();//That is, tss does not have 'this'?
    var sel= IntStream.range(0, multi.rets().size())
      .filter(i->ok(multi,i,t1n))
      .boxed()
      .findFirst();
    return sel
      .map(i->successType(e,selected,i,multi))
      .orElse(FailOr.err(()->Fail.invalidMethodArgumentTypes(e,t1n,multi,expectedT()).pos(e.pos())));
  }
  private FailOr<T> successType(E.MCall e, CM selected, int i, MultiSig multi){
    Mdf mdf = multi.recvMdfs().get(i);
    List<T> ts= multi.tss().stream().map(tsj->tsj.get(i)).toList();
    T ret= multi.rets().get(i);
    var retErr = TransientSemantics.checkReturn(p(), ret);
    if (retErr.isPresent()) {
      return FailOr.err(() -> retErr.get().pos(e.pos()));
    }
    var vpfMode = ComputeVPFMode.of(this, e);
    TsT tst = new TsT(mdf, ts, ret, selected, vpfMode);
    resolvedCalls().put(e.callId(), tst);
    return FailOr.res(ret);
  }
  private boolean ok(MultiSig multi,int i,List<T> t1n){
    return IntStream.range(0, t1n.size()).allMatch(j->{
      var actualT= t1n.get(j);
      var formalT= multi.tss().get(j).get(i);//current par index, current attempt
      return p().isSubType(xbs(),actualT,formalT);
    });
  }
}
  
  
  /*
  * meth(..) as before but return set in overloading

  * selectOverload(..) filters an overloaded version using T0,Ts

  * multiMeth with new order, and return ListListT->ListT, pre filtered by T0,Ts. If empty expected types Ts, we do not filter by Ts.
    sig[mut=iso, read=imm, readonly=imm]
    sig[mut=iso, read=imm]
    sig
    sig[result=hygienic][mut=iso, read=readH] //ignoring the mut/iso
    sig[result=hygenic][1_mut=mutH, other_muts=iso, read=imm ] //if only 1 mut exists

   */
