// rt/flows/pipelineParallel/ConvertFromDataParallel.js
import { base$$flows$$_PipelineParallelFlow_0 } from "../../../base/flows/_PipelineParallelFlow_0.js";
import { base$$flows$$FlowOp_1 } from "../../../base/flows/FlowOp_1.js";
import { base$$True_0 } from "../../../base/True_0.js";
import { base$$False_0 } from "../../../base/False_0.js";
import { base$$Opt_1 } from "../../../base/Opt_1.js";
import { base$$Void_0 } from "../../../base/Void_0.js";
import { PipelineParallelFlow } from "./PipelineParallelFlow.js";

/* ---------------- ConvertFromDataParallel ---------------- */
export const ConvertFromDataParallel = {
  /**
   * @param {DataParallelFlow} source
   * @param {base$$Opt_1} size
   */
  of(source, size) {
    const dpSource = source.getDataParallelSource();
    const dpConsumer = new DPSource(dpSource);
    return base$$flows$$_PipelineParallelFlow_0.$self.fromOp$imm$2(
      dpConsumer,
      size
    );
  }
};

/* ---------------- DPSource ---------------- */
class DPSource {
  constructor(source) {
    this.buffer = [];
    this.waiters = [];
    this.isRunning = true;
    this.hasStarted = false;
    this.source = source;

    this._deliveryScheduled = false;

    // NEW: once true, no more data/errors are forwarded after Stop
    this._stopped = false;
  }

  async start() {
    if (this.hasStarted) throw new Error("start called twice on DPSource");
    this.hasStarted = true;

    (async () => {
      await this.source.for$mut$1({
        stopDown$mut$0: () => {
          // Mark hard-stopped, then enqueue Stop exactly once
          if (!this._stopped) {
            this._stopped = true;
            this._offer(PipelineParallelFlow.Message.Stop.INSTANCE);
          }
          return base$$Void_0.$self;
        },
        pushError$mut$1: info => {
          // If you want: stop the bridge on error too
          if (this._stopped) return base$$Void_0.$self;
          this._offer(new PipelineParallelFlow.Message.Error(info));
          return base$$Void_0.$self;
        },
        $hash$mut$1: x => {
          // Drop data after Stop
          if (this._stopped) return base$$Void_0.$self;
          this._offer(x);
          return base$$Void_0.$self;
        }
      });
    })();
  }

  _offer(item) {
    if (this.waiters.length > 0) this.waiters.shift()(item);
    else this.buffer.push(item);
  }
  async _take() {
    if (this.buffer.length > 0) return this.buffer.shift();
    return new Promise(r => this.waiters.push(r));
  }
  _poll() { return this.buffer.length > 0 ? this.buffer.shift() : undefined; }

  isFinite$mut$0() { return this.source.isFinite$mut$0(); }

  // Synchronous, non-blocking step (as you have now)
  step$mut$1(sink_m$) {
    if (this.isRunning$mut$0() === base$$False_0.$self) return base$$Void_0.$self;
    if (!this.hasStarted) this.start();

    const msg = this._poll();
    if (msg !== undefined) {
      sink_m$.subject.submit(msg);
      if (msg === PipelineParallelFlow.Message.Stop.INSTANCE) {
        this.isRunning = false;
        this.stopUp$mut$0();
      }
      return base$$Void_0.$self;
    }

    if (!this._deliveryScheduled) {
      this._deliveryScheduled = true;
      (async () => {
        try {
          const m = await this._take();
          sink_m$.subject.submit(m);
          if (m === PipelineParallelFlow.Message.Stop.INSTANCE) {
            this.isRunning = false;
            this.stopUp$mut$0();
          }
        } finally {
          this._deliveryScheduled = false;
        }
      })().catch(() => {});
    }

    return base$$Void_0.$self;
  }

  stopUp$mut$0() {
    this.isRunning = false;
    this.source.stopUp$mut$0();
    return base$$Void_0.$self;
  }
  isRunning$mut$0() { return this.isRunning ? base$$True_0.$self : base$$False_0.$self; }
  for$mut$1(downstream_m$) { return base$$flows$$FlowOp_1.for$mut$2$fun(downstream_m$, this); }
  split$mut$0() { return base$$Opt_1.$self; }
  canSplit$read$0() { return base$$False_0.$self; }
}
