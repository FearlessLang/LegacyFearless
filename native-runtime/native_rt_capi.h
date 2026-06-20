/*
 * native_rt_capi.h
 *
 * Reference C declarations for the `capi` surface of the Fearless native
 * runtime (the Rust crate `native-rt`, built with `--features capi` as a
 * staticlib). The Zig runtime declares these externs directly; THIS HEADER IS
 * DOCUMENTATION ONLY and is not consumed by any build.
 *
 * General rules:
 *  - All functions are extern "C", no_mangle, and never panic (the Rust release
 *    profile is panic = "abort").
 *  - String inputs are (ptr, len) byte pairs. Unless stated, they must be valid
 *    UTF-8; this is a PRECONDITION and is NOT checked. Violating it does not
 *    cause UB here (the Rust side fails closed), but the result is unspecified.
 *  - Any `frt_buf` written by this library must be released with frt_buf_free.
 */

#ifndef NATIVE_RT_CAPI_H
#define NATIVE_RT_CAPI_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * A Rust-owned Vec<u8> decomposed into raw parts so it can cross the C ABI.
 * `ptr` may be NULL with len == cap == 0 to denote "no allocation". The caller
 * must NOT free `ptr` directly; ownership is returned to Rust via frt_buf_free.
 */
typedef struct {
    uint8_t *ptr;
    size_t   len;
    size_t   cap;
} frt_buf;

/*
 * Release a frt_buf produced by this library. A zero-cap / NULL-ptr buffer is a
 * no-op. Double-free, or freeing a buffer not produced by this library, is UB.
 */
void frt_buf_free(frt_buf buf);

/*
 * Find the next grapheme cluster boundary strictly after `offset` in (ptr, len),
 * using a single-shot cursor on the Rust stack.
 *
 * This is the sole grapheme primitive. Iterating a string calls it once per
 * boundary, feeding the previous result back as the next `offset`; the
 * flow-split path calls it once at a byte midpoint. `offset` is snapped FORWARD
 * to the next codepoint boundary if it is not already on one, so a caller may
 * pass an arbitrary byte midpoint. Each call re-seeds the cursor with the whole
 * string as context rather than carrying state across calls.
 *
 * Returns `len` when there is no further boundary, and for the defensive cases
 * (NULL ptr, len == 0, or offset >= len).
 *
 * Preconditions (NOT checked): (ptr, len) is valid UTF-8.
 */
size_t frt_grapheme_boundary_after(const uint8_t *ptr, size_t len, size_t offset);

/*
 * Normalise (ptr, len) to NFC.
 *
 * Returns false  => input is already NFC. `*out` is set to an empty buffer
 *                   (ptr == NULL). The caller reuses the original input.
 * Returns true   => normalisation changed the bytes. `*out` is set to a newly
 *                   allocated buffer holding the NFC bytes. The caller copies
 *                   the bytes out and then calls frt_buf_free(*out).
 *
 * `out` must be non-NULL and writable. ASCII (and any already-NFC) input takes
 * a fast path that allocates nothing and returns false.
 *
 * Preconditions (NOT checked): (ptr, len) is valid UTF-8.
 */
bool frt_str_nfc(const uint8_t *ptr, size_t len, frt_buf *out);

/*
 * Compile a regex pattern.
 *
 * Returns a non-NULL opaque handle on success. Call frt_regex_drop exactly once
 * per non-NULL handle. The handle is thread-safe (Send + Sync) and may be used
 * concurrently from multiple threads.
 *
 * Returns NULL on a compile error; `*err` is then set to a newly allocated
 * buffer holding the UTF-8 error message (caller frees with frt_buf_free). On
 * success `*err` is set to an empty buffer. `err` may be NULL to discard the
 * message.
 *
 * Preconditions (NOT checked): (pat, pat_len) is valid UTF-8.
 */
void *frt_regex_compile(const uint8_t *pat, size_t pat_len, frt_buf *err);

/*
 * Drop a handle returned by frt_regex_compile. NULL is a no-op. Double-drop is
 * UB.
 */
void frt_regex_drop(void *handle);

/*
 * Test whether the compiled regex matches anywhere in (hay, hay_len).
 *
 * Preconditions (NOT checked):
 *   - `handle` was produced by frt_regex_compile and not yet dropped.
 *   - (hay, hay_len) is valid UTF-8.
 * A NULL handle returns false.
 */
bool frt_regex_is_match(const void *handle, const uint8_t *hay, size_t hay_len);

/*
 * Upper bound on bytes produced by frt_f64_to_str. Rust's f64 Display never
 * uses scientific notation, so the subnormal case is the longest; 1100 is a
 * safe ceiling.
 */
#define FRT_F64_STR_MAX 1100

/*
 * Format `v` exactly as Rust's f64::to_string(&v) into (out, cap).
 *
 * Returns the number of bytes the formatted value needs.
 *   - If needed <= cap: those bytes are written to `out` and `needed` is
 *     returned.
 *   - If needed > cap (buffer too small): NOTHING is written to `out` and
 *     `needed` is returned, so the caller can retry with a larger buffer.
 * Thus a return value greater than `cap` means "too small, nothing written".
 * `out` may be NULL when probing for the needed length with cap == 0.
 *
 * Note: outputs use Rust formatting, e.g. "inf", "-inf", "NaN", "0", "-0".
 * FRT_F64_STR_MAX bounds the return value.
 */
size_t frt_f64_to_str(double v, uint8_t *out, size_t cap);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* NATIVE_RT_CAPI_H */
