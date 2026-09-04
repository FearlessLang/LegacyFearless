const std = @import("std");

/// A test-and-set spin lock. `extern` so it can sit inside the C-layout
/// headers the cycle collector reads.
pub const WriteLock = extern struct {
    is_locked: std.atomic.Value(bool) = std.atomic.Value(bool).init(false),

    pub fn acquire(self: *WriteLock) void {
        while (self.is_locked.swap(true, .acquire) != false) {
            std.atomic.spinLoopHint();
        }
    }

    pub fn release(self: *WriteLock) void {
        self.is_locked.store(false, .release);
    }
};

test "concurrent increment under lock" {
    const thread_count = 8;
    const increments_per_thread = 10_000;

    var lock: WriteLock = .{};
    var counter: usize = 0;

    var threads: [thread_count]std.Thread = undefined;
    for (&threads) |*t| {
        t.* = try std.Thread.spawn(.{}, struct {
            fn run(lk: *WriteLock, ctr: *usize) void {
                for (0..increments_per_thread) |_| {
                    lk.acquire();
                    ctr.* += 1;
                    lk.release();
                }
            }
        }.run, .{ &lock, &counter });
    }
    for (threads) |t| t.join();

    try std.testing.expectEqual(thread_count * increments_per_thread, counter);
}
