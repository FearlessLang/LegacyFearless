//! Cooperative cancellation scope.
//!
//! A Scope is a shared cancel bit plus an optional parent pointer. Each
//! flow-terminal entry pushes a fresh scope onto the current fiber's TLS slot;
//! work below that point (Fearless drive* recursion, Zig run_chunk polling,
//! stolen thief fibers) reads the TLS scope and bails out on cancel.
//!
//! The `parent` link supports nested scopes: when an inner scope is queried,
//! cancellation bubbles up through its ancestors. This keeps cancel cheap
//! (parent load per check, no child-list broadcast) at the cost of walking
//! depth on each query -- fine because cancel checks are hot only at chunk
//! boundaries (CHUNK_THRESHOLD in flows/exec.zig).
//!
//! ### Lifetime
//! Scopes live on the GC heap (allocated by `pushScope` in the flow-instance
//! VT). `Fiber.saved_scope` and `StolenTask.scope` are both GC-tracked roots,
//! so the Scope stays live for as long as either references it -- even if the
//! terminal that pushed it has already returned.
//!
//! ### Fiber interaction
//! `Fiber.saved_scope` is the only copy, reached through the fiber header, so a
//! fiber switch neither saves nor restores it. Stolen tasks capture the
//! promoter's scope into `StolenTask.scope` at promotion time (heartbeat.zig),
//! and the thief fiber's `saved_scope` is seeded from that at creation. The
//! promotion reads the scope recorded in the shadow frame, not the one the
//! promoter has reached, so a nested terminal's cancel stays inside itself.

const std = @import("std");

pub const Scope = struct {
    cancel: std.atomic.Value(bool),
    parent: ?*Scope,

    pub fn init(parent: ?*Scope) Scope {
        return .{ .cancel = std.atomic.Value(bool).init(false), .parent = parent };
    }

    /// Request cancellation. Single-bit write; other threads observe via
    /// `cancelled()` (acquire-load). Safe to call from any fiber/thread.
    pub fn request(self: *Scope) void {
        self.cancel.store(true, .release);
    }

    /// True if this scope or any ancestor has been cancelled.
    pub fn cancelled(self: *const Scope) bool {
        if (self.cancel.load(.acquire)) return true;
        var cursor = self.parent;
        while (cursor) |p| {
            if (p.cancel.load(.acquire)) return true;
            cursor = p.parent;
        }
        return false;
    }
};

/// The running fiber's active scope. Null when no scope has been pushed on it,
/// and off a fiber stack.
///
/// Resolved through the worker rather than by masking `sp`: pipeline drivers and
/// accept callbacks reach this from stacks that are not always a fiber's, where
/// a mask would land on unrelated memory. `tryPromote` uses `cancelledOn`.
pub fn activeScope() ?*Scope {
    const f = fiber_mod.currentFiberOrNull() orelse return null;
    return f.saved_scope;
}

pub fn setActiveScope(s: ?*Scope) void {
    const f = fiber_mod.currentFiberOrNull() orelse return;
    f.saved_scope = s;
}

/// True if the currently-active scope (or any of its ancestors) is cancelled.
/// Null scope -> false. Read once per chunk in run_chunk.
pub fn currentCancelled() bool {
    if (activeScope()) |s| return s.cancelled();
    return false;
}

/// As `currentCancelled`, for a caller that already holds the fiber.
pub inline fn cancelledOn(f: *const fiber_mod.Fiber) bool {
    if (f.saved_scope) |s| return s.cancelled();
    return false;
}

const fiber_mod = @import("fiber.zig");
