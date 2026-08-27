const std = @import("std");

pub const MAX_SHADOW_DEPTH = 256;

/// The application claimed this frame before the signal handler did. The value
/// is `@ptrFromInt(alignof)` so it satisfies the pointer alignment.
pub const CLAIMED: *JoinObligation = @ptrFromInt(@alignOf(JoinObligation));

pub const JoinObligation = @import("sync/join_obligation.zig").JoinObligation;
const objs = @import("objs.zig");
const FatPtr = objs.FatPtr;
const worker_mod = @import("worker.zig");
const build_options = @import("build_options");
const gc = @import("gc.zig");
const trace = @import("./errors/trace.zig");
const op_counters = @import("op_counters.zig");
const fiber_mod = @import("fiber.zig");
const scope_mod = @import("scope.zig");

pub const LocalsRetainHook = *const fn (*anyopaque, *anyopaque) void;
/// Releases the reference-counted fields of a promoted frame's locals. The
/// second argument is the worker to release on behalf of, because
/// `Worker.recycleTask` runs this off a fiber stack.
pub const LocalsDropHook = *const fn (*anyopaque, releasing_worker_id: u32) void;

pub const ShadowFrame = struct {
	target_method: u64,
	join_obligation: std.atomic.Value(?*JoinObligation),
	child_obligation: std.atomic.Value(?*JoinObligation),
	locals: *anyopaque,
	locals_size: usize,
	retain_fn: LocalsRetainHook,
	/// Called on drop by the reference counting system. It may never run if the
	/// GC reclaims the object first, which only leaves the GC more to do later.
	drop_fn: LocalsDropHook,
	thief_fn: *const fn (*anyopaque, ?*JoinObligation) FatPtr,
	/// The task a promotion of this frame published, so the owning fiber can take
	/// the work back if nobody stole it. Only that fiber writes it; the thief
	/// side never reaches the frame.
	task: std.atomic.Value(?*worker_mod.StolenTask) = std.atomic.Value(?*worker_mod.StolenTask).init(null),
	/// The cancellation scope in effect where the frame was pushed. A promotion
	/// gives this to the thief, and not the scope the fiber has reached by then:
	/// a nested flow terminal below this frame pushes a scope of its own and
	/// cancels it on a short circuit, which must not reach the stolen work.
	scope: ?*scope_mod.Scope = null,
};

/// Both indices in one struct, so push, pop and the heartbeat reach them off a
/// single fiber pointer.
pub const ShadowCursor = struct {
	top: usize = 0,

	/// Promoted frames form a prefix: `pushFrame` appends an un-promoted frame at
	/// `top` and promotion claims the lowest un-promoted frame, so `[0,
	/// lowest_unpromoted)` is promoted and `[lowest_unpromoted, top)` is not.
	/// `lowest_unpromoted >= top` is thus the whole "nothing to promote" test.
	lowest_unpromoted: usize = 0,
};

pub const TRACE_CAP = 256;

/// Null off a fiber stack.
///
/// Only `pushFrame` and `popAndClaim` mask the stack pointer directly, because
/// only they are emitted per call and are always reached from generated code.
/// Everything else resolves the fiber through the worker: a mask on a stack that
/// is not a fiber's lands on unrelated memory rather than reading as absent.
pub fn getShadowStack() ?*[MAX_SHADOW_DEPTH]ShadowFrame {
	const f = fiber_mod.currentFiberOrNull() orelse return null;
	return &f.shadow_frames;
}

pub fn getShadowCursor() ?*ShadowCursor {
	const f = fiber_mod.currentFiberOrNull() orelse return null;
	return &f.shadow_cursor;
}

/// Null when the shadow stack is full.
pub inline fn pushFrame(frame: ShadowFrame) ?usize {
	const f = fiber_mod.currentFiber();
	const ss = &f.shadow_frames;
	const cursor = &f.shadow_cursor;
	const idx = cursor.top;
	if (idx >= MAX_SHADOW_DEPTH) return null;
	op_counters.bump(.vpf_frame_push);
	ss[idx] = frame;
	ss[idx].scope = f.saved_scope;
	asm volatile ("" ::: .{ .memory = true });
	cursor.top = idx + 1;
	return idx;
}

/// Null when this fiber claimed the frame, or the join obligation when a thief
/// had already taken it.
pub inline fn popAndClaim(frame_idx: usize) ?*JoinObligation {
	const f = fiber_mod.currentFiber();
	const cursor = &f.shadow_cursor;
	const frame = &f.shadow_frames[frame_idx];
	const prev = frame.join_obligation.cmpxchgStrong(null, CLAIMED, .acquire, .acquire);
	cursor.top -= 1;
	// The popped frame leaves the stack CLAIMED either way, so the prefix
	// boundary follows the top down when every frame was promoted.
	cursor.lowest_unpromoted = @min(cursor.lowest_unpromoted, cursor.top);
	return if (prev) |obl| obl else null;
}

/// To the current worker's freelist, or `c_allocator.destroy` on a full pool.
pub fn freeObligation(obl: *JoinObligation) void {
	if (worker_mod.getCurrentWorker()) |w| {
		w.recycleObligation(obl);
	} else {
		std.heap.c_allocator.destroy(obl);
	}
}

/// Take back a promotion no worker picked up. True when this fiber won the race
/// and must run the work itself; false when a thief owns it and the caller waits
/// on `obligation` as usual.
///
/// The task cannot be retired underneath this call. A thief finishes only by
/// waiting on the child obligation, which the caller fulfills strictly after a
/// false return, so a lost claim leaves the task alive; a won claim leaves it in
/// its queue for the dequeuing worker to retire.
pub fn reclaimPromotion(frame_idx: usize, obligation: *JoinObligation) bool {
	const frame = &getShadowStack().?[frame_idx];
	const task = frame.task.load(.acquire) orelse return false;
	if (task.claimed.cmpxchgStrong(false, true, .acq_rel, .acquire) != null) return false;

	op_counters.bump(.promotion_reclaimed);
	// The obligations belong to the winner: a thief that lost the claim never
	// touches them, and nothing else holds either pointer.
	freeObligation(obligation);
	if (frame.child_obligation.load(.acquire)) |child_obl| freeObligation(child_obl);
	frame.child_obligation.store(null, .monotonic);
	frame.task.store(null, .monotonic);
	return true;
}

pub inline fn fulfillChildObligation(frame_idx: usize, value: FatPtr) void {
	const frame = &getShadowStack().?[frame_idx];
	const child_obl = frame.child_obligation.load(.acquire).?;
	const boxed = value.box_transient();
	child_obl.fulfill(boxed);
}

/// Null off a fiber stack, and always null when `trace_frames` is off.
pub fn getStackTrace() ?*[TRACE_CAP]trace.TraceFrame {
	if (build_options.trace_frames) {
		const f = fiber_mod.currentFiberOrNull() orelse return null;
		return &f.trace_frames;
	}
	return null;
}

/// The true depth, so a ring-buffer overflow is reportable.
pub fn getTraceTop() ?*usize {
	if (build_options.trace_frames) {
		const f = fiber_mod.currentFiberOrNull() orelse return null;
		return &f.trace_top;
	}
	return null;
}

pub const tracePush = trace.tracePush;
pub const tracePop = trace.tracePop;
