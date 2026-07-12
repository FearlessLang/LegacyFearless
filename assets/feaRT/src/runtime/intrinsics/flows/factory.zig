//! VT_FlowFactory -- the singleton trait Zig-backing `Flow#[E](...)` and friends.
//!
//! Factory patterns follow `list.zig`'s `list_factory_N` shape: each arity has
//! an explicit handler that constructs a list-backed flow directly, rather than
//! round-tripping through Fearless-generated code. `ofIso/N` uses the same
//! primitive since, at the FatPtr layer, iso and mut are indistinguishable --
//! ownership is enforced by the Fearless type system before we get here.

const std = @import("std");
const objs = @import("../../objs.zig");
const int_rt = @import("../int.zig");
const root = @import("root");
const pbf = root.pkg_base_flows;

const object = @import("object.zig");
const instance = @import("instance.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

const vt_flow = &instance.VT_Flow;

// ==========================================
// Factory singleton ctor -- returned by Flow# / Flow.ofIso etc.
// ==========================================

pub fn singleton() FatPtr {
    return objs.obj_k_singleton(&VT_FlowFactory);
}

// ==========================================
// # / 0..4 -- construct from inline args
// ==========================================

fn flow_factory_0(_: FatPtr) callconv(.c) FatPtr {
    return object.make_empty_flow(vt_flow);
}
fn flow_factory_1(_: FatPtr, a0: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_single(vt_flow, a0);
}
fn flow_factory_2(_: FatPtr, a0: FatPtr, a1: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1 });
}
fn flow_factory_3(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2 });
}
fn flow_factory_4(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3 });
}

// ==========================================
// range / 1..2
// ==========================================

fn flow_range_1(_: FatPtr, start: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_infinite(vt_flow, int_rt.deref(start), 1);
}
fn flow_range_2(_: FatPtr, start: FatPtr, end: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_range(vt_flow, int_rt.deref(start), int_rt.deref(end), 1);
}

// ==========================================
// ofIso / 1..16 -- direct Zig construction (no Fearless round-trip)
// ==========================================

fn flow_ofIso_1(_: FatPtr, a0: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_single(vt_flow, a0);
}
fn flow_ofIso_2(_: FatPtr, a0: FatPtr, a1: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1 });
}
fn flow_ofIso_3(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2 });
}
fn flow_ofIso_4(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3 });
}
fn flow_ofIso_5(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4 });
}
fn flow_ofIso_6(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5 });
}
fn flow_ofIso_7(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6 });
}
fn flow_ofIso_8(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7 });
}
fn flow_ofIso_9(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8 });
}
fn flow_ofIso_10(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9 });
}
fn flow_ofIso_11(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10 });
}
fn flow_ofIso_12(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11 });
}
fn flow_ofIso_13(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12 });
}
fn flow_ofIso_14(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13 });
}
fn flow_ofIso_15(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr, a14: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14 });
}
fn flow_ofIso_16(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr, a14: FatPtr, a15: FatPtr) callconv(.c) FatPtr {
    return object.make_flow_from_items(vt_flow, &.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15 });
}

// ==========================================
// Fearless-default delegations (fromOp / fromMutSource / sum / ofIsos / enumerate)
// ==========================================

fn flow_from_op_1(_: FatPtr, source: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_0__ZdotfromOp_1_imm_Zfun(source, singleton());
}
fn flow_from_op_2(_: FatPtr, source: FatPtr, size: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_0__ZdotfromOp_2_imm_Zfun(source, size, singleton());
}
fn flow_from_mut_source_1(_: FatPtr, source: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_0__ZdotfromMutSource_1_imm_Zfun(source, singleton());
}
fn flow_from_mut_source_2(_: FatPtr, source: FatPtr, size: FatPtr) callconv(.c) FatPtr {
    return pbf.Flow_0__ZdotfromMutSource_2_imm_Zfun(source, size, singleton());
}

fn T_flow_sum(self: FatPtr) callconv(.c) FatPtr { return pbf._FlowExtensions_0__Zdotsum_0_imm_Zfun(self); }
fn T_flow_usum(self: FatPtr) callconv(.c) FatPtr { return pbf._FlowExtensions_0__ZdotuSum_0_imm_Zfun(self); }
fn T_flow_fsum(self: FatPtr) callconv(.c) FatPtr { return pbf._FlowExtensions_0__ZdotfSum_0_imm_Zfun(self); }
fn T_flow_enumerate(self: FatPtr) callconv(.c) FatPtr { return pbf._FlowExtensions_0__Zdotenumerate_0_imm_Zfun(self); }
fn T_flow_of_isos(self: FatPtr, es: FatPtr) callconv(.c) FatPtr { return pbf.Flow_0__ZdotofIsos_1_imm_Zfun(es, self); }

// ==========================================
// VT_FlowFactory
// ==========================================

pub const VT_FlowFactory: objs.VTable = .{
    .type_name = "base.flows.Flow/0",
    .hashes = &.{
        h("imm #/0"), h("imm #/1"), h("imm #/2"), h("imm #/3"), h("imm #/4"),
        h("imm .range/1"), h("imm .range/2"),
        h("imm .fromOp/1"), h("imm .fromOp/2"),
        h("imm .fromMutSource/1"), h("imm .fromMutSource/2"),
        h("imm .sum/0"), h("imm .uSum/0"), h("imm .fSum/0"),
        h("imm .enumerate/0"),
        h("imm .ofIsos/1"),
        h("imm .ofIso/1"), h("imm .ofIso/2"), h("imm .ofIso/3"), h("imm .ofIso/4"),
        h("imm .ofIso/5"), h("imm .ofIso/6"), h("imm .ofIso/7"), h("imm .ofIso/8"),
        h("imm .ofIso/9"), h("imm .ofIso/10"), h("imm .ofIso/11"), h("imm .ofIso/12"),
        h("imm .ofIso/13"), h("imm .ofIso/14"), h("imm .ofIso/15"), h("imm .ofIso/16"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&flow_factory_0)),
        @as(*const anyopaque, @ptrCast(&flow_factory_1)),
        @as(*const anyopaque, @ptrCast(&flow_factory_2)),
        @as(*const anyopaque, @ptrCast(&flow_factory_3)),
        @as(*const anyopaque, @ptrCast(&flow_factory_4)),
        @as(*const anyopaque, @ptrCast(&flow_range_1)),
        @as(*const anyopaque, @ptrCast(&flow_range_2)),
        @as(*const anyopaque, @ptrCast(&flow_from_op_1)),
        @as(*const anyopaque, @ptrCast(&flow_from_op_2)),
        @as(*const anyopaque, @ptrCast(&flow_from_mut_source_1)),
        @as(*const anyopaque, @ptrCast(&flow_from_mut_source_2)),
        @as(*const anyopaque, @ptrCast(&T_flow_sum)),
        @as(*const anyopaque, @ptrCast(&T_flow_usum)),
        @as(*const anyopaque, @ptrCast(&T_flow_fsum)),
        @as(*const anyopaque, @ptrCast(&T_flow_enumerate)),
        @as(*const anyopaque, @ptrCast(&T_flow_of_isos)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_1)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_2)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_3)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_4)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_5)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_6)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_7)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_8)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_9)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_10)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_11)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_12)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_13)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_14)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_15)),
        @as(*const anyopaque, @ptrCast(&flow_ofIso_16)),
    },
    .method_names = &.{
        "imm #/0", "imm #/1", "imm #/2", "imm #/3", "imm #/4",
        "imm .range/1", "imm .range/2",
        "imm .fromOp/1", "imm .fromOp/2",
        "imm .fromMutSource/1", "imm .fromMutSource/2",
        "imm .sum/0", "imm .uSum/0", "imm .fSum/0",
        "imm .enumerate/0",
        "imm .ofIsos/1",
        "imm .ofIso/1", "imm .ofIso/2", "imm .ofIso/3", "imm .ofIso/4",
        "imm .ofIso/5", "imm .ofIso/6", "imm .ofIso/7", "imm .ofIso/8",
        "imm .ofIso/9", "imm .ofIso/10", "imm .ofIso/11", "imm .ofIso/12",
        "imm .ofIso/13", "imm .ofIso/14", "imm .ofIso/15", "imm .ofIso/16",
    },
    .storage_mode = .singleton,
};
