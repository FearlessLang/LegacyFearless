//! Despite the name, the system for controlling VPF (Very Parallel Fearless) is not a direct implementation of
//! Heartbeat Scheduling or even the Task Parallel Assembly Language. Instead, it is based on
//! Automatic Parallelism Management by Westrick et. al. This is a descendant of the work on
//! Heartbeat Scheduling, but with much better performance in practice.
//!
//! This is basically exactly what I proposed doing in the future work section of my PhD Thesis "Fearless Automatic Parallelisation"

const std = @import("std");
const shadow_stack_mod = @import("shadow_stack.zig");
const worker_mod = @import("worker.zig");
const log = @import("log.zig");
const scope_mod = @import("scope.zig");
const build_options = @import("build_options");

const ShadowFrame = shadow_stack_mod.ShadowFrame;
const Worker = worker_mod.Worker;
const JoinObligation = @import("sync/join_obligation.zig").JoinObligation;
const StolenTask = worker_mod.StolenTask;

pub const TOKENS_THRESHOLD: u32 = @import("build_options").tokens_threshold;
const ARE_HEARTBEATS_ENABLED = @import("build_options").enable_vpf;

/// Pointer to the running fiber's tokens counter. Swapped by
/// fiber.switchFiber alongside the shadow stack thread-locals. Null when
/// no fiber is running (e.g. on the scheduler fiber).
pub threadlocal var tls_tokens_ptr: ?*u32 = null;

/// This is called at the beginning of _every_ stack frame, effectively once per reduction step.
/// So, this is where we do our work tracking (token granting & promotion purchasing).
/// Each step gives you 1 token. Once you have enough tokens to purchase a promotion, we
/// promote the oldest promotable stack frame. The child gets half of the parent's tokens.
/// This is an implementation of the algorithm described in Section 4 of
/// Automatic Parallelism Management by Westrick et. al.
pub inline fn tryPromote() void {
    comptime if (!ARE_HEARTBEATS_ENABLED) return;
    const tokens_ptr = tls_tokens_ptr orelse return;
    if (tokens_ptr.* >= TOKENS_THRESHOLD) {
        tokens_ptr.* /= 2; // parent keeps half
        // Don't promote into a cancelled subtree -- stealing work we're about
        // to abort just burns a thief fiber. The TLS load is cheap and the
        // parent walk in Scope.cancelled() is short (scope depth = terminal-
        // nesting depth, 1 in practice).
        if (!scope_mod.currentCancelled()) {
            doPromote(tokens_ptr.*); // child will get the same half
        }
    }
    // Overflow shouldn't be an issue here because we check the threshold first, which is guaranteed to be <= U32 MAX
    tokens_ptr.* += 1;
}

noinline fn doPromote(child_initial_tokens: u32) void {
    // Get current worker
    const worker = worker_mod.getCurrentWorker() orelse return;

    // Get current fiber's shadow stack (null if no fiber running)
    const shadow_top_ptr = shadow_stack_mod.getShadowTop() orelse return;
    const top = shadow_top_ptr.*;

    log.trace_scheduling(.hb_entry, worker.id, top, @intFromPtr(worker.current_fiber));

    if (top == 0) return;

    const frames = shadow_stack_mod.getShadowStack() orelse return;

    // Scan from index 0 (oldest) upward for first frame not yet promoted
    var frame_idx: usize = 0;
    while (frame_idx < top) : (frame_idx += 1) {
        if (frames[frame_idx].join_obligation.load(.monotonic) == null) {
            break;
        }
    }
    if (frame_idx >= top) return; // All frames already promoted

    // Check if task queue has space before doing any work
    if (worker.task_queue.len() >= worker.task_queue.capacity() - 1) {
        log.trace_scheduling(.hb_enqueue_fail, 0, worker.task_queue.len(), worker.task_queue.capacity());
        return;
    }

    // Allocate obligation from pool
    const obligation = worker.allocObligation() orelse return;

    // Allocate task from pool
    const task = worker.allocTask() orelse {
        worker.recycleObligation(obligation);
        return;
    };

    const frame = &frames[frame_idx];

    // Copy locals into task
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
    // tls_tokens_ptr is guaranteed non-null here: tryPromote only calls
    // doPromote when it is non-null, and no fiber switch happens in between.
    task.parent_tokens_ptr = tls_tokens_ptr.?;
    // Capture the promoter's scope so the thief fiber inherits it.
    task.scope = scope_mod.active_scope;
    // Snapshot the promoter's trace stack so the thief fiber can show this call
    // chain beneath a fiber boundary if it crashes. doPromote runs on the
    // promoter fiber, so its trace TLS is live here.
    if (build_options.trace_frames) {
        const trace_top_ptr = shadow_stack_mod.getTraceTop().?;
        const trace_frames = shadow_stack_mod.getStackTrace().?;
        @memcpy(task.trace_frames[0..], trace_frames[0..]);
        task.trace_top = trace_top_ptr.*;
    }

    const child_obl = worker.allocObligation() orelse {
        worker.recycleObligation(obligation);
        worker.recycleTask(task);
        return;
    };
    frame.child_obligation.store(child_obl, .monotonic);
    task.child_obligation = child_obl;

    log.trace_scheduling(.hb_promote, frame_idx, @intFromPtr(obligation), @intFromPtr(child_obl));

    // The thief task must be in some worker's queue before the
    // join obligation becomes observable, otherwise a queue-full window
    // between CAS and enqueue would leave the parent waiting on an obligation
    // that nothing will ever fulfill (the lost-task hang).
    //
    // If the queue is full now, abandon the promotion entirely -- frame's
    // join_obligation is still null, so popAndClaim takes the no-promotion
    // branch and the parent runs the work itself. Recycle the prepared
    // resources and we're done.
    const enqueued = worker.task_queue.enqueue(task);
    if (!enqueued) {
        worker.recycleObligation(obligation);
        worker.recycleObligation(child_obl);
        worker.recycleTask(task);
        // Clear child_obligation back out -- the frame might still be claimed
        // by a future promotion attempt for the same frame index, and we
        // don't want a stale pointer left lying around.
        frame.child_obligation.store(null, .monotonic);
        log.trace_scheduling(.hb_enqueue_fail, @intFromPtr(task), @intFromPtr(obligation), 0);
        return;
    }
    log.trace_scheduling(.hb_enqueue, @intFromPtr(task), @intFromPtr(obligation), 0);

    // PUBLISH the obligation. If CAS fails the parent has already claimed the
    // frame (or another promoter beat us), so the queued task corresponds to
    // dead work. Mark it cancelled so thiefTrampoline no-ops when it runs.
    if (frame.join_obligation.cmpxchgStrong(null, obligation, .release, .monotonic)) |old| {
        task.cancelled.store(true, .release);
        // Clear the speculatively-stored child_obligation: the parent didn't
        // see our promotion (CAS lost), so it never wrote into it. The thief
        // will recycle the obligations it owns when it observes .cancelled.
        frame.child_obligation.store(null, .monotonic);
        log.trace_scheduling(.hb_cas_fail, frame_idx, @intFromPtr(old), 0);
        return;
    }
    log.trace_scheduling(.hb_cas_ok, frame_idx, @intFromPtr(obligation), 0);
}
