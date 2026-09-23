package rt.flows;

import base.Opt_1;
import base.Opts_0;
import base.flows.*;
import rt.flows.dataParallel.DataParallelFlowK;
import rt.flows.pipelineParallel.PipelineParallelFlowK;

public interface FlowCreator {
  ScopedValue<Void> IS_SEQUENTIALISED = ScopedValue.newInstance();

  /**
   * @param intended The flow factory the compiler intends for us to use
   * @param original The original flow that we are trying to promote
   * @return A flow that may or may not have been created with intended. This will only ever degrade a flow (i.e.
   * go from DP -> Seq) and never upgrade it (i.e. Seq -> DP).
   */
  static Flow_1 fromFlow(_FlowFactory_0 intended, Flow_1 original) {
//    System.out.println("from "+original+" intended "+intended);
    var op = original.unwrapOp$mut(_UnwrapFlowToken_0.$self);
    long size = (long) original.size$read().match$imm(new base.OptMatch_2(){
      @Override public Object some$mut(Object x) { return x; }
      @Override public Object empty$mut() { return -1L; }
    });
    return fromFlowOp(intended, op, size);
  }

  /// A sequential flow over the ops of `original`. For a DP flow, all ops become sequential.
  /// For a PP flow, the upstream stages stay and the later ops run on one thread.
  static Flow_1 sequential(Flow_1 original) {
    var size = original.size$read();
    var op = original.unwrapOp$mut(_UnwrapFlowToken_0.$self);
    return _SeqFlow_0.$self.fromOp$imm(op, size);
  }

  static Flow_1 fromFlowOp(_FlowFactory_0 intended, FlowOp_1 op, long size) {
    var isSequentialised = IS_SEQUENTIALISED.isBound();
    if (isSequentialised) {
      Opt_1 optSize = size < 0 ? Opt_1.$self : Opts_0.$self.$hash$imm(size);
      return _SeqFlow_0.$self.fromOp$imm(op, optSize);
    }
    if (op.isFinite$mut() == base.False_0.$self && intended instanceof DataParallelFlowK) {
      return PipelineParallelFlowK.$self.fromOp$imm(op, Opt_1.$self);
    }
    if (size < 0) { return intended.fromOp$imm(op, Opt_1.$self); }
    var optSize = Opts_0.$self.$hash$imm(size);
//    if (op.canSplit$read() == base.False_0.$self && intended instanceof DataParallelFlowK) {
//      return _SeqFlow_0.$self.fromOp$imm(op, optSize);
//    }

    var couldBeForkJoinAttempt = size == 2;
    if (couldBeForkJoinAttempt) {
      return intended.fromOp$imm(op, optSize);
    }

    if (size <= 1) {
      return _SeqFlow_0.$self.fromOp$imm(op, optSize);
    }

    if (size < 4 && intended instanceof DataParallelFlowK) {
      return PipelineParallelFlowK.$self.fromOp$imm(op, optSize);
    }

    return intended.fromOp$imm(op, optSize);
  }
}
