const std = @import("std");
const objs = @import("../../objs.zig");
const bool_intrinsics = @import("../bool.zig");
const nat_intrinsics = @import("../nat.zig");
const byte_intrinsics = @import("../byte.zig");
const list_intrinsics = @import("../list.zig");
const gc = @import("../../gc.zig");
const root = @import("root");
const pb = root.pkg_base;

const mut_str = @import("mut_str.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;
const h = objs.hash_signature;

/// How an immutable string relates to the bytes it points at:
///   - `borrowed`: the bytes outlive this object independently (a rodata
///     literal); `str_drop` does nothing.
///   - `owned`: this object owns the buffer; `str_drop` frees it.
///   - `shared`: the bytes are a slice borrowed from `owner` (another Str whose
///     buffer this is a window into); `str_drop` releases one reference to
///     `owner`, which keeps the underlying buffer alive for exactly as long as
///     this sub-string needs it.
pub const Ownership = enum(u32) { borrowed = 0, owned = 1, shared = 2 };

/// Immutable string: pointer to UTF-8 data + byte length, plus an ownership tag
/// and (for `shared`) the owning Str.
///
/// `owner` is stored as the raw `data`/`vt` words of a `FatPtr` rather than a
/// `FatPtr` field so `obj_k` does NOT auto-share/auto-drop it — the owner is
/// refcounted by hand (`make_shared_substr` takes one reference, `str_drop`
/// releases it). For non-`shared` strings the words hold a singleton sentinel,
/// so reconstructing and releasing them is always a no-op.
pub const StrCaptures = extern struct {
    ptr: usize, // [*]const u8 stored as usize (extern struct can't hold non-extern ptrs)
    len: i64,
    owns: u32, // Ownership
    owner_data: usize, // owner FatPtr.data.obj as usize
    owner_vt: usize, // owner FatPtr.vt as usize
};

/// The live UTF-8 bytes of any Fearless string, immutable or mutable. Read-only
/// thunks use this so they can be registered on both `VT_Str` and `VT_MutStr`.
pub fn deref_str(fp: FatPtr) []const u8 {
    if (fp.vt == &mut_str.VT_MutStr) {
        const caps = objs.deref(mut_str.MutStrCaptures, fp);
        const ptr: [*]const u8 = @ptrFromInt(caps.buf_ptr);
        return ptr[0..@intCast(caps.len)];
    }
    const caps = objs.deref(StrCaptures, fp);
    const ptr: [*]const u8 = @ptrFromInt(caps.ptr);
    return ptr[0..@intCast(caps.len)];
}

/// A throwaway singleton used as the `owner` of non-`shared` strings; sharing
/// and releasing it are no-ops (singleton storage mode).
fn sentinel_owner() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

/// Reconstruct the owner `FatPtr` from its stored raw words. Only meaningful
/// (and only ever called) when `owns == shared`.
fn owner_from_words(caps: *const StrCaptures) FatPtr {
    return .{ .data = .{ .obj = @ptrFromInt(caps.owner_data) }, .vt = @ptrFromInt(caps.owner_vt) };
}

pub fn make_str(ptr: [*]const u8, len: usize) FatPtr {
    return make_str_full(ptr, len, .borrowed, sentinel_owner());
}

pub fn make_owned_str(ptr: [*]const u8, len: usize) FatPtr {
    return make_str_full(ptr, len, .owned, sentinel_owner());
}

/// A sub-string that shares its bytes with `owner`'s buffer. Takes one fresh
/// reference to `owner` (released in `str_drop`), so the borrowed slice stays
/// valid for as long as this sub-string lives, independently of every other
/// reference to `owner`.
pub fn make_shared_substr(owner: FatPtr, ptr: [*]const u8, len: usize) FatPtr {
    return make_str_full(ptr, len, .shared, owner.share());
}

/// `owner` is consumed by value: its words are stored verbatim. For `shared`
/// strings the caller must have already taken the reference it is donating
/// (see `make_shared_substr`); for the others `owner` is the no-op sentinel.
pub fn make_str_full(ptr: [*]const u8, len: usize, owns: Ownership, owner: FatPtr) FatPtr {
    return objs.obj_k(StrCaptures, &VT_Str, .{
        .ptr = @intFromPtr(ptr),
        .len = @as(i64, @intCast(len)),
        .owns = @intFromEnum(owns),
        .owner_data = @intFromPtr(owner.data.obj),
        .owner_vt = @intFromPtr(owner.vt),
    });
}

/// Create an immutable string from a comptime literal. The data lives in the
/// binary's rodata section (zero-copy, never freed).
pub fn make_str_from_literal(comptime s: []const u8) FatPtr {
    return make_str(s.ptr, s.len);
}

/// Allocate a `len`-byte buffer for string data. Routes small buffers through
/// the per-thread recycler pool and degrades to a fresh GC allocation for
/// larger ones, keeping the bdwgc alloc lock off the common small-string path.
pub fn alloc_bytes(len: usize) []u8 {
    return gc.recycleAllocSlice(u8, len);
}

/// Release a string-data buffer through the destroyer worker — a batched,
/// off-thread `GC_free` — rather than a synchronous `GC_free` that would take
/// the global alloc lock on the calling thread.
pub fn free_bytes(ptr: [*]u8) void {
    gc.free(@ptrCast(ptr));
}

/// Copy `bytes` into a fresh buffer and wrap it as an owned immutable string.
/// An empty input needs no allocation — return a borrowed empty literal.
pub fn make_str_copy(bytes: []const u8) FatPtr {
    if (bytes.len == 0) return make_str("".ptr, 0);
    const buf = alloc_bytes(bytes.len);
    @memcpy(buf, bytes);
    return make_owned_str(buf.ptr, bytes.len);
}

/// Wrap a message `Str` as a `base.Info` (mirrors `Infos.msg msg`). Consumes
/// the single reference held by `msg`.
pub fn make_info_msg(msg: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Infos_0), comptime h("imm .msg/1"), .{msg}, @src());
}
pub fn make_action_ok(x: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Actions_0), comptime h("imm .ok/1"), .{x}, @src());
}
pub fn make_action_info(info: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Actions_0), comptime h("imm .info/1"), .{info}, @src());
}

pub fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

/// Raise a deterministic `FearlessError` carrying `Infos.msg(<msg>)`.
pub fn raise(comptime msg: []const u8) noreturn {
    const errors = @import("root").errors;
    errors.throwDeterministic(make_info_msg(make_str_from_literal(msg)));
}

/// Number of Unicode codepoints: one per non-continuation byte.
fn codepoint_count(data: []const u8) u64 {
    var count: u64 = 0;
    for (data) |b| {
        if ((b & 0xC0) != 0x80) count += 1;
    }
    return count;
}

/// Byte offset of the start of codepoint index `cp`, scanning forward. `cp`
/// equal to the codepoint count returns the byte length. Mirrors Java
/// `Str.codepointByteOffset`.
fn codepoint_byte_offset(data: []const u8, cp: u64) usize {
    var seen: u64 = 0;
    for (data, 0..) |b, i| {
        if ((b & 0xC0) != 0x80) {
            if (seen == cp) return i;
            seen += 1;
        }
    }
    return data.len;
}

/// `+(other: read Stringable): Str` — coerce `other` via `.str`, then build a
/// fresh owned immutable string. Always immutable, even on a mutable receiver.
pub fn str_concat(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const other_str = objs.call(other, comptime h("read .str/0"), .{}, @src());
    defer other_str.rc_decrement();
    const sa = deref_str(self);
    const sb = deref_str(other_str);
    if (sa.len + sb.len == 0) return make_str("".ptr, 0);
    const buf = alloc_bytes(sa.len + sb.len);
    @memcpy(buf[0..sa.len], sa);
    @memcpy(buf[sa.len..], sb);
    return make_owned_str(buf.ptr, sa.len + sb.len);
}

pub fn str_eq(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer other.rc_decrement();
    return bool_intrinsics.to_bool(std.mem.eql(u8, deref_str(self), deref_str(other)));
}

pub fn str_neq(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer other.rc_decrement();
    return bool_intrinsics.to_bool(!std.mem.eql(u8, deref_str(self), deref_str(other)));
}

pub fn str_size(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return nat_intrinsics.make(codepoint_count(deref_str(self)));
}

pub fn str_is_empty(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return bool_intrinsics.to_bool(deref_str(self).len == 0);
}

pub fn str_starts_with(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer other.rc_decrement();
    const a = deref_str(self);
    const b = deref_str(other);
    return bool_intrinsics.to_bool(a.len >= b.len and std.mem.eql(u8, a[0..b.len], b));
}

/// `.substring(start, end)` — codepoint-indexed. Immutable receivers yield a
/// zero-copy shared slice that keeps the receiver alive; see `slice_of`.
pub fn str_substring(self: FatPtr, start_fp: FatPtr, end_fp: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const data = deref_str(self);
    const start = nat_intrinsics.deref(start_fp);
    const end = nat_intrinsics.deref(end_fp);
    if (start > end) raise("Start index must be less than end index");
    if (end > codepoint_count(data)) raise("End index must be less than the size of the string");
    const start_byte = codepoint_byte_offset(data, start);
    const end_byte = codepoint_byte_offset(data, end);
    return slice_of(self, data, start_byte, end_byte);
}

/// Build a sub-string of `self` over `data[start_byte..end_byte]`. Immutable
/// receivers (stable buffers) share the slice zero-copy and keep the receiver
/// alive (`make_shared_substr`); mutable receivers must copy, since their buffer
/// can be reallocated or freed by a later `.append`/`mut +`.
pub fn slice_of(self: FatPtr, data: []const u8, start_byte: usize, end_byte: usize) FatPtr {
    const sub = data[start_byte..end_byte];
    if (self.vt == &mut_str.VT_MutStr) return make_str_copy(sub);
    return make_shared_substr(self, sub.ptr, sub.len);
}

pub fn str_char_at(self: FatPtr, index_fp: FatPtr) callconv(.c) FatPtr {
    const index = nat_intrinsics.deref(index_fp);
    return str_substring(self, index_fp, nat_intrinsics.make(index + 1));
}

/// `.normalise` — NFC. Reuses the input when already normalised.
pub fn str_normalise(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const native = @import("root").native;
    const data = deref_str(self);
    var out: native.frt_buf = undefined;
    if (native.frt_str_nfc(data.ptr, data.len, &out)) {
        const n = out.len;
        const result = make_str_copy(out.ptr.?[0..n]);
        native.frt_buf_free(out);
        return result;
    }
    // Already NFC: return an owned snapshot so the result is independent of the
    // receiver (which may be a mutable string).
    return make_str_copy(data);
}

/// `.utf8` — a `List[Byte]` of the raw UTF-8 bytes.
///
/// Building a `List` forces `list.zig`'s `VT_List`, whose default-body
/// trampolines reference generated `pkg_base` List symbols. A minimal base that
/// never pulls in `List` lacks those symbols, so this method is comptime-elided
/// there (it is unreachable in such a build — nothing can produce a `List`).
pub fn str_utf8(self: FatPtr) callconv(.c) FatPtr {
    if (comptime @hasDecl(pb, "List_1__Zdotiter_0_mut_Zfun")) {
        defer self.rc_decrement();
        const data = deref_str(self);
        const storage = list_intrinsics.make_storage(data.len);
        for (data) |b| storage.al.appendAssumeCapacity(byte_intrinsics.make(b));
        return list_intrinsics.wrap_list_storage(storage);
    }
    unreachable;
}

/// `.hash(hasher)` — feed this string into the hasher, return the hasher.
pub fn str_hash(self: FatPtr, hasher: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return objs.call(hasher, comptime h("mut .str/1"), .{self.share()}, @src());
}

/// `.float` — parse as `f64` via the native runtime, returning a base
/// `Action[Float]` (ok / info). Eager parse: pure, so observably identical to a
/// lazy one.
///
/// The result wraps base `Action`/`Info`/`Float` types; a minimal base lacking
/// them (e.g. an imm program that never parses a float) comptime-elides this —
/// nothing there can produce the `Action[Float]` this returns.
pub fn str_float(self: FatPtr) callconv(.c) FatPtr {
    if (comptime @hasDecl(pb, "VT_Actions_0")) {
        defer self.rc_decrement();
        const native = @import("root").native;
        const data = deref_str(self);
        var out: f64 = undefined;
        if (native.frt_parse_f64(data.ptr, data.len, &out)) {
            return make_action_ok(float_make(out));
        }
        return make_action_info(make_info_msg(invalid_float_msg(data)));
    }
    unreachable;
}

/// `"Could not parse a Float from: <text>"` as a fresh owned Str, so the failure
/// `Info` names the offending input rather than a bare constant.
fn invalid_float_msg(data: []const u8) FatPtr {
    const prefix = "Could not parse a Float from: ";
    const buf = alloc_bytes(prefix.len + data.len);
    @memcpy(buf[0..prefix.len], prefix);
    @memcpy(buf[prefix.len..], data);
    return make_owned_str(buf.ptr, buf.len);
}

fn float_make(v: f64) FatPtr {
    return @import("../float.zig").make(v);
}

/// `.codepoints` / `.graphemes` — a `Flow[Str]` over the string's units. The
/// receiver is the flow's source owner (keeps the shared buffer alive).
///
/// These return a `Flow`, which lives in `base.flows`; building one forces the
/// flow runtime's `VT_Flow` and its `pkg_base_flows` references. A minimal base
/// without `base.flows` cannot reach these (no `Flow` type exists), so they are
/// comptime-elided there.
pub fn str_codepoints(self: FatPtr) callconv(.c) FatPtr {
    if (comptime @hasDecl(root, "pkg_base_flows")) {
        defer self.rc_decrement();
        const flow_rt = @import("root").flow_rt;
        return flow_rt.make_flow_from_str(self.share(), deref_str(self), .codepoint);
    }
    unreachable;
}
pub fn str_graphemes(self: FatPtr) callconv(.c) FatPtr {
    if (comptime @hasDecl(root, "pkg_base_flows")) {
        defer self.rc_decrement();
        const flow_rt = @import("root").flow_rt;
        return flow_rt.make_flow_from_str(self.share(), deref_str(self), .grapheme);
    }
    unreachable;
}

/// Join a `Flow[Str]` using the receiver as the separator. Materialises the flow
/// into a List, then concatenates with the separator between elements.
pub fn str_join(separator: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    const list = objs.call(flow, comptime h("mut .list/0"), .{}, @src());
    defer separator.rc_decrement();
    defer list.rc_decrement();

    const al = list_intrinsics.deref_list(list);
    const items = al.items;
    if (items.len == 0) return make_str("".ptr, 0);
    if (items.len == 1) return make_str_copy(deref_str(items[0]));

    const sep = deref_str(separator);
    var total_len: usize = 0;
    for (items) |item| total_len += deref_str(item).len;
    total_len += sep.len * (items.len - 1);
    if (total_len == 0) return make_str("".ptr, 0);

    const buf = alloc_bytes(total_len);
    var pos: usize = 0;
    for (items, 0..) |item, i| {
        if (i > 0) {
            @memcpy(buf[pos..][0..sep.len], sep);
            pos += sep.len;
        }
        const s = deref_str(item);
        @memcpy(buf[pos..][0..s.len], s);
        pos += s.len;
    }
    return make_owned_str(buf.ptr, total_len);
}

pub fn str_str_self(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return self.share();
}

fn str_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(StrCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    const caps = self.captures;
    switch (@as(Ownership, @enumFromInt(caps.owns))) {
        .borrowed => {},
        .owned => free_bytes(@ptrFromInt(caps.ptr)),
        .shared => owner_from_words(&caps).rc_decrement(),
    }
}

pub const VT_Str: objs.VTable = .{
    .type_name = "base.Str/0",
    .hashes = &.{
        h("imm +/1"),         h("imm ==/1"),        h("imm !=/1"),
        h("read .str/0"),     h("imm .size/0"),     h("read .isEmpty/0"),
        h("imm .startsWith/1"), h("imm .substring/2"), h("imm .charAt/1"),
        h("imm .normalise/0"), h("imm .codepoints/0"), h("imm .graphemes/0"),
        h("imm .utf8/0"),     h("imm .float/0"),    h("read .hash/1"),
        h("imm .join/1"),
    },
    .methods = &.{
        @ptrCast(&str_concat),      @ptrCast(&str_eq),        @ptrCast(&str_neq),
        @ptrCast(&str_str_self),    @ptrCast(&str_size),      @ptrCast(&str_is_empty),
        @ptrCast(&str_starts_with), @ptrCast(&str_substring), @ptrCast(&str_char_at),
        @ptrCast(&str_normalise),   @ptrCast(&str_codepoints), @ptrCast(&str_graphemes),
        @ptrCast(&str_utf8),        @ptrCast(&str_float),     @ptrCast(&str_hash),
        @ptrCast(&str_join),
    },
    .method_names = &.{
        "imm +/1",         "imm ==/1",        "imm !=/1",
        "read .str/0",     "imm .size/0",     "read .isEmpty/0",
        "imm .startsWith/1", "imm .substring/2", "imm .charAt/1",
        "imm .normalise/0", "imm .codepoints/0", "imm .graphemes/0",
        "imm .utf8/0",     "imm .float/0",    "read .hash/1",
        "imm .join/1",
    },
    .drop_fn = str_drop,
};
