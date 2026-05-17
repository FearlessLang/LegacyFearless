package program;

import ast.T;
import files.Pos;
import id.Id;
import id.Mdf;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public sealed interface CM {
  Id.MethName name();
  List<String> xs();
  boolean isAbs();
  CM withSig(ast.E.Sig sig);
  Pos pos();
  Id.IT<T> c();
  ast.E.Sig sig();
  <TT extends Id.Ty> Map<Id.GX<TT>, Set<Mdf>> bounds();
  default Mdf mdf(){ return name().mdf().orElseThrow(); }
  default T ret(){ return sig().ret(); }
  default String qualifiedName() {
    return c().name()+"$"+name();
  }
  default String toStringSimplified(){ return c() + ", " + name()+"["+ sig().gens().stream().map(Id.GX::toString).collect(Collectors.joining(", "))+"]"+"("+sig().ts().stream().map(T::toString).collect(Collectors.joining(", "))+"): "+ sig().ret(); }
  static CM of(Id.IT<T> c, ast.E.Meth m, ast.E.Sig sig){ return new CoreCM(c,m,sig); }
  static CM of(Id.IT<T> c, astFull.E.Meth m, ast.E.Sig sig){ return new FullCM(c,m,sig); }

  record CoreCM(Id.IT<T> c, ast.E.Meth m, ast.E.Sig sig) implements CM {
    public CoreCM {
      assert m.sig().gens().size() == sig.gens().size() : "Sig mismatch on "+m;
    }

    public Id.MethName name(){ return m.name(); }
    public List<String> xs(){ return m.xs(); }
    public boolean isAbs(){ return m.isAbs(); }
    public CM withSig(ast.E.Sig sig){ return new CoreCM(c, m, sig); }
    public Pos pos() { return this.m.posOrUnknown(); }
    @SuppressWarnings("unchecked")
    @Override public Map<Id.GX<T>, Set<Mdf>> bounds() {
      return sig.bounds();
    }

    @Override
    public String toString() {
      return c + "," + mdf() + " " + name() + "(" + String.join(",", m.xs()) + ")"
        + sig.gens() + sig.ts() + ":" + ret()
        + (isAbs() ? " abs" : " impl");
    }
  }
  record FullCM(Id.IT<T> c, astFull.E.Meth m, ast.E.Sig sig) implements CM{
    public Id.MethName name(){ return m.name().orElseThrow(); }
    public List<String> xs(){ return m.xs(); }
    public boolean isAbs(){ return m.isAbs(); }
    @SuppressWarnings("unchecked")
    @Override public Map<Id.GX<T>, Set<Mdf>> bounds() {
      return sig.bounds();
    }
    public CM withSig(ast.E.Sig sig){ return new FullCM(c, m, sig); }
    public Pos pos() { return this.m.posOrUnknown(); }

    @Override
    public String toString() {
      return c + "," + mdf() + " " + name() + "(" + String.join(",", m.xs()) + ")"
        + sig.gens() + sig.ts() + ":" + ret()
        + (isAbs() ? "abs" : "impl");
    }
  }
}
