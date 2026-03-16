import { FlowCreator } from "./flows/FlowCreator.js";
import { DataParallelFlowK } from "./flows/dataParallel/DataParallelFlowK.js";
import { PipelineParallelFlow } from "./flows/pipelineParallel/PipelineParallelFlow.js";
import { Range } from "./flows/Range.js";

export const rt$$flows = {
  FlowCreator,
  pipelineParallel: {
    PipelineParallelFlow
  },
  dataParallel: {
    DataParallelFlowK
  },
  Range,
};
