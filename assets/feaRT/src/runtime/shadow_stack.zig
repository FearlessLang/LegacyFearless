const std = @import("std");

pub const MAX_SHADOW_DEPTH = 256;

/// Sentinel value: the application has claimed this frame before the signal handler.
/// We use @ptrFromInt(alignof) to satisfy pointer alignment requirements.
pub const CLAIMED: *JoinObligation = @ptrFromInt(@alignOf(JoinObligation));

pub const JoinObligation = @import("sync/join_obligation.zig").JoinObligation;
const objs = @import("objs.zig");
const FatPtr = objs.FatPtr;
const worker_mod = @import("worker.zig");
const build_options = @import("build_options");
const gc = @import("gc.zig");
const trace = @import("./errors/trace.zig");

pub const LocalsRetainHook = *const fn (*anyopaque, *anyopaque) void;
pub const LocalsDropHook = *const fn (*anyopaque) void;

pub const ShadowFrame = struct {
	target_method: u64,
	join_obligation: std.atomic.Value(?*JoinObligation),
	child_obligation: std.atomic.Value(?*JoinObligation),
	locals: *anyopaque,
	locals_size: usize,
	retain_fn: LocalsRetainHook,
	/// This function is called on-drop by the reference counting system. This may never be called if the GC
	/// kills the object instead of RC. This should be okay -- just means a bit more work for the GC to do later.
	drop_fn: LocalsDropHook,
	thief_fn: *const fn (*anyopaque, ?*JoinObligation) FatPtr,
};

/// Thread-local pointer to current fiber's shadow stack frames array.
/// Null when no fiber is running on this thread.
pub threadlocal var shadow_stack: ?*[MAX_SHADOW_DEPTH]ShadowFrame = null;

/// Thread-local pointer to current fiber's shadow stack top index.
/// Null when no fiber is running on this thread.
pub threadlocal var shadow_top: ?*usize = null;

pub const TRACE_CAP = 256;

/// Thread-local pointer to the current fiber's stack trace.
/// Null when no fiber is running, or when `trace_frames` is disabled.
/// See [errors/trace.zig] for more info
pub threadlocal var trace_stack: ?*[TRACE_CAP]trace.TraceFrame = null;

/// Thread-local pointer to the current fiber's trace top index (true depth,
/// so ring-buffer overflow can be reported). Null when no fiber is running.
pub threadlocal var trace_top: ?*usize = null;

/// Fresh reads of TLS shadow stack pointers. See getCurrentWorker() for rationale.
pub noinline fn getShadowStack() ?*[MAX_SHADOW_DEPTH]ShadowFrame {
	const ss = shadow_stack;
	asm volatile ("" ::: .{ .memory = true });
	return ss;
}

pub noinline fn getShadowTop() ?*usize {
	const st = shadow_top;
	asm volatile ("" ::: .{ .memory = true });
	return st;
}

/// Push a shadow frame onto the current fiber's shadow stack.
/// Returns the frame index, or null if the shadow stack is full.
pub inline fn pushFrame(frame: ShadowFrame) ?usize {
	asm volatile ("" ::: .{ .memory = true });
	const ss = getShadowStack().?;
	const ss_top_ptr = getShadowTop().?;
	const idx = ss_top_ptr.*;
	if (idx >= MAX_SHADOW_DEPTH) return null;
	ss[idx] = frame;
	asm volatile ("" ::: .{ .memory = true });
	ss_top_ptr.* += 1;
	return idx;
}

/// Pop and attempt to claim the frame at frame_idx.
/// Returns null if we claimed it (not stolen), or the join obligation if stolen.
pub inline fn popAndClaim(frame_idx: usize) ?*JoinObligation {
	const ss = getShadowStack().?;
	const ss_top_ptr = getShadowTop().?;
	const frame = &ss[frame_idx];
	const prev = frame.join_obligation.cmpxchgStrong(null, CLAIMED, .acquire, .acquire);
	ss_top_ptr.* -= 1;
	return if (prev) |obl| obl else null;
}

/// Recycle a JoinObligation -- pushes to current worker's freelist if possible,
/// otherwise falls back to c_allocator.destroy.
pub fn freeObligation(obl: *JoinObligation) void {
	if (worker_mod.getCurrentWorker()) |w| {
		w.recycleObligation(obl);
	} else {
		std.heap.c_allocator.destroy(obl);
	}
}

/// Deliver r1 to the thief via the child obligation in the frame.
pub inline fn fulfillChildObligation(frame_idx: usize, value: FatPtr) void {
	const ss = getShadowStack().?;
	const frame = &ss[frame_idx];
	const child_obl = frame.child_obligation.load(.acquire).?;
	const w = worker_mod.getCurrentWorker().?;
	const boxed = value.box_transient();
	child_obl.fulfill(boxed, w.ready_queue);
}

/// Fresh reads of the trace TLS pointers. See getCurrentWorker() for rationale.
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
