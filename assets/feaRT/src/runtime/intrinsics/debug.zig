const std = @import("std");
const objs = @import("../objs.zig");
const io = @import("caps/io.zig");
const str_rt = @import("strings/index.zig");
const root = @import("root");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

const str_sig = h("read .str/0");

/// Returns the result of `.str/0` if it exists, or the type name attached to the fat pointer's vtable otherwise
fn value_str(x: FatPtr) FatPtr {
    switch (x.vt.storage_mode) {
        // Numbers bypass vtable lookup, and every one of them answers `.str`.
        .primitive => return objs.call(x, str_sig, .{}, @src()),
        // `Var` and `IsoPod` have no `.str`; only their type is meaningful.
        .primitiveContainer => return type_name_str(x),
        .heap, .singleton, .transient => {
            if (x.vt.method_name(str_sig) != null) {
                const result = objs.call(x, str_sig, .{}, @src());
                x.rc_decrement();
                return result;
            }
            return type_name_str(x);
        },
    }
}

/// Consumes `x`.
fn type_name_str(x: FatPtr) FatPtr {
    defer x.rc_decrement();
    return str_rt.make_str_copy(x.vt.type_name);
}

fn make_void() FatPtr {
    return objs.obj_k_singleton(&root.pkg_base.VT_Void_0);
}

fn debug_println(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    _ = self;
    io.println_stderr(value_str(x));
    return make_void();
}

/// `Debug#x` prints `x` and hands it straight back, so it can be dropped into
/// the middle of an expression.
fn debug_apply(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    _ = self;
    io.println_stderr(value_str(x.share()));
    return x;
}

/// `.identify(x)` names `x`'s type rather than printing anything.
fn debug_identify(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    _ = self;
    return type_name_str(x);
}

pub const VT_Debug: objs.VTable = .{
    .type_name = "base.Debug/0",
    .hashes = &.{
        h("imm #/1"),
        h("imm .println/1"),
        h("imm .identify/1"),
    },
    .methods = &.{
        @ptrCast(&debug_apply),
        @ptrCast(&debug_println),
        @ptrCast(&debug_identify),
    },
    .method_names = &.{
        "imm #/1",
        "imm .println/1",
        "imm .identify/1",
    },
    .storage_mode = .singleton,
};
