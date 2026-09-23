//! Public API for the FeaRT Flow runtime.
//!
//! The real implementation lives in `flows/`; this module exposes the handful
//! of symbols the compiler-generated code and other intrinsics reach for. The
//! compiler hardcodes `@import("runtime/intrinsics/flow.zig")`, so keep this
//! file in place and narrow.

const objs = @import("../objs.zig");
const FatPtr = objs.FatPtr;

const object = @import("flows/object.zig");
const instance = @import("flows/instance.zig");
const factory = @import("flows/factory.zig");
const types = @import("flows/types.zig");
const string_flows = @import("flows/string_flows.zig");

/// A copy at a different address. Build and compare flows with `&instance.VT_Flow`.
pub const VT_Flow = instance.VT_Flow;
pub const VT_FlowFactory = factory.VT_FlowFactory;
pub const VT_FeartDriver = instance.VT_FeartDriver;

/// Takes one reference to `list_fp`. A thunk with a borrowed receiver must share first.
pub fn make_flow_from_list(list_fp: FatPtr) FatPtr {
    return object.make_flow_from_list(&instance.VT_Flow, list_fp);
}

pub fn make_flow_from_str(owner: FatPtr, bytes: []const u8, mode: string_flows.StrSourceMode) FatPtr {
    return object.make_flow_from_str(&instance.VT_Flow, owner, bytes, mode);
}

pub fn make_flow_from_items(items: []const FatPtr) FatPtr {
    return object.make_flow_from_items(&instance.VT_Flow, items);
}

/// Sets the most parallel mode for the flow. Takes and returns the owned `fp`.
/// A non-FeaRT flow comes back unchanged. Each op makes a new flow body, so the change
/// in place does not reach another flow.
pub fn allow_parallelism(fp: FatPtr, comptime p: types.Parallelism) FatPtr {
    if (fp.vt != &instance.VT_Flow) return fp;
    object.deref_flow(fp).parallelism = p;
    return fp;
}

/// Makes the last op and every later op run in element order on the terminal's fiber.
/// Takes and returns the owned `fp`. A non-FeaRT flow comes back unchanged.
pub fn serial_from_last_op(fp: FatPtr) FatPtr {
    if (fp.vt != &instance.VT_Flow) return fp;
    const f = object.deref_flow(fp);
    if (f.serial_from == null and f.ops.len > 0) f.serial_from = f.ops.len - 1;
    return fp;
}
