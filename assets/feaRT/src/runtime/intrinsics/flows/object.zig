//! Flow object layout and constructors.

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const list_rt = @import("../list.zig");
const list_storage = @import("../lists/storage.zig");
const native = @import("../../native.zig");
const scope_mod = @import("../../scope.zig");
const worker_mod = @import("../../worker.zig");
const root = @import("root");
const pb = root.pkg_base;

const types = @import("types.zig");
const ops_node = @import("ops_node.zig");
const string_flows = @import("string_flows.zig");
const exec = @import("exec.zig");
const FatPtr = objs.FatPtr;


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
        .ops_owner = null,
        .is_finite = is_finite,
        .parallelism = .sequential,
        .serial_from = null,
        .ref_count = std.atomic.Value(u32).init(1),
    };
    return f;
}

pub fn retain_flow(f: *types.FeartFlow) void {
    _ = f.ref_count.fetchAdd(1, .monotonic);
}

/// `releasing_worker_id` is the worker that started the drop chain.
pub fn release_flow(f: *types.FeartFlow, releasing_worker_id: u32) void {
    const old_count = f.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;
    _ = f.ref_count.load(.acquire);

    release_flow_body(f, releasing_worker_id);
    gc.recycleDestroy(types.FeartFlow, f, .flow_release);
}

/// Drops the references of the body, but not its storage. A body in a caller frame has no
/// allocation to free.
pub fn release_flow_body(f: *types.FeartFlow, releasing_worker_id: u32) void {
    release_source(f, releasing_worker_id);
    release_ops(f, releasing_worker_id);
}

pub fn flow_drop(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(FlowCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    release_flow(@ptrFromInt(self.captures.flow_ptr), releasing_worker_id);
}

/// Visits every reference of a flow, for the cycle collector. It must match `release_source`
/// and `release_ops` arm for arm. If the collector sees a reference fewer times than the drop
/// releases it, a live node reads as garbage.
pub fn flow_trace(header: *anyopaque, visit: objs.VisitFn, ctx: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(FlowCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    const f: *const types.FeartFlow = @ptrFromInt(self.captures.flow_ptr);

    visit_source(f, visit, ctx);
    if (f.ops_owner) |node| visit(ctx, ops_node.opsEdge(node));
}

fn visit_source(f: *const types.FeartFlow, visit: objs.VisitFn, ctx: *anyopaque) void {
    switch (f.source) {
        .str => |ss| return visit(ctx, ss.owner),
        else => {},
    }

    if (f.source_owner) |source_owner| return visit(ctx, source_owner);

    switch (f.source) {
        .list => |ls| for (ls.items) |item| visit(ctx, item),
        .single => |ss| if (!ss.consumed) visit(ctx, ss.value),
        .str => unreachable,
        .range_finite, .range_infinite, .empty => {},
    }
}

fn release_source(f: *types.FeartFlow, releasing_worker_id: u32) void {
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
        // A list source holds one reference to each item in its slice, also to the items
        // before `index`. Iteration shares items, and does not move them.
        .list => |ls| for (ls.items) |item| item.rc_decrement_as(releasing_worker_id),
        .single => |ss| if (!ss.consumed) ss.value.rc_decrement_as(releasing_worker_id),
        .str => unreachable,
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
        // The caller copies `source` bit for bit, with the owner word, so the owner is the
        // reference of the new flow.
        .str => |ss| _ = ss.owner.share(),
        .range_finite, .range_infinite, .empty => {},
    }
    return null;
}

fn retain_ops(flow: *const types.FeartFlow) void {
    if (flow.ops_owner) |node| _ = ops_node.opsEdge(node).share();
}

fn release_ops(flow: *types.FeartFlow, releasing_worker_id: u32) void {
    if (flow.ops_owner) |node| ops_node.opsEdge(node).rc_decrement_as(releasing_worker_id);
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
        .map, .filter, .peek, .map_filter, .flat_map, .chain => copy.closure = op.closure.share(),
    }
    return copy;
}

fn clone_ops(ops: []types.OpDesc) struct { ops: []types.OpDesc, owner: ?*ops_node.FlowOps } {
    if (ops.len == 0) return .{ .ops = &.{}, .owner = null };

    const cloned = gc.recycleAllocSlice(types.OpDesc, ops.len);
    for (ops, 0..) |op, i| cloned[i] = clone_op(op);
    return .{ .ops = cloned, .owner = ops_node.make_ops(cloned) };
}

fn concat_ops(a: []types.OpDesc, b: []types.OpDesc) struct { ops: []types.OpDesc, owner: ?*ops_node.FlowOps } {
    const n = a.len + b.len;
    if (n == 0) return .{ .ops = &.{}, .owner = null };

    const cloned = gc.recycleAllocSlice(types.OpDesc, n);
    for (a, 0..) |op, i| cloned[i] = clone_op(op);
    for (b, 0..) |op, i| cloned[a.len + i] = clone_op(op);
    return .{ .ops = cloned, .owner = ops_node.make_ops(cloned) };
}

/// The smallest chunk that a split leaves. A split costs two retains of the op chain, and a
/// non-associative fold collects each right half into a list. A split to single elements pays
/// this per element, and a promotion token then stands for one element, not for a chunk of
/// work as the APM bound assumes.
pub const SPLIT_MIN = 2;

fn source_has_one(s: *const types.Source) bool {
    return switch (s.*) {
        .list => |ls| ls.items.len - ls.index == 1,
        .range_finite => |rs| blk: {
            const diff = if (rs.step > 0) rs.end - rs.current else rs.current - rs.end;
            if (diff <= 0) break :blk false;
            const abs_step: i64 = if (rs.step > 0) rs.step else -rs.step;
            break :blk @divTrunc(diff + abs_step - 1, abs_step) == 1;
        },
        .single => |ss| !ss.consumed,
        else => false,
    };
}

/// When `flow` has one element left and a `flat_map` or `chain` op, replaces `flow` with the
/// inner flow of that element, followed by the ops after the op. The elements and their order
/// do not change. The result takes `parallelism` and `serial_from` from the inner flow, so the
/// call site of the inner flow decides if it splits. The caller must make sure that `flow` may
/// split, because this runs user code.
///
/// When a filter before the op drops the element, the function returns false, but the source
/// of `flow` is then empty.
fn reroot_single(flow: *types.FeartFlow) bool {
    const k = for (flow.ops, 0..) |op, i| {
        if (op.kind == .flat_map or op.kind == .chain) break i;
    } else return false;
    if (!source_has_one(&flow.source)) return false;
    if (scope_mod.currentCancelled()) return false;

    // Pull before user code runs, so the body stays consistent if user code raises an error.
    const elem = types.source_next(&flow.source);
    const kept = exec.apply_prefix(flow.ops[0..k], elem) orelse return false;

    const op = flow.ops[k];
    const inner_fp = switch (op.kind) {
        .flat_map => blk: {
            const fp = objs.call(op.closure, h("read #/1"), .{kept}, @src());
            kept.rc_decrement();
            break :blk fp;
        },
        .chain => blk: {
            const list_fp = objs.call(op.closure, h("read #/1"), .{kept}, @src());
            kept.rc_decrement();
            const fp = objs.call(list_fp, h("mut .flow/0"), .{}, @src());
            list_fp.rc_decrement();
            // The outer flow may split, so this chain has `read` or `imm` elements. Nothing can
            // mutate them, so one element at many positions is safe on many workers.
            deref_flow(fp).parallelism = .data_parallel;
            break :blk fp;
        },
        else => unreachable,
    };

    const inner = deref_flow(inner_fp);
    const new_ops = concat_ops(inner.ops, flow.ops[k + 1 ..]);
    const new_owner = retain_source(inner.source, inner.source_owner);

    release_flow_body(flow, worker_mod.currentWorkerId());
    flow.source = inner.source;
    flow.source_owner = new_owner;
    flow.ops = new_ops.ops;
    flow.ops_owner = new_ops.owner;
    flow.is_finite = inner.is_finite;
    flow.parallelism = inner.parallelism;
    // The inner ops come first in the new chain, so this index stays valid.
    flow.serial_from = inner.serial_from;

    inner_fp.rc_decrement();
    return true;
}

/// Divides `flow` in two, into `left` and `right`. Returns false and writes neither output
/// when a split is not possible. The caller owns the storage of both halves.
///
/// Before the split, `reroot_single` can replace `flow` in place. This runs user code, also
/// when the function returns false.
pub fn split_flow_into(flow: *types.FeartFlow, left: *types.FeartFlow, right: *types.FeartFlow) bool {
    while (true) {
        if (flow.parallelism != .data_parallel or flow.serial_from != null) return false;
        for (flow.ops) |op| {
            if (!op.flags.stateless) return false;
        }
        if (!reroot_single(flow)) break;
    }
    switch (flow.source) {
        .list => |ls| {
            const remaining = ls.items.len - ls.index;
            if (remaining < SPLIT_MIN) return false;
            const mid = ls.index + remaining / 2;
            const left_source: types.Source = .{ .list = .{ .items = ls.items[ls.index..mid], .index = 0 } };
            const right_source: types.Source = .{ .list = .{ .items = ls.items[mid..], .index = 0 } };
            retain_ops(flow);
            retain_ops(flow);
            left.* = .{
                .source = left_source,
                .source_owner = retain_source(left_source, flow.source_owner),
                .ops = flow.ops,
                .ops_owner = flow.ops_owner,
                .is_finite = true,
                .parallelism = flow.parallelism,
                .serial_from = flow.serial_from,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            right.* = .{
                .source = right_source,
                .source_owner = retain_source(right_source, flow.source_owner),
                .ops = flow.ops,
                .ops_owner = flow.ops_owner,
                .is_finite = true,
                .parallelism = flow.parallelism,
                .serial_from = flow.serial_from,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            return true;
        },
        .range_finite => |rs| {
            const diff = if (rs.step > 0) rs.end - rs.current else rs.current - rs.end;
            if (diff <= 0) return false;
            const abs_step: i64 = if (rs.step > 0) rs.step else -rs.step;
            const count: i64 = @divTrunc(diff + abs_step - 1, abs_step);
            if (count < SPLIT_MIN) return false;
            const mid = rs.current + @divTrunc(count, 2) * rs.step;
            retain_ops(flow);
            retain_ops(flow);
            left.* = .{
                .source = .{ .range_finite = .{ .current = rs.current, .end = mid, .step = rs.step } },
                .source_owner = null,
                .ops = flow.ops,
                .ops_owner = flow.ops_owner,
                .is_finite = true,
                .parallelism = flow.parallelism,
                .serial_from = flow.serial_from,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            right.* = .{
                .source = .{ .range_finite = .{ .current = mid, .end = rs.end, .step = rs.step } },
                .source_owner = null,
                .ops = flow.ops,
                .ops_owner = flow.ops_owner,
                .is_finite = true,
                .parallelism = flow.parallelism,
                .serial_from = flow.serial_from,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            return true;
        },
        .str => |ss| {
            // Split at the first codepoint or grapheme boundary at or after the byte midpoint.
            const abs_start = ss.index;
            const abs_end = ss.bytes_len;
            if (abs_end - abs_start < SPLIT_MIN) return false;
            const midpoint = abs_start + (abs_end - abs_start) / 2;
            const mid = switch (ss.mode) {
                .codepoint => blk: {
                    var m = midpoint;
                    while (m < abs_end and (ss.bytes_ptr[m] & 0xC0) == 0x80) m += 1;
                    break :blk m;
                },
                .grapheme => native.frt_grapheme_boundary_after(ss.bytes_ptr, abs_end, midpoint),
            };
            if (mid <= abs_start or mid >= abs_end) return false;
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
                .ops_owner = flow.ops_owner,
                .is_finite = true,
                .parallelism = flow.parallelism,
                .serial_from = flow.serial_from,
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
                .ops_owner = flow.ops_owner,
                .is_finite = true,
                .parallelism = flow.parallelism,
                .serial_from = flow.serial_from,
                .ref_count = std.atomic.Value(u32).init(1),
            };
            return true;
        },
        else => return false,
    }
}

/// Copies `src` onto the heap with new references, and leaves `src` whole. This is the
/// `box_fn` of a flow in a caller frame.
pub fn copy_flow_body(src: *const types.FeartFlow) *types.FeartFlow {
    const f = gc.recycleAlloc(types.FeartFlow);
    retain_ops(src);
    f.* = .{
        .source = src.source,
        .source_owner = retain_source(src.source, src.source_owner),
        .ops = src.ops,
        .ops_owner = src.ops_owner,
        .is_finite = src.is_finite,
        .parallelism = src.parallelism,
        .serial_from = src.serial_from,
        .ref_count = std.atomic.Value(u32).init(1),
    };
    return f;
}

/// The new flow shares the source, because a flow is used only once.
pub fn clone_with_op(existing: *types.FeartFlow, new_op: types.OpDesc) *types.FeartFlow {
    const new_flow = gc.recycleAlloc(types.FeartFlow);
    const new_ops = gc.recycleAllocSlice(types.OpDesc, existing.ops.len + 1);
    for (existing.ops, 0..) |op, i| new_ops[i] = clone_op(op);
    new_ops[existing.ops.len] = new_op;
    new_flow.* = .{
        .source = existing.source,
        .source_owner = retain_source(existing.source, existing.source_owner),
        .ops = new_ops,
        .ops_owner = ops_node.make_ops(new_ops),
        .is_finite = existing.is_finite,
        .parallelism = existing.parallelism,
        .serial_from = existing.serial_from,
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
        .ops_owner = ops_clone.owner,
        .is_finite = is_finite,
        .parallelism = existing.parallelism,
        .serial_from = existing.serial_from,
        .ref_count = std.atomic.Value(u32).init(1),
    };
    return new_flow;
}

/// Takes `list_fp` as the reference of `source_owner`. Thus a `.flow` thunk must share its
/// receiver, because the receiver is lent to it.
pub fn make_flow_from_list(comptime vt: *const objs.VTable, list_fp: FatPtr) FatPtr {
    const al = list_rt.deref_list(list_fp);
    const flow = create_flow(.{ .list = .{ .items = al.items, .index = 0 } }, true);
    flow.source_owner = list_fp;
    return make_flow_fp(vt, flow);
}

/// `bytes` borrows the buffer of `owner`. The source takes the reference to `owner`, and
/// `source_owner` stays null.
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

/// A flow over a copy of `items`, in a `ListStorage` that is the source owner. Splits share
/// that owner, so the buffer has one owner for all halves. The items are lent, so each is
/// shared and boxed out of the caller frame.
pub fn make_flow_from_items(comptime vt: *const objs.VTable, items: []const FatPtr) FatPtr {
    const storage = list_storage.make_storage(items.len);
    for (items) |item| storage.al.appendAssumeCapacity(item.share().box_transient());
    const flow = create_flow(.{ .list = .{ .items = storage.al.items, .index = 0 } }, true);
    flow.source_owner = list_storage.storageEdge(storage);
    return make_flow_fp(vt, flow);
}

pub fn make_flow_from_range(comptime vt: *const objs.VTable, start: i64, end: i64, step: i64) FatPtr {
    return make_flow_fp(vt, create_flow(.{ .range_finite = .{ .current = start, .end = end, .step = step } }, true));
}

pub fn make_flow_from_infinite(comptime vt: *const objs.VTable, start: i64, step: i64) FatPtr {
    return make_flow_fp(vt, create_flow(.{ .range_infinite = .{ .current = start, .end = 0, .step = step } }, false));
}

/// As `make_flow_from_items`, for one value.
pub fn make_flow_from_single(comptime vt: *const objs.VTable, value: FatPtr) FatPtr {
    const kept = value.share().box_transient();
    return make_flow_fp(vt, create_flow(.{ .single = .{ .value = kept, .consumed = false } }, true));
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
