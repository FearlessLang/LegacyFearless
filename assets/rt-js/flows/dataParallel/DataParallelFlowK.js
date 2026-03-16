import { DataParallelFlow } from "./DataParallelFlow.js";
import { base$$flows$$_RestrictFlowReuse_0 } from "../../../base/flows/_RestrictFlowReuse_0.js";

export const DataParallelFlowK = {
  $self: null, // we fill it later
  fromOp$imm$2: (source_m$, size_m$) => {
    const flow = new DataParallelFlow(source_m$, size_m$, DataParallelFlowK.$self);
    return base$$flows$$_RestrictFlowReuse_0.$self.$hash$imm$1(flow);
  }
};

// set $self to refer to the object itself
DataParallelFlowK.$self = DataParallelFlowK;
