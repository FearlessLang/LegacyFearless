import { base$$flows$$_SeqFlow_0 } from "../../base/flows/_SeqFlow_0.js";
import { base$$True_0 } from "../../base/True_0.js";
import { base$$False_0 } from "../../base/False_0.js";
import { base$$Opt_1 } from "../../base/Opt_1.js";
import { base$$Void_0 } from "../../base/Void_0.js";

export class Range {
  static $self = new Range();

  $hash$imm$1(start_m$) {
    const op = new InfiniteRangeOp(start_m$);
    return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(op, base$$Opt_1.$self);
  }
  $hash$imm$2(start_m$, end_m$) {
    const totalSize = end_m$ - start_m$;
    const op = new FiniteRangeOp(start_m$, end_m$);
    return base$$flows$$_SeqFlow_0.$self.fromOp$imm$2(op, base$$Opt_1.$self.$hash$imm$1(totalSize));
  }
}

class FiniteRangeOp {
  constructor(start, end) {
    this.cursor = start;
    this.end = end;
  }
  isFinite$mut$0() { return base$$True_0.$self; }

  step$mut$1(sink_m$) {
    if (!this.isRunning()) { sink_m$.stopDown$mut$0(); return base$$Void_0.$self; }
    sink_m$.$hash$mut$1(this.cursor++);
    if (!this.isRunning()) { sink_m$.stopDown$mut$0(); }
    return base$$Void_0.$self;
  }

  stopUp$mut$0() {
    this.cursor = this.end;
    return base$$Void_0.$self;
  }

  isRunning$mut$0() { return this.cursor < this.end ? base$$True_0.$self : base$$False_0.$self; }
  isRunning() {
    return this.cursor < this.end;
  }

  for$mut$1(downstream_m$) {
    for (; this.cursor < this.end; this.cursor++) {
      downstream_m$.$hash$mut$1(this.cursor);
    }
    return downstream_m$.stopDown$mut$0();
  }

  split$mut$0() {
    const size = this.end - this.cursor;
    if (size <= 1) return base$$Opt_1.$self;
    const mid = this.cursor + Math.floor(size / 2);
    const end_ = this.end;
    this.end = mid;
    return base$$Opt_1.$self.$hash$imm$1(new FiniteRangeOp(mid, end_));
  }

  canSplit$read$0() { return (this.end - this.cursor) > 1 ? base$$True_0.$self : base$$False_0.$self; }
}

class InfiniteRangeOp {
  constructor(start) {
    this.cursor = start;
    this.isRunningFlag = true;
  }

  isFinite$mut$0() { return base$$False_0.$self; }
  isRunning$mut$0() { return this.isRunningFlag ? base$$True_0.$self : base$$False_0.$self; }

  step$mut$1(sink_m$) {
    if (!this.isRunningFlag) { sink_m$.stopDown$mut$0(); return base$$Void_0.$self; }
    sink_m$.$hash$mut$1(this.increment());
    if (!this.isRunningFlag) { sink_m$.stopDown$mut$0(); }
    return base$$Void_0.$self;
  }

  stopUp$mut$0() { this.isRunningFlag = false; return base$$Void_0.$self; }

  for$mut$1(downstream_m$) {
    while (this.isRunningFlag) {
      downstream_m$.$hash$mut$1(this.increment());
    }
    return downstream_m$.stopDown$mut$0();
  }

  split$mut$0() { return base$$Opt_1.$self; }
  canSplit$read$0() { return base$$False_0.$self; }

  increment() { return this.cursor++; }
}
