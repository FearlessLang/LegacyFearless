package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import magic.Magic;
import magic.MagicImpls;
import utils.Bug;
import utils.Streams;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// TODO: also optimise Block#(...)
public class BlockOptimisation implements
  MIRCloneVisitor,
  FlattenChain<MIR.Block.BlockStmt, Class<? extends MIR.Block.BlockStmt>, MIR.Block>
{
  private final MagicImpls<?> magic;
  private Map<MIR.FName, MIR.Fun> funs;
  public BlockOptimisation(MagicImpls<?> magic) {
    this.magic = magic;
  }

  @Override public MIR.Package visitPackage(MIR.Package pkg) {
    this.funs = pkg.funs().stream().collect(Collectors.toMap(MIR.Fun::name, Function.identity()));
    return MIRCloneVisitor.super.visitPackage(pkg);
  }

  @Override public MIR.Fun visitFun(MIR.Fun fun) {
    if (!(fun.body() instanceof MIR.MCall call)) { return MIRCloneVisitor.super.visitFun(fun); }
    var isBlock = this.magic.isMagic(Magic.Block, call.recv());
    if (!isBlock) {
      return MIRCloneVisitor.super.visitFun(fun);
    }
    return this.visitFluentCall(call, List.of(MIR.Block.BlockStmt.Return.class), Optional.empty())
      .map(fun::withBody)
      .orElse(MIRCloneVisitor.super.visitFun(fun));
  }

  @Override public Optional<MIR.Block> visitFluentCall(
    MIR.MCall call,
    List<Class<? extends MIR.Block.BlockStmt>> validEndings,
    Optional<MIR.X> self
  ) {
    var stmts = new ArrayDeque<MIR.Block.BlockStmt>();
    var eagerStmts = new ArrayDeque<MIR.Block.BlockStmt>();
    var res = flatten(call, stmts, eagerStmts, self);
    if (res == FlattenStatus.INVALID) { return Optional.empty(); }
    if (validEndings.stream().noneMatch(c->c.isAssignableFrom(stmts.getLast().getClass()))) { return Optional.empty(); }
    assert res == FlattenStatus.FLATTENED;
    var allStmts = Stream.concat(eagerStmts.stream(), stmts.stream()).toList();
    return Optional.of(new MIR.Block(call, allStmts, call.t()));
  }

  @Override public FlattenStatus flatten(
    MIR.E expr,
    Deque<MIR.Block.BlockStmt> stmts,
    Deque<MIR.Block.BlockStmt> eagerStmts,
    Optional<MIR.X> self
  ) {
    return switch (expr) {
      case MIR.MCall mCall -> {
        if (mCall.name().equals(new Id.MethName("#", 0)) && this.magic.isMagic(Magic.BlockK, mCall.recv())) {
          yield FlattenStatus.FLATTENED;
        }
        if (mCall.name().equals(new Id.MethName(".return", 1))) {
          var res = this.visitReturn(mCall.args().getFirst());
          if (res.isEmpty()) { yield  FlattenStatus.INVALID; }
          stmts.offerFirst(new MIR.Block.BlockStmt.Return(res.get()));
        } else if (mCall.name().equals(new Id.MethName(".do", 1))) {
          var res = this.visitReturn(mCall.args().getFirst());
          if (res.isEmpty()) {
            yield FlattenStatus.INVALID;
          }
          stmts.offerFirst(new MIR.Block.BlockStmt.Do(res.get()));
        } else if (mCall.name().equals(new Id.MethName(".error", 1))) {
          var res = this.visitReturn(mCall.args().getFirst());
          if (res.isEmpty()) {
            yield FlattenStatus.INVALID;
          }
          stmts.offerFirst(new MIR.Block.BlockStmt.Throw(res.get()));
        } else if (mCall.name().equals(new Id.MethName(".loop", 1))) {
          // The block function is often implemented with a Block itself, so leaving it
          // separate lets this optimisation apply to it too.
          stmts.offerFirst(new MIR.Block.BlockStmt.Loop(mCall.args().getFirst()));
        } else if (mCall.name().equals(new Id.MethName(".if", 1))) {
          var res = this.visitReturn(mCall.args().getFirst());
          if (res.isEmpty()) {
            yield FlattenStatus.INVALID;
          }
          stmts.offerFirst(new MIR.Block.BlockStmt.If(res.get()));
        } else if (mCall.name().equals(new Id.MethName(".let", 2))) {
          var variable = this.visitReturn(mCall.args().getFirst());
          var continuationCall = this.visitVarContinuation(mCall.args().get(1));
          if (variable.isEmpty() || continuationCall.isEmpty()) { yield FlattenStatus.INVALID; }
          var continuation = this.visitFluentCall(
            continuationCall.get().continuationCall(),
            List.of(MIR.Block.BlockStmt.Return.class, MIR.Block.BlockStmt.Do.class), // TODO: and .error when we have that
            Optional.of(continuationCall.get().selfVar())
          );
          if (continuation.isEmpty()) { yield FlattenStatus.INVALID; }
          stmts.offerFirst(new MIR.Block.BlockStmt.Let(continuationCall.get().var().name(), variable.get()));
          continuation.get().stmts().forEach(stmts::offerLast);
        } else if (mCall.name().equals(new Id.MethName(".var", 2))) {
          var variable = this.visitReturn(mCall.args().getFirst());
          var continuationCall = this.visitVarContinuation(mCall.args().get(1));
          if (variable.isEmpty() || continuationCall.isEmpty()) {yield FlattenStatus.INVALID;}
          var continuation = this.visitFluentCall(
            continuationCall.get().continuationCall(),
            List.of(MIR.Block.BlockStmt.Return.class, MIR.Block.BlockStmt.Do.class), // TODO: and .error when we have that
            Optional.of(continuationCall.get().selfVar())
          );
          if (continuation.isEmpty()) {yield FlattenStatus.INVALID;}
          stmts.offerFirst(new MIR.Block.BlockStmt.Var(continuationCall.get().var().name(), variable.get()));
          continuation.get().stmts().forEach(stmts::offerLast);
        } else if (mCall.name().equals(new Id.MethName(".openIso", 2))) {
          var continuationCall = this.visitVarContinuation(mCall.args().get(1));
          if (continuationCall.isEmpty()) {yield FlattenStatus.INVALID;}
          var continuation = this.visitFluentCall(
            continuationCall.get().continuationCall(),
            List.of(MIR.Block.BlockStmt.Return.class, MIR.Block.BlockStmt.Do.class), // TODO: and .error when we have that
            Optional.of(continuationCall.get().selfVar())
          );
          if (continuation.isEmpty()) {yield FlattenStatus.INVALID;}
          eagerStmts.offerFirst(new MIR.Block.BlockStmt.Let(continuationCall.get().var().name(), mCall.args().getFirst()));
          continuation.get().stmts().forEach(stmts::offerLast);
        } else {
          // TODO: .assert
          yield FlattenStatus.INVALID;
        }
        yield flatten(mCall.recv(), stmts, eagerStmts, self);
      }
      case MIR.BoolExpr _ -> throw Bug.todo();
      case MIR.X x -> self.filter(x::equals).map(_->FlattenStatus.FLATTENED).orElse(FlattenStatus.INVALID);
      case MIR.CreateObj ignored -> FlattenStatus.INVALID;
      case MIR.StaticCall ignored -> throw Bug.unreachable();
      case MIR.DirectCall ignored -> throw Bug.unreachable();
      case MIR.Block ignored -> throw Bug.unreachable();
      case MIR.UpdatableListAsIdFnCall ignored -> throw Bug.unreachable();
      case MIR.Box box -> flatten(box.inner(), stmts, eagerStmts, self);
    };
  }

  private Optional<MIR.E> visitReturn(MIR.E fn) {
    if (!(fn instanceof MIR.CreateObj k)) { return Optional.empty(); }
    if (!this.magic.isMagic(Magic.ReturnStmt, k)) { return Optional.empty(); }
    if (k.meths().size() != 1) { return Optional.empty(); }
    var m = k.meths().getFirst();
    assert m.sig().name().equals(new Id.MethName("#", 0));
    var body = this.funs.get(m.fName().orElseThrow()).body();
    return Optional.of(body);
  }
  private record VarContinuation(MIR.X var, MIR.X selfVar, MIR.MCall continuationCall) {}
  private Optional<VarContinuation> visitVarContinuation(MIR.E fn) {
    if (!(fn instanceof MIR.CreateObj k)) { return Optional.empty(); }
    if (!this.magic.isMagic(Magic.Continuation, k)) { return Optional.empty(); }
    if (k.meths().size() != 1) { return Optional.empty(); }
    var m = k.meths().getFirst();
    assert m.sig().name().equals(new Id.MethName("#", 2));
    var body = (MIR.MCall) this.funs.get(m.fName().orElseThrow()).body();
    return Optional.of(new VarContinuation(m.sig().xs().getFirst(), m.sig().xs().get(1), body));
  }
}
