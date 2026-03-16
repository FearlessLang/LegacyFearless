import { base$$True_0 } from "../base/True_0.js";
import { base$$False_0 } from "../base/False_0.js";
import { base$$Opt_1 } from "../base/Opt_1.js";
import { base$$Opts_0 } from "../base/Opts_0.js";
import { base$$Void_0 } from "../base/Void_0.js";
import { base$$List_1 } from "../base/List_1.js";

export const rt$$ListK = {
  $self: null, // will set below

  asShallowClone(list, f) {
    if (list instanceof ListImpl) {
      return new ListImpl([...list.inner]);
    }
    if (list instanceof ByteBufferListImpl) {
      return list;
    }
    return list.as$read$1(f);
  },

  // will inject $hash$imm$N below

  fromLList$imm$1(llist) {
    const arr = [];
    for (const e of llist.iter$mut$0()) arr.push(e);
    return new ListImpl(arr);
  },

  withCapacity$imm$1(n) {
    if (n > Number.MAX_SAFE_INTEGER) {
      throw new Error(`Lists may not have a capacity greater than ${Number.MAX_SAFE_INTEGER}`);
    }
    return new ListImpl(new Array(n));
  }
};

// dynamically generate $hash$imm$0 ... $hash$imm$16
for (let n = 0; n <= 16; n++) {
  rt$$ListK[`$hash$imm$${n}`] = (...elements) => {
    return new ListImpl(elements);
  };
}

rt$$ListK.$self = rt$$ListK;

// --------------------
// ListImpl
// --------------------
export class ListImpl {
  constructor(inner = []) {
    this.inner = inner;
  }

  get$imm$1(i) { return this.inner[i]; }
  get$read$1(i) { return this.inner[i]; }
  get$mut$1(i) { return this.inner[i]; }

  add$mut$1(e) { this.inner.push(e); return base$$Void_0.$self; }
  $plus$mut$1(e) { this.inner.push(e); return this; }

  addAll$mut(other) {
    this.inner.push(...other.inner);
    return base$$Void_0.$self;
  }

  takeFirst$mut$0() {
    if (this.inner.length === 0) return base$$Opt_1.$self;
    return base$$Opts_0.$self.$hash$imm$1(this.inner.shift());
  }

  tryGet$imm$1(i) {
    return i >= this.inner.length ? base$$Opt_1.$self : base$$Opts_0.$self.$hash$imm$1(this.inner[i]);
  }
  tryGet$read$1(i) { return this.tryGet$imm$1(i); }
  tryGet$mut$1(i) { return this.tryGet$imm$1(i); }

  iter$imm$0() { return base$$List_1.iter$imm$1$fun(this); }
  iter$read$0() { return base$$List_1.iter$read$1$fun(this); }
  iter$mut$0() { return base$$List_1.iter$mut$1$fun(this); }

  flow$imm$0() { return base$$List_1.flow$imm$1$fun(this); }
  flow$read$0() { return base$$List_1.flow$read$1$fun(this); }
  flow$mut$0() { return base$$List_1.flow$mut$1$fun(this); }

  isEmpty$read$0() { return this.inner.length === 0 ? base$$True_0.$self : base$$False_0.$self; }
  clear$mut$0() { this.inner.length = 0; return base$$Void_0.$self; }

  subList$read$2$2(from, to) { return base$$List_1.subList$read$3$fun(from, to, this); }
  as$read$1(f) { return base$$List_1.as$read$2$fun(f, this); }

  size$read$0() { return BigInt(this.inner.length); }

  _flowimm$imm$2(start, end) { return base$$List_1._flowimm$imm$3$fun(start, end, this); }
  _flowread$read$2(start, end) { return base$$List_1._flowread$read$3$fun(start, end, this); }
}

// --------------------
// ByteBufferListImpl
// --------------------
export class ByteBufferListImpl {
  constructor(inner) {
    this.inner = inner; // assume some JS typed array or similar
  }

  get$imm$1(i) { return this.inner[i]; }
  get$read$1(i) { return this.inner[i]; }
  get$mut$0() { throw new Error("Unreachable code"); }

  tryGet$imm$1(i) { return i >= this.inner.length ? base$$Opt_1.$self : base$$Opts_0.$self.$hash$imm$1(this.inner[i]); }
  tryGet$read$1(i) { return this.tryGet$imm$1(i); }
  tryGet$mut$0() { throw new Error("Unreachable code"); }

  iter$imm$0() { return base$$List_1.iter$imm$1$fun(this); }
  iter$read$0() { return base$$List_1.iter$read$1$fun(this); }
  iter$mut$0() { throw new Error("Unreachable code"); }

  flow$imm$0() { return base$$List_1.flow$imm$1$fun(this); }
  flow$read$0() { return base$$List_1.flow$read$1$fun(this); }
  flow$mut$0() { throw new Error("Unreachable code"); }

  isEmpty$read$0() { return this.inner.length === 0 ? base$$True_0.$self : base$$False_0.$self; }
  clear$mut$0() { throw new Error("Unreachable code"); }

  subList$read$2(from, to) { return base$$List_1.subList$read$3$fun(from, to, this); }
  as$read$1(f) { return base$$List_1.as$read$2$fun(f, this); }

  size$read$0() { return BigInt(this.inner.length); }

  add$mut$0() { throw new Error("Unreachable code"); }
  $plus$mut$0() { throw new Error("Unreachable code"); }

  _flowimm$imm$2(start, end) { return base$$List_1._flowimm$imm$3$fun(start, end, this); }
  _flowread$read$2(start, end) { return base$$List_1._flowread$read$3$fun(start, end, this); }
}
