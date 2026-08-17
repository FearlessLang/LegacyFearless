//! Opt-in counters for the operations the scalar performance work is meant to remove:
//! object construction, refcount traffic and method dispatch.
//!
//! Wall time on a benchmark moves for many reasons, so the measure of an optimisation is the
//! count of operations it removes. Build with `-Dop_counters=true` (the compiler passes it when
//! `FEART_OP_COUNTERS` is set in the environment) and the program prints a table to stderr at
//! exit. With the option off, every function here is an empty inline body and the counters
//! compile out.

const std = @import("std");
const ENABLED = @import("build_options").op_counters;

/// One counter per operation kind. Adding a kind needs no other change: the dump walks the enum.
pub const Op = enum {
    /// `rt.obj_k`: a refcounted heap object.
    heap_obj,
    /// `rt.obj_k_singleton`: a statically allocated instance, no refcounting.
    singleton_obj,
    /// `rt.init_transient_obj`: a stack slot, no refcounting.
    transient_obj,
    /// `FatPtr.box_transient` on a live transient: a stack slot that had to become a heap object.
    boxed_transient,
    /// An atomic increment of a heap object's refcount.
    rc_increment,
    /// An atomic decrement of a heap object's refcount.
    rc_decrement,
    /// `rt.call` on an object receiver: storage-mode switch, then the inline cache.
    virtual_call,
    /// `rt.call` that the storage-mode switch sent to an intrinsic module.
    virtual_call_primitive,
    /// A call site that reached an intrinsic module with no storage-mode switch.
    direct_call_primitive,
    /// An inline-cache probe that missed entry 0 and had to walk the polymorphic entries.
    ic_slow_probe,
};

const COUNT = @typeInfo(Op).@"enum".fields.len;

var counters: [COUNT]std.atomic.Value(u64) = @splat(std.atomic.Value(u64).init(0));

/// Records one operation. `.monotonic` is enough: the totals are only read after every worker has
/// stopped, and a lost update between threads would not change a conclusion drawn from millions of
/// operations.
pub inline fn bump(comptime op: Op) void {
    if (!ENABLED) return;
    _ = counters[@intFromEnum(op)].fetchAdd(1, .monotonic);
}

/// A devirtualised call site has no counter: nothing on its path is shared with any other call
/// form, so a bump there would be the only cost it has. Its count is the drop in `virtual_call`
/// between two builds, and the compiler reports how many call sites it rewrote.
///
/// Prints the table to stderr. Does nothing when the option is off.
pub fn dump() void {
    if (!ENABLED) return;
    std.debug.print("[op_counters]\n", .{});
    inline for (@typeInfo(Op).@"enum".fields) |field| {
        const value = counters[field.value].load(.monotonic);
        std.debug.print("[op_counters] {s}\t{d}\n", .{ field.name, value });
    }
}
