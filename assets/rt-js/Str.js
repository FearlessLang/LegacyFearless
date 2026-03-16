import { base$$True_0 } from "../base/True_0.js";
import { base$$False_0 } from "../base/False_0.js";
import { rt$$NativeRuntime } from "./NativeRuntime.js";
import { FearlessError } from "./FearlessError.js";
import { ByteBufferListImpl } from "./ListK.js";
import { rt$$MutStr } from "./MutStr.js";
import { BaseStr } from "./BaseStr.js";

export class rt$$Str extends BaseStr{
  utf8$imm$0() {
    return new ByteBufferListImpl(this.utf8());
  }
  str$read$0() { return this; }

  static wrap(array) {
    // Accepts a JS Array or TypedArray of numbers (0–255)
    return new Uint8Array(array);
  }

  $equals$equals$imm$1(other) {
    const a = this.utf8(), b = other.utf8();
    if (a.length !== b.length) return base$$False_0.$self;
    for (let i = 0; i < a.length; i++) {
      if (a[i] !== b[i]) {
        return base$$False_0.$self;
      }
    }
    return base$$True_0.$self;
  }

  $exclamation$equals$imm$1(other) {
    return this.$equals$equals$imm$1(other) === base$$True_0.$self ? base$$False_0.$self : base$$True_0.$self;
  }

  startsWith$imm$1(other) {
    const a = this.utf8(), b = other.utf8();
    if (b.length > a.length) return base$$False_0.$self;
    for (let i = 0; i < b.length; i++) {
      if (a[i] !== b[i]) {
        return base$$False_0.$self;
      }
    }
    return base$$True_0.$self;
  }

  $plus$imm$1(other) {
    const a = this.utf8(), b = other.str$read$0().utf8();
    const res = new Uint8Array(a.length + b.length);
    res.set(a);
    res.set(b, a.length);
    return rt$$Str.fromTrustedUtf8(res);
  }

  join$imm$1(flow_m$) {
    // MF_1 acc_m$, F_3 f_m$
    return flow_m$.fold$mut$2(
      { $hash$mut$0: () => new rt$$MutStr() },
      { $hash$read$2: (acc, str) => acc.isEmpty$read$0() === base$$True_0.$self
          ? acc.$plus$mut$1(str)
          : acc.$plus$mut$1(this).$plus$mut$1(str) }
    ).str$read$0(); // return a proper rt$$Str
  }

  size$imm$0() { return BigInt(this.graphemes().length); }
  isEmpty$read$0() { return this.utf8().length === 0 ? base$$True_0.$self : base$$False_0.$self; }

  substring$imm$2(start, end) {
    if (start > end) throw new FearlessError(rt$$Str.fromJsStr("Start index must be <= end"));
    if (start < 0) throw new FearlessError(rt$$Str.fromJsStr("Start index >= 0"));
    if (end > this.size$imm$0()) throw new FearlessError(rt$$Str.fromJsStr("End index <= size"));
    return new rt$$Str.SubStr(this, start, end);
  }

  charAt$imm$1(index) { return this.substring$imm$2(index, index + 1); }

  normalise$imm$0() {
    const utf8 = rt$$NativeRuntime.normaliseString(this.utf8());
    return rt$$Str.fromTrustedUtf8(utf8);
  }

  // --- Static helpers ---
  static fromJsStr(str) {
    const encoder = new TextEncoder();
    return rt$$Str.fromTrustedUtf8(encoder.encode(str));
  }

  static numToStr(x) {
    return rt$$Str.fromJsStr(String(x));
  }

  static fromUtf8(utf8) {
    rt$$NativeRuntime.validateStringOrThrow(utf8);
    return rt$$Str.fromTrustedUtf8(utf8);
  }

  static fromTrustedUtf8(utf8) {
    return new class extends rt$$Str {
      constructor() {
        super();
        this._utf8 = utf8;
        this._graphemes = null;
      }
      utf8() { return this._utf8; }
      graphemes() {
        if (!this._graphemes) this._graphemes = rt$$NativeRuntime.indexString(this._utf8);
        return this._graphemes;
      }
    };
  }

  /** Substring implementation */
  static SubStr = class extends rt$$Str {
    constructor(parent, start, end) {
      super();
      const graphemes = parent.graphemes();
      const utf8 = parent.utf8();
      const idxStart = start >= graphemes.length ? utf8.length : graphemes[start];
      const idxEnd = end >= graphemes.length ? utf8.length : graphemes[end];
      this._utf8 = utf8.slice(idxStart, idxEnd);
      this._size = end - start;
      this._graphemes = null;
    }
    utf8() { return this._utf8; }
    graphemes() {
      if (!this._graphemes) this._graphemes = rt$$NativeRuntime.indexString(this._utf8);
      return this._graphemes;
    }
    size$imm$0() { return BigInt(this._size); }
    isEmpty$read$0() { return this._size === 0 ? base$$True_0.$self : base$$False_0.$self; }
  };
}

function wrapFnMut$0(fn) {
  return { $hash$mut$0: fn, $hash$read$0: fn, $hash$imm$0: fn };
}
function wrapFnMut$2(fn) {
  return { $hash$mut$2: fn, $hash$read$2: fn, $hash$imm$2: fn };
}
