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

pub const LocalsRetainHook = *const fn (*anyopaque, *anyopaque) void;
pub const LocalsDropHook = *const fn (*anyopaque) void;

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
};

pub threadlocal var shadow_stack: ?*[MAX_SHADOW_DEPTH]ShadowFrame = null;

/// Both indices in one struct, so push, pop and the heartbeat reach them with a
/// single thread-local read.
pub const ShadowCursor = struct {
	top: usize = 0,

	/// Promoted frames form a prefix: `pushFrame` appends an un-promoted frame at
	/// `top` and promotion claims the lowest un-promoted frame, so `[0,
	/// lowest_unpromoted)` is promoted and `[lowest_unpromoted, top)` is not.
	/// `lowest_unpromoted >= top` is thus the whole "nothing to promote" test.
	lowest_unpromoted: usize = 0,
};

pub threadlocal var shadow_cursor: ?*ShadowCursor = null;

pub const TRACE_CAP = 256;

/// Null when `trace_frames` is off. See [errors/trace.zig].
pub threadlocal var trace_stack: ?*[TRACE_CAP]trace.TraceFrame = null;

/// The true depth, so a ring-buffer overflow is reportable.
pub threadlocal var trace_top: ?*usize = null;

/// Fresh reads, for the reason `getCurrentWorker` gives.
pub noinline fn getShadowStack() ?*[MAX_SHADOW_DEPTH]ShadowFrame {
	const ss = shadow_stack;
	asm volatile ("" ::: .{ .memory = true });
	return ss;
}

pub noinline fn getShadowCursor() ?*ShadowCursor {
	const sc = shadow_cursor;
	asm volatile ("" ::: .{ .memory = true });
	return sc;
}

/// Null when the shadow stack is full.
pub inline fn pushFrame(frame: ShadowFrame) ?usize {
	asm volatile ("" ::: .{ .memory = true });
	const ss = getShadowStack().?;
	const cursor = getShadowCursor().?;
	const idx = cursor.top;
	if (idx >= MAX_SHADOW_DEPTH) return null;
	op_counters.bump(.vpf_frame_push);
	ss[idx] = frame;
	asm volatile ("" ::: .{ .memory = true });
	cursor.top = idx + 1;
	return idx;
}

/// Null when this fiber claimed the frame, or the join obligation when a thief
/// had already taken it.
pub inline fn popAndClaim(frame_idx: usize) ?*JoinObligation {
	const ss = getShadowStack().?;
	const cursor = getShadowCursor().?;
	const frame = &ss[frame_idx];
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
	const ss = getShadowStack().?;
	const frame = &ss[frame_idx];
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
	const ss = getShadowStack().?;
	const frame = &ss[frame_idx];
	const child_obl = frame.child_obligation.load(.acquire).?;
	const boxed = value.box_transient();
	child_obl.fulfill(boxed);
}

/// Fresh reads, for the reason `getCurrentWorker` gives.
pub noinline fn getStackTrace() ?*[TRACE_CAP]trace.TraceFrame {
	const ts = trace_stack;
	asm volatile ("" ::: .{ .memory = true });
	return ts;
}

pub noinline fn getTraceTop() ?*usize {
	const tt = trace_top;
	asm volatile ("" ::: .{ .memory = true });
	return tt;
}

pub const tracePush = trace.tracePush;
pub const tracePop = trace.tracePop;
