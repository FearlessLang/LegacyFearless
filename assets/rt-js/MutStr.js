import { BaseStr } from "./BaseStr.js";
import { rt$$NativeRuntime } from "./NativeRuntime.js";
import { base$$True_0 } from "../base/True_0.js";
import { base$$False_0 } from "../base/False_0.js";
import { base$$Void_0 } from "../base/Void_0.js";
import { rt$$Str } from "./Str.js";

export class rt$$MutStr extends BaseStr {
  constructor(str) {
    super();
    // Start with an empty buffer, grow as needed
    this._buffer = new Uint8Array(16);
    this._length = 0; // actual bytes used
    this._graphemes = null;
    if (str) {
      this.put(str);
    }
  }

  // Return a Str-like object with the current bytes
  utf8() {
    return this._buffer.slice(0, this._length);
  }

  // Compute or return cached grapheme indices
  graphemes() {
    if (this._graphemes) return this._graphemes;
    this._graphemes = rt$$NativeRuntime.indexString(this.utf8());
    return this._graphemes;
  }

  // Is the string empty?
  isEmpty$read$0() {
    return this._length === 0 ? base$$True_0.$self : base$$False_0.$self;
  }

  // Mutable concatenation
  $plus$mut$1(other$) {
    if (typeof other$.str$read$0 === "function") {
      const other = other$.str$read$0();
      this.put(other);
    }
    return this;
  }

  append$mut(other$) {
    this.$plus$mut$1(other$);
    return base$$Void_0.$self;
  }

  clear$mut() {
    this._buffer = new Uint8Array(16);
    this._length = 0;
    this._graphemes = null;
    return base$$Void_0.$self;
  }

  str$read$0() {
    return rt$$Str.fromTrustedUtf8(this.utf8());
  }

  // --- private helper ---
  put(str) {
    this._graphemes = null;
    // const bytes = str.utf8();
    // Accept plain JS strings or rt$$Str
    let bytes;
    if (typeof str === "string") {
      bytes = new TextEncoder().encode(str);
    } else if (str && typeof str.utf8 === "function") {
      bytes = str.utf8();
    } else {
      throw new TypeError("MutStr.put: expected rt$$Str or string, got " + typeof str);
    }
    // Resize buffer if needed
    if (this._buffer.length - this._length < bytes.length) {
      const minSizeIncrease = Math.floor(this._buffer.length * 1.5) + 1;
      const newSize = Math.max(minSizeIncrease, this._buffer.length + bytes.length);
      const newBuffer = new Uint8Array(newSize);
      newBuffer.set(this._buffer.slice(0, this._length));
      this._buffer = newBuffer;
    }
    this._buffer.set(bytes, this._length);
    this._length += bytes.length;
  }

}
