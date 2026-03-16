// testNativeRuntimeStrings.js
import { rt$$NativeRuntime } from "../../../assets/rt-js/NativeRuntime.js";
import assert from "node:assert/strict";

rt$$NativeRuntime.ensureWasm();

const enc = new TextEncoder();
const dec = new TextDecoder("utf-8");

console.log("Running NativeRuntime WASM UTF-8 tests…");

// --- hashString ---
{
  const s = "Hello 🌍";
  const utf8 = enc.encode(s);
  const h1 = rt$$NativeRuntime.hashString(utf8);
  const h2 = rt$$NativeRuntime.hashString(utf8);
  assert.equal(h1, h2, "hashString must be deterministic");
}

// --- normaliseString ---
{
  // Pre-composed U+00C5 vs decomposed U+0041 + U+030A
  const composed = enc.encode("Å");
  const decomposed = enc.encode("Å");

  const normalised = rt$$NativeRuntime.normaliseString(decomposed);
  const normStr = dec.decode(normalised);

  assert.equal(normStr, "Å", "normaliseString should canonicalize decomposed forms");
}

// --- indexString ---
{
  const input = "👩🏽‍💻abc";
  const utf8 = enc.encode(input);
  const indices = rt$$NativeRuntime.indexString(utf8);

  assert.ok(indices instanceof Int32Array, "indexString must return an Int32Array");
  assert.ok(indices.length > 1, "indexString must return at least one grapheme index");
  assert.equal(indices[0], 0, "First grapheme should start at 0");
}

// --- compileRegex and doesRegexMatch ---
{
  const pattern = "^[a-z]+$";
  const patUtf8 = enc.encode(pattern);

  const compiled = rt$$NativeRuntime.compileRegexPattern(patUtf8);
  assert.ok(compiled, "compileRegex should return a valid handle");

  assert.equal(rt$$NativeRuntime.doesRegexMatch(patUtf8, enc.encode("hello")), true);
  assert.equal(rt$$NativeRuntime.doesRegexMatch(patUtf8, enc.encode("HELLO")), false);
  assert.equal(rt$$NativeRuntime.doesRegexMatch(patUtf8, enc.encode("abc123")), false);
  assert.equal(rt$$NativeRuntime.doesRegexMatch(patUtf8, enc.encode("abc")), true);

  const numPattern = enc.encode("^[0-9]+$");
  assert.equal(rt$$NativeRuntime.doesRegexMatch(numPattern, enc.encode("12345")), true);
  assert.equal(rt$$NativeRuntime.doesRegexMatch(numPattern, enc.encode("123a")), false);
}

console.log("✅ All NativeRuntime WASM UTF-8 assertions passed.\n");
