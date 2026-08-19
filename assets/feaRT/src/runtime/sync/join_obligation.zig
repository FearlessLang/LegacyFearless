const std = @import("std");
const Fiber = @import("../fiber.zig").Fiber;
const log = @import("../log.zig");

const FatPtr = @import("../objs.zig").FatPtr;

pub const JoinObligation = struct {
	/// The result, as two 64-bit atomics. `vt` doubles as the ready flag: null
	/// means not yet fulfilled.
	result_data: std.atomic.Value(u64),
	result_vt: std.atomic.Value(u64),
	waiter: std.atomic.Value(?*Fiber),
	/// `fulfill`'s last touch of this obligation. An obligation often lives in
	/// the waiting fiber's frame, and the waiter may consume the result and pop
	/// that frame before `fulfill`'s waiter swap runs, so every `wait` exit spins
	/// on this flag before letting the frame die.
	fulfill_done: std.atomic.Value(bool),

	pub fn init() JoinObligation {
		return .{
			.result_data = std.atomic.Value(u64).init(0),
			.result_vt = std.atomic.Value(u64).init(0),
			.waiter = std.atomic.Value(?*Fiber).init(null),
			.fulfill_done = std.atomic.Value(bool).init(false),
		};
	}

	pub fn fulfill(self: *JoinObligation, value: FatPtr) void {
		log.trace_scheduling(.obl_fulfill, @intFromPtr(self), @intFromPtr(value.vt), @bitCast(value.data));

		// Data then vt; a reader treats vt != 0 as ready.
		//
		// This store and the waiter swap below are a Dekker pair with `wait`'s
		// [waiter.store; vt.load]: one side must observe the other, or the wakeup
		// is lost and the fiber parks forever. All four ops need seq_cst;
		// release/acquire permits the StoreLoad reordering that loses it.
		self.result_data.store(@bitCast(value.data), .monotonic);
		self.result_vt.store(@intFromPtr(value.vt), .seq_cst);

		// A swap, not a load: claiming the waiter lets only one of fulfill and
		// wait's CAS act on it, so the fiber cannot be scheduled twice.
		const w = self.waiter.swap(null, .seq_cst);

		// Last touch of the obligation's memory. From here the waiter may free or
		// reuse it, so the code below reads only the fiber and the queue.
		self.fulfill_done.store(true, .release);

		if (w) |fiber| {
			log.trace_scheduling(.fiber_enqueue, @intFromPtr(fiber), @intFromEnum(fiber.state), @intFromPtr(self));
			@import("../worker.zig").enqueueFiber(fiber);
		}
	}

	/// The caller must have established that vt is non-zero.
	fn loadResult(self: *JoinObligation) FatPtr {
		// Acquire on vt synchronises with the release in `fulfill`.
		const vt = self.result_vt.load(.acquire);
		const data = self.result_data.load(.monotonic);
		return .{
			.data = @bitCast(data),
			.vt = @ptrFromInt(vt),
		};
	}

	fn isReady(self: *JoinObligation) bool {
		return self.result_vt.load(.acquire) != 0;
	}

	/// Spin until `fulfill` is done with this obligation's memory, so the caller
	/// may free it, usually by popping its own frame. The window is two
	/// instructions, so this almost never iterates.
	fn awaitFulfillDone(self: *JoinObligation) void {
		while (!self.fulfill_done.load(.acquire)) {
			std.atomic.spinLoopHint();
		}
	}

	pub fn wait(self: *JoinObligation, worker: anytype) FatPtr {
		if (self.isReady()) {
			const vt = self.result_vt.load(.acquire);
			const data = self.result_data.load(.monotonic);
			log.trace_scheduling(.obl_wait_ready, @intFromPtr(self), data, vt);
			if (vt == 0) {
				log.trace_scheduling(.obl_wait_ready, @intFromPtr(self), 0xDEAD0001, data);
				@trap();
			}
			self.awaitFulfillDone();
			return .{ .data = @bitCast(data), .vt = @ptrFromInt(vt) };
		}

		const current_fiber = worker.current_fiber.?;
		// Close the resume gate before publishing this fiber as a waiter. Once
		// the waiter store makes it visible, `fulfill` can swap and enqueue it
		// and another worker can dequeue it, and the closed gate is what makes
		// that worker spin until this fiber has saved its state.
		current_fiber.resume_gate.store(false, .release);
		// Dekker pair with `fulfill`'s [vt.store; waiter.swap]; see there for why
		// this store and the re-check below are both seq_cst.
		self.waiter.store(current_fiber, .seq_cst);

		if (self.result_vt.load(.seq_cst) != 0) {
			// Try to reclaim the waiter registration before the fulfiller does.
			if (self.waiter.cmpxchgStrong(current_fiber, null, .acquire, .monotonic) == null) {
				// Cleared the waiter before `fulfill` saw it.
				self.awaitFulfillDone();
				return self.loadResult();
			}
			// The fulfiller already swapped the waiter to null and enqueued this
			// fiber, so it MUST park to absorb that enqueue. Without the park the
			// fiber is scheduled twice.
			log.trace_scheduling(.obl_wait_park, @intFromPtr(self), @intFromPtr(current_fiber), 1);
			worker.parkCurrentFiber();
			self.awaitFulfillDone();
			return self.loadResult();
		}

		log.trace_scheduling(.obl_wait_park, @intFromPtr(self), @intFromPtr(current_fiber), 0);
		worker.parkCurrentFiber();

		if (!self.isReady()) {
			log.trace_scheduling(.obl_wait_resume, @intFromPtr(self), 0xDEAD0003, 0);
			@trap();
		}
		self.awaitFulfillDone();
		return self.loadResult();
	}
};
