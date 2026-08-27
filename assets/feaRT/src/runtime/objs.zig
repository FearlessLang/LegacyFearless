const std = @import("std");
const builtin = @import("builtin");
const assert = std.debug.assert;

const gc = @import("gc.zig");
const allocator = gc.allocator;
const alloc_recycler = @import("alloc_recycler.zig");

const nat_rt = @import("intrinsics/nat.zig");
const int_rt = @import("intrinsics/int.zig");
const float_rt = @import("intrinsics/float.zig");
const byte_rt = @import("intrinsics/byte.zig");
const var_rt = @import("intrinsics/var.zig");
const list_rt = @import("intrinsics/list.zig");
const isopod_rt = @import("intrinsics/isopod.zig");
const error_rt = @import("error.zig");
const op_counters = @import("op_counters.zig");
const worker_mod = @import("worker.zig");

// FNV-1a 64-bit, evaluated at comptime.
pub fn hash_signature(comptime str: []const u8) u64 {
	var hash: u64 = 14695981039346656037;
	const prime: u64 = 1099511628211;

	for (str) |c| {
		hash ^= @as(u64, c);
		hash *%= prime;
	}
	return hash;
}

/// Biased reference counting, after Choi et al. (PACT'18). A heap object records
/// the worker that built it. That worker adds and removes references with plain
/// loads and stores on `biased`; every other worker uses atomics on `shared`.
/// The true reference count is `biased + count(shared)`. Once that worker gives up
/// the object, the two halves merge and the object stays shared-only.
///
/// `extern` keeps the C layout, so the header is always the first bytes.
pub const ObjectHeader = extern struct {
	/// Bits 2..31 hold a signed reference count; bit 0 is `MERGED` and bit 1 is
	/// `QUEUED`. The count moves in `UNIT` steps, so it never carries into the
	/// flags, and the flags are only set, never cleared.
	shared: std.atomic.Value(u32),
	/// References held for the worker named by `biased_worker_id`. No other thread
	/// may touch it.
	biased: u32,
	drop_fn: ?DropFn,
	alloc_size: usize,
	/// The worker whose `biased` half covers references, as its id plus one, or 0
	/// when no worker holds a biased reference. Atomic because other workers read
	/// it while this one clears it, but `.monotonic` is enough: only a thread that
	/// reads its own id can take a biased path, and every other reader sees a
	/// foreign id or 0 and takes the shared path.
	biased_worker_id: std.atomic.Value(u32),
	alloc_align_log2: u8,

	/// The reference count as seen by a thread with no biased half. A unit test
	/// binds no worker, so every operation takes the shared path and this is the
	/// whole count.
	pub fn refCountForTest(self: *const ObjectHeader) u32 {
		return @intCast(sharedCount(self.shared.load(.monotonic)));
	}
};

comptime {
	// The recycler matches exact size classes, so a bigger header would push
	// every object into the next class.
	assert(@sizeOf(ObjectHeader) == 32);
}

/// Releases the reference-counted captures of a dying object. The second
/// argument is the worker to release them on behalf of: a drop chain can start
/// on the scheduler stack, where the stack pointer masks to no fiber, so the
/// worker travels with the call.
pub const DropFn = *const fn (*anyopaque, releasing_worker_id: u32) callconv(.c) void;
pub const BoxFn = *const fn (FatPtr) callconv(.c) FatPtr;

const MERGED: u32 = 1;
const QUEUED: u32 = 2;
const UNIT: u32 = 4;

/// For objects that must never be freed: static singletons and stack
/// transients. The storage-mode switch returns before any reference counting
/// path, so this is only safety padding.
const IMMORTAL_COUNT: u32 = @as(u32, std.math.maxInt(i32)) >> 2;
const IMMORTAL_SHARED: u32 = IMMORTAL_COUNT << 2;

/// 4096 is the most cores Linux supports.
const REFERENCER_MARGIN: u32 = 4096;
const BIASED_LIMIT: u32 = std.math.maxInt(u32) - REFERENCER_MARGIN;
const SHARED_LIMIT: i32 = @as(i32, @intCast(IMMORTAL_COUNT)) - REFERENCER_MARGIN;

inline fn sharedCount(word: u32) i32 {
	return @as(i32, @bitCast(word)) >> 2;
}

inline fn isMerged(word: u32) bool {
	return word & MERGED != 0;
}

inline fn isQueued(word: u32) bool {
	return word & QUEUED != 0;
}

pub const StorageMode = enum(u8) {
	/// No ObjectHeader: `data` is the value.
	primitive,
	/// Heap header, refcounted, freed through the recycler or GC_free.
	heap,
	/// Static header, skipped on share and rc_decrement. The immortal reference
	/// count these carry is padding: the fast path tests `storage_mode`.
	singleton,
	/// Stack allocated and non-escaping. RC operations are no-ops; a path that
	/// crosses tasks must call the vtable boxing hook first.
	transient,
	/// No ObjectHeader: `data` points at the value. Unlike `.primitive`, these
	/// have their own clean-up in `release`.
	primitiveContainer,
};

pub const VTable = struct {
	type_name: []const u8,
	// Separate key and value slices, which is SIMD-friendly.
	hashes: []const u64,
	// Untyped: call sites cast to the signature they need.
	methods: []const *const anyopaque,
	method_names: []const []const u8,
	storage_mode: StorageMode = .heap,
	drop_fn: ?DropFn = null,
	box_fn: ?BoxFn = null,

	pub fn method_name(self: *const VTable, hash: u64) ?[]const u8 {
		for (self.method_names, self.hashes) |name, h| {
			if (h == hash) return name;
		}
		return null;
	}
};

pub const FatPtr = extern struct {
	/// Points at a C-ABI struct whose first field is an [`ObjectHeader`]. Usually
	/// a second field holds the captures of the object literal.
	///
	/// A primitive type such as Int, Nat, Float or Byte instead points at a
	/// singleton vtable, and this word holds the value itself.
	data: FearlessValue,
	vt: *const VTable,

	pub fn boxed_value(ptr: *const FatPtr) *ObjectHeader {
		if (std.debug.runtime_safety) {
			const mode = ptr.vt.storage_mode;
			assert(mode != .primitive and mode != .primitiveContainer);
		}
		return ptr.data.obj;
	}

	pub fn share(ptr: *const FatPtr) FatPtr {
		const copy: FatPtr = .{ .data = ptr.*.data, .vt = ptr.*.vt };
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return copy,
			.primitiveContainer => {
				containerRetain(@ptrCast(ptr.data.cell));
				return copy;
			},
			.heap => {
				op_counters.bump(.rc_increment);
				incRef(ptr.boxed_value());
				return copy;
			},
		}
	}

	/// Release one reference held by the fiber this runs on. Generated code
	/// calls this, so the identity comes off the stack pointer.
	///
	/// The storage mode decides first, so a value that holds no reference count
	/// returns without reading an identity it would not use.
	pub fn rc_decrement(ptr: *const FatPtr) void {
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return,
			.heap, .primitiveContainer =>
				ptr.rc_decrement_as(worker_mod.currentWorkerId()),
		}
	}

	/// Release one reference on behalf of `releasing_worker_id`, for a caller that may not be on a
	/// fiber stack. A drop chain carries the identity from its start, so an
	/// object released on the scheduler stack takes the same paths it would have
	/// taken on the worker's own fiber.
	pub fn rc_decrement_as(ptr: *const FatPtr, releasing_worker_id: u32) void {
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return,
			.primitiveContainer =>
				if (ptr.vt == &var_rt.VT_Var)
					var_rt.release(ptr.data.cell, releasing_worker_id)
				else if (ptr.vt == &isopod_rt.VT_IsoPod)
					isopod_rt.release(ptr.data.iso_cell, releasing_worker_id)
				else if (ptr.vt == &error_rt.VT_RuntimeError or ptr.vt == &error_rt.VT_RuntimeNdError)
					error_rt.release(ptr.data.err_cell, releasing_worker_id),
			.heap => rc_decrement_slow(ptr, releasing_worker_id),
		}
	}

	noinline fn rc_decrement_slow(ptr: *const FatPtr, releasing_worker_id: u32) void {
		op_counters.bump(.rc_decrement);
		const obj = ptr.boxed_value();
		if (releasing_worker_id != 0 and obj.biased_worker_id.load(.monotonic) == releasing_worker_id) {
			biasedDecRef(obj, releasing_worker_id);
			return;
		}
		sharedDecRef(obj, releasing_worker_id);
	}

	pub fn is_primitive(ptr: *const FatPtr) bool {
		return ptr.vt.storage_mode == .primitive;
	}

	pub fn is_transient(ptr: *const FatPtr) bool {
		return ptr.vt.storage_mode == .transient;
	}

	pub fn box_transient(ptr: *const FatPtr) FatPtr {
		if (!ptr.is_transient()) return ptr.*;
		op_counters.bump(.boxed_transient);
		const box = ptr.vt.box_fn orelse @panic("Transient object has no boxing hook");
		return box(ptr.*);
	}
};

/// The worker holding the biased half takes the plain path; anyone else pays for
/// an atomic.
inline fn incRef(obj: *ObjectHeader) void {
	const me = worker_mod.currentWorkerId();
	if (me != 0 and obj.biased_worker_id.load(.monotonic) == me) {
		const biased = obj.biased;
		if (biased >= BIASED_LIMIT) {
			@branchHint(.unlikely);
			@panic("Too many references");
		}
		obj.biased = biased + 1;
		return;
	}
	sharedIncRef(obj);
}

noinline fn sharedIncRef(obj: *ObjectHeader) void {
	op_counters.bump(.rc_shared_increment);
	const old = obj.shared.fetchAdd(UNIT, .monotonic);
	if (sharedCount(old) >= SHARED_LIMIT) {
		@branchHint(.unlikely);
		@panic("Too many references");
	}
}

/// The worker holding the biased half is its only writer, so this needs no atomic
/// until the last biased reference goes. The object then clears
/// `biased_worker_id` and merges the two halves, and that merge is what discovers
/// whether it is dead.
fn biasedDecRef(obj: *ObjectHeader, releasing_worker_id: u32) void {
	const biased = obj.biased;
	if (std.debug.runtime_safety) assert(biased != 0);
	obj.biased = biased - 1;
	if (biased != 1) return;

	obj.biased_worker_id.store(0, .monotonic);
	op_counters.bump(.rc_merge);
	const old = obj.shared.fetchOr(MERGED, .acq_rel);
	// With the biased half at zero the shared count is the whole count, so a
	// negative value means a thread released a reference it never held.
	if (std.debug.runtime_safety) assert(sharedCount(old) >= 0);
	if (sharedCount(old) == 0) freeHeader(obj, releasing_worker_id);
}

/// Release one reference from a thread that holds no biased half.
///
/// One compare-and-swap picks between three outcomes, so no other thread can see
/// the object look dead while this call still needs it:
///   * already merged: a plain decrement, and the object dies at zero;
///   * the shared count stays at or above zero: a plain decrement, and the
///     object lives on its remaining references;
///   * the shared count would go below zero, so the biased half still covers this
///     reference. Hand the object to the worker holding it. The queue entry
///     takes over the reference, which is why the count does not move.
fn sharedDecRef(obj: *ObjectHeader, releasing_worker_id: u32) void {
	op_counters.bump(.rc_shared_decrement);
	while (true) {
		const word = obj.shared.load(.monotonic);
		const biased_worker_id = obj.biased_worker_id.load(.monotonic);
		if (!isMerged(word) and !isQueued(word) and sharedCount(word) <= 0) {
			if (obj.shared.cmpxchgWeak(word, word | QUEUED, .acq_rel, .monotonic) != null) continue;
			queueForMerge(obj, biased_worker_id);
			return;
		}
		if (obj.shared.cmpxchgWeak(word, word -% UNIT, .release, .monotonic) != null) continue;
		const new_count = sharedCount(word) - 1;
		if (std.debug.runtime_safety) assert(!isMerged(word) or new_count >= 0);
		if (isMerged(word) and new_count == 0) {
			// Acquire the release sequence from prior decrements before the drop
			// hooks run.
			_ = obj.shared.load(.acquire);
			freeHeader(obj, releasing_worker_id);
		}
		return;
	}
}

/// An object whose biased half must fold into its shared half before it can
/// die. The entry owns a reference, so the object survives until that worker
/// drains the queue.
pub const MergeNode = extern struct {
	next: ?*MergeNode,
	obj: *ObjectHeader,
};

pub const MergeQueue = std.atomic.Value(?*MergeNode);

/// Treiber stack push, the same shape as the destroyer's backlog.
fn queueForMerge(obj: *ObjectHeader, biased_worker_id: u32) void {
	op_counters.bump(.rc_queue_push);
	// A negative shared count means a biased half covers a reference, so the object
	// names a worker and that worker has a queue.
	const head = worker_mod.mergeQueueFor(biased_worker_id) orelse @panic("Merge of an object with no owning worker");
	const node = gc.recycleAlloc(MergeNode);
	node.obj = obj;
	var old = head.load(.monotonic);
	while (true) {
		node.next = old;
		if (head.cmpxchgWeak(old, node, .release, .monotonic)) |observed| {
			old = observed;
		} else return;
	}
}

/// Runs on the owning worker, the only thread allowed to read `biased`.
///
/// The worker is passed in because the scheduler loop
/// drains off a fiber stack.
pub fn drainMergeQueue(head: *MergeQueue, releasing_worker_id: u32) void {
	var node = head.swap(null, .acquire) orelse return;
	while (true) {
		const next = node.next;
		mergeAndRelease(node.obj, releasing_worker_id);
		gc.recycleDestroy(MergeNode, node, .rc_merge_node);
		node = next orelse return;
	}
}

fn mergeAndRelease(obj: *ObjectHeader, releasing_worker_id: u32) void {
	if (obj.biased_worker_id.load(.monotonic) != 0) {
		op_counters.bump(.rc_merge);
		const biased = obj.biased;
		obj.biased = 0;
		obj.biased_worker_id.store(0, .monotonic);
		// The entry's own reference is one of the biased ones, so the shared
		// count stays above zero between these two updates and no other thread
		// can decide the object is dead inside the gap.
		if (biased != 0) _ = obj.shared.fetchAdd(biased *% UNIT, .monotonic);
		_ = obj.shared.fetchOr(MERGED, .acq_rel);
	}
	sharedDecRef(obj, releasing_worker_id);
}

/// The caller has already established that the last reference is gone.
fn freeHeader(obj: *ObjectHeader, releasing_worker_id: u32) void {
	if (obj.drop_fn) |drop| drop(@ptrCast(obj), releasing_worker_id);
	const bytes = @as([*]u8, @ptrCast(obj))[0..obj.alloc_size];
	const alloc_size = obj.alloc_size;
	const align_log2 = obj.alloc_align_log2;
	// Pool slots live on the GC heap, so leftover pointer-shaped bytes would
	// conservatively pin the children this call just RC-decremented.
	@memset(bytes[@sizeOf(ObjectHeader)..], 0);
	if (!alloc_recycler.push(bytes.ptr, alloc_size, align_log2)) {
		const log = @import("log.zig");
		log.trace_alloc_caching(.alloc_raw_free, alloc_size, @intFromEnum(log.RawFreeSource.rc_decrement), align_log2);
		@import("destroyer.zig").submit(@ptrCast(bytes.ptr));
	}
	gc.recordRcFree();
}

pub const FearlessValue = extern union {
	obj: *ObjectHeader,
	int: i64,
	nat: u64,
	float: f64,
	byte: u8,
	cell: *var_rt.VarCell,
	iso_cell: *isopod_rt.IsoCell,
	err_cell: *error_rt.ErrorCell,
};

/// Common prefix of `var_rt.VarCell` and `isopod_rt.IsoCell`. Both keep an
/// atomic `ref_count` at offset 0, so a `.primitiveContainer` FatPtr retains
/// uniformly through a `@ptrCast` to this header. The asserts below lock that.
const RcCellHeader = extern struct { ref_count: std.atomic.Value(u32) };

comptime {
	assert(@offsetOf(var_rt.VarCell, "ref_count") == 0);
	assert(@offsetOf(isopod_rt.IsoCell, "ref_count") == 0);
}

/// Uniform retain for `.primitiveContainer` cells.
inline fn containerRetain(cell: *RcCellHeader) void {
	const count = cell.ref_count.fetchAdd(1, .monotonic);
	const referencer_limit = std.math.maxInt(u32) - 4096;
	if (count >= referencer_limit) {
		@branchHint(.unlikely);
		@panic("Too many references");
	}
}

/// Methods cached per call site, to avoid a vtable lookup.
const POLYMORPHIC_INLINE_CACHE_SIZE = 4;

/// One cache entry. It holds the target, and the key mixed with the target rather than the key
/// as it stands.
///
/// A reader gets the key back with one exclusive-or. If a write of one thread ever interleaves
/// with a write of another, the key a reader computes belongs to neither write and does not
/// match its receiver, so the entry reads as a miss. This is what lets the two words stay
/// correct with the cache shared between threads: no reader can pair the key of one
/// target with a different target.
const MethodCacheEntry = struct {
	target: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),
	key_mix: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),

	/// A target address is never zero, so zero marks an entry no write has filled.
	inline fn isEmpty(self: *const MethodCacheEntry) bool {
		return self.target.load(.acquire) == 0;
	}

	/// Each word is read exactly once, so the compiler cannot re-load `target`
	/// between the check and the jump. The acquire pairs with `publish`'s
	/// release: a non-zero target implies its matching `key_mix` is visible.
	inline fn targetFor(self: *const MethodCacheEntry, vt: *const VTable) ?*const anyopaque {
		const t = self.target.load(.acquire);
		if (t == 0) { return null; }
		const k = self.key_mix.load(.monotonic);
		if ((k ^ t) != @intFromPtr(vt)) { return null; }
		return @ptrFromInt(t);
	}

	/// Invalidate, fill, then revalidate, so every state a concurrent reader can
	/// observe is either the old entry or a clean miss.
	inline fn publish(self: *MethodCacheEntry, vt: *const VTable, target: *const anyopaque) void {
		const t = @intFromPtr(target);
		self.target.store(0, .monotonic);
		self.key_mix.store(@intFromPtr(vt) ^ t, .monotonic);
		self.target.store(t, .release);
	}
};

const InlineCache = struct {
	entries: [POLYMORPHIC_INLINE_CACHE_SIZE]MethodCacheEntry = [_]MethodCacheEntry{.{}} ** POLYMORPHIC_INLINE_CACHE_SIZE,
	/// Round-robin replacement cursor, so a miss touches one entry rather than
	/// shifting all of them.
	next_index: std.atomic.Value(u8) = std.atomic.Value(u8).init(0),
};

/// Slow path: a linear scan of the VTable.
fn lookup_method(vt: *const VTable, target_hash: u64) ?*const anyopaque {
	// A linear scan is fastest for the small arrays a VTable holds.
	for (vt.hashes, 0..) |h, i| {
		if (h == target_hash) {
			return vt.methods[i];
		}
	}
	return null;
}

/// Fast path: the cache, then `resolve_method_slow`.
inline fn resolve_method(receiver: FatPtr, hash: u64, ic: *InlineCache) *const anyopaque {
	// Monomorphic entry, which takes most hot calls.
	if (ic.entries[0].targetFor(receiver.vt)) |target| {
		return target;
	}
	op_counters.bump(.ic_slow_probe);
	return resolve_method_slow(receiver, hash, ic);
}

fn resolve_method_slow(receiver: FatPtr, hash: u64, ic: *InlineCache) *const anyopaque {
	// Polymorphic entries, for a hot call on dynamic input.
	for (1..POLYMORPHIC_INLINE_CACHE_SIZE) |i| {
		// Entries fill from 0 up, so an empty one ends the search.
		if (ic.entries[i].isEmpty()) { break; }
		if (ic.entries[i].targetFor(receiver.vt)) |target| {
			return target;
		}
	}

	const target = lookup_method(receiver.vt, hash) orelse dispatch_failed(receiver, hash);

	// Fill the next slot in rotation. Entries fill 0 upwards, so the scan above
	// may still stop at the first empty one.
	const slot = ic.next_index.fetchAdd(1, .monotonic) % POLYMORPHIC_INLINE_CACHE_SIZE;
	ic.entries[slot].publish(receiver.vt, target);

	return target;
}

fn dispatch_failed(receiver: FatPtr, hash: u64) noreturn {
	const name = receiver.vt.method_name(hash) orelse {
		std.log.err("Failed to dispatch to {s} with method {d}", .{receiver.vt.type_name, hash});
		unreachable;
	};

	std.log.err("Failed to dispatch to {s} with method \'{s}\'", .{receiver.vt.type_name, name});
	unreachable;
}

pub fn primitive_dispatch_failed(
	comptime type_name: []const u8,
	comptime target_method: u64,
) noreturn {
	if (comptime builtin.mode == .Debug or builtin.mode == .ReleaseSafe) {
		@panic(std.fmt.comptimePrint(
			"Failed to dispatch to {s}: no intrinsic for method hash {d}",
			.{ type_name, target_method },
		));
	}
	unreachable;
}

pub fn GenMethodCallType(comptime arity: usize) type {
	// One slot for self, plus `arity` argument slots.
	const param_types: [arity + 1]type = @splat(FatPtr);
	return *const @Fn(
		&param_types,
		&@splat(.{}),
		FatPtr,
		.{ .@"callconv" = .c },
	);
}

const MethodDispatchUniquenessTag = struct {
	line: u32,
	col: u32,
	target_method_hash: u64,
	module_hash: u64,
};
pub fn GenDispatchCacheType(comptime tag: MethodDispatchUniquenessTag) type {
	return struct {
		/// Makes the cache type of each call site unique.
		const uniqueness_tag = tag;

		/// Shared across threads: the entry representation tolerates it (see
		/// `MethodCacheEntry`), and one warm cache beats one per thread.
		var ic: InlineCache = .{};
	};
}

/// The universal method call. `args` is a tuple, such as `.{ a, b }`.
pub fn call(receiver: FatPtr, comptime target_method: u64, args: anytype, comptime src: std.builtin.SourceLocation) FatPtr {
	switch (receiver.vt.storage_mode) {
		.primitive => {
			op_counters.bump(.virtual_call_primitive);
			if (receiver.vt == &nat_rt.VT_Nat) return nat_rt.dispatch(target_method, receiver, args);
			if (receiver.vt == &int_rt.VT_Int) return int_rt.dispatch(target_method, receiver, args);
			if (receiver.vt == &float_rt.VT_Float) return float_rt.dispatch(target_method, receiver, args);
			if (receiver.vt == &byte_rt.VT_Byte) return byte_rt.dispatch(target_method, receiver, args);
			unreachable;
		},
		.primitiveContainer => {
			op_counters.bump(.virtual_call_primitive);
			if (receiver.vt == &var_rt.VT_Var) return var_rt.dispatch(target_method, receiver, args);
			if (receiver.vt == &isopod_rt.VT_IsoPod) return isopod_rt.dispatch(target_method, receiver, args);
			unreachable;
		},
		.heap, .singleton, .transient => {},
		// TODO: can't do nat/int/var/iso as a `switch (receiver.vt)` because of
		// Zig bug: https://github.com/ziglang/zig/issues/22351
	}

	op_counters.bump(.virtual_call);

	// One cache per call site.
	const CacheType = GenDispatchCacheType(.{
		.line = src.line,
		.col = src.column,
		.target_method_hash = target_method,
		.module_hash = hash_signature(src.module),
	});

	const ArgsType = @TypeOf(args);
	const args_info = @typeInfo(ArgsType);
	if (args_info != .@"struct" or !args_info.@"struct".is_tuple) {
		@compileError("Args must be a tuple literal, e.g., .{ a, b }");
	}
	const arity = args.len;
	const FnPtr = GenMethodCallType(arity);

	const target_opaque = resolve_method(receiver, target_method, &CacheType.ic);
	const func: FnPtr = @ptrCast(@alignCast(target_opaque));

	const self_tuple = .{receiver};
	const full_args = self_tuple ++ args;

	return @call(.auto, func, full_args);
}

/// A call whose receiver's declared type makes every runtime value a
/// `.primitive`, so the storage-mode switch and the inline cache of `call` are
/// both dead weight. `module.dispatch` resolves `target_method` at comptime.
pub inline fn dispatch_primitive(
	comptime module: type,
	comptime target_method: u64,
	receiver: FatPtr,
	args: anytype,
) FatPtr {
	op_counters.bump(.direct_call_primitive);
	return module.dispatch(target_method, receiver, args);
}

pub fn GenObjectLayoutType(comptime Captures: type) type {
	return extern struct {
		header: ObjectHeader,
		captures: Captures,
	};
}

pub fn obj_k(
	comptime Captures: type,
	comptime vt: *const VTable,
	captures: Captures,
) FatPtr {
	op_counters.bump(.heap_obj);
	const Layout = GenObjectLayoutType(Captures);
	const align_log2: u8 = @intFromEnum(std.mem.Alignment.of(Layout));

	const MemorySlot = struct { ptr: *Layout, raw_size: usize };
	const memory_slot: MemorySlot = if (alloc_recycler.pop(@sizeOf(Layout), align_log2)) |recycled|
		.{ .ptr = @ptrCast(@alignCast(recycled.ptr)), .raw_size = recycled.real_size }
	else blk: {
		const fresh = allocator.create(Layout) catch @panic("OOM");
		gc.recordRcAlloc();
		break :blk .{ .ptr = fresh, .raw_size = @sizeOf(Layout) };
	};
	// The building worker owns the object and holds its first reference in the
	// biased half. An object built before the pool starts names no worker, so it is
	// born merged and every operation on it takes the shared path.
	const me = worker_mod.currentWorkerId();
	memory_slot.ptr.* = .{
			.header = .{
				.shared = std.atomic.Value(u32).init(if (me == 0) UNIT | MERGED else 0),
				.biased = if (me == 0) 0 else 1,
				.drop_fn = vt.drop_fn orelse captureDropFn(Captures),
				.alloc_size = memory_slot.raw_size,
				.biased_worker_id = std.atomic.Value(u32).init(me),
				.alloc_align_log2 = align_log2,
			},
		.captures = shareCaptureFields(Captures, captures),
	};
	return .{
		.data = FearlessValue{ .obj = &memory_slot.ptr.header },
		.vt = vt,
	};
}

pub fn obj_k_singleton(comptime vt: *const VTable) FatPtr {
	op_counters.bump(.singleton_obj);
	const Layout = GenObjectLayoutType(extern struct {});
	// The wrapper is what makes the instance statically allocated.
	const Wrapper = struct {
		var instance: Layout = .{
			.header = .{
				.shared = std.atomic.Value(u32).init(IMMORTAL_SHARED),
				.biased = 0,
				.drop_fn = null,
				.alloc_size = @sizeOf(Layout),
				.biased_worker_id = std.atomic.Value(u32).init(0),
				.alloc_align_log2 = @intFromEnum(std.mem.Alignment.of(Layout)),
			},
			.captures = .{},
		};
	};
	return .{
		.data = FearlessValue{ .obj = &Wrapper.instance.header },
		.vt = vt,
	};
}

pub fn init_transient_obj(
	comptime Captures: type,
	obj: *GenObjectLayoutType(Captures),
	comptime vt: *const VTable,
	captures: Captures,
) FatPtr {
	op_counters.bump(.transient_obj);
	obj.* = .{
		.header = .{
			.shared = std.atomic.Value(u32).init(IMMORTAL_SHARED),
			.biased = 0,
			.drop_fn = vt.drop_fn orelse captureDropFn(Captures),
			.alloc_size = @sizeOf(GenObjectLayoutType(Captures)),
			.biased_worker_id = std.atomic.Value(u32).init(0),
			.alloc_align_log2 = @intFromEnum(std.mem.Alignment.of(GenObjectLayoutType(Captures))),
		},
		.captures = shareCaptureFields(Captures, captures),
	};
	return .{
		.data = FearlessValue{ .obj = &obj.header },
		.vt = vt,
	};
}

pub fn drop_transient_obj(comptime Captures: type, obj: *GenObjectLayoutType(Captures)) void {
	dropCaptureFields(Captures, &obj.captures, worker_mod.currentWorkerId());
}

pub fn deref(comptime Captures: type, ptr: FatPtr) *const Captures {
	const SelfT = GenObjectLayoutType(Captures);
	const self: *const SelfT = @ptrCast(@alignCast(ptr.boxed_value()));
	return &self.captures;
}

fn captureDropFn(comptime Captures: type) ?DropFn {
	if (!capturesContainFatPtr(Captures)) return null;
	return struct {
		fn drop(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
			const Layout = GenObjectLayoutType(Captures);
			const self: *const Layout = @ptrCast(@alignCast(header));
			dropCaptureFields(Captures, &self.captures, releasing_worker_id);
		}
	}.drop;
}

/// True when reference counting `field` of `Captures` can change a count.
///
/// A capture struct may declare `rc_free_fields`, the names of the fields whose static type
/// gives them a storage mode with no reference count. Those fields are copied and discarded
/// as plain bytes, so retain and release skip them and the storage-mode test they carry.
/// A struct that declares nothing counts every `FatPtr` field, which is what the runtime's
/// own hand-written capture structs rely on.
fn isCountedField(comptime Captures: type, comptime field: std.builtin.Type.StructField) bool {
	if (field.type != FatPtr) return false;
	if (!@hasDecl(Captures, "rc_free_fields")) return true;
	for (Captures.rc_free_fields) |name| {
		if (std.mem.eql(u8, name, field.name)) return false;
	}
	return true;
}

fn capturesContainFatPtr(comptime Captures: type) bool {
	return switch (@typeInfo(Captures)) {
		.@"struct" => |info| inline for (info.fields) |field| {
			if (comptime isCountedField(Captures, field)) break true;
		} else false,
		else => false,
	};
}

fn shareCaptureFields(comptime Captures: type, captures: Captures) Captures {
	var copy = captures;
	switch (@typeInfo(Captures)) {
		.@"struct" => |info| inline for (info.fields) |field| {
			if (comptime isCountedField(Captures, field)) {
				@field(copy, field.name) = @field(captures, field.name).share();
			}
		},
		else => {},
	}
	return copy;
}

fn dropCaptureFields(comptime Captures: type, captures: *const Captures, releasing_worker_id: u32) void {
	switch (@typeInfo(Captures)) {
		.@"struct" => |info| inline for (info.fields) |field| {
			if (comptime isCountedField(Captures, field)) {
				@field(captures.*, field.name).rc_decrement_as(releasing_worker_id);
			}
		},
		else => {},
	}
}

test "heap object starts at one, share increments, decrement drops captures" {
	const testing = std.testing;
	gc.init_gc();

	const ChildCaps = extern struct {};
	const ParentCaps = extern struct { child: FatPtr };
	const child_vt: VTable = .{ .type_name = "test.Child", .hashes = &.{}, .methods = &.{}, .method_names = &.{} };
	const parent_vt: VTable = .{ .type_name = "test.Parent", .hashes = &.{}, .methods = &.{}, .method_names = &.{} };

	var child = obj_k(ChildCaps, &child_vt, .{});
	try testing.expectEqual(@as(u32, 1), child.boxed_value().refCountForTest());

	var child_shared = child.share();
	try testing.expectEqual(@as(u32, 2), child.boxed_value().refCountForTest());
	child_shared.rc_decrement();
	try testing.expectEqual(@as(u32, 1), child.boxed_value().refCountForTest());

	var parent = obj_k(ParentCaps, &parent_vt, .{ .child = child });
	try testing.expectEqual(@as(u32, 2), child.boxed_value().refCountForTest());
	parent.rc_decrement();
	try testing.expectEqual(@as(u32, 1), child.boxed_value().refCountForTest());
	child.rc_decrement();
}

test "primitives and immortal singletons ignore RC operations" {
	const testing = std.testing;
	const nat = nat_rt.make(10);
	_ = nat.share();
	nat.rc_decrement();

	const vt: VTable = .{ .type_name = "test.Singleton", .hashes = &.{}, .methods = &.{}, .method_names = &.{}, .storage_mode = .singleton };
	var singleton = obj_k_singleton(&vt);
	try testing.expectEqual(IMMORTAL_COUNT, singleton.boxed_value().refCountForTest());
	_ = singleton.share();
	singleton.rc_decrement();
	try testing.expectEqual(IMMORTAL_COUNT, singleton.boxed_value().refCountForTest());
}

test "transient object layout matches heap layout and ignores RC operations" {
	const testing = std.testing;
	gc.init_gc();

	const Caps = extern struct { child: FatPtr };
	const heap_vt: VTable = .{ .type_name = "test.Heap", .hashes = &.{}, .methods = &.{}, .method_names = &.{} };
	const transient_vt: VTable = .{ .type_name = "test.Transient", .hashes = &.{}, .methods = &.{}, .method_names = &.{}, .storage_mode = .transient };
	try testing.expectEqual(@sizeOf(GenObjectLayoutType(Caps)), @sizeOf(extern struct { header: ObjectHeader, captures: Caps }));

	var child = obj_k(extern struct {}, &heap_vt, .{});
	var stack_obj: GenObjectLayoutType(Caps) = undefined;
	var transient = init_transient_obj(Caps, &stack_obj, &transient_vt, .{ .child = child });
	try testing.expect(transient.is_transient());
	try testing.expectEqual(@as(u32, 2), child.boxed_value().refCountForTest());
	_ = transient.share();
	transient.rc_decrement();
	try testing.expectEqual(IMMORTAL_COUNT, transient.boxed_value().refCountForTest());
	drop_transient_obj(Caps, &stack_obj);
	try testing.expectEqual(@as(u32, 1), child.boxed_value().refCountForTest());
	child.rc_decrement();
}
