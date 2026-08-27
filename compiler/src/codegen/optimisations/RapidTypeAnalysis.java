package codegen.optimisations;

import codegen.MIR;
import id.Id;
import magic.Magic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Rapid type analysis over a {@link MIR.Program}.
///
/// The table maps a declared type to every concrete type that can flow into a receiver of
/// it. A concrete type enters from an object literal ({@link MIR.CreateObj}) or from the
/// fixed set the runtime provides ({@link Magic}). One entry with an object literal behind
/// it means a monomorphic receiver.
///
/// The table covers the whole compilation only for a package the compiler parsed in full. A
/// package the front end reads back from its `pkgInfo` cache contributes declarations whose
/// method bodies are all `base.Abort!`, so its object literals are absent and every impl set
/// it would have filled is partial. A receiver such a package declares can therefore look
/// monomorphic here while the real program has many implementations of it, which makes an
/// answer about one unsound to act on. It is also where a runtime-backed type is declared,
/// and one of those has no object literal for the table to record at all.
///
/// A caller must refuse those packages itself by declaring a package, which is enough because a
/// literal such a package owns can only register against a supertype that same package owns.
/// {@link RcFreeTypes} takes the set of them for exactly this.
///
/// Covering the compilation is not the same as covering every compilation. Text a
/// {@link main.CompilationUnit} writes is read back by later ones, which hold packages this
/// table never saw, so an answer written into such text needs a guarantee from the declaration
/// on top of this table.
public final class RapidTypeAnalysis {
  private final Map<Id.DecId, Set<Id.DecId>> impls = new HashMap<>();
  private final Map<Id.DecId, MIR.CreateObj> literals = new HashMap<>();
  private final Set<Id.DecId> directCallTargets = new HashSet<>();

  public RapidTypeAnalysis(MIR.Program p) {
    // The runtime provides these without an object literal. Recording them keeps a declared
    // type that a runtime-backed type implements out of the monomorphic set.
    for (var magicDec : Magic.allMagicDecs()) {
      // `superDecIds` needs a declaration, which a program that never imports the magic
      // type does not have.
      if (!p.p().ds().containsKey(magicDec)) { continue; }
      record(p, magicDec);
    }
    for (var pkg : p.pkgs()) {
      for (var def : pkg.defs().values()) {
        def.singletonInstance().ifPresent(k -> collect(p, k));
      }
      for (var fun : pkg.funs()) {
        collect(p, fun.body());
      }
    }
  }

  /// The one concrete type behind `declared`, when an object literal backs it. Empty for a
  /// polymorphic or runtime-backed receiver.
  public Optional<MIR.CreateObj> monomorphicImpl(Id.DecId declared) {
    var candidates = impls.get(declared);
    if (candidates == null || candidates.size() != 1) { return Optional.empty(); }
    return Optional.ofNullable(literals.get(candidates.iterator().next()));
  }

  /// Every concrete type that can flow into a receiver declared `declared`, empty when
  /// nothing was seen creating one.
  public Set<Id.DecId> implsOf(Id.DecId declared) {
    return impls.getOrDefault(declared, Set.of());
  }

  /// The object literal behind a concrete type, empty for one the runtime provides itself.
  public Optional<MIR.CreateObj> literalOf(Id.DecId concrete) {
    return Optional.ofNullable(literals.get(concrete));
  }

  private void record(MIR.Program p, Id.DecId concrete) {
    for (var sup : p.p().superDecIds(concrete)) {
      impls.computeIfAbsent(sup, ignored -> new HashSet<>()).add(concrete);
    }
  }

  /// Every object literal of one expression tree. A `BoolExpr` arm is a separate `Fun` that
  /// the package walk reaches on its own, so this does not follow arms.
  private void collect(MIR.Program p, MIR.E e) {
    // An MIR body nests deeply, so recursion is not an option here.
    var work = new ArrayList<MIR.E>();
    work.add(e);
    while (!work.isEmpty()) {
      var next = work.removeLast();
      switch (next) {
        case MIR.CreateObj k -> {
          var id = k.concreteT().id();
          if (literals.putIfAbsent(id, k) == null) { record(p, id); }
          work.addAll(k.captures());
        }
        case MIR.X ignored -> {}
        case MIR.MCall call -> {
          work.add(call.recv());
          work.addAll(call.args());
        }
        case MIR.DirectCall call -> {
          directCallTargets.add(call.concreteType());
          work.add(call.original());
        }
        // A guarded call names the wrapper of its guess, so that type needs its wrapper for
        // the same reason a direct call does.
        case MIR.GuardedCall call -> {
          directCallTargets.add(call.concreteType());
          work.add(call.original());
        }
        case MIR.StaticCall call -> {
          work.add(call.original());
          work.addAll(call.args());
        }
        case MIR.UpdatableListAsIdFnCall call -> work.add(call.e());
        case MIR.Box box -> work.add(box.inner());
        case MIR.Block block -> {
          work.add(block.original());
          block.stmts().forEach(stmt -> work.add(stmt.e()));
        }
        case MIR.BoolExpr expr -> {
          work.add(expr.original());
          work.add(expr.condition());
        }
      }
    }
  }

  /// Every concrete type a {@link MIR.DirectCall} names, with its object literal. A backend
  /// that emits per-literal wrappers lazily must force each one, because a call site can name
  /// a literal its own package never creates.
  public List<Map.Entry<Id.DecId, MIR.CreateObj>> directCallTargets() {
    return directCallTargets.stream()
      .filter(literals::containsKey)
      .map(id -> Map.entry(id, literals.get(id)))
      .toList();
  }
}
