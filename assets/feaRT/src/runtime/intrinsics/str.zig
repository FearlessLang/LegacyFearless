const std = @import("std");
const objs = @import("../objs.zig");
const bool_intrinsics = @import("bool.zig");
const int_intrinsics = @import("int.zig");
const nat_intrinsics = @import("nat.zig");
const byte_intrinsics = @import("byte.zig");
const list_intrinsics = @import("list.zig");
const gc = @import("../gc.zig");
const root = @import("root");
const pb = root.pkg_base;

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;
const h = objs.hash_signature;

// ==========================================
// Captures
// ==========================================

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

/// Mutable string: a GC-allocated growable buffer mutated in place on the heap
/// object. `len` bytes are live; `cap` bytes are allocated.
pub const MutStrCaptures = extern struct {
    buf_ptr: usize, // [*]u8 stored as usize
    len: i64,
    cap: i64,
};

// ==========================================
// Slice access (shared by both string kinds)
// ==========================================

/// The live UTF-8 bytes of any Fearless string, immutable or mutable. Read-only
/// thunks use this so they can be registered on both `VT_Str` and `VT_MutStr`.
pub fn deref_str(fp: FatPtr) []const u8 {
    if (fp.vt == &VT_MutStr) {
        const caps = objs.deref(MutStrCaptures, fp);
        const ptr: [*]const u8 = @ptrFromInt(caps.buf_ptr);
        return ptr[0..@intCast(caps.len)];
    }
    const caps = objs.deref(StrCaptures, fp);
    const ptr: [*]const u8 = @ptrFromInt(caps.ptr);
    return ptr[0..@intCast(caps.len)];
}

/// Mutable view of a `VT_MutStr`'s captures, for in-place append/clear.
fn deref_mut_caps(fp: FatPtr) *MutStrCaptures {
    const Layout = objs.GenObjectLayoutType(MutStrCaptures);
    const self: *Layout = @ptrCast(@alignCast(fp.boxed_value()));
    return &self.captures;
}

// ==========================================
// Constructors
// ==========================================

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
fn make_str_full(ptr: [*]const u8, len: usize, owns: Ownership, owner: FatPtr) FatPtr {
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
fn alloc_bytes(len: usize) []u8 {
    return gc.recycleAllocSlice(u8, len);
}

/// Release a string-data buffer through the destroyer worker — a batched,
/// off-thread `GC_free` — rather than a synchronous `GC_free` that would take
/// the global alloc lock on the calling thread.
fn free_bytes(ptr: [*]u8) void {
    gc.free(@ptrCast(ptr));
}

/// Copy `bytes` into a fresh buffer and wrap it as an owned immutable string.
/// An empty input needs no allocation — return a borrowed empty literal.
fn make_str_copy(bytes: []const u8) FatPtr {
    if (bytes.len == 0) return make_str("".ptr, 0);
    const buf = alloc_bytes(bytes.len);
    @memcpy(buf, bytes);
    return make_owned_str(buf.ptr, bytes.len);
}

/// Create a mutable string seeded with `s` (used by `mut ""` and `mut "lit"`).
pub fn make_mut_str_from_literal(comptime s: []const u8) FatPtr {
    return make_mut_str_bytes(s);
}

fn make_mut_str_bytes(seed: []const u8) FatPtr {
    const cap = @max(@as(usize, 16), seed.len);
    const buf = alloc_bytes(cap);
    @memcpy(buf[0..seed.len], seed);
    return objs.obj_k(MutStrCaptures, &VT_MutStr, .{
        .buf_ptr = @intFromPtr(buf.ptr),
        .len = @intCast(seed.len),
        .cap = @intCast(cap),
    });
}

// ==========================================
// Small Fearless-object helpers (Action / Info)
// ==========================================

fn make_info_msg(msg: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Infos_0), comptime h("imm .msg/1"), .{msg}, @src());
}
fn make_action_ok(x: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Actions_0), comptime h("imm .ok/1"), .{x}, @src());
}
fn make_action_info(info: FatPtr) FatPtr {
    return objs.call(objs.obj_k_singleton(&pb.VT_Actions_0), comptime h("imm .info/1"), .{info}, @src());
}

/// Raise a deterministic `FearlessError` carrying `Infos.msg(<msg>)`.
fn raise(comptime msg: []const u8) noreturn {
    const errors = @import("root").errors;
    errors.throwDeterministic(make_info_msg(make_str_from_literal(msg)));
}

// ==========================================
// Codepoint scanning helpers
// ==========================================

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

// ==========================================
// Read-only methods (shared by VT_Str + VT_MutStr)
// ==========================================

/// `+(other: read Stringable): Str` — coerce `other` via `.str`, then build a
/// fresh owned immutable string. Always immutable, even on a mutable receiver.
fn str_concat(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
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

fn str_eq(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer other.rc_decrement();
    return bool_intrinsics.to_bool(std.mem.eql(u8, deref_str(self), deref_str(other)));
}

fn str_neq(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer other.rc_decrement();
    return bool_intrinsics.to_bool(!std.mem.eql(u8, deref_str(self), deref_str(other)));
}

fn str_size(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return nat_intrinsics.make(codepoint_count(deref_str(self)));
}

fn str_is_empty(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return bool_intrinsics.to_bool(deref_str(self).len == 0);
}

fn str_starts_with(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer other.rc_decrement();
    const a = deref_str(self);
    const b = deref_str(other);
    return bool_intrinsics.to_bool(a.len >= b.len and std.mem.eql(u8, a[0..b.len], b));
}

/// `.substring(start, end)` — codepoint-indexed. Immutable receivers yield a
/// zero-copy shared slice that keeps the receiver alive; see `slice_of`.
fn str_substring(self: FatPtr, start_fp: FatPtr, end_fp: FatPtr) callconv(.c) FatPtr {
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
fn slice_of(self: FatPtr, data: []const u8, start_byte: usize, end_byte: usize) FatPtr {
    const sub = data[start_byte..end_byte];
    if (self.vt == &VT_MutStr) return make_str_copy(sub);
    return make_shared_substr(self, sub.ptr, sub.len);
}

fn str_char_at(self: FatPtr, index_fp: FatPtr) callconv(.c) FatPtr {
    const index = nat_intrinsics.deref(index_fp);
    return str_substring(self, index_fp, nat_intrinsics.make(index + 1));
}

/// `.normalise` — NFC. Reuses the input when already normalised.
fn str_normalise(self: FatPtr) callconv(.c) FatPtr {
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
fn str_utf8(self: FatPtr) callconv(.c) FatPtr {
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
fn str_hash(self: FatPtr, hasher: FatPtr) callconv(.c) FatPtr {
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
fn str_float(self: FatPtr) callconv(.c) FatPtr {
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
    return @import("float.zig").make(v);
}

/// `.codepoints` / `.graphemes` — a `Flow[Str]` over the string's units. The
/// receiver is the flow's source owner (keeps the shared buffer alive).
///
/// These return a `Flow`, which lives in `base.flows`; building one forces the
/// flow runtime's `VT_Flow` and its `pkg_base_flows` references. A minimal base
/// without `base.flows` cannot reach these (no `Flow` type exists), so they are
/// comptime-elided there.
fn str_codepoints(self: FatPtr) callconv(.c) FatPtr {
    if (comptime @hasDecl(root, "pkg_base_flows")) {
        defer self.rc_decrement();
        const flow_rt = @import("root").flow_rt;
        return flow_rt.make_flow_from_str(self.share(), deref_str(self), .codepoint);
    }
    unreachable;
}
fn str_graphemes(self: FatPtr) callconv(.c) FatPtr {
    if (comptime @hasDecl(root, "pkg_base_flows")) {
        defer self.rc_decrement();
        const flow_rt = @import("root").flow_rt;
        return flow_rt.make_flow_from_str(self.share(), deref_str(self), .grapheme);
    }
    unreachable;
}

/// Join a `Flow[Str]` using the receiver as the separator. Materialises the flow
/// into a List, then concatenates with the separator between elements.
fn str_join(separator: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
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

// ==========================================
// VT_Str (immutable)
// ==========================================

fn str_str_self(self: FatPtr) callconv(.c) FatPtr {
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

// ==========================================
// VT_MutStr (mutable)
// ==========================================

fn mut_str_ensure(caps: *MutStrCaptures, additional: usize) void {
    const len: usize = @intCast(caps.len);
    const cap: usize = @intCast(caps.cap);
    if (cap - len >= additional) return;
    const grown = cap * 3 / 2 + 1;
    const new_cap = @max(len + additional, grown);
    const old: [*]u8 = @ptrFromInt(caps.buf_ptr);
    const new_buf = alloc_bytes(new_cap);
    @memcpy(new_buf[0..len], old[0..len]);
    free_bytes(old);
    caps.buf_ptr = @intFromPtr(new_buf.ptr);
    caps.cap = @intCast(new_cap);
}

fn mut_str_append_bytes(caps: *MutStrCaptures, src: []const u8) void {
    mut_str_ensure(caps, src.len);
    const buf: [*]u8 = @ptrFromInt(caps.buf_ptr);
    const len: usize = @intCast(caps.len);
    @memcpy(buf[len .. len + src.len], src);
    caps.len += @intCast(src.len);
}

/// `mut .append(other: read Stringable): Void` — append `other.str`.
fn mut_str_append(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const other_str = objs.call(other, comptime h("read .str/0"), .{}, @src());
    defer other_str.rc_decrement();
    mut_str_append_bytes(deref_mut_caps(self), deref_str(other_str));
    return make_void();
}

/// `mut +(other): mut Str` — append and return self.
fn mut_str_plus(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    const other_str = objs.call(other, comptime h("read .str/0"), .{}, @src());
    defer other_str.rc_decrement();
    mut_str_append_bytes(deref_mut_caps(self), deref_str(other_str));
    return self; // transfer our receiver reference back to the caller
}

/// `mut .clear: Void` — reset length, keep capacity. Snapshots taken via `.str`
/// are copies and stay unaffected.
fn mut_str_clear(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    deref_mut_caps(self).len = 0;
    return make_void();
}

/// `read .str: Str` — an immutable snapshot copy, decoupled from later mutation.
fn mut_str_str(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return make_str_copy(deref_str(self));
}

fn mut_str_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(MutStrCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    free_bytes(@ptrFromInt(self.captures.buf_ptr));
}

pub const VT_MutStr: objs.VTable = .{
    .type_name = "base.Str/0",
    .hashes = &.{
        // Read surface (shared thunks)
        h("imm +/1"),          h("imm ==/1"),         h("imm !=/1"),
        h("imm .size/0"),      h("read .isEmpty/0"),  h("imm .startsWith/1"),
        h("imm .substring/2"), h("imm .charAt/1"),    h("imm .normalise/0"),
        h("imm .codepoints/0"), h("imm .graphemes/0"), h("imm .utf8/0"),
        h("imm .float/0"),     h("read .hash/1"),     h("imm .join/1"),
        // Mutable surface + snapshot .str
        h("read .str/0"),      h("mut .append/1"),    h("mut +/1"),
        h("mut .clear/0"),
    },
    .methods = &.{
        @ptrCast(&str_concat),      @ptrCast(&str_eq),         @ptrCast(&str_neq),
        @ptrCast(&str_size),        @ptrCast(&str_is_empty),   @ptrCast(&str_starts_with),
        @ptrCast(&str_substring),   @ptrCast(&str_char_at),    @ptrCast(&str_normalise),
        @ptrCast(&str_codepoints),  @ptrCast(&str_graphemes),  @ptrCast(&str_utf8),
        @ptrCast(&str_float),       @ptrCast(&str_hash),       @ptrCast(&str_join),
        @ptrCast(&mut_str_str),     @ptrCast(&mut_str_append), @ptrCast(&mut_str_plus),
        @ptrCast(&mut_str_clear),
    },
    .method_names = &.{
        "imm +/1",          "imm ==/1",         "imm !=/1",
        "imm .size/0",      "read .isEmpty/0",  "imm .startsWith/1",
        "imm .substring/2", "imm .charAt/1",    "imm .normalise/0",
        "imm .codepoints/0", "imm .graphemes/0", "imm .utf8/0",
        "imm .float/0",     "read .hash/1",     "imm .join/1",
        "read .str/0",      "mut .append/1",    "mut +/1",
        "mut .clear/0",
    },
    .drop_fn = mut_str_drop,
};

// ==========================================
// UTF16 / UTF8 singletons
// ==========================================

/// Encode a Unicode scalar as UTF-8 into a fresh owned string. Invalid scalars
/// (surrogates / out of range) fall back to U+FFFD, matching how the Java
/// backend's `new String(int[]{cp})` round-trips unmappable code points.
fn encode_scalar(cp: u32) FatPtr {
    var buf: [4]u8 = undefined;
    const scalar: u21 = if (cp <= 0x10FFFF) @intCast(cp) else 0xFFFD;
    const n = std.unicode.utf8Encode(scalar, &buf) catch std.unicode.utf8Encode(0xFFFD, &buf) catch unreachable;
    return make_str_copy(buf[0..n]);
}

fn utf16_from_code_point(self: FatPtr, cp_fp: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return encode_scalar(@intCast(nat_intrinsics.deref(cp_fp)));
}

fn utf16_from_surrogate_pair(self: FatPtr, high_fp: FatPtr, low_fp: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const high = nat_intrinsics.deref(high_fp);
    const low = nat_intrinsics.deref(low_fp);
    // const cp: u32 = @intCast(0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00));
    const cp: u32 = if (high >= 0xD800 and high <= 0xDBFF and low >= 0xDC00 and low <= 0xDFFF)
        @intCast(0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00))
    else
        0xFFFD;
    return encode_scalar(cp);
}

fn utf16_is_surrogate(self: FatPtr, cp_fp: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const cp = nat_intrinsics.deref(cp_fp);
    return bool_intrinsics.to_bool(cp >= 0xD800 and cp < 0xE000);
}

pub const VT_UTF16: objs.VTable = .{
    .type_name = "base.UTF16/0",
    .hashes = &.{
        h("imm .fromCodePoint/1"),
        h("imm .fromSurrogatePair/2"),
        h("imm .isSurrogate/1"),
    },
    .methods = &.{
        @ptrCast(&utf16_from_code_point),
        @ptrCast(&utf16_from_surrogate_pair),
        @ptrCast(&utf16_is_surrogate),
    },
    .method_names = &.{
        "imm .fromCodePoint/1",
        "imm .fromSurrogatePair/2",
        "imm .isSurrogate/1",
    },
    .storage_mode = .singleton,
};

/// `UTF8.fromBytes(list): Action[Str]` — validate the bytes as UTF-8.
fn utf8_from_bytes(self: FatPtr, list_fp: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer list_fp.rc_decrement();
    const al = list_intrinsics.deref_list(list_fp);
    if (al.items.len == 0) return make_action_ok(make_str("".ptr, 0));
    const buf = alloc_bytes(al.items.len);
    for (al.items, 0..) |item, i| buf[i] = byte_intrinsics.deref(item);
    if (std.unicode.utf8ValidateSlice(buf)) {
        return make_action_ok(make_owned_str(buf.ptr, buf.len));
    }
    free_bytes(buf.ptr);
    return make_action_info(make_info_msg(make_str_from_literal("Invalid UTF-8 byte sequence")));
}

pub const VT_UTF8: objs.VTable = .{
    .type_name = "base.UTF8/0",
    .hashes = &.{h("imm .fromBytes/1")},
    .methods = &.{@ptrCast(&utf8_from_bytes)},
    .method_names = &.{"imm .fromBytes/1"},
    .storage_mode = .singleton,
};

fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

// ==========================================
// Numeric → string (used by Int/Nat/Byte/Float intrinsics)
// ==========================================

/// Convert an Int/Nat (i64/u64) to a decimal string. Allocates via GC.
pub fn int_to_str(n: FatPtr) FatPtr {
    const uval: u64 = if (n.vt == &nat_intrinsics.VT_Nat) nat_intrinsics.deref(n) else @bitCast(int_intrinsics.deref(n));
    var buf: [20]u8 = undefined; // max u64 decimal digits
    var len: usize = 0;
    if (uval == 0) {
        buf[0] = '0';
        len = 1;
    } else {
        var tmp = uval;
        while (tmp > 0) : (len += 1) {
            buf[len] = @intCast('0' + (tmp % 10));
            tmp /= 10;
        }
        var i: usize = 0;
        var j: usize = len - 1;
        while (i < j) {
            const t = buf[i];
            buf[i] = buf[j];
            buf[j] = t;
            i += 1;
            j -= 1;
        }
    }
    return make_str_copy(buf[0..len]);
}

/// Format a Float (f64) exactly as Rust's `f64::to_string`, routed through the
/// native runtime so the output is byte-identical to the Java backend.
pub fn float_to_str(f: FatPtr) FatPtr {
    const native = @import("root").native;
    const float_intrinsics = @import("float.zig");
    const v = float_intrinsics.deref(f);
    var buf: [native.FRT_F64_STR_MAX]u8 = undefined;
    const needed = native.frt_f64_to_str(v, &buf, buf.len);
    std.debug.assert(needed <= buf.len);
    return make_str_copy(buf[0..needed]);
}
