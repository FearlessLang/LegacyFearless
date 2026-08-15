//! Factory vtables for `base.List` and `base.UList`: the `#/0..16` inline
//! constructors plus `.fromLList`, `.consumeUList`, and `.withCapacity`.

const objs = @import("../../objs.zig");
const nat_rt = @import("../nat.zig");
const storage_mod = @import("storage.zig");
const instance = @import("instance.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;
const ArrayList = storage_mod.ArrayList;
const ListCaptures = storage_mod.ListCaptures;
const make_list = instance.make_list;
const make_ulist = instance.make_ulist;

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

fn fromLList_into(llist: FatPtr, al: *ArrayList) void {
    // Walk the LList via .head/.tail. .size bounds the walk, so every .head
    // is a Some and the extractor's empty case is unreachable.
    const size = nat_rt.deref(objs.call(llist.share(), h("imm .size/0"), .{}, @src()));
    var i: u64 = 0;
    var current = llist;
    while (i < size) : (i += 1) {
        const opt = objs.call(current.share(), h("imm .head/0"), .{}, @src());
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
    const storage = storage_mod.make_storage(@intCast(size));
    fromLList_into(llist, &storage.al);
    return instance.wrap_list_storage(storage);
}

fn ulist_fromLList(_: FatPtr, llist: FatPtr) callconv(.c) FatPtr {
    const size = nat_rt.deref(objs.call(llist.share(), h("imm .size/0"), .{}, @src()));
    const storage = storage_mod.make_storage(@intCast(size));
    fromLList_into(llist, &storage.al);
    return instance.wrap_ulist_storage(storage);
}

fn list_consumeUList(_: FatPtr, ulist: FatPtr) callconv(.c) FatPtr {
    // Zero-copy: reuse the UList's ArrayList, just retag as List
    defer ulist.rc_decrement();
    const caps = objs.deref(ListCaptures, ulist);
    storage_mod.retain_storage(@ptrFromInt(caps.list_ptr));
    return objs.obj_k(ListCaptures, &instance.VT_List, caps.*);
}

fn ulist_withCapacity(_: FatPtr, cap: FatPtr) callconv(.c) FatPtr {
    return instance.make_ulist_with_capacity(nat_rt.deref(cap));
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
    return instance.make_void();
}

fn opt_extractor_none(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    unreachable;
}

const VT_OptExtractor: objs.VTable = .{
    .type_name = "<runtime list opt extractor>",
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
