package program.typesystem;

import ast.T;
import id.Id;
import magic.Magic;
import program.CM;

import java.util.List;
import java.util.Set;

public final class TransientSemantics {
  private TransientSemantics() {}

  public static List<T> originalParameterTypes(CM selected) {
    return switch (selected) {
      case CM.CoreCM core -> core.m().sig().ts();
      case CM.FullCM full -> full.m().sig().orElseThrow().accept(visitors.InjectionVisitor.of()).ts();
    };
  }

  public static boolean isStorageLikeCall(CM selected) {
    return isStorageLikeCall(selected.c().name(), selected.name());
  }

  public static boolean isStorageLikeCall(Id.DecId owner, Id.MethName meth) {
    var methName = meth.name();
    if (owner.equals(Magic.Vars) || owner.equals(Magic.RefK) || owner.equals(Magic.IsoPodK)) { return true; }
    if (owner.equals(Magic.UListK) || owner.equals(Magic.FListK)) { return methName.equals("#") || methName.equals(".fromLList"); }
    if (owner.equals(Magic.UList)) {
      return Set.of("#", ".add", ".set", ".insert", ".update", "+", ":=").contains(methName);
    }
    if (owner.equals(Magic.FList)) {
      return Set.of("#", ".add", ".set", ".insert", ".update", "+").contains(methName);
    }
    return false;
  }
}
