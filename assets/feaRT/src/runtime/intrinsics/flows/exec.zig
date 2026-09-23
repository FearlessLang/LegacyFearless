//! Chunk executor. APM cannot see this loop. User closures run through `objs.call`, so they
//! are Fearless frames and their heartbeat prologues run.

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

/// Terminal callback. Returns false to stop the pull of elements.
pub const AcceptFn = *const fn (ctx: *anyopaque, elem: FatPtr) bool;

const Applied = union(enum) {
    pass: FatPtr,
    /// The element continues, then the pull stops.
    pass_then_stop: FatPtr,
    skip,
    /// The op stops the pull now.
    done,
    /// The op ran the remaining ops itself.
    expanded,
};

pub fn run_chunk(flow: *types.FeartFlow, ctx: *anyopaque, accept: AcceptFn) void {
    var stopped = false;
    while (!stopped and types.source_has_next(&flow.source)) {
        // Poll each element. One element can run user closures of any cost, so a stride can
        // hide a cancel for seconds.
        if (scope_mod.currentCancelled()) return;
        process_element(flow.ops, types.source_next(&flow.source), ctx, accept, &stopped);
    }
}

pub fn process_element(
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

/// The element arrives owned, and a closure only borrows it. An arm that returns `.pass`
/// gives that reference on. All other arms release it.
fn apply_op(
    op: *types.OpDesc,
    elem: FatPtr,
    remaining: []types.OpDesc,
    ctx: *anyopaque,
    accept: AcceptFn,
    stopped: *bool,
) Applied {
    return switch (op.kind) {
        .map => blk: {
            defer elem.rc_decrement();
            break :blk .{ .pass = objs.call(op.closure, h("read #/1"), .{elem}, @src()) };
        },
        .peek => blk: {
            const result = objs.call(op.closure, h("read #/1"), .{elem}, @src());
            result.rc_decrement();
            break :blk .{ .pass = elem };
        },
        .map_ctx => blk: {
            const cell: *types.CtxCell = @ptrFromInt(op.state);
            const iso_ctx = objs.call(cell.ctx, h("mut .iso/0"), .{}, @src());
            defer iso_ctx.rc_decrement();
            const cval = objs.call(iso_ctx, h("mut .self/0"), .{}, @src());
            defer cval.rc_decrement();
            defer elem.rc_decrement();
            break :blk .{ .pass = objs.call(op.closure, h("read #/2"), .{ cval, elem }, @src()) };
        },
        .peek_ctx => blk: {
            const cell: *types.CtxCell = @ptrFromInt(op.state);
            const iso_ctx = objs.call(cell.ctx, h("mut .iso/0"), .{}, @src());
            defer iso_ctx.rc_decrement();
            const cval = objs.call(iso_ctx, h("mut .self/0"), .{}, @src());
            defer cval.rc_decrement();
            const result = objs.call(op.closure, h("read #/2"), .{ cval, elem }, @src());
            result.rc_decrement();
            break :blk .{ .pass = elem };
        },
        .filter => blk: {
            const ok = objs.call(op.closure, h("read #/1"), .{elem}, @src());
            const passed = ok.vt == &pb.VT_True_0;
            ok.rc_decrement();
            if (passed) break :blk .{ .pass = elem };
            elem.rc_decrement();
            break :blk .skip;
        },
        .map_filter => blk: {
            defer elem.rc_decrement();
            const opt = objs.call(op.closure, h("read #/1"), .{elem}, @src());
            if (opt.vt == &pb.VT_Opt_1) {
                opt.rc_decrement();
                break :blk .skip;
            }
            break :blk .{ .pass = extract_some(opt) };
        },
        .scan => blk: {
            const cell: *types.ScanCell = @ptrFromInt(op.state);
            defer elem.rc_decrement();
            const old_acc = cell.acc;
            cell.acc = objs.call(op.closure, h("read #/2"), .{ old_acc, elem }, @src());
            old_acc.rc_decrement();
            break :blk .{ .pass = cell.acc.share() };
        },
        .limit => blk: {
            if (op.state == 0) {
                elem.rc_decrement();
                break :blk .done;
            }
            op.state -= 1;
            // Stop after the last element. Another pull runs the upstream ops on an element
            // that the limit drops, and an error there is visible (`.map{Error.msg "x"}.limit(1)`).
            if (op.state == 0) break :blk .{ .pass_then_stop = elem };
            break :blk .{ .pass = elem };
        },
        .flat_map => blk: {
            const inner_fp = objs.call(op.closure, h("read #/1"), .{elem}, @src());
            elem.rc_decrement();
            const inner = object.deref_flow(inner_fp);
            while (!stopped.* and types.source_has_next(&inner.source)) {
                const inner_elem = types.source_next(&inner.source);
                process_through(inner.ops, remaining, inner_elem, ctx, accept, stopped);
            }
            inner_fp.rc_decrement();
            break :blk .expanded;
        },
        .chain => blk: {
            const list_fp = objs.call(op.closure, h("read #/1"), .{elem}, @src());
            elem.rc_decrement();
            const inner_fp = objs.call(list_fp, h("mut .flow/0"), .{}, @src());
            list_fp.rc_decrement();
            const inner = object.deref_flow(inner_fp);
            while (!stopped.* and types.source_has_next(&inner.source)) {
                const inner_elem = types.source_next(&inner.source);
                process_through(inner.ops, remaining, inner_elem, ctx, accept, stopped);
            }
            inner_fp.rc_decrement();
            break :blk .expanded;
        },
        .actor => blk: {
            defer elem.rc_decrement();
            const state: *types.ActorState = @ptrFromInt(op.state);
            const sink = objs.obj_k(ActorSinkCaptures, &VT_ActorSink, .{
                .remaining_ptr = @intFromPtr(remaining.ptr),
                .remaining_len = remaining.len,
                .ctx_ptr = @intFromPtr(ctx),
                .accept_ptr = @intFromPtr(accept),
                .stopped_ptr = @intFromPtr(stopped),
            });
            defer sink.rc_decrement();
            const res = objs.call(state.callback, h("read #/3"), .{ sink, state.state_fp, elem }, @src());
            defer res.rc_decrement();
            const is_stopped = objs.call(res, h("imm .match/1"), .{objs.obj_k_singleton(&VT_ActorResMatch)}, @src());
            if (is_stopped.vt == &pb.VT_True_0) stopped.* = true;
            is_stopped.rc_decrement();
            break :blk .expanded;
        },
    };
}

/// Applies stateless element ops to one owned element. Returns null when a filter drops it.
pub fn apply_prefix(ops: []types.OpDesc, elem: FatPtr) ?FatPtr {
    var current = elem;
    var dummy: u8 = 0;
    var stopped = false;
    for (ops) |*op| {
        switch (apply_op(op, current, &.{}, @ptrCast(&dummy), &never_accept, &stopped)) {
            .pass => |next| current = next,
            .skip => return null,
            .pass_then_stop, .done, .expanded => unreachable,
        }
    }
    return current;
}

fn never_accept(ctx: *anyopaque, elem: FatPtr) bool {
    _ = ctx;
    _ = elem;
    unreachable;
}

/// Applies `first_ops`, then `second_ops`, without a concatenated slice.
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

const OptExtractCaptures = extern struct { result_ptr: usize };

fn opt_extract_some(self: FatPtr, val: FatPtr) callconv(.c) FatPtr {
    const caps = objs.deref(OptExtractCaptures, self);
    const ptr: *FatPtr = @ptrFromInt(caps.result_ptr);
    // The slot outlives the arm, so it keeps its own reference.
    ptr.* = val.share().box_transient();
    return object.make_void();
}

fn opt_extract_none(_: FatPtr) callconv(.c) FatPtr {
    unreachable;
}

/// `Opts.#` can call `.some` and `.empty` with any receiver modifier, so the table has all three.
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

pub fn extract_some(opt: FatPtr) FatPtr {
    var extracted: FatPtr = undefined;
    const extractor = objs.obj_k(OptExtractCaptures, &VT_OptExtract, .{ .result_ptr = @intFromPtr(&extracted) });
    defer opt.rc_decrement();
    const matched = objs.call(opt, h("imm .match/1"), .{extractor}, @src());
    extractor.rc_decrement();
    matched.rc_decrement();
    return extracted;
}

const ActorSinkCaptures = extern struct {
    remaining_ptr: usize,
    remaining_len: usize,
    ctx_ptr: usize,
    accept_ptr: usize,
    stopped_ptr: usize,
};

fn actor_sink_accept(self: FatPtr, element: FatPtr) callconv(.c) FatPtr {
    const caps = objs.deref(ActorSinkCaptures, self);
    const remaining: []types.OpDesc = @as([*]types.OpDesc, @ptrFromInt(caps.remaining_ptr))[0..caps.remaining_len];
    const ctx: *anyopaque = @ptrFromInt(caps.ctx_ptr);
    const accept: AcceptFn = @ptrFromInt(caps.accept_ptr);
    const stopped: *bool = @ptrFromInt(caps.stopped_ptr);
    // The ops own their element, but the actor body lends this one. Box it, because the
    // terminal can outlive the frame of the actor.
    process_element(remaining, element.share().box_transient(), ctx, accept, stopped);
    return object.make_void();
}

fn actor_sink_push_error(self: FatPtr, info: FatPtr) callconv(.c) FatPtr {
    const caps = objs.deref(ActorSinkCaptures, self);
    const stopped: *bool = @ptrFromInt(caps.stopped_ptr);
    if (stopped.*) {
        return object.make_void();
    }
    // `throwDeterministic` takes a reference, and `info` is lent.
    unwind.throwDeterministic(info.share());
}

const VT_ActorSink: objs.VTable = .{
    .type_name = "base.flows._ActorSink/1",
    .hashes = &.{ h("mut #/1"), h("mut .pushError/1") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&actor_sink_accept)),
        @as(*const anyopaque, @ptrCast(&actor_sink_push_error)),
    },
    .method_names = &.{ "mut #/1", "mut .pushError/1" },
};

fn actor_match_continue(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    return objs.obj_k_singleton(&pb.VT_False_0);
}

fn actor_match_stop(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
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
