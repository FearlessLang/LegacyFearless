package magic;

import codegen.MIR;
import id.Id;

import java.util.List;
import java.util.Optional;

public interface MagicImpls<R> {
  /// The traits whose values the runtime makes itself, and that cannot carry the
  /// `base.RuntimeImplemented` marker.
  ///
  /// A literal type cannot carry the marker: its implementation is a clone
  /// {@link Magic#getDec} synthesises from a template, which would carry the marker into a
  /// lambda that the well-formedness rule on the marker forbids.
  ///
  /// A pass reads this to learn that a value of the trait can arrive with no object literal
  /// behind it, so an implementation set built from literals alone is incomplete for it. That is
  /// what keeps `base.Var` off the specialised reference-count operations: the runtime makes its
  /// cells, and their storage mode is not the storage mode of the Fearless lambda in `Vars#`.
  ///
  /// `base.Bool` is not here. The runtime makes no value of it and the backend answers no call on
  /// it: `True` and `False` are ordinary Fearless singletons with Fearless bodies, and where the
  /// runtime needs a boolean it returns one of those same two singletons.
  ///
  /// {@link #get} is not derived from this list and keeps its own if-chain.
  List<Id.DecId> MAGIC_DECS = List.of(
    Magic.Int, Magic.Nat, Magic.Float, Magic.Byte, Magic.Str, Magic.Var, Magic.IsoPod);

  default Optional<MagicTrait<MIR.E,R>> get(MIR.E e) {
    if (isMagic(Magic.Int, e)) { return Optional.ofNullable(int_(e)); }
    if (isMagic(Magic.Nat, e)) { return Optional.ofNullable(nat(e)); }
    if (isMagic(Magic.Float, e)) { return Optional.ofNullable(float_(e)); }
    if (isMagic(Magic.Byte, e)) { return Optional.ofNullable(byte_(e)); }
    if (isMagic(Magic.Str, e)) { return Optional.ofNullable(str(e)); }
    if (isMagic(Magic.Bool, e)) { return Optional.ofNullable(bool(e)); }
    if (isMagic(Magic.Debug, e)) { return Optional.ofNullable(debug(e)); }
    if (isMagic(Magic.Vars, e)) { return Optional.ofNullable(vars(e)); }
    if (isMagic(Magic.RefK, e)) { return Optional.ofNullable(refK(e)); }
    if (isMagic(Magic.IsoPodK, e)) { return Optional.ofNullable(isoPodK(e)); }
    if (isMagic(Magic.Assert, e)) { return Optional.ofNullable(assert_(e)); }
    if (isMagic(Magic.Abort, e)) { return Optional.ofNullable(abort(e)); }
    if (isMagic(Magic.MagicAbort, e)) { return Optional.ofNullable(magicAbort(e)); }
    if (isMagic(Magic.ErrorK, e)) { return Optional.ofNullable(errorK(e)); }
    if (isMagic(Magic.Try, e)) { return Optional.ofNullable(tryCatch(e)); }
    if (isMagic(Magic.PipelineParallelSinkK, e)) { return Optional.ofNullable(pipelineParallelSinkK(e)); }
    if (isMagic(Magic.DataParallelFlowK, e)) { return Optional.ofNullable(dataParallelFlowK(e)); }
    if (isMagic(Magic.FListK, e)) { return Optional.ofNullable(listK(e)); }
    if (isMagic(Magic.UListK, e)) { return Optional.ofNullable(uListK(e)); }
    if (isMagic(Magic.FlowK, e)) { return Optional.ofNullable(flowK(e)); }
    if (isMagic(Magic.FeartDriver, e)) { return Optional.ofNullable(feartDriverK(e)); }
    if (isMagic(Magic.FlowRange, e)) { return Optional.ofNullable(flowRange(e)); }
    if (isMagic(Magic.CheapHash, e)) { return Optional.ofNullable(cheapHash(e)); }
    if (isMagic(Magic.RegexK, e)) { return Optional.ofNullable(regexK(e)); }
    if (isMagic(Magic.UTF8, e)) { return Optional.ofNullable(utf8(e)); }
    if (isMagic(Magic.UTF16, e)) { return Optional.ofNullable(utf16(e)); }
    if (isMagic(Magic.MapK, e)) { return Optional.ofNullable(mapK(e)); }
    if (isMagic(Magic.BlackBox, e)) { return Optional.ofNullable(blackBox(e)); }
    return Optional.empty();
  }

  default boolean isMagic(Id.DecId magicDec, MIR.E e) {
    if (e.t().name().isEmpty()) { return false; }
    return isMagic(magicDec, e.t().name().get());
  }
  default boolean isMagic(Id.DecId magicDec, Id.DecId freshName) {
    return p().superDecIds(freshName).contains(magicDec);
//    if (freshName.gen() != magicDec.gen()) { return false; }
//    var gens = Id.GX.standardNames(freshName.gen()).stream().map(gx->new T(Mdf.mdf, gx)).toList();
//    return p().isSubType(XBs.empty(), new T(Mdf.mdf, new Id.IT<>(freshName, gens)), new T(Mdf.mdf, new Id.IT<>(magicDec, gens)));
  }

  MagicTrait<MIR.E,R> int_(MIR.E e);
  MagicTrait<MIR.E,R> nat(MIR.E e);
  MagicTrait<MIR.E,R> float_(MIR.E e);
  MagicTrait<MIR.E,R> byte_(MIR.E e);
  MagicTrait<MIR.E,R> str(MIR.E e);
  MagicTrait<MIR.E,R> asciiStr(MIR.E e);
  MagicTrait<MIR.E,R> debug(MIR.E e);
  MagicTrait<MIR.E,R> refK(MIR.E e);
  MagicTrait<MIR.E,R> isoPodK(MIR.E e);
  MagicTrait<MIR.E,R> assert_(MIR.E e);
  MagicTrait<MIR.E,R> cheapHash(MIR.E e);
  MagicTrait<MIR.E,R> regexK(MIR.E e);
  default MagicTrait<MIR.E,R> blackBox(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> utf8(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> utf16(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> bool(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> vars(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> abort(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> magicAbort(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> errorK(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> tryCatch(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> listK(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> uListK(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> flowK(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> feartDriverK(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> flowRange(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> pipelineParallelSinkK(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> dataParallelFlowK(MIR.E e) { return null; }
  default MagicTrait<MIR.E,R> mapK(MIR.E e) { return null; }
  default MagicCallable<MIR.E,R> variantCall(MIR.E e) { return null; }
  ast.Program p();

}
