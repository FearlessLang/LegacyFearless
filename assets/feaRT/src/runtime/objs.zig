const std = @import("std");
const builtin = @import("builtin");
const assert = std.debug.assert;

const gc = @import("gc.zig");
const allocator = gc.allocator;
const alloc_recycler = @import("alloc_recycler.zig");

// Intrinsic modules for compile-time dispatch
const nat_rt = @import("intrinsics/nat.zig");
const int_rt = @import("intrinsics/int.zig");
const float_rt = @import("intrinsics/float.zig");
const byte_rt = @import("intrinsics/byte.zig");
const var_rt = @import("intrinsics/var.zig");
const list_rt = @import("intrinsics/list.zig");
const isopod_rt = @import("intrinsics/isopod.zig");
const error_rt = @import("error.zig");
const op_counters = @import("op_counters.zig");

// ==========================================
// Comptime Hashing (FNV-1a 64-bit)
// ==========================================
// This runs inside the compiler! No runtime cost.
pub fn hash_signature(comptime str: []const u8) u64 {
	var hash: u64 = 14695981039346656037;
	const prime: u64 = 1099511628211;

	for (str) |c| {
		hash ^= @as(u64, c);
		hash *%= prime; // Wrapping multiplication
	}
	return hash;
}

// ==========================================
// Runtime Core Structures
// ==========================================

// 'extern' guarantees C-compatible layout (Header is always first bytes)
pub const ObjectHeader = extern struct {
	ref_count: std.atomic.Value(u32),
	drop_fn: ?DropFn,
	alloc_size: usize,
	alloc_align_log2: u8,
};

pub const DropFn = *const fn (*anyopaque) callconv(.c) void;
pub const BoxFn = *const fn (FatPtr) callconv(.c) FatPtr;
const IMMORTAL_REFCOUNT = std.math.maxInt(u32);

pub const StorageMode = enum(u8) {
	/// No ObjectHeader; the FatPtr's `data` field IS the value.
	primitive,
	/// Normal heap-allocated header, refcounted, freed via recycler/GC_free.
	heap,
	/// Statically-allocated header. Skipped on share/rc_decrement.
	/// Today these objects are also marked with IMMORTAL_REFCOUNT for safety,
	/// but the fast path checks the vtable's storage_mode instead.
	singleton,
	/// Stack allocated, no-escape object. RC operations are no-ops locally;
	/// crossing-task paths must call the vtable boxing hook first.
	transient,
	/// No ObjectHeader; the FatPtr's `data` field IS a pointer to the value.
  /// Unlike `.primitive` these have custom clean-up logic on `release`.
	primitiveContainer,
};

pub const VTable = struct {
	type_name: []const u8,
	// We store separate slices for keys/values to be SIMD-friendly
	hashes: []const u64,
	// Untyped function pointers. Call sites cast them to specific signatures.
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
	/// This points to a C-ABI struct where the first item is an [`ObjectHeader`].
	/// It's important to note that in most cases there will be a second field which
	/// is a struct containing all the captures of the object literal.
	///
	/// As an optimisation, certain types like integers, natural numbers, floating point numbers, bytes, etc.
	/// will point to a singleton vtable and the object header pointer will actually just be the value of the type.
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
				const obj = ptr.boxed_value();
				const count = obj.ref_count.fetchAdd(1, .monotonic);
				// 4096 feels right here as that's the most cores Linux can currently support, but this is likely never going
				// to actually be relevant for safety at all here.
				const referencer_limit = std.math.maxInt(u32) - 4096;
				if (count >= referencer_limit) {
					@branchHint(.unlikely);
					@panic("Too many references");
				}
				return copy;
			},
		}
	}

	pub fn rc_decrement(ptr: *const FatPtr) void {
		switch (ptr.vt.storage_mode) {
			.primitive, .singleton, .transient => return,
			.primitiveContainer =>
				if (ptr.vt == &var_rt.VT_Var)
					var_rt.release(ptr.data.cell)
				else if (ptr.vt == &isopod_rt.VT_IsoPod)
					isopod_rt.release(ptr.data.iso_cell)
				else if (ptr.vt == &error_rt.VT_RuntimeError or ptr.vt == &error_rt.VT_RuntimeNdError)
					error_rt.release(ptr.data.err_cell),
			.heap => rc_decrement_slow(ptr),
		}
	}

	noinline fn rc_decrement_slow(ptr: *const FatPtr) void {
		op_counters.bump(.rc_decrement);
		const obj = ptr.boxed_value();
		const old_count = obj.ref_count.fetchSub(1, .release);
		if (std.debug.runtime_safety) assert(old_count != 0);
		if (old_count != 1) return;

		// Acquire the release sequence from prior decrements before running drop hooks.
		_ = obj.ref_count.load(.acquire);

		if (obj.drop_fn) |drop| drop(@ptrCast(obj));
		const bytes = @as([*]u8, @ptrCast(obj))[0..obj.alloc_size];
		const alloc_size = obj.alloc_size;
		const align_log2 = obj.alloc_align_log2;
		// Zero the captures region before handing the body to the recycler:
		// pool slots live on the GC heap, so any leftover pointer-shaped
		// bytes would conservatively pin children we just RC-decremented.
		@memset(bytes[@sizeOf(ObjectHeader)..], 0);
		if (!alloc_recycler.push(bytes.ptr, alloc_size, align_log2)) {
			const log = @import("log.zig");
			log.trace_alloc_caching(.alloc_raw_free, alloc_size, @intFromEnum(log.RawFreeSource.rc_decrement), align_log2);
			@import("destroyer.zig").submit(@ptrCast(bytes.ptr));
		}
		gc.recordRcFree();
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

/// Common prefix of `var_rt.VarCell` and `isopod_rt.IsoCell`. Both keep their
/// atomic `ref_count` at offset 0, so a `.primitiveContainer` FatPtr can be
/// retained uniformly by `@ptrCast`-ing its cell pointer to this header. The
/// comptime asserts below lock that invariant.
const RcCellHeader = extern struct { ref_count: std.atomic.Value(u32) };

comptime {
	assert(@offsetOf(var_rt.VarCell, "ref_count") == 0);
	assert(@offsetOf(isopod_rt.IsoCell, "ref_count") == 0);
}

/// Uniform retain for `.primitiveContainer` cells (var/iso)
inline fn containerRetain(cell: *RcCellHeader) void {
	const count = cell.ref_count.fetchAdd(1, .monotonic);
	const referencer_limit = std.math.maxInt(u32) - 4096;
	if (count >= referencer_limit) {
		@branchHint(.unlikely);
		@panic("Too many references");
	}
}

/// How many methods to cache at call-sites to avoid vtable lookups
const POLYMORPHIC_INLINE_CACHE_SIZE = 4;

const MethodCacheEntry = struct {
	key: ?*const VTable = null,
	target: ?*const anyopaque = null,
};

const InlineCache = struct {
	entries: [POLYMORPHIC_INLINE_CACHE_SIZE]MethodCacheEntry = [_]MethodCacheEntry{.{}} ** POLYMORPHIC_INLINE_CACHE_SIZE,
	next_index: u8 = 0,
};

// ==========================================
// Dispatch Logic
// ==========================================

// This is the "Slow Path" - Linear scan over the VTable
fn lookup_method(vt: *const VTable, target_hash: u64) ?*const anyopaque {
	// In assembly this optimizes very well for small arrays
	for (vt.hashes, 0..) |h, i| {
		if (h == target_hash) {
			return vt.methods[i];
		}
	}
	return null;
}

// The "Fast Path" - Check cache, then fallback
inline fn resolve_method(receiver: FatPtr, hash: u64, ic: *InlineCache) *const anyopaque {
	// Monomorphic cache (most hot calls should be handled here)
	if (ic.entries[0].target) |target| {
		if (ic.entries[0].key == receiver.vt) {
			return target;
		}
	}
	op_counters.bump(.ic_slow_probe);
	return resolve_method_slow(receiver, hash, ic);
}

fn resolve_method_slow(receiver: FatPtr, hash: u64, ic: *InlineCache) *const anyopaque {
	// Polymorphic cache (hot calls on dynamic input)
	for (1..POLYMORPHIC_INLINE_CACHE_SIZE) |i| {
		if (ic.entries[i].target) |target| {
			if (ic.entries[i].key == receiver.vt) {
				return target;
			}
		} else {
			// Early-exit if the cache has no value at this index so we can get to the slow path faster
			break;
		}
	}

	// Cache miss, time for a v-table lookup and cache update
	const target = lookup_method(receiver.vt, hash) orelse dispatch_failed(receiver, hash);

	// Move everything in the cache down and set the top of it to what we resolved.
	var i: u8 = POLYMORPHIC_INLINE_CACHE_SIZE - 1;
	while (i > 0) : (i -= 1) {
		ic.entries[i] = ic.entries[i - 1];
	}
	ic.entries[0] = .{ .key = receiver.vt, .target = target };

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
	// 1 slot for 'self', plus `arity` slots for arguments
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
		/// Ensures different call-sites have unique cache types
		const uniqueness_tag = tag;

		/// The actual cache for optimising dynamic dispatch.
		threadlocal var ic: InlineCache = .{};
	};
}

/// The universal method call function
/// args: A tuple of arguments, e.g., .{ arg1, arg2 }
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

	// Static Cache (One per call-site, specialized by args type)
	const CacheType = GenDispatchCacheType(.{
		.line = src.line,
		.col = src.column,
		.target_method_hash = target_method,
		.module_hash = hash_signature(src.module),
	});

	// 2. Comptime: Determine Arity & Function Type
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

/// A call whose receiver's declared type makes every runtime value a `.primitive`, so the
/// storage-mode switch and the inline cache of `call` are both dead weight. `module` is the
/// intrinsic module of the receiver type, and its `dispatch` resolves `target_method` at comptime.
pub inline fn dispatch_primitive(
	comptime module: type,
	comptime target_method: u64,
	receiver: FatPtr,
	args: anytype,
) FatPtr {
	op_counters.bump(.direct_call_primitive);
	return module.dispatch(target_method, receiver, args);
}

// ==========================================
// Object Allocation
// ==========================================

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
	memory_slot.ptr.* = .{
			.header = .{
				.ref_count = std.atomic.Value(u32).init(1),
				.drop_fn = vt.drop_fn orelse captureDropFn(Captures),
				.alloc_size = memory_slot.raw_size,
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
	// Wrap to ensure this instance is statically allocated
	const Wrapper = struct {
		var instance: Layout = .{
			.header = .{
				.ref_count = std.atomic.Value(u32).init(IMMORTAL_REFCOUNT),
				.drop_fn = null,
				.alloc_size = @sizeOf(Layout),
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
			.ref_count = std.atomic.Value(u32).init(IMMORTAL_REFCOUNT),
			.drop_fn = vt.drop_fn orelse captureDropFn(Captures),
			.alloc_size = @sizeOf(GenObjectLayoutType(Captures)),
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
	dropCaptureFields(Captures, &obj.captures);
}

pub fn deref(comptime Captures: type, ptr: FatPtr) *const Captures {
	const SelfT = GenObjectLayoutType(Captures);
	const self: *const SelfT = @ptrCast(@alignCast(ptr.boxed_value()));
	return &self.captures;
}

fn captureDropFn(comptime Captures: type) ?DropFn {
	if (!capturesContainFatPtr(Captures)) return null;
	return struct {
		fn drop(header: *anyopaque) callconv(.c) void {
			const Layout = GenObjectLayoutType(Captures);
			const self: *const Layout = @ptrCast(@alignCast(header));
			dropCaptureFields(Captures, &self.captures);
		}
	}.drop;
}

fn capturesContainFatPtr(comptime Captures: type) bool {
	return switch (@typeInfo(Captures)) {
		.@"struct" => |info| inline for (info.fields) |field| {
			if (field.type == FatPtr) break true;
		} else false,
		else => false,
	};
}

fn shareCaptureFields(comptime Captures: type, captures: Captures) Captures {
	var copy = captures;
	switch (@typeInfo(Captures)) {
		.@"struct" => |info| inline for (info.fields) |field| {
			if (field.type == FatPtr) {
				@field(copy, field.name) = @field(captures, field.name).share();
			}
		},
		else => {},
	}
	return copy;
}

fn dropCaptureFields(comptime Captures: type, captures: *const Captures) void {
	switch (@typeInfo(Captures)) {
		.@"struct" => |info| inline for (info.fields) |field| {
			if (field.type == FatPtr) {
				@field(captures.*, field.name).rc_decrement();
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
	try testing.expectEqual(@as(u32, 1), child.boxed_value().ref_count.load(.monotonic));

	var child_shared = child.share();
	try testing.expectEqual(@as(u32, 2), child.boxed_value().ref_count.load(.monotonic));
	child_shared.rc_decrement();
	try testing.expectEqual(@as(u32, 1), child.boxed_value().ref_count.load(.monotonic));

	var parent = obj_k(ParentCaps, &parent_vt, .{ .child = child });
	try testing.expectEqual(@as(u32, 2), child.boxed_value().ref_count.load(.monotonic));
	parent.rc_decrement();
	try testing.expectEqual(@as(u32, 1), child.boxed_value().ref_count.load(.monotonic));
	child.rc_decrement();
}

test "primitives and immortal singletons ignore RC operations" {
	const testing = std.testing;
	const nat = nat_rt.make(10);
	_ = nat.share();
	nat.rc_decrement();

	const vt: VTable = .{ .type_name = "test.Singleton", .hashes = &.{}, .methods = &.{}, .method_names = &.{}, .storage_mode = .singleton };
	var singleton = obj_k_singleton(&vt);
	try testing.expectEqual(IMMORTAL_REFCOUNT, singleton.boxed_value().ref_count.load(.monotonic));
	_ = singleton.share();
	singleton.rc_decrement();
	try testing.expectEqual(IMMORTAL_REFCOUNT, singleton.boxed_value().ref_count.load(.monotonic));
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
	try testing.expectEqual(@as(u32, 2), child.boxed_value().ref_count.load(.monotonic));
	_ = transient.share();
	transient.rc_decrement();
	try testing.expectEqual(IMMORTAL_REFCOUNT, transient.boxed_value().ref_count.load(.monotonic));
	drop_transient_obj(Caps, &stack_obj);
	try testing.expectEqual(@as(u32, 1), child.boxed_value().ref_count.load(.monotonic));
	child.rc_decrement();
}
