const std = @import("std");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");
const nat_rt = @import("nat.zig");
const bool_intrinsics = @import("bool.zig");
const flow_rt = @import("flow.zig");
const root = @import("root");
const pb = root.pkg_base;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

const ArrayList = std.ArrayList(FatPtr);

pub const ListStorage = struct {
    ref_count: std.atomic.Value(u32),
    al: ArrayList,
};

// ==========================================
// Data Representation
// ==========================================

pub const ListCaptures = extern struct {
    list_ptr: usize, // *ListStorage stored as usize (extern struct can't hold non-extern ptrs)
};

fn deref_storage(fp: FatPtr) *ListStorage {
    const caps = objs.deref(ListCaptures, fp);
    return @ptrFromInt(caps.list_ptr);
}

pub fn deref_list(fp: FatPtr) *ArrayList {
    return &deref_storage(fp).al;
}

pub fn make_storage(capacity: usize) *ListStorage {
    const storage = gc.allocator.create(ListStorage) catch @panic("OOM");
    storage.* = .{
        .ref_count = std.atomic.Value(u32).init(1),
        .al = ArrayList.initCapacity(gc.allocator, capacity) catch @panic("OOM"),
    };
    return storage;
}

fn retain_storage(storage: *ListStorage) void {
    _ = storage.ref_count.fetchAdd(1, .monotonic);
}

fn release_storage(storage: *ListStorage) void {
    const old_count = storage.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;
    _ = storage.ref_count.load(.acquire);
    for (storage.al.items) |item| item.rc_decrement();
    storage.al.deinit(gc.allocator);
    gc.recycleDestroy(ListStorage, storage, .list_release);
}

fn list_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(ListCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    release_storage(@ptrFromInt(self.captures.list_ptr));
}

fn make_list(items: []const FatPtr) FatPtr {
    const storage = make_storage(items.len);
    storage.al.appendSliceAssumeCapacity(items);
    return objs.obj_k(ListCaptures, &VT_List, .{ .list_ptr = @intFromPtr(storage) });
}

fn make_ulist(items: []const FatPtr) FatPtr {
    const storage = make_storage(items.len);
    storage.al.appendSliceAssumeCapacity(items);
    return objs.obj_k(ListCaptures, &VT_UList, .{ .list_ptr = @intFromPtr(storage) });
}

fn make_ulist_with_capacity(cap: u64) FatPtr {
    const storage = make_storage(@intCast(cap));
    return objs.obj_k(ListCaptures, &VT_UList, .{ .list_ptr = @intFromPtr(storage) });
}

pub fn wrap_list_storage(storage: *ListStorage) FatPtr {
    return objs.obj_k(ListCaptures, &VT_List, .{ .list_ptr = @intFromPtr(storage) });
}

pub fn wrap_ulist_storage(storage: *ListStorage) FatPtr {
    return objs.obj_k(ListCaptures, &VT_UList, .{ .list_ptr = @intFromPtr(storage) });
}

// ==========================================
// Opt / Void helpers
// ==========================================

fn make_some(item: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Opts_0), h("imm #/1"), .{item}, @src());
}

fn make_none() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Opt_1);
}

fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

// ==========================================
// Shared instance methods (List + UList)
// ==========================================

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

// ==========================================
// List-specific methods
// ==========================================

fn list_uList(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const al = deref_list(self);
    const storage = make_storage(al.items.len);
    storage.al.appendSliceAssumeCapacity(al.items);
    for (storage.al.items) |item| _ = item.share();
    return wrap_ulist_storage(storage);
}

// ==========================================
// UList-specific methods
// ==========================================

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
    retain_storage(@ptrFromInt(caps.list_ptr));
    return objs.obj_k(ListCaptures, &VT_List, caps.*);
}

// ==========================================
// Default-body thunks (delegate to generated _Zfun)
// ==========================================

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

// ==========================================
// VTables
// ==========================================

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
    .drop_fn = list_drop,
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
    .drop_fn = list_drop,
};

// ==========================================
// Factory functions (comptime-generated)
// ==========================================

// Unfortunately we can't use a loop to generate these because Zig needs each
// function to have a unique type signature. We generate each arity explicitly.
fn list_factory_0(_: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{});
}
fn list_factory_1(_: FatPtr, a0: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{a0});
}
fn list_factory_2(_: FatPtr, a0: FatPtr, a1: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1 });
}
fn list_factory_3(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2 });
}
fn list_factory_4(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3 });
}
fn list_factory_5(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4 });
}
fn list_factory_6(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5 });
}
fn list_factory_7(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6 });
}
fn list_factory_8(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7 });
}
fn list_factory_9(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8 });
}
fn list_factory_10(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9 });
}
fn list_factory_11(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10 });
}
fn list_factory_12(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11 });
}
fn list_factory_13(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12 });
}
fn list_factory_14(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13 });
}
fn list_factory_15(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr, a14: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14 });
}
fn list_factory_16(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr, a14: FatPtr, a15: FatPtr) callconv(.c) FatPtr {
    return make_list(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15 });
}

fn ulist_factory_0(_: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{});
}
fn ulist_factory_1(_: FatPtr, a0: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{a0});
}
fn ulist_factory_2(_: FatPtr, a0: FatPtr, a1: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1 });
}
fn ulist_factory_3(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2 });
}
fn ulist_factory_4(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3 });
}
fn ulist_factory_5(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4 });
}
fn ulist_factory_6(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5 });
}
fn ulist_factory_7(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6 });
}
fn ulist_factory_8(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7 });
}
fn ulist_factory_9(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8 });
}
fn ulist_factory_10(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9 });
}
fn ulist_factory_11(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10 });
}
fn ulist_factory_12(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11 });
}
fn ulist_factory_13(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12 });
}
fn ulist_factory_14(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13 });
}
fn ulist_factory_15(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr, a14: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14 });
}
fn ulist_factory_16(_: FatPtr, a0: FatPtr, a1: FatPtr, a2: FatPtr, a3: FatPtr, a4: FatPtr, a5: FatPtr, a6: FatPtr, a7: FatPtr, a8: FatPtr, a9: FatPtr, a10: FatPtr, a11: FatPtr, a12: FatPtr, a13: FatPtr, a14: FatPtr, a15: FatPtr) callconv(.c) FatPtr {
    return make_ulist(&.{ a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15 });
}

// ==========================================
// fromLList / consumeUList / withCapacity
// ==========================================

fn fromLList_into(llist: FatPtr, al: *ArrayList) void {
    // Walk the LList using .head/0 and .tail/0
    // .head/0 returns Opt: empty Opt (VT_Opt_1) for empty list, Some for non-empty
    // .size/0 gives us the count so we know when to stop (avoids Opt extraction)
    const size = nat_rt.deref(objs.call(llist.share(), h("imm .size/0"), .{}, @src()));
    var i: u64 = 0;
    var current = llist;
    while (i < size) : (i += 1) {
        // Use .get/1 with index — this returns an Opt, but we use .match to extract
        const opt = objs.call(current.share(), h("imm .head/0"), .{}, @src());
        // opt is a Some — use .match/1 with our extractor handler
        const handler = objs.obj_k(OptExtractorCaptures, &VT_OptExtractor, .{
            .result_ptr = @intFromPtr(&al.items.ptr[al.items.len]),
        });
        _ = objs.call(opt, h("imm .match/1"), .{handler}, @src());
        al.items.len += 1;
        const next = objs.call(current, h("imm .tail/0"), .{}, @src());
        current = next;
    }
    current.rc_decrement();
}

fn list_fromLList(_: FatPtr, llist: FatPtr) callconv(.c) FatPtr {
    const size = nat_rt.deref(objs.call(llist.share(), h("imm .size/0"), .{}, @src()));
    const storage = make_storage(@intCast(size));
    fromLList_into(llist, &storage.al);
    return wrap_list_storage(storage);
}

fn ulist_fromLList(_: FatPtr, llist: FatPtr) callconv(.c) FatPtr {
    const size = nat_rt.deref(objs.call(llist.share(), h("imm .size/0"), .{}, @src()));
    const storage = make_storage(@intCast(size));
    fromLList_into(llist, &storage.al);
    return wrap_ulist_storage(storage);
}

fn list_consumeUList(_: FatPtr, ulist: FatPtr) callconv(.c) FatPtr {
    // Zero-copy: reuse the UList's ArrayList, just retag as List
    defer ulist.rc_decrement();
    const caps = objs.deref(ListCaptures, ulist);
    retain_storage(@ptrFromInt(caps.list_ptr));
    return objs.obj_k(ListCaptures, &VT_List, caps.*);
}

fn ulist_withCapacity(_: FatPtr, cap: FatPtr) callconv(.c) FatPtr {
    return make_ulist_with_capacity(nat_rt.deref(cap));
}

// Helper for extracting values from Opt via .match
// The handler captures a pointer to the output slot.
const OptExtractorCaptures = extern struct {
    result_ptr: usize, // *FatPtr stored as usize
};

fn opt_extractor_some(self: FatPtr, val: FatPtr) callconv(.c) FatPtr {
    const caps = objs.deref(OptExtractorCaptures, self);
    const ptr: *FatPtr = @ptrFromInt(caps.result_ptr);
    ptr.* = val;
    return make_void();
}

fn opt_extractor_none(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    unreachable;
}

const VT_OptExtractor: objs.VTable = .{
    .type_name = "_ListOptExtractor",
    .hashes = &.{
        h("imm .some/1"),
        h("imm .none/0"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&opt_extractor_some)),
        @as(*const anyopaque, @ptrCast(&opt_extractor_none)),
    },
    .method_names = &.{
        "imm .some/1",
        "imm .none/0",
    },
};

// ==========================================
// Factory VTables
// ==========================================

pub const VT_ListFactory: objs.VTable = .{
    .type_name = "base.List/0",
    .hashes = &.{
        h("imm #/0"),
        h("imm #/1"),
        h("imm #/2"),
        h("imm #/3"),
        h("imm #/4"),
        h("imm #/5"),
        h("imm #/6"),
        h("imm #/7"),
        h("imm #/8"),
        h("imm #/9"),
        h("imm #/10"),
        h("imm #/11"),
        h("imm #/12"),
        h("imm #/13"),
        h("imm #/14"),
        h("imm #/15"),
        h("imm #/16"),
        h("imm .fromLList/1"),
        h("imm .consumeUList/1"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&list_factory_0)),
        @as(*const anyopaque, @ptrCast(&list_factory_1)),
        @as(*const anyopaque, @ptrCast(&list_factory_2)),
        @as(*const anyopaque, @ptrCast(&list_factory_3)),
        @as(*const anyopaque, @ptrCast(&list_factory_4)),
        @as(*const anyopaque, @ptrCast(&list_factory_5)),
        @as(*const anyopaque, @ptrCast(&list_factory_6)),
        @as(*const anyopaque, @ptrCast(&list_factory_7)),
        @as(*const anyopaque, @ptrCast(&list_factory_8)),
        @as(*const anyopaque, @ptrCast(&list_factory_9)),
        @as(*const anyopaque, @ptrCast(&list_factory_10)),
        @as(*const anyopaque, @ptrCast(&list_factory_11)),
        @as(*const anyopaque, @ptrCast(&list_factory_12)),
        @as(*const anyopaque, @ptrCast(&list_factory_13)),
        @as(*const anyopaque, @ptrCast(&list_factory_14)),
        @as(*const anyopaque, @ptrCast(&list_factory_15)),
        @as(*const anyopaque, @ptrCast(&list_factory_16)),
        @as(*const anyopaque, @ptrCast(&list_fromLList)),
        @as(*const anyopaque, @ptrCast(&list_consumeUList)),
    },
    .method_names = &.{
        "imm #/0",
        "imm #/1",
        "imm #/2",
        "imm #/3",
        "imm #/4",
        "imm #/5",
        "imm #/6",
        "imm #/7",
        "imm #/8",
        "imm #/9",
        "imm #/10",
        "imm #/11",
        "imm #/12",
        "imm #/13",
        "imm #/14",
        "imm #/15",
        "imm #/16",
        "imm .fromLList/1",
        "imm .consumeUList/1",
    },
    .storage_mode = .singleton,
};

pub const VT_UListFactory: objs.VTable = .{
    .type_name = "base.UList/0",
    .hashes = &.{
        h("imm #/0"),
        h("imm #/1"),
        h("imm #/2"),
        h("imm #/3"),
        h("imm #/4"),
        h("imm #/5"),
        h("imm #/6"),
        h("imm #/7"),
        h("imm #/8"),
        h("imm #/9"),
        h("imm #/10"),
        h("imm #/11"),
        h("imm #/12"),
        h("imm #/13"),
        h("imm #/14"),
        h("imm #/15"),
        h("imm #/16"),
        h("imm .fromLList/1"),
        h("imm .withCapacity/1"),
    },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&ulist_factory_0)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_1)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_2)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_3)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_4)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_5)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_6)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_7)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_8)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_9)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_10)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_11)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_12)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_13)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_14)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_15)),
        @as(*const anyopaque, @ptrCast(&ulist_factory_16)),
        @as(*const anyopaque, @ptrCast(&ulist_fromLList)),
        @as(*const anyopaque, @ptrCast(&ulist_withCapacity)),
    },
    .method_names = &.{
        "imm #/0",
        "imm #/1",
        "imm #/2",
        "imm #/3",
        "imm #/4",
        "imm #/5",
        "imm #/6",
        "imm #/7",
        "imm #/8",
        "imm #/9",
        "imm #/10",
        "imm #/11",
        "imm #/12",
        "imm #/13",
        "imm #/14",
        "imm #/15",
        "imm #/16",
        "imm .fromLList/1",
        "imm .withCapacity/1",
    },
    .storage_mode = .singleton,
};
