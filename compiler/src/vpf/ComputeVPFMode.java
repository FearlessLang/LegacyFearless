package vpf;

import ast.E;
import ast.T;
import failure.FailOr;
import id.Mdf;
import program.typesystem.ETypeSystem;
import program.typesystem.Gamma;
import program.typesystem.TsT;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface ComputeVPFMode {
  /// Can a method call be parallelised via VPF?
  /// Parallelisation of a method call can happen if there are two or more arguments (including the receiver) that are
  /// MCalls and either:
  /// A. All arguments can be type-checked in an environment where all mut and mutH bindings are weakened to read and
  /// readH respectively.
  /// B. All arguments except one can be type-checked in an environment where all mut, mutH, read, and readH bindings are
  /// not present in Gamma.
  static VPFCallMode of(ETypeSystem ts, E.MCall call) {
    // must have at least 2 sub-calls (incl. receiver)
    var subCalls = callArgsInclRecv(call)
      .filter(e -> e instanceof E.MCall)
      .limit(2)
      .count();
    if (subCalls != 2) {
      return VPFCallMode.Sequential;
    }

    var canPromoteNoMut = canPromoteNoMut(ts, call);
    if (canPromoteNoMut == VPFCallMode.Parallel) {
      return canPromoteNoMut;
    }
    var canPromoteOnlyOneLikeMut = canPromoteOnlyOneLikeMut(ts, call);
    if (canPromoteOnlyOneLikeMut == VPFCallMode.Parallel) {
      return canPromoteOnlyOneLikeMut;
    }
    return VPFCallMode.Sequential;
  }

  /** Create a disposable ETypeSystem with a fresh resolvedCalls map so VPF probing doesn't corrupt the real one. */
  private static ETypeSystem disposableTs(ETypeSystem ts, Gamma g) {
    return ETypeSystem.of(ts.p(), g, ts.xbs(), ts.expectedT(), new ConcurrentHashMap<>(), ts.cache(), ts.depth());
  }

  private static VPFCallMode canPromoteNoMut(ETypeSystem ts, E.MCall call) {
    var g = ts.g();
    var weakenedGamma = new Gamma(){
      @Override public FailOr<Optional<T>> getO(String s) {
        return g.getO(s).map(optT->optT
          .filter(t->t.mdf() != Mdf.mdf)
          .map(t->switch (t.mdf()) {
            case mut -> t.withMdf(Mdf.read);
            case mutH -> t.withMdf(Mdf.readH);
            default -> t;
          })
        );
      }
      @Override public String toStr() {
        return "canPromoteNoMut:"+g.toStr();
      }
      @Override public List<String> dom() {
        return g.dom();
      }
    };

    ETypeSystem stricterTs = disposableTs(ts, weakenedGamma);
    var isPromotable = callArgsInclRecv(call)
      .allMatch(arg -> arg.accept(stricterTs).isRes());
    return isPromotable ? VPFCallMode.Parallel : VPFCallMode.Sequential;
  }

  private static VPFCallMode canPromoteOnlyOneLikeMut(ETypeSystem ts, E.MCall call) {
    var g = ts.g();
    var weakenedGamma = new Gamma(){
      @Override public FailOr<Optional<T>> getO(String s) {
        return g.getO(s).map(optT->optT
          .filter(t->t.mdf() != Mdf.mdf && !t.mdf().isLikeMut())
        );
      }
      @Override public String toStr() {
        return "canPromoteOnlyOneLikeMut:"+g.toStr();
      }
      @Override public List<String> dom() {
        return g.dom();
      }
    };

    ETypeSystem stricterTs = disposableTs(ts, weakenedGamma);

    var allArgs = callArgsInclRecv(call).toList();
    var allOk = IntStream.range(0, allArgs.size())
      .anyMatch(i -> IntStream.range(0, allArgs.size())
        .filter(j -> j != i)
        .mapToObj(j -> allArgs.get(j).accept(stricterTs))
        .allMatch(FailOr::isRes)
      );
    return allOk ? VPFCallMode.Parallel : VPFCallMode.Sequential;
  }

  private static Stream<E> callArgsInclRecv(E.MCall call) {
    return Stream.concat(Stream.of(call.receiver()), call.es().stream());
  }
}
