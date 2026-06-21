const std = @import("std");

/// Set by `main` from `init.io`. The process-wide `std.Io` interface.
/// Set once before any worker thread is spawned, so concurrent reads need no synchronisation.
pub var runtime_io: std.Io = undefined;

/// Set by `main` from `init.minimal.args.vector`: the process argv (argv[0] is
/// the binary path). Read by `env.zig`'s `launchArgs`. Set once before any
/// worker thread is spawned, so concurrent reads need no synchronisation.
pub var launch_args_vec: []const [*:0]const u8 = &.{};

pub fn set_runtime_io(io: std.Io) void {
	runtime_io = io;
}

pub fn set_launch_args(v: []const [*:0]const u8) void {
	launch_args_vec = v;
}

pub fn launch_args() []const [*:0]const u8 {
	return launch_args_vec;
}
