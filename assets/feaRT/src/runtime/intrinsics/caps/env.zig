const std = @import("std");
const objs = @import("../../objs.zig");
const process = @import("../../process_singletons.zig");
const str_rt = @import("../strings/index.zig");
const root = @import("root");
const pb = root.pkg_base;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

fn env_launchArgs(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const argv = process.launch_args();
    var acc = objs.obj_k_singleton(&pb.VT_LList_1);
    var i: usize = argv.len;
    while (i > 1) {
        i -= 1;
        const arg = std.mem.span(argv[i]);
        const s = str_rt.make_str_copy(arg);
        const next = objs.call(acc, comptime h("mut .pushFront/1"), .{s}, @src());
        acc.rc_decrement();
        acc = next;
    }
    return acc;
}

/// `ToIso[Env]` methods. Env is a stateless singleton, so both hand the
/// singleton straight back (RC ops on a singleton are no-ops).
fn env_iso(self: FatPtr) callconv(.c) FatPtr {
    return self;
}
fn env_self(self: FatPtr) callconv(.c) FatPtr {
    return self;
}

pub const VT_Env: objs.VTable = .{
    .type_name = "base.caps.Env/0",
    .hashes = &.{
        h("mut .launchArgs/0"),
        h("mut .iso/0"),
        h("mut .self/0"),
    },
    .methods = &.{
        @ptrCast(&env_launchArgs),
        @ptrCast(&env_iso),
        @ptrCast(&env_self),
    },
    .method_names = &.{
        "mut .launchArgs/0",
        "mut .iso/0",
        "mut .self/0",
    },
    .storage_mode = .singleton,
};

pub fn make_env() FatPtr {
    return objs.obj_k_singleton(&VT_Env);
}
