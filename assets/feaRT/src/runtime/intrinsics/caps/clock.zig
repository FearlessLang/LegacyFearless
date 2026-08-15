const std = @import("std");
const objs = @import("../../objs.zig");
const nat_rt = @import("../nat.zig");
const process = @import("../../process_singletons.zig");

const FatPtr = objs.FatPtr;

/// `Clock.monotonic` -- nanoseconds from a monotonic source, matching the Java
/// backend's `System.nanoTime`. The epoch is unspecified, so only differences
/// between two readings are meaningful. `.awake` is the platform's monotonic
/// clock (`CLOCK_MONOTONIC` on Linux); its timestamps never go backwards, so
/// the cast to the unsigned `Nat` representation is safe once clamped at zero.
fn clock_monotonic(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const ts = std.Io.Clock.now(.awake, process.runtime_io);
    const nanos = ts.nanoseconds;
    return nat_rt.make(if (nanos <= 0) 0 else @intCast(nanos));
}

fn clock_iso(self: FatPtr) callconv(.c) FatPtr {
    return self;
}

fn clock_self(self: FatPtr) callconv(.c) FatPtr {
    return self;
}

const h = objs.hash_signature;

pub const VT_Clock: objs.VTable = .{
    .type_name = "base.caps.Clock/0",
    .hashes = &.{
        h("mut .monotonic/0"),
        h("mut .iso/0"),
        h("mut .self/0"),
    },
    .methods = &.{
        @ptrCast(&clock_monotonic),
        @ptrCast(&clock_iso),
        @ptrCast(&clock_self),
    },
    .method_names = &.{
        "mut .monotonic/0",
        "mut .iso/0",
        "mut .self/0",
    },
    .storage_mode = .singleton,
};

pub fn make_clock() FatPtr {
    return objs.obj_k_singleton(&VT_Clock);
}
