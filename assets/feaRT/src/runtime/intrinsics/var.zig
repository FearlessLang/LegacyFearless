const std = @import("std");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;
const root = @import("root");
const log = @import("../log.zig");

pub const VarCell = extern struct {
    ref_count: std.atomic.Value(u32),
    /// The language guarantees data race freedom, so acquire/release is enough
    /// for cross-thread visibility.
    value: std.atomic.Value(*FatPtr),
};

pub const VT_Var: objs.VTable = .{
    .type_name = "<rt impl for base.Var/1>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
};

pub fn make(value: FatPtr) FatPtr {
    const val_ptr = gc.allocator.create(FatPtr) catch @panic("OOM");
    val_ptr.* = value;
    const cell = gc.allocator.create(VarCell) catch @panic("OOM");
    cell.* = .{
        .ref_count = std.atomic.Value(u32).init(1),
        .value = std.atomic.Value(*FatPtr).init(val_ptr),
    };
    return .{
        .data = .{ .cell = cell },
        .vt = &VT_Var,
    };
}

pub fn retain(cell: *VarCell) void {
    const count = cell.ref_count.fetchAdd(1, .monotonic);
    const referencer_limit = std.math.maxInt(u32) - 4096;
    if (count >= referencer_limit) {
        @branchHint(.unlikely);
        @panic("Too many references");
    }
}

/// `releasing_worker_id` is the worker on whose behalf this release runs. It
/// travels down from the start of the drop chain.
pub noinline fn release(cell: *VarCell, releasing_worker_id: u32) void {
    const old_count = cell.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;

    _ = cell.ref_count.load(.acquire);
    const old_ptr = cell.value.load(.monotonic);
    old_ptr.*.rc_decrement_as(releasing_worker_id);
    gc.recycleDestroy(FatPtr, old_ptr, .var_release);
    gc.recycleDestroy(VarCell, cell, .var_release);
}

fn get(self: FatPtr) FatPtr {
    return self.data.cell.value.load(.monotonic).*.share();
}

fn swap(self: FatPtr, new_value: FatPtr) FatPtr {
    var cell = self.data.cell;
    const new_ptr = gc.allocator.create(FatPtr) catch @panic("OOM");
    new_ptr.* = new_value;
    const old_ptr = cell.value.swap(new_ptr, .monotonic);
    const old_value = old_ptr.*;
    gc.recycleDestroy(FatPtr, old_ptr, .var_release);
    return old_value;
}

fn set(self: FatPtr, new_value: FatPtr) FatPtr {
    var cell = self.data.cell;
    const new_ptr = gc.allocator.create(FatPtr) catch @panic("OOM");
    new_ptr.* = new_value;
    const old_ptr = cell.value.swap(new_ptr, .monotonic);
    old_ptr.*.rc_decrement();
    gc.recycleDestroy(FatPtr, old_ptr, .var_release);
    return make_void();
}

fn make_void() FatPtr {
    if (comptime @hasDecl(root, "pkg_base")) {
        return objs.obj_k_singleton(&root.pkg_base.VT_Void_0);
    }
    return objs.obj_k_singleton(&VT_TestVoid);
}

const VT_TestVoid: objs.VTable = .{
    .type_name = "<test Void>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .singleton,
};

fn vars_create(self: FatPtr, value: FatPtr) callconv(.c) FatPtr {
    _ = self;
    return make(value);
}

const h = objs.hash_signature;

pub const VT_Vars: objs.VTable = .{
    .type_name = "base.Vars/0",
    .hashes = &.{h("imm #/1")},
    .methods = &.{@ptrCast(&vars_create)},
    .method_names = &.{"imm #/1"},
    .storage_mode = .singleton,
};

pub fn dispatch(comptime target_method: u64, self: FatPtr, args: anytype) FatPtr {
    return switch (target_method) {
        h("mut .get/0"), h("read .get/0"), h("mut */0"), h("read */0") => get(self),
        h("mut .swap/1") => swap(self, args[0]),
        h("mut :=/1"), h("mut .set/1") => set(self, args[0]),
        h("mut <-/1"), h("mut .update/1") => {
            // self is not decremented: it moves into the swap call.
            const current = self.data.cell.value.load(.monotonic).*.share();
            const new_val = objs.call(args[0], h("mut #/1"), .{current}, @src());
            args[0].rc_decrement();
            return swap(self, new_val);
        },
        else => objs.primitive_dispatch_failed(VT_Var.type_name, target_method),
    };
}

test "Var get set and swap retain returned values and release overwritten storage" {
    const testing = std.testing;
    gc.init_gc();

    const Captures = extern struct {};
    const vt_a: objs.VTable = .{ .type_name = "test.VarA", .hashes = &.{}, .methods = &.{}, .method_names = &.{} };
    const vt_b: objs.VTable = .{ .type_name = "test.VarB", .hashes = &.{}, .methods = &.{}, .method_names = &.{} };
    const vt_c: objs.VTable = .{ .type_name = "test.VarC", .hashes = &.{}, .methods = &.{}, .method_names = &.{} };

    var a = objs.obj_k(Captures, &vt_a, .{});
    var b = objs.obj_k(Captures, &vt_b, .{});
    var c = objs.obj_k(Captures, &vt_c, .{});
    var cell_fp = make(a.share());
    try testing.expectEqual(@as(u32, 2), a.boxed_value().refCountForTest());

    // `get`, `set` and `swap` borrow their receiver: none of them releases it.
    // Sharing it here would leave the cell with a reference no one gives back,
    // so the release below would never reach zero and never free the value.
    var got = get(cell_fp);
    try testing.expectEqual(@as(u32, 3), a.boxed_value().refCountForTest());
    got.rc_decrement();
    try testing.expectEqual(@as(u32, 2), a.boxed_value().refCountForTest());

    var old = swap(cell_fp, b.share());
    try testing.expectEqual(@as(u32, 2), a.boxed_value().refCountForTest());
    try testing.expectEqual(@as(u32, 2), b.boxed_value().refCountForTest());
    old.rc_decrement();
    try testing.expectEqual(@as(u32, 1), a.boxed_value().refCountForTest());

    _ = set(cell_fp, c.share());
    try testing.expectEqual(@as(u32, 1), b.boxed_value().refCountForTest());
    try testing.expectEqual(@as(u32, 2), c.boxed_value().refCountForTest());

    cell_fp.rc_decrement();
    try testing.expectEqual(@as(u32, 1), c.boxed_value().refCountForTest());

    a.rc_decrement();
    b.rc_decrement();
    c.rc_decrement();
}
