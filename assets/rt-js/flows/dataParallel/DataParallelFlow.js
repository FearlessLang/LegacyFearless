import { base$$flows$$Flow_1 } from "../../../base/flows/Flow_1.js";
import { base$$flows$$_SeqFlow_0 } from "../../../base/flows/_SeqFlow_0.js";
import { base$$flows$$_TerminalOps_1 } from "../../../base/flows/_TerminalOps_1.js";
import { base$$flows$$_NonTerminalOps_1 } from "../../../base/flows/_NonTerminalOps_1.js";
import { base$$flows$$_With_0 } from "../../../base/flows/_With_0.js";
import { base$$flows$$_Map_0 } from "../../../base/flows/_Map_0.js";
import { base$$flows$$_AssumeFinite_1 } from "../../../base/flows/_AssumeFinite_1.js";
import { base$$flows$$_Filter_0 } from "../../../base/flows/_Filter_0.js";
import { base$$flows$$_FlatMap_0 } from "../../../base/flows/_FlatMap_0.js";
import { base$$Opt_1 } from "../../../base/Opt_1.js";
import { base$$Opts_0 } from "../../../base/Opts_0.js";
import { base$$Extensible_1 } from "../../../base/Extensible_1.js";
import { base$$OptFlatMap_2 } from "../../../base/OptFlatMap_2.js";
// import { ConvertFromDataParallel } from "../pipelineParallel/ConvertFromDataParallel.js";
// NOTE: ConvertFromDataParallel is intentionally not used here.
// In the current JavaScript runtime, DataParallelFlow operations are executed
// sequentially rather than through the pipeline-parallel bridge. Using
// ConvertFromDataParallel would introduce unnecessary async overhead and
// complexity for small or bounded flows. Once a true parallel scheduler or
// worker-based backend is implemented, these calls can be re-enabled.

// base/_SinkDecorator_0
const Ins_SinkDecorator_0 = {
  $hash$imm$1: s => s,
};

export class DataParallelFlow {
  constructor(source_m$, size_m$, $this) {
    this.source_m$ = source_m$;
    this.size_m$ = size_m$;
    this.$this = $this;

    this.size = this.size_m$.match$mut$1({
      some$mut$1: x => x,
      empty$mut$0: () => -1
    });
  }

  // Flow_1 helpers
  self$mut$0() { return base$$flows$$Flow_1.self$mut$1$fun(this); }
  self$read$0() { return base$$flows$$Flow_1.self$read$1$fun(this); }
  self$imm$0() { return base$$flows$$Flow_1.self$imm$1$fun(this); }
  let$mut$2(x_m$, cont_m$) { return base$$flows$$Flow_1.let$mut$3$fun(x_m$, cont_m$, this); }

  // actorMut$mut$2(state_m$, f_m$) { return ConvertFromDataParallel.of(this, this.size_m$).actorMut$mut$2(state_m$, f_m$); }
  // actor$mut$2(state_m$, f_m$) { return ConvertFromDataParallel.of(this, this.size_m$).actor$mut$2(state_m$, f_m$); }
  // limit$mut$1(n_m$) { return ConvertFromDataParallel.of(this, this.size_m$).limit$mut$1(n_m$); }
  actorMut$mut$2(state_m$, f_m$) { throw new Error("todo"); }
  actor$mut$2(state_m$, f_m$) { throw new Error("todo"); }
  // limit$mut$1(n_m$) { throw new Error("todo"); }
  limit$mut$1(n_m$) {
    const n = Number(n_m$);
    const source = this.getDataParallelSource();

    let count = 0;
    let stopped = false;

    // helper: create limited downstream
    const wrapDownstream = (downstream) => ({
      $hash$mut$1: (item) => {
        if (stopped) return;
        if (count < n) {
          downstream.$hash$mut$1(item);
          count++;
          if (count >= n) {
            stopped = true;
            // tell both directions to stop
            if (typeof downstream.stopDown$mut$0 === "function") downstream.stopDown$mut$0();
            if (typeof downstream.stopUp$mut$0 === "function") downstream.stopUp$mut$0();
            if (typeof source.stopUp$mut$0 === "function") source.stopUp$mut$0();
          }
        }
      },

      stopUp$mut$0: () => {
        stopped = true;
        if (typeof downstream.stopUp$mut$0 === "function") downstream.stopUp$mut$0();
      },

      stopDown$mut$0: () => {
        stopped = true;
        if (typeof downstream.stopDown$mut$0 === "function") downstream.stopDown$mut$0();
      },

      // $hash$mut$1: (self) => self,
    });

    const limitedSource = {
      ...source,
      // Forward iteration properly
      for$mut$1: (downstream) => {
        count = 0; stopped = false;
        // run underlying for$mut$1
        return source.for$mut$1(wrapDownstream(downstream));
      },
      step$mut$1: (sink) => {
        count = 0; stopped = false;
        // run underlying step$mut$1
        return source.step$mut$1(wrapDownstream(sink));
      },
      stopUp$mut$0: () => { stopped = true; if (source.stopUp$mut$0) source.stopUp$mut$0(); },
    };

    return this.$this.fromOp$imm$2(limitedSource, this.size_m$);
  }


  with$mut$1(other_m$) {
    const op = base$$flows$$_With_0.$self.$hash$imm$3(Ins_SinkDecorator_0, this.source_m$, other_m$.unwrapOp$mut$1(null));
    return this.$this.fromOp$imm$2(op, base$$Opt_1.$self);
  }

  first$mut$0() {
    return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(this.getDataParallelSource(), this.size_m$).first$mut$0();
  }
  last$mut$0() { return base$$flows$$_TerminalOps_1.last$mut$1$fun(this); }
  list$mut$0() { return base$$flows$$_TerminalOps_1.list$mut$1$fun(this); }
  all$mut$1(predicate_m$) {
    const res = this.filter$mut$1(x => predicate_m$.$hash$read$1(x).not$imm$0()).first$mut$0();
    return res.isEmpty$read$0();
  }
  none$mut$1(predicate_m$) { return base$$flows$$_TerminalOps_1.none$mut$2$fun(predicate_m$, this); }

  filter$mut$1(p_m$) { return this.$this.fromOp$imm$2(base$$flows$$_Filter_0.$self.$hash$imm$3(Ins_SinkDecorator_0, this.source_m$, p_m$), base$$Opt_1.$self); }
  flatMap$mut$1(f_m$) { return this.$this.fromOp$imm$2(base$$flows$$_FlatMap_0.$self.$hash$imm$3(Ins_SinkDecorator_0, this.source_m$, f_m$), base$$Opt_1.$self); }

  findMap$mut$1(f_m$) {
    return this
      .map$mut$1(f_m$)
      .filter$mut$1(res => res.isSome$read$0())
      .first$mut$0()
      .flatMap$mut$1({
        some$mut$1: x => base$$OptFlatMap_2.some$mut$2$fun(x, this),
        empty$mut$0: () => base$$OptFlatMap_2.empty$mut$1$fun(this),
        $hash$mut$1: t => t
      });
  }

  unwrapOp$mut$1(fear55$_m$) { return this.source_m$; }

  fold$mut$2(acc_m$, f_m$) {
    return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(this.getDataParallelSource(), this.size_m$).fold$mut$2(acc_m$, f_m$);
  }
  only$mut$0() { return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(this.getDataParallelSource(), this.size_m$).only$mut(); }
  get$mut$0() { return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(this.getDataParallelSource(), this.size_m$).get$mut(); }
  opt$mut$0() { return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(this.getDataParallelSource(), this.size_m$).opt$mut(); }

  map$mut$1(f_m$) { return this.$this.fromOp$imm$2(base$$flows$$_Map_0.$self.$hash$imm$3(Ins_SinkDecorator_0, this.source_m$, f_m$), this.size_m$); }
  // map$mut$2(c, f_m$) { return ConvertFromDataParallel.of(this, this.size_m$).map$mut$2(c, f_m$); }
  map$mut$2(c, f_m$) { throw new Error("todo"); }

  mapFilter$mut$1(f_m$) { return base$$flows$$_NonTerminalOps_1.mapFilter$mut$2$fun(f_m$, this); }
  assumeFinite$mut$0() { return this.$this.fromOp$imm$2(base$$flows$$_AssumeFinite_1.$self.$hash$read$1(this.source_m$), this.size_m$); }
  peek$mut$1(f_m$) { return base$$flows$$_NonTerminalOps_1.peek$mut$2$fun(f_m$, this); }
  // peek$mut$2(c, f_m$) { return ConvertFromDataParallel.of(this, this.size_m$).peek$mut(c, f_m$); }
  peek$mut$2(c, f_m$)  { throw new Error("todo"); }

  any$mut$1(predicate_m$) { return this.filter$mut$1(predicate_m$).first$mut$0().isSome$read$0(); }
  find$mut$1(predicate_m$) { return base$$flows$$_TerminalOps_1.find$mut$2$fun(predicate_m$, this); }
  max$mut$1(compare_m$) { return base$$flows$$_TerminalOps_1.max$mut$2$fun(compare_m$, this); }

  count$mut$0() { return this.size >= 0 ? this.size : this.fold$mut$2(() => 0, (acc, _) => acc + 1); }
  size$read$0() { return this.size_m$; }
  scan$mut$2(acc_m$, f_m$) { return base$$flows$$_NonTerminalOps_1.scan$mut$3$fun(acc_m$, f_m$, this); }

  $hash$imm$1(ext_m$) { return base$$Extensible_1.$hash$imm$2$fun(ext_m$, this); }
  $hash$read$1(ext_m$) { return base$$Extensible_1.$hash$read$2$fun(ext_m$, this); }
  $hash$mut$1(ext_m$) { return base$$Extensible_1.$hash$mut$2$fun(ext_m$, this); }

  join$mut$1(f) { return base$$flows$$Flow_1.join$mut$2$fun(f, this); }
  for$mut$1(f_m$) { return base$$flows$$_TerminalOps_1.for$mut$2$fun(f_m$, this); }
  forEffect$mut$1(f_m$) { return base$$flows$$_TerminalOps_1.forEffect$mut$2$fun(f_m$, this); }

  equals(obj) {
    if (obj === this) return true;
    if (!obj || obj.constructor !== DataParallelFlow) return false;
    return this.source_m$ === obj.source_m$ && this.size_m$ === obj.size_m$ && this.$this === obj.$this;
  }

  hashCode() {
    return JSON.stringify([this.source_m$, this.size_m$, this.$this]).split("").reduce((h, c) => ((h << 5) + h + c.charCodeAt(0)) | 0, 5381);
  }

  toString() {
    return `DataParallelFlow[source_m$=${this.source_m$}, size_m$=${this.size_m$}, $this=${this.$this}]`;
  }

  getDataParallelSource() {
    const outer = this;
    return {
      isFinite$mut$0: () => outer.source_m$.isFinite$mut$0(),
      step$mut$1: sink => outer.source_m$.step$mut$1(sink),
      stopUp$mut$0: () => outer.source_m$.stopUp$mut$0(),
      isRunning$mut$0: () => outer.source_m$.isRunning$mut$0(),
      for$mut$1: downstream => outer.source_m$.for$mut$1(downstream),
      split$mut$0: () => outer.source_m$.split$mut$0(),
      canSplit$read$0: () => outer.source_m$.canSplit$read$0()
    };
  }

}

// DataParallelFlow.$self = new DataParallelFlow(null, base$$Opt_1.$self, null);
// Because circular trap: DataParallelFlow.js
//   → base/Opt_1.js
//     → base/Fear2554$_1.js
//       → rt-js/flows.js
//         → DataParallelFlowK.js
//           → DataParallelFlow.js
let __dp_self;
Object.defineProperty(DataParallelFlow, "$self", {
  get() {
    if (!__dp_self) {
      __dp_self = new DataParallelFlow(null, base$$Opt_1.$self, null);
    }
    return __dp_self;
  }
});
