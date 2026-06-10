const objs = @import("../objs.zig");
const root = @import("root");

pub fn to_bool(cond: bool) objs.FatPtr {
	return if (cond)
		objs.obj_k_singleton(&root.pkg_base.VT_True_0)
	else
		objs.obj_k_singleton(&root.pkg_base.VT_False_0);
}
