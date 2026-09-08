const std = @import("std");
const objs = @import("../../objs.zig");
const reactor = @import("../../io/reactor.zig");
const FiberMutex = @import("../../sync/fiber_mutex.zig").FiberMutex;
const worker_mod = @import("../../worker.zig");

const FatPtr = objs.FatPtr;

const str_rt = @import("../strings/index.zig");
const path_rt = @import("path.zig");
const env_rt = @import("env.zig");
const pb = @import("root").pkg_base;

fn make_void() FatPtr {
    return objs.obj_k_singleton(&pb.VT_Void_0);
}

/// Serialise concurrent fibers' writes to each stream so their lines stay intact.
/// A fiber mutex (not a spinlock): the owner parks on the reactor mid-write while
/// holding it, so contended fibers must park rather than busy-block a worker.
var stdout_mutex: FiberMutex = .{};
var stderr_mutex: FiberMutex = .{};

/// Write `msg`'s bytes to `fd` through the reactor, serialised on `mutex`. `msg`
/// is on loan and its owner outlives the call, so its data stays live for the
/// whole I/O, which parks until the write completes.
fn write_str(fd: i32, mutex: *FiberMutex, msg: FatPtr) void {
    const data = str_rt.deref_str(msg);
    const w = worker_mod.getCurrentWorker().?;
    mutex.acquire(w);
    defer mutex.release();
    _ = reactor.writeAsync(fd, data, reactor.NO_OFFSET);
}

/// As `write_str` but appends a newline atomically via a 2-iovec `writev`.
fn writeln_str(fd: i32, mutex: *FiberMutex, msg: FatPtr) void {
    const data = str_rt.deref_str(msg);
    const vecs = [2]std.posix.iovec_const{
        .{ .base = data.ptr, .len = data.len },
        .{ .base = "\n", .len = 1 },
    };
    const w = worker_mod.getCurrentWorker().?;
    mutex.acquire(w);
    defer mutex.release();
    _ = reactor.writevAsync(fd, vecs[0..], reactor.NO_OFFSET);
}

/// Write `msg` and a newline to stderr, serialised with `IO.printlnErr` so
/// diagnostics from other subsystems (`Debug`, traces) stay line-atomic against
/// a program's own error output. Consumes `msg`.
pub fn println_stderr(msg: FatPtr) void {
    writeln_str(std.posix.STDERR_FILENO, &stderr_mutex, msg);
}

fn io_print(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    write_str(std.posix.STDOUT_FILENO, &stdout_mutex, msg);
    return make_void();
}

fn io_println(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    writeln_str(std.posix.STDOUT_FILENO, &stdout_mutex, msg);
    return make_void();
}

fn io_print_err(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    write_str(std.posix.STDERR_FILENO, &stderr_mutex, msg);
    return make_void();
}

fn io_println_err(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    writeln_str(std.posix.STDERR_FILENO, &stderr_mutex, msg);
    return make_void();
}

fn io_accessRW(self: FatPtr, path: FatPtr) callconv(.c) FatPtr {
    _ = self;
    return path_rt.rw_from_cwd(path);
}

fn io_accessR(self: FatPtr, path: FatPtr) callconv(.c) FatPtr {
    _ = self;
    return path_rt.read_from_cwd(path);
}

fn io_accessW(self: FatPtr, path: FatPtr) callconv(.c) FatPtr {
    _ = self;
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
