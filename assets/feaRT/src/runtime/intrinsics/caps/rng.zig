const std = @import("std");
const objs = @import("../../objs.zig");
const nat_rt = @import("../nat.zig");
const process = @import("../../process_singletons.zig");

const FatPtr = objs.FatPtr;

/// `RandomSeed#` -- a fresh, non-zero 64-bit seed drawn from the platform's
/// entropy source, as the Java backend draws from `SecureRandom`.
/// `base.rng.FRandom` errors on a zero seed, so the draw is repeated until it is
/// non-zero. If no secure source is available the process-wide `std.Io` falls
/// back to its own seeded generator rather than failing the program.
fn random_seed_next(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const entropy = process.runtime_io;
    var bytes: [8]u8 = undefined;
    var seed: u64 = 0;
    while (seed == 0) {
        entropy.randomSecure(&bytes) catch entropy.random(&bytes);
        seed = std.mem.readInt(u64, &bytes, .big);
    }
    return nat_rt.make(seed);
}

fn random_seed_iso(self: FatPtr) callconv(.c) FatPtr {
    return self;
}

fn random_seed_self(self: FatPtr) callconv(.c) FatPtr {
    return self;
}

const h = objs.hash_signature;

pub const VT_RandomSeed: objs.VTable = .{
    .type_name = "base.caps.RandomSeed/0",
    .hashes = &.{
        h("mut #/0"),
        h("mut .iso/0"),
        h("mut .self/0"),
    },
    .methods = &.{
        @ptrCast(&random_seed_next),
        @ptrCast(&random_seed_iso),
        @ptrCast(&random_seed_self),
    },
    .method_names = &.{
        "mut #/0",
        "mut .iso/0",
        "mut .self/0",
    },
    .storage_mode = .singleton,
};

pub fn make_rng() FatPtr {
    return objs.obj_k_singleton(&VT_RandomSeed);
}
