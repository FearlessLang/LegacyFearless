//! Instance methods and vtables for `base.List` and `base.UList`, plus the
//! constructors that pair a `ListStorage` with the right vtable. Native methods
//! cover the storage-touching operations; everything else trampolines to the
//! generated base default bodies (`T_*` thunks).

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const nat_rt = @import("../nat.zig");
const bool_intrinsics = @import("../bool.zig");
const flow_rt = @import("../flow.zig");
const storage_mod = @import("storage.zig");
const root = @import("root");
const pb = root.pkg_base;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;
const ListCaptures = storage_mod.ListCaptures;
const ListStorage = storage_mod.ListStorage;
const deref_list = storage_mod.deref_list;

pub fn make_list(items: []const FatPtr) FatPtr {
    const storage = storage_mod.make_storage(items.len);
    storage.al.appendSliceAssumeCapacity(items);
    return objs.obj_k(ListCaptures, &VT_List, .{ .list_ptr = @intFromPtr(storage) });
}

pub fn make_ulist(items: []const FatPtr) FatPtr {
    const storage = storage_mod.make_storage(items.len);
    storage.al.appendSliceAssumeCapacity(items);
    return objs.obj_k(ListCaptures, &VT_UList, .{ .list_ptr = @intFromPtr(storage) });
}

pub fn make_ulist_with_capacity(cap: u64) FatPtr {
    const storage = storage_mod.make_storage(@intCast(cap));
    return objs.obj_k(ListCaptures, &VT_UList, .{ .list_ptr = @intFromPtr(storage) });
}

pub fn wrap_list_storage(storage: *ListStorage) FatPtr {
    return objs.obj_k(ListCaptures, &VT_List, .{ .list_ptr = @intFromPtr(storage) });
}

pub fn wrap_ulist_storage(storage: *ListStorage) FatPtr {
    return objs.obj_k(ListCaptures, &VT_UList, .{ .list_ptr = @intFromPtr(storage) });
}

pub fn make_some(item: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Opts_0), h("imm #/1"), .{item}, @src());
}

pub fn make_none() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Opt_1);
}

pub fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

// Shared instance methods (List + UList)

fn list_get(self: FatPtr, index: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer index.rc_decrement();
    const al = deref_list(self);
    const i = nat_rt.deref(index);
    return al.items[i].share();
}

fn list_tryGet(self: FatPtr, index: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer index.rc_decrement();
    const al = deref_list(self);
    const i = nat_rt.deref(index);
    if (i >= al.items.len) return make_none();
    return make_some(al.items[i].share());
}

fn list_size(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const al = deref_list(self);
    return nat_rt.make(al.items.len);
}

fn list_isEmpty(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const al = deref_list(self);
    return bool_intrinsics.to_bool(al.items.len == 0);
}

// List-specific methods

fn list_uList(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const al = deref_list(self);
    const storage = storage_mod.make_storage(al.items.len);
    storage.al.appendSliceAssumeCapacity(al.items);
    for (storage.al.items) |item| _ = item.share();
    return wrap_ulist_storage(storage);
}

// UList-specific methods

fn ulist_add(self: FatPtr, item: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const al = deref_list(self);
    al.append(gc.allocator, item) catch @panic("OOM");
    return make_void();
}

fn ulist_takeFirst(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const al = deref_list(self);
    if (al.items.len == 0) return make_none();
    const first = al.orderedRemove(0);
    return make_some(first);
}

fn ulist_clear(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const al = deref_list(self);
    for (al.items) |item| item.rc_decrement();
    al.clearRetainingCapacity();
    return make_void();
}

fn ulist_to_list(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    // Zero-copy retag: share backing with a List wrapper
    const caps = objs.deref(ListCaptures, self);
    storage_mod.retain_storage(@ptrFromInt(caps.list_ptr));
    return objs.obj_k(ListCaptures, &VT_List, caps.*);
}

// Default-body thunks (delegate to generated _Zfun)

fn T_list_as_read(self_m: FatPtr, f_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__Zdotas_1_read_Zfun(f_m, self_m);
}
fn T_list_flow_mut(self_m: FatPtr) callconv(.c) FatPtr {
    return flow_rt.make_flow_from_list(self_m);
}
fn T_list_flowmut_mut(self_m: FatPtr, start_m: FatPtr, end_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__Zdot_flowmut_2_mut_Zfun(start_m, end_m, self_m);
}
fn T_list_flow_read(self_m: FatPtr) callconv(.c) FatPtr {
    return flow_rt.make_flow_from_list(self_m);
}
fn T_list_flowread_read(self_m: FatPtr, start_m: FatPtr, end_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__Zdot_flowread_2_read_Zfun(start_m, end_m, self_m);
}
fn T_list_flow_imm(self_m: FatPtr) callconv(.c) FatPtr {
    return flow_rt.make_flow_from_list(self_m);
}
fn T_list_flowimm_imm(self_m: FatPtr, start_m: FatPtr, end_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__Zdot_flowimm_2_imm_Zfun(start_m, end_m, self_m);
}
fn T_list_iter_mut(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__Zdotiter_0_mut_Zfun(self_m);
}
fn T_list_iter_read(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__Zdotiter_0_read_Zfun(self_m);
}
fn T_list_iter_imm(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__Zdotiter_0_imm_Zfun(self_m);
}
fn T_list_subList_read(self_m: FatPtr, from_m: FatPtr, to_m: FatPtr) callconv(.c) FatPtr {
    return pb.List_1__ZdotsubList_2_read_Zfun(from_m, to_m, self_m);
}

fn T_ulist_plus_mut(self_m: FatPtr, e_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__Zplus_1_mut_Zfun(e_m, self_m);
}
fn T_ulist_addAll_mut(self_m: FatPtr, other_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__ZdotaddAll_1_mut_Zfun(other_m, self_m);
}
fn T_ulist_as_read(self_m: FatPtr, f_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__Zdotas_1_read_Zfun(f_m, self_m);
}
fn T_ulist_flow_mut(self_m: FatPtr) callconv(.c) FatPtr {
    return flow_rt.make_flow_from_list(self_m);
}
fn T_ulist_flow_read(self_m: FatPtr) callconv(.c) FatPtr {
    return flow_rt.make_flow_from_list(self_m);
}
fn T_ulist_flowread_read(self_m: FatPtr, start_m: FatPtr, end_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__Zdot_flowread_2_read_Zfun(start_m, end_m, self_m);
}
fn T_ulist_flow_imm(self_m: FatPtr) callconv(.c) FatPtr {
    return flow_rt.make_flow_from_list(self_m);
}
fn T_ulist_flowimm_imm(self_m: FatPtr, start_m: FatPtr, end_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__Zdot_flowimm_2_imm_Zfun(start_m, end_m, self_m);
}
fn T_ulist_iter_mut(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__Zdotiter_0_mut_Zfun(self_m);
}
fn T_ulist_iter_read(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__Zdotiter_0_read_Zfun(self_m);
}
fn T_ulist_iter_imm(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.UList_1__Zdotiter_0_imm_Zfun(self_m);
}

pub const VT_List: objs.VTable = .{
    .type_name = "base.List/1",
    .hashes = &.{
        h("mut .get/1"),        h("read .get/1"),     h("imm .get/1"),
        h("mut .tryGet/1"),     h("read .tryGet/1"),  h("imm .tryGet/1"),
        h("read .size/0"),      h("read .isEmpty/0"), h("mut .uList/0"),
        h("read .uList/0"),     h("imm .uList/0"),    h("read .as/1"),
        h("mut .flow/0"),       h("mut ._flowmut/2"), h("read .flow/0"),
        h("read ._flowread/2"), h("imm .flow/0"),     h("imm ._flowimm/2"),
        h("mut .iter/0"),       h("read .iter/0"),    h("imm .iter/0"),
        h("read .subList/2"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&list_get)),             @as(*const anyopaque, @ptrCast(&list_get)),           @as(*const anyopaque, @ptrCast(&list_get)),
        @as(*const anyopaque, @ptrCast(&list_tryGet)),          @as(*const anyopaque, @ptrCast(&list_tryGet)),        @as(*const anyopaque, @ptrCast(&list_tryGet)),
        @as(*const anyopaque, @ptrCast(&list_size)),            @as(*const anyopaque, @ptrCast(&list_isEmpty)),       @as(*const anyopaque, @ptrCast(&list_uList)),
        @as(*const anyopaque, @ptrCast(&list_uList)),           @as(*const anyopaque, @ptrCast(&list_uList)),         @as(*const anyopaque, @ptrCast(&T_list_as_read)),
        @as(*const anyopaque, @ptrCast(&T_list_flow_mut)),      @as(*const anyopaque, @ptrCast(&T_list_flowmut_mut)), @as(*const anyopaque, @ptrCast(&T_list_flow_read)),
        @as(*const anyopaque, @ptrCast(&T_list_flowread_read)), @as(*const anyopaque, @ptrCast(&T_list_flow_imm)),    @as(*const anyopaque, @ptrCast(&T_list_flowimm_imm)),
        @as(*const anyopaque, @ptrCast(&T_list_iter_mut)),      @as(*const anyopaque, @ptrCast(&T_list_iter_read)),   @as(*const anyopaque, @ptrCast(&T_list_iter_imm)),
        @as(*const anyopaque, @ptrCast(&T_list_subList_read)),
    },
    .method_names = &.{
        "mut .get/1",        "read .get/1",     "imm .get/1",
        "mut .tryGet/1",     "read .tryGet/1",  "imm .tryGet/1",
        "read .size/0",      "read .isEmpty/0", "mut .uList/0",
        "read .uList/0",     "imm .uList/0",    "read .as/1",
        "mut .flow/0",       "mut ._flowmut/2", "read .flow/0",
        "read ._flowread/2", "imm .flow/0",     "imm ._flowimm/2",
        "mut .iter/0",       "read .iter/0",    "imm .iter/0",
        "read .subList/2",
    },
    .drop_fn = storage_mod.list_drop,
};

pub const VT_UList: objs.VTable = .{
    .type_name = "base.UList/1",
    .hashes = &.{
        h("mut .get/1"),       h("read .get/1"),       h("imm .get/1"),
        h("mut .tryGet/1"),    h("read .tryGet/1"),    h("imm .tryGet/1"),
        h("read .size/0"),     h("read .isEmpty/0"),   h("mut .add/1"),
        h("mut .takeFirst/0"), h("mut .clear/0"),      h("mut .list/0"),
        h("read .list/0"),     h("imm .list/0"),       h("mut +/1"),
        h("mut .addAll/1"),    h("read .as/1"),        h("mut .flow/0"),
        h("read .flow/0"),     h("read ._flowread/2"), h("imm .flow/0"),
        h("imm ._flowimm/2"),  h("mut .iter/0"),       h("read .iter/0"),
        h("imm .iter/0"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&list_get)),            @as(*const anyopaque, @ptrCast(&list_get)),              @as(*const anyopaque, @ptrCast(&list_get)),
        @as(*const anyopaque, @ptrCast(&list_tryGet)),         @as(*const anyopaque, @ptrCast(&list_tryGet)),           @as(*const anyopaque, @ptrCast(&list_tryGet)),
        @as(*const anyopaque, @ptrCast(&list_size)),           @as(*const anyopaque, @ptrCast(&list_isEmpty)),          @as(*const anyopaque, @ptrCast(&ulist_add)),
        @as(*const anyopaque, @ptrCast(&ulist_takeFirst)),     @as(*const anyopaque, @ptrCast(&ulist_clear)),           @as(*const anyopaque, @ptrCast(&ulist_to_list)),
        @as(*const anyopaque, @ptrCast(&ulist_to_list)),       @as(*const anyopaque, @ptrCast(&ulist_to_list)),         @as(*const anyopaque, @ptrCast(&T_ulist_plus_mut)),
        @as(*const anyopaque, @ptrCast(&T_ulist_addAll_mut)),  @as(*const anyopaque, @ptrCast(&T_ulist_as_read)),       @as(*const anyopaque, @ptrCast(&T_ulist_flow_mut)),
        @as(*const anyopaque, @ptrCast(&T_ulist_flow_read)),   @as(*const anyopaque, @ptrCast(&T_ulist_flowread_read)), @as(*const anyopaque, @ptrCast(&T_ulist_flow_imm)),
        @as(*const anyopaque, @ptrCast(&T_ulist_flowimm_imm)), @as(*const anyopaque, @ptrCast(&T_ulist_iter_mut)),      @as(*const anyopaque, @ptrCast(&T_ulist_iter_read)),
        @as(*const anyopaque, @ptrCast(&T_ulist_iter_imm)),
    },
    .method_names = &.{
        "mut .get/1",       "read .get/1",       "imm .get/1",
        "mut .tryGet/1",    "read .tryGet/1",    "imm .tryGet/1",
        "read .size/0",     "read .isEmpty/0",   "mut .add/1",
        "mut .takeFirst/0", "mut .clear/0",      "mut .list/0",
        "read .list/0",     "imm .list/0",       "mut +/1",
        "mut .addAll/1",    "read .as/1",        "mut .flow/0",
        "read .flow/0",     "read ._flowread/2", "imm .flow/0",
        "imm ._flowimm/2",  "mut .iter/0",       "read .iter/0",
        "imm .iter/0",
    },
    .drop_fn = storage_mod.list_drop,
};
