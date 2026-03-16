import { base$$True_0 } from "../../base/True_0.js";
import { base$$False_0 } from "../../base/False_0.js";
import { base$$Opt_1 } from "../../base/Opt_1.js";
import { base$$Opts_0 } from "../../base/Opts_0.js";
import { base$$Void_0 } from "../../base/Void_0.js";

export const SpliteratorFlowOp = {
  of(iterable) {
    // Convert iterable to an array for splitting & tracking
    const arr = Array.from(iterable);
    let index = 0;
    let hasStopped = false;

    return {
      isFinite$mut$0() {
        return base$$True_0.$self;
      },

      step$mut$1(sink_m$) {
        if (hasStopped || index >= arr.length) {
          hasStopped = true;
          sink_m$.stopDown$mut$0();
        } else {
          sink_m$.$hash$mut$1(arr[index++]);
        }
        return base$$Void_0.$self;
      },

      stopUp$mut$0() {
        hasStopped = true;
        return base$$Void_0.$self;
      },

      isRunning$mut$0() {
        return hasStopped ? base$$False_0.$self : base$$True_0.$self;
      },

      for$mut$1(downstream_m$) {
        while (index < arr.length) {
          downstream_m$.$hash$mut$1(arr[index++]);
        }
        downstream_m$.stopDown$mut$0();
        hasStopped = true;
        return base$$Void_0.$self;
      },

      split$mut$0() {
        const remaining = arr.length - index;
        if (remaining <= 1) return base$$Opt_1.$self;
        const mid = index + Math.floor(remaining / 2);
        const firstHalf = arr.slice(index, mid);
        index = mid; // leave second half for this iterator
        return base$$Opts_0.$self.$hash$imm$1(SpliteratorFlowOp.of(firstHalf));
      },

      canSplit$read$0() {
        return (arr.length - index) > 1 ? base$$True_0.$self : base$$False_0.$self;
      }
    };
  }
};
