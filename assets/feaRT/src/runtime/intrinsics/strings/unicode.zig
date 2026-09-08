const std = @import("std");
const objs = @import("../../objs.zig");
const bool_intrinsics = @import("../bool.zig");
const nat_intrinsics = @import("../nat.zig");
const byte_intrinsics = @import("../byte.zig");
const list_intrinsics = @import("../list.zig");
const str = @import("str.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

/// Encode a Unicode scalar as UTF-8 into a fresh owned string. Invalid scalars
/// (surrogates / out of range) fall back to U+FFFD, matching how the Java
/// backend's `new String(int[]{cp})` round-trips unmappable code points.
fn encode_scalar(cp: u32) FatPtr {
    var buf: [4]u8 = undefined;
    const scalar: u21 = if (cp <= 0x10FFFF) @intCast(cp) else 0xFFFD;
    const n = std.unicode.utf8Encode(scalar, &buf) catch std.unicode.utf8Encode(0xFFFD, &buf) catch unreachable;
    return str.make_str_copy(buf[0..n]);
}

fn utf16_from_code_point(self: FatPtr, cp_fp: FatPtr) callconv(.c) FatPtr {
    _ = self;
    return encode_scalar(@intCast(nat_intrinsics.deref(cp_fp)));
}

fn utf16_from_surrogate_pair(self: FatPtr, high_fp: FatPtr, low_fp: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const high = nat_intrinsics.deref(high_fp);
    const low = nat_intrinsics.deref(low_fp);
    const cp: u32 = if (high >= 0xD800 and high <= 0xDBFF and low >= 0xDC00 and low <= 0xDFFF)
        @intCast(0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00))
    else
        0xFFFD;
    return encode_scalar(cp);
}

fn utf16_is_surrogate(self: FatPtr, cp_fp: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const cp = nat_intrinsics.deref(cp_fp);
    return bool_intrinsics.to_bool(cp >= 0xD800 and cp < 0xE000);
}

pub const VT_UTF16: objs.VTable = .{
    .type_name = "base.UTF16/0",
    .hashes = &.{
        h("imm .fromCodePoint/1"),
        h("imm .fromSurrogatePair/2"),
        h("imm .isSurrogate/1"),
    },
    .methods = &.{
        @ptrCast(&utf16_from_code_point),
        @ptrCast(&utf16_from_surrogate_pair),
        @ptrCast(&utf16_is_surrogate),
    },
    .method_names = &.{
        "imm .fromCodePoint/1",
        "imm .fromSurrogatePair/2",
        "imm .isSurrogate/1",
    },
    .storage_mode = .singleton,
};

/// `UTF8.fromBytes(list): Action[Str]` -- validate the bytes as UTF-8.
fn utf8_from_bytes(self: FatPtr, list_fp: FatPtr) callconv(.c) FatPtr {
    _ = self;
    const al = list_intrinsics.deref_list(list_fp);
    if (al.items.len == 0) return str.make_action_ok(str.make_str("".ptr, 0));
    const buf = str.alloc_bytes(al.items.len);
    for (al.items, 0..) |item, i| buf[i] = byte_intrinsics.deref(item);
    if (std.unicode.utf8ValidateSlice(buf)) {
        return str.make_action_ok(str.make_owned_str(buf.ptr, buf.len));
    }
    str.free_bytes(buf.ptr);
    return str.make_action_info(str.make_info_msg(str.make_str_from_literal("Invalid UTF-8 byte sequence")));
}

pub const VT_UTF8: objs.VTable = .{
    .type_name = "base.UTF8/0",
    .hashes = &.{h("imm .fromBytes/1")},
    .methods = &.{@ptrCast(&utf8_from_bytes)},
    .method_names = &.{"imm .fromBytes/1"},
    .storage_mode = .singleton,
};
