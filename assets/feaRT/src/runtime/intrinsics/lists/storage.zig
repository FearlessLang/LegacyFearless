//! Refcounted backing store shared by List and UList. A `List`/`UList` object
//! is a one-word capture (`ListCaptures`) pointing at a `ListStorage`; several
//! wrapper objects may share one storage (zero-copy retags between List and
//! UList), so the storage carries its own atomic refcount independent of the
//! wrappers' object refcounts.

const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const worker_mod = @import("../../worker.zig");

const FatPtr = objs.FatPtr;

pub const ItemVec = std.ArrayList(FatPtr);

/// An `ItemVec` holds a slice, so this struct cannot be `extern` and its header
/// is not guaranteed to sit at offset 0.
/// [`storageEdge`] therefore names the header itself, and the two hooks below
/// walk back to the storage from it.
pub const ListStorage = struct {
    header: objs.RcCellHeader,
    al: ItemVec,
};

/// The vtable of a `ListStorage`.
///
/// A storage is a node of the cycle collector in its own right, because several
/// wrappers may share one: if each wrapper enumerated the storage's items, one
/// real reference would be counted once per wrapper. No program value names a
/// storage, so this vtable exists only for the `FatPtr` the collector builds
/// over one.
pub const VT_ListStorage: objs.VTable = .{
    .type_name = "<runtime list storage>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitiveContainer,
    .trace_fn = storage_trace,
};

/// The `FatPtr` the collector follows to reach `storage`. It names the header
/// rather than the storage, which is what lets the collector read a colour and
/// a count through the same cast it uses for every other cell.
pub fn storageEdge(storage: *ListStorage) FatPtr {
    return .{ .data = .{ .raw_cell = @ptrCast(&storage.header) }, .vt = &VT_ListStorage };
}

/// The storage whose header `node` names.
pub fn storageOfEdge(node: FatPtr) *ListStorage {
    const header: *objs.RcCellHeader = @ptrCast(@alignCast(node.data.raw_cell));
    return @alignCast(@fieldParentPtr("header", header));
}

/// Enumerates every item, for the cycle collector.
fn storage_trace(node: *anyopaque, visit: objs.VisitFn, ctx: *anyopaque) callconv(.c) void {
    const header: *objs.RcCellHeader = @ptrCast(@alignCast(node));
    const storage: *ListStorage = @alignCast(@fieldParentPtr("header", header));
    for (storage.al.items) |item| visit(ctx, item);
}

pub const ListCaptures = extern struct {
    list_ptr: usize, // *ListStorage stored as usize (extern struct can't hold non-extern ptrs)
};

pub fn deref_storage(fp: FatPtr) *ListStorage {
    const caps = objs.deref(ListCaptures, fp);
    return @ptrFromInt(caps.list_ptr);
}

pub fn deref_list(fp: FatPtr) *ItemVec {
    return &deref_storage(fp).al;
}

pub fn make_storage(capacity: usize) *ListStorage {
    const storage = gc.allocator.create(ListStorage) catch @panic("OOM");
    storage.* = .{
        .header = .born,
        .al = ItemVec.initCapacity(gc.allocator, capacity) catch @panic("OOM"),
    };
    // The wrapper about to be built holds the one reference `born` gives it.
    return storage;
}

/// One more wrapper over `storage`, from a zero-copy retag between `List` and
/// `UList`.
pub fn retain_storage(storage: *ListStorage) void {
    _ = storageEdge(storage).share();
}

/// Releases every item a dead storage holds. Part of `Release`, which runs as
/// soon as the count reaches zero.
pub fn drop_children(storage: *ListStorage, releasing_worker_id: u32) void {
    for (storage.al.items) |item| item.rc_decrement_as(releasing_worker_id);
}

/// Gives a dead storage back. Part of the collector's `Free`.
pub fn free_storage(storage: *ListStorage, releasing_worker_id: u32) void {
    _ = releasing_worker_id;
    storage.al.deinit(gc.allocator);
    gc.recycleDestroy(ListStorage, storage, .list_release);
}

/// Enumerates the one reference a `List` or `UList` wrapper holds: its storage.
pub fn list_trace(header: *anyopaque, visit: objs.VisitFn, ctx: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(ListCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    visit(ctx, storageEdge(@ptrFromInt(self.captures.list_ptr)));
}

pub fn list_drop(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(ListCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    const storage: *ListStorage = @ptrFromInt(self.captures.list_ptr);
    storageEdge(storage).rc_decrement_as(releasing_worker_id);
}
