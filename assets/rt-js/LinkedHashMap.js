import { base$$True_0 } from "../base/True_0.js";
import { base$$False_0 } from "../base/False_0.js";
import { base$$Opt_1 } from "../base/Opt_1.js";
import { base$$Opts_0 } from "../base/Opts_0.js";
import { base$$Void_0 } from "../base/Void_0.js";
import { base$$flows$$Flow_0 } from "../base/flows/Flow_0.js";
import { SpliteratorFlowOp } from "./flows/SpliteratorFlowOp.js";
import { rt$$CheapHash } from "./CheapHash.js";

export class rt$$LinkedHashMap {
  constructor(keyEq, hashFn) {
    this.hashFn = hashFn;
    this.keyEq = keyEq;
    this.inner = new Map();
  }

  keyOf(k) {
    return new Key(k, this.hashFn, (k1, k2) => this.keyEq$read$2(k1, k2));
  }

  get$read$1(k) {
    const res = this.inner.get(this.keyOf(k).hashCode());
    return res !== undefined ? base$$Opts_0.$self.$hash$imm$1(res) : base$$Opt_1.$self;
  }
  get$imm$1(k) { return this.get$read$1(k); }
  get$mut$1(k) { return this.get$read$1(k); }

  $plus$mut$2(k, v) {
    this.inner.set(this.keyOf(k).hashCode(), v);
    return this;
  }

  remove$mut$1(k) {
    const key = this.keyOf(k).hashCode();
    const res = this.inner.get(key);
    this.inner.delete(key);
    return res !== undefined ? base$$Opts_0.$self.$hash$imm$1(res) : base$$Opt_1.$self;
  }

  clear$mut$0() {
    this.inner.clear();
    return base$$Void_0.$self;
  }

  keyEq$read$2(k1, k2) {
    const res = this.keyEq.$hash$read$2(k1, k2);
    return res instanceof Bool_0 ? res : (res ? base$$True_0.$self : base$$False_0.$self);
  }

  isEmpty$read$0() {
    return this.inner.size === 0 ? base$$True_0.$self : base$$False_0.$self;
  }

  put$mut$2(k, v) {
    return this.$plus$mut$2(k, v);
  }

  keys$read$0() {
    const keys = Array.from(this.inner.keys());
    return base$$flows$$Flow_0.$self.fromOp$imm$2(SpliteratorFlowOp.of(keys), this.inner.size);
  }

  values$mut$0() {
    const values = Array.from(this.inner.values());
    return base$$flows$$Flow_0.$self.fromMutSource$imm$2(SpliteratorFlowOp.of(values), this.inner.size);
  }

  values$read$0() { return this.values$mut$0(); }
  values$imm$0() { return this.values$read$0(); }

  flowMut$mut$0() {
    return base$$flows$$Flow_0.$self.fromMutSource$imm$2(SpliteratorFlowOp.of(this.mapToEntries()), this.inner.size);
  }

  flow$read$0() {
    return base$$flows$$Flow_0.$self.fromOp$imm$2(SpliteratorFlowOp.of(this.mapToEntries()), this.inner.size);
  }

  flow$imm$0() { return this.flow$read$0(); }

  mapToEntries$0() {
    const entries = [];
    for (const [keyHash, value] of this.inner.entries()) {
      entries.push({
        key$read$0: () => keyHash, // Note: In JS we use hash as key
        value$read$0: () => value,
        value$mut$0: () => value
      });
    }
    return entries;
  }
}

// Key wrapper
class Key {
  constructor(k, hashFn, keyEqFn) {
    this.k = k;
    this.hashFn = hashFn;
    this.keyEqFn = keyEqFn;
    this._hash = null;
  }

  hashCode() {
    if (this._hash === null) {
      const ch = this.hashFn.$hash$read$1(new rt$$CheapHash());
      this._hash = Math.floor(ch.compute$mut$0());
    }
    return this._hash;
  }

  equals(other) {
    return this.keyEqFn(this.k, other.k) === base$$True_0.$self;
  }
}
