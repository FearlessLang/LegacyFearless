//! The op chain of a flow, as a node of the cycle collector.
//!
//! A split gives both halves the same chain by reference and counts the chain,
//! not the closures in it. A flow that enumerated those closures itself would
//! therefore count one reference once per half, and the collector would take a
//! live closure for garbage. The chain is a node of its own instead, and a flow
//! holds one edge to it.
//!
//! This lives apart from `object.zig` so that `objs.zig` can reach the release
//! and free hooks without reaching the generated base package.

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const cycles = @import("../../cycles.zig");
const worker_mod = @import("../../worker.zig");
const types = @import("types.zig");

const FatPtr = objs.FatPtr;

/// An `OpDesc` slice holds a pointer, so this struct cannot be `extern` and its
/// header is not guaranteed to sit at offset 0. [`opsEdge`] therefore names the
/// header itself, and [`opsOfEdge`] walks back to the chain from it.
pub const FlowOps = struct {
    header: objs.RcCellHeader,
    ops: []types.OpDesc,
};

/// The vtable of a `FlowOps`. No program value names an op chain, so this
/// exists only for the `FatPtr` the collector builds over one.
pub const VT_FlowOps: objs.VTable = .{
    .type_name = "<runtime flow ops>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
    .trace_fn = ops_trace,
};

/// The `FatPtr` the collector follows to reach `node`. It names the header
/// rather than the chain, which is what lets the collector read a colour and a
/// count through the same cast it uses for every other cell.
pub fn opsEdge(node: *FlowOps) FatPtr {
    return .{ .data = .{ .raw_cell = @ptrCast(&node.header) }, .vt = &VT_FlowOps };
}

/// The chain whose header `node` names.
pub fn opsOfEdge(node: FatPtr) *FlowOps {
    const header: *objs.RcCellHeader = @ptrCast(@alignCast(node.data.raw_cell));
    return @alignCast(@fieldParentPtr("header", header));
}

/// Takes ownership of `ops`, which the chain frees when it dies.
pub fn make_ops(ops: []types.OpDesc) *FlowOps {
    const node = gc.recycleAlloc(FlowOps);
    node.* = .{ .header = .born, .ops = ops };
    // A scan accumulator and an actor state are written as the chain runs, so a
    // chain that holds either is a node a cycle can run through.
    for (ops) |op| switch (op.kind) {
        .scan, .actor => {
            cycles.noteMutable(opsEdge(node));
            break;
        },
        else => {},
    };
    // The flow about to be built holds the one reference `born` gives it.
    return node;
}

/// Enumerates every reference the ops hold, for the cycle collector.
///
/// The chain itself is fixed once built. The cells a `scan` or an `actor` op
/// names are not: those hold a value the executor replaces as elements go past,
/// which is why `make_ops` enters such a chain into the candidate set.
fn ops_trace(node: *anyopaque, visit: objs.VisitFn, ctx: *anyopaque) callconv(.c) void {
    const header: *objs.RcCellHeader = @ptrCast(@alignCast(node));
    const self: *FlowOps = @alignCast(@fieldParentPtr("header", header));
    for (self.ops) |op| visit_op(op, visit, ctx);
}

fn visit_op(op: types.OpDesc, visit: objs.VisitFn, ctx: *anyopaque) void {
    switch (op.kind) {
        .scan => {
            const cell: *types.ScanCell = @ptrFromInt(op.state);
            visit(ctx, cell.acc);
            visit(ctx, op.closure);
        },
        .actor => {
            const state: *types.ActorState = @ptrFromInt(op.state);
            visit(ctx, state.state_fp);
            visit(ctx, state.callback);
        },
        .map_ctx, .peek_ctx => {
            const cell: *types.CtxCell = @ptrFromInt(op.state);
            visit(ctx, cell.ctx);
            visit(ctx, op.closure);
        },
        .limit => {},
        .map, .filter, .peek, .map_filter, .flat_map => visit(ctx, op.closure),
    }
}

/// Releases every reference a dead chain holds. Part of the collector's
/// `Release`, which runs as soon as the count reaches zero.
///
/// The state cells stay whole, because `Free` is what gives storage back.
pub fn drop_children(node: *FlowOps, releasing_worker_id: u32) void {
    for (node.ops) |op| release_op_children(op, releasing_worker_id);
}

fn release_op_children(op: types.OpDesc, releasing_worker_id: u32) void {
    switch (op.kind) {
        .scan => {
            const cell: *types.ScanCell = @ptrFromInt(op.state);
            cell.acc.rc_decrement_as(releasing_worker_id);
            op.closure.rc_decrement_as(releasing_worker_id);
        },
        .actor => {
            const state: *types.ActorState = @ptrFromInt(op.state);
            state.state_fp.rc_decrement_as(releasing_worker_id);
            state.callback.rc_decrement_as(releasing_worker_id);
        },
        .map_ctx, .peek_ctx => {
            const cell: *types.CtxCell = @ptrFromInt(op.state);
            cell.ctx.rc_decrement_as(releasing_worker_id);
            op.closure.rc_decrement_as(releasing_worker_id);
        },
        .limit => {},
        .map, .filter, .peek, .map_filter, .flat_map => op.closure.rc_decrement_as(releasing_worker_id),
    }
}

/// Gives a dead chain back, with the state cells and the op slice it owns.
pub fn free_ops(node: *FlowOps, releasing_worker_id: u32) void {
    _ = releasing_worker_id;
    for (node.ops) |op| free_op_state(op);
    gc.recycleDestroySlice(types.OpDesc, node.ops, .flow_op_release);
    gc.recycleDestroy(FlowOps, node, .flow_op_release);
}

fn free_op_state(op: types.OpDesc) void {
    switch (op.kind) {
        .scan => gc.recycleDestroy(types.ScanCell, @as(*types.ScanCell, @ptrFromInt(op.state)), .flow_op_release),
        .actor => gc.recycleDestroy(types.ActorState, @as(*types.ActorState, @ptrFromInt(op.state)), .flow_op_release),
        .map_ctx, .peek_ctx => gc.recycleDestroy(types.CtxCell, @as(*types.CtxCell, @ptrFromInt(op.state)), .flow_op_release),
        .limit, .map, .filter, .peek, .map_filter, .flat_map => {},
    }
}
