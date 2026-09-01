const std = @import("std");

/// Set by `main` from `init.io`. The process-wide `std.Io` interface.
/// Set once before any worker thread is spawned, so concurrent reads need no synchronisation.
pub var runtime_io: std.Io = undefined;

/// Whether `runtime_io` holds an interface yet. It starts `undefined`, so a read
/// before `main` publishes one faults, and a test binary runs no `main` at all
/// while still starting the destroyer thread through `recycleDestroy`. Threads
/// that idle must ask this first; see `idle`.
var runtime_io_set: std.atomic.Value(bool) = std.atomic.Value(bool).init(false);

/// Set by `main` from `init.minimal.args.vector`: the process argv (argv[0] is
/// the binary path). Read by `env.zig`'s `launchArgs`. Set once before any
/// worker thread is spawned, so concurrent reads need no synchronisation.
pub var launch_args_vec: []const [*:0]const u8 = &.{};

pub fn set_runtime_io(io: std.Io) void {
	runtime_io = io;
	runtime_io_set.store(true, .release);
}

/// Waits `ms` in a loop that has no work, without reading `runtime_io` before
/// `main` sets it. Yielding is enough for the unset case: only a test binary
/// reaches it, where the thread has no throughput to protect.
pub fn idle(ms: i64) void {
	if (runtime_io_set.load(.acquire)) {
		std.Io.sleep(runtime_io, std.Io.Duration.fromMilliseconds(ms), .awake) catch {};
		return;
	}
	std.Thread.yield() catch {};
}

pub fn set_launch_args(v: []const [*:0]const u8) void {
	launch_args_vec = v;
}

pub fn launch_args() []const [*:0]const u8 {
	return launch_args_vec;
}
