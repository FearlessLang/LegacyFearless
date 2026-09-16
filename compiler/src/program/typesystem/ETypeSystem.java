package program.typesystem;

import ast.E;
import ast.T;
import failure.FailOr;
import program.Program;
import visitors.Visitor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public interface ETypeSystem extends Visitor<FailOr<T>> {
  Program p();
  Gamma g();
  XBs xbs();
  ConcurrentHashMap<Long, TsT> resolvedCalls();
  /// Restrict RC overloading on some method calls. This is needed for when we do narrowed-gamma checks to uphold
  /// certain properties (i.e. [vpf.ComputeVPFMode]).
  Optional<Map<Long, TsT>> callRestrictions();
  List<T> expectedT();
  int depth(); // used to call Program.meths with normalisation done correctly
  TypeSystemCache cache();
  default FailOr<T> visitX(E.X e){
    return g().get(e);
  }

  static ETypeSystem of(Program p, Gamma g, XBs xbs, List<T> expectedT, ConcurrentHashMap<Long, TsT> resolvedCalls, TypeSystemCache cache, int depth){
    return of(p, g, xbs, expectedT, resolvedCalls, cache, depth, Optional.empty());
  }
  static ETypeSystem of(Program p, Gamma g, XBs xbs, List<T> expectedT, ConcurrentHashMap<Long, TsT> resolvedCalls, TypeSystemCache cache, int depth, Optional<Map<Long, TsT>> callRestrictions){
    record Ts(Program p, Gamma g, XBs xbs, List<T> expectedT, ConcurrentHashMap<Long, TsT> resolvedCalls, TypeSystemCache cache, int depth, Optional<Map<Long, TsT>> callRestrictions) implements EMethTypeSystem, ELambdaTypeSystem{}
    return new Ts(p, g, xbs, expectedT, resolvedCalls, cache, depth, callRestrictions.map(Collections::unmodifiableMap));
  }
  default ETypeSystem withExpectedTs(List<T> expectedT){ return of(p(), g(), xbs(), expectedT, resolvedCalls(), cache(), depth(), callRestrictions()); }
  default ETypeSystem withGamma(Gamma g){ return of(p(), g, xbs(), expectedT(), resolvedCalls(), cache(), depth(), callRestrictions()); }
  default ETypeSystem withXBs(XBs xbs){ return of(p(), g(), xbs, expectedT(), resolvedCalls(), cache(), depth(), callRestrictions()); }
  default ETypeSystem withProgram(Program p){ return of(p, g(), xbs(), expectedT(), resolvedCalls(), cache(), depth(), callRestrictions()); }
}
