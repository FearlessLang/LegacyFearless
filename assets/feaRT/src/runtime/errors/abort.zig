const std = @import("std");
const objs = @import("../objs.zig");
const unwind = @import("unwind.zig");
const trace = @import("./trace.zig");

const writeStderr = @import("./io.zig").writeStderr;

const h = objs.hash_signature;

const FatPtr = objs.FatPtr;

/// Lowering target for `base.Abort!`/`base.Magic!` (the `magicAbort` stub).
/// Prints `msg` to stderr and exits with code 1. `noreturn` for the same
/// sub-expression-slot reason as `throwDeterministic`.
pub fn abortProgram(msg: []const u8) noreturn {
    writeStderr(msg);
    writeStderr("\n");
    trace.printCrashTrace();
    std.process.exit(1);
}

fn abort_bang(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    abortProgram("Program aborted");
}

pub const VT_Abort: objs.VTable = .{
    .type_name = "base.Abort/0",
    .hashes = &.{h("imm !/0")},
    .methods = &.{@as(*const anyopaque, @ptrCast(&abort_bang))},
    .method_names = &.{"imm !/0"},
    .storage_mode = .singleton,
};

fn magic_bang(self: FatPtr) callconv(.c) FatPtr {
    _ = self;
    abortProgram("No magic code was found");
}

fn magic_bang_sys(self: FatPtr, sys: FatPtr) callconv(.c) FatPtr {
    _ = .{ self, sys };
    abortProgram("No magic code was found");
}

pub const VT_Magic: objs.VTable = .{
    .type_name = "base.Magic/0",
    .hashes = &.{ h("imm !/0"), h("imm !/1") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&magic_bang)),
        @as(*const anyopaque, @ptrCast(&magic_bang_sys)),
    },
    .method_names = &.{ "imm !/0", "imm !/1" },
    .storage_mode = .singleton,
};
