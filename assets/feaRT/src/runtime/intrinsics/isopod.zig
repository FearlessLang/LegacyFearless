const std = @import("std");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");
const str_rt = @import("strings/index.zig");
const bool_intrinsics = @import("bool.zig");
const root = @import("root");
const log = @import("../log.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

pub const IsoCell = extern struct {
    ref_count: std.atomic.Value(u32),
    value: std.atomic.Value(?*FatPtr),
};

pub const VT_IsoPod: objs.VTable = .{
    .type_name = "<rt impl for base.IsoPod/1>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
};

pub fn make(value: FatPtr) FatPtr {
    const val_ptr = gc.allocator.create(FatPtr) catch @panic("OOM");
    val_ptr.* = value;
    const cell = gc.allocator.create(IsoCell) catch @panic("OOM");
    cell.* = .{
        .ref_count = std.atomic.Value(u32).init(1),
        .value = std.atomic.Value(?*FatPtr).init(val_ptr),
    };
    gc.recordRcAlloc();
    return .{
        .data = .{ .iso_cell = cell },
        .vt = &VT_IsoPod,
    };
}

pub fn retain(cell: *IsoCell) void {
    const count = cell.ref_count.fetchAdd(1, .monotonic);
    const referencer_limit = std.math.maxInt(u32) - 4096;
    if (count >= referencer_limit) {
        @branchHint(.unlikely);
        @panic("Too many references");
    }
}

pub noinline fn release(cell: *IsoCell) void {
    const old_count = cell.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;

    _ = cell.ref_count.load(.acquire);
    if (cell.value.swap(null, .monotonic)) |old_ptr| {
        old_ptr.*.rc_decrement();
        gc.recycleDestroy(FatPtr, old_ptr, .isopod_release);
    }
    gc.recycleDestroy(IsoCell, cell, .isopod_release);
    gc.recordRcFree();
}

fn is_alive(self: FatPtr) FatPtr {
    defer self.rc_decrement();
    const val = self.data.iso_cell.value.load(.monotonic);
    return bool_intrinsics.to_bool(val != null);
}

fn peek(self: FatPtr, viewer: FatPtr) FatPtr {
    defer self.rc_decrement();
    const val = self.data.iso_cell.value.load(.monotonic);
    if (val) |ptr| {
        return objs.call(viewer, h("mut .some/1"), .{ptr.*.share()}, @src());
    }
    return objs.call(viewer, h("mut .empty/0"), .{}, @src());
}

fn consume(self: FatPtr) FatPtr {
    defer self.rc_decrement();
    const cell = self.data.iso_cell;
    const old_ptr = cell.value.swap(null, .monotonic);
    if (old_ptr) |ptr| {
        const value = ptr.*;
        gc.recycleDestroy(FatPtr, ptr, .isopod_release);
        return value;
    }
    if (comptime @hasDecl(root, "errors")) {
        _ = objs.call(
            objs.obj_k_singleton(&root.errors.VT_ErrorK),
            h("imm .msg/1"),
            .{str_rt.make_str_from_literal("Cannot consume an empty IsoPod.")},
            @src(),
        );
    } else {
        @panic("Cannot consume an empty IsoPod.");
    }
    unreachable;
}

fn next(self: FatPtr, new_value: FatPtr) FatPtr {
    defer self.rc_decrement();
    const cell = self.data.iso_cell;
    const new_ptr = gc.allocator.create(FatPtr) catch @panic("OOM");
    new_ptr.* = new_value;
    const old_ptr = cell.value.swap(new_ptr, .monotonic);
    if (old_ptr) |ptr| {
        ptr.*.rc_decrement();
        gc.recycleDestroy(FatPtr, ptr, .isopod_release);
    }
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

/// Invoke a code-generated method for one of the Mearless functions implementing
/// one of the IsoPod methods.
fn fearlessBody(comptime name: []const u8, args: anytype) FatPtr {
    if (comptime @hasDecl(root, "pkg_base")) {
        if (comptime @hasDecl(root.pkg_base, name)) {
            return @call(.auto, @field(root.pkg_base, name), args);
        }
    }
    @panic("This base has no Fearless body for the requested IsoPod method");
}

pub fn dispatch(comptime target_method: u64, self: FatPtr, args: anytype) FatPtr {
    return switch (target_method) {
        h("read .isAlive/0") => is_alive(self),
        h("read .peek/1") => peek(self, args[0]),
        h("mut !/0") => consume(self),
        h("mut .next/1") => next(self, args[0]),
        h("read .look/1") => fearlessBody("IsoPod_1__Zdotlook_1_read_Zfun", .{ args[0], self }),
        h("read .isDead/0") => fearlessBody("IsoPod_1__ZdotisDead_0_read_Zfun", .{self}),
        h("mut .consume/1") => fearlessBody("IsoPod_1__Zdotconsume_1_mut_Zfun", .{ args[0], self }),
        h("mut :=/1") => fearlessBody("IsoPod_1__Zcolon_Zeq_1_mut_Zfun", .{ args[0], self }),
        h("mut .mutate/1") => fearlessBody("IsoPod_1__Zdotmutate_1_mut_Zfun", .{ args[0], self }),
        else => objs.primitive_dispatch_failed(VT_IsoPod.type_name, target_method),
    };
}

test "IsoPod consume transfers exactly once and next releases overwritten value" {
    const testing = std.testing;
    gc.init_gc();

    const Captures = extern struct {};
    const vt_a: objs.VTable = .{ .type_name = "test.IsoA", .hashes = &.{}, .methods = &.{}, .method_names = &.{}, };
    const vt_b: objs.VTable = .{ .type_name = "test.IsoB", .hashes = &.{}, .methods = &.{} , .method_names = &.{}, };
    const vt_c: objs.VTable = .{ .type_name = "test.IsoC", .hashes = &.{}, .methods = &.{} , .method_names = &.{}, };

    var a = objs.obj_k(Captures, &vt_a, .{});
    var b = objs.obj_k(Captures, &vt_b, .{});
    var c = objs.obj_k(Captures, &vt_c, .{});
    var pod = make(a.share());
    try testing.expectEqual(@as(u32, 2), a.boxed_value().ref_count.load(.monotonic));

    var consumed = consume(pod.share());
    try testing.expectEqual(@as(u32, 2), a.boxed_value().ref_count.load(.monotonic));
    consumed.rc_decrement();
    try testing.expectEqual(@as(u32, 1), a.boxed_value().ref_count.load(.monotonic));

    _ = next(pod.share(), b.share());
    try testing.expectEqual(@as(u32, 2), b.boxed_value().ref_count.load(.monotonic));
    _ = next(pod.share(), c.share());
    try testing.expectEqual(@as(u32, 1), b.boxed_value().ref_count.load(.monotonic));
    try testing.expectEqual(@as(u32, 2), c.boxed_value().ref_count.load(.monotonic));

    pod.rc_decrement();
    try testing.expectEqual(@as(u32, 1), c.boxed_value().ref_count.load(.monotonic));

    a.rc_decrement();
    b.rc_decrement();
    c.rc_decrement();
}

const PeekViewerCaptures = extern struct { result_ptr: usize };

fn peek_viewer_some(self: FatPtr, value: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const caps = objs.deref(PeekViewerCaptures, self);
    const result: *FatPtr = @ptrFromInt(caps.result_ptr);
    result.* = value;
    return make_void();
}

fn peek_viewer_empty(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return make_void();
}

const VT_PeekViewer: objs.VTable = .{
    .type_name = "<test IsoPod peek viewer>",
    .hashes = &.{ h("mut .some/1"), h("mut .empty/0") },
    .methods = &.{ @ptrCast(&peek_viewer_some), @ptrCast(&peek_viewer_empty) },
    .method_names = &.{ "mut .some/1", "mut .empty/0" },
};

test "IsoPod peek shares the stored value with the viewer" {
    const testing = std.testing;
    gc.init_gc();

    const Captures = extern struct {};
    const vt: objs.VTable = .{ .type_name = "test.IsoPeek", .hashes = &.{}, .methods = &.{}, .method_names = &.{}, };
    var value = objs.obj_k(Captures, &vt, .{});
    var pod = make(value.share());
    try testing.expectEqual(@as(u32, 2), value.boxed_value().ref_count.load(.monotonic));

    var seen: FatPtr = undefined;
    var viewer = objs.obj_k(PeekViewerCaptures, &VT_PeekViewer, .{ .result_ptr = @intFromPtr(&seen) });
    var result = peek(pod.share(), viewer.share());
    try testing.expectEqual(@as(u32, 3), value.boxed_value().ref_count.load(.monotonic));

    result.rc_decrement();
    seen.rc_decrement();
    viewer.rc_decrement();
    pod.rc_decrement();
    value.rc_decrement();
}
