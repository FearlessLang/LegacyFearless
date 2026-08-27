const std = @import("std");
const objs = @import("../objs.zig");
const bool_intrinsics = @import("bool.zig");
const str_rt = @import("strings/index.zig");
const gc = @import("../gc.zig");
const root = @import("root");
const native = root.native;
const pb = root.pkg_base;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

/// A compiled regex. `handle` is the native `frt_regex_compile` handle (dropped
/// on release); `pattern` is the original pattern Str, returned by `.str`.
pub const RegexCaptures = extern struct {
    handle: usize, // *anyopaque
    pattern: FatPtr,
};

fn deref_regex(fp: FatPtr) *const RegexCaptures {
    return objs.deref(RegexCaptures, fp);
}

fn regex_str(self: FatPtr) callconv(.c) FatPtr {
    return deref_regex(self).pattern.share();
}

fn regex_is_match(self: FatPtr, haystack: FatPtr) callconv(.c) FatPtr {
    defer haystack.rc_decrement();
    const hay = str_rt.deref_str(haystack);
    const handle: ?*const anyopaque = @ptrFromInt(deref_regex(self).handle);
    return bool_intrinsics.to_bool(native.frt_regex_is_match(handle, hay.ptr, hay.len));
}

fn regex_drop(header: *anyopaque, releasing_worker_id: u32) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(RegexCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    native.frt_regex_drop(@ptrFromInt(self.captures.handle));
    self.captures.pattern.rc_decrement_as(releasing_worker_id);
}

pub const VT_Regex: objs.VTable = .{
    .type_name = "base.Regex/0",
    .hashes = &.{ h("read .str/0"), h("imm .isMatch/1") },
    .methods = &.{ @ptrCast(&regex_str), @ptrCast(&regex_is_match) },
    .method_names = &.{ "read .str/0", "imm .isMatch/1" },
    .drop_fn = regex_drop,
};

/// `Regexs#(pattern: Str): Regex` -- compile the pattern, raising a deterministic
/// `FearlessError` carrying the native compiler's message on failure.
fn regexs_compile(self: FatPtr, pattern_fp: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const pat = str_rt.deref_str(pattern_fp);
    var err: native.frt_buf = undefined;
    const handle = native.frt_regex_compile(pat.ptr, pat.len, &err);
    if (handle == null) {
        // throwDeterministic unwinds past Zig `defer`, so release explicitly.
        const info = compile_error_info(err);
        pattern_fp.rc_decrement();
        root.errors.throwDeterministic(info);
    }
    // obj_k shares `pattern` into the captures; release our incoming reference.
    const regex = objs.obj_k(RegexCaptures, &VT_Regex, .{
        .handle = @intFromPtr(handle.?),
        .pattern = pattern_fp,
    });
    pattern_fp.rc_decrement();
    return regex;
}

/// Build an `Infos.msg(...)` from a compile-error buffer, then free the buffer.
fn compile_error_info(err: native.frt_buf) FatPtr {
    const msg = if (err.ptr) |p| blk: {
        const buf = gc.recycleAllocSlice(u8, err.len);
        @memcpy(buf, p[0..err.len]);
        native.frt_buf_free(err);
        break :blk str_rt.make_owned_str(buf.ptr, err.len);
    } else str_rt.make_str_from_literal("Invalid regex pattern");
    return objs.call(objs.obj_k_singleton(&pb.VT_Infos_0), comptime h("imm .msg/1"), .{msg}, @src());
}

pub const VT_Regexs: objs.VTable = .{
    .type_name = "base.Regexs/0",
    .hashes = &.{h("imm #/1")},
    .methods = &.{@ptrCast(&regexs_compile)},
    .method_names = &.{"imm #/1"},
    .storage_mode = .singleton,
};
