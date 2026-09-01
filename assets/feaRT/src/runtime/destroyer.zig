const std = @import("std");
const gc = @import("gc.zig");
const process = @import("process_singletons.zig");
const libgc = @import("libgc");

// `GC_free_inner` is the unlocked free body that bdwgc's own `GC_free` runs
// after taking the allocator lock. It's marked `GC_INNER` (hidden visibility)
// in `gc_priv.h`, but the symbol lives in the static libgc archive we link,
// so a manual extern resolves at link time. We use it from inside
// `GC_call_with_alloc_lock` so a whole drained chain pays the lock cost
// exactly once -- without it, profiling shows the destroyer spending ~40% of
// wall time blocked in `pthread_mutex_lock` fighting workers for the lock.
extern fn GC_free_inner(p: ?*anyopaque) void;

/// Single dedicated thread that owns every `GC_free` call. Worker threads
/// push raw pointers onto a Treiber stack here; the destroyer drains the
/// entire backlog with one atomic swap and then walks the chain with no
/// atomics. Removes bdwgc-lock contention from worker hot paths and lets
/// the libc free body run concurrently with worker progress on a separate
/// core.
///
/// The link cell is overlaid on the first 8 bytes of each freed allocation,
/// so there's no separate node allocation. Every producer must therefore
/// hand us a pointer that is ≥ 8 bytes and 8-byte aligned -- see the
/// comptime/runtime asserts below.
pub const Node = extern struct { next: ?*Node };

var head: std.atomic.Value(?*Node) align(std.atomic.cache_line) = .{ .raw = null };

pub fn init() void {
    const thread = std.Thread.spawn(.{}, destroyerLoop, .{}) catch @panic("Failed to spawn destroyer");
    thread.detach();
}

pub fn submit(p: *anyopaque) void {
    std.debug.assert(@intFromPtr(p) % 8 == 0);
    const node: *Node = @ptrCast(@alignCast(p));
    var old = head.load(.monotonic);
    while (true) {
        node.next = old;
        if (head.cmpxchgWeak(old, node, .release, .monotonic)) |observed| {
            old = observed;
        } else return;
    }
}

/// Splice a pre-linked chain `chain_head -> ... -> chain_tail` onto the
/// stack with one CAS. Caller has already linked the chain locally with
/// non-atomic stores; the `.release` here ensures those link writes are
/// visible to the consumer after its `.acquire` swap.
pub fn submit_chain(chain_head: *Node, chain_tail: *Node) void {
    std.debug.assert(@intFromPtr(chain_head) % 8 == 0);
    std.debug.assert(@intFromPtr(chain_tail) % 8 == 0);
    var old = head.load(.monotonic);
    while (true) {
        chain_tail.next = old;
        if (head.cmpxchgWeak(old, chain_head, .release, .monotonic)) |observed| {
            old = observed;
        } else return;
    }
}

fn freeChainLocked(arg: ?*anyopaque) callconv(.c) ?*anyopaque {
    var n: ?*Node = @ptrCast(@alignCast(arg));
    while (n) |node| {
        const next = node.next;
        GC_free_inner(node);
        n = next;
    }
    return null;
}

fn destroyerLoop() void {
    gc.register_thread();
    defer gc.unregister_thread();

    while (true) {
        const n = head.swap(null, .acquire);
        if (n == null) {
            process.idle(1);
            continue;
        }
        _ = libgc.GC_call_with_alloc_lock(freeChainLocked, n);
    }
}
