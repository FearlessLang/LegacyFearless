const std = @import("std");
const objs = @import("objs.zig");
const gc = @import("gc.zig");
const trace = @import("errors/trace.zig");

const FatPtr = objs.FatPtr;

/// Immutable error payload cell. Mirrors `var`/`isopod` cells: the atomic
/// `ref_count` sits at offset 0 so a `.primitiveContainer` FatPtr can be
/// retained and released through the uniform container path in `objs.zig`.
/// Unlike those cells the boxed `info` is never mutated after construction, so
/// the payload needs no atomics of its own.
pub const ErrorCell = extern struct {
    ref_count: std.atomic.Value(u32),
    /// Heap FatPtr of the `base.Info` describing the error. Never updated.
    info: *FatPtr,
    /// Throw-site snapshot of the originating fiber's trace, rendered on an
    /// uncaught crash. Null when `trace_frames` is off (or capture failed); the
    /// GC reclaims it when the cell becomes unreachable. Never updated.
    trace: ?*trace.TraceSnapshot = null,
};

comptime {
    std.debug.assert(@offsetOf(ErrorCell, "ref_count") == 0);
}

/// Deterministic error payload (`Error!`). Caught by both `Try` and `CapTry`.
pub const VT_RuntimeError: objs.VTable = .{
    .type_name = "<runtime error>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
};

/// Non-deterministic error payload (panic / hardware fault). Caught only by
/// `CapTry`; a plain `Try` re-propagates it.
pub const VT_RuntimeNdError: objs.VTable = .{
    .type_name = "<runtime nondeterministic error>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
};

/// Classification of a FatPtr crossing an obligation/flow join point.
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

/// Wrap `info` as a deterministic (`Error!`) error payload. Takes ownership of
/// the single reference held by `info`.
pub fn makeDeterministic(info: FatPtr) FatPtr {
    return makeCell(info, &VT_RuntimeError);
}

/// Wrap `info` as a non-deterministic (panic / hardware fault) error payload.
/// Takes ownership of the single reference held by `info`.
pub fn makeNd(info: FatPtr) FatPtr {
    return makeCell(info, &VT_RuntimeNdError);
}

/// Classify a FatPtr: `.none` for ordinary values, `.deterministic`/`.nd` for
/// the two tagged error payloads.
pub inline fn tagOf(p: FatPtr) ErrorTag {
    if (p.vt == &VT_RuntimeError) return .deterministic;
    if (p.vt == &VT_RuntimeNdError) return .nd;
    return .none;
}

/// Borrow a fresh reference to the boxed `base.Info` out of an error payload.
pub fn infoOf(p: FatPtr) FatPtr {
    return p.data.err_cell.info.*.share();
}

/// The throw-site trace snapshot carried by an error payload, if any.
pub fn traceOf(p: FatPtr) ?*trace.TraceSnapshot {
    return p.data.err_cell.trace;
}

/// RC drop hook for error payloads, routed from `objs.rc_decrement`'s
/// `.primitiveContainer` branch for both error vtables.
pub noinline fn release(cell: *ErrorCell) void {
    const old_count = cell.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;

    _ = cell.ref_count.load(.acquire);
    cell.info.*.rc_decrement();
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
    try testing.expectEqual(@as(u32, 1), info.boxed_value().ref_count.load(.monotonic));

    // makeDeterministic / makeNd each take ownership of the reference passed in.
    const det = makeDeterministic(info.share());
    try testing.expectEqual(@as(u32, 2), info.boxed_value().ref_count.load(.monotonic));
    try testing.expectEqual(ErrorTag.deterministic, tagOf(det));
    try testing.expectEqual(ErrorTag.none, tagOf(info));

    const nd = makeNd(info.share());
    try testing.expectEqual(@as(u32, 3), info.boxed_value().ref_count.load(.monotonic));
    try testing.expectEqual(ErrorTag.nd, tagOf(nd));

    // Releasing a payload drops its boxed-info reference.
    det.rc_decrement();
    try testing.expectEqual(@as(u32, 2), info.boxed_value().ref_count.load(.monotonic));
    nd.rc_decrement();
    try testing.expectEqual(@as(u32, 1), info.boxed_value().ref_count.load(.monotonic));

    // The cell itself is refcounted through the uniform container path: a
    // shared payload survives the first decrement and frees on the last.
    const det2 = makeDeterministic(info.share());
    const det2_alias = det2.share();
    try testing.expectEqual(@as(u32, 2), info.boxed_value().ref_count.load(.monotonic));
    det2.rc_decrement();
    try testing.expectEqual(@as(u32, 2), info.boxed_value().ref_count.load(.monotonic));
    det2_alias.rc_decrement();
    try testing.expectEqual(@as(u32, 1), info.boxed_value().ref_count.load(.monotonic));

    info.rc_decrement();
}
