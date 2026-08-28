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

/// Runs at the top of every stack frame, so once per reduction step. Each step
/// grants one token. A promotion costs `TOKENS_THRESHOLD` tokens, which are
/// destroyed; what survives the charge splits evenly between parent and child.
/// The charge is what bounds promotions at `W / TOKENS_THRESHOLD` (APM Theorem
/// 4.1 with C/N = 1/TOKENS_THRESHOLD); the split alone bounds nothing. Tokens
/// are spent only on a promotion that published, so a frame with nothing to
/// promote keeps saving towards the next promotable one.
///
/// Section 4 of Automatic Parallelism Management, Westrick et al.
pub inline fn tryPromote() void {
    comptime if (!ARE_HEARTBEATS_ENABLED) return;
    // Generated code always runs on a fiber stack, so the header is always
    // there to be found; the flag is what suppresses promotion.
    const fiber = fiber_mod.currentFiber();
    if (!fiber.vpf_enabled) return;
    // Relaxed: a plain load and store on the owner's path. A thief credit that
    // lands between them is lost; see `Fiber.tokens`.
    const tokens = fiber.tokens.load(.monotonic);
    if (tokens >= TOKENS_THRESHOLD) {
        // Both tests are inline and reject without a call, which is what keeps
        // a miss cheap; nothing else may run on the healthy path. Promoting
        // into a cancelled subtree only burns a thief fiber on work that is
        // about to abort.
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

/// True when the fiber has an un-promoted shadow frame.
inline fn hasPromotableFrame(fiber: *const Fiber) bool {
    const cursor = &fiber.shadow_cursor;
    return cursor.lowest_unpromoted < cursor.top;
}

/// A fiber that runs a long time without reaching the scheduler still has to
/// drain objects handed back by other workers. The inline rejection above skips
/// `doPromote` and its drain, so keep that liveness on a coarse cadence.
inline fn periodicDrain(tokens: u32) void {
    if (tokens & 0xFFFF == 0) drainMergeQueueOnly();
}

noinline fn drainMergeQueueOnly() void {
    const worker = worker_mod.getCurrentWorker() orelse return;
    worker.drainMergeQueue();
}

/// Promote the oldest un-promoted frame of the running fiber. True only when the
/// promotion published its join obligation, which is what makes it worth
/// charging for; every early return leaves the work to the parent.
noinline fn doPromote(fiber: *Fiber, child_initial_tokens: u32) bool {
    const worker = worker_mod.getCurrentWorker() orelse {
        op_counters.bump(.promotion_miss_no_fiber);
        return false;
    };

    worker.drainMergeQueue();

    const cursor = &fiber.shadow_cursor;
    const frame_idx = cursor.lowest_unpromoted;

    log.trace_scheduling(.hb_entry, worker.id, cursor.top, @intFromPtr(worker.current_fiber));

    // Re-test: the frame state can change between the inline check and here.
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
    // The thief fiber shows this call chain beneath a fiber boundary if it
    // crashes.
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
    // Published before the task can be dequeued, so the join point always finds
    // the task it has to race for.
    frame.task.store(task, .monotonic);

    log.trace_scheduling(.hb_promote, frame_idx, @intFromPtr(obligation), @intFromPtr(child_obl));

    // Strictly before the task can be dequeued: from here another worker may
    // build a thief for it, and that thief pays a join credit into this fiber's
    // `tokens`. The reference keeps the mapping that holds them alive even if
    // this fiber abandons and is destroyed first.
    fiber.retainMapping();
    task.holds_parent_ref = true;

    // Before the join obligation becomes observable: a window between CAS and
    // enqueue would leave the parent waiting on an obligation nothing fulfills.
    // `enqueueTask` is total, so admission never refuses and tokens deplete the
    // way the model assumes.
    worker_mod.enqueueTask(task);
    log.trace_scheduling(.hb_enqueue, @intFromPtr(task), @intFromPtr(obligation), 0);

    // Publish. A failed CAS means the parent or another promoter claimed the
    // frame, so the queued task is dead work.
    if (frame.join_obligation.cmpxchgStrong(null, obligation, .release, .monotonic)) |old| {
        // Claimed either way, so the frame leaves the un-promoted suffix.
        cursor.lowest_unpromoted = frame_idx + 1;
        // Take the execution rights back if no worker holds them. The winner
        // recycles the obligations; the task stays in its queue for the
        // dequeuing worker to retire. Losing costs a thief fiber whose result
        // nobody reads, but stays correct.
        if (task.claimed.cmpxchgStrong(false, true, .acq_rel, .acquire) == null) {
            worker.recycleObligation(obligation);
            worker.recycleObligation(child_obl);
        }
        frame.child_obligation.store(null, .monotonic);
        frame.task.store(null, .monotonic);
        log.trace_scheduling(.hb_cas_fail, frame_idx, @intFromPtr(old), 0);
        op_counters.bump(.promotion_miss_cas_lost);
        return false;
    }
    log.trace_scheduling(.hb_cas_ok, frame_idx, @intFromPtr(obligation), 0);
    cursor.lowest_unpromoted = frame_idx + 1;
    return true;
}
