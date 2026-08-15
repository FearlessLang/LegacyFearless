//! `.assertEq` for the primitive number types.
//!
//! `assets/base/nums.fear` declares `.assertEq` as `Magic!` on every numeric
//! type and puts the real comparison and `Expected:`/`Actual:` formatting in the
//! pure-Fearless `_IntAssertionHelper` and its siblings. Each numeric intrinsic
//! lowers its `.assertEq` slots to the helper for its type, as the Java backend
//! does.
//!
//! Not every standard library defines every helper: `assets/immBase` has no
//! `_ByteAssertionHelper`, and a minimal base may have none at all. Each
//! reference is therefore `@hasDecl`-guarded, and a build whose base lacks the
//! helper cannot call the slot that needs it.

const objs = @import("../objs.zig");
const root = @import("root");

const FatPtr = objs.FatPtr;

/// `expected.assertEq(actual)` routed to `<helper>.assertEq(expected, actual)`.
/// `helper` is the mangled type name, e.g. `"_IntAssertionHelper_0"`.
pub fn assert_eq(comptime helper: []const u8, expected: FatPtr, actual: FatPtr) FatPtr {
    if (comptime @hasDecl(root, "pkg_base")) {
        const pb = root.pkg_base;
        const fun = helper ++ "__ZdotassertEq_2_imm_Zfun";
        if (comptime @hasDecl(pb, fun)) {
            return @field(pb, fun)(expected, actual, singleton(helper));
        }
    }
    @panic("This base has no numeric assertion helper for .assertEq");
}

/// `expected.assertEq(actual, message)`.
pub fn assert_eq_msg(comptime helper: []const u8, expected: FatPtr, actual: FatPtr, message: FatPtr) FatPtr {
    if (comptime @hasDecl(root, "pkg_base")) {
        const pb = root.pkg_base;
        const fun = helper ++ "__ZdotassertEq_3_imm_Zfun";
        if (comptime @hasDecl(pb, fun)) {
            return @field(pb, fun)(expected, actual, message, singleton(helper));
        }
    }
    @panic("This base has no numeric assertion helper for .assertEq");
}

/// The helper object itself. It captures nothing, so the compiler emits it as a
/// singleton vtable and the generated body takes it as its trailing `this`.
fn singleton(comptime helper: []const u8) FatPtr {
    return objs.obj_k_singleton(&@field(root.pkg_base, "VT_" ++ helper));
}
