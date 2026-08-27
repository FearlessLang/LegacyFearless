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

pub const VT_Flow = instance.VT_Flow;
pub const VT_FlowFactory = factory.VT_FlowFactory;
pub const VT_FeartDriver = instance.VT_FeartDriver;

// Called by `list.zig` when a List/UList hands itself to `.flow`. It takes one
// reference, so a thunk with a borrowed receiver shares before it calls.
pub fn make_flow_from_list(list_fp: FatPtr) FatPtr {
    return object.make_flow_from_list(&VT_Flow, list_fp);
}

// Called by `str.zig` for `.codepoints` / `.graphemes`.
pub fn make_flow_from_str(owner: FatPtr, bytes: []const u8, mode: string_flows.StrSourceMode) FatPtr {
    return object.make_flow_from_str(&VT_Flow, owner, bytes, mode);
}

// Called by `map.zig` to build a flow over a snapshot of entries/keys/values.
pub fn make_flow_from_items(items: []const FatPtr) FatPtr {
    return object.make_flow_from_items(&VT_Flow, items);
}
