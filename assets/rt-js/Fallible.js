import { base$$Action_1 } from "../base/Action_1.js";

export class Fallible {
  constructor(runFn) {
    // store the function that will act as run$mut$1
    this.run$mut$1 = runFn;
  }

  $exclamation$mut$0() {
    return base$$Action_1.$exclamation$mut$1$fun(this);
  }

  mapInfo$mut$1(f_m$) {
    return base$$Action_1.mapInfo$mut$2$fun(f_m$, this);
  }

  map$mut$1(f_m$) {
    return base$$Action_1.map$mut$2$fun(f_m$, this);
  }

  andThen$mut$1(f_m$) {
    return base$$Action_1.andThen$mut$2$fun(f_m$, this);
  }

  ok$mut$0() {
    return base$$Action_1.ok$mut$1$fun(this);
  }

  info$mut$0() {
    return base$$Action_1.info$mut$1$fun(this);
  }
}
