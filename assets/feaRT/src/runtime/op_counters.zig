//! Opt-in counters for the operations the scalar performance work is meant to
//! remove: object construction, refcount traffic and method dispatch.
//!
//! Wall time moves for many reasons, so the measure of an optimisation is the
//! count of operations it removes. Build with `-Dop_counters=true`, which the
//! compiler passes when `FEART_OP_COUNTERS` is in the environment, and the
//! program prints a table to stderr at exit. With the option off every function
//! here is an empty inline body.

const std = @import("std");
/// Test this before evaluating anything that exists only to classify a bump:
/// with the option off such work must not be on the path at all.
pub const ENABLED = @import("build_options").op_counters;

/// Adding a kind needs no other change: the dump walks the enum.
pub const Op = enum {
    /// `rt.obj_k`: a refcounted heap object.
    heap_obj,
    /// `rt.obj_k_singleton`: a static instance, no refcounting.
    singleton_obj,
    /// `rt.init_transient_obj`: a stack slot, no refcounting.
    transient_obj,
    /// A live transient that had to become a heap object.
    boxed_transient,
    rc_increment,
    rc_decrement,
    /// An increment that missed the biased count and paid for an atomic. The
    /// biased hit rate is `1 - rc_shared_increment / rc_increment`.
    rc_shared_increment,
    rc_shared_decrement,
    /// A fold of a biased count into a shared count. At most one per object.
    rc_merge,
    /// An object handed to its owning worker because a foreign worker released a
    /// reference the biased half still covered.
    rc_queue_push,
    /// `rt.call` on an object receiver: storage-mode switch, then inline cache.
    virtual_call,
    /// `rt.call` that the storage-mode switch sent to an intrinsic module.
    virtual_call_primitive,
    /// A call site that reached an intrinsic module with no storage-mode switch.
    direct_call_primitive,
    /// A probe that missed entry 0 and walked the polymorphic entries.
    ic_slow_probe,
    /// A `doPromote` that published its join obligation and was charged for.
    promotion,
    promotion_miss_no_frame,
    promotion_miss_cancelled,
    /// Always zero: a task deque grows rather than refusing. Kept so a counter
    /// dump holds its shape.
    promotion_miss_queue_full,
    /// The publish CAS lost: the parent or another promoter claimed the frame.
    promotion_miss_cas_lost,
    /// No obligation or task left in the worker pools.
    promotion_miss_pool_empty,
    /// Always zero, like `promotion_miss_queue_full`: an enqueue has no way to
    /// report a loss back to the promoter.
    promotion_miss_enqueue_fail,
    /// A published promotion the parent took back at its join point because no
    /// worker had claimed it. Bounds how much of the promotion rate becomes
    /// real fibers.
    promotion_reclaimed,
    /// No worker, fiber or shadow stack. Expect ~0; anything else means the
    /// thread-local swap is wrong.
    promotion_miss_no_fiber,
    /// A shadow frame pushed at a VPF call site: the ceiling on promotions.
    vpf_frame_push,
    /// Taken from another worker's deque by the steal step of `findTask`.
    task_stolen,
    /// A task that won its claim at dequeue and got a real fiber, with the stack
    /// that costs. Bounded by `promotion - promotion_reclaimed`.
    thief_fiber_created,
    /// Dequeued after the parent had reclaimed it. The dequeue and the recycle
    /// are wasted, but no stack was spent.
    task_dequeue_claim_lost,
    /// One token granted, so one `tryPromote` call, so one frame. This is `W` in
    /// the APM cost model and the denominator of the Theorem 4.1 bound
    /// `promotions <= W / TOKENS_THRESHOLD`. Strictly larger than
    /// `vpf_frame_push`, which counts VPF call sites only.
    ///
    /// Bumps a contended atomic on the hottest path in the program. Read it for
    /// structure, never for timing.
    token_granted,
    /// A worker asking for a cycle collection because its heap has grown past
    /// the threshold. The trigger counter is per-thread but the collection it
    /// asks for is global, so read this against the worker count: if it scales
    /// with the number of workers, the threshold is being reached independently
    /// on each one.
    cycle_collection,
    /// A collection that ran: the world stopped and the passes completed.
    cycle_collection_run,
    /// A collection given up because the world would not stop inside the
    /// budget, or because another one already held it. Read against
    /// `cycle_collection`: a count that tracks it means fibers are running
    /// longer than the budget without reaching their scheduler.
    cycle_collection_abandoned,
    /// A node entered into the candidate set: a container that took an edge it
    /// could not have been born with. Read against `heap_obj` for the share of
    /// a program's objects a cycle could possibly run through.
    cycle_candidate_added,
    /// A candidate root the collector examined.
    cycle_root_examined,
    /// A node freed as part of a garbage cycle. Nothing but the collector could
    /// have reclaimed it.
    cycle_node_freed,
    /// A heap object born green: it cannot reach a mutable container, so it can
    /// never be part of a cycle. Read against `heap_obj` for the share of a
    /// program's objects a cycle cannot run through.
    green_obj,
    /// Reference count operations whose node is green. Read against
    /// `rc_increment` and `rc_decrement` for the share of the counting traffic
    /// that belongs to objects no cycle can run through.
    rc_increment_green,
    rc_decrement_green,
};

const COUNT = @typeInfo(Op).@"enum".fields.len;

var counters: [COUNT]std.atomic.Value(u64) = @splat(std.atomic.Value(u64).init(0));

/// `.monotonic` is enough: the totals are read only after every worker has
/// stopped, and a lost update would not change a conclusion drawn from millions
/// of operations.
pub inline fn bump(comptime op: Op) void {
    if (!ENABLED) return;
    _ = counters[@intFromEnum(op)].fetchAdd(1, .monotonic);
}

/// Prints the table to stderr.
///
/// A devirtualised call site has no counter: nothing on its path is shared with
/// another call form, so a bump would be its only cost. Read its count as the
/// drop in `virtual_call` between two builds.
pub fn dump() void {
    if (!ENABLED) return;
    std.debug.print("[op_counters]\n", .{});
    inline for (@typeInfo(Op).@"enum".fields) |field| {
        const value = counters[field.value].load(.monotonic);
        std.debug.print("[op_counters] {s}\t{d}\n", .{ field.name, value });
    }
}
