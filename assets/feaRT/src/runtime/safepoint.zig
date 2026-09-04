//! Stop-the-world, for the synchronous cycle collector.
//!
//! `Worker.checkpoint` runs between fiber slices, so a worker that answers a
//! park request is by construction not executing user code. That is what makes
//! this a genuine stop-the-world with no safepoint poll in generated code, and
//! it is also the limit of it: a fiber that runs a long time without returning
//! to its scheduler cannot answer, so the wait is bounded and gives up rather
//! than blocking. Giving up loses reclamation, never correctness.

const std = @import("std");

const process = @import("process_singletons.zig");
const op_counters = @import("op_counters.zig");

/// Slots, indexed by worker id. Ids run from 1 and 0 names "no worker", so the
/// array holds one more than the workers a pool can have.
const SLOTS: u32 = 512;

/// How long the caller spins for a worker before it starts sleeping. A worker
/// between fiber slices answers within a few hundred cycles.
const SPINS: usize = 4096;

/// How many rounds the wait tolerates without a single further worker parking
/// before it gives up.
///
/// The wait is bounded by lack of *progress*, not by elapsed time. A fixed time
/// budget cannot tell "one fiber is never coming back" from "thirty-two workers
/// are arriving one per round", and a pool answers a request one worker at a
/// time: with a flat budget the last workers to arrive were still outstanding
/// when it ran out, the request was dropped, everyone was released, and the next
/// collection started again from nothing. Measured on the cyclic benchmark under
/// VPF, every collection was abandoned and nothing was ever reclaimed.
///
/// So each round that reduces the outstanding count resets this, and only a run
/// of rounds where nothing moved ends the wait.
const STALL_ROUNDS: usize = 8;

/// One worker's park state.
///
/// Padded to a cache line: `parked` is written on the way in and out of every
/// collection and `live` is read by the waiting thread, so two workers sharing
/// one line would put that traffic on the interconnect.
const Slot = struct {
    /// True while a worker loop owns this slot.
    live: std.atomic.Value(bool) align(std.atomic.cache_line) = std.atomic.Value(bool).init(false),
    /// True while that worker is waiting inside [`park`].
    parked: std.atomic.Value(bool) = std.atomic.Value(bool).init(false),
};

var slots: [SLOTS]Slot = @splat(.{});

/// Raised while a collection needs the world stopped.
var park_request = std.atomic.Value(bool).init(false);

/// Won by the one thread running a collection. A worker that loses the exchange
/// carries on and answers the winner's request at its own next checkpoint.
var collecting = std.atomic.Value(bool).init(false);

/// Takes the slot of `id`. A worker loop calls this before its first checkpoint.
pub fn enlist(id: u32) void {
    slots[id % SLOTS].live.store(true, .release);
}

/// Gives the slot of `id` back. Nothing waits on it after this.
pub fn retire(id: u32) void {
    const slot = &slots[id % SLOTS];
    slot.parked.store(false, .release);
    slot.live.store(false, .release);
}

/// Releases any parked workers and clears the collection lock. Called when
/// cycle collection is disabled during exception handling or signal recovery.
pub fn forceResume() void {
    park_request.store(false, .release);
    collecting.store(false, .release);
}

/// Answers a park request, if there is one. One relaxed load when there is not,
/// which is every checkpoint of a program that makes no cycles.
pub inline fn poll(id: u32) void {
    if (park_request.load(.monotonic)) {
        @branchHint(.unlikely);
        park(id);
    }
}

noinline fn park(id: u32) void {
    const slot = &slots[id % SLOTS];
    slot.parked.store(true, .release);
    var spins: usize = 0;
    while (park_request.load(.acquire)) {
        if (spins < SPINS) {
            spins += 1;
            std.atomic.spinLoopHint();
        } else {
            process.idle(1);
        }
    }
    slot.parked.store(false, .release);
}

/// Runs `body` with every other live worker parked, and hands it `me`.
///
/// `body` takes the worker id so that a thread which loses the exchange below
/// leaves no mark at all: it never runs, so it never names itself to the work
/// the winner is doing.
///
/// False when the world did not stop inside the budget: the request is dropped,
/// whoever parked is released, and this collection is skipped. False is also
/// what a caller gets when another thread already holds the collection.
pub fn stopTheWorld(me: u32, body: *const fn (me: u32) void) bool {
    if (collecting.cmpxchgStrong(false, true, .acq_rel, .monotonic) != null) return false;
    defer collecting.store(false, .release);

    park_request.store(true, .release);
    if (!waitForWorld(me)) {
        park_request.store(false, .release);
        op_counters.bump(.cycle_collection_abandoned);
        return false;
    }

    body(me);

    park_request.store(false, .release);
    op_counters.bump(.cycle_collection_run);
    return true;
}

/// Waits for every live worker other than `me` to reach [`park`].
///
/// False when the wait stalled: [`STALL_ROUNDS`] rounds passed with the same
/// workers still outstanding, so one of them is inside a fiber that is not
/// coming back soon and this collection is dropped.
fn waitForWorld(me: u32) bool {
    var stalls: usize = 0;
    var fewest: usize = std.math.maxInt(usize);
    while (true) {
        const remaining = outstanding(me);
        if (remaining == 0) return true;
        if (remaining < fewest) {
            fewest = remaining;
            stalls = 0;
        } else {
            stalls += 1;
            if (stalls > STALL_ROUNDS) return false;
        }
        // A worker between fiber slices answers within a few hundred cycles, so
        // spinning first keeps the common case off the scheduler entirely.
        for (0..SPINS) |_| std.atomic.spinLoopHint();
        if (outstanding(me) == 0) return true;
        process.idle(1);
    }
}

/// Live workers other than `me` that have not yet parked.
fn outstanding(me: u32) usize {
    var n: usize = 0;
    for (&slots, 0..) |*slot, i| {
        if (i == me % SLOTS) continue;
        if (slot.live.load(.acquire) and !slot.parked.load(.acquire)) n += 1;
    }
    return n;
}

const testing = std.testing;

test "a worker that never reaches a checkpoint is abandoned rather than waited for" {
    // The slot is live and never parks, which is the shape of a fiber that runs
    // longer than the budget without returning to its scheduler.
    enlist(3);
    defer retire(3);

    const Body = struct {
        var ran: bool = false;
        var ran_as: u32 = 0;
        fn run(me: u32) void {
            ran = true;
            ran_as = me;
        }
    };
    Body.ran = false;
    try testing.expect(!stopTheWorld(1, &Body.run));
    try testing.expect(!Body.ran);
    // The request is down again, so the next collection is not held off by this
    // one having given up.
    try testing.expect(!park_request.load(.acquire));
}

test "a world with nothing but the caller in it stops at once" {
    const Body = struct {
        var ran: bool = false;
        var ran_as: u32 = 0;
        fn run(me: u32) void {
            ran = true;
            ran_as = me;
        }
    };
    Body.ran = false;
    enlist(1);
    defer retire(1);
    try testing.expect(stopTheWorld(1, &Body.run));
    try testing.expect(Body.ran);
    // The body is told which worker is running it, which is the identity every
    // free of a collection is charged to.
    try testing.expectEqual(@as(u32, 1), Body.ran_as);
}
