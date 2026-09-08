const std = @import("std");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");
const cycles = @import("../cycles.zig");
const nat_rt = @import("nat.zig");
const hash_rt = @import("hash.zig");
const bool_intrinsics = @import("bool.zig");
const root = @import("root");
const pb = root.pkg_base;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

// `base.LinkedHashMap` -- an insertion-ordered map. The backing is an
// array-backed hash map (`std.ArrayHashMapUnmanaged`): the array gives
// insertion order, the hash index gives O(1) get/put/remove. Keys are hashed and
// compared through the Fearless `hashFn`/`keyEq` closures the program supplied,
// mirroring the Java backend's `LinkedHashMap.Key`. `store_hash = true` caches
// each key's u32 hash in the table, so those closures run once per operation
// rather than once per probe.

fn make_some(item: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Opts_0), comptime h("imm #/1"), .{item}, @src());
}
fn make_none() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Opt_1);
}
fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

pub const EntryCaptures = extern struct { key: FatPtr, value: FatPtr };

fn entry_key(self: FatPtr) callconv(.c) FatPtr {
    return objs.deref(EntryCaptures, self).key.share();
}
fn entry_value(self: FatPtr) callconv(.c) FatPtr {
    return objs.deref(EntryCaptures, self).value.share();
}

pub const VT_Entry: objs.VTable = .{
    .type_name = "base.Entry/2",
    .hashes = &.{ h("read .key/0"), h("read .value/0"), h("mut .value/0") },
    .methods = &.{ @ptrCast(&entry_key), @ptrCast(&entry_value), @ptrCast(&entry_value) },
    .method_names = &.{ "read .key/0", "read .value/0", "mut .value/0" },
};

// obj_k shares the key/value into the captures, so callers pass borrowed refs.
fn make_entry(key: FatPtr, value: FatPtr) FatPtr {
    return objs.obj_k(EntryCaptures, &VT_Entry, .{ .key = key, .value = value });
}

/// A by-value view of a `MapStorage`'s closures, handed to the std map for the
/// duration of one operation. It holds *non-owning* copies of `keyEq`/`hashFn`
/// (same data/vt as the storage's owned refs). Every operand of a call is lent,
/// receiver and argument alike, so the closures and the keys both pass to
/// `objs.call` as they stand.
pub const MapCtx = struct {
    keyEq: FatPtr,
    hashFn: FatPtr,

    /// `Key.hashCode`: build a `ToHash` from the key, feed it a fresh
    /// `CheapHash`, then truncate the computed u64 to u32 (the Java backend's
    /// `Math.toIntExact` over an `int` hash).
    pub fn hash(self: MapCtx, key: FatPtr) u32 {
        const to_hash = objs.call(self.hashFn, comptime h("read #/1"), .{key}, @src());
        defer to_hash.rc_decrement();
        const cheap = hash_rt.make_cheap_hash();
        defer cheap.rc_decrement();
        const hashed = objs.call(to_hash, comptime h("read .hash/1"), .{cheap}, @src());
        defer hashed.rc_decrement();
        const n = objs.call(hashed, comptime h("mut .compute/0"), .{}, @src());
        return @truncate(nat_rt.deref(n));
    }

    /// `Key.equals`: the program's `keyEq` closure decides. `a`/`b` are the
    /// map's stored / probe keys; neither is consumed.
    pub fn eql(self: MapCtx, a: FatPtr, b: FatPtr, b_index: usize) bool {
        _ = b_index;
        const res = objs.call(self.keyEq, comptime h("read #/2"), .{ a, b }, @src());
        return res.vt == &pb.VT_True_0;
    }
};

const MapType = std.ArrayHashMapUnmanaged(FatPtr, FatPtr, MapCtx, true);

pub const MapStorage = struct {
    map: MapType,
    keyEq: FatPtr,
    hashFn: FatPtr,
};

pub const MapCaptures = extern struct { storage_ptr: usize };

fn deref_storage(fp: FatPtr) *MapStorage {
    return @ptrFromInt(objs.deref(MapCaptures, fp).storage_ptr);
}

/// A transient, non-owning context built per operation from the storage.
fn ctx_of(storage: *MapStorage) MapCtx {
    return .{ .keyEq = storage.keyEq, .hashFn = storage.hashFn };
}

/// Enumerates every key, every value and both closures, for the cycle
/// collector.
pub fn map_trace(header: *anyopaque, visit: objs.VisitFn, ctx: *anyopaque) callconv(.c) void {
    const storage = storage_of_header(header);
    for (storage.map.keys()) |k| visit(ctx, k);
    for (storage.map.values()) |v| visit(ctx, v);
    visit(ctx, storage.keyEq);
    visit(ctx, storage.hashFn);
}

/// Releases every entry and both closures. Part of `Release`.
pub fn map_drop(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
    const storage = storage_of_header(header);
    for (storage.map.keys()) |k| k.rc_decrement_as(releasing_worker_id);
    for (storage.map.values()) |v| v.rc_decrement_as(releasing_worker_id);
    storage.keyEq.rc_decrement_as(releasing_worker_id);
    storage.hashFn.rc_decrement_as(releasing_worker_id);
}

/// Gives the table back. Part of the collector's `Free`, which a candidate-root
/// buffer can hold back until the buffer has been looked at.
pub fn map_free(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
    _ = releasing_worker_id;
    const storage = storage_of_header(header);
    storage.map.deinit(gc.allocator);
    gc.recycleDestroy(MapStorage, storage, .map_release);
}

fn storage_of_header(header: *anyopaque) *MapStorage {
    const Layout = objs.GenObjectLayoutType(MapCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    return @ptrFromInt(self.captures.storage_ptr);
}

/// Insert or replace. Both operands are on loan, so the map takes a reference of
/// its own to each before it stores them.
fn map_insert(self: FatPtr, storage: *MapStorage, key: FatPtr, val: FatPtr) void {
    cycles.noteStore(self, key);
    cycles.noteStore(self, val);
    const key_kept = key.share().box_transient();
    const val_kept = val.share().box_transient();
    const gop = storage.map.getOrPutContext(gc.allocator, key_kept, ctx_of(storage)) catch @panic("OOM");
    if (!gop.found_existing) {
        // getOrPutContext already wrote `key_kept` into key_ptr.
        gop.value_ptr.* = val_kept;
        return;
    }
    // The key already present keeps its place, so the reference taken for the
    // key goes back along with the value it displaced.
    const replaced = gop.value_ptr.*;
    gop.value_ptr.* = val_kept;
    replaced.rc_decrement();
    key_kept.rc_decrement();
}

fn map_plus(self: FatPtr, k: FatPtr, v: FatPtr) callconv(.c) FatPtr {
    map_insert(self, deref_storage(self), k, v);
    return self.share(); // `mut +` answers with the same mutable map
}

fn map_put(self: FatPtr, k: FatPtr, v: FatPtr) callconv(.c) FatPtr {
    map_insert(self, deref_storage(self), k, v);
    return make_void();
}

fn map_is_empty(self: FatPtr) callconv(.c) FatPtr {
    return bool_intrinsics.to_bool(deref_storage(self).map.count() == 0);
}

fn map_get(self: FatPtr, key: FatPtr) callconv(.c) FatPtr {
    const storage = deref_storage(self);
    if (storage.map.getContext(key, ctx_of(storage))) |v| return make_some(v.share());
    return make_none();
}

fn map_remove(self: FatPtr, key: FatPtr) callconv(.c) FatPtr {
    const storage = deref_storage(self);
    if (storage.map.fetchOrderedRemoveContext(key, ctx_of(storage))) |kv| {
        // The stored key is dropped; the value goes to the caller.
        kv.key.rc_decrement();
        return make_some(kv.value);
    }
    return make_none();
}

fn map_clear(self: FatPtr) callconv(.c) FatPtr {
    const storage = deref_storage(self);
    // The entries go before the clear: a safe build fills a cleared region with
    // `undefined`, so a slice taken beforehand does not survive it.
    for (storage.map.keys()) |k| k.rc_decrement();
    for (storage.map.values()) |v| v.rc_decrement();
    storage.map.clearRetainingCapacity();
    return make_void();
}

fn map_key_eq(self: FatPtr, a: FatPtr, b: FatPtr) callconv(.c) FatPtr {
    return objs.call(deref_storage(self).keyEq, comptime h("read #/2"), .{ a, b }, @src());
}

/// Flow over an ordered internal slice (keys / values). `make_flow_from_items`
/// lends the slice and takes a reference of its own to each element, so the
/// map's references pass to it as they stand.
fn flow_over(items: []const FatPtr) FatPtr {
    const flow_rt = @import("root").flow_rt;
    return flow_rt.make_flow_from_items(items);
}

fn map_keys(self: FatPtr) callconv(.c) FatPtr {
    return flow_over(deref_storage(self).map.keys());
}
fn map_values(self: FatPtr) callconv(.c) FatPtr {
    return flow_over(deref_storage(self).map.values());
}

/// `imm .flow` / `read .flow` / `mut .flowMut` -- a flow of `Entry` objects in
/// insertion order. `make_entry` shares the key/value into each entry (the
/// storage keeps its refs); the entries themselves are handed to the flow.
fn map_flow_entries(self: FatPtr) callconv(.c) FatPtr {
    const flow_rt = @import("root").flow_rt;
    const storage = deref_storage(self);
    const keys = storage.map.keys();
    const vals = storage.map.values();
    const n = keys.len;
    if (n == 0) return flow_rt.make_flow_from_items(&.{});
    const entries = gc.recycleAllocSlice(FatPtr, n);
    defer gc.free(@ptrCast(entries.ptr));
    for (0..n) |i| entries[i] = make_entry(keys[i], vals[i]);
    return flow_rt.make_flow_from_items(entries);
}

pub const VT_LinkedHashMap: objs.VTable = .{
    .type_name = "base.LinkedHashMap/2",
    .hashes = &.{
        h("mut +/2"),        h("mut .put/2"),     h("read .isEmpty/0"),
        h("mut .get/1"),     h("read .get/1"),    h("imm .get/1"),
        h("mut .remove/1"),  h("mut .clear/0"),   h("read .keyEq/2"),
        h("mut .flowMut/0"), h("imm .flow/0"),    h("read .flow/0"),
        h("read .keys/0"),   h("mut .values/0"),  h("read .values/0"),
        h("imm .values/0"),
    },
    .methods = &.{
        @ptrCast(&map_plus),          @ptrCast(&map_put),    @ptrCast(&map_is_empty),
        @ptrCast(&map_get),           @ptrCast(&map_get),    @ptrCast(&map_get),
        @ptrCast(&map_remove),        @ptrCast(&map_clear),  @ptrCast(&map_key_eq),
        @ptrCast(&map_flow_entries),  @ptrCast(&map_flow_entries), @ptrCast(&map_flow_entries),
        @ptrCast(&map_keys),          @ptrCast(&map_values), @ptrCast(&map_values),
        @ptrCast(&map_values),
    },
    .method_names = &.{
        "mut +/2",        "mut .put/2",     "read .isEmpty/0",
        "mut .get/1",     "read .get/1",    "imm .get/1",
        "mut .remove/1",  "mut .clear/0",   "read .keyEq/2",
        "mut .flowMut/0", "imm .flow/0",    "read .flow/0",
        "read .keys/0",   "mut .values/0",  "read .values/0",
        "imm .values/0",
    },
    .drop_fn = map_drop,
    .free_fn = map_free,
    .trace_fn = map_trace,
};

/// `Maps.hashMap(keyEq, hashFn): mut LinkedHashMap`. Both closures are on loan
/// and the storage keeps them, so it takes a reference of its own to each and
/// releases them on map drop.
fn maps_hashmap(self: FatPtr, keyEq: FatPtr, hashFn: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const storage = gc.recycleAlloc(MapStorage);
    storage.* = .{
        .map = .empty,
        .keyEq = keyEq.share().box_transient(),
        .hashFn = hashFn.share().box_transient(),
    };
    return objs.obj_k(MapCaptures, &VT_LinkedHashMap, .{ .storage_ptr = @intFromPtr(storage) });
}

pub const VT_Maps: objs.VTable = .{
    .type_name = "base.Maps/0",
    .hashes = &.{h("imm .hashMap/2")},
    .methods = &.{@ptrCast(&maps_hashmap)},
    .method_names = &.{"imm .hashMap/2"},
    .storage_mode = .singleton,
};
