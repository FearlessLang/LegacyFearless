//! Flow object layout + constructors.
//!
//! A Flow's FatPtr wraps a `FlowCaptures` whose single field is an integer
//! pointer to a heap-allocated `FeartFlow`. We keep the flow on the heap
//! (rather than inline) so append/split can create new flows cheaply without
//! re-boxing closures.

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const list_rt = @import("../list.zig");
const native = @import("../../native.zig");
const root = @import("root");
const pb = root.pkg_base;

const types = @import("types.zig");
const string_flows = @import("string_flows.zig");
const FatPtr = objs.FatPtr;

const ArrayList = std.ArrayList(FatPtr);

pub const FlowCaptures = extern struct { flow_ptr: usize };

pub fn deref_flow(fp: FatPtr) *types.FeartFlow {
    const caps = objs.deref(FlowCaptures, fp);
    return @ptrFromInt(caps.flow_ptr);
}

pub fn make_flow_fp(comptime vt: *const objs.VTable, f: *types.FeartFlow) FatPtr {
    return objs.obj_k(FlowCaptures, vt, .{ .flow_ptr = @intFromPtr(f) });
}

pub fn create_flow(source: types.Source, is_finite: bool) *types.FeartFlow {
    const f = gc.recycleAlloc(types.FeartFlow);
    f.* = .{
        .source = source,
        .ops = &.{},
        .ops_ref_count = null,
        .is_finite = is_finite,
        .ref_count = std.atomic.Value(u32).init(1),
    };
    return f;
}

pub fn retain_flow(f: *types.FeartFlow) void {
    _ = f.ref_count.fetchAdd(1, .monotonic);
}

/// `releasing_worker_id` is the worker on whose behalf this release runs. It
/// travels down from the start of the drop chain.
pub fn release_flow(f: *types.FeartFlow, releasing_worker_id: u32) void {
    const old_count = f.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;
    _ = f.ref_count.load(.acquire);

    release_source(f, releasing_worker_id);
    release_ops(f, releasing_worker_id);
    gc.recycleDestroy(types.FeartFlow, f, .flow_release);
}

pub fn flow_drop(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(FlowCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    release_flow(@ptrFromInt(self.captures.flow_ptr), releasing_worker_id);
}

fn release_source(f: *types.FeartFlow, releasing_worker_id: u32) void {
    // String sources own their owner Str directly (graphemes use a stateless
    // native lookup, so there is no cursor to release).
    switch (f.source) {
        .str => |ss| {
            ss.owner.rc_decrement_as(releasing_worker_id);
            return;
        },
        else => {},
    }

    if (f.source_owner) |source_owner| {
        source_owner.rc_decrement_as(releasing_worker_id);
        return;
    }

    switch (f.source) {
        // List sources own one reference to every item in their slice; iteration
        // shares items out instead of moving them.
        .list => |ls| for (ls.items) |item| item.rc_decrement_as(releasing_worker_id),
        .single => |ss| if (!ss.consumed) ss.value.rc_decrement_as(releasing_worker_id),
        .str => unreachable, // handled above
        .range_finite, .range_infinite, .empty => {},
    }
}

fn retain_source(source: types.Source, source_owner: ?FatPtr) ?FatPtr {
    if (source_owner) |owner| return owner.share();

    switch (source) {
        .list => |ls| {
            for (ls.items) |item| _ = item.share();
        },
        .single => |ss| {
            if (!ss.consumed) _ = ss.value.share();
        },
        // The caller bit-copies `source` (including the owner words), so just
        // bump the owner's refcount to match the new flow's reference.
        .str => |ss| _ = ss.owner.share(),
        .range_finite, .range_infinite, .empty => {},
    }
    return null;
}

fn make_ops_ref_count() *types.OpsRefCount {
    const ref_count = gc.recycleAlloc(types.OpsRefCount);
    ref_count.* = .{ .value = std.atomic.Value(u32).init(1) };
    return ref_count;
}

fn retain_ops(flow: *types.FeartFlow) void {
    if (flow.ops_ref_count) |ref_count| {
        _ = ref_count.value.fetchAdd(1, .monotonic);
    }
}

fn release_ops(flow: *types.FeartFlow, releasing_worker_id: u32) void {
    const ref_count = flow.ops_ref_count orelse return;
    const old_count = ref_count.value.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;
    _ = ref_count.value.load(.acquire);

    for (flow.ops) |op| release_op(op, releasing_worker_id);
    gc.recycleDestroySlice(types.OpDesc, flow.ops, .flow_op_release);
    gc.recycleDestroy(types.OpsRefCount, ref_count, .flow_op_release);
}

fn release_op(op: types.OpDesc, releasing_worker_id: u32) void {
    switch (op.kind) {
        .scan => {
            const cell: *types.ScanCell = @ptrFromInt(op.state);
            cell.acc.rc_decrement_as(releasing_worker_id);
            gc.recycleDestroy(types.ScanCell, cell, .flow_op_release);
            op.closure.rc_decrement_as(releasing_worker_id);
        },
        .actor => {
            const state: *types.ActorState = @ptrFromInt(op.state);
            state.state_fp.rc_decrement_as(releasing_worker_id);
            state.callback.rc_decrement_as(releasing_worker_id);
            gc.recycleDestroy(types.ActorState, state, .flow_op_release);
        },
        .map_ctx, .peek_ctx => {
            const cell: *types.CtxCell = @ptrFromInt(op.state);
            cell.ctx.rc_decrement_as(releasing_worker_id);
            gc.recycleDestroy(types.CtxCell, cell, .flow_op_release);
            op.closure.rc_decrement_as(releasing_worker_id);
        },
        .limit => {},
        .map, .filter, .peek, .map_filter, .flat_map => op.closure.rc_decrement_as(releasing_worker_id),
    }
}

fn clone_op(op: types.OpDesc) types.OpDesc {
    var copy = op;
    switch (op.kind) {
        .scan => {
            const old_cell: *types.ScanCell = @ptrFromInt(op.state);
            const new_cell = gc.recycleAlloc(types.ScanCell);
            new_cell.* = .{ .acc = old_cell.acc.share() };
            copy.state = @intFromPtr(new_cell);
            copy.closure = op.closure.share();
        },
        .actor => {
            const old_state: *types.ActorState = @ptrFromInt(op.state);
            const new_state = gc.recycleAlloc(types.ActorState);
            new_state.* = .{
                .state_fp = old_state.state_fp.share(),
                .callback = old_state.callback.share(),
            };
            copy.state = @intFromPtr(new_state);
        },
        .map_ctx, .peek_ctx => {
            const old_cell: *types.CtxCell = @ptrFromInt(op.state);
            const new_cell = gc.recycleAlloc(types.CtxCell);
            new_cell.* = .{ .ctx = old_cell.ctx.share() };
            copy.state = @intFromPtr(new_cell);
            copy.closure = op.closure.share();
        },
        .limit => {},
        .map, .filter, .peek, .map_filter, .flat_map => copy.closure = op.closure.share(),
    }
    return copy;
}

fn clone_ops(ops: []types.OpDesc) struct { ops: []types.OpDesc, ref_count: ?*types.OpsRefCount } {
    if (ops.len == 0) return .{ .ops = &.{}, .ref_count = null };

    const cloned = gc.recycleAllocSlice(types.OpDesc, ops.len);
    for (ops, 0..) |op, i| cloned[i] = clone_op(op);
    return .{ .ops = cloned, .ref_count = make_ops_ref_count() };
}

// Divide-and-conquer source split for the parallel driver.
//
// Returns two flows whose elements, iterated in order, reproduce the original
// flow's element sequence exactly. Both halves share the original `ops` slice
// by reference (safe because ops arrays are immutable post-`clone_with_op`).
//
// Returns null when splitting would violate semantics:
//   - any op in the chain is stateful (scan/limit/actor); splits would
//     either duplicate or reorder side-effects;
//   - the remaining element count is below 2 (nothing to split);
//   - the source isn't index-addressable (single/empty/range_infinite).
// Mirrors the Java reference impl in assets/rt/flows/Range.java which
// also splits both list- and range-backed flows.
pub fn split_flow(flow: *types.FeartFlow) ?struct { left: *types.FeartFlow, right: *types.FeartFlow } {
    for (flow.ops) |op| {
        if (!op.flags.stateless) return null;
    }
    switch (flow.source) {
        .list => |ls| {
            const remaining = ls.items.len - ls.index;
            if (remaining < 2) return null;
            const mid = ls.index + remaining / 2;
            const left_items = ls.items[ls.index..mid];
            const right_items = ls.items[mid..];
            const left = gc.recycleAlloc(types.FeartFlow);
            const right = gc.recycleAlloc(types.FeartFlow);
            const left_source: types.Source = .{ .list = .{ .items = left_items, .index = 0 } };
            const right_source: types.Source = .{ .list = .{ .items = right_items, .index = 0 } };
            retain_ops(flow);
            retain_ops(flow);
            left.* = .{
                .source = left_source,
                .source_owner = retain_source(left_source, flow.source_owner),
                .ops = flow.ops,
                .ops_ref_count = flow.ops_ref_count,
                .is_finite = true,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            right.* = .{
                .source = right_source,
                .source_owner = retain_source(right_source, flow.source_owner),
                .ops = flow.ops,
                .ops_ref_count = flow.ops_ref_count,
                .is_finite = true,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            return .{ .left = left, .right = right };
        },
        .range_finite => |rs| {
            // Element count: ceil((end - current) / abs(step)). Same formula as
            // flow_size's range arm. Negative step ranges (current > end) work
            // because we take the absolute step for the count and reuse the
            // signed step to compute the midpoint coordinate.
            const diff = if (rs.step > 0) rs.end - rs.current else rs.current - rs.end;
            if (diff <= 0) return null;
            const abs_step: i64 = if (rs.step > 0) rs.step else -rs.step;
            const count: i64 = @divTrunc(diff + abs_step - 1, abs_step);
            if (count < 2) return null;
            // mid lands on an element boundary even for step != ±1 because we
            // multiply by a whole element index (count/2) before adding to
            // current. Half-open semantics: left = [current, mid), right = [mid, end).
            const mid = rs.current + @divTrunc(count, 2) * rs.step;
            const left = gc.recycleAlloc(types.FeartFlow);
            const right = gc.recycleAlloc(types.FeartFlow);
            retain_ops(flow);
            retain_ops(flow);
            left.* = .{
                .source = .{ .range_finite = .{ .current = rs.current, .end = mid, .step = rs.step } },
                .source_owner = null,
                .ops = flow.ops,
                .ops_ref_count = flow.ops_ref_count,
                .is_finite = true,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            right.* = .{
                .source = .{ .range_finite = .{ .current = mid, .end = rs.end, .step = rs.step } },
                .source_owner = null,
                .ops = flow.ops,
                .ops_ref_count = flow.ops_ref_count,
                .is_finite = true,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            return .{ .left = left, .right = right };
        },
        .str => |ss| {
            // Split the live window `[index, bytes_len)` at the first unit
            // boundary at/after its byte midpoint. Codepoint boundaries are
            // found in pure Zig (skip continuation bytes); grapheme boundaries
            // via the one-shot native lookup. Each half is re-based to its own
            // buffer start with `index = 0` and takes its own reference to
            // `owner`. Half-open: left = [start, mid), right = [mid, end).
            const abs_start = ss.index;
            const abs_end = ss.bytes_len;
            if (abs_end - abs_start < 2) return null;
            const midpoint = abs_start + (abs_end - abs_start) / 2;
            const mid = switch (ss.mode) {
                .codepoint => blk: {
                    var m = midpoint;
                    while (m < abs_end and (ss.bytes_ptr[m] & 0xC0) == 0x80) m += 1;
                    break :blk m;
                },
                .grapheme => native.frt_grapheme_boundary_after(ss.bytes_ptr, abs_end, midpoint),
            };
            if (mid <= abs_start or mid >= abs_end) return null;
            const left = gc.recycleAlloc(types.FeartFlow);
            const right = gc.recycleAlloc(types.FeartFlow);
            retain_ops(flow);
            retain_ops(flow);
            left.* = .{
                .source = .{ .str = .{
                    .bytes_ptr = ss.bytes_ptr + abs_start,
                    .bytes_len = mid - abs_start,
                    .index = 0,
                    .mode = ss.mode,
                    .owner = ss.owner.share(),
                } },
                .source_owner = null,
                .ops = flow.ops,
                .ops_ref_count = flow.ops_ref_count,
                .is_finite = true,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            right.* = .{
                .source = .{ .str = .{
                    .bytes_ptr = ss.bytes_ptr + mid,
                    .bytes_len = abs_end - mid,
                    .index = 0,
                    .mode = ss.mode,
                    .owner = ss.owner.share(),
                } },
                .source_owner = null,
                .ops = flow.ops,
                .ops_ref_count = flow.ops_ref_count,
                .is_finite = true,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            return .{ .left = left, .right = right };
        },
        // single (count<=1) and empty have nothing to split. range_infinite
        // can't produce two finite halves; the Java InfiniteRangeOp also
        // returns empty from split$mut.
        else => return null,
    }
}

// Immutable-slice append: allocate a fresh ops slice, one longer than the
// existing, and return a new flow. Source is shared (flows are single-use).
pub fn clone_with_op(existing: *types.FeartFlow, new_op: types.OpDesc) *types.FeartFlow {
    const new_flow = gc.recycleAlloc(types.FeartFlow);
    const new_ops = gc.recycleAllocSlice(types.OpDesc, existing.ops.len + 1);
    for (existing.ops, 0..) |op, i| new_ops[i] = clone_op(op);
    new_ops[existing.ops.len] = new_op;
    new_flow.* = .{
        .source = existing.source,
        .source_owner = retain_source(existing.source, existing.source_owner),
        .ops = new_ops,
        .ops_ref_count = make_ops_ref_count(),
        .is_finite = existing.is_finite,
        .ref_count = std.atomic.Value(u32).init(1),
    };
    return new_flow;
}

pub fn clone_with_finiteness(existing: *types.FeartFlow, is_finite: bool) *types.FeartFlow {
    const new_flow = gc.recycleAlloc(types.FeartFlow);
    const ops_clone = clone_ops(existing.ops);
    new_flow.* = .{
        .source = existing.source,
        .source_owner = retain_source(existing.source, existing.source_owner),
        .ops = ops_clone.ops,
        .ops_ref_count = ops_clone.ref_count,
        .is_finite = is_finite,
        .ref_count = std.atomic.Value(u32).init(1),
    };
    return new_flow;
}

/// Takes the one reference `source_owner` holds, which `release_source` drops.
/// A `.flow` thunk therefore shares its receiver: self is lent to the thunk, not
/// given to it.
pub fn make_flow_from_list(comptime vt: *const objs.VTable, list_fp: FatPtr) FatPtr {
    const al = list_rt.deref_list(list_fp);
    const flow = create_flow(.{ .list = .{ .items = al.items, .index = 0 } }, true);
    flow.source_owner = list_fp;
    return make_flow_fp(vt, flow);
}

// Flow over a string's codepoints / graphemes. `owner` is the Str whose buffer
// `bytes` borrows; the StrSource takes the one reference (released on drop) and
// `source_owner` stays null -- string sources carry their owner in the source.
pub fn make_flow_from_str(comptime vt: *const objs.VTable, owner: FatPtr, bytes: []const u8, mode: string_flows.StrSourceMode) FatPtr {
    const flow = create_flow(.{ .str = .{
        .bytes_ptr = bytes.ptr,
        .bytes_len = bytes.len,
        .index = 0,
        .mode = mode,
        .owner = owner,
    } }, true);
    return make_flow_fp(vt, flow);
}

// Generic "flow over N items": used by both factory `#/N` and `ofIso/N`.
// Items are copied into a gc-owned ArrayList; the flow holds a view into it.
pub fn make_flow_from_items(comptime vt: *const objs.VTable, items: []const FatPtr) FatPtr {
    const al = gc.allocator.create(ArrayList) catch @panic("OOM");
    al.* = ArrayList.initCapacity(gc.allocator, items.len) catch @panic("OOM");
    al.appendSliceAssumeCapacity(items);
    return make_flow_fp(vt, create_flow(.{ .list = .{ .items = al.items, .index = 0 } }, true));
}

pub fn make_flow_from_range(comptime vt: *const objs.VTable, start: i64, end: i64, step: i64) FatPtr {
    return make_flow_fp(vt, create_flow(.{ .range_finite = .{ .current = start, .end = end, .step = step } }, true));
}

pub fn make_flow_from_infinite(comptime vt: *const objs.VTable, start: i64, step: i64) FatPtr {
    return make_flow_fp(vt, create_flow(.{ .range_infinite = .{ .current = start, .end = 0, .step = step } }, false));
}

pub fn make_flow_from_single(comptime vt: *const objs.VTable, value: FatPtr) FatPtr {
    return make_flow_fp(vt, create_flow(.{ .single = .{ .value = value, .consumed = false } }, true));
}

pub fn make_empty_flow(comptime vt: *const objs.VTable) FatPtr {
    return make_flow_fp(vt, create_flow(.empty, true));
}

const h = objs.hash_signature;

pub fn make_some(item: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Opts_0), h("imm #/1"), .{item}, @src());
}
pub fn make_none() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Opt_1);
}
pub fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}
