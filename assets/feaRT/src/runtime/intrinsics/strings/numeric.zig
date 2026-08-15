const std = @import("std");
const objs = @import("../../objs.zig");
const int_intrinsics = @import("../int.zig");
const nat_intrinsics = @import("../nat.zig");
const float_intrinsics = @import("../float.zig");
const str = @import("str.zig");

const FatPtr = objs.FatPtr;

/// Convert an Int/Nat (i64/u64) to a decimal string. Allocates via GC.
///
/// Nat is unsigned, Int is signed: a negative Int gets a `-` and the magnitude of
/// its value. The magnitude is taken in u64 so that `minInt(i64)`, whose absolute
/// value does not fit back into an i64, still comes out right.
pub fn int_to_str(n: FatPtr) FatPtr {
    var negative = false;
    var uval: u64 = undefined;
    if (n.vt == &nat_intrinsics.VT_Nat) {
        uval = nat_intrinsics.deref(n);
    } else {
        const ival = int_intrinsics.deref(n);
        negative = ival < 0;
        const bits: u64 = @bitCast(ival);
        uval = if (negative) ~bits +% 1 else bits;
    }
    var buf: [20]u8 = undefined; // "-" + the 19 digits of minInt(i64), or 20 u64 digits
    const start: usize = if (negative) 1 else 0;
    if (negative) buf[0] = '-';
    var len = start;
    if (uval == 0) {
        buf[len] = '0';
        len += 1;
    } else {
        var tmp = uval;
        while (tmp > 0) : (len += 1) {
            buf[len] = @intCast('0' + (tmp % 10));
            tmp /= 10;
        }
        var i = start;
        var j = len - 1;
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
