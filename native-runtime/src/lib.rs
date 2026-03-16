// JNI-based strings + regex modules (only for JVM/Android/native builds)
#[cfg(not(feature = "wasm"))]
pub mod strings;

#[cfg(not(feature = "wasm"))]
pub mod regex;

// Compiler-only module
#[cfg(feature = "compiler-only")]
pub mod compiler;

// WASM bindings (only for wasm32 builds with "wasm" feature)
#[cfg(feature = "wasm")]
pub mod wasm_bindings;
