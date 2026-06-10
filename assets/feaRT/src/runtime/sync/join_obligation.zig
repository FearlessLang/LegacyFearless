const std = @import("std");
const Fiber = @import("../fiber.zig").Fiber;
const MpmcBoundedQueue = @import("mpmc.zig").MpmcBoundedQueue;
const log = @import("../log.zig");

const FatPtr = @import("../objs.zig").FatPtr;

pub const JoinObligation = struct {
	/// Result stored as two separate 64-bit atomics.
	/// The vt pointer doubles as the "ready" flag: null vt means not yet fulfilled.
	result_data: std.atomic.Value(u64),
	result_vt: std.atomic.Value(u64),
	waiter: std.atomic.Value(?*Fiber),

	pub fn init() JoinObligation {
		return .{
			.result_data = std.atomic.Value(u64).init(0),
			.result_vt = std.atomic.Value(u64).init(0),
			.waiter = std.atomic.Value(?*Fiber).init(null),
		};
	}

	pub fn fulfill(self: *JoinObligation, value: FatPtr, ready_queue: *MpmcBoundedQueue(*Fiber)) void {
		log.trace_scheduling(.obl_fulfill, @intFromPtr(self), @intFromPtr(value.vt), @bitCast(value.data));

		// Store data first, then vt with release.
		// The vt store is the "publication" — readers check vt != 0 to know
		// the result is ready. Release on vt ensures data is visible first.
		self.result_data.store(@bitCast(value.data), .monotonic);
		self.result_vt.store(@intFromPtr(value.vt), .release);

		// SWAP (not load) — atomically claim the waiter so that only one
		// side (fulfill or wait's CAS) can act on it, preventing double-schedule.
		const w = self.waiter.swap(null, .acq_rel);
		if (w) |fiber| {
			log.trace_scheduling(.fiber_enqueue, @intFromPtr(fiber), @intFromEnum(fiber.state), @intFromPtr(self));
			fiber.state = .Ready;
			ready_queue.enqueueWithSpin(fiber);
		}
	}

	/// Load the result. Caller must ensure vt is non-zero (result is ready).
	fn loadResult(self: *JoinObligation) FatPtr {
		// Acquire on vt synchronizes with the release in fulfill().
		const vt = self.result_vt.load(.acquire);
		const data = self.result_data.load(.monotonic);
		return .{
			.data = @bitCast(data),
			.vt = @ptrFromInt(vt),
		};
	}

	/// Check if result is ready (vt != 0).
	fn isReady(self: *JoinObligation) bool {
		return self.result_vt.load(.acquire) != 0;
	}

	pub fn wait(self: *JoinObligation, worker: anytype) FatPtr {
		// Path 1: already ready
		if (self.isReady()) {
			const vt = self.result_vt.load(.acquire);
			const data = self.result_data.load(.monotonic);
			log.trace_scheduling(.obl_wait_ready, @intFromPtr(self), data, vt);
			if (vt == 0) {
				log.trace_scheduling(.obl_wait_ready, @intFromPtr(self), 0xDEAD0001, data);
				@trap();
			}
			return .{ .data = @bitCast(data), .vt = @ptrFromInt(vt) };
		}

		const current_fiber = worker.current_fiber.?;
		// Close the resume gate BEFORE publishing ourselves as a waiter.
		// Once waiter.store makes us visible, fulfill() can swap+enqueue us
		// and another worker can dequeue us. The gate must already be closed
		// so that dequeuing worker spins until we've fully saved our state.
		current_fiber.resume_gate.store(false, .release);
		self.waiter.store(current_fiber, .release);

		// Path 2: ready after storing waiter (TOCTOU re-check)
		if (self.isReady()) {
			// Try to reclaim our waiter registration before fulfiller claims it.
			if (self.waiter.cmpxchgStrong(current_fiber, null, .acquire, .monotonic) == null) {
				// CAS succeeded — we cleared waiter before fulfill saw it. Safe to return.
				return self.loadResult();
			}
			// CAS failed — fulfiller already swapped waiter to null and enqueued us.
			// We MUST park to absorb the enqueue, otherwise the fiber is double-scheduled.
			// Gate already closed above before waiter.store.
			log.trace_scheduling(.obl_wait_park, @intFromPtr(self), @intFromPtr(current_fiber), 1);
			worker.parkCurrentFiber();
			return self.loadResult();
		}

		// Path 3: park and wait
		// Gate already closed above before waiter.store.
		log.trace_scheduling(.obl_wait_park, @intFromPtr(self), @intFromPtr(current_fiber), 0);
		worker.parkCurrentFiber();

		// After resume: fulfill must have happened
		if (!self.isReady()) {
			log.trace_scheduling(.obl_wait_resume, @intFromPtr(self), 0xDEAD0003, 0);
			@trap();
		}
		return self.loadResult();
	}
};
