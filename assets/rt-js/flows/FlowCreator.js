import { base$$Opt_1 } from "../../base/Opt_1.js";
import { base$$Opts_0 } from "../../base/Opts_0.js";
import { base$$flows$$_UnwrapFlowToken_0 } from "../../base/flows/_UnwrapFlowToken_0.js";
import { base$$flows$$_SeqFlow_0 } from "../../base/flows/_SeqFlow_0.js";
import { DataParallelFlowK } from "./dataParallel/DataParallelFlowK.js";
import { PipelineParallelFlowK } from "./pipelineParallel/PipelineParallelFlowK.js";

// We intentionally avoid enabling PipelineParallelFlow (PP) in the JS backend.
// The current single-threaded event loop cannot execute true pipeline parallelism,
// and simulating it with async queues would only add scheduling overhead without
// real concurrency benefits. For now, all flows are downgraded to sequential or
// data-parallel (DP) execution. PP remains reserved for a future runtime that
// supports worker threads or async pipelines with true overlap.

// Fake "ScopedValue" for sequentialisation toggle
let IS_SEQUENTIALISED = { bound: false };

export const FlowCreator = {
  IS_SEQUENTIALISED,

  /**
   * @param {base$$flows$$_FlowFactory_0} intended The flow factory the compiler intends for us to use
   * @param {base$$flows$$Flow_1} original The original flow that we are trying to promote
   * @return {base$$flows$$Flow_1} A flow that may or may not have been created with intended.
   *                  Only ever degrades flows (DP -> Seq), never upgrades.
   */
  fromFlow(intended, original) {
    const op = original.unwrapOp$mut$1(base$$flows$$_UnwrapFlowToken_0.$self);

    // size = long, or -1 if empty
    const size = original.size$read$0().match$imm$1({
      some$mut$1: (x) => (typeof x === "bigint" ? x : BigInt(x)),
      empty$mut$0: () => -1n,
    });
    const sizeNum = size < 0n ? -1 : Number(size);

    return FlowCreator.fromFlowOp(intended, op, sizeNum);
  },

  /**
   * @param {base$$flows$$_FlowFactory_0} intended
   * @param {base$$flows$$FlowOp_1} op
   * @param {number} size
   */
  fromFlowOp(intended, op, size) {
    const isSequentialised = IS_SEQUENTIALISED.bound;

    if (isSequentialised) {
      const optSize = size < 0 ? base$$Opt_1.$self : base$$Opts_0.$self.$hash$imm$1(size);
      return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(op, optSize);
    }

    // When infinite source & intended DP → switch to PP
    // if (op.isFinite$mut$0() === base$$False_0.$self && intended === DataParallelFlowK.$self) {
    //   return PipelineParallelFlowK.$self.fromOp$imm$2(op, base$$Opt_1.$self);
    // }

    if (size < 0) {
      return intended.fromOp$imm$2(op, base$$Opt_1.$self);
    }
    const optSize = base$$Opts_0.$self.$hash$imm$1(size);

    // Special-case fork/join
    if (size === 2) {
      return intended.fromOp$imm$2(op, optSize);
    }

    // small flows → Seq
    if (size <= 1) {
      return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(op, optSize);
    }

    // Small DP flows (but >1) → PP
    // if ((size < 4) && intended === DataParallelFlowK.$self) {
    //   return PipelineParallelFlowK.$self.fromOp$imm$2(op, optSize);
    // }


    // default: use what the compiler asked for
    return intended.fromOp$imm$2(op, optSize); // here
  },
};
