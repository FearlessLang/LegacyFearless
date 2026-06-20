const std = @import("std");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");
const nat_rt = @import("nat.zig");
const hash_rt = @import("hash.zig");
const bool_intrinsics = @import("bool.zig");
const root = @import("root");
const pb = root.pkg_base;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

// `base.LinkedHashMap` — an insertion-ordered map. The backing is an
// array-backed hash map (`std.ArrayHashMapUnmanaged`): the array gives
// insertion order, the hash index gives O(1) get/put/remove. Keys are hashed and
// compared through the Fearless `hashFn`/`keyEq` closures the program supplied,
// mirroring the Java backend's `LinkedHashMap.Key`. `store_hash = true` caches
// each key's u32 hash in the table, so those closures run once per operation
// rather than once per probe.

// ==========================================
// Opt / Void helpers
// ==========================================

fn make_some(item: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Opts_0), comptime h("imm #/1"), .{item}, @src());
}
fn make_none() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Opt_1);
}
fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

// ==========================================
// Entry
// ==========================================

pub const EntryCaptures = extern struct { key: FatPtr, value: FatPtr };

fn entry_key(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return objs.deref(EntryCaptures, self).key.share();
}
fn entry_value(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
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

// ==========================================
// Hash-map context
// ==========================================

/// A by-value view of a `MapStorage`'s closures, handed to the std map for the
/// duration of one operation. It holds *non-owning* copies of `keyEq`/`hashFn`
/// (same data/vt as the storage's owned refs), so `hash`/`eql` must `.share()`
/// before each `objs.call` — `call` consumes its receiver/args, and we must not
/// drain the storage's references.
pub const MapCtx = struct {
    keyEq: FatPtr,
    hashFn: FatPtr,

    /// `Key.hashCode`: build a `ToHash` from the key, feed it a fresh
    /// `CheapHash`, then truncate the computed u64 to u32 (the Java backend's
    /// `Math.toIntExact` over an `int` hash).
    pub fn hash(self: MapCtx, key: FatPtr) u32 {
        const to_hash = objs.call(self.hashFn.share(), comptime h("read #/1"), .{key.share()}, @src());
        const hashed = objs.call(to_hash, comptime h("read .hash/1"), .{hash_rt.make_cheap_hash()}, @src());
        const n = objs.call(hashed, comptime h("mut .compute/0"), .{}, @src());
        return @truncate(nat_rt.deref(n));
    }

    /// `Key.equals`: the program's `keyEq` closure decides. `a`/`b` are the
    /// map's stored / probe keys; neither is consumed.
    pub fn eql(self: MapCtx, a: FatPtr, b: FatPtr, b_index: usize) bool {
        _ = b_index;
        const res = objs.call(self.keyEq.share(), comptime h("read #/2"), .{ a.share(), b.share() }, @src());
        return res.vt == &pb.VT_True_0;
    }
};

const MapType = std.ArrayHashMapUnmanaged(FatPtr, FatPtr, MapCtx, true);

// ==========================================
// Map storage
// ==========================================

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

fn map_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(MapCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    const storage: *MapStorage = @ptrFromInt(self.captures.storage_ptr);
    for (storage.map.keys()) |k| k.rc_decrement();
    for (storage.map.values()) |v| v.rc_decrement();
    storage.map.deinit(gc.allocator);
    storage.keyEq.rc_decrement();
    storage.hashFn.rc_decrement();
    gc.recycleDestroy(MapStorage, storage, .map_release);
}

/// Insert or replace; takes ownership of `key` and `val`.
fn map_insert(storage: *MapStorage, key: FatPtr, val: FatPtr) void {
    const gop = storage.map.getOrPutContext(gc.allocator, key, ctx_of(storage)) catch @panic("OOM");
    if (gop.found_existing) {
        gop.value_ptr.*.rc_decrement(); // release the replaced value
        gop.value_ptr.* = val;
        key.rc_decrement(); // key already present; keep the stored one
    } else {
        // getOrPutContext already wrote `key` into key_ptr.
        gop.value_ptr.* = val;
    }
}

// ==========================================
// LinkedHashMap methods
// ==========================================

fn map_plus(self: FatPtr, k: FatPtr, v: FatPtr) callconv(.c) FatPtr {
    map_insert(deref_storage(self), k, v);
    return self; // `mut +` returns the same mutable map
}

fn map_put(self: FatPtr, k: FatPtr, v: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    map_insert(deref_storage(self), k, v);
    return make_void();
}

fn map_is_empty(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return bool_intrinsics.to_bool(deref_storage(self).map.count() == 0);
}

fn map_get(self: FatPtr, key: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer key.rc_decrement();
    const storage = deref_storage(self);
    if (storage.map.getContext(key, ctx_of(storage))) |v| return make_some(v.share());
    return make_none();
}

fn map_remove(self: FatPtr, key: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer key.rc_decrement();
    const storage = deref_storage(self);
    if (storage.map.fetchOrderedRemoveContext(key, ctx_of(storage))) |kv| {
        kv.key.rc_decrement(); // release the stored key; hand the value to the caller
        return make_some(kv.value);
    }
    return make_none();
}

fn map_clear(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const storage = deref_storage(self);
    for (storage.map.keys()) |k| k.rc_decrement();
    for (storage.map.values()) |v| v.rc_decrement();
    storage.map.clearRetainingCapacity();
    return make_void();
}

fn map_key_eq(self: FatPtr, a: FatPtr, b: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return objs.call(deref_storage(self).keyEq.share(), comptime h("read #/2"), .{ a, b }, @src());
}

// ==========================================
// Flows
// ==========================================

/// Flow over an ordered internal slice (keys / values). `make_flow_from_items`
/// takes ownership and does NOT share, so we copy + `.share()` each element
/// into a temporary slice it can consume.
fn flow_over(items: []const FatPtr) FatPtr {
    const flow_rt = @import("root").flow_rt;
    if (items.len == 0) return flow_rt.make_flow_from_items(&.{});
    const copy = gc.recycleAllocSlice(FatPtr, items.len);
    defer gc.free(@ptrCast(copy.ptr));
    for (items, 0..) |it, i| copy[i] = it.share();
    return flow_rt.make_flow_from_items(copy);
}

fn map_keys(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return flow_over(deref_storage(self).map.keys());
}
fn map_values(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return flow_over(deref_storage(self).map.values());
}

/// `imm .flow` / `read .flow` / `mut .flowMut` — a flow of `Entry` objects in
/// insertion order. `make_entry` shares the key/value into each entry (the
/// storage keeps its refs); the entries themselves are handed to the flow.
fn map_flow_entries(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
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
};

// ==========================================
// Maps factory
// ==========================================

/// `Maps.hashMap(keyEq, hashFn): mut LinkedHashMap`. Takes ownership of both
/// closures (stored in the map storage, released on map drop).
fn maps_hashmap(self: FatPtr, keyEq: FatPtr, hashFn: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement(); // singleton: no-op
    const storage = gc.recycleAlloc(MapStorage);
    storage.* = .{
        .map = .empty,
        .keyEq = keyEq,
        .hashFn = hashFn,
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
