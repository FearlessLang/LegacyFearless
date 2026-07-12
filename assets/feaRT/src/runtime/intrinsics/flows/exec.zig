//! Chunk executor -- the opaque-to-APM inner loop.
//!
//! `run_chunk` pulls elements from a flow's source and walks each one through
//! the flat `[]OpDesc` array. User closures are invoked through `objs.call`
//! (still Fearless frames, so the heartbeat prologue sees them); the walk
//! itself stays in Zig so per-op dispatch cost is paid once per closure call,
//! not once per heartbeat check.

const std = @import("std");
const objs = @import("../../objs.zig");
const scope_mod = @import("../../scope.zig");
const unwind = @import("../../errors/unwind.zig");
const root = @import("root");
const pb = root.pkg_base;

const types = @import("types.zig");
const object = @import("object.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

// Terminal callback: returns `true` to continue pulling elements, `false` to
// signal synchronous short-circuit (e.g. `.first` / ordered `.findMap`).
pub const AcceptFn = *const fn (ctx: *anyopaque, elem: FatPtr) bool;

// Result of applying one op to one element.
const Applied = union(enum) {
    pass: FatPtr, // element (possibly transformed) continues
    pass_then_stop: FatPtr, // element continues + accepts, then stop pulling
    skip, // filter rejected; discard this source element
    done, // op triggered short-circuit (limit exhausted etc)
    expanded, // op (flat_map / actor) handled the suffix itself
};

// Per-iteration cancel poll. Each iteration of run_chunk's while loop calls
// arbitrary user closures via the op chain (map closures, filter predicates,
// flatMap inner flows, etc.) -- those can be unboundedly expensive, so polling
// only every N=1024 elements could leave the cancel signal unobserved for
// seconds-to-minutes. The atomic acquire-load is ~2-5 ns; on a 100M-element
// flow that's ~200-500 ms total overhead, which is acceptable. If profiling
// later shows this dominates a benchmark we can tune to a small threshold
// (e.g. 4-8); not motivated yet.
pub fn run_chunk(flow: *types.FeartFlow, ctx: *anyopaque, accept: AcceptFn) void {
    var stopped = false;
    while (!stopped and types.source_has_next(&flow.source)) {
        if (scope_mod.currentCancelled()) return;
        process_element(flow.ops, types.source_next(&flow.source), ctx, accept, &stopped);
    }
}

// Walk `ops` applying each to the element; if we run out of ops, invoke accept.
fn process_element(
    ops: []types.OpDesc,
    init_elem: FatPtr,
    ctx: *anyopaque,
    accept: AcceptFn,
    stopped: *bool,
) void {
    var current = init_elem;
    var stop_after = false;
    for (ops, 0..) |*op, i| {
        switch (apply_op(op, current, ops[i + 1 ..], ctx, accept, stopped)) {
            .pass => |next| current = next,
            .pass_then_stop => |next| {
                current = next;
                stop_after = true;
            },
            .skip => return,
            .done => {
                stopped.* = true;
                return;
            },
            .expanded => return,
        }
        if (stopped.*) return;
    }
    if (!accept(ctx, current)) stopped.* = true;
    if (stop_after) stopped.* = true;
}

fn apply_op(
    op: *types.OpDesc,
    elem: FatPtr,
    remaining: []types.OpDesc,
    ctx: *anyopaque,
    accept: AcceptFn,
    stopped: *bool,
) Applied {
    return switch (op.kind) {
        .map => .{ .pass = objs.call(op.closure.share(), h("read #/1"), .{elem}, @src()) },
        .peek => blk: {
            const result = objs.call(op.closure.share(), h("read #/1"), .{elem.share()}, @src());
            result.rc_decrement();
            break :blk .{ .pass = elem };
        },
        .map_ctx => blk: {
            const cell: *types.CtxCell = @ptrFromInt(op.state);
            const iso_ctx = objs.call(cell.ctx.share(), h("mut .iso/0"), .{}, @src());
            const cval = objs.call(iso_ctx, h("mut .self/0"), .{}, @src());
            break :blk .{ .pass = objs.call(op.closure.share(), h("read #/2"), .{ cval, elem }, @src()) };
        },
        .peek_ctx => blk: {
            const cell: *types.CtxCell = @ptrFromInt(op.state);
            const iso_ctx = objs.call(cell.ctx.share(), h("mut .iso/0"), .{}, @src());
            const cval = objs.call(iso_ctx, h("mut .self/0"), .{}, @src());
            const result = objs.call(op.closure.share(), h("read #/2"), .{ cval, elem.share() }, @src());
            result.rc_decrement();
            break :blk .{ .pass = elem };
        },
        .filter => blk: {
            const ok = objs.call(op.closure.share(), h("read #/1"), .{elem.share()}, @src());
            const passed = ok.vt == &pb.VT_True_0;
            ok.rc_decrement();
            if (passed) break :blk .{ .pass = elem };
            elem.rc_decrement();
            break :blk .skip;
        },
        .map_filter => blk: {
            const opt = objs.call(op.closure.share(), h("read #/1"), .{elem}, @src());
            if (opt.vt == &pb.VT_Opt_1) {
                opt.rc_decrement();
                break :blk .skip;
            }
            break :blk .{ .pass = extract_some(opt) };
        },
        .scan => blk: {
            const cell: *types.ScanCell = @ptrFromInt(op.state);
            const old_acc = cell.acc;
            cell.acc = objs.call(op.closure.share(), h("read #/2"), .{ old_acc.share(), elem }, @src());
            old_acc.rc_decrement();
            break :blk .{ .pass = cell.acc.share() };
        },
        .limit => blk: {
            if (op.state == 0) {
                elem.rc_decrement();
                break :blk .done;
            }
            op.state -= 1;
            // Emitting the last allowed element: pass it through (so it's
            // accepted) but stop pulling afterwards. Otherwise the next source
            // pull would run the upstream ops on an element we'd discard --
            // observable if one of those ops throws (`.map{Error.msg "foo"}.limit(1)`).
            if (op.state == 0) break :blk .{ .pass_then_stop = elem };
            break :blk .{ .pass = elem };
        },
        .flat_map => blk: {
            const inner_fp = objs.call(op.closure.share(), h("read #/1"), .{elem}, @src());
            const inner = object.deref_flow(inner_fp);
            while (!stopped.* and types.source_has_next(&inner.source)) {
                const inner_elem = types.source_next(&inner.source);
                process_through(inner.ops, remaining, inner_elem, ctx, accept, stopped);
            }
            inner_fp.rc_decrement();
            break :blk .expanded;
        },
        .actor => blk: {
            const state: *types.ActorState = @ptrFromInt(op.state);
            const sink = objs.obj_k(ActorSinkCaptures, &VT_ActorSink, .{
                .remaining_ptr = @intFromPtr(remaining.ptr),
                .remaining_len = remaining.len,
                .ctx_ptr = @intFromPtr(ctx),
                .accept_ptr = @intFromPtr(accept),
                .stopped_ptr = @intFromPtr(stopped),
            });
            const res = objs.call(state.callback.share(), h("read #/3"), .{ sink, state.state_fp.share(), elem }, @src());
            const is_stopped = objs.call(res, h("imm .match/1"), .{objs.obj_k_singleton(&VT_ActorResMatch)}, @src());
            if (is_stopped.vt == &pb.VT_True_0) stopped.* = true;
            is_stopped.rc_decrement();
            break :blk .expanded;
        },
    };
}

// Used by flat_map's nested loop: walk `first_ops` first, then `second_ops`,
// without allocating a concatenated slice.
fn process_through(
    first_ops: []types.OpDesc,
    second_ops: []types.OpDesc,
    init_elem: FatPtr,
    ctx: *anyopaque,
    accept: AcceptFn,
    stopped: *bool,
) void {
    var current = init_elem;
    var stop_after = false;
    for (first_ops, 0..) |*op, i| {
        switch (apply_op(op, current, first_ops[i + 1 ..], ctx, accept, stopped)) {
            .pass => |next| current = next,
            .pass_then_stop => |next| {
                current = next;
                stop_after = true;
            },
            .skip => return,
            .done => {
                stopped.* = true;
                return;
            },
            .expanded => return,
        }
        if (stopped.*) return;
    }
    process_element(second_ops, current, ctx, accept, stopped);
    if (stop_after) stopped.* = true;
}

// ==========================================
// Opt extractor helper (for mapFilter / findMap)
// ==========================================

const OptExtractCaptures = extern struct { result_ptr: usize };

fn opt_extract_some(self: FatPtr, val: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const caps = objs.deref(OptExtractCaptures, self);
    const ptr: *FatPtr = @ptrFromInt(caps.result_ptr);
    ptr.* = val;
    return object.make_void();
}

fn opt_extract_none(_: FatPtr) callconv(.c) FatPtr {
    unreachable;
}

// Opt's match dispatches via `OptMatch[T,R]: {mut .some(x: T): R, mut .empty: R}`
// (see assets/base/optionals.fear:55). The body in `Opts.#` ends up calling
// `m.some(x)` / `m.empty` with whatever modifier the receiver permits -- which
// in practice is `mut`, but registering all three variants is cheap insurance
// (mirrors the small runtime helper vtable pattern).
pub const VT_OptExtract: objs.VTable = .{
    .type_name = "<runtime flow opt extractor>",
    .hashes = &.{
        h("mut .some/1"),  h("read .some/1"),  h("imm .some/1"),
        h("mut .empty/0"), h("read .empty/0"), h("imm .empty/0"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&opt_extract_some)),
        @as(*const anyopaque, @ptrCast(&opt_extract_some)),
        @as(*const anyopaque, @ptrCast(&opt_extract_some)),
        @as(*const anyopaque, @ptrCast(&opt_extract_none)),
        @as(*const anyopaque, @ptrCast(&opt_extract_none)),
        @as(*const anyopaque, @ptrCast(&opt_extract_none)),
    },
    .method_names = &.{
        "mut .some/1",  "read .some/1",  "imm .some/1",
        "mut .empty/0", "read .empty/0", "imm .empty/0",
    },
};

// Helper accessible to findMap in terminals.zig.
pub fn extract_some(opt: FatPtr) FatPtr {
    var extracted: FatPtr = undefined;
    const extractor = objs.obj_k(OptExtractCaptures, &VT_OptExtract, .{ .result_ptr = @intFromPtr(&extracted) });
    const matched = objs.call(opt, h("imm .match/1"), .{extractor}, @src());
    matched.rc_decrement();
    return extracted;
}

// ==========================================
// Actor sink -- downstream reinjection for the .actor op callback
// ==========================================

const ActorSinkCaptures = extern struct {
    remaining_ptr: usize,
    remaining_len: usize,
    ctx_ptr: usize,
    accept_ptr: usize,
    stopped_ptr: usize,
};

fn actor_sink_accept(self: FatPtr, element: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const caps = objs.deref(ActorSinkCaptures, self);
    const remaining: []types.OpDesc = @as([*]types.OpDesc, @ptrFromInt(caps.remaining_ptr))[0..caps.remaining_len];
    const ctx: *anyopaque = @ptrFromInt(caps.ctx_ptr);
    const accept: AcceptFn = @ptrFromInt(caps.accept_ptr);
    const stopped: *bool = @ptrFromInt(caps.stopped_ptr);
    process_element(remaining, element, ctx, accept, stopped);
    return object.make_void();
}

fn actor_sink_push_error(self: FatPtr, info: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const caps = objs.deref(ActorSinkCaptures, self);
    const stopped: *bool = @ptrFromInt(caps.stopped_ptr);
    if (stopped.*) {
        info.rc_decrement();
        return object.make_void();
    }
    unwind.throwDeterministic(info);
}

const VT_ActorSink: objs.VTable = .{
    .type_name = "<runtime flow actor sink>",
    .hashes = &.{ h("mut #/1"), h("mut .pushError/1") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&actor_sink_accept)),
        @as(*const anyopaque, @ptrCast(&actor_sink_push_error)),
    },
    .method_names = &.{ "mut #/1", "mut .pushError/1" },
};

fn actor_match_continue(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return objs.obj_k_singleton(&pb.VT_False_0);
}

fn actor_match_stop(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return objs.obj_k_singleton(&pb.VT_True_0);
}

const VT_ActorResMatch: objs.VTable = .{
    .type_name = "<runtime flow actor-res matcher>",
    .hashes = &.{ h("mut .continue/0"), h("mut .stop/0") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&actor_match_continue)),
        @as(*const anyopaque, @ptrCast(&actor_match_stop)),
    },
    .method_names = &.{ "mut .continue/0", "mut .stop/0" },
    .storage_mode = .singleton,
};
