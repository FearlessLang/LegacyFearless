const std = @import("std");
const objs = @import("../objs.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;

pub const VT_Int: objs.VTable = .{
	.type_name = "base.Int/0",
	.hashes = &.{},
	.methods = &.{},
	.method_names = &.{},
	.storage_mode = .primitive,
};

pub inline fn make(v: i64) FatPtr {
	return FatPtr{
		.data = FearlessValue{ .int = v },
		.vt = &VT_Int,
	};
}

pub inline fn deref(i: FatPtr) i64 {
	if (std.debug.runtime_safety) {
		std.debug.assert(i.vt == &VT_Int);
	}
	return i.data.int;
}

// Arithmetic (signed, wrapping)
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
	return make(@divTrunc(deref(a), divisor));
}

pub inline fn mod(a: FatPtr, b: FatPtr) FatPtr {
	const divisor = deref(b);
	if (divisor == 0) @panic("/ by zero");
	return make(@mod(deref(a), divisor));
}

/// `**(n: Nat): Int` -- exponentiation by squaring in wrapping 64-bit, matching
/// the Java backend's `long` pow. The exponent is a Nat.
pub inline fn pow(a: FatPtr, exp: FatPtr) FatPtr {
	const nat_intrinsics = @import("nat.zig");
	var base: i64 = deref(a);
	var exp_bits: u64 = nat_intrinsics.deref(exp);
	var res: i64 = 1;
	while (exp_bits != 0) : (exp_bits >>= 1) {
		if ((exp_bits & 1) != 0) res *%= base;
		base *%= base;
	}
	return make(res);
}

pub inline fn abs(a: FatPtr) FatPtr {
	const v = deref(a);
	return make(if (v < 0) -v else v);
}

/// Integer square root, matching `rt.Numbers.intSqrt`: a floating-point seed
/// refined by Newton's method, then corrected upwards once so the result is the
/// exact floor of the real square root across the whole i64 range.
pub inline fn sqrt(a: FatPtr) FatPtr {
	const x = deref(a);
	if (x < 0) @panic("sqrt of negative Int");
	if (x <= 1) return make(x);
	var r: i64 = @intFromFloat(@sqrt(@as(f64, @floatFromInt(x))));
	while (true) {
		const q = @divTrunc(x, r);
		const nr: i64 = @bitCast(@as(u64, @bitCast(r +% q)) >> 1);
		if (nr >= r) break;
		r = nr;
	}
	const rp1 = r + 1;
	if (@divTrunc(x, rp1) >= rp1) r = rp1;
	return make(r);
}

// Comparisons (signed)
const bool_intrinsics = @import("bool.zig");

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

// Conversions
pub fn to_nat(a: FatPtr) FatPtr {
	const nat_intrinsics = @import("nat.zig");
	return nat_intrinsics.make(@bitCast(deref(a)));
}

pub fn to_float(a: FatPtr) FatPtr {
	const float_intrinsics = @import("float.zig");
	return float_intrinsics.make(@floatFromInt(deref(a)));
}

pub fn to_byte(a: FatPtr) FatPtr {
	const byte_intrinsics = @import("byte.zig");
	return byte_intrinsics.make(@truncate(@as(u64, @bitCast(deref(a)))));
}

// Bitwise. Signed throughout, matching Java's `long` operators: `>>` keeps the
// sign bit and both shifts take their count modulo 64.
pub inline fn shift_left(a: FatPtr, b: FatPtr) FatPtr {
	const shift: u6 = @truncate(@as(u64, @bitCast(deref(b))));
	return make(deref(a) << shift);
}

pub inline fn shift_right(a: FatPtr, b: FatPtr) FatPtr {
	const shift: u6 = @truncate(@as(u64, @bitCast(deref(b))));
	return make(deref(a) >> shift);
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

/// `read .hash(hasher)` -- feed this Int into the hasher and hand the hasher
/// back, so `Hasher.hash(x)`'s `x.hash(this)` body still yields a `mut Hasher`.
pub fn hash(a: FatPtr, hasher: FatPtr) FatPtr {
	return objs.call(hasher, comptime objs.hash_signature("mut .int/1"), .{a}, @src());
}

// Compile-time-resolved intrinsic dispatch for Int.
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
		h("read .str/0") => @import("strings/index.zig").int_to_str(self),
		h("read .int/0") => self,
		h("read .nat/0") => to_nat(self),
		h("read .float/0") => to_float(self),
		h("read .byte/0") => to_byte(self),
		h("imm .shiftLeft/1") => shift_left(self, args[0]),
		h("imm .shiftRight/1") => shift_right(self, args[0]),
		h("imm .xor/1") => bitwise_xor(self, args[0]),
		h("imm .bitwiseAnd/1") => bitwise_and(self, args[0]),
		h("imm .bitwiseOr/1") => bitwise_or(self, args[0]),
		h("read .hash/1") => hash(self, args[0]),
		h("imm .assertEq/1") => num_assert.assert_eq("_IntAssertionHelper_0", self, args[0]),
		h("imm .assertEq/2") => num_assert.assert_eq_msg("_IntAssertionHelper_0", self, args[0], args[1]),
		else => objs.primitive_dispatch_failed(VT_Int.type_name, target_method),
	};
}
