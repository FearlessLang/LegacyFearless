const objs = @import("../objs.zig");
const io = @import("caps/io.zig");
const try_rt = @import("try.zig");

const FatPtr = objs.FatPtr;

fn system_io(self: FatPtr) callconv(.c) FatPtr {
	_ = self;
	return io.make_io();
}

fn system_rng(self: FatPtr) callconv(.c) FatPtr {
	_ = self;
	@panic("System.rng not implemented");
}

fn system_try(self: FatPtr) callconv(.c) FatPtr {
	_ = self;
	// Force the plain-`Try` path to be type-checked by `zig build` even though
	// the dev exe only reaches CapTry (plain `Try` is only referenced by magic
	// in generated programs). TODO: delete this before merging this branch
	comptime {
		_ = &try_rt.VT_Try;
	}
	return objs.obj_k_singleton(&try_rt.VT_CapTry);
}

const h = objs.hash_signature;

pub const VT_IO = io.VT_IO;

pub const VT_System: objs.VTable = .{
	.type_name = "base.caps._System/0",
	.hashes = &.{
		h("mut .io/0"),
		h("mut .rng/0"),
		h("mut .try/0"),
	},
	.methods = &.{
		@ptrCast(&system_io),
		@ptrCast(&system_rng),
		@ptrCast(&system_try),
	},
	.method_names = &.{
		"mut .io/0",
		"mut .rng/0",
		"mut .try/0",
	},
	.storage_mode = .singleton,
};

pub fn make_system() FatPtr {
	return objs.obj_k_singleton(&VT_System);
}
