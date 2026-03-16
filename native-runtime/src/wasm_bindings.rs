#![cfg(feature = "wasm")]

use wasm_bindgen::prelude::*;
use unicode_segmentation::UnicodeSegmentation;
use unicode_normalization::UnicodeNormalization;
use regex::Regex;
use seahash;

// ===============================
// String / numeric conversions
// ===============================

#[wasm_bindgen]
pub fn float_to_str(n: f64) -> Vec<u8> {
    // Converts float to UTF-8 string bytes
    n.to_string().into_bytes()
}

#[wasm_bindgen]
pub fn int_to_str(n: i64) -> Vec<u8> {
    n.to_string().into_bytes()
}

#[wasm_bindgen]
pub fn nat_to_str(n: i64) -> Vec<u8> {
    // In Java, Nat maps to unsigned 64-bit
    (n as u64).to_string().into_bytes()
}

#[wasm_bindgen]
pub fn byte_to_str(n: i8) -> Vec<u8> {
    // Unsigned byte conversion
    (n as u8).to_string().into_bytes()
}

// ============================================================
// String utilities (UTF-8 based, no JS string conversions)
// ============================================================

#[wasm_bindgen]
pub fn hash_string(bytes: &[u8]) -> u64 {
    // Computes a hash directly over UTF-8 bytes
    seahash::hash(bytes)
}

#[wasm_bindgen]
pub fn normalise_string(bytes: &[u8]) -> Vec<u8> {
    // Decodes UTF-8, normalises to NFC, returns UTF-8 bytes again
    let s = std::str::from_utf8(bytes).unwrap_or("");
    let normalized = s.nfc().collect::<String>();
    normalized.into_bytes()
}

#[wasm_bindgen]
pub fn index_string(bytes: &[u8]) -> js_sys::Int32Array {
    // Decodes UTF-8, computes grapheme cluster start indices
    let s = std::str::from_utf8(bytes).unwrap_or("");
    let indices: Vec<i32> = s
        .grapheme_indices(true)
        .map(|(idx, _)| idx as i32)
        .collect();
    js_sys::Int32Array::from(&indices[..])
}

// ============================================================
// Regex utilities
// ============================================================

#[wasm_bindgen]
pub fn compile_regex_pattern(pattern_bytes: &[u8]) -> Result<JsValue, String> {
    let pattern_str = std::str::from_utf8(pattern_bytes)
        .map_err(|_| "Invalid UTF-8 in regex pattern")?;
    Regex::new(pattern_str)
        .map(|_re| JsValue::from_str(pattern_str))
        .map_err(|e| e.to_string())
}

#[wasm_bindgen]
pub fn does_regex_match(pattern_bytes: &[u8], text_bytes: &[u8]) -> bool {
    let pattern = match std::str::from_utf8(pattern_bytes) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let text = match std::str::from_utf8(text_bytes) {
        Ok(s) => s,
        Err(_) => return false,
    };
    match Regex::new(pattern) {
        Ok(re) => re.is_match(text),
        Err(_) => false,
    }
}


// ===============================
// String validation (UTF-8 check)
// ===============================

#[wasm_bindgen]
pub fn validate_string(bytes: &[u8]) -> Result<(), String> {
    if std::str::from_utf8(bytes).is_err() {
        Err(String::from("Invalid UTF-8 sequence"))
    } else {
        Ok(())
    }
}
