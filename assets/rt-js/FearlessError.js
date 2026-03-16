import { toStringFromUtf8 } from "./BaseStr.js";


function extractMessage(info) {
  const u8 = info.msg_m$._utf8;
  return toStringFromUtf8(u8);
}

export class FearlessError extends Error {
  constructor(info) {
    const msg = extractMessage(info);
    super(msg);                 // Initialize Error’s internal message
    this.name = "FearlessError";
    this.info = info;
  }

  getMessage() {
    return this.message;
  }

  toString() {
    return this.getMessage();
  }

  // static throwFearlessError(info) {
  //   throw new FearlessError(info);
  // }
}
