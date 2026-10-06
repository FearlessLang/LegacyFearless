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

/// FNV-1a 64-bit
pub fn hash_signature(comptime str: []const u8) u64 {
	@setEvalBranchQuota(1_000_000);
	var hash: u64 = 14695981039346656037;
	const prime: u64 = 1099511628211;

	for (str) |c| {
		hash ^= @as(u64, c);
		hash *%= prime;
	}
	return hash;
}

pub const ObjectHeader = extern struct {
	shared: std.atomic.Value(u32),
	biased: u32,
	drop_fn: ?DropFn,
	biased_worker_id: std.atomic.Value(u32),
	colour: u8 = 0,
	buffered: bool = false,
	green: u8 = 0,

	pub fn refCountForTest(self: *const ObjectHeader) u32 {
		return @intCast(sharedCount(self.shared.load(.monotonic)));
	}
};

comptime {
	assert(@sizeOf(ObjectHeader) == 24);
}

pub const DropFn = *const fn (*anyopaque, releasing_worker_id: u32) callconv(.c) void;
pub const BoxFn = *const fn (FatPtr) callconv(.c) FatPtr;

const MERGED: u32 = 1;
const QUEUED: u32 = 2;
const UNIT: u32 = 4;

const IMMORTAL_COUNT: u32 = @as(u32, std.math.maxInt(i32)) >> 2;
const IMMORTAL_SHARED: u32 = IMMORTAL_COUNT << 2;

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

pub const Colour = enum(u8) {
	black = 0,
	grey = 1,
	white = 2,
	purple = 3,
	red = 5,
};

pub const Green = enum(u8) { unset = 0, green = 1, not_green = 2 };

pub const VisitFn = *const fn (ctx: *anyopaque, edge: FatPtr) callconv(.c) void;

pub const TraceFn = *const fn (node: *anyopaque, visit: VisitFn, ctx: *anyopaque) callconv(.c) void;

pub const FreeFn = *const fn (node: *anyopaque, releasing_worker_id: u32) callconv(.c) void;

pub const StorageMode = enum(u8) {
	primitive,
	heap,
	singleton,
	transient,
	primitiveContainer,
};

pub const VTable = struct {
	type_name: []const u8,
	hashes: []const u64,
	methods: []const *const anyopaque,
	method_names: []const []const u8,
	storage_mode: StorageMode = .heap,
	drop_fn: ?DropFn = null,
	box_fn: ?BoxFn = null,
	trace_fn: ?TraceFn = null,
	free_fn: ?FreeFn = null,
	green: bool = false,

	pub fn method_name(self: *const VTable, hash: u64) ?[]const u8 {
		for (self.method_names, self.hashes) |name, h| {
			if (h == hash) return name;
		}
		return null;
	}
};

pub const FatPtr = extern struct {
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

	pub fn share_as(ptr: *const FatPtr, acquiring_worker_id: u32) FatPtr {
		_ = acquiring_worker_id;
		return ptr.share();
	}

	pub inline fn share_heap(ptr: *const FatPtr) FatPtr {
		if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .heap);
		const copy = ptr.*;
		op_counters.bump(.rc_increment);
		incRef(ptr.boxed_value());
		return copy;
	}

	pub inline fn share_heap_or_transient(ptr: *const FatPtr) FatPtr {
		const copy = ptr.*;
		if (ptr.vt.storage_mode == .heap) {
			op_counters.bump(.rc_increment);
			incRef(ptr.boxed_value());
		} else if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .transient);
		return copy;
	}

	pub fn rc_decrement(ptr: *const FatPtr) void {
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return,
			.primitiveContainer =>
				containerRelease(ptr.*, worker_mod.currentWorkerId()),
			.heap => rc_decrement_slow(ptr.*, worker_mod.currentWorkerId()),
		}
	}

	pub fn rc_decrement_as(ptr: *const FatPtr, releasing_worker_id: u32) void {
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return,
			.primitiveContainer => containerRelease(ptr.*, releasing_worker_id),
			.heap => rc_decrement_slow(ptr.*, releasing_worker_id),
		}
	}

	pub inline fn rc_decrement_heap(ptr: *const FatPtr) void {
		if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .heap);
		rc_decrement_slow(ptr.*, worker_mod.currentWorkerId());
	}

	pub inline fn rc_decrement_heap_as(ptr: *const FatPtr, releasing_worker_id: u32) void {
		if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .heap);
		rc_decrement_slow(ptr.*, releasing_worker_id);
	}

	pub inline fn rc_decrement_heap_or_transient(ptr: *const FatPtr) void {
		if (ptr.vt.storage_mode == .heap) {
			rc_decrement_slow(ptr.*, worker_mod.currentWorkerId());
		} else if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .transient);
	}

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

noinline fn freeHeapObject(obj: *ObjectHeader, vt: *const VTable, releasing_worker_id: u32) void {
	if (std.debug.runtime_safety) assert(!obj.buffered);
	if (obj.drop_fn) |drop| drop(@ptrCast(obj), releasing_worker_id);
	if (vt.free_fn) |f| f(@ptrCast(obj), releasing_worker_id);
	op_counters.bump(.heap_obj_freed);
	heap.free(releasing_worker_id, @ptrCast(obj));
}

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
	if (std.debug.runtime_safety) assert(sharedCount(old) >= 0);
	if (sharedCount(old) == 0) {
		freeHeapObject(obj, node.vt, releasing_worker_id);
		return;
	}
}

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
			_ = obj.shared.load(.acquire);
			freeHeapObject(obj, node.vt, releasing_worker_id);
			return;
		}
		return;
	}
}

pub const MergeNode = extern struct {
	next: ?*MergeNode,
	node: FatPtr,
};

pub const MergeQueue = std.atomic.Value(?*MergeNode);

fn queueForMerge(node: FatPtr, biased_worker_id: u32) void {
	op_counters.bump(.rc_queue_push);
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
	foldBiased(obj);
	sharedDecRef(node, obj, releasing_worker_id);
}

fn foldBiased(obj: *ObjectHeader) void {
	if (obj.biased_worker_id.load(.monotonic) == 0) return;
	op_counters.bump(.rc_merge);
	const biased = obj.biased;
	obj.biased = 0;
	obj.biased_worker_id.store(0, .monotonic);
	if (biased != 0) _ = obj.shared.fetchAdd(biased *% UNIT, .monotonic);
	_ = obj.shared.fetchOr(MERGED, .acq_rel);
}

noinline fn releaseNode(node: FatPtr, releasing_worker_id: u32) void {
	releaseChildren(node, releasing_worker_id);
	freeNode(node, releasing_worker_id);
}

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

pub fn freeNode(node: FatPtr, id: u32) void {
	if (std.debug.runtime_safety) assert(!nodeMark(node).buffered.*);
	switch (node.vt.storage_mode) {
		.heap => {
			const obj = node.data.obj;
			if (node.vt.free_fn) |f| f(@ptrCast(obj), id);
			op_counters.bump(.heap_obj_freed);
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

pub inline fn greenOf(obj: *const ObjectHeader) Green {
	return @enumFromInt(obj.green);
}

pub inline fn valueIsGreen(v: FatPtr) bool {
	if (v.vt.green) return true;
	return switch (v.vt.storage_mode) {
		.primitive, .singleton => true,
		.heap, .transient => greenOf(v.data.obj) == .green,
		.primitiveContainer => false,
	};
}

pub inline fn isAcyclic(node: FatPtr) bool {
	return valueIsGreen(node) or node.vt.trace_fn == null;
}

pub inline fn children(node: FatPtr, visit: VisitFn, ctx: *anyopaque) void {
	const trace = node.vt.trace_fn orelse return;
	switch (node.vt.storage_mode) {
		.heap => trace(@ptrCast(node.data.obj), visit, ctx),
		.primitiveContainer => trace(@ptrCast(node.data.raw_cell), visit, ctx),
		.primitive, .singleton, .transient => {},
	}
}

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
	raw_cell: *anyopaque,
};

pub const RcCellHeader = extern struct {
	ref_count: std.atomic.Value(u32),
	crc: i32 = 0,
	colour: u8 = 0,
	buffered: bool = false,

	pub const born: RcCellHeader = .{ .ref_count = std.atomic.Value(u32).init(1) };
};

comptime {
	assert(@sizeOf(RcCellHeader) == 12);
	assert(@offsetOf(var_rt.VarCell, "header") == 0);
	assert(@offsetOf(isopod_rt.IsoCell, "header") == 0);
	assert(@offsetOf(error_rt.ErrorCell, "header") == 0);
}

pub inline fn cellHeader(ptr: FatPtr) *RcCellHeader {
	if (std.debug.runtime_safety) assert(ptr.vt.storage_mode == .primitiveContainer);
	return @ptrCast(@alignCast(ptr.data.raw_cell));
}

noinline fn containerRetain(cell: *RcCellHeader) void {
	const count = cell.ref_count.fetchAdd(1, .monotonic);
	if (count >= BIASED_LIMIT) {
		@branchHint(.unlikely);
		@panic("Too many references");
	}
}

noinline fn containerRelease(node: FatPtr, releasing_worker_id: u32) void {
	op_counters.bump(.rc_decrement);
	const cell = cellHeader(node);
	const old_count = cell.ref_count.fetchSub(1, .release);
	if (std.debug.runtime_safety) assert(old_count != 0);
	if (old_count != 1) return;
	_ = cell.ref_count.load(.acquire);
	releaseNode(node, releasing_worker_id);
}

const POLYMORPHIC_INLINE_CACHE_SIZE = 4;

const MethodCacheEntry = struct {
	target: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),
	key_mix: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),

	inline fn isEmpty(self: *const MethodCacheEntry) bool {
		return self.target.load(.acquire) == 0;
	}

	inline fn targetFor(self: *const MethodCacheEntry, vt: *const VTable) ?*const anyopaque {
		const t = self.target.load(.acquire);
		if (t == 0) { return null; }
		const k = self.key_mix.load(.monotonic);
		if ((k ^ t) != @intFromPtr(vt)) { return null; }
		return @ptrFromInt(t);
	}

	inline fn publish(self: *MethodCacheEntry, vt: *const VTable, target: *const anyopaque) void {
		const t = @intFromPtr(target);
		self.target.store(0, .monotonic);
		self.key_mix.store(@intFromPtr(vt) ^ t, .monotonic);
		self.target.store(t, .release);
	}
};

const InlineCache = struct {
	entries: [POLYMORPHIC_INLINE_CACHE_SIZE]MethodCacheEntry = [_]MethodCacheEntry{.{}} ** POLYMORPHIC_INLINE_CACHE_SIZE,
	next_index: std.atomic.Value(u8) = std.atomic.Value(u8).init(0),
};

fn lookup_method(vt: *const VTable, target_hash: u64) ?*const anyopaque {
	for (vt.hashes, 0..) |h, i| {
		if (h == target_hash) {
			return vt.methods[i];
		}
	}
	return null;
}

inline fn resolve_method(receiver: FatPtr, hash: u64, ic: *InlineCache) *const anyopaque {
	if (ic.entries[0].targetFor(receiver.vt)) |target| {
		return target;
	}
	op_counters.bump(.ic_slow_probe);
	return resolve_method_slow(receiver, hash, ic);
}

fn resolve_method_slow(receiver: FatPtr, hash: u64, ic: *InlineCache) *const anyopaque {
	for (1..POLYMORPHIC_INLINE_CACHE_SIZE) |i| {
		if (ic.entries[i].isEmpty()) { break; }
		if (ic.entries[i].targetFor(receiver.vt)) |target| {
			return target;
		}
	}

	const target = lookup_method(receiver.vt, hash) orelse dispatch_failed(receiver, hash);

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
		const uniqueness_tag = tag;

		var ic: InlineCache = .{};
	};
}

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
	op_counters.bumpType(.heap_obj, vt.type_name);
	const Layout = GenObjectLayoutType(Captures);
	const green = resolveGreen(Captures, vt, captures);
	if (green == .green) op_counters.bump(.green_obj);

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

pub const VT_sum_0captures: VTable = .{
	.type_name = "<runtime sum 0 captures>",
	.hashes = &.{},
	.methods = &.{},
	.method_names = &.{},
	.storage_mode = .singleton,
};

pub inline fn sum_0captures() FatPtr {
	return .{ .data = FearlessValue{ .nat = 0 }, .vt = &VT_sum_0captures };
}

pub inline fn is_sum_0captures(p: FatPtr) bool {
	return p.vt == &VT_sum_0captures;
}

pub fn obj_k_singleton(comptime vt: *const VTable) FatPtr {
	op_counters.bump(.singleton_obj);
	const Layout = GenObjectLayoutType(extern struct {});
	const Wrapper = struct {
		var instance: Layout = .{
			.header = .{
				.shared = std.atomic.Value(u32).init(IMMORTAL_SHARED),
				.biased = 0,
				.drop_fn = null,
				.biased_worker_id = std.atomic.Value(u32).init(0),
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
	op_counters.bumpType(.transient_obj, vt.type_name);
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

inline fn resolveGreen(
	comptime Captures: type,
	comptime vt: *const VTable,
	captures: Captures,
) Green {
	if (comptime vt.green) return .green;
	if (comptime vt.trace_fn != captureTraceFn(Captures)) return .not_green;
	return if (capturesAreGreen(Captures, captures)) .green else .not_green;
}

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
