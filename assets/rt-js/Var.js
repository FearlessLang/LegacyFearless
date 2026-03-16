export class rt$$Var {
  constructor(value) {
    this.value = value;
  }
  get$read$0() {
    return this.value;
  }
  get$mut$0() {
    return this.value;
  }
  swap$mut$1(newValue) {
    const old = this.value;
    this.value = newValue;
    return old;
  }
}
