const std = @import("std");
const testing = std.testing;
const fence = @import("fence.zig");

/// A growable Chase-Lev work-stealing deque.
///
/// One worker owns the deque and is the only thread that may `push` and `take`,
/// both at the bottom end. Any other thread may `steal` from the top. The owner
/// thus gets the newest item, warmest in cache, and a thief gets the oldest,
/// which in a fork-join program is the largest remaining subtree.
///
/// `push` never fails: the buffer doubles when it fills, so there is no overflow
/// path that could make an item unreachable.
///
/// `top` and `bottom` are monotonic counters, never pointers, which is what
/// makes recycled items safe: an item taken, recycled and pushed again at the
/// same address cannot produce an ABA. Intrusive links would need hazard
/// pointers or tagging for the same guarantee.
///
/// Chase and Lev (2005), with the weak-memory orderings of Le, Pop, Cohen and
/// Nardelli (2013). The two `seq_cst` fences those call for come from
/// `fence.seqCst`.
pub fn ChaseLevDeque(comptime T: type) type {
	return struct {
		const Self = @This();

		/// `abort` means a concurrent operation won the race for the same item.
		/// The deque may still hold work.
		pub const Steal = union(enum) {
			task: T,
			empty,
			abort,
		};

		const Buffer = struct {
			mask: i64,
			cells: []std.atomic.Value(T),
			/// The buffer this one replaced. A thief may still be reading it
			/// through a pointer loaded before the swap, so every buffer lives as
			/// long as the deque.
			prev: ?*Buffer,

			fn get(self: *Buffer, index: i64) T {
				return self.cells[@intCast(index & self.mask)].load(.monotonic);
			}

			fn put(self: *Buffer, index: i64, item: T) void {
				self.cells[@intCast(index & self.mask)].store(item, .monotonic);
			}
		};

		const cache_line = std.atomic.cache_line;
		/// The oldest index still held. Thieves advance it.
		top: std.atomic.Value(i64) align(cache_line),
		/// One past the newest index. Only the owner writes it.
		bottom: std.atomic.Value(i64) align(cache_line),
		buffer: std.atomic.Value(*Buffer),
		allocator: std.mem.Allocator,

		/// `capacity` must be a power of two and at least 2. A starting size only.
		pub fn init(allocator: std.mem.Allocator, capacity: usize) !*Self {
			if (capacity < 2 or (capacity & (capacity - 1)) != 0) return error.InvalidSize;

			const buffer = try newBuffer(allocator, capacity, null);
			const self = try allocator.create(Self);
			self.* = .{
				.top = std.atomic.Value(i64).init(0),
				.bottom = std.atomic.Value(i64).init(0),
				.buffer = std.atomic.Value(*Buffer).init(buffer),
				.allocator = allocator,
			};
			return self;
		}

		/// Frees the deque and every buffer it allocated. Safe only once no thief
		/// can still be inside `steal`, because a thief may hold a pointer to a
		/// buffer `grow` has replaced.
		pub fn deinit(self: *Self) void {
			var buffer: ?*Buffer = self.buffer.load(.monotonic);
			while (buffer) |b| {
				const prev = b.prev;
				self.allocator.free(b.cells);
				self.allocator.destroy(b);
				buffer = prev;
			}
			self.allocator.destroy(self);
		}

		fn newBuffer(allocator: std.mem.Allocator, capacity: usize, prev: ?*Buffer) !*Buffer {
			const buffer = try allocator.create(Buffer);
			buffer.* = .{
				.mask = @as(i64, @intCast(capacity)) - 1,
				.cells = try allocator.alloc(std.atomic.Value(T), capacity),
				.prev = prev,
			};
			return buffer;
		}

		/// Owner only. Grows the buffer rather than refusing.
		pub fn push(self: *Self, item: T) !void {
			const b = self.bottom.load(.monotonic);
			const t = self.top.load(.acquire);
			var a = self.buffer.load(.monotonic);

			if (b - t > a.mask) {
				a = try self.grow(a, b, t);
			}

			a.put(b, item);
			// Pairs with the acquire load of `bottom` in `steal`, so a thief that
			// sees this index also sees the cell write.
			self.bottom.store(b + 1, .release);
		}

		fn grow(self: *Self, old: *Buffer, b: i64, t: i64) !*Buffer {
			const capacity = (old.mask + 1) * 2;
			const new = try newBuffer(self.allocator, @intCast(capacity), old);
			var i = t;
			while (i < b) : (i += 1) {
				new.put(i, old.get(i));
			}
			self.buffer.store(new, .release);
			return new;
		}

		/// Owner only.
		pub fn take(self: *Self) ?T {
			const b = self.bottom.load(.monotonic) - 1;
			// An empty deque is the common answer on a scheduler probe and needs
			// no ordering: only the owner pushes, so nothing can add an item
			// behind this test. That keeps a poll down to two loads.
			if (b < self.top.load(.monotonic)) return null;

			const a = self.buffer.load(.monotonic);
			// Claim the slot, then read `top`. The fence stops the store sinking
			// below the load, the reordering that would let the owner and a thief
			// both take the last item.
			self.bottom.store(b, .monotonic);
			fence.seqCst();
			const t = self.top.load(.monotonic);

			if (t > b) {
				// Restore the canonical empty state.
				self.bottom.store(b + 1, .monotonic);
				return null;
			}

			const item = a.get(b);
			if (t != b) return item;

			// The last item, so a thief may be claiming this same index.
			const lost = self.top.cmpxchgStrong(t, t + 1, .seq_cst, .monotonic) != null;
			self.bottom.store(b + 1, .monotonic);
			return if (lost) null else item;
		}

		/// For any thread that does not own the deque.
		pub fn steal(self: *Self) Steal {
			const t = self.top.load(.acquire);
			// `empty` is always a safe answer, so a thief that already sees an
			// empty deque needs no fence. Only a claim needs the ordering.
			if (t >= self.bottom.load(.acquire)) return .empty;

			// Pairs with the fence in `take`: it puts this load of `bottom` after
			// the load of `top` in the same total order that puts the owner's
			// store of `bottom` before its load of `top`.
			fence.seqCst();
			const b = self.bottom.load(.acquire);
			if (t >= b) return .empty;

			// Pairs with the release store in `grow`, so the buffer read below is
			// at least as new as the index range just observed.
			const a = self.buffer.load(.acquire);
			const item = a.get(t);
			if (self.top.cmpxchgStrong(t, t + 1, .seq_cst, .monotonic) != null) return .abort;
			return .{ .task = item };
		}

		/// Stale as soon as it returns.
		pub fn len(self: *Self) usize {
			const b = self.bottom.load(.monotonic);
			const t = self.top.load(.monotonic);
			return if (b > t) @intCast(b - t) else 0;
		}
	};
}

test "ChaseLevDeque push and take are LIFO" {
	const Deque = ChaseLevDeque(usize);
	const deque = try Deque.init(testing.allocator, 4);
	defer deque.deinit();

	try deque.push(1);
	try deque.push(2);
	try deque.push(3);

	try testing.expectEqual(@as(usize, 3), deque.len());
	try testing.expectEqual(@as(?usize, 3), deque.take());
	try testing.expectEqual(@as(?usize, 2), deque.take());
	try testing.expectEqual(@as(?usize, 1), deque.take());
	try testing.expectEqual(@as(?usize, null), deque.take());
}

test "ChaseLevDeque steal is FIFO" {
	const Deque = ChaseLevDeque(usize);
	const deque = try Deque.init(testing.allocator, 4);
	defer deque.deinit();

	try deque.push(1);
	try deque.push(2);
	try deque.push(3);

	try testing.expectEqual(@as(usize, 1), deque.steal().task);
	try testing.expectEqual(@as(usize, 2), deque.steal().task);
	try testing.expectEqual(@as(?usize, 3), deque.take());
	try testing.expect(deque.steal() == .empty);
}

test "ChaseLevDeque grows past its initial capacity" {
	const Deque = ChaseLevDeque(usize);
	const deque = try Deque.init(testing.allocator, 2);
	defer deque.deinit();

	const count = 1000;
	for (0..count) |i| try deque.push(i);
	try testing.expectEqual(@as(usize, count), deque.len());

	var i: usize = count;
	while (i > 0) {
		i -= 1;
		try testing.expectEqual(@as(?usize, i), deque.take());
	}
	try testing.expectEqual(@as(?usize, null), deque.take());
}

test "ChaseLevDeque one owner and many thieves lose nothing" {
	const Deque = ChaseLevDeque(usize);
	const deque = try Deque.init(testing.allocator, 4);
	defer deque.deinit();

	const thief_count = 4;
	const item_count = 100_000;

	const Context = struct {
		deque: *Deque,
		seen: [item_count]std.atomic.Value(u8),
		pushed_all: std.atomic.Value(bool),

		fn record(self: *@This(), item: usize) void {
			_ = self.seen[item].fetchAdd(1, .monotonic);
		}
	};

	const ctx = try testing.allocator.create(Context);
	defer testing.allocator.destroy(ctx);
	ctx.deque = deque;
	for (&ctx.seen) |*slot| slot.* = std.atomic.Value(u8).init(0);
	ctx.pushed_all = std.atomic.Value(bool).init(false);

	const thief = struct {
		fn run(c: *Context) void {
			while (true) {
				switch (c.deque.steal()) {
					.task => |item| c.record(item),
					.abort => std.atomic.spinLoopHint(),
					.empty => {
						if (c.pushed_all.load(.acquire) and c.deque.len() == 0) return;
						std.atomic.spinLoopHint();
					},
				}
			}
		}
	}.run;

	var threads: [thief_count]std.Thread = undefined;
	for (&threads) |*t| t.* = try std.Thread.spawn(.{}, thief, .{ctx});

	for (0..item_count) |i| {
		try deque.push(i);
		// Interleave owner takes with the thieves, to exercise both ends.
		if (i % 3 == 0) {
			if (deque.take()) |item| ctx.record(item);
		}
	}
	while (deque.take()) |item| ctx.record(item);
	ctx.pushed_all.store(true, .release);

	for (&threads) |*t| t.join();

	for (&ctx.seen, 0..) |*slot, i| {
		const times = slot.load(.monotonic);
		if (times != 1) {
			std.debug.print("item {d} was taken {d} times\n", .{ i, times });
			return error.ItemNotTakenExactlyOnce;
		}
	}
}
