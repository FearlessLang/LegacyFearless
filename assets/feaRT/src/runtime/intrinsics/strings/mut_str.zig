const objs = @import("../../objs.zig");
const str = @import("str.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

/// Mutable string: a GC-allocated growable buffer mutated in place on the heap
/// object. `len` bytes are live; `cap` bytes are allocated.
pub const MutStrCaptures = extern struct {
    buf_ptr: usize, // [*]u8 stored as usize
    len: i64,
    cap: i64,
};

/// Mutable view of a `VT_MutStr`'s captures, for in-place append/clear.
fn deref_mut_caps(fp: FatPtr) *MutStrCaptures {
    const Layout = objs.GenObjectLayoutType(MutStrCaptures);
    const self: *Layout = @ptrCast(@alignCast(fp.boxed_value()));
    return &self.captures;
}

/// Create a mutable string seeded with `s` (used by `mut ""` and `mut "lit"`).
pub fn make_mut_str_from_literal(comptime s: []const u8) FatPtr {
    return make_mut_str_bytes(s);
}

pub fn make_mut_str_bytes(seed: []const u8) FatPtr {
    const cap = @max(@as(usize, 16), seed.len);
    const buf = str.alloc_bytes(cap);
    @memcpy(buf[0..seed.len], seed);
    return objs.obj_k(MutStrCaptures, &VT_MutStr, .{
        .buf_ptr = @intFromPtr(buf.ptr),
        .len = @intCast(seed.len),
        .cap = @intCast(cap),
    });
}

fn mut_str_ensure(caps: *MutStrCaptures, additional: usize) void {
    const len: usize = @intCast(caps.len);
    const cap: usize = @intCast(caps.cap);
    if (cap - len >= additional) return;
    const grown = cap * 3 / 2 + 1;
    const new_cap = @max(len + additional, grown);
    const old: [*]u8 = @ptrFromInt(caps.buf_ptr);
    const new_buf = str.alloc_bytes(new_cap);
    @memcpy(new_buf[0..len], old[0..len]);
    str.free_bytes(old);
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
    mut_str_append_bytes(deref_mut_caps(self), str.deref_str(other_str));
    return str.make_void();
}

/// `mut +(other): mut Str` — append and return self.
fn mut_str_plus(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    const other_str = objs.call(other, comptime h("read .str/0"), .{}, @src());
    defer other_str.rc_decrement();
    mut_str_append_bytes(deref_mut_caps(self), str.deref_str(other_str));
    return self; // transfer our receiver reference back to the caller
}

/// `mut .clear: Void` — reset length, keep capacity. Snapshots taken via `.str`
/// are copies and stay unaffected.
fn mut_str_clear(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    deref_mut_caps(self).len = 0;
    return str.make_void();
}

/// `read .str: Str` — an immutable snapshot copy, decoupled from later mutation.
fn mut_str_str(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return str.make_str_copy(str.deref_str(self));
}

fn mut_str_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(MutStrCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    str.free_bytes(@ptrFromInt(self.captures.buf_ptr));
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
        @ptrCast(&str.str_concat),     @ptrCast(&str.str_eq),         @ptrCast(&str.str_neq),
        @ptrCast(&str.str_size),       @ptrCast(&str.str_is_empty),   @ptrCast(&str.str_starts_with),
        @ptrCast(&str.str_substring),  @ptrCast(&str.str_char_at),    @ptrCast(&str.str_normalise),
        @ptrCast(&str.str_codepoints), @ptrCast(&str.str_graphemes),  @ptrCast(&str.str_utf8),
        @ptrCast(&str.str_float),      @ptrCast(&str.str_hash),       @ptrCast(&str.str_join),
        @ptrCast(&mut_str_str),        @ptrCast(&mut_str_append),     @ptrCast(&mut_str_plus),
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
