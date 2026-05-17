package program.typesystem;

import ast.E;
import ast.FreeVariables;
import ast.T;
import failure.CompileError;
import failure.Fail;
import id.Id;
import magic.Magic;
import program.CM;
import program.Program;
import utils.Streams;

import java.util.*;

public final class TransientSemantics {
  private TransientSemantics() {}

  public static boolean containsTransient(Program p, T t) {
    return t.match(
      _ -> false,
      it -> headImplementsTransient(p, it) || it.ts().stream().anyMatch(arg -> containsTransient(p, arg))
    );
  }

  public static Optional<CompileError> checkArgumentAllowed(Program p, T argument, T parameter) {
    if (!containsTransient(p, argument)) { return Optional.empty(); }
    if (headImplementsTransient(p, parameter)) { return Optional.empty(); }
    return Optional.of(Fail.transientArgument(argument, parameter));
  }

  public static Optional<CompileError> checkReceiver(Program p, T recv) {
    if (!containsTransient(p, recv)) { return Optional.empty(); }
    if (headImplementsTransient(p, recv)) { return Optional.empty(); }
    return Optional.of(Fail.transientReceiver(recv));
  }

  public static Optional<CompileError> checkReturn(Program p, T ret) {
    if (!containsTransient(p, ret)) { return Optional.empty(); }
    return Optional.of(Fail.transientReturn(ret));
  }

  public static Optional<CompileError> checkCaptures(Program p, Gamma g, T lambdaT, E.Lambda lit) {
    if (headImplementsTransient(p, lambdaT) || lambdaImplementsTransient(p, lit)) { return Optional.empty(); }
    for (var capture : capturedNames(lit)) {
      var capturedT = g.get(capture);
      if (!capturedT.isRes()) { continue; }
      if (containsTransient(p, capturedT.get())) {
        return Optional.of(Fail.transientCapture(capture, capturedT.get(), lambdaT));
      }
    }
    return Optional.empty();
  }

  private static boolean lambdaImplementsTransient(Program p, E.Lambda lit) {
    return lit.its().stream().anyMatch(it -> headImplementsTransient(p, it));
  }

  public static Optional<CompileError> checkStorageLikeCall(Program p, CM selected, List<T> actuals) {
    if (actuals.stream().noneMatch(t -> containsTransient(p, t))) { return Optional.empty(); }
    if (!isStorageLikeCall(selected)) { return Optional.empty(); }
    return actuals.stream()
      .filter(t -> containsTransient(p, t))
      .findFirst()
      .map(t -> Fail.transientStorage(t, selected));
  }

  public static Optional<CompileError> checkArgumentsAllowedByOriginalParameters(Program p, CM selected, List<T> arguments) {
    var parameters = originalParameterTypes(selected);
    return Streams.zip(parameters, arguments)
      .filterMap((parameter, argument) -> checkArgumentAllowed(p, argument, parameter))
      .findFirst();
  }

  public static List<T> originalParameterTypes(CM selected) {
    return switch (selected) {
      case CM.CoreCM core -> core.m().sig().ts();
      case CM.FullCM full -> full.m().sig().orElseThrow().accept(visitors.InjectionVisitor.of()).ts();
    };
  }

  public static boolean isStorageLikeCall(CM selected) {
    var owner = selected.c().name();
    var meth = selected.name().name();
    if (owner.equals(Magic.Vars) || owner.equals(Magic.RefK) || owner.equals(Magic.IsoPodK)) { return true; }
    if (owner.equals(Magic.UListK) || owner.equals(Magic.FListK)) { return meth.equals("#") || meth.equals(".fromLList"); }
    if (owner.equals(Magic.UList)) {
      return Set.of("#", ".add", ".set", ".insert", ".update", "+", ":=").contains(meth);
    }
    if (owner.equals(Magic.FList)) {
      return Set.of("#", ".add", ".set", ".insert", ".update", "+").contains(meth);
    }
    return false;
  }

  private static boolean headImplementsTransient(Program p, Id.IT<T> it) {
    return headImplementsTransient(p, it, new HashSet<>());
  }

  private static boolean headImplementsTransient(Program p, Id.IT<T> it, Set<Id.DecId> seen) {
    if (!seen.add(it.name())) { return false; }
    if (it.name().equals(Magic.Transient)) { return true; }
    return p.itsOf(it).stream().anyMatch(parent -> headImplementsTransient(p, parent, seen));
  }

  private static boolean headImplementsTransient(Program p, T t) {
    return t.match(_ -> false, it -> headImplementsTransient(p, it));
  }

  public static SortedSet<String> capturedNames(E.Lambda lit) {
    var visitor = new FreeVariables();
    lit.accept(visitor);
    return Collections.unmodifiableSortedSet(visitor.res());
  }
}
