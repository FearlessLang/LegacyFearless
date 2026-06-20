//! C-ABI surface for the Zig runtime.
//!
//! This module is gated behind the `capi` feature and contains ZERO `jni`
//! imports. It exposes Unicode (grapheme segmentation, NFC normalisation),
//! regex, and f64 formatting helpers so the Zig runtime can link the same Rust
//! dependencies the JNI surface uses, without going through JNI.
//!
//! # Panics
//! No panicking path is reachable from any `extern "C"` function in this
//! module (the release profile is `panic = "abort"`). UB-preconditions such as
//! "input is valid UTF-8" are documented per function and are NOT checked.

// Every `frt_*` entry point that dereferences a caller-supplied raw pointer is
// an `unsafe fn` (the caller upholds the documented preconditions). Keep the
// inner `unsafe {}` blocks meaningful under edition 2021 by opting in to the
// edition-2024 behaviour of `unsafe_op_in_unsafe_fn`.
#![deny(unsafe_op_in_unsafe_fn)]

use std::slice;

use regex::Regex;
use unicode_normalization::{is_nfc_quick, IsNormalized, UnicodeNormalization};
use unicode_segmentation::GraphemeCursor;

/// A Rust-owned `Vec<u8>` decomposed into its raw parts so it can cross the C
/// ABI. Must be returned to Rust via [`frt_buf_free`] to release the
/// allocation; freeing it any other way is undefined behaviour.
#[repr(C)]
pub struct frt_buf {
    pub ptr: *mut u8,
    pub len: usize,
    pub cap: usize,
}

impl frt_buf {
    /// An empty buffer that owns no allocation.
    const EMPTY: frt_buf = frt_buf {
        ptr: std::ptr::null_mut(),
        len: 0,
        cap: 0,
    };

    /// Decompose a `Vec<u8>` into a `frt_buf`. Ownership of the allocation
    /// transfers to the buffer.
    fn from_vec(mut v: Vec<u8>) -> frt_buf {
        let ptr = v.as_mut_ptr();
        let len = v.len();
        let cap = v.capacity();
        std::mem::forget(v);
        frt_buf { ptr, len, cap }
    }
}

/// Free a [`frt_buf`] previously produced by this library.
///
/// A zero-`cap` buffer (e.g. the result when nothing was allocated) is a no-op.
///
/// # Safety
/// - `buf` was produced by this library and has not been freed before (no
///   double-free), and its parts were not freed by any other means.
#[no_mangle]
pub unsafe extern "C" fn frt_buf_free(buf: frt_buf) {
    if !buf.ptr.is_null() && buf.cap != 0 {
        // Safety: per the contract `buf` was produced by `frt_buf::from_vec`,
        // so these parts describe a live `Vec<u8>` allocation.
        unsafe {
            drop(Vec::from_raw_parts(buf.ptr, buf.len, buf.cap));
        }
    }
}

/// Find the next grapheme cluster boundary strictly after `offset` in
/// `(ptr, len)`, using a single-shot [`GraphemeCursor`] on the stack.
///
/// This is the sole grapheme primitive: iterating a string calls it once per
/// boundary (feeding the previous result back as the next `offset`), and the
/// flow-split path calls it once at a byte midpoint. `offset` is snapped FORWARD
/// to the next codepoint boundary if it does not already sit on one, so a caller
/// may pass an arbitrary byte midpoint. Returns `len` when there is no further
/// boundary, and for the defensive cases (null ptr, `len == 0`, or a snapped
/// offset that reaches `len`).
///
/// Each call re-seeds a fresh cursor with the whole string as context, so the
/// segmentation state (category lookups, regional-indicator parity) is
/// recomputed per boundary rather than carried across calls.
///
/// # Safety
/// - `(ptr, len)` describe valid UTF-8.
#[no_mangle]
pub unsafe extern "C" fn frt_grapheme_boundary_after(
    ptr: *const u8,
    len: usize,
    offset: usize,
) -> usize {
    if ptr.is_null() || len == 0 || offset >= len {
        return len;
    }
    // Safety: per the contract `(ptr, len)` describe valid UTF-8 bytes.
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };
    let s = match std::str::from_utf8(bytes) {
        Ok(s) => s,
        // Documented precondition violated; fail closed rather than panic.
        Err(_) => return len,
    };
    // GraphemeCursor requires its start offset to be a char boundary; snap any
    // mid-codepoint midpoint forward to the next one.
    let mut off = offset;
    while off < len && !s.is_char_boundary(off) {
        off += 1;
    }
    if off >= len {
        return len;
    }
    let mut cursor = GraphemeCursor::new(off, len, true);
    // The whole string is one chunk (chunk_start = 0), so `next_boundary` never
    // requests pre-context or a further chunk.
    match cursor.next_boundary(s, 0) {
        Ok(Some(b)) => b,
        // No further boundary: the rest of the string is one cluster.
        Ok(None) => len,
        Err(_) => len,
    }
}

/// Parse `(ptr, len)` as an `f64` using Rust's `str::parse::<f64>`, writing the
/// result to `*out` and returning `true` on success. On any failure (non-UTF-8,
/// not a valid float) returns `false` and leaves `*out` untouched. Pairs with
/// [`frt_f64_to_str`] so round-tripping is byte-consistent.
///
/// # Safety
/// - `out` is non-null and writable.
/// - `(ptr, len)` is either `(null, 0)` or describes a readable byte slice.
#[no_mangle]
pub unsafe extern "C" fn frt_parse_f64(ptr: *const u8, len: usize, out: *mut f64) -> bool {
    if out.is_null() {
        return false;
    }
    let bytes = if ptr.is_null() || len == 0 {
        b"".as_slice()
    } else {
        // Safety: per the contract `(ptr, len)` describe a byte slice.
        unsafe { slice::from_raw_parts(ptr, len) }
    };
    let s = match std::str::from_utf8(bytes) {
        Ok(s) => s,
        Err(_) => return false,
    };
    match s.parse::<f64>() {
        Ok(v) => {
            // Safety: `out` checked non-null above.
            unsafe {
                *out = v;
            }
            true
        }
        Err(_) => false,
    }
}

/// Normalise `ptr`/`len` to NFC.
///
/// Returns `false` when the input is already NFC; in that case `*out` is left
/// as an empty buffer and the caller should reuse the original input.
///
/// Returns `true` when normalisation changed the bytes; in that case `*out` is
/// set to a newly allocated buffer holding the NFC bytes. The caller copies the
/// bytes out and then frees the buffer with [`frt_buf_free`].
///
/// # Safety
/// - `out` is non-null and writable.
/// - `ptr`/`len` is either `(null, 0)` or describes valid UTF-8.
#[no_mangle]
pub unsafe extern "C" fn frt_str_nfc(ptr: *const u8, len: usize, out: *mut frt_buf) -> bool {
    if out.is_null() {
        return false;
    }
    // Safety: caller provides a writable `frt_buf` at `out`.
    unsafe {
        *out = frt_buf::EMPTY;
    }
    if ptr.is_null() || len == 0 {
        return false;
    }
    // Safety: per the contract `ptr`/`len` describe valid UTF-8 bytes.
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };
    let s = match std::str::from_utf8(bytes) {
        Ok(s) => s,
        Err(_) => return false,
    };
    // Fast path: already-NFC inputs (including all ASCII) allocate nothing.
    if is_nfc_quick(s.chars()) == IsNormalized::Yes {
        return false;
    }
    let nfc: String = s.nfc().collect();
    if nfc.as_bytes() == bytes {
        return false;
    }
    // Safety: `out` checked non-null above.
    unsafe {
        *out = frt_buf::from_vec(nfc.into_bytes());
    }
    true
}

/// Compile a regex pattern.
///
/// Returns an opaque handle on success (one [`frt_regex_drop`] per non-NULL
/// return), or NULL on a compile error. On error, `*err` is set to a newly
/// allocated buffer holding the UTF-8 error message, which the caller frees
/// with [`frt_buf_free`]. On success `*err` is left as an empty buffer.
///
/// The returned handle is `Send + Sync` (`regex::Regex` is thread-safe) and may
/// be used concurrently from multiple threads.
///
/// # Safety
/// - `err` is null or points to a writable `frt_buf`.
/// - `pat`/`pat_len` is either `(null, 0)` or describes valid UTF-8.
#[no_mangle]
pub unsafe extern "C" fn frt_regex_compile(
    pat: *const u8,
    pat_len: usize,
    err: *mut frt_buf,
) -> *mut std::ffi::c_void {
    if !err.is_null() {
        // Safety: caller provides a writable `frt_buf` at `err`.
        unsafe {
            *err = frt_buf::EMPTY;
        }
    }
    let bytes = if pat.is_null() || pat_len == 0 {
        b"".as_slice()
    } else {
        // Safety: per the contract `pat`/`pat_len` describe valid UTF-8 bytes.
        unsafe { slice::from_raw_parts(pat, pat_len) }
    };
    let pattern = match std::str::from_utf8(bytes) {
        Ok(s) => s,
        Err(e) => {
            // Safety: `err` upholds the same contract `write_err` requires.
            unsafe { write_err(err, e.to_string()) };
            return std::ptr::null_mut();
        }
    };
    match Regex::new(pattern) {
        Ok(re) => Box::into_raw(Box::new(re)) as *mut std::ffi::c_void,
        Err(e) => {
            // Safety: `err` upholds the same contract `write_err` requires.
            unsafe { write_err(err, e.to_string()) };
            std::ptr::null_mut()
        }
    }
}

/// # Safety
/// - `err` is null or points to a writable `frt_buf`.
unsafe fn write_err(err: *mut frt_buf, msg: String) {
    if !err.is_null() {
        // Safety: caller guarantees `err` is writable when non-null.
        unsafe {
            *err = frt_buf::from_vec(msg.into_bytes());
        }
    }
}

/// Drop a regex handle returned by [`frt_regex_compile`]. NULL is a no-op.
///
/// # Safety
/// - `handle` is null, or was produced by [`frt_regex_compile`] and has not
///   already been dropped (no double-drop).
#[no_mangle]
pub unsafe extern "C" fn frt_regex_drop(handle: *mut std::ffi::c_void) {
    if !handle.is_null() {
        // Safety: per the contract `handle` was produced by `frt_regex_compile`.
        unsafe {
            drop(Box::from_raw(handle as *mut Regex));
        }
    }
}

/// Test whether the regex `handle` matches anywhere in `hay`/`hay_len`.
///
/// # Safety
/// - `handle` is null, or was produced by [`frt_regex_compile`] and not yet
///   dropped.
/// - `hay`/`hay_len` is either `(null, 0)` or describes valid UTF-8.
#[no_mangle]
pub unsafe extern "C" fn frt_regex_is_match(
    handle: *const std::ffi::c_void,
    hay: *const u8,
    hay_len: usize,
) -> bool {
    if handle.is_null() {
        return false;
    }
    // Safety: per the contract `handle` is a live regex from `frt_regex_compile`.
    let re = unsafe { &*(handle as *const Regex) };
    let bytes = if hay.is_null() || hay_len == 0 {
        b"".as_slice()
    } else {
        // Safety: per the contract `hay`/`hay_len` describe valid UTF-8 bytes.
        unsafe { slice::from_raw_parts(hay, hay_len) }
    };
    match std::str::from_utf8(bytes) {
        Ok(s) => re.is_match(s),
        Err(_) => false,
    }
}

/// Upper bound on the number of bytes `f64::to_string` can produce. Rust's
/// `Display` impl for `f64` never uses scientific notation, so the subnormal
/// worst case is the longest output; 1100 bytes is a safe ceiling.
pub const FRT_F64_STR_MAX: usize = 1100;

/// Format `v` exactly as `f64::to_string(&v)` into `out`/`cap`.
///
/// Returns the number of bytes the formatted value needs. If `cap` is large
/// enough, those bytes are written to `out` and the same length is returned. If
/// `cap` is too small (`cap < needed`), NOTHING is written and the needed
/// length is returned so the caller can retry with a larger buffer. A return
/// value greater than `cap` therefore signals "buffer too small, nothing
/// written".
///
/// `FRT_F64_STR_MAX` is a safe upper bound on the return value.
///
/// # Safety
/// - `out` is null, or points to a writable buffer of at least `cap` bytes.
#[no_mangle]
pub unsafe extern "C" fn frt_f64_to_str(v: f64, out: *mut u8, cap: usize) -> usize {
    let s = v.to_string();
    let bytes = s.as_bytes();
    let needed = bytes.len();
    if needed <= cap && !out.is_null() {
        // Safety: `out` has room for `needed` bytes (checked) and is writable.
        unsafe {
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, needed);
        }
    }
    needed
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collect_buf(buf: &frt_buf) -> Vec<u8> {
        if buf.ptr.is_null() {
            return Vec::new();
        }
        unsafe { slice::from_raw_parts(buf.ptr, buf.len).to_vec() }
    }

    #[test]
    fn buf_free_empty_is_noop() {
        // Safety: the empty buffer owns no allocation.
        unsafe { frt_buf_free(frt_buf::EMPTY) };
    }

    /// Walk a string's grapheme boundaries end-to-end by repeatedly asking for
    /// the next boundary -- the same one-shot the runtime uses to iterate.
    fn sweep_boundaries(bytes: &[u8]) -> Vec<usize> {
        let mut boundaries = Vec::new();
        let mut pos = 0;
        while pos < bytes.len() {
            // Safety: `bytes` is valid UTF-8 and `pos` is a grapheme boundary.
            pos = unsafe { frt_grapheme_boundary_after(bytes.as_ptr(), bytes.len(), pos) };
            boundaries.push(pos);
        }
        boundaries
    }

    #[test]
    fn grapheme_sweep_basic_ascii() {
        assert_eq!(sweep_boundaries(b"abc"), vec![1, 2, 3]);
    }

    #[test]
    fn grapheme_sweep_zwj_emoji_is_one_cluster() {
        // Family: man ZWJ woman ZWJ girl ZWJ boy is a single grapheme cluster.
        let s = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}\u{200D}\u{1F466}";
        let bytes = s.as_bytes();
        assert_eq!(sweep_boundaries(bytes), vec![bytes.len()]);
    }

    #[test]
    fn grapheme_sweep_mixed_clusters() {
        // "a" + precomposed e-acute + family-emoji + "z". A forward sweep yields
        // a boundary at the end of each cluster.
        let s = "a\u{00E9}\u{1F468}\u{200D}\u{1F469}z";
        let bytes = s.as_bytes();
        let expected = vec![
            1,                       // a
            1 + 2,                   // e-acute (2 bytes)
            1 + 2 + 11,              // man-ZWJ-woman cluster (4+3+4 bytes)
            1 + 2 + 11 + 1,          // z
        ];
        assert_eq!(sweep_boundaries(bytes), expected);
    }

    #[test]
    fn grapheme_boundary_after_ascii() {
        let s = b"abc";
        // Safety: `s` is valid UTF-8 for its whole length.
        unsafe {
            assert_eq!(frt_grapheme_boundary_after(s.as_ptr(), s.len(), 0), 1);
            assert_eq!(frt_grapheme_boundary_after(s.as_ptr(), s.len(), 1), 2);
            assert_eq!(frt_grapheme_boundary_after(s.as_ptr(), s.len(), 2), 3);
            // Offset already at the end: no further boundary, returns len.
            assert_eq!(frt_grapheme_boundary_after(s.as_ptr(), s.len(), 3), 3);
        }
    }

    #[test]
    fn grapheme_boundary_after_jumps_whole_cluster_and_snaps() {
        // "a" + (man ZWJ woman, one cluster) + "z".
        let s = "a\u{1F468}\u{200D}\u{1F469}z";
        let bytes = s.as_bytes();
        let cluster_end = 1 + (4 + 3 + 4); // past the whole ZWJ cluster
        // Safety: `bytes` is valid UTF-8 for its whole length.
        unsafe {
            // After 'a' (offset 1) the next boundary is the end of the cluster.
            assert_eq!(
                frt_grapheme_boundary_after(bytes.as_ptr(), bytes.len(), 1),
                cluster_end
            );
            // A midpoint landing inside the first emoji snaps forward to the
            // next codepoint boundary, then still reports the cluster end.
            assert_eq!(
                frt_grapheme_boundary_after(bytes.as_ptr(), bytes.len(), 3),
                cluster_end
            );
            // 'z' is the final single-codepoint cluster.
            assert_eq!(
                frt_grapheme_boundary_after(bytes.as_ptr(), bytes.len(), cluster_end),
                bytes.len()
            );
        }
    }

    #[test]
    fn grapheme_boundary_after_defensive_inputs() {
        let s = b"ab";
        // Safety: null/`s` are valid inputs for their lengths.
        unsafe {
            assert_eq!(frt_grapheme_boundary_after(std::ptr::null(), 0, 0), 0);
            // offset >= len returns len.
            assert_eq!(frt_grapheme_boundary_after(s.as_ptr(), s.len(), 2), 2);
            assert_eq!(frt_grapheme_boundary_after(s.as_ptr(), s.len(), 99), 2);
        }
    }

    #[test]
    fn parse_f64_roundtrips_and_rejects() {
        fn parse(s: &str) -> Option<f64> {
            let mut out = f64::NAN;
            // Safety: `s` is a readable byte slice and `out` is writable.
            let ok = unsafe { frt_parse_f64(s.as_ptr(), s.len(), &mut out) };
            if ok { Some(out) } else { None }
        }
        for v in ["0", "-0", "42.1337", "1e300", "12345678901234567890"] {
            assert_eq!(parse(v), Some(v.parse::<f64>().unwrap()));
        }
        // Bit-identical signed zero.
        assert_eq!(parse("-0").unwrap().to_bits(), (-0.0f64).to_bits());
        // Rejections leave the slot untouched and return false.
        let mut out = 123.0f64;
        // Safety: readable byte slices; `out` writable / null where noted.
        unsafe {
            assert!(!frt_parse_f64(b"xyz".as_ptr(), 3, &mut out));
            assert_eq!(out, 123.0);
            assert!(!frt_parse_f64(b"".as_ptr(), 0, &mut out));
            assert!(!frt_parse_f64(b"1.0".as_ptr(), 3, std::ptr::null_mut()));
        }
    }

    #[test]
    fn nfc_ascii_fast_path_returns_false() {
        let s = b"hello world";
        let mut out = frt_buf::EMPTY;
        // Safety: `s` is valid UTF-8; `out` is writable.
        let changed = unsafe { frt_str_nfc(s.as_ptr(), s.len(), &mut out) };
        assert!(!changed);
        assert!(out.ptr.is_null());
    }

    #[test]
    fn nfc_decomposed_input_returns_true_with_correct_bytes() {
        // "e" + combining acute accent -> precomposed "é".
        let s = "e\u{0301}";
        let bytes = s.as_bytes();
        let mut out = frt_buf::EMPTY;
        // Safety: `bytes` is valid UTF-8; `out` is writable.
        let changed = unsafe { frt_str_nfc(bytes.as_ptr(), bytes.len(), &mut out) };
        assert!(changed);
        let got = collect_buf(&out);
        assert_eq!(got, "\u{00E9}".as_bytes());
        // Safety: `out` was produced by `frt_str_nfc` above and freed once.
        unsafe { frt_buf_free(out) };
    }

    #[test]
    fn nfc_already_composed_returns_false() {
        let s = "\u{00E9}"; // already NFC
        let bytes = s.as_bytes();
        let mut out = frt_buf::EMPTY;
        // Safety: `bytes` is valid UTF-8; `out` is writable.
        let changed = unsafe { frt_str_nfc(bytes.as_ptr(), bytes.len(), &mut out) };
        assert!(!changed);
        assert!(out.ptr.is_null());
    }

    #[test]
    fn nfc_empty_returns_false() {
        let mut out = frt_buf::EMPTY;
        // Safety: null/empty inputs; `out` is writable.
        unsafe {
            assert!(!frt_str_nfc(std::ptr::null(), 0, &mut out));
            assert!(!frt_str_nfc(b"".as_ptr(), 0, &mut out));
        }
    }

    #[test]
    fn regex_compile_match_and_drop() {
        let pat = b"^a.c$";
        let mut err = frt_buf::EMPTY;
        // Safety: valid UTF-8 pattern / haystacks; `err` writable; `h` dropped once.
        unsafe {
            let h = frt_regex_compile(pat.as_ptr(), pat.len(), &mut err);
            assert!(!h.is_null());
            assert!(err.ptr.is_null());

            let hay = b"abc";
            assert!(frt_regex_is_match(h, hay.as_ptr(), hay.len()));
            let hay2 = b"abcd";
            assert!(!frt_regex_is_match(h, hay2.as_ptr(), hay2.len()));
            // Empty haystack against a pattern requiring content.
            assert!(!frt_regex_is_match(h, std::ptr::null(), 0));

            frt_regex_drop(h);
        }
    }

    #[test]
    fn regex_compile_error_path() {
        let pat = b"(unclosed";
        let mut err = frt_buf::EMPTY;
        // Safety: valid UTF-8 pattern; `err` writable; the error buf freed once.
        unsafe {
            let h = frt_regex_compile(pat.as_ptr(), pat.len(), &mut err);
            assert!(h.is_null());
            assert!(!err.ptr.is_null());
            let msg = collect_buf(&err);
            assert!(!msg.is_empty());
            // Should be valid UTF-8 error text.
            assert!(std::str::from_utf8(&msg).is_ok());
            frt_buf_free(err);
        }
    }

    #[test]
    fn regex_drop_null_is_noop() {
        // Safety: null handle drop is a no-op; null handle match returns false.
        unsafe {
            frt_regex_drop(std::ptr::null_mut());
            assert!(!frt_regex_is_match(std::ptr::null(), b"x".as_ptr(), 1));
        }
    }

    #[test]
    fn f64_to_str_roundtrips() {
        fn fmt(v: f64) -> String {
            let mut buf = [0u8; FRT_F64_STR_MAX];
            // Safety: `buf` is writable for its whole length.
            let n = unsafe { frt_f64_to_str(v, buf.as_mut_ptr(), buf.len()) };
            assert!(n <= buf.len());
            String::from_utf8(buf[..n].to_vec()).unwrap()
        }
        for v in [0.0f64, 1.0, -1.0, 3.14159, 1e300, f64::MIN_POSITIVE] {
            assert_eq!(fmt(v), v.to_string());
            assert_eq!(fmt(v).parse::<f64>().unwrap(), v);
        }
        // Subnormal.
        let sub = f64::from_bits(1);
        assert_eq!(fmt(sub), sub.to_string());
        // Signed zero.
        assert_eq!(fmt(0.0), "0");
        assert_eq!(fmt(-0.0), "-0");
        // Specials.
        assert_eq!(fmt(f64::NAN), "NaN");
        assert_eq!(fmt(f64::INFINITY), "inf");
        assert_eq!(fmt(f64::NEG_INFINITY), "-inf");
    }

    #[test]
    fn f64_to_str_cap_too_small_writes_nothing() {
        let v = 12345.678f64;
        let s = v.to_string();
        // Safety: null/`buf` are valid `out` arguments for the given caps.
        // cap = 0: returns needed length, writes nothing.
        let needed = unsafe { frt_f64_to_str(v, std::ptr::null_mut(), 0) };
        assert_eq!(needed, s.len());
        // cap one short: writes nothing, returns needed.
        let mut buf = vec![0xAAu8; s.len() - 1];
        let n = unsafe { frt_f64_to_str(v, buf.as_mut_ptr(), buf.len()) };
        assert_eq!(n, s.len());
        assert!(n > buf.len());
        assert!(buf.iter().all(|&b| b == 0xAA), "buffer must be untouched");
    }

    #[test]
    fn f64_str_max_bounds_subnormal() {
        let sub = f64::from_bits(1);
        assert!(sub.to_string().len() <= FRT_F64_STR_MAX);
    }
}
