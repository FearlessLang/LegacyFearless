//! Bounded SPSC ring carrying the ordered message stream between two pipeline
//! stage fibers. The producer parks when the ring is full, the consumer when it
//! is empty; each side wakes the other through the shared completion primitive.
//! Closing either end wakes and permanently unblocks the opposite side, so a
//! stage that exits for any reason can never leave its neighbour parked.
//!
//! This buffer is async-safe, so it cooperates with the fiber scheduler.

const std = @import("std");
const objs = @import("../../../objs.zig");
const gc = @import("../../../gc.zig");
const completion = @import("../../../sync/completion.zig");
const JoinObligation = @import("../../../sync/join_obligation.zig").JoinObligation;

const FatPtr = objs.FatPtr;

/// One slot of the inter-stage stream. `err` carries a tag-typed error payload
/// (an `error_rt` cell); like `stop` it terminates the stream -- nothing after
/// the first `err`/`stop` is meaningful.
pub const Msg = union(enum) {
    data: FatPtr,
    err: FatPtr,
    stop: void,
};

pub const CAPACITY: u64 = 256; // power of two
const MASK: u64 = CAPACITY - 1;
/// Refill hysteresis: a producer parked on a full ring is only woken once the
/// occupancy has dropped below this, so one park/wake round trip is amortised
/// over a large refill batch instead of lock-stepping one wake per element
/// against a slower consumer.
const REFILL: u64 = CAPACITY / 2;

pub const EnqueueResult = enum { ok, closed };
pub const DequeueResult = union(enum) { msg: Msg, closed: void };

pub const Ring = struct {
    buf: []Msg,
    /// Consumer position; only the consumer advances it.
    head: std.atomic.Value(u64),
    /// Producer position; only the producer advances it.
    tail: std.atomic.Value(u64),
    producer_closed: std.atomic.Value(bool),
    consumer_closed: std.atomic.Value(bool),
    producer_waiter: std.atomic.Value(?*JoinObligation),
    consumer_waiter: std.atomic.Value(?*JoinObligation),

    /// GC-heap allocated and never freed explicitly -- reclaimed by the GC once
    /// the pipeline run that owns it returns (same convention as terminal
    /// cancel Scopes). The buffer lives on the GC heap so in-flight FatPtrs are
    /// conservatively scanned.
    pub fn create() *Ring {
        const r = gc.allocator.create(Ring) catch @panic("OOM");
        r.* = .{
            .buf = gc.allocator.alloc(Msg, CAPACITY) catch @panic("OOM"),
            .head = std.atomic.Value(u64).init(0),
            .tail = std.atomic.Value(u64).init(0),
            .producer_closed = std.atomic.Value(bool).init(false),
            .consumer_closed = std.atomic.Value(bool).init(false),
            .producer_waiter = std.atomic.Value(?*JoinObligation).init(null),
            .consumer_waiter = std.atomic.Value(?*JoinObligation).init(null),
        };
        return r;
    }

    /// Producer side. Parks when full. On `.closed` the payload has already
    /// been dropped -- the consumer is gone and nothing downstream can observe it.
    pub fn enqueue(self: *Ring, msg: Msg) EnqueueResult {
        while (true) {
            if (self.consumer_closed.load(.acquire)) {
                dropMsg(msg);
                return .closed;
            }
            const head = self.head.load(.acquire);
            const tail = self.tail.load(.monotonic);
            if (tail -% head < CAPACITY) {
                self.buf[tail & MASK] = msg;
                self.tail.store(tail +% 1, .release);
                wake(&self.consumer_waiter);
                return .ok;
            }
            // Full: publish a waiter, then re-check (Dekker pair with the
            // consumer's [advance head; swap producer_waiter]). Either we see
            // the freed slot / close here, or the consumer sees our waiter.
            var obl = JoinObligation.init();
            self.producer_waiter.store(&obl, .seq_cst);
            const head2 = self.head.load(.seq_cst);
            if (tail -% head2 < CAPACITY or self.consumer_closed.load(.seq_cst)) {
                if (self.producer_waiter.cmpxchgStrong(&obl, null, .acquire, .monotonic) == null) {
                    continue; // reclaimed the slot; retry immediately
                }
                // The consumer claimed our obligation and will fulfill it:
                // absorb that wake before the frame dies, then retry.
            }
            // About to sleep: repair any guarded wake the consumer may have
            // missed, so both sides can never end up parked at once. A
            // spurious consumer wake is harmless (it re-checks and re-parks).
            wakeForce(&self.consumer_waiter);
            _ = completion.wait(&obl);
        }
    }

    /// Consumer side. Parks when empty. `.closed` means the producer closed
    /// abnormally (teardown) and the ring is drained -- treat like end-of-input.
    pub fn dequeue(self: *Ring) DequeueResult {
        while (true) {
            if (self.takeOne()) |msg| return .{ .msg = msg };
            if (self.producer_closed.load(.acquire)) {
                if (self.takeOne()) |msg| return .{ .msg = msg }; // close raced a final enqueue
                return .closed;
            }
            var obl = JoinObligation.init();
            self.consumer_waiter.store(&obl, .seq_cst);
            const tail2 = self.tail.load(.seq_cst);
            const head = self.head.load(.monotonic);
            if (tail2 != head or self.producer_closed.load(.seq_cst)) {
                if (self.consumer_waiter.cmpxchgStrong(&obl, null, .acquire, .monotonic) == null) {
                    continue;
                }
            }
            // About to sleep: repair any guarded/threshold wake the producer
            // may have missed, so both sides can never end up parked at once.
            wakeForce(&self.producer_waiter);
            _ = completion.wait(&obl);
        }
    }

    /// Non-parking pop; used by `dequeue` and by teardown draining.
    fn takeOne(self: *Ring) ?Msg {
        const head = self.head.load(.monotonic);
        const tail = self.tail.load(.acquire);
        if (tail == head) return null;
        const msg = self.buf[head & MASK];
        self.head.store(head +% 1, .release);
        // A producer only parks on a completely full ring; hold its wake back
        // until the ring is half drained so it refills in one big batch.
        if (tail -% (head +% 1) < REFILL) wake(&self.producer_waiter);
        return msg;
    }

    pub fn closeProducer(self: *Ring) void {
        self.producer_closed.store(true, .seq_cst);
        wakeForce(&self.consumer_waiter);
    }

    pub fn closeConsumer(self: *Ring) void {
        self.consumer_closed.store(true, .seq_cst);
        wakeForce(&self.producer_waiter);
    }

    /// After both sides have exited (caller has joined all stages): drop any
    /// in-flight payloads so their refcounts release. The buffer itself is left
    /// to the GC.
    pub fn drainDrop(self: *Ring) void {
        while (self.takeOne()) |msg| dropMsg(msg);
    }
};

/// Fast-path wake: skip the RMW entirely when no waiter is published, making
/// the per-message cost a plain load in the common nobody-parked case. The
/// guard load can in principle read a stale null; that is safe because every
/// miss has a retry -- the next message re-attempts the wake, and the paths
/// with no next message (parking opposite the peer, closing an end) use
/// `wakeForce` instead.
fn wake(slot: *std.atomic.Value(?*JoinObligation)) void {
    if (slot.load(.seq_cst) == null) return;
    wakeForce(slot);
}

/// Unconditional wake: an RMW always reads the newest value in the slot's
/// modification order, so it can never miss a published waiter.
fn wakeForce(slot: *std.atomic.Value(?*JoinObligation)) void {
    if (slot.swap(null, .seq_cst)) |obl| {
        completion.fulfill(obl, completion.encodeInt(0));
    }
}

fn dropMsg(msg: Msg) void {
    switch (msg) {
        .data, .err => |fp| fp.rc_decrement(),
        .stop => {},
    }
}
