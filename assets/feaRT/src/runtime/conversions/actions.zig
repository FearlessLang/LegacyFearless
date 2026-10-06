
const objs = @import("../objs.zig");
const pb = @import("root").pkg_base;

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

pub const RunFn = *const fn (self: FatPtr, m: FatPtr) callconv(.c) FatPtr;

fn map(self_m: FatPtr, f_m: FatPtr) callconv(.c) FatPtr {
    return pb.Action_1__Zdotmap_1_mut_Zfun(f_m, self_m);
}
fn andThen(self_m: FatPtr, f_m: FatPtr) callconv(.c) FatPtr {
    return pb.Action_1__ZdotandThen_1_mut_Zfun(f_m, self_m);
}
fn mapInfo(self_m: FatPtr, f_m: FatPtr) callconv(.c) FatPtr {
    return pb.Action_1__ZdotmapInfo_1_mut_Zfun(f_m, self_m);
}
fn bang(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.Action_1__Zbang_0_mut_Zfun(self_m);
}
fn ok(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.Action_1__Zdotok_0_mut_Zfun_boxed(self_m);
}
fn info(self_m: FatPtr) callconv(.c) FatPtr {
    return pb.Action_1__Zdotinfo_0_mut_Zfun_boxed(self_m);
}

pub fn ActionVTable(
    comptime type_name: []const u8,
    comptime run: RunFn,
    comptime drop_fn: ?objs.DropFn,
) objs.VTable {
    return .{
        .type_name = type_name,
        .hashes = &.{
            h("mut .run/1"),     h("mut .map/1"), h("mut .andThen/1"),
            h("mut .mapInfo/1"), h("mut !/0"),    h("mut .ok/0"),
            h("mut .info/0"),
        },
        .methods = &.{
            @ptrCast(run),      @ptrCast(&map),  @ptrCast(&andThen),
            @ptrCast(&mapInfo), @ptrCast(&bang), @ptrCast(&ok),
            @ptrCast(&info),
        },
        .method_names = &.{
            "mut .run/1",     "mut .map/1", "mut .andThen/1",
            "mut .mapInfo/1", "mut !/0",    "mut .ok/0",
            "mut .info/0",
        },
        .drop_fn = drop_fn,
    };
}
