// Public API for the FeaRT Flow runtime.
//
// The real implementation lives in `flows/`; this module exposes the handful
// of symbols the compiler-generated code and other intrinsics reach for. The
// compiler hardcodes `@import("runtime/intrinsics/flow.zig")`, so keep this
// file in place and narrow.

const objs = @import("../objs.zig");
const FatPtr = objs.FatPtr;

const object = @import("flows/object.zig");
const instance = @import("flows/instance.zig");
const factory = @import("flows/factory.zig");

pub const VT_Flow = instance.VT_Flow;
pub const VT_FlowFactory = factory.VT_FlowFactory;
pub const VT_FeartDriver = instance.VT_FeartDriver;

// Called by `list.zig` when a List/UList hands itself to `.flow`.
pub fn make_flow_from_list(list_fp: FatPtr) FatPtr {
    return object.make_flow_from_list(&VT_Flow, list_fp);
}
