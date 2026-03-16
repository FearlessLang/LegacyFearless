import * as wasmGlue from "./libwasm/native_rt.js";
import { _toUint8Array, toStringFromUtf8 } from "./BaseStr.js";
let fs, path, url;

try {
  // Dynamic import works only in Node; browsers will skip it
  if (typeof process !== "undefined" && process.versions?.node) {
    fs = await import("fs");
    path = await import("path");
    url = await import("url");
  }
} catch (e) {
  // ignored — browser environment
}

export const rt$$NativeRuntime = {
  async ensureWasm() {
    try {
      const isNode = typeof process !== "undefined" && process.versions?.node;
      if (isNode) {
        // Resolve wasm path relative to this JS file
        const __dirname = path.dirname(url.fileURLToPath(import.meta.url));
        const wasmPath = path.join(__dirname, "libwasm/native_rt_bg.wasm");
        const wasmBytes = fs.readFileSync(wasmPath);
        wasmGlue.initSync({ module: wasmBytes });
      } else {
        // Browser: fetch wasm and pass bytes
        const wasmUrl = new URL("./rt-js/libwasm/native_rt_bg.wasm", import.meta.url);
        const response = await fetch(wasmUrl);
        const bytes = await response.arrayBuffer();
        wasmGlue.initSync({ module: new WebAssembly.Module(bytes) });
      }
    } catch (e) {
      console.warn("NativeRuntime: wasm init failed:", e);
    }
  },
  // Normalize input to a JS string (since wasm-bindgen glue expects string)
  _toJsString(s) {
    if (typeof s === "string") return s;
    if (s instanceof Uint8Array) return new TextDecoder("utf-8").decode(s);
    throw new TypeError(`Expected string or Uint8Array, got ${typeof s}`);
  },

  validateStringOrThrow(bytes) {
    return wasmGlue.validate_string(bytes);
  },

  floatToStr(n) {
    return wasmGlue.float_to_str(n);
  },

  normaliseString(s) {
    // this.ensureWasm();
    // const jsStr = this._toJsString(s);
    return wasmGlue.normalise_string(s);
  },

  hashString(s) {
    // this.ensureWasm();
    // const jsStr = this._toJsString(s);
    return wasmGlue.hash_string(s);
  },

  indexString(s) {
    // this.ensureWasm();
    // const jsStr = this._toJsString(s);
    return wasmGlue.index_string(s);
  },

  compileRegexPattern(pat) {
    // this.ensureWasm();
    // const p = this._toJsString(pat);
    return wasmGlue.compile_regex_pattern(pat);
  },

  doesRegexMatch(pat, text) {
    // this.ensureWasm();
    // const p = this._toJsString(pat);
    // const t = this._toJsString(text);
    return wasmGlue.does_regex_match(pat, text);
  },


  // Regex class compatible with Java API
  Regex: class {
    constructor(patternStr) {
      // store original Str object
      this.pattenStr = patternStr;
      // convert Str/UTF-8 to JS string
      const s = toStringFromUtf8(
        _toUint8Array(patternStr)
      );
      // compile via WASM
      try {
        this.patternPtr = rt$$NativeRuntime.compileRegexPattern(s); // in JS, just store string
      } catch (err) {
        throw new Error(`Invalid regex: ${err}`);
      }
      // optionally, could implement a cleanup function if needed
      this._cleaned = false;
    }

    str$read() {
      return this.pattenStr;
    }

    doesRegexMatch(str) {
      const text = toStringFromUtf8(
        _toUint8Array(str)
      );
      return rt$$NativeRuntime.doesRegexMatch(this.patternPtr, text);
    }

    // optional: cleanup method to mimic Java Cleaner
    cleanup() {
      if (!this._cleaned) {
        // in JS/WASM, nothing to free unless you implement caching
        this._cleaned = true;
      }
    }
  }
};

