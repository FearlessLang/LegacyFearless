const std = @import("std");
const objs = @import("../objs.zig");
const bool_intrinsics = @import("bool.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;

pub const VT_Float: objs.VTable = .{
    .type_name = "base.Float/0",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .primitive,
};

pub inline fn make(v: f64) FatPtr {
    return FatPtr{
        .data = FearlessValue{ .float = v },
        .vt = &VT_Float,
    };
}

pub inline fn deref(f: FatPtr) f64 {
    if (std.debug.runtime_safety) {
        std.debug.assert(f.vt == &VT_Float);
    }
    return f.data.float;
}

// Arithmetic (IEEE-754)
pub inline fn add(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) + deref(b));
}

pub inline fn sub(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) - deref(b));
}

pub inline fn mul(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) * deref(b));
}

pub inline fn div(a: FatPtr, b: FatPtr) FatPtr {
    return make(deref(a) / deref(b));
}

/// Matches Java's `a % b` on doubles: result has the sign of the dividend.
pub inline fn mod(a: FatPtr, b: FatPtr) FatPtr {
    return make(@rem(deref(a), deref(b)));
}

/// Matches `Math.pow`.
pub inline fn pow(a: FatPtr, b: FatPtr) FatPtr {
    return make(std.math.pow(f64, deref(a), deref(b)));
}

pub inline fn abs(a: FatPtr) FatPtr {
    return make(@abs(deref(a)));
}

pub inline fn sqrt(a: FatPtr) FatPtr {
    return make(@sqrt(deref(a)));
}

// Comparisons (IEEE-754; NaN compares false everywhere except !=)
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

// Rounding (return Int). Matches Java's Math.round / Math.ceil / Math.floor,
// then narrowed to i64 as the declared return type requires.
pub inline fn round(a: FatPtr) FatPtr {
    const int_intrinsics = @import("int.zig");
    // Java's Math.round(double): floor(x + 0.5), with NaN -> 0.
    const v = deref(a);
    if (std.math.isNan(v)) return int_intrinsics.make(0);
    return int_intrinsics.make(f64_to_i64(std.math.floor(v + 0.5)));
}

pub inline fn ceil(a: FatPtr) FatPtr {
    const int_intrinsics = @import("int.zig");
    return int_intrinsics.make(f64_to_i64(std.math.ceil(deref(a))));
}

pub inline fn floor(a: FatPtr) FatPtr {
    const int_intrinsics = @import("int.zig");
    return int_intrinsics.make(f64_to_i64(std.math.floor(deref(a))));
}

/// Saturating, NaN-safe f64 -> i64 conversion matching Java's `(long)double`.
inline fn f64_to_i64(v: f64) i64 {
    if (std.math.isNan(v)) return 0;
    if (v >= 9223372036854775807.0) return std.math.maxInt(i64);
    if (v <= -9223372036854775808.0) return std.math.minInt(i64);
    return @intFromFloat(v);
}

// Predicates
pub inline fn is_nan(a: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(std.math.isNan(deref(a)));
}

pub inline fn is_infinite(a: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(std.math.isInf(deref(a)));
}

pub inline fn is_pos_infinity(a: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) == std.math.inf(f64));
}

pub inline fn is_neg_infinity(a: FatPtr) FatPtr {
    return bool_intrinsics.to_bool(deref(a) == -std.math.inf(f64));
}

// Conversions
pub fn to_int(a: FatPtr) FatPtr {
    const int_intrinsics = @import("int.zig");
    return int_intrinsics.make(f64_to_i64(deref(a)));
}

pub fn to_nat(a: FatPtr) FatPtr {
    const nat_intrinsics = @import("nat.zig");
    return nat_intrinsics.make(@bitCast(f64_to_i64(deref(a))));
}

pub fn to_byte(a: FatPtr) FatPtr {
    const byte_intrinsics = @import("byte.zig");
    return byte_intrinsics.make(@bitCast(@as(i8, @truncate(f64_to_i64(deref(a))))));
}

pub fn to_float(a: FatPtr) FatPtr {
    return a; // Identity
}

pub fn hash(a: FatPtr, hasher: FatPtr) FatPtr {
	defer hasher.rc_decrement();
    return objs.call(hasher, comptime objs.hash_signature("mut .float/1"), .{a}, @src());
}

// Compile-time-resolved intrinsic dispatch for Float.
const h = objs.hash_signature;
const num_assert = @import("num_assert.zig");
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
        h("imm .round/0") => round(self),
        h("imm .ceil/0") => ceil(self),
        h("imm .floor/0") => floor(self),
        h("imm .isNaN/0") => is_nan(self),
        h("imm .isInfinite/0") => is_infinite(self),
        h("imm .isPosInfinity/0") => is_pos_infinity(self),
        h("imm .isNegInfinity/0") => is_neg_infinity(self),
        h("read .str/0") => @import("strings/index.zig").float_to_str(self),
        h("read .int/0") => to_int(self),
        h("read .nat/0") => to_nat(self),
        h("read .byte/0") => to_byte(self),
        h("read .float/0") => self,
        h("read .hash/1") => hash(self, args[0]),
        h("imm .assertEq/1") => num_assert.assert_eq("_FloatAssertionHelper_0", self, args[0]),
        h("imm .assertEq/2") => num_assert.assert_eq_msg("_FloatAssertionHelper_0", self, args[0], args[1]),
        else => objs.primitive_dispatch_failed(VT_Float.type_name, target_method),
    };
}
