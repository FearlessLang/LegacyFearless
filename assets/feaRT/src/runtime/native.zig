//! Zig-side extern declarations for the Fearless native runtime's C ABI
//! (`frt_*`), implemented by the Rust crate `native-rt` and linked in as the
//! staticlib `lib/native/libnative_rt.a`. The authoritative documentation lives
//! in `native-runtime/native_rt_capi.h`; the ownership rules duplicated here
//! must stay in sync with it.
//!
//! General rules (see the header for the full text):
//!  - All functions are extern "C", never panic (Rust release profile is
//!    `panic = "abort"`).
//!  - String inputs are (ptr, len) byte pairs and must be valid UTF-8. This is
//!    an unchecked precondition; the Rust side fails closed but the result is
//!    otherwise unspecified.
//!  - Any `frt_buf` written by this library MUST be released with `frt_buf_free`.

const std = @import("std");

/// A Rust-owned `Vec<u8>` decomposed into raw parts so it can cross the C ABI.
/// `ptr` may be null with `len == cap == 0` to denote "no allocation". The
/// caller must NOT free `ptr` directly; ownership is returned to Rust via
/// `frt_buf_free`.
pub const frt_buf = extern struct {
    ptr: ?[*]u8,
    len: usize,
    cap: usize,
};

/// Release a `frt_buf` produced by this library. A zero-cap / null-ptr buffer
/// is a no-op. Double-free, or freeing a buffer not produced by this library,
/// is undefined behaviour.
pub extern fn frt_buf_free(buf: frt_buf) void;

/// Find the next grapheme cluster boundary strictly after `offset` in
/// `(ptr, len)`, using a single-shot `GraphemeCursor` on the Rust stack.
///
/// This is the sole grapheme primitive. Iterating a string calls it once per
/// boundary, feeding the previous result back as the next `offset`; the
/// flow-split path calls it once at a byte midpoint. `offset` is snapped FORWARD
/// to the next codepoint boundary if it is not already on one, so a caller may
/// pass an arbitrary byte midpoint. Each call re-seeds the cursor with the whole
/// string as context rather than carrying state across calls.
///
/// Returns `len` when there is no further boundary, and for the defensive cases
/// (null ptr, `len == 0`, or `offset >= len`).
///
/// Precondition (NOT checked): `(ptr, len)` is valid UTF-8.
pub extern fn frt_grapheme_boundary_after(ptr: ?[*]const u8, len: usize, offset: usize) usize;

/// Normalise `(ptr, len)` to NFC.
///
/// Returns `false` => input is already NFC; `out.*` is set to an empty buffer
/// (`ptr == null`) and the caller reuses the original input. Returns `true` =>
/// the bytes changed; `out.*` holds a newly allocated NFC buffer that the caller
/// copies out and then releases with `frt_buf_free(out.*)`.
///
/// `out` must be non-null and writable. ASCII (and any already-NFC) input takes
/// an allocation-free fast path and returns `false`.
///
/// Precondition (NOT checked): `(ptr, len)` is valid UTF-8.
pub extern fn frt_str_nfc(ptr: ?[*]const u8, len: usize, out: *frt_buf) bool;

/// Compile a regex pattern. Returns a non-null opaque handle on success; call
/// `frt_regex_drop` exactly once per non-null handle. The handle is `Send +
/// Sync` and may be used concurrently.
///
/// Returns null on a compile error; `err.*` is then a newly allocated buffer
/// with the UTF-8 error message (caller frees with `frt_buf_free`). On success
/// `err.*` is an empty buffer. `err` may be null to discard the message.
///
/// Precondition (NOT checked): `(pat, pat_len)` is valid UTF-8.
pub extern fn frt_regex_compile(pat: ?[*]const u8, pat_len: usize, err: ?*frt_buf) ?*anyopaque;

/// Drop a handle returned by `frt_regex_compile`. Null is a no-op. Double-drop
/// is undefined behaviour.
pub extern fn frt_regex_drop(handle: ?*anyopaque) void;

/// Test whether the compiled regex matches anywhere in `(hay, hay_len)`.
///
/// Preconditions (NOT checked): `handle` was produced by `frt_regex_compile`
/// and not yet dropped, and `(hay, hay_len)` is valid UTF-8. A null handle
/// returns `false`.
pub extern fn frt_regex_is_match(handle: ?*const anyopaque, hay: ?[*]const u8, hay_len: usize) bool;

/// Upper bound on bytes produced by `frt_f64_to_str`. Rust's `f64` Display never
/// uses scientific notation, so the subnormal case is the longest; 1100 is a
/// safe ceiling.
pub const FRT_F64_STR_MAX: usize = 1100;

/// Format `v` exactly as Rust's `f64::to_string(&v)` into `(out, cap)`.
///
/// Returns the number of bytes the formatted value needs. If `needed <= cap`
/// those bytes are written to `out`. If `needed > cap` NOTHING is written, so a
/// return value greater than `cap` means "too small, nothing written"; retry
/// with a larger buffer. `out` may be null when probing with `cap == 0`. The
/// return value is bounded by `FRT_F64_STR_MAX`.
pub extern fn frt_f64_to_str(v: f64, out: ?[*]u8, cap: usize) usize;

/// Parse `(ptr, len)` as an `f64` using Rust's `str::parse::<f64>`, writing the
/// result to `out.*` and returning `true` on success. On any failure (non-UTF-8
/// or not a valid float) returns `false` and leaves `out.*` untouched. Pairs
/// with `frt_f64_to_str` for byte-consistent round-tripping. `out` must be
/// non-null and writable.
pub extern fn frt_parse_f64(ptr: ?[*]const u8, len: usize, out: *f64) bool;

/// Trivial, side-effect-free call that references a `frt_*` symbol so the
/// staticlib is actually pulled into the link. Formats `1.0` into a stack
/// buffer and returns the resulting byte length (expected to be 1, for "1").
/// Invoked once from runtime init; kept cheap and pure so it can never affect
/// program behaviour, only force the linker to retain `libnative_rt.a`.
pub fn linkProbe() usize {
    var buf: [FRT_F64_STR_MAX]u8 = undefined;
    return frt_f64_to_str(1.0, &buf, buf.len);
}
