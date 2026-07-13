//! One pipeline stage: a worker fiber that runs a contiguous slice of the flow's
//! op array over the ordered message stream, plus a supervisor fiber that turns
//! the worker's fiber-death-on-throw (`feart_unwind`) into an in-band `err`
//! message so stream order is preserved. Mirrors `intrinsics/try.zig`'s
//! child-fiber pattern: catching a Fearless error means running the code on a
//! disposable fiber and reading its root obligation.

const std = @import("std");
const build_options = @import("build_options");
const objs = @import("../../../objs.zig");
const gc = @import("../../../gc.zig");
const scope_mod = @import("../../../scope.zig");
const worker_mod = @import("../../../worker.zig");
const fiber_mod = @import("../../../fiber.zig");
const shadow_stack = @import("../../../shadow_stack.zig");
const error_rt = @import("../../../error.zig");
const trace = @import("../../../errors/trace.zig");
const completion = @import("../../../sync/completion.zig");
const JoinObligation = @import("../../../sync/join_obligation.zig").JoinObligation;
const root = @import("root");
const pb = root.pkg_base;

const types = @import("../types.zig");
const exec = @import("../exec.zig");
const ring_mod = @import("ring.zig");
const Ring = ring_mod.Ring;

const FatPtr = objs.FatPtr;
const Fiber = fiber_mod.Fiber;

/// Shared by a stage's supervisor and worker; lives in the engine caller's
/// GC-heap allocation, reachable from the caller's stack until after join.
pub const StageCtx = struct {
    /// Only stage 0 (in == null) touches the source.
    flow: *types.FeartFlow,
    ops: []types.OpDesc,
    in: ?*Ring,
    out: *Ring,
};

/// Spawn a fiber the way Try does, but without waiting: the caller owns `obl`
/// (stack- or heap-resident, stable until fulfilled) and joins later.
pub fn spawnFiber(entry: *const fn (*Fiber) void, context: *anyopaque, obl: *JoinObligation) void {
    const worker = worker_mod.getCurrentWorker().?;
    const parent_fiber = worker.current_fiber.?;
    const child = Fiber.create(entry, context) catch @panic("OOM creating pipeline fiber");
    child.root_obligation = obl;
    child.saved_scope = scope_mod.active_scope;
    child.parent_tokens_ptr = &parent_fiber.tokens;
    if (build_options.trace_frames) {
        trace.inheritStackTrace(child, &parent_fiber.trace_frames, parent_fiber.trace_top);
    }
    child.state = .Ready;
    worker.ready_queue.enqueueWithSpin(child);
}

pub fn supervisorEntry(fiber: *Fiber) void {
    const st: *StageCtx = @ptrCast(@alignCast(fiber.context.?));
    var wobl = JoinObligation.init();
    spawnFiber(&workerEntry, st, &wobl);
    const result = completion.wait(&wobl);
    if (error_rt.tagOf(result) == .none) {
        result.rc_decrement(); // Void from the worker's normal path
    } else {
        // The worker unwound mid-element. Its already-enqueued outputs are a
        // valid prefix (identical to sequential, where pre-throw emissions
        // happened); the error follows them in stream order. Then tear down
        // our ring ends on the dead worker's behalf.
        _ = st.out.enqueue(.{ .err = result });
        if (st.in) |in| in.closeConsumer();
        st.out.closeProducer();
    }
    const worker = worker_mod.getCurrentWorker().?;
    fiber.root_obligation.?.fulfill(objs.obj_k_singleton(&pb.VT_Void_0).box_transient(), worker.ready_queue);
}

fn workerEntry(fiber: *Fiber) void {
    const st: *StageCtx = @ptrCast(@alignCast(fiber.context.?));
    if (st.in == null) runSourceStage(st) else runMidStage(st);
    const worker = worker_mod.getCurrentWorker().?;
    fiber.root_obligation.?.fulfill(objs.obj_k_singleton(&pb.VT_Void_0).box_transient(), worker.ready_queue);
}

/// Accept target for `exec.process_element` inside a stage: results go onto the
/// output ring. Returning `false` (downstream closed) makes process_element set
/// `stopped`, ending the stage loop.
const RingAcceptCtx = struct { out: *Ring, out_closed: bool };
fn ring_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *RingAcceptCtx = @ptrCast(@alignCast(ctx_ptr));
    switch (ctx.out.enqueue(.{ .data = elem })) {
        .ok => return true,
        .closed => {
            ctx.out_closed = true;
            return false;
        },
    }
}

fn runSourceStage(st: *StageCtx) void {
    var actx = RingAcceptCtx{ .out = st.out, .out_closed = false };
    var stopped = false;
    while (!stopped and types.source_has_next(&st.flow.source)) {
        if (scope_mod.currentCancelled()) break;
        exec.process_element(st.ops, types.source_next(&st.flow.source), @ptrCast(&actx), &ring_accept, &stopped);
    }
    if (!actx.out_closed) _ = st.out.enqueue(.stop);
    st.out.closeProducer();
}

fn runMidStage(st: *StageCtx) void {
    const in = st.in.?;
    var actx = RingAcceptCtx{ .out = st.out, .out_closed = false };
    var stopped = false;
    loop: while (true) {
        if (scope_mod.currentCancelled()) break;
        switch (in.dequeue()) {
            .closed => break, // upstream tore down abnormally; cascade below
            .msg => |m| switch (m) {
                .data => |elem| {
                    exec.process_element(st.ops, elem, @ptrCast(&actx), &ring_accept, &stopped);
                    if (stopped) {
                        // Our op ended the flow (limit exhausted / actor stop)
                        // or downstream closed. Downstream still gets an
                        // orderly stop; closing our input (below) tears the
                        // upstream down.
                        if (!actx.out_closed) _ = st.out.enqueue(.stop);
                        break :loop;
                    }
                },
                // First error in stream order wins; nothing after it can be
                // observed, so forward it and exit -- closing our input makes
                // the upstream self-terminate instead of us draining it.
                .err => |payload| {
                    _ = st.out.enqueue(.{ .err = payload });
                    break :loop;
                },
                .stop => {
                    if (!actx.out_closed) _ = st.out.enqueue(.stop);
                    break :loop;
                },
            },
        }
    }
    in.closeConsumer();
    st.out.closeProducer();
}
