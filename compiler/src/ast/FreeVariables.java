package ast;

import visitors.CollectorVisitor;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class FreeVariables implements CollectorVisitor<SortedSet<String>> {
  private final SortedSet<String> res = new TreeSet<>(String::compareTo);
  private Set<String> fresh = new HashSet<>();

  public SortedSet<String> res() {return this.res;}

  public Void visitLambda(E.Lambda e) {
    var old = fresh;
    fresh = new HashSet<>(fresh);
    fresh.add(e.selfName());
    CollectorVisitor.super.visitLambda(e);
    this.fresh = old;
    return null;
  }

  public Void visitMeth(E.Meth m) {
    var old = fresh;
    fresh = new HashSet<>(fresh);
    fresh.addAll(m.xs());
    CollectorVisitor.super.visitMeth(m);
    this.fresh = old;
    return null;
  }

  public Void visitX(E.X e) {
    if (!fresh.contains(e.name())) {res.add(e.name());}
    return CollectorVisitor.super.visitX(e);
  }
}
