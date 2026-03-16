import { rt$$Str } from "./Str.js";
import { FearlessError } from "./FearlessError.js";
import { base$$Void_0 } from "../base/Void_0.js";
import { ListImpl, ByteBufferListImpl } from "./ListK.js";
import { Fallible } from "./Fallible.js";

// Detect environment: Node vs browser
const hasNodeBuffer =
  typeof globalThis !== "undefined" &&
  typeof globalThis.Buffer !== "undefined" &&
  typeof globalThis.Buffer.from === "function";

function makeBuffer(arr) {
  if (hasNodeBuffer) {
    return Buffer.from(arr);
  } else {
    return new Uint8Array(arr);
  }
}

export class rt$$UTF8 {
  static $self = new rt$$UTF8();

  fromBytes$imm$1(utf8Bytes_m$) {
    if (utf8Bytes_m$ instanceof ByteBufferListImpl) {
      return this.utf8ToStr(utf8Bytes_m$.inner.slice());
    } else if (utf8Bytes_m$ instanceof ListImpl) {
      return this.utf8ToStr(this.rawListToBuffer(utf8Bytes_m$.inner));
    } else {
      return this.utf8ToStr(this.listToBuffer(utf8Bytes_m$));
    }
  }

  // Define the custom error here
  static StringEncodingError = class extends FearlessError {
    constructor(message) {
      super(message); // store message or info
      this.name = "StringEncodingError";
    }
  };

  // your existing utf8ToStr
  utf8ToStr(utf8) {
    return new Fallible((res) => {
      try {
        return res.ok$mut$1(rt$$Str.fromUtf8(utf8));
      } catch (e) {
        if (e instanceof DOMException) {
          return res.info$mut$1(
            new rt$$UTF8.StringEncodingError("Invalid UTF-8 byte sequence")
          );
        }
        throw e;
      }
    });
  }

  rawListToBuffer(arr) {
    // Normalize signed bytes -> [0..255]
    return makeBuffer(arr.map((b) => (Number(b) & 0xff)));
  }

  listToBuffer(list_1) {
    const size = list_1.size$read$0().intValue();
    const arr = new Array(size);
    let i = 0;
    list_1.iter$mut$0().for$mut$1((b) => {
      arr[i++] = b & 0xff;
      return base$$Void_0.$self;
    });
    return makeBuffer(arr);
  }
}
