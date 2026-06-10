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
//! depth on each query — fine because cancel checks are hot only at chunk
//! boundaries (CHUNK_THRESHOLD in flows/exec.zig).
//!
//! ### Lifetime
//! Scopes live on the GC heap (allocated by `pushScope` in the flow-instance
//! VT). The TLS `active_scope`, `Fiber.saved_scope`, and `StolenTask.scope`
//! are all GC-tracked roots, so the Scope stays live for as long as any of
//! those references it — even if the terminal that pushed it has already
//! returned. (Earlier revisions stack-allocated the Scope and relied on the
//! terminal outliving every reader; that invariant proved fragile under
//! enqueue/CAS races at low APM thresholds.)
//!
//! ### Fiber interaction
//! `active_scope` is a `threadlocal` just like `tls_tokens_ptr` and
//! `shadow_stack`, but the authoritative per-fiber state lives in
//! `Fiber.saved_scope`. `fiber.switchFiber` saves the current TLS into the
//! outgoing fiber's slot and loads the incoming fiber's slot into TLS. Stolen
//! tasks capture the promoter's scope into `StolenTask.scope` at promotion
//! time (heartbeat.zig); the thief fiber's `saved_scope` is seeded from that
//! before its first switchFiber installs it into TLS.

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

/// TLS pointer to the current fiber's active scope. Null when no scope has
/// been pushed on this fiber (e.g. the scheduler fiber, or below any terminal).
pub threadlocal var active_scope: ?*Scope = null;

/// True if the currently-active scope (or any of its ancestors) is cancelled.
/// Null scope → false. Hot path reads this once per chunk in run_chunk; keep
/// the load order monotonic so the compiler doesn't pessimise the fast-path.
pub fn currentCancelled() bool {
    if (active_scope) |s| return s.cancelled();
    return false;
}
