const std = @import("std");
const objs = @import("../../objs.zig");
const native = @import("../../native.zig");

const FatPtr = objs.FatPtr;

/// Iterate a string by codepoint (pure Zig boundary jumps) or by extended
/// grapheme cluster (via the native boundary lookup).
pub const StrSourceMode = enum { codepoint, grapheme };

/// A flow over a string's units. `owner` is the Str whose UTF-8 buffer
/// `bytes_ptr`/`bytes_len` borrow; the source holds one reference to it (taken
/// at construction / split, released in `release_source`) so the buffer stays
/// alive for the flow's lifetime, and `source_next` shares it into each emitted
/// sub-string. `bytes_len` is the exclusive end byte offset and `index` the
/// next unit's offset, so a split half is just a narrower `[index, bytes_len)`
/// window over the same buffer. Grapheme mode finds each unit boundary with a
/// stateless native lookup, so the source carries no cursor.
pub const StrSource = struct {
    bytes_ptr: [*]const u8,
    bytes_len: usize,
    index: usize,
    mode: StrSourceMode,
    owner: FatPtr,
};

/// Count the remaining units in `ss` (`[index, bytes_len)`): codepoints by a
/// non-continuation-byte scan, graphemes by repeated native boundary lookups.
/// `index` and `bytes_len` are always unit boundaries (iteration and split only
/// ever land on them), so the suffix segments the same as it would in context.
pub fn str_source_size(ss: StrSource) u64 {
    const data = ss.bytes_ptr[ss.index..ss.bytes_len];
    switch (ss.mode) {
        // The source buffer is validated UTF-8, so the count never errors.
        .codepoint => return @intCast(std.unicode.utf8CountCodepoints(data) catch unreachable),
        .grapheme => {
            var count: u64 = 0;
            var pos: usize = 0;
            while (pos < data.len) {
                pos = native.frt_grapheme_boundary_after(data.ptr, data.len, pos);
                count += 1;
            }
            return count;
        },
    }
}
