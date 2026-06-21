const std = @import("std");
const objs = @import("../../objs.zig");
const gc = @import("../../gc.zig");
const process = @import("../../process_singletons.zig");
const str_rt = @import("../strings/index.zig");
const actions = @import("../../conversions/actions.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

/// The lazy `mut Action[Str]` returned by `ReadPath.readStr`. Owns a copy of the
/// path bytes — the action may outlive the path handle it was built from — and
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

/// `.run(m)` — read the whole file now, validate UTF-8, and dispatch to
/// `m.ok`/`m.info`. Mirrors `ReadWritePath.java`'s catch arms.
fn readstr_run(self: FatPtr, m: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const caps = objs.deref(ReadStrCaptures, self);
    const p: [*]const u8 = @ptrFromInt(caps.ptr);
    const path = p[0..@intCast(caps.len)];
    const data = std.Io.Dir.cwd().readFileAlloc(process.runtime_io, path, gc.allocator, .unlimited) catch |e| {
        const prefix: []const u8 = if (e == error.FileNotFound) "File not found: " else "Could not read file: ";
        return reply_info(m, prefix, path);
    };
    if (!std.unicode.utf8ValidateSlice(data)) {
        gc.free(@ptrCast(data.ptr));
        return reply_info(m, "Invalid UTF-8 in file: ", path);
    }
    return objs.call(m, comptime h("mut .ok/1"), .{str_rt.make_owned_str(data.ptr, data.len)}, @src());
}

pub const VT_ReadStrAction = actions.ActionVTable("<runtime read-str action>", &readstr_run, &readstr_drop);
