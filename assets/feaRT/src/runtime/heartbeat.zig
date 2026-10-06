//! Promotion control for VPF (Very Parallel Fearless).
//!
//! The name is historical: this is not Heartbeat Scheduling, nor the Task
//! Parallel Assembly Language. It follows Automatic Parallelism Management
//! (Westrick et al.), a descendant of Heartbeat Scheduling that performs much
//! better in practice, and it is the design proposed in the future work of my
//! PhD thesis "Fearless Automatic Parallelisation".

const std = @import("std");
const shadow_stack_mod = @import("shadow_stack.zig");
const worker_mod = @import("worker.zig");
const log = @import("log.zig");
const scope_mod = @import("scope.zig");
const op_counters = @import("op_counters.zig");
const build_options = @import("build_options");

const fiber_mod = @import("fiber.zig");
const Fiber = fiber_mod.Fiber;
const ShadowFrame = shadow_stack_mod.ShadowFrame;
const Worker = worker_mod.Worker;
const JoinObligation = @import("sync/join_obligation.zig").JoinObligation;
const StolenTask = worker_mod.StolenTask;

pub const TOKENS_THRESHOLD: u32 = @import("build_options").tokens_threshold;
const ARE_HEARTBEATS_ENABLED = @import("build_options").enable_vpf;

/// Runs once per reduction step and grants one token. A promotion destroys
/// `TOKENS_THRESHOLD` tokens, and parent and child split the remainder. The
/// charge bounds promotions at `W / TOKENS_THRESHOLD` (APM Theorem 4.1 with
/// C/N = 1/TOKENS_THRESHOLD); the split alone bounds nothing. Only a published
/// promotion spends tokens.
///
/// Section 4 of Automatic Parallelism Management, Westrick et al.
pub inline fn tryPromote() void {
    comptime if (!ARE_HEARTBEATS_ENABLED) return;
    // Generated code always runs on a fiber stack. The flag suppresses promotion.
    const fiber = fiber_mod.currentFiber();
    if (!fiber.vpf_enabled) return;
    // Relaxed: a plain load and store on the owner's path. A thief credit that
    // lands between them is lost; see `Fiber.tokens`.
    const tokens = fiber.tokens.load(.monotonic);
    if (tokens >= TOKENS_THRESHOLD) {
        // Both tests are inline, so a miss makes no call. Do not add work to
        // this path. A promotion into a cancelled subtree wastes a thief fiber.
        if (hasPromotableFrame(fiber) and !scope_mod.cancelledOn(fiber)) {
            const remainder = (tokens - TOKENS_THRESHOLD) / 2;
            if (doPromote(fiber, remainder)) {
                op_counters.bump(.promotion);
                fiber.tokens.store(remainder, .monotonic);
                return;
            }
        } else {
            // doPromote records its own reason.
            if (op_counters.ENABLED) {
                if (!hasPromotableFrame(fiber)) {
                    op_counters.bump(.promotion_miss_no_frame);
                } else {
                    op_counters.bump(.promotion_miss_cancelled);
                }
            }
            periodicDrain(tokens);
        }
    }
    // The threshold check above bounds this below U32_MAX, so it cannot overflow.
    op_counters.bump(.token_granted);
    fiber.tokens.store(tokens + 1, .monotonic);
}

inline fn hasPromotableFrame(fiber: *const Fiber) bool {
    const cursor = &fiber.shadow_cursor;
    return cursor.lowest_unpromoted < cursor.top;
}

/// A fiber that does not reach the scheduler must still give its
/// reference-counting chains to the collector. The inline rejection skips the
/// checkpoint in `doPromote`, so this does it on a coarse cadence. Otherwise
/// such a fiber stalls every epoch.
inline fn periodicDrain(tokens: u32) void {
    if (tokens & 0xFFFF == 0) checkpointOnly();
}

noinline fn checkpointOnly() void {
    const worker = worker_mod.getCurrentWorker() orelse return;
    worker.checkpoint();
}

/// Promotes the oldest un-promoted frame. True only when the join obligation is
/// published; a false return leaves the work to the parent.
noinline fn doPromote(fiber: *Fiber, child_initial_tokens: u32) bool {
    const worker = worker_mod.getCurrentWorker() orelse {
        op_counters.bump(.promotion_miss_no_fiber);
        return false;
    };

    worker.checkpoint();

    const cursor = &fiber.shadow_cursor;
    const frame_idx = cursor.lowest_unpromoted;

    log.trace_scheduling(.hb_entry, worker.id, cursor.top, @intFromPtr(worker.current_fiber));

    if (frame_idx >= cursor.top) {
        op_counters.bump(.promotion_miss_no_frame);
        return false;
    }

    const frames = &fiber.shadow_frames;

    const obligation = worker.allocObligation() orelse {
        op_counters.bump(.promotion_miss_pool_empty);
        return false;
    };

    const task = worker.allocTask() orelse {
        worker.recycleObligation(obligation);
        op_counters.bump(.promotion_miss_pool_empty);
        return false;
    };

    const frame = &frames[frame_idx];

    if (std.debug.runtime_safety) {
        std.debug.assert(frame.locals_size <= task.locals_copy.len);
    }
    const locals_size = @min(frame.locals_size, task.locals_copy.len);
    const src: [*]const u8 = @ptrCast(frame.locals);
    @memcpy(task.locals_copy[0..locals_size], src[0..locals_size]);
    task.locals_drop_fn = frame.drop_fn;
    frame.retain_fn(@ptrCast(&task.locals_copy), frame.locals);

    task.thief_fn = frame.thief_fn;
    task.obligation = obligation;
    task.initial_tokens = child_initial_tokens;
    task.parent = fiber;
    task.scope = frame.scope;
    // A crash on the thief shows this call chain under the fiber boundary.
    if (build_options.trace_frames) {
        @memcpy(task.trace_frames[0..], fiber.trace_frames[0..]);
        task.trace_top = fiber.trace_top;
    }

    const child_obl = worker.allocObligation() orelse {
        worker.recycleObligation(obligation);
        worker.recycleTask(task);
        op_counters.bump(.promotion_miss_pool_empty);
        return false;
    };
    frame.child_obligation.store(child_obl, .monotonic);
    task.child_obligation = child_obl;
    // Before the enqueue, so the join point always finds the task to race for.
    frame.task.store(task, .monotonic);

    log.trace_scheduling(.hb_promote, frame_idx, @intFromPtr(obligation), @intFromPtr(child_obl));

    // Before the enqueue: a thief pays a join credit into this fiber's `tokens`.
    // The reference keeps that mapping alive if this fiber is destroyed first.
    fiber.retainMapping();
    task.holds_parent_ref = true;

    // Before the publish: otherwise the parent can wait on an obligation that
    // nothing fulfills. `enqueueTask` is total, so tokens deplete as the model
    // assumes.
    worker_mod.enqueueTask(task);
    log.trace_scheduling(.hb_enqueue, @intFromPtr(task), @intFromPtr(obligation), 0);

    // A plain, unconditional store: `[lowest_unpromoted, top)` is un-promoted, so
    // the slot is null. Proof: `Vpf.publication_never_fails` in
    // `experiments/formal`. It assumes no asynchronous writer, so signal-driven
    // promotion makes it false.
    if (std.debug.runtime_safety) {
        std.debug.assert(frame.join_obligation == null);
    }
    frame.join_obligation = obligation;
    log.trace_scheduling(.hb_cas_ok, frame_idx, @intFromPtr(obligation), 0);
    cursor.lowest_unpromoted = frame_idx + 1;
    return true;
}
