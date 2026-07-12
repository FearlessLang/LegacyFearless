const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const str_rt = @import("../strings/index.zig");
const actions = @import("../../conversions/actions.zig");
const reactor = @import("../../io/reactor.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

/// First read buffer size, doubled on demand as the file grows past it.
const INITIAL_CAP: usize = 64 * 1024;

/// `-errno` for a missing file, matched against the reactor's `openat` result to
/// pick the "File not found" vs "Could not read file" message.
const ENOENT: i32 = -@as(i32, @intCast(@intFromEnum(std.posix.E.NOENT)));

/// The lazy `mut Action[Str]` returned by `ReadPath.readStr`. Owns a copy of the
/// path bytes -- the action may outlive the path handle it was built from -- and
/// touches the filesystem only when `.run` is invoked. Mirrors `try.zig`'s
/// `VT_TryAction`: `.run` is native, the rest trampoline to the emitted base
/// `Action` default bodies.
const ReadStrCaptures = extern struct {
    ptr: usize, // [*]const u8 stored as usize
    len: i64,
};

pub fn make_action(path_bytes: []const u8) FatPtr {
    const buf = gc.recycleAllocSlice(u8, path_bytes.len);
    @memcpy(buf, path_bytes);
    return objs.obj_k(ReadStrCaptures, &VT_ReadStrAction, .{ .ptr = @intFromPtr(buf.ptr), .len = @intCast(buf.len) });
}

fn readstr_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(ReadStrCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    gc.free(@ptrFromInt(self.captures.ptr));
}

/// Build `<prefix><path>` and deliver it to the match object as `m.info`.
fn reply_info(m: FatPtr, prefix: []const u8, path: []const u8) FatPtr {
    const buf = gc.recycleAllocSlice(u8, prefix.len + path.len);
    @memcpy(buf[0..prefix.len], prefix);
    @memcpy(buf[prefix.len..], path);
    const msg = str_rt.make_owned_str(buf.ptr, buf.len);
    return objs.call(m, comptime h("mut .info/1"), .{str_rt.make_info_msg(msg)}, @src());
}

/// `.run(m)` -- read the whole file now, validate UTF-8, and dispatch to
/// `m.ok`/`m.info`. Mirrors `ReadWritePath.java`'s catch arms. Each reactor call
/// (`openat`/`read`/`close`) parks this fiber and resumes it on completion, so
/// the worker thread runs other fibers while the I/O is outstanding. The stored
/// path is already absolute (from `scoped_resolve`), so `openat` from `AT.FDCWD`
/// is CWD-independent.
fn readstr_run(self: FatPtr, m: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const caps = objs.deref(ReadStrCaptures, self);
    const p: [*]const u8 = @ptrFromInt(caps.ptr);
    const path = p[0..@intCast(caps.len)];

    // Null-terminate the path for `openat`.
    const path_z_buf = gc.recycleAllocSlice(u8, path.len + 1);
    @memcpy(path_z_buf[0..path.len], path);
    path_z_buf[path.len] = 0;
    const path_z: [*:0]const u8 = @ptrCast(path_z_buf.ptr);

    const fd = reactor.openatAsync(std.posix.AT.FDCWD, path_z, .{ .ACCMODE = .RDONLY }, 0);
    gc.free(@ptrCast(path_z_buf.ptr));
    if (fd < 0) {
        const prefix: []const u8 = if (fd == ENOENT) "File not found: " else "Could not read file: ";
        return reply_info(m, prefix, path);
    }

    // Read the whole file into a growing GC buffer (kept reachable via this
    // fiber's stack across parks, so an in-flight read survives a GC cycle).
    var buf = gc.recycleAllocSlice(u8, INITIAL_CAP);
    var filled: usize = 0;
    while (true) {
        if (filled == buf.len) {
            const grown = gc.recycleAllocSlice(u8, buf.len * 2);
            @memcpy(grown[0..filled], buf[0..filled]);
            gc.free(@ptrCast(buf.ptr));
            buf = grown;
        }
        const n = reactor.readAsync(fd, buf[filled..], filled);
        if (n == 0) break; // EOF
        if (n < 0) {
            gc.free(@ptrCast(buf.ptr));
            _ = reactor.closeAsync(fd);
            return reply_info(m, "Could not read file: ", path);
        }
        filled += @intCast(n);
    }
    _ = reactor.closeAsync(fd);

    const data = buf[0..filled];
    if (!std.unicode.utf8ValidateSlice(data)) {
        gc.free(@ptrCast(buf.ptr));
        return reply_info(m, "Invalid UTF-8 in file: ", path);
    }
    return objs.call(m, comptime h("mut .ok/1"), .{str_rt.make_owned_str(data.ptr, data.len)}, @src());
}

pub const VT_ReadStrAction = actions.ActionVTable("<runtime read-str action>", &readstr_run, &readstr_drop);
