//! VT_Flow: the vtable of each Zig-resident flow instance.
//!
//! An intermediate op (`map`, `filter`, ...) appends one `OpDesc` and returns a new flow.
//! A terminal op (`fold`, `find`, ...) dispatches into `terminals.zig`.
//! `only`, `get`, `opt`, `let`, `join` and `#/1` keep their default Fearless bodies. They
//! stay here as delegation thunks.

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const cycles = @import("../../cycles.zig");
const scope_mod = @import("../../scope.zig");
const nat_rt = @import("../nat.zig");
const list_rt = @import("../list.zig");
const root = @import("root");
const pb = root.pkg_base;
const pbf = root.pkg_base_flows;

const types = @import("types.zig");
const object = @import("object.zig");
const terminals = @import("terminals.zig");
const worker_mod = @import("../../worker.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

// Each terminal pushes a new cancellation scope at entry, chained to the outer scope on the same
// fiber. Thus all code below it finds a valid scope. The Scope stays on the GC heap, because a
// thief task can capture it into StolenTask.scope and outlive the terminal.
fn pushScope() struct { current: *scope_mod.Scope, prev: ?*scope_mod.Scope } {
    const prev = scope_mod.activeScope();
    const s = gc.allocator.create(scope_mod.Scope) catch @panic("OOM");
    s.* = scope_mod.Scope.init(prev);
    scope_mod.setActiveScope(s);
    return .{ .current = s, .prev = prev };
}
fn popScope(prev: ?*scope_mod.Scope) void {
    scope_mod.setActiveScope(prev);
    // Do not free the Scope. The GC reclaims it when no reference remains.
}

fn make_scan_cell(initial: FatPtr) u64 {
    const cell = gc.recycleAlloc(types.ScanCell);
    cell.* = .{ .acc = initial };
    return @intFromPtr(cell);
}

fn make_actor_cell(initial_state: FatPtr, callback: FatPtr) u64 {
    const state = gc.recycleAlloc(types.ActorState);
    state.* = .{ .state_fp = initial_state, .callback = callback };
    return @intFromPtr(state);
}

fn make_ctx_cell(ctx: FatPtr) u64 {
    const cell = gc.recycleAlloc(types.CtxCell);
    cell.* = .{ .ctx = ctx };
    return @intFromPtr(cell);
}

fn flow_map(self: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .map,
        .closure = f,
        .state = 0,
        .flags = .{},
    }));
}
fn flow_map2(self: FatPtr, ctx: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .map_ctx,
        .closure = f,
        .state = make_ctx_cell(ctx),
        .flags = .{ .stateless = false },
    }));
}
fn flow_filter(self: FatPtr, pred: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .filter,
        .closure = pred,
        .state = 0,
        .flags = .{},
    }));
}
fn flow_flat_map(self: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .flat_map,
        .closure = f,
        .state = 0,
        .flags = .{},
    }));
}
fn flow_peek(self: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .peek,
        .closure = f,
        .state = 0,
        .flags = .{},
    }));
}
fn flow_peek2(self: FatPtr, ctx: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .peek_ctx,
        .closure = f,
        .state = make_ctx_cell(ctx),
        .flags = .{ .stateless = false },
    }));
}
fn flow_map_filter(self: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .map_filter,
        .closure = f,
        .state = 0,
        .flags = .{},
    }));
}
fn flow_scan(self: FatPtr, initial: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .scan,
        .closure = f,
        .state = make_scan_cell(initial),
        .flags = .{ .stateless = false },
    }));
}
fn flow_limit(self: FatPtr, n: FatPtr) callconv(.c) FatPtr {
    const new_flow = object.clone_with_op(object.deref_flow(self), .{
        .kind = .limit,
        .closure = undefined,
        .state = nat_rt.deref(n),
        .flags = .{ .stateless = false, .short_circuiting = true },
    });
    new_flow.is_finite = true;
    return object.make_flow_fp(&VT_Flow, new_flow);
}
fn flow_actor(self: FatPtr, state_fp: FatPtr, callback: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_op(object.deref_flow(self), .{
        .kind = .actor,
        .closure = undefined,
        .state = make_actor_cell(state_fp, callback),
        .flags = .{ .stateless = false },
    }));
}
fn flow_actor_mut(self: FatPtr, state_fp: FatPtr, callback: FatPtr) callconv(.c) FatPtr {
    return flow_actor(self, state_fp, callback);
}
fn flow_assume_finite(self: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.clone_with_finiteness(object.deref_flow(self), true));
}

// The terminals below go through the Fearless `_FeartDriver` wrappers, so that APM and VPF find
// a Fearless body at the top of the call chain. VPF tags the `.merge` in those bodies, and the
// `.mergeFold` in `.driveReduceFn`, as VPFParallelisable. The split of the driver is the only
// source of flow data parallelism. Thus a terminal that skips it also makes all upstream ops
// sequential. Only `forEffect` calls `terminals.drive_*` directly, by design: it runs user side
// effects through a captured mutable reference, so it can never be parallel.

fn flow_fold(self: FatPtr, initial: FatPtr, combine: FatPtr) callconv(.c) FatPtr {
    const sc = pushScope();
    defer popScope(sc.prev);
    defer initial.rc_decrement();
    const seed = objs.call(initial, h("mut #/0"), .{}, @src());
    return pbf._FeartDriver_0__ZdotdriveReduce_3_mut_Zfun(self.share(), seed, combine, driver_singleton());
}
fn flow_first(self: FatPtr) callconv(.c) FatPtr {
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveFirst_1_mut_Zfun(self.share(), driver_singleton());
}
fn flow_last(self: FatPtr) callconv(.c) FatPtr {
    if (!object.deref_flow(self).is_finite) @panic("Terminal on infinite flow");
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveLast_1_mut_Zfun(self.share(), driver_singleton());
}
fn flow_count(self: FatPtr) callconv(.c) FatPtr {
    if (!object.deref_flow(self).is_finite) @panic("Terminal on infinite flow");
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveCount_1_mut_Zfun(self.share(), driver_singleton());
}
fn flow_list(self: FatPtr) callconv(.c) FatPtr {
    if (!object.deref_flow(self).is_finite) @panic("Terminal on infinite flow");
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveCollect_1_mut_Zfun(self.share(), driver_singleton());
}
fn flow_for(self: FatPtr, callback: FatPtr) callconv(.c) FatPtr {
    if (!object.deref_flow(self).is_finite) @panic("Terminal on infinite flow");
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveFor_2_mut_Zfun(self.share(), callback, driver_singleton());
}
fn flow_for_effect(self: FatPtr, callback: FatPtr) callconv(.c) FatPtr {
    const driver_obj = driver_singleton();
    return objs.call(driver_obj, h("mut .runChunkFor/2"), .{ self.share(), callback }, @src());
}
// The predicated terminals delegate to the `_TerminalOps[E]` defaults. Those dispatch
// `.findMap` and `.unorderedFindMap` back through this same VT_Flow, so the parallel and cancel
// work occurs in the resolved findMap impl. The scope push and pop stay here, to keep the full
// terminal under one cancel scope. If not, the `scope.request()` of a stolen thief in the
// findMap call goes to the scope of the caller, not to the scope of this terminal.
fn flow_any(self: FatPtr, pred: FatPtr) callconv(.c) FatPtr {
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._TerminalOps_1__Zdotany_1_mut_Zfun(pred, self);
}
fn flow_all(self: FatPtr, pred: FatPtr) callconv(.c) FatPtr {
    if (!object.deref_flow(self).is_finite) @panic("Terminal on infinite flow");
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._TerminalOps_1__Zdotall_1_mut_Zfun(pred, self);
}
fn flow_none(self: FatPtr, pred: FatPtr) callconv(.c) FatPtr {
    if (!object.deref_flow(self).is_finite) @panic("Terminal on infinite flow");
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._TerminalOps_1__Zdotnone_1_mut_Zfun(pred, self);
}
fn flow_find(self: FatPtr, pred: FatPtr) callconv(.c) FatPtr {
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._TerminalOps_1__Zdotfind_1_mut_Zfun(pred, self);
}
fn flow_first_pred(self: FatPtr, pred: FatPtr) callconv(.c) FatPtr {
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._TerminalOps_1__Zdotfirst_1_mut_Zfun(pred, self);
}
fn flow_find_map(self: FatPtr, mapper: FatPtr) callconv(.c) FatPtr {
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveFindMap_2_mut_Zfun(self.share(), mapper, driver_singleton());
}
fn flow_unordered_find_map(self: FatPtr, mapper: FatPtr) callconv(.c) FatPtr {
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveUnorderedFindMap_2_mut_Zfun(self.share(), mapper, driver_singleton());
}
fn flow_max(self: FatPtr, comparator: FatPtr) callconv(.c) FatPtr {
    if (!object.deref_flow(self).is_finite) @panic("Terminal on infinite flow");
    const sc = pushScope();
    defer popScope(sc.prev);
    return pbf._FeartDriver_0__ZdotdriveMax_2_mut_Zfun(self.share(), comparator, driver_singleton());
}

fn flow_self(self: FatPtr) callconv(.c) FatPtr {
    return self.share();
}

fn flow_size(self: FatPtr) callconv(.c) FatPtr {
    const flow = object.deref_flow(self);
    if (!flow.is_finite) return object.make_none();
    if (flow.ops.len != 0) return object.make_none();

    // Only the O(1) sources give a size here. `.count/0` gives the size at any cost.
    return switch (flow.source) {
        .list => |s| object.make_some(nat_rt.make(s.items.len - s.index)),
        .range_finite => |s| blk: {
            const diff = if (s.step > 0) s.end - s.current else s.current - s.end;
            const abs_step = if (s.step > 0) s.step else -s.step;
            const count: u64 = if (diff <= 0) 0 else @intCast(@divTrunc(diff + abs_step - 1, abs_step));
            break :blk object.make_some(nat_rt.make(count));
        },
        .single => |s| object.make_some(nat_rt.make(if (s.consumed) 0 else 1)),
        .empty => object.make_some(nat_rt.make(0)),
        .str => object.make_none(),
        .range_infinite => object.make_none(),
    };
}

fn T_flow_only(self: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_1__Zdotonly_0_mut_Zfun(self);
}
fn T_flow_get(self: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_1__Zdotget_0_mut_Zfun(self);
}
fn T_flow_opt(self: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_1__Zdotopt_0_mut_Zfun(self);
}
fn T_flow_let(self: FatPtr, a: FatPtr, b: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_1__Zdotlet_2_mut_Zfun(a, b, self);
}
fn T_flow_join(self: FatPtr, joinable: FatPtr) callconv(.c) FatPtr {
    defer joinable.rc_decrement();
    return objs.call(joinable, h("imm .join/1"), .{self.share()}, @src());
}
/// `Extensible[Flow[E]]#(ext)` at each receiver mdf. The Fearless body is `ext#(this.self)`, and
/// `.self` on a flow is the identity. Thus all three mdfs pass the receiver to `mut #/1`.
fn T_flow_hash1(self: FatPtr, ext: FatPtr) callconv(.c) FatPtr {
    defer ext.rc_decrement();
    return objs.call(ext, h("mut #/1"), .{self.share()}, @src());
}

/// `.unwrapOp` gives the `FlowOp` of a flow to the Fearless flow operators in base. A
/// Zig-resident flow replaces those paths with its native `OpDesc` pipeline, and a caller also
/// needs a `_UnwrapFlowToken`, which is private to `base.flows`. This slot keeps the vtable
/// complete and fails with a clear message, not with a missing-method dispatch abort.
fn T_flow_unwrap_op(self: FatPtr, unwrap: FatPtr) callconv(.c) FatPtr {
    _ = .{ self, unwrap };
    @panic("unwrapOp is not supported on FeaRT-native flows");
}

/// FatPtr of the `_FeartDriver` singleton. It stays in this module, so that the flow instance
/// vtable and the driver vtable can refer to each other at comptime.
pub fn driver_singleton() FatPtr {
    return objs.obj_k_singleton(&VT_FeartDriver);
}

fn driver_run_chunk_reduce(self: FatPtr, flow: FatPtr, initial: FatPtr, combine: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    defer combine.rc_decrement();
    return terminals.drive_fold(object.deref_flow(flow), initial, combine);
}

fn driver_run_chunk_collect(self: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    return terminals.drive_list(object.deref_flow(flow));
}

fn driver_run_chunk_find_map(self: FatPtr, flow: FatPtr, mapper: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    defer mapper.rc_decrement();
    return terminals.drive_find_map(object.deref_flow(flow), mapper);
}

fn driver_run_chunk_for(self: FatPtr, flow: FatPtr, callback: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    defer callback.rc_decrement();
    return terminals.drive_for(object.deref_flow(flow), callback);
}

fn driver_run_chunk_first(self: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    return terminals.drive_first(object.deref_flow(flow));
}

fn driver_run_chunk_count(self: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    return terminals.drive_count(object.deref_flow(flow));
}

fn driver_run_chunk_last(self: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    return terminals.drive_last(object.deref_flow(flow));
}

fn driver_run_chunk_unordered_find_map(self: FatPtr, flow: FatPtr, mapper: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    defer mapper.rc_decrement();
    return terminals.drive_unordered_find_map(object.deref_flow(flow), mapper);
}

fn driver_drive_reduce(this: FatPtr, flow: FatPtr, initial: FatPtr, combine: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveReduce_3_mut_Zfun(flow, initial, combine, this);
}
fn driver_drive_reduce_fn(this: FatPtr, flow: FatPtr, initial: FatPtr, combine: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveReduceFn_3_mut_Zfun(flow, initial, combine, this);
}
fn driver_drive_collect(this: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveCollect_1_mut_Zfun(flow, this);
}
fn driver_drive_find_map(this: FatPtr, flow: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveFindMap_2_mut_Zfun(flow, f, this);
}
fn driver_drive_for(this: FatPtr, flow: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveFor_2_mut_Zfun(flow, f, this);
}
fn driver_drive_first(this: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveFirst_1_mut_Zfun(flow, this);
}
fn driver_drive_count(this: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveCount_1_mut_Zfun(flow, this);
}
fn driver_drive_last(this: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveLast_1_mut_Zfun(flow, this);
}
fn driver_drive_unordered_find_map(this: FatPtr, flow: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveUnorderedFindMap_2_mut_Zfun(flow, f, this);
}
fn driver_drive_max(this: FatPtr, flow: FatPtr, compare: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotdriveMax_2_mut_Zfun(flow, compare, this);
}
fn driver_run_chunk_max(this: FatPtr, flow: FatPtr, compare: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__ZdotrunChunkMax_2_mut_Zfun(flow, compare, this);
}
fn driver_merge(this: FatPtr, left: FatPtr, right: FatPtr) callconv(.c) FatPtr {
    return pbf._FeartDriver_0__Zdotmerge_2_imm_Zfun(left, right, this);
}
fn driver_merge3(_: FatPtr, left: FatPtr, right: FatPtr, combine: FatPtr) callconv(.c) FatPtr {
    defer combine.rc_decrement();
    return objs.call(combine, h("read #/2"), .{ left, right }, @src());
}

/// `_FeartDriver.mergeFold/3`: folds one collected chunk into `acc`, in index order. The list
/// keeps ownership of the chunk elements, so each element is shared into the call. `acc` moves
/// into the call, and the result replaces it. Thus only one accumulator reference stays live.
fn driver_merge_fold(_: FatPtr, acc: FatPtr, chunk: FatPtr, combine: FatPtr) callconv(.c) FatPtr {
    defer chunk.rc_decrement();
    defer combine.rc_decrement();
    var a = acc;
    const items = list_rt.deref_list(chunk);
    for (items.items) |elem| {
        a = objs.call(combine, h("read #/2"), .{ a, elem.share() }, @src());
    }
    return a;
}

const FlowSlot = objs.GenObjectLayoutType(object.FlowCaptures);

/// Both halves of a split live in this frame, which outlives every use of them.
///
/// The `.some/2` arms below drive the halves to completion before they return, so no
/// half survives this call. A VPF promotion inside an arm does not change that: the
/// promoted frame boxes each transient it captures, and the parent stays suspended at
/// its join, so the frame it reads stays mapped. Splitting is the whole cost of the
/// divide-and-conquer, so a frame slot in place of four heap objects is what makes the
/// leaves, not the splits, the price of a flow.
///
/// A transient holds no reference count, so the `rc_decrement` an arm does on a half is
/// a no-op and this frame owns the single release. That covers the `.shouldStop` arms,
/// which return without driving either half.
fn driver_split_match(self: FatPtr, flow: FatPtr, cases: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer flow.rc_decrement();
    defer cases.rc_decrement();

    var left_body: types.FeartFlow = undefined;
    var right_body: types.FeartFlow = undefined;
    if (!object.split_flow_into(object.deref_flow(flow), &left_body, &right_body)) {
        return objs.call(cases, h("mut .empty/0"), .{}, @src());
    }
    defer {
        const releasing_worker_id = worker_mod.currentWorkerId();
        object.release_flow_body(&left_body, releasing_worker_id);
        object.release_flow_body(&right_body, releasing_worker_id);
    }

    var left_slot: FlowSlot = undefined;
    var right_slot: FlowSlot = undefined;
    const left_fp = objs.init_transient_obj(object.FlowCaptures, &left_slot, &VT_FlowTransient, .{
        .flow_ptr = @intFromPtr(&left_body),
    });
    const right_fp = objs.init_transient_obj(object.FlowCaptures, &right_slot, &VT_FlowTransient, .{
        .flow_ptr = @intFromPtr(&right_body),
    });
    return objs.call(cases, h("mut .some/2"), .{ left_fp, right_fp }, @src());
}

/// Promotes a flow in a caller frame to the heap, and leaves the original whole. Called
/// when a value crosses a fiber boundary, so the copy must be able to outlive that frame.
fn box_flow(self_m: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_fp(&VT_Flow, object.copy_flow_body(object.deref_flow(self_m)));
}

/// Folds the right half of a split flow's result list into the left half.
fn list_concat_apply(_: FatPtr, l: FatPtr, r: FatPtr) callconv(.c) FatPtr {
    const l_storage = list_rt.deref_storage(l);
    const l_al = &l_storage.al;
    const r_items = list_rt.deref_list(r).items;
    const l_edge = list_rt.storageEdge(l_storage);
    for (r_items) |item| cycles.noteStore(l_edge, item);
    l_al.appendSlice(gc.allocator, r_items) catch @panic("OOM");
    for (r_items) |item| _ = item.share();
    r.rc_decrement();
    return l;
}

fn first_some_apply(_: FatPtr, l: FatPtr, r: FatPtr) callconv(.c) FatPtr {
    if (l.vt == &pb.VT_Opt_1) return r;
    r.rc_decrement();
    return l;
}

pub const VT_ListConcatReducer: objs.VTable = .{
    .type_name = "<runtime flow list-concat reducer>",
    .hashes = &.{ h("mut #/2"), h("read #/2"), h("imm #/2") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&list_concat_apply)),
        @as(*const anyopaque, @ptrCast(&list_concat_apply)),
        @as(*const anyopaque, @ptrCast(&list_concat_apply)),
    },
    .method_names = &.{ "mut #/2", "read #/2", "imm #/2" },
    .storage_mode = .singleton,
};

pub const VT_FirstSomeReducer: objs.VTable = .{
    .type_name = "<runtime flow first-some reducer>",
    .hashes = &.{ h("mut #/2"), h("read #/2"), h("imm #/2") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&first_some_apply)),
        @as(*const anyopaque, @ptrCast(&first_some_apply)),
        @as(*const anyopaque, @ptrCast(&first_some_apply)),
    },
    .method_names = &.{ "mut #/2", "read #/2", "imm #/2" },
    .storage_mode = .singleton,
};

fn driver_collect_reducer(_: FatPtr) callconv(.c) FatPtr {
    return objs.obj_k_singleton(&VT_ListConcatReducer);
}

fn driver_find_map_reducer(_: FatPtr) callconv(.c) FatPtr {
    return objs.obj_k_singleton(&VT_FirstSomeReducer);
}

fn driver_should_stop(_: FatPtr) callconv(.c) FatPtr {
    if (scope_mod.currentCancelled()) {
        return objs.obj_k_singleton(&pb.VT_True_0);
    }
    return objs.obj_k_singleton(&pb.VT_False_0);
}

pub const VT_FeartDriver: objs.VTable = .{
    .type_name = "base.flows._FeartDriver/0",
    .hashes = &.{
        h("mut .runChunkReduce/3"),
        h("mut .runChunkCollect/1"),
        h("mut .runChunkFindMap/2"),
        h("mut .runChunkFor/2"),
        h("mut .runChunkFirst/1"),
        h("mut .runChunkCount/1"),
        h("mut .runChunkLast/1"),
        h("mut .runChunkUnorderedFindMap/2"),
        h("mut .runChunkMax/2"),
        h("mut .driveReduce/3"),
        h("mut .driveReduceFn/3"),
        h("mut .driveCollect/1"),
        h("mut .driveFindMap/2"),
        h("mut .driveFor/2"),
        h("mut .driveFirst/1"),
        h("mut .driveCount/1"),
        h("mut .driveLast/1"),
        h("mut .driveUnorderedFindMap/2"),
        h("mut .driveMax/2"),
        h("imm .merge/2"),
        h("imm .merge/3"),
        h("imm .mergeFold/3"),
        h("imm .collectReducer/0"),
        h("imm .findMapReducer/0"),
        h("mut .shouldStop/0"),
        h("imm .splitMatch/2"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_reduce)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_collect)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_find_map)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_for)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_first)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_count)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_last)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_unordered_find_map)),
        @as(*const anyopaque, @ptrCast(&driver_run_chunk_max)),
        @as(*const anyopaque, @ptrCast(&driver_drive_reduce)),
        @as(*const anyopaque, @ptrCast(&driver_drive_reduce_fn)),
        @as(*const anyopaque, @ptrCast(&driver_drive_collect)),
        @as(*const anyopaque, @ptrCast(&driver_drive_find_map)),
        @as(*const anyopaque, @ptrCast(&driver_drive_for)),
        @as(*const anyopaque, @ptrCast(&driver_drive_first)),
        @as(*const anyopaque, @ptrCast(&driver_drive_count)),
        @as(*const anyopaque, @ptrCast(&driver_drive_last)),
        @as(*const anyopaque, @ptrCast(&driver_drive_unordered_find_map)),
        @as(*const anyopaque, @ptrCast(&driver_drive_max)),
        @as(*const anyopaque, @ptrCast(&driver_merge)),
        @as(*const anyopaque, @ptrCast(&driver_merge3)),
        @as(*const anyopaque, @ptrCast(&driver_merge_fold)),
        @as(*const anyopaque, @ptrCast(&driver_collect_reducer)),
        @as(*const anyopaque, @ptrCast(&driver_find_map_reducer)),
        @as(*const anyopaque, @ptrCast(&driver_should_stop)),
        @as(*const anyopaque, @ptrCast(&driver_split_match)),
    },
    .method_names = &.{
        "mut .runChunkReduce/3",
        "mut .runChunkCollect/1",
        "mut .runChunkFindMap/2",
        "mut .runChunkFor/2",
        "mut .runChunkFirst/1",
        "mut .runChunkCount/1",
        "mut .runChunkLast/1",
        "mut .runChunkUnorderedFindMap/2",
        "mut .runChunkMax/2",
        "mut .driveReduce/3",
        "mut .driveReduceFn/3",
        "mut .driveCollect/1",
        "mut .driveFindMap/2",
        "mut .driveFor/2",
        "mut .driveFirst/1",
        "mut .driveCount/1",
        "mut .driveLast/1",
        "mut .driveUnorderedFindMap/2",
        "mut .driveMax/2",
        "imm .merge/2",
        "imm .merge/3",
        "imm .mergeFold/3",
        "imm .collectReducer/0",
        "imm .findMapReducer/0",
        "mut .shouldStop/0",
        "imm .splitMatch/2",
    },
    .storage_mode = .singleton,
};

pub const VT_Flow: objs.VTable = .{
    .type_name = "base.flows.Flow/1",
    .hashes = &.{
        h("mut .map/1"),
        h("mut .map/2"),
        h("mut .filter/1"),
        h("mut .flatMap/1"),
        h("mut .limit/1"),
        h("mut .peek/1"),
        h("mut .peek/2"),
        h("mut .scan/2"),
        h("mut .mapFilter/1"),
        h("mut .actor/2"),
        h("mut .actorMut/2"),
        h("mut .assumeFinite/0"),
        h("mut .fold/2"),
        h("mut .first/0"),
        h("mut .last/0"),
        h("mut .count/0"),
        h("mut .list/0"),
        h("mut .for/1"),
        h("mut .forEffect/1"),
        h("mut .any/1"),
        h("mut .all/1"),
        h("mut .none/1"),
        h("mut .find/1"),
        h("mut .first/1"),
        h("mut .findMap/1"),
        h("mut .unorderedFindMap/1"),
        h("mut .max/1"),
        h("mut .self/0"),
        h("read .self/0"),
        h("imm .self/0"),
        h("read .size/0"),
        h("mut .only/0"),
        h("mut .get/0"),
        h("mut .opt/0"),
        h("mut .let/2"),
        h("mut .join/1"),
        h("mut #/1"),
        h("read #/1"),
        h("imm #/1"),
        h("mut .unwrapOp/1"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&flow_map)),
        @as(*const anyopaque, @ptrCast(&flow_map2)),
        @as(*const anyopaque, @ptrCast(&flow_filter)),
        @as(*const anyopaque, @ptrCast(&flow_flat_map)),
        @as(*const anyopaque, @ptrCast(&flow_limit)),
        @as(*const anyopaque, @ptrCast(&flow_peek)),
        @as(*const anyopaque, @ptrCast(&flow_peek2)),
        @as(*const anyopaque, @ptrCast(&flow_scan)),
        @as(*const anyopaque, @ptrCast(&flow_map_filter)),
        @as(*const anyopaque, @ptrCast(&flow_actor)),
        @as(*const anyopaque, @ptrCast(&flow_actor_mut)),
        @as(*const anyopaque, @ptrCast(&flow_assume_finite)),
        @as(*const anyopaque, @ptrCast(&flow_fold)),
        @as(*const anyopaque, @ptrCast(&flow_first)),
        @as(*const anyopaque, @ptrCast(&flow_last)),
        @as(*const anyopaque, @ptrCast(&flow_count)),
        @as(*const anyopaque, @ptrCast(&flow_list)),
        @as(*const anyopaque, @ptrCast(&flow_for)),
        @as(*const anyopaque, @ptrCast(&flow_for_effect)),
        @as(*const anyopaque, @ptrCast(&flow_any)),
        @as(*const anyopaque, @ptrCast(&flow_all)),
        @as(*const anyopaque, @ptrCast(&flow_none)),
        @as(*const anyopaque, @ptrCast(&flow_find)),
        @as(*const anyopaque, @ptrCast(&flow_first_pred)),
        @as(*const anyopaque, @ptrCast(&flow_find_map)),
        @as(*const anyopaque, @ptrCast(&flow_unordered_find_map)),
        @as(*const anyopaque, @ptrCast(&flow_max)),
        @as(*const anyopaque, @ptrCast(&flow_self)),
        @as(*const anyopaque, @ptrCast(&flow_self)),
        @as(*const anyopaque, @ptrCast(&flow_self)),
        @as(*const anyopaque, @ptrCast(&flow_size)),
        @as(*const anyopaque, @ptrCast(&T_flow_only)),
        @as(*const anyopaque, @ptrCast(&T_flow_get)),
        @as(*const anyopaque, @ptrCast(&T_flow_opt)),
        @as(*const anyopaque, @ptrCast(&T_flow_let)),
        @as(*const anyopaque, @ptrCast(&T_flow_join)),
        @as(*const anyopaque, @ptrCast(&T_flow_hash1)),
        @as(*const anyopaque, @ptrCast(&T_flow_hash1)),
        @as(*const anyopaque, @ptrCast(&T_flow_hash1)),
        @as(*const anyopaque, @ptrCast(&T_flow_unwrap_op)),
    },
    .method_names = &.{
        "mut .map/1",
        "mut .map/2",
        "mut .filter/1",
        "mut .flatMap/1",
        "mut .limit/1",
        "mut .peek/1",
        "mut .peek/2",
        "mut .scan/2",
        "mut .mapFilter/1",
        "mut .actor/2",
        "mut .actorMut/2",
        "mut .assumeFinite/0",
        "mut .fold/2",
        "mut .first/0",
        "mut .last/0",
        "mut .count/0",
        "mut .list/0",
        "mut .for/1",
        "mut .forEffect/1",
        "mut .any/1",
        "mut .all/1",
        "mut .none/1",
        "mut .find/1",
        "mut .first/1",
        "mut .findMap/1",
        "mut .unorderedFindMap/1",
        "mut .max/1",
        "mut .self/0",
        "read .self/0",
        "imm .self/0",
        "read .size/0",
        "mut .only/0",
        "mut .get/0",
        "mut .opt/0",
        "mut .let/2",
        "mut .join/1",
        "mut #/1",
        "read #/1",
        "imm #/1",
        "mut .unwrapOp/1",
    },
    .drop_fn = object.flow_drop,
    .trace_fn = object.flow_trace,
};

/// `VT_Flow` for a flow whose storage is a caller frame. It answers the same methods:
/// each intermediate op clones the body onto the heap, and each terminal reads it.
pub const VT_FlowTransient: objs.VTable = blk: {
    var vt = VT_Flow;
    vt.storage_mode = .transient;
    vt.drop_fn = null;
    vt.box_fn = &box_flow;
    break :blk vt;
};
