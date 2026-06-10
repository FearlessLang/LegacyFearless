const std = @import("std");
const builtin = @import("builtin");
const testing = std.testing;

/// A thread-safe, lock-free, multi-producer multi-consumer (MPMC) bounded queue.
/// Based on the algorithm by Dmitry Vyukov.
///
/// Also suitable for MPSC (multi-producer single-consumer) usage, where the lack
/// of consumer contention makes dequeue slightly more efficient.
///
/// The queue holds exactly `size` items (the full buffer capacity is usable).
///
/// Note: The algorithm uses monotonically increasing position counters. Wraparound
/// would require 2^63+ operations, which is not practically achievable.
pub fn MpmcBoundedQueue(comptime T: type) type {
	return struct {
		const Self = @This();

		const Cell = struct {
			sequence: std.atomic.Value(usize),
			data: T,
		};

		// Cache line size constant (typically 64 bytes) to avoid false sharing.
		const cache_line_size = std.atomic.cache_line;

		mask: usize,
		buffer: []Cell,
		allocator: std.mem.Allocator,

		enqueue_pos: std.atomic.Value(usize),
		dequeue_pos: std.atomic.Value(usize),

		/// Initialize the queue with a specific capacity.
		/// Capacity must be a power of 2 and >= 2.
		pub fn init(allocator: std.mem.Allocator, size: usize) !*Self {
			if (size < 2 or (size & (size - 1)) != 0) {
				return error.InvalidSize;
			}

			const self = try allocator.create(Self);
			self.allocator = allocator;
			self.buffer = try allocator.alloc(Cell, size);
			self.mask = size - 1;

			for (self.buffer, 0..) |*cell, i| {
				cell.sequence = std.atomic.Value(usize).init(i);
				cell.data = undefined;
			}

			self.enqueue_pos = std.atomic.Value(usize).init(0);
			self.dequeue_pos = std.atomic.Value(usize).init(0);

			return self;
		}

		/// Deinitialize and free resources.
		pub fn deinit(self: *Self) void {
			self.allocator.free(self.buffer);
			self.allocator.destroy(self);
		}

		/// Try to enqueue an item. Returns true on success, false if the queue is full.
		pub fn enqueue(self: *Self, data: T) bool {
			var pos = self.enqueue_pos.load(.monotonic);
			while (true) {
				const cell = &self.buffer[pos & self.mask];
				const seq = cell.sequence.load(.acquire);

				// We use @bitCast to isize to handle wrapping arithmetic correctly.
				const diff = @as(isize, @bitCast(seq)) - @as(isize, @bitCast(pos));

				if (diff == 0) {
					// Cell is ready for writing.
					// Try to increment enqueue_pos to reserve this cell.
					if (self.enqueue_pos.cmpxchgWeak(pos, pos + 1, .monotonic, .monotonic)) |updated_pos| {
						// CAS failed, someone else moved enqueue_pos. Retry with new pos.
						pos = updated_pos;
					} else {
						// CAS succeeded. We reserved this cell.
						cell.data = data;
						// Release ordering ensures data write is visible before sequence update.
						cell.sequence.store(pos + 1, .release);
						return true;
					}
				} else if (diff < 0) {
					// Sequence is less than pos. The buffer is full.
					return false;
				} else {
					// diff > 0. Sequence is ahead of pos.
					// This implies pos is stale (we loaded an old pos, but the cell has been reused already).
					pos = self.enqueue_pos.load(.monotonic);
				}
			}
		}

		/// Try to dequeue an item. Returns the item on success, null if the queue is empty.
		pub fn dequeue(self: *Self) ?T {
			var pos = self.dequeue_pos.load(.monotonic);
			while (true) {
				const cell = &self.buffer[pos & self.mask];
				const seq = cell.sequence.load(.acquire);

				const diff = @as(isize, @bitCast(seq)) - @as(isize, @bitCast(pos + 1));

				if (diff == 0) {
					// Cell is ready for reading.
					if (self.dequeue_pos.cmpxchgWeak(pos, pos + 1, .monotonic, .monotonic)) |updated_pos| {
						pos = updated_pos;
					} else {
						// Success.
						const data = cell.data;
						// Release ordering ensures data read happens before sequence update.
						cell.sequence.store(pos + self.mask + 1, .release);
						return data;
					}
				} else if (diff < 0) {
					// Queue is empty.
					return null;
				} else {
					// diff > 0. Sequence is ahead, pos is stale.
					pos = self.dequeue_pos.load(.monotonic);
				}
			}
		}

		/// Returns true if the queue appears empty at the moment of the call.
		/// This is a snapshot and may be stale by the time it returns.
		pub fn isEmpty(self: *Self) bool {
			const pos = self.dequeue_pos.load(.monotonic);
			const cell = &self.buffer[pos & self.mask];
			const seq = cell.sequence.load(.acquire);
			const diff = @as(isize, @bitCast(seq)) - @as(isize, @bitCast(pos + 1));
			return diff < 0;
		}

		/// Returns an approximate count of items in the queue.
		/// This is a snapshot and may be stale by the time it returns.
		pub fn len(self: *Self) usize {
			const enq = self.enqueue_pos.load(.monotonic);
			const deq = self.dequeue_pos.load(.monotonic);
			if (enq >= deq) {
				return enq - deq;
			} else {
				// Wraparound case (extremely rare, requires 2^63+ ops)
				return 0;
			}
		}

		/// Returns the capacity of the queue.
		pub fn capacity(self: *Self) usize {
			return self.mask + 1;
		}

		/// Try to enqueue an item.
		pub fn enqueueWithSpin(self: *Self, data: T) void {
			while (true) {
				if (self.enqueue(data)) {
					return;
				}
				std.atomic.spinLoopHint();
			}
		}

		/// Try to dequeue an item.
		/// Returns the item.
		pub fn dequeueWithSpin(self: *Self) T {
			while (true) {
				if (self.dequeue()) |data| {
					return data;
				}
				std.atomic.spinLoopHint();
			}
		}
	};
}

test "MpmcBoundedQueue basic usage" {
	const Queue = MpmcBoundedQueue(i32);
	const queue = try Queue.init(testing.allocator, 4);
	defer queue.deinit();

	try testing.expect(queue.enqueue(1));
	try testing.expect(queue.enqueue(2));
	try testing.expect(queue.enqueue(3));
	try testing.expect(queue.enqueue(4));
	try testing.expect(!queue.enqueue(5)); // Full

	try testing.expectEqual(@as(?i32, 1), queue.dequeue());
	try testing.expectEqual(@as(?i32, 2), queue.dequeue());
	try testing.expectEqual(@as(?i32, 3), queue.dequeue());
	try testing.expectEqual(@as(?i32, 4), queue.dequeue());
	try testing.expectEqual(@as(?i32, null), queue.dequeue()); // Empty

	try testing.expect(queue.enqueue(5));
	try testing.expectEqual(@as(?i32, 5), queue.dequeue());
}

test "MpmcBoundedQueue helper methods" {
	const Queue = MpmcBoundedQueue(i32);
	const queue = try Queue.init(testing.allocator, 4);
	defer queue.deinit();

	try testing.expectEqual(@as(usize, 4), queue.capacity());
	try testing.expect(queue.isEmpty());
	try testing.expectEqual(@as(usize, 0), queue.len());

	try testing.expect(queue.enqueue(1));
	try testing.expect(!queue.isEmpty());
	try testing.expectEqual(@as(usize, 1), queue.len());

	try testing.expect(queue.enqueue(2));
	try testing.expectEqual(@as(usize, 2), queue.len());

	_ = queue.dequeue();
	try testing.expectEqual(@as(usize, 1), queue.len());

	_ = queue.dequeue();
	try testing.expect(queue.isEmpty());
	try testing.expectEqual(@as(usize, 0), queue.len());
}

test "MpmcBoundedQueue multithreaded" {
	const Queue = MpmcBoundedQueue(usize);
	const queue = try Queue.init(testing.allocator, 64);
	defer queue.deinit();

	const producer_count = 4;
	const consumer_count = 4;
	const items_per_producer = 10000;

	const Context = struct {
		q: *Queue,
		count: usize,
		done: std.atomic.Value(bool) = std.atomic.Value(bool).init(false),
	};
	var ctx = Context{ .q = queue, .count = items_per_producer };

	const producer = struct {
		fn run(c: *Context) void {
			var i: usize = 0;
			while (i < c.count) {
				if (c.q.enqueue(i)) {
					i += 1;
				} else {
					std.Thread.yield() catch {};
				}
			}
		}
	}.run;

	const consumer = struct {
		fn run(c: *Context, total_consumed: *usize) void {
			while (true) {
				if (c.q.dequeue()) |_| {
					_ = @atomicRmw(usize, total_consumed, .Add, 1, .monotonic);
				} else {
					if (c.done.load(.acquire)) break;
					std.Thread.yield() catch {};
				}
			}
		}
	}.run;

	var threads: [producer_count + consumer_count]std.Thread = undefined;
	var total_consumed: usize = 0;

	for (0..producer_count) |i| {
		threads[i] = try std.Thread.spawn(.{}, producer, .{&ctx});
	}
	for (0..consumer_count) |i| {
		threads[producer_count + i] = try std.Thread.spawn(.{}, consumer, .{ &ctx, &total_consumed });
	}

	for (0..producer_count) |i| {
		threads[i].join();
	}

	// Signal consumers that producers are done
	ctx.done.store(true, .release);

	for (0..consumer_count) |i| {
		threads[producer_count + i].join();
	}

	try testing.expectEqual(producer_count * items_per_producer, total_consumed);
}
