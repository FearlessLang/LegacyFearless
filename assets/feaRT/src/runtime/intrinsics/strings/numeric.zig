const std = @import("std");
const objs = @import("../../objs.zig");
const int_intrinsics = @import("../int.zig");
const nat_intrinsics = @import("../nat.zig");
const float_intrinsics = @import("../float.zig");
const str = @import("str.zig");

const FatPtr = objs.FatPtr;

/// Convert an Int/Nat (i64/u64) to a decimal string. Allocates via GC.
pub fn int_to_str(n: FatPtr) FatPtr {
    const uval: u64 = if (n.vt == &nat_intrinsics.VT_Nat) nat_intrinsics.deref(n) else @bitCast(int_intrinsics.deref(n));
    var buf: [20]u8 = undefined; // max u64 decimal digits
    var len: usize = 0;
    if (uval == 0) {
        buf[0] = '0';
        len = 1;
    } else {
        var tmp = uval;
        while (tmp > 0) : (len += 1) {
            buf[len] = @intCast('0' + (tmp % 10));
            tmp /= 10;
        }
        var i: usize = 0;
        var j: usize = len - 1;
        while (i < j) {
            const t = buf[i];
            buf[i] = buf[j];
            buf[j] = t;
            i += 1;
            j -= 1;
        }
    }
    return str.make_str_copy(buf[0..len]);
}

/// Format a Float (f64) exactly as Rust's `f64::to_string`, routed through the
/// native runtime so the output is byte-identical to the Java backend.
pub fn float_to_str(f: FatPtr) FatPtr {
    const native = @import("root").native;
    const v = float_intrinsics.deref(f);
    var buf: [native.FRT_F64_STR_MAX]u8 = undefined;
    const needed = native.frt_f64_to_str(v, &buf, buf.len);
    std.debug.assert(needed <= buf.len);
    return str.make_str_copy(buf[0..needed]);
}
