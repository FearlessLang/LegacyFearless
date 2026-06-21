const std = @import("std");
const objs = @import("../../objs.zig");
const WriteLock = @import("../../sync/write_lock.zig").WriteLock;

const FatPtr = objs.FatPtr;

const str_rt = @import("../strings/index.zig");
const path_rt = @import("path.zig");
const env_rt = @import("env.zig");
const pb = @import("root").pkg_base;

fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

var stdout_lock: WriteLock = .{};
var stderr_lock: WriteLock = .{};

fn io_print(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer msg.rc_decrement();
    const data = str_rt.deref_str(msg);
    stdout_lock.acquire();
    defer stdout_lock.release();
    _ = std.posix.system.write(std.posix.STDOUT_FILENO, data.ptr, data.len);
    return make_void();
}

fn io_println(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer msg.rc_decrement();
    const data = str_rt.deref_str(msg);
    const vecs: [2]std.posix.iovec_const = .{
        .{ .base = data.ptr, .len = data.len },
        .{ .base = "\n", .len = 1 },
    };
    stdout_lock.acquire();
    defer stdout_lock.release();
    _ = std.posix.system.writev(std.posix.STDOUT_FILENO, &vecs, vecs.len);
    return make_void();
}

fn io_print_err(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer msg.rc_decrement();
    const data = str_rt.deref_str(msg);
    stderr_lock.acquire();
    defer stderr_lock.release();
    _ = std.posix.system.write(std.posix.STDERR_FILENO, data.ptr, data.len);
    return make_void();
}

fn io_println_err(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer msg.rc_decrement();
    const data = str_rt.deref_str(msg);
    const vecs: [2]std.posix.iovec_const = .{
        .{ .base = data.ptr, .len = data.len },
        .{ .base = "\n", .len = 1 },
    };
    stderr_lock.acquire();
    defer stderr_lock.release();
    _ = std.posix.system.writev(std.posix.STDERR_FILENO, &vecs, vecs.len);
    return make_void();
}

fn io_accessRW(self: FatPtr, path: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer path.rc_decrement();
    return path_rt.rw_from_cwd(path);
}

fn io_accessR(self: FatPtr, path: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer path.rc_decrement();
    return path_rt.read_from_cwd(path);
}

fn io_accessW(self: FatPtr, path: FatPtr) callconv(.c) FatPtr {
    _ = self;
    defer path.rc_decrement();
    return path_rt.write_from_cwd(path);
}

fn io_env(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    return env_rt.make_env();
}

fn io_iso(self: FatPtr) callconv(.c) FatPtr {
    return self;
}
fn io_self(self: FatPtr) callconv(.c) FatPtr {
    return self;
}

const h = objs.hash_signature;

pub const VT_IO: objs.VTable = .{
    .type_name = "base.caps.IO/0",
    .hashes = &.{
        h("mut .print/1"),    h("mut .println/1"),  h("mut .printErr/1"),
        h("mut .printlnErr/1"), h("mut .accessR/1"), h("mut .accessW/1"),
        h("mut .accessRW/1"), h("mut .env/0"),      h("mut .iso/0"),
        h("mut .self/0"),
    },
    .method_names = &.{
        "mut .print/1",    "mut .println/1",  "mut .printErr/1",
        "mut .printlnErr/1", "mut .accessR/1", "mut .accessW/1",
        "mut .accessRW/1", "mut .env/0",      "mut .iso/0",
        "mut .self/0",
    },
    .methods = &.{
        @ptrCast(&io_print),    @ptrCast(&io_println),  @ptrCast(&io_print_err),
        @ptrCast(&io_println_err), @ptrCast(&io_accessR), @ptrCast(&io_accessW),
        @ptrCast(&io_accessRW), @ptrCast(&io_env),      @ptrCast(&io_iso),
        @ptrCast(&io_self),
    },
    .storage_mode = .singleton,
};

pub fn make_io() FatPtr {
    return objs.obj_k_singleton(&VT_IO);
}
