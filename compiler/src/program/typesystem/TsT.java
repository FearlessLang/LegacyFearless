package program.typesystem;

import java.util.List;
import java.util.stream.Collectors;

import ast.T;
import id.Mdf;
import program.CM;
import vpf.VPFCallMode;

public record TsT(Mdf recv, List<T> ts, T t, CM original, VPFCallMode vpfMode){
  public TsT(Mdf recv, List<T> ts, T t, CM original) {
    this(recv, ts, t, original, VPFCallMode.Sequential);
  }
  public String toString(){
    return recv+" ("+ts.stream().map(T::toString).collect(Collectors.joining(", "))+"): "+t;
  }
}