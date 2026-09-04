//! Terminal drivers -- each pairs a ctx struct with an AcceptFn and calls
//! `run_chunk`. Accept returns `false` to request a synchronous short-circuit
//! (used by `.first`, `.findMap`, `.unorderedFindMap`).
//!
//! `any`/`all`/`none`/`find`/`first(pred)` have no dedicated drivers -- they
//! expand through the Fearless default body in `_TerminalOps[E]` which
//! routes via `.findMap` (ordered) or `.unorderedFindMap` (cancel-safe). The
//! driver-level parallel/cancel logic lives in those two paths only.

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const scope_mod = @import("../../scope.zig");
const nat_rt = @import("../nat.zig");
const list_rt = @import("../list.zig");
const root = @import("root");
const pb = root.pkg_base;

const types = @import("types.zig");
const object = @import("object.zig");
const exec = @import("exec.zig");
const engine = @import("pipeline/engine.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

const ArrayList = std.ArrayList(FatPtr);

// Every terminal funnels through here: chains with a serial-work op (actor /
// scan / ctx ops) run on the staged-fiber pipeline engine; everything else
// keeps the sequential chunk walk (data parallelism enters higher up, via the
// driver's splitMatch, which never splits stateful chains).
fn drive(flow: *types.FeartFlow, ctx: *anyopaque, accept: exec.AcceptFn) void {
    if (engine.shouldPipeline(flow)) {
        engine.run(flow, ctx, accept);
    } else {
        exec.run_chunk(flow, ctx, accept);
    }
}

const FoldCtx = struct { acc: FatPtr, combine: FatPtr };
fn fold_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *FoldCtx = @ptrCast(@alignCast(ctx_ptr));
    const old_acc = ctx.acc;
    ctx.acc = objs.call(ctx.combine, h("read #/2"), .{ old_acc.share(), elem }, @src());
    old_acc.rc_decrement();
    return true;
}
pub fn drive_fold(flow: *types.FeartFlow, initial: FatPtr, combine: FatPtr) FatPtr {
    var ctx = FoldCtx{ .acc = initial, .combine = combine };
    drive(flow, @ptrCast(&ctx), &fold_accept);
    return ctx.acc;
}

const OneCtx = struct { value: ?FatPtr };
fn first_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *OneCtx = @ptrCast(@alignCast(ctx_ptr));
    ctx.value = elem;
    return false;
}
fn last_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *OneCtx = @ptrCast(@alignCast(ctx_ptr));
    if (ctx.value) |old| old.rc_decrement();
    ctx.value = elem;
    return true;
}
pub fn drive_first(flow: *types.FeartFlow) FatPtr {
    var ctx = OneCtx{ .value = null };
    drive(flow, @ptrCast(&ctx), &first_accept);
    return if (ctx.value) |v| object.make_some(v) else object.make_none();
}
pub fn drive_last(flow: *types.FeartFlow) FatPtr {
    var ctx = OneCtx{ .value = null };
    drive(flow, @ptrCast(&ctx), &last_accept);
    return if (ctx.value) |v| object.make_some(v) else object.make_none();
}

const CountCtx = struct { n: u64 };
fn count_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *CountCtx = @ptrCast(@alignCast(ctx_ptr));
    elem.rc_decrement();
    ctx.n += 1;
    return true;
}
pub fn drive_count(flow: *types.FeartFlow) FatPtr {
    var ctx = CountCtx{ .n = 0 };
    drive(flow, @ptrCast(&ctx), &count_accept);
    return nat_rt.make(ctx.n);
}

const ListCtx = struct { al: *ArrayList };

fn list_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *ListCtx = @ptrCast(@alignCast(ctx_ptr));
    ctx.al.append(gc.allocator, elem) catch @panic("OOM");
    return true;
}
pub fn drive_list(flow: *types.FeartFlow) FatPtr {
    const storage = list_rt.make_storage(0);
    var ctx = ListCtx{ .al = &storage.al };
    drive(flow, @ptrCast(&ctx), &list_accept);
    return list_rt.wrap_list_storage(storage);
}

const ForCtx = struct { callback: FatPtr };
fn for_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *ForCtx = @ptrCast(@alignCast(ctx_ptr));
    const result = objs.call(ctx.callback, h("mut #/1"), .{elem}, @src());
    result.rc_decrement();
    return true;
}
pub fn drive_for(flow: *types.FeartFlow, callback: FatPtr) FatPtr {
    var ctx = ForCtx{ .callback = callback };
    drive(flow, @ptrCast(&ctx), &for_accept);
    return object.make_void();
}

// Used by `.findMap` / `.find` / `.first(pred)` -- ordered semantics, so the
// accept fn must NOT trigger scope.request: the merge picks the leftmost
// match, and a sibling fork could still hold an earlier match we need.
const FindCtx = struct { predicate: FatPtr, found: ?FatPtr };
fn find_map_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *FindCtx = @ptrCast(@alignCast(ctx_ptr));
    const result = objs.call(ctx.predicate, h("read #/1"), .{elem}, @src());
    if (result.vt == &pb.VT_Opt_1) {
        result.rc_decrement();
        return true;
    }
    ctx.found = exec.extract_some(result);
    return false;
}
pub fn drive_find_map(flow: *types.FeartFlow, mapper: FatPtr) FatPtr {
    var ctx = FindCtx{ .predicate = mapper, .found = null };
    drive(flow, @ptrCast(&ctx), &find_map_accept);
    return if (ctx.found) |v| object.make_some(v) else object.make_none();
}

// Used by `.any` / `.all` / `.none` (through the `_TerminalOps` defaults)
// and by any direct caller of `.unorderedFindMap`. On first match,
// the accept fn calls `scope.request()` so any sibling forks running on
// thief fibers observe the cancel at their next per-iteration scope poll.
fn unordered_find_map_accept(ctx_ptr: *anyopaque, elem: FatPtr) bool {
    const ctx: *FindCtx = @ptrCast(@alignCast(ctx_ptr));
    const result = objs.call(ctx.predicate, h("read #/1"), .{elem}, @src());
    if (result.vt == &pb.VT_Opt_1) {
        result.rc_decrement();
        return true;
    }
    ctx.found = exec.extract_some(result);
    if (scope_mod.activeScope()) |s| s.request();
    return false;
}
pub fn drive_unordered_find_map(flow: *types.FeartFlow, mapper: FatPtr) FatPtr {
    var ctx = FindCtx{ .predicate = mapper, .found = null };
    drive(flow, @ptrCast(&ctx), &unordered_find_map_accept);
    return if (ctx.found) |v| object.make_some(v) else object.make_none();
}
