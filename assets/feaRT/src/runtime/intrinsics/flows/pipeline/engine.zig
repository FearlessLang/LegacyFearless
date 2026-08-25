//! The pipeline-parallel flow engine. The op array is cut at those ops; each
//! serial op and each stateless run between them gets its own stage fiber,
//! adjacent stages are connected by bounded SPSC rings, and the calling fiber
//! consumes the last ring, running any trailing stateless ops plus the terminal.
//!
//! Deliberately shares no control flow with the DP flow driver: stateful chains
//! never split, so `_FeartDriver`'s splitMatch already collapses them to the
//! sequential leaf -- this engine simply upgrades that leaf. `limit` neither
//! triggers nor cuts: it is stateful but does no serial *work*; inside any
//! single stage it is correct, and its stop propagates through the ring
//! protocol.

const std = @import("std");
const objs = @import("../../../objs.zig");
const gc = @import("../../../gc.zig");
const scope_mod = @import("../../../scope.zig");
const error_rt = @import("../../../error.zig");
const unwind = @import("../../../errors/unwind.zig");
const completion = @import("../../../sync/completion.zig");
const JoinObligation = @import("../../../sync/join_obligation.zig").JoinObligation;

const types = @import("../types.zig");
const exec = @import("../exec.zig");
const ring_mod = @import("ring.zig");
const stage_mod = @import("stage.zig");
const Ring = ring_mod.Ring;

const FatPtr = objs.FatPtr;

fn isCut(op: types.OpDesc) bool {
    return switch (op.kind) {
        .scan, .actor, .map_ctx, .peek_ctx => true,
        else => false,
    };
}

pub fn shouldPipeline(flow: *types.FeartFlow) bool {
    for (flow.ops) |op| {
        if (isCut(op)) return true;
    }
    return false;
}

const Plan = struct {
    /// Op slice per stage fiber. stages[0] runs the source too (its slice is
    /// the leading stateless run and may be empty -- a pure source pump).
    stages: [][]types.OpDesc,
    /// Trailing stateless ops after the last cut; run on the calling fiber
    /// together with the terminal accept.
    tail: []types.OpDesc,
};

fn partition(ops: []types.OpDesc) Plan {
    var stages: std.ArrayList([]types.OpDesc) = .empty;
    var seg_start: usize = 0;
    for (ops, 0..) |op, i| {
        if (!isCut(op)) continue;
        // Leading run becomes stage 0 even when empty (the source pump);
        // between-cut runs only get a fiber when non-empty.
        if (i > seg_start or stages.items.len == 0) {
            stages.append(gc.allocator, ops[seg_start..i]) catch @panic("OOM");
        }
        stages.append(gc.allocator, ops[i .. i + 1]) catch @panic("OOM");
        seg_start = i + 1;
    }
    return .{ .stages = stages.items, .tail = ops[seg_start..] };
}

/// Same contract as `exec.run_chunk`: pull the whole flow through `ops` into
/// `accept`, returning when the stream ends, `accept` short-circuits, or the
/// flow errors (in which case this rethrows on the calling fiber after all
/// stage fibers have been joined -- the flow is fully quiescent on return or
/// unwind, upholding the blocking-flow world-freeze invariant).
pub fn run(flow: *types.FeartFlow, ctx: *anyopaque, accept: exec.AcceptFn) void {
    const plan = partition(flow.ops);
    const n = plan.stages.len;

    const rings = gc.allocator.alloc(*Ring, n) catch @panic("OOM");
    for (rings) |*r| r.* = Ring.create();
    const stage_ctxs = gc.allocator.alloc(stage_mod.StageCtx, n) catch @panic("OOM");
    const sup_obls = gc.allocator.alloc(JoinObligation, n) catch @panic("OOM");
    for (0..n) |i| {
        stage_ctxs[i] = .{
            .flow = flow,
            .ops = plan.stages[i],
            .in = if (i == 0) null else rings[i - 1],
            .out = rings[i],
        };
        sup_obls[i] = JoinObligation.init();
        stage_mod.spawnFiber(&stage_mod.supervisorEntry, &stage_ctxs[i], &sup_obls[i]);
    }

    const last = rings[n - 1];
    var stopped = false;
    var err_payload: ?FatPtr = null;
    while (!stopped) {
        if (scope_mod.currentCancelled()) break;
        switch (last.dequeue()) {
            .closed => break,
            .msg => |m| switch (m) {
                .data => |elem| exec.process_element(plan.tail, elem, ctx, accept, &stopped),
                .err => |payload| {
                    err_payload = payload;
                    break;
                },
                .stop => break,
            },
        }
    }

    // The result is determined; stop all remaining pipeline work. The ring
    // closes are what actually wake parked stage fibers -- the scope request
    // just shortens in-flight closure work (and is what `.any`'s accept fn
    // relies on).
    last.closeConsumer();
    if (scope_mod.activeScope()) |s| s.request();
    for (sup_obls) |*obl| {
        const r = completion.wait(obl);
        if (error_rt.tagOf(r) == .none) {
            r.rc_decrement();
        } else if (err_payload == null) {
            err_payload = r; // a supervisor itself faulted (e.g. OOM panic)
        } else {
            r.rc_decrement();
        }
    }
    for (rings) |r| r.drainDrop();

    if (err_payload) |payload| unwind.feart_unwind(payload);
}
