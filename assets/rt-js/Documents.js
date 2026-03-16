import { rt$$Document } from "./Document.js";

/**
 * Runtime singleton for `Documents`, which creates new Document instances.
 */
export class rt$$Documents {
  static $self = new rt$$Documents();

  /** read .create(): mut Document -> Magic! */
  $hash$imm$1() {
    // In the browser, just return the global `document` wrapper.
    return rt$$Document.$self;
  }
}
