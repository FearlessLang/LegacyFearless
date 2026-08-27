const std = @import("std");
const objs = @import("objs.zig");
const gc = @import("gc.zig");
const trace = @import("errors/trace.zig");

const FatPtr = objs.FatPtr;

/// Immutable error payload cell. `ref_count` sits at offset 0 so a
/// `.primitiveContainer` FatPtr can be retained through the uniform container
/// path in `objs.zig`. Nothing after `ref_count` is ever written again.
pub const ErrorCell = extern struct {
    ref_count: std.atomic.Value(u32),
    info: *FatPtr,
    /// Rendered on an uncaught crash. Null when `trace_frames` is off.
    trace: ?*trace.TraceSnapshot = null,
};

comptime {
    std.debug.assert(@offsetOf(ErrorCell, "ref_count") == 0);
}

/// Deterministic error payload (`Error!`). Caught by `Try` and `CapTry`.
pub const VT_RuntimeError: objs.VTable = .{
    .type_name = "<runtime error>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
};

/// Non-deterministic error payload (panic / hardware fault). Caught only by
/// `CapTry`; `Try` re-propagates it.
pub const VT_RuntimeNdError: objs.VTable = .{
    .type_name = "<runtime nondeterministic error>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
};

pub const ErrorTag = enum { none, deterministic, nd };

fn makeCell(info: FatPtr, comptime vt: *const objs.VTable) FatPtr {
    const box = gc.allocator.create(FatPtr) catch @panic("OOM");
    box.* = info;
    const cell = gc.allocator.create(ErrorCell) catch @panic("OOM");
    cell.* = .{
        .ref_count = std.atomic.Value(u32).init(1),
        .info = box,
        .trace = trace.captureTrace(),
    };
    gc.recordRcAlloc();
    return .{ .data = .{ .err_cell = cell }, .vt = vt };
}

/// Takes ownership of the reference held by `info`.
pub fn makeDeterministic(info: FatPtr) FatPtr {
    return makeCell(info, &VT_RuntimeError);
}

/// Takes ownership of the reference held by `info`.
pub fn makeNd(info: FatPtr) FatPtr {
    return makeCell(info, &VT_RuntimeNdError);
}

pub inline fn tagOf(p: FatPtr) ErrorTag {
    if (p.vt == &VT_RuntimeError) return .deterministic;
    if (p.vt == &VT_RuntimeNdError) return .nd;
    return .none;
}

/// Fresh reference to the boxed `base.Info`.
pub fn infoOf(p: FatPtr) FatPtr {
    return p.data.err_cell.info.*.share();
}

pub fn traceOf(p: FatPtr) ?*trace.TraceSnapshot {
    return p.data.err_cell.trace;
}

/// RC drop hook, routed from `objs.rc_decrement_as`'s `.primitiveContainer`
/// branch. `releasing_worker_id` is the identity releasing the cell.
pub noinline fn release(cell: *ErrorCell, releasing_worker_id: u32) void {
    const old_count = cell.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;

    _ = cell.ref_count.load(.acquire);
    cell.info.*.rc_decrement_as(releasing_worker_id);
    gc.recycleDestroy(FatPtr, cell.info, .error_release);
    gc.recycleDestroy(ErrorCell, cell, .error_release);
    gc.recordRcFree();
}

test "error payloads tag correctly and release their boxed info" {
    const testing = std.testing;
    gc.init_gc();

    const Caps = extern struct {};
    const vt: objs.VTable = .{ .type_name = "test.Info", .hashes = &.{}, .methods = &.{}, .method_names = &.{} };

    var info = objs.obj_k(Caps, &vt, .{});
    try testing.expectEqual(@as(u32, 1), info.boxed_value().refCountForTest());

    // makeDeterministic / makeNd each take ownership of the reference passed in.
    const det = makeDeterministic(info.share());
    try testing.expectEqual(@as(u32, 2), info.boxed_value().refCountForTest());
    try testing.expectEqual(ErrorTag.deterministic, tagOf(det));
    try testing.expectEqual(ErrorTag.none, tagOf(info));

    const nd = makeNd(info.share());
    try testing.expectEqual(@as(u32, 3), info.boxed_value().refCountForTest());
    try testing.expectEqual(ErrorTag.nd, tagOf(nd));

    det.rc_decrement();
    try testing.expectEqual(@as(u32, 2), info.boxed_value().refCountForTest());
    nd.rc_decrement();
    try testing.expectEqual(@as(u32, 1), info.boxed_value().refCountForTest());

    // A shared payload survives the first decrement and frees on the last.
    const det2 = makeDeterministic(info.share());
    const det2_alias = det2.share();
    try testing.expectEqual(@as(u32, 2), info.boxed_value().refCountForTest());
    det2.rc_decrement();
    try testing.expectEqual(@as(u32, 2), info.boxed_value().refCountForTest());
    det2_alias.rc_decrement();
    try testing.expectEqual(@as(u32, 1), info.boxed_value().refCountForTest());

    info.rc_decrement();
}
