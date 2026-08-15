const objs = @import("../objs.zig");
const io = @import("caps/io.zig");
const rng = @import("caps/rng.zig");
const try_rt = @import("try.zig");

const FatPtr = objs.FatPtr;

fn system_io(self: FatPtr) callconv(.c) FatPtr {
	_ = self;
	return io.make_io();
}

fn system_rng(self: FatPtr) callconv(.c) FatPtr {
	_ = self;
	return rng.make_rng();
}

fn system_try(self: FatPtr) callconv(.c) FatPtr {
	_ = self;
	return objs.obj_k_singleton(&try_rt.VT_CapTry);
}

fn system_iso(self: FatPtr) callconv(.c) FatPtr {
	return self;
}

fn system_self(self: FatPtr) callconv(.c) FatPtr {
	return self;
}

const h = objs.hash_signature;

pub const VT_IO = io.VT_IO;
pub const VT_RandomSeed = rng.VT_RandomSeed;

pub const VT_System: objs.VTable = .{
	.type_name = "base.caps._System/0",
	.hashes = &.{
		h("mut .io/0"),
		h("mut .rng/0"),
		h("mut .try/0"),
		h("mut .iso/0"),
		h("mut .self/0"),
	},
	.methods = &.{
		@ptrCast(&system_io),
		@ptrCast(&system_rng),
		@ptrCast(&system_try),
		@ptrCast(&system_iso),
		@ptrCast(&system_self),
	},
	.method_names = &.{
		"mut .io/0",
		"mut .rng/0",
		"mut .try/0",
		"mut .iso/0",
		"mut .self/0",
	},
	.storage_mode = .singleton,
};

pub fn make_system() FatPtr {
	return objs.obj_k_singleton(&VT_System);
}
