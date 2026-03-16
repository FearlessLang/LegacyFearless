import { base$$Opt_1 } from "../base/Opt_1.js";
import { base$$Opts_0 } from "../base/Opts_0.js";
import { rt$$Element } from "./Element.js";
import { base$$Void_0 } from "../base/Void_0.js";

export class rt$$Document {
  static $self = new rt$$Document();

  getElementById$mut$1(id_m$) {
    const id = id_m$.toJsString();
    const el = document.getElementById(id);
    if (el == null) {
      return base$$Opt_1.$self;
    }
    return base$$Opts_0.$self.$hash$imm$1(new rt$$Element(el));
  }

  createElement$mut$1(tag_m$) {
    const tag = tag_m$.toJsString();
    const el = document.createElement(tag);
    return new rt$$Element(el);
  }

  waitCompletion$mut$0() {
    // No-op in the browser
    return base$$Void_0.$self;
  }
}
