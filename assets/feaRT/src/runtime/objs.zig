const std = @import("std");
const builtin = @import("builtin");
const assert = std.debug.assert;

const gc = @import("gc.zig");
const allocator = gc.allocator;
const heap = @import("heap.zig");

const nat_rt = @import("intrinsics/nat.zig");
const int_rt = @import("intrinsics/int.zig");
const float_rt = @import("intrinsics/float.zig");
const byte_rt = @import("intrinsics/byte.zig");
const var_rt = @import("intrinsics/var.zig");
const list_rt = @import("intrinsics/list.zig");
const list_storage = @import("intrinsics/lists/storage.zig");
const flow_ops = @import("intrinsics/flows/ops_node.zig");
const isopod_rt = @import("intrinsics/isopod.zig");
const error_rt = @import("error.zig");
const op_counters = @import("op_counters.zig");
const worker_mod = @import("worker.zig");
const cycles = @import("cycles.zig");

// FNV-1a 64-bit, evaluated at comptime.
pub fn hash_signature(comptime str: []const u8) u64 {
	// The loop below costs one branch per character, and every call in one
	// comptime evaluation draws on the same quota. A call site can sit inside a
	// deep chain of inline expansions, each with its own signatures to hash, so
	// the total is a property of the generated program, not of any one signature.
	@setEvalBranchQuota(1_000_000);
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
	///
	/// A collection folds this half into `shared` for every node it reaches and
	/// then uses the word as that node's cyclic reference count. See [`nodeRef`]:
	/// there is no room for a field of its own inside a header this size, and a
	/// folded half is dead for the rest of the object's life.
	biased: u32,
	drop_fn: ?DropFn,
	/// The worker whose `biased` half covers references, as its id plus one, or 0
	/// when no worker holds a biased reference. Atomic because other workers read
	/// it while this one clears it, but `.monotonic` is enough: only a thread that
	/// reads its own id can take a biased path, and every other reader sees a
	/// foreign id or 0 and takes the shared path.
	biased_worker_id: std.atomic.Value(u32),
	/// A [`Colour`], as its integer value. It lives in the tail padding the
	/// fields above leave, so it costs nothing.
	colour: u8 = 0,
	/// True while the candidate-root buffer of the cycle collector holds this
	/// object. It keeps one node out of the buffer twice, and it holds `Release`
	/// back from freeing a node the buffer still names.
	buffered: bool = false,
	/// A [`Green`], as its integer value. Written once by the thread that builds
	/// the object, before any other thread can name it, and never written again:
	/// a reader reached this object through a publication that already ordered
	/// the write, so it needs no atomic.
	green: u8 = 0,

	/// The reference count as seen by a thread with no biased half. A unit test
	/// binds no worker, so every operation takes the shared path and this is the
	/// whole count.
	pub fn refCountForTest(self: *const ObjectHeader) u32 {
		return @intCast(sharedCount(self.shared.load(.monotonic)));
	}
};

comptime {
	// Size classes step by 8 bytes over the range object bodies fall in, so the
	// header decides which class an object lands in. A body is the header plus
	// one word per capture, and this size keeps that sum on a class boundary.
	assert(@sizeOf(ObjectHeader) == 24);
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

/// The colour lattice of Bacon and Rajan. The collector runs the algorithm; the
/// value lives in a node header, which is why the enum is declared here.
///
/// `black` is zero, so a fresh header is born black with no initialiser.
pub const Colour = enum(u8) {
	/// In use, or already reclaimed.
	black = 0,
	/// Under trial deletion: a possible member of a cycle.
	grey = 1,
	/// A proposed member of a garbage cycle.
	white = 2,
	/// A possible root of a cycle, held by the Roots buffer.
	purple = 3,
	/// A member of a garbage cycle that a collection is taking apart.
	red = 5,
};

/// Whether an object can take part in a reference cycle.
///
/// A cycle needs a mutable container, and an object that cannot reach one cannot
/// be in one, whatever its reference capability. Green is therefore per
/// *instance* and not per type: with generics erased, one vtable serves
/// `Opt[Nat]` and `Opt[mut Var]` alike, and only the captures an instance was
/// actually built with answer the question.
///
/// `unset` is zero, so a header path that does not answer reads as `not_green`,
/// which costs a collection pass over an object that never needed one and never
/// loses a cycle.
pub const Green = enum(u8) { unset = 0, green = 1, not_green = 2 };

/// Called once for every reference a node holds.
pub const VisitFn = *const fn (ctx: *anyopaque, edge: FatPtr) callconv(.c) void;

/// Enumerates the references a node holds. `node` is the node header: an
/// [`ObjectHeader`] for a `.heap` object, the cell itself for a
/// `.primitiveContainer`.
///
/// A vtable with no `trace_fn` reads as a leaf. That loses collection through
/// such a node and never costs correctness: fewer edges means fewer trial
/// decrements, so a node can only look more live than it is.
pub const TraceFn = *const fn (node: *anyopaque, visit: VisitFn, ctx: *anyopaque) callconv(.c) void;

/// Gives back what a node holds that is not reference counted: a boxed value, a
/// list buffer, a hash table.
///
/// This is the paper's `Free(S)`, and it is separate from the drop hook because
/// `Release(S)` decrements a node's children as soon as its count reaches zero
/// but must leave the node itself standing while the candidate-root buffer holds
/// it. `MarkRoots` frees it later.
pub const FreeFn = *const fn (node: *anyopaque, releasing_worker_id: u32) callconv(.c) void;

pub const StorageMode = enum(u8) {
	/// No ObjectHeader: `data` is the value.
	primitive,
	/// Heap header, refcounted, freed back to its size class.
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
	trace_fn: ?TraceFn = null,
	free_fn: ?FreeFn = null,
	/// True when the compiler proved that every instance of this object literal
	/// is green, whatever it was built with. It is the fast path over
	/// [`Green`]: an instance whose vtable answers here needs no per-capture
	/// read at construction.
	green: bool = false,

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
				containerRetain(cellHeader(copy));
				return copy;
			},
			.heap => {
				op_counters.bump(.rc_increment);
				incRef(ptr.boxed_value());
				return copy;
			},
		}
	}

	/// [`share`] for a caller that carries the worker identity rather than
	/// reading it off the stack pointer.
	///
	/// Biased reference counting reads the identity itself, off the stack
	/// pointer, so the two differ only in what they cost a caller that already
	/// holds one.
	pub fn share_as(ptr: *const FatPtr, acquiring_worker_id: u32) FatPtr {
		_ = acquiring_worker_id;
		return ptr.share();
	}

	/// Take one reference where the static type admits `.heap` alone, so the count goes up with
	/// no storage-mode test.
	pub inline fn share_heap(ptr: *const FatPtr) FatPtr {
		if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .heap);
		const copy = ptr.*;
		op_counters.bump(.rc_increment);
		incRef(ptr.boxed_value());
		return copy;
	}

	/// Take one reference where the static type admits `.heap` and `.transient` only. A transient
	/// value holds no count, so one test replaces the general dispatch.
	pub inline fn share_heap_or_transient(ptr: *const FatPtr) FatPtr {
		const copy = ptr.*;
		if (ptr.vt.storage_mode == .heap) {
			op_counters.bump(.rc_increment);
			incRef(ptr.boxed_value());
		} else if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .transient);
		return copy;
	}

	/// Release one reference held by the fiber this runs on. Generated code
	/// calls this, so the identity comes off the stack pointer.
	///
	/// The storage mode decides first, so a value that holds no reference count
	/// returns without reading an identity it would not use.
	pub fn rc_decrement(ptr: *const FatPtr) void {
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return,
			.primitiveContainer =>
				containerRelease(ptr.*, worker_mod.currentWorkerId()),
			.heap => rc_decrement_slow(ptr.*, worker_mod.currentWorkerId()),
		}
	}

	/// Release one reference on behalf of `releasing_worker_id`, for a caller that may not be on a
	/// fiber stack. A drop chain carries the identity from its start, so an
	/// object released on the scheduler stack takes the same paths it would have
	/// taken on the worker's own fiber.
	pub fn rc_decrement_as(ptr: *const FatPtr, releasing_worker_id: u32) void {
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return,
			.primitiveContainer => containerRelease(ptr.*, releasing_worker_id),
			.heap => rc_decrement_slow(ptr.*, releasing_worker_id),
		}
	}

	/// Release one reference where the static type admits `.heap` alone. The counterpart of
	/// [`share_heap`].
	pub inline fn rc_decrement_heap(ptr: *const FatPtr) void {
		if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .heap);
		rc_decrement_slow(ptr.*, worker_mod.currentWorkerId());
	}

	/// [`rc_decrement_heap`] for a caller that carries the identity rather than reading it off the
	/// stack pointer.
	pub inline fn rc_decrement_heap_as(ptr: *const FatPtr, releasing_worker_id: u32) void {
		if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .heap);
		rc_decrement_slow(ptr.*, releasing_worker_id);
	}

	/// Release one reference where the static type admits `.heap` and `.transient` only. The
	/// counterpart of [`share_heap_or_transient`]. The identity comes off the stack pointer, and
	/// only the heap arm reads it.
	pub inline fn rc_decrement_heap_or_transient(ptr: *const FatPtr) void {
		if (ptr.vt.storage_mode == .heap) {
			rc_decrement_slow(ptr.*, worker_mod.currentWorkerId());
		} else if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .transient);
	}

	/// [`rc_decrement_heap_or_transient`] for a caller that carries the identity rather than
	/// reading it off the stack pointer.
	pub inline fn rc_decrement_heap_or_transient_as(ptr: *const FatPtr, releasing_worker_id: u32) void {
		if (ptr.vt.storage_mode == .heap) {
			rc_decrement_slow(ptr.*, releasing_worker_id);
		} else if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .transient);
	}

	noinline fn rc_decrement_slow(node: FatPtr, releasing_worker_id: u32) void {
		op_counters.bump(.rc_decrement);
		const obj = node.boxed_value();
		if (releasing_worker_id != 0 and obj.biased_worker_id.load(.monotonic) == releasing_worker_id) {
			biasedDecRef(node, obj, releasing_worker_id);
			return;
		}
		sharedDecRef(node, obj, releasing_worker_id);
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

/// Fast direct teardown for a heap object reaching count zero.
noinline fn freeHeapObject(obj: *ObjectHeader, vt: *const VTable, releasing_worker_id: u32) void {
	if (std.debug.runtime_safety) assert(!obj.buffered);
	if (obj.drop_fn) |drop| drop(@ptrCast(obj), releasing_worker_id);
	if (vt.free_fn) |f| f(@ptrCast(obj), releasing_worker_id);
	heap.free(releasing_worker_id, @ptrCast(obj));
}

/// The worker holding the biased half is its only writer, so this needs no atomic
/// until the last biased reference goes. The object then clears
/// `biased_worker_id` and merges the two halves, and that merge is what discovers
/// whether it is dead.
fn biasedDecRef(node: FatPtr, obj: *ObjectHeader, releasing_worker_id: u32) void {
	const biased = obj.biased;
	if (std.debug.runtime_safety) assert(biased != 0);
	obj.biased = biased - 1;
	if (biased != 1) {
		return;
	}

	obj.biased_worker_id.store(0, .monotonic);
	op_counters.bump(.rc_merge);
	const old = obj.shared.fetchOr(MERGED, .acq_rel);
	// With the biased half at zero the shared count is the whole count, so a
	// negative value means a thread released a reference it never held.
	if (std.debug.runtime_safety) assert(sharedCount(old) >= 0);
	if (sharedCount(old) == 0) {
		freeHeapObject(obj, node.vt, releasing_worker_id);
		return;
	}
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
fn sharedDecRef(node: FatPtr, obj: *ObjectHeader, releasing_worker_id: u32) void {
	op_counters.bump(.rc_shared_decrement);
	while (true) {
		const word = obj.shared.load(.monotonic);
		const biased_worker_id = obj.biased_worker_id.load(.monotonic);
		if (!isMerged(word) and !isQueued(word) and sharedCount(word) <= 0) {
			if (obj.shared.cmpxchgWeak(word, word | QUEUED, .acq_rel, .monotonic) != null) continue;
			queueForMerge(node, biased_worker_id);
			return;
		}
		if (obj.shared.cmpxchgWeak(word, word -% UNIT, .release, .monotonic) != null) continue;
		const new_count = sharedCount(word) - 1;
		if (std.debug.runtime_safety) assert(!isMerged(word) or new_count >= 0);
		if (isMerged(word) and new_count == 0) {
			// Acquire the release sequence from prior decrements before the drop
			// hooks run.
			_ = obj.shared.load(.acquire);
			freeHeapObject(obj, node.vt, releasing_worker_id);
			return;
		}
		return;
	}
}

/// An object whose biased half must fold into its shared half before it can
/// die. The entry owns a reference, so the object survives until that worker
/// drains the queue.
pub const MergeNode = extern struct {
	next: ?*MergeNode,
	node: FatPtr,
};

pub const MergeQueue = std.atomic.Value(?*MergeNode);

/// Treiber stack push.
fn queueForMerge(node: FatPtr, biased_worker_id: u32) void {
	op_counters.bump(.rc_queue_push);
	// A negative shared count means a biased half covers a reference, so the object
	// names a worker and that worker has a queue.
	const head = worker_mod.mergeQueueFor(biased_worker_id) orelse @panic("Merge of an object with no owning worker");
	const entry = gc.recycleAlloc(MergeNode);
	entry.node = node;
	var old = head.load(.monotonic);
	while (true) {
		entry.next = old;
		if (head.cmpxchgWeak(old, entry, .release, .monotonic)) |observed| {
			old = observed;
		} else return;
	}
}

/// Runs on the owning worker, the only thread allowed to read `biased`.
///
/// The worker is passed in because the scheduler loop drains off a fiber stack.
pub fn drainMergeQueue(head: *MergeQueue, releasing_worker_id: u32) void {
	var entry = head.swap(null, .acquire) orelse return;
	while (true) {
		const next = entry.next;
		const node = entry.node;
		gc.recycleDestroy(MergeNode, entry, .rc_merge_node);
		mergeAndRelease(node, releasing_worker_id);
		entry = next orelse return;
	}
}

fn mergeAndRelease(node: FatPtr, releasing_worker_id: u32) void {
	const obj = node.data.obj;
	// The entry's own reference is one of the biased ones, so the shared count
	// stays above zero across the fold and no other thread can decide the object
	// is dead inside it.
	foldBiased(obj);
	sharedDecRef(node, obj, releasing_worker_id);
}

/// Folds the biased half of a heap object into its shared half, and leaves the
/// object shared-only for the rest of its life.
fn foldBiased(obj: *ObjectHeader) void {
	if (obj.biased_worker_id.load(.monotonic) == 0) return;
	op_counters.bump(.rc_merge);
	const biased = obj.biased;
	obj.biased = 0;
	obj.biased_worker_id.store(0, .monotonic);
	if (biased != 0) _ = obj.shared.fetchAdd(biased *% UNIT, .monotonic);
	_ = obj.shared.fetchOr(MERGED, .acq_rel);
}

/// `Release(S)`: the last reference is gone, so every reference the node holds
/// goes with it, and the node's own storage follows.
///
/// A candidate cycle root holds a reference of its own while the candidate set
/// names it (see `cycles.noteStore`), so a node that reaches zero here is never
/// one the collector still has to look at.
noinline fn releaseNode(node: FatPtr, releasing_worker_id: u32) void {
	releaseChildren(node, releasing_worker_id);
	freeNode(node, releasing_worker_id);
}

/// The `for T in children(S) Decrement(T)` of `Release`. It enumerates exactly
/// the references a node owns, which is the same set `children` traces.
pub fn releaseChildren(node: FatPtr, id: u32) void {
	switch (node.vt.storage_mode) {
		.heap => {
			const obj = node.data.obj;
			if (obj.drop_fn) |drop| drop(@ptrCast(obj), id);
		},
		.primitiveContainer => {
			if (node.vt == &var_rt.VT_Var) return var_rt.drop_children(node.data.cell, id);
			if (node.vt == &isopod_rt.VT_IsoPod) return isopod_rt.drop_children(node.data.iso_cell, id);
			if (node.vt == &error_rt.VT_RuntimeError or node.vt == &error_rt.VT_RuntimeNdError)
				return error_rt.drop_children(node.data.err_cell, id);
			if (node.vt == &list_storage.VT_ListStorage)
				return list_storage.drop_children(list_storage.storageOfEdge(node), id);
			if (node.vt == &flow_ops.VT_FlowOps)
				return flow_ops.drop_children(flow_ops.opsOfEdge(node), id);
			unreachable;
		},
		.primitive, .singleton, .transient => unreachable,
	}
}

/// `Free(S)`: the node's own storage goes back, and nothing else. Its children
/// were released when its count reached zero.
pub fn freeNode(node: FatPtr, id: u32) void {
	// A buffered node is named by the candidate-root buffer, and freeing it
	// there would leave that buffer holding dead memory. Whoever frees it must
	// take it out of the buffer first.
	if (std.debug.runtime_safety) assert(!nodeMark(node).buffered.*);
	switch (node.vt.storage_mode) {
		.heap => {
			const obj = node.data.obj;
			if (node.vt.free_fn) |f| f(@ptrCast(obj), id);
			heap.free(id, @ptrCast(obj));
		},
		.primitiveContainer => {
			if (node.vt == &var_rt.VT_Var) return var_rt.free_cell(node.data.cell, id);
			if (node.vt == &isopod_rt.VT_IsoPod) return isopod_rt.free_cell(node.data.iso_cell, id);
			if (node.vt == &error_rt.VT_RuntimeError or node.vt == &error_rt.VT_RuntimeNdError)
				return error_rt.free_cell(node.data.err_cell, id);
			if (node.vt == &list_storage.VT_ListStorage)
				return list_storage.free_storage(list_storage.storageOfEdge(node), id);
			if (node.vt == &flow_ops.VT_FlowOps)
				return flow_ops.free_ops(flow_ops.opsOfEdge(node), id);
			unreachable;
		},
		.primitive, .singleton, .transient => unreachable,
	}
}

/// The [`Green`] an object header holds.
pub inline fn greenOf(obj: *const ObjectHeader) Green {
	return @enumFromInt(obj.green);
}

/// Whether `v` can be part of a cycle. See [`Green`].
pub inline fn valueIsGreen(v: FatPtr) bool {
	if (v.vt.green) return true;
	return switch (v.vt.storage_mode) {
		// A primitive is no object at all, and a singleton captures nothing,
		// which is what makes it one.
		.primitive, .singleton => true,
		.heap, .transient => greenOf(v.data.obj) == .green,
		// Every one of these is a cell the program can store into.
		.primitiveContainer => false,
	};
}

/// Whether the collector should never treat this node as a candidate cycle root,
/// and whether a store of it can close a cycle.
///
/// A node with no `trace_fn` holds no edge a traversal can follow, so it can
/// reach nothing and close nothing. Any runtime type that holds an edge MUST
/// declare a trace hook, or it will read as a leaf here.
pub inline fn isAcyclic(node: FatPtr) bool {
	return valueIsGreen(node) or node.vt.trace_fn == null;
}

/// `children(S)`. A node with no hook holds no edge the collector can follow.
pub inline fn children(node: FatPtr, visit: VisitFn, ctx: *anyopaque) void {
	const trace = node.vt.trace_fn orelse return;
	switch (node.vt.storage_mode) {
		.heap => trace(@ptrCast(node.data.obj), visit, ctx),
		.primitiveContainer => trace(@ptrCast(node.data.raw_cell), visit, ctx),
		.primitive, .singleton, .transient => {},
	}
}

/// The colour and buffer state of a node, whichever header shape it has. The
/// two shapes agree on what they hold and not on where, so a caller reaches
/// them through this rather than through a cast.
///
/// This is what the mutator paths use. Neither field needs the counts, so
/// nothing here touches the biased half.
pub const NodeMark = struct {
	colour: *u8,
	buffered: *bool,
};

pub inline fn nodeMark(node: FatPtr) NodeMark {
	switch (node.vt.storage_mode) {
		.heap => {
			const obj = node.data.obj;
			return .{ .colour = &obj.colour, .buffered = &obj.buffered };
		},
		.primitiveContainer => {
			const cell = cellHeader(node);
			return .{ .colour = &cell.colour, .buffered = &cell.buffered };
		},
		.primitive, .singleton, .transient => unreachable,
	}
}

/// [`NodeMark`] with the two counts a cycle pass reads.
///
/// `rc` is the whole count and `crc` the cyclic one. A `.heap` node keeps no
/// field for the cyclic count: its header has no room for a fifth word, so this
/// folds the biased half into the shared one and uses the word the fold leaves
/// dead. That fold is only sound with the world stopped, which is where every
/// caller of this runs.
pub const NodeRef = struct {
	rc: i32,
	crc: *i32,
	colour: *u8,
	buffered: *bool,
};

pub fn nodeRef(node: FatPtr) NodeRef {
	switch (node.vt.storage_mode) {
		.heap => {
			const obj = node.data.obj;
			foldBiased(obj);
			return .{
				.rc = sharedCount(obj.shared.load(.monotonic)),
				.crc = @ptrCast(&obj.biased),
				.colour = &obj.colour,
				.buffered = &obj.buffered,
			};
		},
		.primitiveContainer => {
			const cell = cellHeader(node);
			return .{
				.rc = @intCast(cell.ref_count.load(.monotonic)),
				.crc = &cell.crc,
				.colour = &cell.colour,
				.buffered = &cell.buffered,
			};
		},
		.primitive, .singleton, .transient => unreachable,
	}
}

/// True for a value the collector counts. Everything else is a primitive held
/// inside the `FatPtr`, or immortal.
pub inline fn isNode(node: FatPtr) bool {
	const mode = node.vt.storage_mode;
	return mode == .heap or mode == .primitiveContainer;
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
	/// A node that no program value names: a `ListStorage`, or the op chain of
	/// a flow. Only the cycle collector builds a `FatPtr` over this member, and
	/// it pairs it with a vtable that exists for that purpose alone.
	raw_cell: *anyopaque,
};

/// Common prefix of every reference-counted cell: `var_rt.VarCell`,
/// `isopod_rt.IsoCell`, `error_rt.ErrorCell`, `storage.ListStorage` and
/// `ops_node.FlowOps`. Each begins with one of these, so a
/// `.primitiveContainer` FatPtr retains uniformly through a `@ptrCast` to it,
/// and the cycle collector reads a colour through the same cast. The asserts
/// below lock that.
///
/// A cell is shared between wrappers rather than owned by one worker, so the
/// count is a plain atomic with no biased half.
pub const RcCellHeader = extern struct {
	ref_count: std.atomic.Value(u32),
	/// The cyclic reference count. See [`NodeRef`].
	crc: i32 = 0,
	/// A [`Colour`], as its integer value.
	colour: u8 = 0,
	/// True while the candidate-root buffer of the cycle collector holds this
	/// cell.
	buffered: bool = false,

	/// A cell its maker has just built, holding the one reference the maker took.
	pub const born: RcCellHeader = .{ .ref_count = std.atomic.Value(u32).init(1) };
};

comptime {
	// One count, one cyclic count and two bytes of state. A cell is small, so
	// this is what decides its size class.
	assert(@sizeOf(RcCellHeader) == 12);
	assert(@offsetOf(var_rt.VarCell, "header") == 0);
	assert(@offsetOf(isopod_rt.IsoCell, "header") == 0);
	assert(@offsetOf(error_rt.ErrorCell, "header") == 0);
}

/// The common header of the cell a `.primitiveContainer` FatPtr names.
pub inline fn cellHeader(ptr: FatPtr) *RcCellHeader {
	if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .primitiveContainer);
	return @ptrCast(@alignCast(ptr.data.raw_cell));
}

/// Uniform retain for `.primitiveContainer` cells.
///
/// `noinline` because this is cold and holds an atomic. Inlined, the atomic fetch-add
/// and register spills land in every caller of share() where the storage mode is not statically known.
noinline fn containerRetain(cell: *RcCellHeader) void {
	const count = cell.ref_count.fetchAdd(1, .monotonic);
	if (count >= BIASED_LIMIT) {
		@branchHint(.unlikely);
		@panic("Too many references");
	}
}

/// Uniform release for `.primitiveContainer` cells.
///
/// `noinline` because this is cold and holds an atomic. Inlined, the read-modify-write
/// and the spill of `node` around it land in every loop that drops a reference, and a
/// loop that drops one per iteration pays for both on the path where the count does not
/// reach zero.
noinline fn containerRelease(node: FatPtr, releasing_worker_id: u32) void {
	op_counters.bump(.rc_decrement);
	const cell = cellHeader(node);
	const old_count = cell.ref_count.fetchSub(1, .release);
	if (std.debug.runtime_safety) assert(old_count != 0);
	if (old_count != 1) return;
	_ = cell.ref_count.load(.acquire);
	releaseNode(node, releasing_worker_id);
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
	const green = resolveGreen(Captures, vt, captures);
	if (green == .green) op_counters.bump(.green_obj);

	// The building worker owns the object and holds its first reference in the
	// biased half. Generated code always runs on a fiber, so it always names a
	// worker. `me == 0` only happens under `builtin.is_test`; such an object is
	// born merged and takes the shared path for every operation.
	const me = worker_mod.currentWorkerId();

	const raw = heap.allocComptime(me, @sizeOf(Layout), @alignOf(Layout)) orelse @panic("OOM");
	const slot: *Layout = @ptrCast(@alignCast(raw));
	slot.* = .{
		.header = .{
			.shared = std.atomic.Value(u32).init(if (me == 0) UNIT | MERGED else 0),
			.biased = if (me == 0) 0 else 1,
			.drop_fn = vt.drop_fn orelse captureDropFn(Captures),
			.biased_worker_id = std.atomic.Value(u32).init(me),
			.green = @intFromEnum(green),
		},
		.captures = shareCaptureFields(Captures, captures),
	};
	return .{
		.data = FearlessValue{ .obj = &slot.header },
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
				.biased_worker_id = std.atomic.Value(u32).init(0),
				// A singleton captures nothing, which is what makes it one.
				.green = @intFromEnum(Green.green),
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
			.biased_worker_id = std.atomic.Value(u32).init(0),
			.green = @intFromEnum(resolveGreen(Captures, vt, captures)),
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

/// The [`Green`] an object built from `captures` is born with.
///
/// A vtable whose trace hook is not the generic one reaches edges this cannot
/// see -- every mutable container of the standard library is such a type -- so
/// only the generic shape may be answered from the captures. `captureTraceFn`
/// gives back null when no field is counted, so a literal that captures only
/// primitives compares null against null and is answered from its empty capture
/// set: green, with no work at run time.
inline fn resolveGreen(
	comptime Captures: type,
	comptime vt: *const VTable,
	captures: Captures,
) Green {
	if (comptime vt.green) return .green;
	if (comptime vt.trace_fn != captureTraceFn(Captures)) return .not_green;
	return if (capturesAreGreen(Captures, captures)) .green else .not_green;
}

/// Whether every counted capture is itself green. The values are the same before
/// and after [`shareCaptureFields`], so either copy answers.
inline fn capturesAreGreen(comptime Captures: type, captures: Captures) bool {
	switch (@typeInfo(Captures)) {
		.@"struct" => |info| inline for (info.fields) |field| {
			if (comptime isCountedField(Captures, field)) {
				if (!valueIsGreen(@field(captures, field.name))) return false;
			}
		},
		else => {},
	}
	return true;
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

/// Mirror of [`captureDropFn`]: it enumerates the same fields that one
/// releases, so an object literal traces exactly the references it owns.
pub fn captureTraceFn(comptime Captures: type) ?TraceFn {
	if (!capturesContainFatPtr(Captures)) return null;
	return struct {
		fn trace(header: *anyopaque, visit: VisitFn, ctx: *anyopaque) callconv(.c) void {
			const Layout = GenObjectLayoutType(Captures);
			const self: *const Layout = @ptrCast(@alignCast(header));
			inline for (@typeInfo(Captures).@"struct".fields) |field| {
				if (comptime isCountedField(Captures, field)) {
					visit(ctx, @field(self.captures, field.name));
				}
			}
		}
	}.trace;
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
