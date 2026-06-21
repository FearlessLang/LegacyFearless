const std = @import("std");
const objs = @import("../objs.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;

pub const VT_Int: objs.VTable = .{
	.type_name = "Int",
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

/// `**(n: Nat): Int` — exponentiation by squaring in wrapping 64-bit, matching
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

// Compile-time-resolved intrinsic dispatch for Int.
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
		else => unreachable,
	};
}
