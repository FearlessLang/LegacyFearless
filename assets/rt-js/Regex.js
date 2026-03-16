import { rt$$NativeRuntime } from "./NativeRuntime.js";
import { base$$True_0 } from "../base/True_0.js";
import { base$$False_0 } from "../base/False_0.js";
import { toStringFromUtf8 } from "./BaseStr.js";

export class rt$$Regex {
  constructor(patternStr) {
    this.pattenStr = patternStr;
    this.inner = new rt$$NativeRuntime.Regex(patternStr.utf8());
  }

  // Returns the original pattern string
  str$read() {
    return this.pattenStr;
  }

  // Checks if the given string matches the regex
  isMatch$imm(str) {
    return this.inner.doesRegexMatch(str.utf8()) ? base$$True_0.$self : base$$False_0.$self;
  }
}

// NativeRuntime.Regex wrapper for WASM
rt$$NativeRuntime.Regex = class {
  constructor(utf8Pattern) {
    this.pattern = toStringFromUtf8(utf8Pattern);
    // Ensure WASM is ready
    rt$$NativeRuntime.ensureWasm();
  }

  doesRegexMatch(utf8Str) {
    const text = toStringFromUtf8(utf8Str);
    return rt$$NativeRuntime._wasmReady
      ? rt$$NativeRuntime.does_regex_match(this.pattern, text)
      : new RegExp(this.pattern).test(text);
  }
};
