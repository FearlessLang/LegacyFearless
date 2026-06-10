//! Compatibility re-export for the `_FeartDriver` runtime singleton.
//!
//! The `_FeartDriver` vtable lives beside `VT_Flow` in `instance.zig` so the
//! driver-only split path can wrap split halves with a comptime `&VT_Flow`
//! reference without an import cycle.

const instance = @import("instance.zig");
const objs = @import("../../objs.zig");

const FatPtr = objs.FatPtr;

pub const VT_FeartDriver = instance.VT_FeartDriver;

pub fn singleton() FatPtr {
    return instance.driver_singleton();
}
