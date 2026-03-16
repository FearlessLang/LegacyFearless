import { base$$True_0 } from "../base/True_0.js";
import { base$$False_0 } from "../base/False_0.js";

export class rt$$Num {
  // since IEEE-754 floating-point arithmetic cannot exactly represent many decimal fractions.
  // This ensures expressions like (0.1 + 0.2 == 0.3) evaluate as true within a small epsilon.
  static eqFloat(a, b) {
    if (Object.is(a, b)) return true;     // handles +0/-0 and infinities
    if (Number.isNaN(a) || Number.isNaN(b)) return false;
    const diff = Math.abs(a - b);
    const scale = Math.max(1, Math.abs(a), Math.abs(b));
    // ~8-ish ulps; tune if you like (2..16 are common choices)
    return diff <= Number.EPSILON * 8 * scale;
  }
  // integer sqrt for signed Int (BigInt)
  static intSqrt(x) {
    x = BigInt(x);
    if (x < 0n) throw new Error("sqrt of negative Int");
    if (x <= 1n) return x;

    let r = BigInt(Math.floor(Math.sqrt(Number(x))));
    while (true) {
      const q = x / r;
      const nr = (r + q) >> 1n;
      if (nr >= r) break;
      r = nr;
    }
    const rp1 = r + 1n;
    if (x / rp1 >= rp1) r = rp1;
    return r;
  }

  // sqrt for Nat (unsigned)
  static natSqrt(u) {
    u = BigInt.asUintN(64, u);
    if (u === 0n || u === 1n) return u;

    let r = BigInt(Math.floor(Math.sqrt(Number(u))));
    while (true) {
      const q = u / r;
      const nr = (r + q) >> 1n;
      if (nr >= r) break;
      r = nr;
    }
    const rp1 = r + 1n;
    if (u / rp1 >= rp1) r = rp1;
    return r;
  }

  // sqrt for Byte
  static byteSqrt(raw) {
    const x = Number(raw) & 0xFF; // unsigned byte
    const r = Math.floor(Math.sqrt(x));
    return r & 0xFF;
  }

  // convert JS boolean to Fearless Bool object
  static toBool(b) {
    return b ? base$$True_0.$self : base$$False_0.$self;
  }

  // Overflow/Underflow:  just like clocks, numbers silently wrap
  static toInt64(x) {
    const mask64 = (1n << 64n) - 1n;
    x &= mask64;
    if (x >= (1n << 63n)) x -= (1n << 64n);
    return x;
  }

  static toNat64(x) {
    const mask64 = (1n << 64n) - 1n;
    return x & mask64;
  }
  static toByte8(x)  { return Number(x) & 0xFF; }
}
