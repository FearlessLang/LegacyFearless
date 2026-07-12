//! Async I/O for the fiber runtime: each `*Async` call submits one I/O operation
//! to the backend and parks the calling fiber via the shared completion
//! primitive (`sync/completion.zig`), freeing the worker OS thread to run other
//! fibers. When the operation completes the backend fulfils the obligation,
//! resuming the parked fiber. The result is the raw syscall/cqe `i32` (`>= 0`
//! success, `< 0` = `-errno`), carried int-encoded through the obligation.
//!
//! On Linux the backend is a single shared io_uring with one poller thread; the
//! kernel fulfils obligations. Elsewhere it is a single dedicated blocking-I/O
//! thread (modelled on the destroyer) that performs the syscall and fulfils the
//! obligation itself. Both share the submit -> park -> resume -> decode skeleton
//! in `awaitOp`; only op dispatch and result production differ.

const std = @import("std");
const builtin = @import("builtin");
const linux = std.os.linux;

const JoinObligation = @import("../sync/join_obligation.zig").JoinObligation;
const completion = @import("../sync/completion.zig");
const MpmcBoundedQueue = @import("../sync/mpmc.zig").MpmcBoundedQueue;
const gc = @import("../gc.zig");
const process = @import("../process_singletons.zig");

/// Stream/"current position" offset sentinel (= -1 as u64). For a stream fd
/// (stdout/stderr) io_uring uses the file's current position, giving ordinary
/// `write`/`writev` semantics; the fallback must instead pick `write`/`writev`
/// over `pwrite`/`pwritev` (which reject -1 on a pipe).
pub const NO_OFFSET: u64 = std.math.maxInt(u64);

/// What the backend should do. The io_uring poller ignores `op` (the kernel
/// already holds the request); the fallback thread switches on it to pick the
/// blocking syscall.
const Op = union(enum) {
    openat: struct { dirfd: i32, path: [*:0]const u8, flags: std.posix.O, mode: std.posix.mode_t },
    read: struct { fd: i32, buf: []u8, offset: u64 },
    write: struct { fd: i32, buf: []const u8, offset: u64 },
    writev: struct { fd: i32, iovecs: []const std.posix.iovec_const, offset: u64 },
    close: struct { fd: i32 },
};

/// Lives stack-local in `awaitOp`'s frame on the (parked) fiber's stack -- its
/// address is stable for the whole I/O and stays GC-reachable through the parked
/// fiber's stack. The backend keys completions by `&Completion` (io_uring
/// `user_data` / fallback queue node), so `obl` is always reachable when the
/// completion fires.
const Completion = struct {
    obl: JoinObligation,
    op: Op,
};

/// Spawn the backend thread. Called once from `main` after `completion.init`
/// has registered the ready queue and before `pool.run()`.
pub fn init() !void {
    try backend.init();
}

/// Submit `op`, park the current fiber, and return the raw result once resumed.
/// The `Completion` lives on this frame (the parked fiber's stack), so its
/// address is stable and GC-reachable for the lifetime of the I/O.
fn awaitOp(op: Op) i32 {
    var c = Completion{ .obl = JoinObligation.init(), .op = op };
    backend.submit(&c);
    return @intCast(completion.decodeInt(completion.wait(&c.obl)));
}

/// `openat(dirfd, path)` -- returns an fd (`>= 0`) or `-errno`.
pub fn openatAsync(dirfd: i32, path_z: [*:0]const u8, flags: std.posix.O, mode: std.posix.mode_t) i32 {
    return awaitOp(.{ .openat = .{ .dirfd = dirfd, .path = path_z, .flags = flags, .mode = mode } });
}

/// `pread(fd, buf, offset)` -- returns bytes read (`0` = EOF) or `-errno`.
pub fn readAsync(fd: i32, buf: []u8, offset: u64) i32 {
    return awaitOp(.{ .read = .{ .fd = fd, .buf = buf, .offset = offset } });
}

/// `write(fd, buf)` for a stream fd (`offset = NO_OFFSET`) -- returns bytes
/// written or `-errno`. The caller's `buf` lives in its frame, which stays alive
/// on the parked fiber's stack for the whole op (same stability as `readAsync`).
pub fn writeAsync(fd: i32, buf: []const u8, offset: u64) i32 {
    return awaitOp(.{ .write = .{ .fd = fd, .buf = buf, .offset = offset } });
}

/// `writev(fd, iovecs)` for a stream fd (`offset = NO_OFFSET`) -- returns bytes
/// written or `-errno`. The caller's `iovecs` slice and the array it points at
/// both live in the caller's frame, kept alive on the parked fiber's stack.
pub fn writevAsync(fd: i32, iovecs: []const std.posix.iovec_const, offset: u64) i32 {
    return awaitOp(.{ .writev = .{ .fd = fd, .iovecs = iovecs, .offset = offset } });
}

/// `close(fd)` -- returns `0` or `-errno`.
pub fn closeAsync(fd: i32) i32 {
    return awaitOp(.{ .close = .{ .fd = fd } });
}

const backend = if (builtin.os.tag == .linux) LinuxBackend else FallbackBackend;

/// io_uring backend: one shared ring, one sole CQ consumer (the poller), and a
/// submit mutex serialising SQ production. Concurrent `io_uring_enter` from the
/// poller (CQ only) and submitters (SQ only) is allowed, so no other locking is
/// needed. The poller blocks in-kernel on `copy_cqe` until a completion is ready.
const LinuxBackend = struct {
    var ring: linux.IoUring = undefined;
    var submit_mutex: std.Io.Mutex = .init;

    fn init() !void {
        ring = try linux.IoUring.init(256, 0);
        const thread = try std.Thread.spawn(.{}, pollerLoop, .{});
        thread.detach();
    }

    /// Hand all pending SQEs to the kernel, retrying on EINTR: BDWGC's
    /// stop-the-world suspend signal routinely interrupts `io_uring_enter`,
    /// and dropping the submit would leave the SQE in the userspace ring with
    /// its fiber parked forever on a completion the kernel never produces.
    fn submitPending() void {
        while (true) {
            _ = ring.submit() catch |err| switch (err) {
                error.SignalInterrupt => continue,
                else => return,
            };
            return;
        }
    }

    fn submit(c: *Completion) void {
        submit_mutex.lockUncancelable(process.runtime_io);
        defer submit_mutex.unlock(process.runtime_io);
        // With the mutex held across get_sqe + submit, SQ depth stays ~1, so 256
        // entries is ample; on a (theoretical) full SQ, flush and retry.
        while (true) {
            const prepared = switch (c.op) {
                .openat => |o| ring.openat(@intFromPtr(c), o.dirfd, o.path, o.flags, o.mode),
                .read => |r| ring.read(@intFromPtr(c), r.fd, .{ .buffer = r.buf }, r.offset),
                .write => |w| ring.write(@intFromPtr(c), w.fd, w.buf, w.offset),
                .writev => |w| ring.writev(@intFromPtr(c), w.fd, w.iovecs, w.offset),
                .close => |k| ring.close(@intFromPtr(c), k.fd),
            };
            if (prepared) |_| {
                submitPending();
                return;
            } else |err| switch (err) {
                error.SubmissionQueueFull => {
                    submitPending();
                },
            }
        }
    }

    fn pollerLoop() void {
        gc.register_thread();
        defer gc.unregister_thread();
        while (true) {
            const cqe = ring.copy_cqe() catch continue;
            const c: *Completion = @ptrFromInt(cqe.user_data);
            completion.fulfill(&c.obl, completion.encodeInt(cqe.res));
        }
    }
};

/// Single dedicated blocking-I/O thread for non-Linux targets. Submitters push
/// `*Completion`s onto an MPMC queue; the thread drains it, runs the blocking
/// syscall, and fulfils the obligation. Serialises all fallback I/O through one
/// thread (acceptable per design). Mirrors the destroyer's lifecycle.
const FallbackBackend = struct {
    var submit_queue: *MpmcBoundedQueue(*Completion) = undefined;

    fn init() !void {
        submit_queue = try MpmcBoundedQueue(*Completion).init(gc.allocator, 256);
        const thread = try std.Thread.spawn(.{}, ioLoop, .{});
        thread.detach();
    }

    fn submit(c: *Completion) void {
        submit_queue.enqueueWithSpin(c);
    }

    fn ioLoop() void {
        gc.register_thread();
        defer gc.unregister_thread();
        while (true) {
            if (submit_queue.dequeue()) |c| {
                const res: i32 = switch (c.op) {
                    .openat => |o| openRes(o.dirfd, o.path, o.flags, o.mode),
                    .read => |r| readRes(r.fd, r.buf, r.offset),
                    .write => |w| writeRes(w.fd, w.buf, w.offset),
                    .writev => |w| writevRes(w.fd, w.iovecs),
                    .close => |k| blk: {
                        std.posix.close(k.fd);
                        break :blk 0;
                    },
                };
                completion.fulfill(&c.obl, completion.encodeInt(res));
            } else {
                std.Io.sleep(process.runtime_io, std.Io.Duration.fromMilliseconds(1), .awake) catch {};
            }
        }
    }

    fn openRes(dirfd: i32, path: [*:0]const u8, flags: std.posix.O, mode: std.posix.mode_t) i32 {
        const fd = std.posix.openatZ(dirfd, path, flags, mode) catch |e| return errToErrno(e);
        return @intCast(fd);
    }

    fn readRes(fd: i32, buf: []u8, offset: u64) i32 {
        const n = std.posix.pread(fd, buf, offset) catch |e| return errToErrno(e);
        return @intCast(n);
    }

    fn writeRes(fd: i32, buf: []const u8, offset: u64) i32 {
        const n = if (offset == NO_OFFSET)
            std.posix.write(fd, buf) catch |e| return errToErrno(e)
        else
            std.posix.pwrite(fd, buf, offset) catch |e| return errToErrno(e);
        return @intCast(n);
    }

    fn writevRes(fd: i32, iovecs: []const std.posix.iovec_const) i32 {
        const n = std.posix.writev(fd, iovecs) catch |e| return errToErrno(e);
        return @intCast(n);
    }

    /// Map a Zig error back to `-errno` so the driver sees the same values it
    /// would on the io_uring backend. Only the cases the file-read driver
    /// distinguishes are mapped precisely; anything else collapses to `-EIO`.
    fn errToErrno(err: anyerror) i32 {
        const e: std.posix.E = switch (err) {
            error.FileNotFound => .NOENT,
            error.AccessDenied, error.PermissionDenied => .ACCES,
            error.IsDir => .ISDIR,
            error.NotDir => .NOTDIR,
            error.SymLinkLoop => .LOOP,
            error.NameTooLong => .NAMETOOLONG,
            error.SystemFdQuotaExceeded => .NFILE,
            error.ProcessFdQuotaExceeded => .MFILE,
            else => .IO,
        };
        return -@as(i32, @intCast(@intFromEnum(e)));
    }
};
