
const std = @import("std");
pub const ENABLED = @import("build_options").op_counters;

pub const Op = enum {
    heap_obj,
    heap_obj_freed,
    singleton_obj,
    transient_obj,
    boxed_transient,
    rc_increment,
    rc_decrement,
    rc_shared_increment,
    rc_shared_decrement,
    rc_merge,
    rc_queue_push,
    virtual_call,
    virtual_call_primitive,
    direct_call_primitive,
    ic_slow_probe,
    promotion,
    promotion_miss_no_frame,
    promotion_miss_cancelled,
    promotion_miss_queue_full,
    promotion_miss_cas_lost,
    promotion_miss_pool_empty,
    promotion_miss_enqueue_fail,
    promotion_reclaimed,
    promotion_miss_no_fiber,
    vpf_frame_push,
    task_stolen,
    thief_fiber_created,
    task_dequeue_claim_lost,
    obligation_alloc,
    obligation_free,
    token_granted,
    cycle_collection,
    cycle_collection_run,
    cycle_collection_abandoned,
    cycle_candidate_added,
    cycle_root_examined,
    cycle_node_freed,
    green_obj,
    rc_increment_green,
    rc_decrement_green,
};

const COUNT = @typeInfo(Op).@"enum".fields.len;

var counters: [COUNT]std.atomic.Value(u64) = @splat(std.atomic.Value(u64).init(0));

pub inline fn bump(comptime op: Op) void {
    if (!ENABLED) return;
    _ = counters[@intFromEnum(op)].fetchAdd(1, .monotonic);
}

const TypeCell = struct {
    kind: Op,
    name: []const u8,
    count: *std.atomic.Value(u64),
    next: ?*TypeCell,
};

var type_cells: std.atomic.Value(?*TypeCell) = .init(null);

pub inline fn bumpType(comptime kind: Op, comptime name: []const u8) void {
    if (!ENABLED) return;
    const Cell = struct {
        var count: std.atomic.Value(u64) = .init(0);
        var cell: TypeCell = .{ .kind = kind, .name = name, .count = &count, .next = null };
        var listed: std.atomic.Value(bool) = .init(false);
    };
    if (!Cell.listed.swap(true, .acq_rel)) {
        var head = type_cells.load(.acquire);
        while (true) {
            Cell.cell.next = head;
            head = type_cells.cmpxchgWeak(head, &Cell.cell, .acq_rel, .acquire) orelse break;
        }
    }
    _ = Cell.count.fetchAdd(1, .monotonic);
}

pub fn dump() void {
    if (!ENABLED) return;
    std.debug.print("[op_counters]\n", .{});
    inline for (@typeInfo(Op).@"enum".fields) |field| {
        const value = counters[field.value].load(.monotonic);
        std.debug.print("[op_counters] {s}\t{d}\n", .{ field.name, value });
    }
    var cell = type_cells.load(.acquire);
    while (cell) |c| : (cell = c.next) {
        std.debug.print("[op_counters] by_type\t{s}\t{s}\t{d}\n", .{
            @tagName(c.kind), c.name, c.count.load(.monotonic),
        });
    }
}
