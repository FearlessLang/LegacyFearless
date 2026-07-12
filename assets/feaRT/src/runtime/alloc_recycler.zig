//! Per-thread, size-classed object recycler that bypasses bdwgc's global free
//! lock for the common allocation/free pattern of small object literals.
//!
//! Correctness depends on:
//!   1. Each thread calling `register_thread()` before its first `push`. bdwgc
//!      does not scan TLS by default, so without explicit GC_add_roots the
//!      cycle collector would free pool entries from under us -- and the next
//!      pop would hand back storage bdwgc has stitched into its intrinsic
//!      free list, corrupting that list on the next header write.
//!   2. The caller zeroing the captures region before `push`, so the pool
//!      slots (which the conservative scan will now traverse) don't pin
//!      children we already RC-decremented.

const std = @import("std");
const log = @import("log.zig");
const gc = @import("gc.zig");
const destroyer = @import("destroyer.zig");

const MissReason = enum(u8) { class_out_of_range = 0, class_full = 1 };

inline fn traceMiss(size: usize, reason: MissReason, align_log2: u8) void {
	log.trace_alloc_caching(.alloc_recycle_miss, size, @intFromEnum(reason), align_log2);
}

// Every class size must be ≥ sizeof(destroyer.Node) so that on drain we can
// overlay a `?*Node` link onto the first bytes of each slot.
const CLASS_SIZES = [_]usize{ 8, 16, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120, 128, 136, 144, 152 };
comptime {
	for (CLASS_SIZES) |s| std.debug.assert(s >= @sizeOf(destroyer.Node));
}
const NUM_CLASSES: usize = CLASS_SIZES.len;
const SLOTS_PER_CLASS: usize = 8192;

threadlocal var pools: [NUM_CLASSES][SLOTS_PER_CLASS]?[*]u8 =
	.{.{null} ** SLOTS_PER_CLASS} ** NUM_CLASSES;
threadlocal var pool_lens: [NUM_CLASSES]u16 = .{0} ** NUM_CLASSES;

pub const PoolMemory = struct { start: [*]u8, end: [*]u8 };
pub fn get_pool_info() PoolMemory {
	const start: [*]u8 = @ptrCast(&pools);
	const end: [*]u8 = start + @sizeOf(@TypeOf(pools));
	return .{ .start = start, .end = end };
}

inline fn classIndex(size: usize, comptime exact: bool) ?usize {
	for (CLASS_SIZES, 0..) |cs, i| {
		if (exact) {
			if (cs == size) return i;
		} else {
			if (cs >= size) return i;
		}
	}
	return null;
}

pub const RecycledBlock = struct {
	ptr: [*]u8,
	real_size: usize,
};

/// Try to take a recycled object body of the given size and alignment.
/// Returns null on miss; caller falls back to a fresh allocation.
pub fn pop(size: usize, _: u8) ?RecycledBlock {
	const idx = classIndex(size, false) orelse return null;
	const len = pool_lens[idx];
	if (len == 0) return null;
	const new_len = len - 1;
	pool_lens[idx] = new_len;
	const ptr = pools[idx][new_len];
	pools[idx][new_len] = null;
	const real_size = CLASS_SIZES[idx];
	return RecycledBlock{ .ptr = ptr.?, .real_size = real_size };
}

/// Try to recycle an object body. The caller must have already run any
/// `drop_fn` and zeroed the captures region (so bdwgc's conservative scan
/// of the threadlocal pool can't pin already-decremented children).
/// Returns false if the size class is unsupported, alignment doesn't
/// match, or the per-class pool is full.
pub fn push(ptr: [*]u8, size: usize, align_log2: u8) bool {
	const idx = classIndex(size, true) orelse {
		traceMiss(size, .class_out_of_range, align_log2);
		return false;
	};
	var len = pool_lens[idx];
	if (len >= SLOTS_PER_CLASS) {
		// log.trace_alloc_caching(.alloc_recycle_drain, idx, SLOTS_PER_CLASS, size);
		// Build a chain of dead slots locally with non-atomic stores, then
		// splice the whole chain onto the destroyer's Treiber stack with one
		// CAS. The first slot processed becomes the chain's tail (its `next`
		// is set to the initial null); subsequent slots get prepended.
		var chain_head: ?*destroyer.Node = null;
		var chain_tail: ?*destroyer.Node = null;
		for (0..len) |j| {
			const node: *destroyer.Node = @ptrCast(@alignCast(pools[idx][j].?));
			node.next = chain_head;
			if (chain_tail == null) chain_tail = node;
			chain_head = node;
			pools[idx][j] = null;
		}
		if (chain_head) |h| destroyer.submit_chain(h, chain_tail.?);
		pool_lens[idx] = 0;
		len = 0;
	}
	pools[idx][len] = ptr;
	pool_lens[idx] = len + 1;
	return true;
}
