const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const str_rt = @import("../strings/index.zig");
const list_rt = @import("../list.zig");
const readstr_rt = @import("readstr.zig");
const errors = @import("root").errors;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

/// A scoped path handle. Owns its resolved absolute bytes; `path_drop` frees
/// them. The three path capabilities (`ReadPath`/`WritePath`/`ReadWritePath`)
/// all share this representation and differ only by which methods they expose.
const PathCaptures = extern struct {
    ptr: usize, // [*]const u8 stored as usize
    len: i64,
};

fn path_inner(self: FatPtr) []const u8 {
    const caps = objs.deref(PathCaptures, self);
    const p: [*]const u8 = @ptrFromInt(caps.ptr);
    return p[0..@intCast(caps.len)];
}

fn path_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(PathCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    gc.free(@ptrFromInt(self.captures.ptr));
}

/// `bytes` is consumed: ownership of the buffer transfers to the new object.
fn make_path(bytes: []u8, comptime vt: *const objs.VTable) FatPtr {
    return objs.obj_k(PathCaptures, vt, .{ .ptr = @intFromPtr(bytes.ptr), .len = @intCast(bytes.len) });
}

/// The current working directory as freshly-allocated bytes (recycler buffer;
/// release with `gc.free`). The resolution root for the IO-level access methods
/// (the root capability).
fn cwd_alloc() []u8 {
    var buf: [std.fs.max_path_bytes]u8 = undefined;
    const rc = std.os.linux.getcwd(&buf, buf.len);
    const signed: isize = @bitCast(rc);
    if (signed <= 0) @panic("getcwd failed");
    // The raw syscall returns the length including the trailing NUL.
    const len: usize = @as(usize, @intCast(signed)) - 1;
    const out = gc.recycleAllocSlice(u8, len);
    @memcpy(out, buf[0..len]);
    return out;
}

/// Resolve the `List[Str]` `segments` against `root_bytes` into an owned,
/// absolute, normalised path (`.`/`..` collapsed). The result is GC-allocated;
/// release with `gc.free`. `root_bytes` must be absolute.
fn resolve_path(root_bytes: []const u8, segments: FatPtr) []u8 {
    const al = list_rt.deref_list(segments);
    const parts = gc.recycleAllocSlice([]const u8, al.items.len + 1);
    parts[0] = root_bytes;
    for (al.items, 0..) |item, i| parts[i + 1] = str_rt.deref_str(item);
    const resolved = std.fs.path.resolve(gc.allocator, parts) catch @panic("OOM resolving path");
    gc.free(@ptrCast(parts.ptr));
    return resolved;
}

/// Whether `candidate` lies within `base` (equal, or a descendant on a path
/// boundary). Both are normalised absolute paths, so a plain prefix test plus a
/// separator-boundary check is exact -- this closes the lexical `..` escape that
/// the Java `startsWith` check permits.
fn is_within(base: []const u8, candidate: []const u8) bool {
    if (!std.mem.startsWith(u8, candidate, base)) return false;
    if (candidate.len == base.len) return true;
    if (base.len > 0 and base[base.len - 1] == '/') return true;
    return candidate[base.len] == '/';
}

/// Resolve `segments` against `self`'s scope and enforce containment. Returns
/// owned resolved bytes on success; throws a deterministic error (Java-compatible
/// message) on escape. Never returns a path outside `self`.
fn scoped_resolve(self: FatPtr, segments: FatPtr) []u8 {
    const inner = path_inner(self);
    const resolved = resolve_path(inner, segments);
    if (!is_within(inner, resolved)) throw_scope_violation(inner, resolved);
    return resolved;
}

fn throw_scope_violation(inner: []const u8, resolved: []u8) noreturn {
    const prefix = "Expected the path to be scoped under '";
    const mid = "', but it resolved to '";
    const suffix = "'";
    const total = prefix.len + inner.len + mid.len + resolved.len + suffix.len;
    const buf = gc.recycleAllocSlice(u8, total);
    var pos: usize = 0;
    @memcpy(buf[pos..][0..prefix.len], prefix);
    pos += prefix.len;
    @memcpy(buf[pos..][0..inner.len], inner);
    pos += inner.len;
    @memcpy(buf[pos..][0..mid.len], mid);
    pos += mid.len;
    @memcpy(buf[pos..][0..resolved.len], resolved);
    pos += resolved.len;
    @memcpy(buf[pos..][0..suffix.len], suffix);
    gc.free(@ptrCast(resolved.ptr));
    errors.throwDeterministic(str_rt.make_info_msg(str_rt.make_owned_str(buf.ptr, buf.len)));
}

/// IO-level access: resolve `segments` (borrowed) against the CWD and wrap as the
/// corresponding path capability. No containment check -- this is the root.
pub fn rw_from_cwd(segments: FatPtr) FatPtr {
    const cwd = cwd_alloc();
    defer gc.free(@ptrCast(cwd.ptr));
    return make_path(resolve_path(cwd, segments), &VT_ReadWritePath);
}
pub fn read_from_cwd(segments: FatPtr) FatPtr {
    const cwd = cwd_alloc();
    defer gc.free(@ptrCast(cwd.ptr));
    return make_path(resolve_path(cwd, segments), &VT_ReadPath);
}
pub fn write_from_cwd(segments: FatPtr) FatPtr {
    const cwd = cwd_alloc();
    defer gc.free(@ptrCast(cwd.ptr));
    return make_path(resolve_path(cwd, segments), &VT_WritePath);
}

fn path_accessRW(self: FatPtr, segments: FatPtr) callconv(.c) FatPtr {
    defer segments.rc_decrement();
    return make_path(scoped_resolve(self, segments), &VT_ReadWritePath);
}
fn path_accessR(self: FatPtr, segments: FatPtr) callconv(.c) FatPtr {
    defer segments.rc_decrement();
    return make_path(scoped_resolve(self, segments), &VT_ReadPath);
}
fn path_accessW(self: FatPtr, segments: FatPtr) callconv(.c) FatPtr {
    defer segments.rc_decrement();
    return make_path(scoped_resolve(self, segments), &VT_WritePath);
}

fn path_readStr(self: FatPtr) callconv(.c) FatPtr {
    return readstr_rt.make_action(path_inner(self));
}

/// `ToIso` for path handles. Heap objects, and the receiver is lent, so each of these
/// answers with a share of it rather than with the loan itself.
fn path_iso(self: FatPtr) callconv(.c) FatPtr {
    return self.share();
}
fn path_self(self: FatPtr) callconv(.c) FatPtr {
    return self.share();
}

pub const VT_ReadWritePath: objs.VTable = .{
    .type_name = "base.caps.ReadWritePath/0",
    .hashes = &.{
        h("mut .accessRW/1"), h("mut .accessR/1"), h("mut .accessW/1"),
        h("mut .readStr/0"),  h("mut .iso/0"),     h("mut .self/0"),
    },
    .methods = &.{
        @ptrCast(&path_accessRW), @ptrCast(&path_accessR), @ptrCast(&path_accessW),
        @ptrCast(&path_readStr),  @ptrCast(&path_iso),     @ptrCast(&path_self),
    },
    .method_names = &.{
        "mut .accessRW/1", "mut .accessR/1", "mut .accessW/1",
        "mut .readStr/0",  "mut .iso/0",     "mut .self/0",
    },
    .drop_fn = path_drop,
};

pub const VT_ReadPath: objs.VTable = .{
    .type_name = "base.caps.ReadPath/0",
    .hashes = &.{
        h("mut .accessR/1"), h("mut .readStr/0"), h("mut .iso/0"), h("mut .self/0"),
    },
    .methods = &.{
        @ptrCast(&path_accessR), @ptrCast(&path_readStr), @ptrCast(&path_iso), @ptrCast(&path_self),
    },
    .method_names = &.{
        "mut .accessR/1", "mut .readStr/0", "mut .iso/0", "mut .self/0",
    },
    .drop_fn = path_drop,
};

pub const VT_WritePath: objs.VTable = .{
    .type_name = "base.caps.WritePath/0",
    .hashes = &.{
        h("mut .accessW/1"), h("mut .iso/0"), h("mut .self/0"),
    },
    .methods = &.{
        @ptrCast(&path_accessW), @ptrCast(&path_iso), @ptrCast(&path_self),
    },
    .method_names = &.{
        "mut .accessW/1", "mut .iso/0", "mut .self/0",
    },
    .drop_fn = path_drop,
};
