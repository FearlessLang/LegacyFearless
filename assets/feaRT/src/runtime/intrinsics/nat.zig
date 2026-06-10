const std = @import("std");
const objs = @import("../objs.zig");
const bool_intrinsics = @import("bool.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;

pub const VT_Nat: objs.VTable = .{
	.type_name = "Nat",
	.hashes = &.{},
	.methods = &.{},
	.method_names = &.{},
	.storage_mode = .primitive,
};

pub inline fn make(v: u64) FatPtr {
	return FatPtr{
		.data = FearlessValue{ .nat = v },
		.vt = &VT_Nat,
	};
}

pub inline fn deref(n: FatPtr) u64 {
	if (std.debug.runtime_safety) {
		std.debug.assert(n.vt == &VT_Nat);
	}
	return n.data.nat;
}

// Arithmetic (wrapping)
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

pub inline fn abs(a: FatPtr) FatPtr {
	return a; // Nat is always non-negative
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

// Conversions
pub fn to_int(a: FatPtr) FatPtr {
	const int_intrinsics = @import("int.zig");
	return int_intrinsics.make(@bitCast(deref(a)));
}

pub fn to_nat(a: FatPtr) FatPtr {
	return a; // Identity
}

pub fn to_float(a: FatPtr) FatPtr {
	_ = a;
	@panic("TODO: Floats");
}

pub fn to_byte(a: FatPtr) FatPtr {
	_ = a;
	@panic("TODO: Bytes");
}

// Bitwise
pub inline fn shift_left(a: FatPtr, b: FatPtr) FatPtr {
	const av = deref(a);
	const shift: u6 = @intCast(deref(b));
	return make(av << shift);
}

pub inline fn shift_right(a: FatPtr, b: FatPtr) FatPtr {
	const av = deref(a);
	const shift: u6 = @intCast(deref(b));
	return make(av >> shift);
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

// Compile-time-resolved intrinsic dispatch for Nat.
// Since target_method is comptime, the switch resolves at compile time —
// only the matching branch survives in the emitted code.
// The runtime cost is just the vt pointer comparison (~1 cycle).
const h = objs.hash_signature;
pub fn dispatch(comptime target_method: u64, self: FatPtr, args: anytype) FatPtr {
	return switch (target_method) {
		h("imm +/1") => add(self, args[0]),
		h("imm -/1") => sub(self, args[0]),
		h("imm */1") => mul(self, args[0]),
		h("imm //1") => div(self, args[0]),
		h("imm %/1") => mod(self, args[0]),
		h("imm .abs/0") => abs(self),
		h("imm >/1") => gt(self, args[0]),
		h("imm </1") => lt(self, args[0]),
		h("imm >=/1") => gte(self, args[0]),
		h("imm <=/1") => lte(self, args[0]),
		h("imm ==/1") => eq(self, args[0]),
		h("imm !=/1") => neq(self, args[0]),
		h("read .str/0") => @import("str.zig").int_to_str(self),
		h("read .int/0") => to_int(self),
		h("read .nat/0") => self,
		h("imm .shiftLeft/1") => shift_left(self, args[0]),
		h("imm .shiftRight/1") => shift_right(self, args[0]),
		h("imm .xor/1") => bitwise_xor(self, args[0]),
		h("imm .bitwiseAnd/1") => bitwise_and(self, args[0]),
		h("imm .bitwiseOr/1") => bitwise_or(self, args[0]),
		else => unreachable,
	};
}
