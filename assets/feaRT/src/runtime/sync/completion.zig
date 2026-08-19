//! Park the current fiber until an external event completes.
//!
//! A caller makes a stack-local `JoinObligation`, hands its address to whatever
//! completes the event, and parks on it with `wait`, which frees the worker OS
//! thread for other fibers. The completer calls `fulfill`, which publishes the
//! result and makes the fiber runnable again.
//!
//! An event that carries no Fearless value, such as a raw syscall result, uses
//! `encodeInt`/`decodeInt`: the integer travels in the `FatPtr` data word and
//! `vt` is a non-null sentinel that satisfies the `vt != 0` ready check. Such a
//! `FatPtr` is not a Fearless value. Decode it immediately after `wait` and
//! never let it reach code that dispatches on `vt`.

const JoinObligation = @import("join_obligation.zig").JoinObligation;
const objs = @import("../objs.zig");
const FatPtr = objs.FatPtr;
const VTable = objs.VTable;
const worker_mod = @import("../worker.zig");

/// Returns at once if `obl` is already fulfilled. Must run inside a fiber.
pub fn wait(obl: *JoinObligation) FatPtr {
    const w = worker_mod.getCurrentWorker().?;
    return obl.wait(w);
}

/// Safe from any thread: one that runs no worker uses the shared ready list.
pub fn fulfill(obl: *JoinObligation, value: FatPtr) void {
    obl.fulfill(value);
}

/// The `vt` of an int-encoded result, so the `vt != 0` ready check holds. Never
/// dereferenced, but aligned as a `VTable` so the pointer is statically valid.
var int_res_sentinel: u8 align(@alignOf(VTable)) = 0;

/// The int goes in the data word, the sentinel address in `vt`.
pub fn encodeInt(res: i64) FatPtr {
    return .{ .data = .{ .int = res }, .vt = @ptrCast(&int_res_sentinel) };
}

pub fn decodeInt(fp: FatPtr) i64 {
    return fp.data.int;
}
