const std = @import("std");

pub const MAX_SHADOW_DEPTH = 256;

/// Marks a frame that its own fiber claimed. A promoted frame holds its join
/// obligation instead. The value is `@ptrFromInt(alignof)` to satisfy pointer
/// alignment.
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
/// Releases the reference-counted fields of the locals. The worker id is a
/// parameter because `Worker.recycleTask` runs this off a fiber stack.
pub const LocalsDropHook = *const fn (*anyopaque, releasing_worker_id: u32) void;

pub const ShadowFrame = struct {
	target_method: u64,
	join_obligation: ?*JoinObligation,
	child_obligation: std.atomic.Value(?*JoinObligation),
	locals: *anyopaque,
	locals_size: usize,
	retain_fn: LocalsRetainHook,
	drop_fn: LocalsDropHook,
	thief_fn: *const fn (*anyopaque, ?*JoinObligation) FatPtr,
	/// The published task, so the owning fiber can take back unstolen work. Only
	/// the owning fiber writes it.
	task: std.atomic.Value(?*worker_mod.StolenTask) = std.atomic.Value(?*worker_mod.StolenTask).init(null),
	/// The cancellation scope at push time. The thief gets this scope, not the
	/// current one: a nested flow terminal below this frame can cancel its own
	/// scope, and that must not reach the stolen work.
	scope: ?*scope_mod.Scope = null,
};

pub const ShadowCursor = struct {
	top: usize = 0,

	/// Promoted frames are a prefix: `[0, lowest_unpromoted)` is promoted and
	/// `[lowest_unpromoted, top)` is not.
	lowest_unpromoted: usize = 0,
};

pub const TRACE_CAP = 256;

/// Null off a fiber stack.
///
/// Only `pushFrame` and `popAndClaim` mask the stack pointer, because only
/// generated code calls them. Other code must get the fiber from the worker: a
/// mask on a non-fiber stack reads unrelated memory.
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

/// Null when this fiber claimed the frame. Otherwise the join obligation of the
/// promotion.
pub inline fn popAndClaim(frame_idx: usize) ?*JoinObligation {
	const f = fiber_mod.currentFiber();
	const cursor = &f.shadow_cursor;
	const frame = &f.shadow_frames[frame_idx];
	// Plain, not atomic: only the owning fiber writes this field, and a fiber
	// runs on one worker at a time. On migration, the `resume_gate`
	// release/acquire pair publishes it, as for `ShadowCursor`.
	const prev = frame.join_obligation;
	if (prev == null) frame.join_obligation = CLAIMED;
	cursor.top -= 1;
	// Keeps the prefix boundary at or below `top`.
	cursor.lowest_unpromoted = @min(cursor.lowest_unpromoted, cursor.top);
	return if (prev) |obl| obl else null;
}

pub fn freeObligation(obl: *JoinObligation) void {
	if (worker_mod.getCurrentWorker()) |w| {
		w.recycleObligation(obl);
	} else {
		op_counters.bump(.obligation_free);
		std.heap.c_allocator.destroy(obl);
	}
}

/// Takes back a promotion that no worker took. True: this fiber must run the
/// work. False: a thief owns it, and the caller waits on `obligation`.
///
/// The task stays alive during this call. A thief finishes only after the
/// caller fulfills the child obligation, which is after a false return. After a
/// true return, the dequeuing worker retires the task.
pub fn reclaimPromotion(frame_idx: usize, obligation: *JoinObligation) bool {
	const frame = &getShadowStack().?[frame_idx];
	const task = frame.task.load(.acquire) orelse return false;
	if (task.claimed.cmpxchgStrong(false, true, .acq_rel, .acquire) != null) return false;

	op_counters.bump(.promotion_reclaimed);
	// The winner owns both obligations. No other code holds them.
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
