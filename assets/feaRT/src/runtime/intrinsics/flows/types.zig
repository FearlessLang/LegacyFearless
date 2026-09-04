//! Core data types for the FeaRT Flow runtime.
//!
//! `OpDesc` is the unit of the flat op array -- every fluent call on a flow
//! appends one `OpDesc` instead of wrapping the flow in a new sink object.
//! `Source` is the lazy iteration state; `FeartFlow` pairs the two.

const std = @import("std");
const objs = @import("../../objs.zig");
const int_rt = @import("../int.zig");
const native = @import("../../native.zig");
const string_flows = @import("string_flows.zig");
const ops_node = @import("ops_node.zig");

const FatPtr = objs.FatPtr;

pub const OpKind = enum(u8) {
    map,
    filter,
    peek,
    map_filter,
    flat_map,
    scan,
    limit,
    actor,
    map_ctx,
    peek_ctx,
};

// Scheduler hints. Stateless ops can be duplicated across parallel splits;
// short-circuiting ops can end the flow early; pipeline-breakers force a
// barrier before downstream ops (sort / distinct / groupBy -- none in the
// current trait, but the flag is wired for when they land).
pub const OpFlags = packed struct(u8) {
    stateless: bool = true,
    short_circuiting: bool = false,
    pipeline_breaker: bool = false,
    _pad: u5 = 0,
};

pub const OpDesc = struct {
    kind: OpKind,
    closure: FatPtr,
    state: u64, // opaque per-op state (ptr to ScanCell / ActorState, or a counter)
    flags: OpFlags,
};

pub const ListSource = struct { items: []const FatPtr, index: usize };
pub const RangeSource = struct { current: i64, end: i64, step: i64 };
pub const SingleSource = struct { value: FatPtr, consumed: bool };

pub const Source = union(enum) {
    list: ListSource,
    range_finite: RangeSource,
    range_infinite: RangeSource,
    single: SingleSource,
    str: string_flows.StrSource,
    empty: void,
};

pub const FeartFlow = struct {
    source: Source,
    // When non-null, the source's items live inside this FatPtr's storage (a
    // list intrinsic) -- drop releases the FatPtr instead of walking the items
    // slice. Lets `make_flow_from_list` keep a List intrinsic's items alive
    // without deep-sharing every element. Range / single / empty leave this
    // null and own their source data directly.
    source_owner: ?FatPtr = null,
    ops: []OpDesc,
    /// The node that owns `ops`, and null when there are none. The slice is
    /// repeated here so that iteration reads it without a second indirection.
    ops_owner: ?*ops_node.FlowOps = null,
    is_finite: bool,
    // Body refcount. Currently always 1 (each FatPtr owns its own body), but
    // `flow_drop` releases through this so future schemes that share bodies
    // (e.g. an alternate split design) can bump it without touching call sites.
    ref_count: std.atomic.Value(u32),
};

// Stateful-op state cells. Allocated on intermediate-op append and kept alive
// by the GC through the OpDesc.state integer pointer.
pub const ScanCell = struct { acc: FatPtr };
pub const ActorState = struct { state_fp: FatPtr, callback: FatPtr };
// Cell for contextful flow operations (like .map/2). The `ToIso[C]` ctx is a
// fixed capture: each element calls `.iso` then `.self`.
// This cell is immutable.
pub const CtxCell = struct { ctx: FatPtr };

// ==========================================
// Source primitives
// ==========================================

pub fn source_has_next(s: *Source) bool {
    return switch (s.*) {
        .list => |ls| ls.index < ls.items.len,
        .range_finite => |rs| if (rs.step > 0) rs.current < rs.end else rs.current > rs.end,
        .range_infinite => true,
        .single => |ss| !ss.consumed,
        .str => |ss| ss.index < ss.bytes_len,
        .empty => false,
    };
}

pub fn source_next(s: *Source) FatPtr {
    switch (s.*) {
        .list => |*ls| {
            const v = ls.items[ls.index].share();
            ls.index += 1;
            return v;
        },
        .range_finite, .range_infinite => |*rs| {
            const v = rs.current;
            rs.current += rs.step;
            return int_rt.make(v);
        },
        .single => |*ss| {
            ss.consumed = true;
            return ss.value;
        },
        .str => |*ss| {
            const str_rt = @import("root").str_rt;
            const start = ss.index;
            const unit_end = switch (ss.mode) {
                // Validated UTF-8: the leading byte gives the sequence length.
                .codepoint => @min(start + (std.unicode.utf8ByteSequenceLength(ss.bytes_ptr[start]) catch 1), ss.bytes_len),
                // `start` is already a grapheme boundary, so the next boundary
                // after it is the end of the current cluster.
                .grapheme => native.frt_grapheme_boundary_after(ss.bytes_ptr, ss.bytes_len, start),
            };
            ss.index = unit_end;
            // A shared sub-Str over the owner's buffer: it takes its own
            // reference to `owner`, so it stays valid independently of the flow.
            return str_rt.make_shared_substr(ss.owner, ss.bytes_ptr + start, unit_end - start);
        },
        .empty => unreachable,
    }
}
