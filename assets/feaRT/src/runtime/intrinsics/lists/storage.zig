//! Refcounted backing store shared by List and UList. A `List`/`UList` object
//! is a one-word capture (`ListCaptures`) pointing at a `ListStorage`; several
//! wrapper objects may share one storage (zero-copy retags between List and
//! UList), so the storage carries its own atomic refcount independent of the
//! wrappers' object refcounts.

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");

const FatPtr = objs.FatPtr;

pub const ArrayList = std.ArrayList(FatPtr);

pub const ListStorage = struct {
    ref_count: std.atomic.Value(u32),
    al: ArrayList,
};

pub const ListCaptures = extern struct {
    list_ptr: usize, // *ListStorage stored as usize (extern struct can't hold non-extern ptrs)
};

pub fn deref_storage(fp: FatPtr) *ListStorage {
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

pub fn retain_storage(storage: *ListStorage) void {
    _ = storage.ref_count.fetchAdd(1, .monotonic);
}

/// `releasing_worker_id` is the worker on whose behalf this release runs. It
/// travels down from the start of the drop chain.
pub fn release_storage(storage: *ListStorage, releasing_worker_id: u32) void {
    const old_count = storage.ref_count.fetchSub(1, .release);
    if (std.debug.runtime_safety) std.debug.assert(old_count != 0);
    if (old_count != 1) return;
    _ = storage.ref_count.load(.acquire);
    for (storage.al.items) |item| item.rc_decrement_as(releasing_worker_id);
    storage.al.deinit(gc.allocator);
    gc.recycleDestroy(ListStorage, storage, .list_release);
}

pub fn list_drop(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(ListCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    release_storage(@ptrFromInt(self.captures.list_ptr), releasing_worker_id);
}
