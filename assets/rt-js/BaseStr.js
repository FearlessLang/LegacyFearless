const _textDecoder = typeof TextDecoder !== "undefined" ? new TextDecoder("utf-8") : null;
const _textEncoder = typeof TextEncoder !== "undefined" ? new TextEncoder() : null;

// export function validateStringOrThrow(u8) {
//   const bytes = _toUint8Array(u8);
//   if (_textDecoder) {
//     try {
//       const dec = new TextDecoder("utf-8", { fatal: true });
//       dec.decode(bytes);
//     } catch {
//       throw new Error("Invalid UTF-8");
//     }
//   }
// }

export function _toUint8Array(bufOrStr) {
  if (!bufOrStr) return new Uint8Array(0);
  if (bufOrStr instanceof Uint8Array) return bufOrStr;
  if (bufOrStr instanceof ArrayBuffer) return new Uint8Array(bufOrStr);
  if (typeof bufOrStr === "string") return _textEncoder.encode(bufOrStr);
  if (typeof bufOrStr.utf8 === "function") return _toUint8Array(bufOrStr.utf8());
  throw new TypeError("Expected Uint8Array | ArrayBuffer | string | Str-like object");
}

export function toStringFromUtf8(u8) {
  if (!u8) return "";
  if (!(u8 instanceof Uint8Array)) u8 = _toUint8Array(u8);
  if (!_textDecoder) return Array.from(u8).map(b => String.fromCharCode(b)).join("");
  return _textDecoder.decode(u8);
}

export class BaseStr {
  /** Convert to native JS string */
  toJsString() {
    return toStringFromUtf8(this.utf8());
  }
}