//! Reusable "park the current fiber until an external event completes" primitive.
//!
//! A caller creates a stack-local `JoinObligation`, hands its address to whatever
//! will complete the event (an I/O backend, another fiber, a timer thread, ...),
//! and parks on it via `wait`, freeing the worker OS thread to run other fibers.
//! The completer calls `fulfill`, which publishes the result and enqueues the
//! parked fiber onto the process-wide ready queue so a worker resumes it.
//!
//! Events that carry no Fearless value (raw syscall results, pure wake-ups) use
//! `encodeInt`/`decodeInt`: the integer travels in the `FatPtr`'s data word and
//! `vt` is a non-null sentinel that satisfies `JoinObligation`'s `vt != 0`
//! "ready" check. Such a `FatPtr` is not a Fearless value: decode it immediately
//! after `wait` and never let it escape to code that dispatches on `vt`.

const Fiber = @import("../fiber.zig").Fiber;
const MpmcBoundedQueue = @import("mpmc.zig").MpmcBoundedQueue;
const JoinObligation = @import("join_obligation.zig").JoinObligation;
const objs = @import("../objs.zig");
const FatPtr = objs.FatPtr;
const VTable = objs.VTable;
const worker_mod = @import("../worker.zig");

/// Process-wide ready queue, set once in `init` before any completer thread is
/// spawned or any fiber can park.
var ready_queue_ptr: *MpmcBoundedQueue(*Fiber) = undefined;

/// Record the ready queue. Called once from the emitted `main` after the worker
/// pool is built and before `reactor.init()` / `pool.run()`.
pub fn init(ready_queue: *MpmcBoundedQueue(*Fiber)) void {
    ready_queue_ptr = ready_queue;
}

/// Park the current fiber on `obl` (no-op if already fulfilled) and return the
/// fulfilled value. Must run on a worker thread, inside a fiber.
pub fn wait(obl: *JoinObligation) FatPtr {
    const w = worker_mod.getCurrentWorker().?;
    return obl.wait(w);
}

/// Fulfill `obl` with `value`, enqueueing its parked fiber (if any) onto the
/// ready queue. Safe from any thread, including non-worker threads.
pub fn fulfill(obl: *JoinObligation, value: FatPtr) void {
    obl.fulfill(value, ready_queue_ptr);
}

/// Non-null sentinel used as the `vt` of int-encoded results so `JoinObligation`'s
/// `vt != 0` "ready" check holds. Never dereferenced as a `VTable`, but aligned
/// as one so forming the pointer is statically valid.
var int_res_sentinel: u8 align(@alignOf(VTable)) = 0;

/// Pack a raw integer result into an obligation `FatPtr`: the int goes in the
/// data word, the sentinel address in `vt`.
pub fn encodeInt(res: i64) FatPtr {
    return .{ .data = .{ .int = res }, .vt = @ptrCast(&int_res_sentinel) };
}

/// Inverse of `encodeInt`: pull the integer back out of a fulfilled obligation.
pub fn decodeInt(fp: FatPtr) i64 {
    return fp.data.int;
}
