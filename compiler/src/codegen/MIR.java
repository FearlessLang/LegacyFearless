package codegen;

import ast.T;
import id.Id;
import id.Mdf;
import magic.LiteralKind;
import program.CM;
import utils.Bug;
import visitors.MIRVisitor;

import java.util.*;

public sealed interface MIR {
  sealed interface E extends MIR {
    MT t();
    <R> R accept(MIRVisitor<R> v, boolean checkMagic);
  }

  static TreeSet<X> createCapturesSet() {
    return new TreeSet<>(Comparator.comparing(X::name));
  }

  record Program(ast.Program p, List<Package> pkgs) implements MIR {
    public TypeDef of(Id.DecId id) {
      return pkgs.stream()
        .filter(pkg->pkg.defs.containsKey(id))
        .map(pkg->pkg.defs.get(id))
        .findFirst()
        .orElseThrow();
    }
  }
  record Package(String name, Map<Id.DecId, TypeDef> defs, List<Fun> funs) implements MIR {}
  record TypeDef(Id.DecId name, List<MT.Plain> impls, List<Sig> sigs, Optional<CreateObj> singletonInstance) implements MIR {
    public TypeDef {
      // Literals conflict on methods, so no literal is compatible with another.
      // Mearless relies on this.
      assert impls.stream()
        .map(MT.Plain::id)
        .map(Id.DecId::name)
        .filter(LiteralKind::isLiteral)
        .count() <= 1;
    }
  }
  record Fun(FName name, List<X> args, MT ret, E body) implements MIR {
    public Fun withBody(E body) { return new Fun(name, args, ret, body); }
  }
  record CreateObj(MT t, String selfName, List<Meth> meths, List<Meth> unreachableMs, SortedSet<X> captures) implements E {
    public CreateObj {
      captures = Collections.unmodifiableSortedSet(captures);
    }

    public MT.Plain concreteT() {
      return switch (t) {
        case MT.Any ignored -> throw Bug.unreachable();
        case MT.Plain plain -> plain;
        case MT.Usual usual -> new MT.Plain(usual.mdf(), usual.it().name());
      };
    }
    private static T makeT(Mdf mdf, Id.DecId def){
      return new T(mdf, new Id.IT<>(def, Id.GX.standardNames(def.gen()).stream().map(gx->new T(Mdf.mdf, gx)).toList()));
    }
    public CreateObj(Mdf mdf, Id.DecId def) {      
      this(
        new MT.Usual(makeT(mdf,def)),
        astFull.E.X.freshName(),
        List.of(),
        List.of(),
        Collections.unmodifiableSortedSet(createCapturesSet())
      );
    }

    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitCreateObj(this, checkMagic);
    }
  }
  record Meth(Id.DecId origin, Sig sig, boolean capturesSelf, SortedSet<String> captures, Optional<FName> fName) implements MIR {
    public Meth withSig(Sig sig) {
      return new Meth(origin, sig, capturesSelf, captures, fName);
    }
    public Meth withName(Id.MethName name) {
      return this.withSig(this.sig.withName(name));
    }
  }
  record Sig(Id.MethName name, List<X> xs, MT rt) implements MIR {
    public Mdf mdf() { return name.mdf().orElseThrow(); }
    public Sig withName(Id.MethName name) {
      return new Sig(name, xs, rt);
    }
    public Sig withRT(MT rt) {
      return new Sig(name, xs, rt);
    }
  }
  record X(String name, MT t) implements E {
    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitX(this, checkMagic);
    }
  }
  record MCall(E recv, Id.MethName name, List<? extends E> args, MT t, MT originalRet, Mdf mdf, EnumSet<CallVariant> variant) implements E {
    public MCall(E recv, Id.MethName name, List<? extends E> args, MT t, Mdf mdf, EnumSet<CallVariant> variant) {
      this(recv, name, args, t, t, mdf, variant);
    }

    public MCall withRecv(E recv) {
      return new MCall(recv, name, args, t, originalRet, mdf, variant);
    }

    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitMCall(this, checkMagic);
    }
    public MCall withVariants(EnumSet<CallVariant> variant) {
      return new MCall(recv, name, args, t, originalRet, mdf, variant);
    }

    public enum CallVariant {
      Standard,
      PipelineParallelFlow,
      DataParallelFlow,
      SafeMutSourceFlow,
      VPFParallelisable;

      public boolean isStandard() { return this == Standard; }
    }
    public boolean canParallelise() {
      return variant.contains(CallVariant.PipelineParallelFlow) || variant.contains(CallVariant.DataParallelFlow);
    }
  }

  record FName(Id.DecId d, Id.MethName m, boolean capturesSelf, Mdf mdf) {
    public FName(CM cm, boolean capturesSelf) {
      this(cm.c().name(), cm.name(), capturesSelf, cm.mdf());
    }
  }

  record BoolExpr(E original, E condition, FName then, FName else_) implements E {
    public BoolExpr {
      assert condition.t().name().isPresent() && (
        condition.t().name().get().equals(new Id.DecId("base.True", 0))
        || condition.t().name().get().equals(new Id.DecId("base.False", 0))
        || condition.t().name().get().equals(new Id.DecId("base.Bool", 0))
      );
    }
    @Override public MT t() {
      return original.t();
    }
    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitBoolExpr(this, checkMagic);
    }
  }

  record Block(E original, Collection<BlockStmt> stmts, MT expectedT) implements E {
    public sealed interface BlockStmt {
      E e();
      BlockStmt withE(E e);
      record Return(E e) implements BlockStmt {
        @Override public Return withE(E e) { return new Return(e); }
      }
      record Do(E e) implements BlockStmt {
        @Override public Do withE(E e) { return new Do(e); }
      }
      record Throw(E e) implements BlockStmt {
        @Override public Throw withE(E e) { return new Throw(e); }
      }
      record Loop(E e) implements BlockStmt {
        @Override public Loop withE(E e) { return new Loop(e); }
      }
      record If(E pred) implements BlockStmt {
        @Override public E e() { return pred; }
        @Override public If withE(E e) { return new If(e); }
      }
      record Let(String name, E value) implements BlockStmt {
        @Override public E e() { return value; }
        @Override public Let withE(E e) { return new Let(name, e); }
      }
      record Var(String name, E value) implements BlockStmt {
        @Override public E e() { return value; }
        @Override public Var withE(E e) { return new Var(name, e); }
      }
    }

    public Block withStmts(Collection<BlockStmt> stmts) {
      return new Block(original, stmts, expectedT);
    }

    @Override public MT t() {
      return original.t();
    }
    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitBlockExpr(this, checkMagic);
    }
  }

  record StaticCall(E original, FName fun, List<E> args, Optional<MT> castTo) implements E {

    @Override public MT t() {
      return original.t();
    }

    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitStaticCall(this, checkMagic);
    }
  }

  /// A call whose receiver is exactly one concrete literal, so nothing reads the
  /// vtable. Codegen emits a direct call to the per-literal method wrapper of
  /// {@code concreteType}, which takes `(receiver, args...)` and reads the captures out
  /// of the receiver. A backend with no such wrapper falls back to {@link #original()}.
  record DirectCall(MCall original, Id.DecId concreteType) implements E {
    @Override public MT t() { return original.t(); }

    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitDirectCall(this, checkMagic);
    }
  }

  record UpdatableListAsIdFnCall(MIR.MCall e) implements E {
    @Override public MT t() {return e.t();}
    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitUpdatableListAsIdFnCall(this, checkMagic);
    }
  }

  record Box(E inner) implements E {
    @Override public MT t() { return inner.t(); }
    @Override public <R> R accept(MIRVisitor<R> v, boolean checkMagic) {
      return v.visitBox(this, checkMagic);
    }
  }

  sealed interface MT {
    Mdf mdf();
    Optional<Id.DecId> name();
    default boolean isAny() { return false; }
    static MT of(T t) {
      return t.match(gx->new Any(t.mdf()), it->new Usual(t));
    }
    record Usual(T t) implements MT {
      public Usual {
        assert t.isIt();
      }
      public Mdf mdf() {
        return t.mdf();
      }
      public Id.IT<T> it() {
        return (Id.IT<T>) t.rt();
      }
      public Optional<Id.DecId> name() { return Optional.of(it().name()); }

      @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MT mt)) { return false; }
        if (mt.name().isEmpty()) { return false; }
        return this.t().mdf().equals(mt.mdf()) && this.it().name().equals(mt.name().get());
      }
      @Override public int hashCode() {
        return Objects.hash(this.t.mdf(), this.it().name());
      }
    }
    record Plain(Mdf mdf, Id.DecId id) implements MT {
      public Optional<Id.DecId> name() { return Optional.of(id); }
      @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MT mt)) { return false; }
        if (mt.name().isEmpty()) { return false; }
        return this.mdf().equals(mt.mdf()) && this.id.equals(mt.name().get());
      }
      @Override public int hashCode() {
        return Objects.hash(this.mdf(), this.id());
      }

    }
    record Any(Mdf mdf) implements MT {
      @Override public boolean isAny() { return true; }
      @Override public Optional<Id.DecId> name() {
        return Optional.empty();
      }
    }
  }
}
