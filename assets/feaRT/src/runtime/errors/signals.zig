//! Hardware faults (stack overflow) become non-deterministic Fearless errors.
//!
//! A fiber's machine stack is a fixed mmap with a PROT_NONE guard page at its low
//! end (see `fiber.zig`). Unbounded recursion grows the stack down past
//! `stack_bottom` into the guard page, which raises SIGSEGV. `ndPanicHandler`
//! already covers `@panic`-class ND faults, but Zig's panic machinery cannot
//! catch a hardware fault, so a real signal handler is necessary.
//!
//! The faulting stack has no space left, so the signal needs a stack of its own.
//! The handler rewrites the saved registers, and `sigreturn` then resumes on the
//! recovery stack in `ndRecoveryTrampoline`, which enters the same `feart_unwind`
//! path a user-level error uses.
//!
//! BDW-GC uses signals too. A fault that is not a fiber stack overflow goes to
//! the GC's own handler (`chainOldHandler`).

const std = @import("std");
const builtin = @import("builtin");
const worker_mod = @import("../worker.zig");
const gc = @import("../gc.zig");
const error_rt = @import("../error.zig");
const unwind = @import("unwind.zig");
const fiber_mod = @import("../fiber.zig");
const Fiber = fiber_mod.Fiber;

const ALT_STACK_SIZE = 64 * 1024;

/// Per-thread block holding the signal alt stack and the recovery stack, laid
/// out base-upwards as a pseudo-`Fiber`, the alt stack, then the recovery stack
/// to the top.
///
/// It is a fiber-shaped mapping because `ndRecoveryTrampoline` runs generated
/// code, and generated code masks its stack pointer to find a `Fiber`. The
/// header's `vpf_enabled` is false, which is what suppresses promotion while the
/// faulting fiber is being unwound.
///
/// Whole-block GC roots, so the ND `Info` the trampoline builds cannot be swept
/// by a cycle collection mid-construction.
threadlocal var signal_block: usize = 0;
threadlocal var recovery_stack_top: usize = 0;

/// Message handed from the signal handler to the recovery trampoline.
threadlocal var pending_nd_msg: []const u8 = "";

/// BDW-GC's fault handlers, captured when we install ours on top.
var old_sa_segv: std.posix.Sigaction = undefined;
var old_sa_bus: std.posix.Sigaction = undefined;

const is_darwin = builtin.os.tag.isDarwin();

/// Minimal, mutable view of the kernel's register context for the architectures
/// we support. Layout mirrors `std.debug.cpu_context`'s `signal_ucontext_t`
/// (verified against the Linux kernel and Apple headers); we only read/write the
/// instruction pointer, stack pointer and frame pointer, so trailing fields are
/// omitted (the real structures are longer, accessed through a pointer).
const NativeMcontext = if (is_darwin) switch (builtin.cpu.arch) {
    .x86_64 => extern struct {
        _trapno: u16, _cpu: u16, _err: u32, _faultvaddr: u64,
        rax: u64, rbx: u64, rcx: u64, rdx: u64, rdi: u64, rsi: u64,
        rbp: u64, rsp: u64,
        r8: u64, r9: u64, r10: u64, r11: u64, r12: u64, r13: u64, r14: u64, r15: u64,
        rip: u64,
    },
    .aarch64 => extern struct {
        _far: u64 align(16),
        _esr: u64,
        x: [30]u64, // x0..x29 (x29 = frame pointer)
        lr: u64, // x30
        sp: u64,
        pc: u64,
    },
    else => @compileError("ND signal handling unsupported on this architecture"),
} else switch (builtin.cpu.arch) {
    .x86_64 => extern struct {
        r8: u64, r9: u64, r10: u64, r11: u64, r12: u64, r13: u64, r14: u64, r15: u64,
        rdi: u64, rsi: u64, rbp: u64, rbx: u64, rdx: u64, rax: u64, rcx: u64,
        rsp: u64, rip: u64,
    },
    .aarch64 => extern struct {
        fault_address: u64 align(16),
        x: [30]u64, // x0..x29 (x29 = frame pointer)
        lr: u64, // x30
        sp: u64,
        pc: u64,
    },
    else => @compileError("ND signal handling unsupported on this architecture"),
};

/// Darwin reaches the register file through a pointer, every other target we
/// support embeds it. `mcontextOf` hides the difference.
const NativeUcontext = if (is_darwin) extern struct {
    _onstack: i32,
    _sigmask: std.c.sigset_t,
    _stack: std.c.stack_t,
    _link: ?*anyopaque,
    _mcsize: u64,
    mcontext: *NativeMcontext,
} else switch (builtin.cpu.arch) {
    .x86_64 => extern struct {
        flags: usize,
        link: ?*anyopaque,
        stack: std.posix.stack_t,
        mcontext: NativeMcontext,
    },
    .aarch64 => extern struct {
        flags: usize,
        link: ?*anyopaque,
        stack: std.posix.stack_t,
        sigmask: std.os.linux.sigset_t,
        unused: [120]u8,
        mcontext: NativeMcontext,
    },
    else => @compileError("ND signal handling unsupported on this architecture"),
};

inline fn mcontextOf(uctx: *NativeUcontext) *NativeMcontext {
    return if (is_darwin) uctx.mcontext else &uctx.mcontext;
}

/// Rewrite a saved register context so that sigreturn resumes at `entry` on the
/// recovery stack whose top is `sp_top`. The frame pointer is zeroed to cleanly
/// terminate any stack walk; no return address is set because the trampoline is
/// `noreturn`.
fn setResumeContext(uctx: *NativeUcontext, entry: usize, sp_top: usize) void {
    const mc = mcontextOf(uctx);
    switch (builtin.cpu.arch) {
        .x86_64 => {
            // SysV ABI: a callee entered via `call` sees rsp ≡ 8 (mod 16). We
            // jump in directly, so mimic that by under-aligning the 16-aligned
            // top by 8.
            mc.rip = entry;
            mc.rsp = (sp_top & ~@as(usize, 15)) - 8;
            mc.rbp = 0;
        },
        .aarch64 => {
            // AArch64 requires a 16-aligned sp at all times.
            mc.pc = entry;
            mc.sp = sp_top & ~@as(usize, 15);
            mc.lr = 0;
            mc.x[29] = 0; // frame pointer
        },
        else => unreachable,
    }
}

/// True if `addr` faulted in the currently-running fiber's guard page, i.e. the
/// access was a stack overflow we should turn into an ND error. Async-signal-
/// safe: only fenced TLS reads and plain field reads.
fn isStackOverflow(addr: usize) bool {
    const worker = worker_mod.getCurrentWorker() orelse return false;
    const fiber = worker.current_fiber orelse return false;
    return fiber.guardContains(addr);
}

/// SA_SIGINFO fault handler for SIGSEGV/SIGBUS. Runs on the alt stack.
fn ndSignalHandler(sig: std.posix.SIG, info: *const std.posix.siginfo_t, ctx: ?*anyopaque) callconv(.c) void {
    gc.disable_cycle_collection();
    const addr = @intFromPtr(if (is_darwin) info.addr else info.fields.sigfault.addr);
    if (ctx != null and isStackOverflow(addr)) {
        pending_nd_msg = "Stack overflowed";
        const uctx: *NativeUcontext = @ptrCast(@alignCast(ctx.?));
        setResumeContext(uctx, @intFromPtr(&ndRecoveryTrampoline), recovery_stack_top);
        return;
    }
    chainOldHandler(sig, info, ctx);
}

/// Forward a fault we don't own to whatever handler BDW-GC installed before us
/// (so its incremental dirty-bit tracking keeps working). If that slot is empty
/// or ignore, reset the disposition to default and return -- the faulting
/// instruction re-executes and terminates the process the normal way.
fn chainOldHandler(sig: std.posix.SIG, info: *const std.posix.siginfo_t, ctx: ?*anyopaque) void {
    const old: *const std.posix.Sigaction = switch (sig) {
        .SEGV => &old_sa_segv,
        .BUS => &old_sa_bus,
        else => {
            resetToDefault(sig);
            return;
        },
    };
    if ((old.flags & std.posix.SA.SIGINFO) != 0) {
        if (old.handler.sigaction) |f| {
            f(sig, info, ctx);
            return;
        }
    } else if (old.handler.handler) |hf| {
        if (hf != std.posix.SIG.IGN) {
            hf(sig);
            return;
        }
    }
    resetToDefault(sig);
}

fn resetToDefault(sig: std.posix.SIG) void {
    var sa = std.posix.Sigaction{
        .handler = .{ .handler = std.posix.SIG.DFL },
        .mask = std.posix.sigemptyset(),
        .flags = 0,
    };
    std.posix.sigaction(sig, &sa, null);
}

/// Resumed (via sigreturn) on the recovery stack after a stack-overflow fault.
/// Runs on a clean stack with signals unmasked, so building the ND `Info` and
/// unwinding the faulting fiber is ordinary code. Building the `Info` runs
/// generated code, which finds the pseudo-fiber at the base of the signal block
/// and so leaves the dying fiber alone.
fn ndRecoveryTrampoline() callconv(.c) noreturn {
    unwind.feart_unwind(error_rt.makeNd(unwind.buildInfo(pending_nd_msg)));
}

/// Install the process-wide ND fault handlers. Must be called AFTER `gc.init_gc`
/// so the handlers BDW-GC installs for incremental collection are captured as
/// our fall-through (`old_sa_*`).
pub fn installNdHandlers() void {
    var sa = std.posix.Sigaction{
        .handler = .{ .sigaction = &ndSignalHandler },
        .mask = std.posix.sigemptyset(),
        .flags = std.posix.SA.SIGINFO | std.posix.SA.ONSTACK,
    };
    std.posix.sigaction(.SEGV, &sa, &old_sa_segv);
    std.posix.sigaction(.BUS, &sa, &old_sa_bus);
}

/// Per-thread setup: register the alt stack the kernel delivers faults onto and
/// the recovery stack the handler resumes onto. Called at the top of each
/// worker loop (covers worker 0 / the main thread too). The recovery stack is
/// registered as a GC root range so an `Info` built on it during recovery isn't
/// swept by a concurrent cycle collection.
pub fn initThreadSignalStacks(worker_id: u32) void {
    if (signal_block != 0) return;

    const base = fiber_mod.mapAlignedBlock() catch @panic("OOM mapping signal stacks");
    signal_block = base;

    const header: *Fiber = @ptrFromInt(base);
    const alt_bottom = base + fiber_mod.FIBER_REGION;
    const recovery_bottom = alt_bottom + ALT_STACK_SIZE;
    // Short of the very top, so a live stack pointer never masks to the block
    // that follows this one.
    recovery_stack_top = base + fiber_mod.STACK_SIZE - 64;

    header.* = .{
        .sp = 0,
        .stack_bottom = @ptrFromInt(recovery_bottom),
        .stack_size = recovery_stack_top - recovery_bottom,
        .entry_fn = null,
        .context = null,
        .state = .Running,
        // Only this worker's thread ever runs on the recovery stack, so an
        // unwind there keeps the biased reference counting paths it would have
        // taken on the fiber that faulted.
        .worker_id = worker_id,
        // A masked recovery-stack pointer must find this header and pass the
        // `currentFiber` check.
        .magic = fiber_mod.FIBER_MAGIC,
    };

    var ss = std.posix.stack_t{
        .sp = @ptrFromInt(alt_bottom),
        .flags = 0,
        .size = ALT_STACK_SIZE,
    };
    std.posix.sigaltstack(&ss, null) catch {};

    // Only the recovery region: an `Info` built during recovery lives there and a
    // collection must not sweep it. The alt stack holds no Fearless objects, and
    // the fiber registry reaches the header.
    gc.addRoots(@ptrFromInt(recovery_bottom), @ptrFromInt(recovery_stack_top));
}

/// Undo `initThreadSignalStacks` before a worker thread's TLS is torn down, so a
/// later collection never scans freed memory.
pub fn deinitThreadSignalStacks() void {
    const base = signal_block;
    if (base == 0) return;
    signal_block = 0;
    const recovery_bottom = base + fiber_mod.FIBER_REGION + ALT_STACK_SIZE;
    gc.removeRoots(@ptrFromInt(recovery_bottom), @ptrFromInt(recovery_stack_top));
    recovery_stack_top = 0;
    // Before the unmap, so a stale stack pointer that masks here fails the
    // `currentFiber` check instead of reading a dead header.
    const header: *Fiber = @ptrFromInt(base);
    header.magic = 0;
    fiber_mod.unmapAlignedBlock(base);
}
