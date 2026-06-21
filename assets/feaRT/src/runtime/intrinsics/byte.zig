const std = @import("std");
const objs = @import("../objs.zig");
const bool_intrinsics = @import("bool.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;

pub const VT_Byte: objs.VTable = .{
    .type_name = "base.Byte/0",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitive,
};

pub inline fn make(v: u8) FatPtr {
    return FatPtr{
        .data = FearlessValue{ .byte = v },
        .vt = &VT_Byte,
    };
}

pub inline fn deref(b: FatPtr) u8 {
    if (std.debug.runtime_safety) {
        std.debug.assert(b.vt == &VT_Byte);
    }
    return b.data.byte;
}

// Arithmetic. Fearless Byte is u8; the Java backend truncates each result with a
// `(byte)` cast, so we wrap modulo 256 to match.
pub inline fn add(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) +% deref(b));
}

pub inline fn sub(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) -% deref(b));
}

pub inline fn mul(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) *% deref(b));
}

pub inline fn div(a: FatPtr, b: FatPtr) FatPtr {
    const divisor = deref(b);
    if (divisor == 0) @panic("/ by zero");
    return make(deref(a) / divisor);
}

pub inline fn mod(a: FatPtr, b: FatPtr) FatPtr {
    const divisor = deref(b);
    if (divisor == 0) @panic("/ by zero");
    return make(deref(a) % divisor);
}

/// Matches Java's `Numbers.pow8`: the base is sign-extended (Java `byte`), the
/// pow runs in wrapping 64-bit, then the result is truncated to a byte.
pub inline fn pow(a: FatPtr, exp: FatPtr) FatPtr {
    const nat_intrinsics = @import("nat.zig");
    var base: i64 = @as(i8, @bitCast(deref(a)));
    var exp_bits: u64 = nat_intrinsics.deref(exp);
    var res: i64 = 1;
    while (exp_bits != 0) : (exp_bits >>= 1) {
        if ((exp_bits & 1) != 0) res *%= base;
        base *%= base;
    }
    return make(@truncate(@as(u64, @bitCast(res))));
}

pub inline fn abs(a: FatPtr) FatPtr {
    return a; // Byte is always non-negative
}

pub inline fn sqrt(a: FatPtr) FatPtr {
    const x: f64 = @floatFromInt(deref(a));
    return make(@intFromFloat(@floor(@sqrt(x))));
}

// Comparisons (unsigned)
pub inline fn gt(a: FatPtr, b: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) > deref(b));
}

pub inline fn lt(a: FatPtr, b: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) < deref(b));
}

pub inline fn gte(a: FatPtr, b: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) >= deref(b));
}

pub inline fn lte(a: FatPtr, b: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) <= deref(b));
}

pub inline fn eq(a: FatPtr, b: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) == deref(b));
}

pub inline fn neq(a: FatPtr, b: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) != deref(b));
}

// Bitwise. Java widens both operands via `Byte.toUnsignedInt`; for shifts the
// result keeps int width before being narrowed back to a byte on store, so we
// compute in u32 and truncate.
pub inline fn shift_left(a: FatPtr, b: FatPtr) FatPtr {
    const av: u32 = deref(a);
    const shift: u5 = @intCast(deref(b) & 0x1f);
    return make(@truncate(av << shift));
}

pub inline fn shift_right(a: FatPtr, b: FatPtr) FatPtr {
    const av: u32 = deref(a);
    const shift: u5 = @intCast(deref(b) & 0x1f);
    return make(@truncate(av >> shift));
}

pub inline fn bitwise_xor(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) ^ deref(b));
}

pub inline fn bitwise_and(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) & deref(b));
}

pub inline fn bitwise_or(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) | deref(b));
}

/// Matches Java: `(byte)(Byte.toUnsignedInt(a) + delta)`.
pub inline fn offset(a: FatPtr, delta: FatPtr) FatPtr {
    const int_intrinsics = @import("int.zig");
    const base: i64 = deref(a);
    const result: i64 = base +% int_intrinsics.deref(delta);
    return make(@truncate(@as(u64, @bitCast(result))));
}

// Conversions
pub fn to_int(a: FatPtr) FatPtr {
    const int_intrinsics = @import("int.zig");
    return int_intrinsics.make(deref(a));
}

pub fn to_nat(a: FatPtr) FatPtr {
    const nat_intrinsics = @import("nat.zig");
    return nat_intrinsics.make(deref(a));
}

pub fn to_float(a: FatPtr) FatPtr {
    const float_intrinsics = @import("float.zig");
    return float_intrinsics.make(@floatFromInt(deref(a)));
}

pub fn to_byte(a: FatPtr) FatPtr {
    return a; // Identity
}

pub fn to_str(a: FatPtr) FatPtr {
    const str_intrinsics = @import("strings/index.zig");
    const nat_intrinsics = @import("nat.zig");
    return str_intrinsics.int_to_str(nat_intrinsics.make(deref(a)));
}

// Compile-time-resolved intrinsic dispatch for Byte.
const h = objs.hash_signature;
pub fn dispatch(comptime target_method: u64, self: FatPtr, args: anytype) FatPtr {
    return switch (target_method) {
        h("imm +/1") => add(self, args[0]),
        h("imm -/1") => sub(self, args[0]),
        h("imm */1") => mul(self, args[0]),
        h("imm //1") => div(self, args[0]),
        h("imm %/1") => mod(self, args[0]),
        h("imm **/1") => pow(self, args[0]),
        h("imm .abs/0") => abs(self),
        h("imm .sqrt/0") => sqrt(self),
        h("imm >/1") => gt(self, args[0]),
        h("imm </1") => lt(self, args[0]),
        h("imm >=/1") => gte(self, args[0]),
        h("imm <=/1") => lte(self, args[0]),
        h("imm ==/1") => eq(self, args[0]),
        h("imm !=/1") => neq(self, args[0]),
        h("imm .shiftLeft/1") => shift_left(self, args[0]),
        h("imm .shiftRight/1") => shift_right(self, args[0]),
        h("imm .xor/1") => bitwise_xor(self, args[0]),
        h("imm .bitwiseAnd/1") => bitwise_and(self, args[0]),
        h("imm .bitwiseOr/1") => bitwise_or(self, args[0]),
        h("imm .offset/1") => offset(self, args[0]),
        h("read .str/0") => to_str(self),
        h("read .int/0") => to_int(self),
        h("read .nat/0") => to_nat(self),
        h("read .float/0") => to_float(self),
        h("read .byte/0") => self,
        else => unreachable,
    };
}
