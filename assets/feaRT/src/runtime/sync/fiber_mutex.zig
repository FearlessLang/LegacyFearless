const std = @import("std");
const WriteLock = @import("write_lock.zig").WriteLock;
const JoinObligation = @import("join_obligation.zig").JoinObligation;
const completion = @import("completion.zig");

/// One waiting fiber. Lives stack-local in `acquire`'s frame, which stays alive
/// (and GC-reachable) on the parked fiber's 2MB stack for the whole wait, so the
/// mutex's intrusive queue can safely reference it until handoff.
const Waiter = struct { obl: JoinObligation, next: ?*Waiter = null };

/// A mutex whose contended waiters park (yield the worker) instead of spinning,
/// so it is safe to hold across a fiber park (e.g. across a reactor I/O). The
/// owner can park mid-critical-section while other fibers wanting the lock park
/// on it; the worker keeps running other ready fibers. A tiny internal spinlock
/// (`guard`) protects only the O(1) queue/`held` mutations and is never held
/// across a park.
pub const FiberMutex = struct {
    /// Protects `held`/`head`/`tail`; held only for O(1) list ops, never across a park.
    guard: WriteLock = .{},
    held: bool = false,
    /// FIFO waiter queue (pop head, push tail) for fairness.
    head: ?*Waiter = null,
    tail: ?*Waiter = null,

    pub fn acquire(self: *FiberMutex, worker: anytype) void {
        self.guard.acquire();
        if (!self.held) {
            self.held = true;
            self.guard.release();
            return;
        }
        // Contended: enqueue our stack-local waiter, drop the guard, then park.
        var w = Waiter{ .obl = JoinObligation.init() };
        if (self.tail) |t| {
            t.next = &w;
            self.tail = &w;
        } else {
            self.head = &w;
            self.tail = &w;
        }
        self.guard.release();
        // If `release` fulfilled us between dropping the guard and here, `wait`
        // returns immediately (JoinObligation Path 1). Either way, on return we
        // OWN the lock: `release` hands ownership off directly without clearing
        // `held`, so there is nothing more to do here.
        _ = w.obl.wait(worker);
    }

    pub fn release(self: *FiberMutex) void {
        self.guard.acquire();
        if (self.head) |w| {
            self.head = w.next;
            if (self.head == null) self.tail = null;
            self.guard.release();
            // Direct handoff: `held` stays true; this waiter becomes the new
            // owner. The wake carries no value (int-encoded 0); `acquire`
            // discards it. Safe from any thread, worker or not.
            completion.fulfill(&w.obl, completion.encodeInt(0));
        } else {
            self.held = false;
            self.guard.release();
        }
    }
};
