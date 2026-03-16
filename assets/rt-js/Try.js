import { rt$$Str } from "./Str.js";
import { base$$Infos_0 } from "../base/Infos_0.js";

/**
 * JS equivalent of rt.Try
 */
export class rt$$Try {
  static $self = new rt$$Try();

  $hash$imm$1(try_m$) {
    const val = {
      run$mut$1(res_m$) {
        try {
          return res_m$.ok$mut$1(try_m$.$hash$read$0());
        } catch (err) {
          if (err.isFearlessError) {
            return res_m$.info$mut$1(err.info);
          } else {
            const msg = err.message || "Unknown error";
            return res_m$.info$mut$1(
              base$$Infos_0.$self.msg$imm$1(rt$$Str.fromJsStr(msg))
            );
          }
        }
      }
    };
    return val;
  }


  $hash$imm$2(data, try$) {
    return (res) => {
      try {
        return res.ok$mut$1(try$.$hash$read$1(data));
      } catch (err) {
        if (err instanceof Error && err.isFearlessError) {
          return res.info$mut$1(err.info);
        } else {
          const msg = err.message || "Unknown error";
          return res.info$mut$1(base$$Infos_0.$self.msg$imm$1(rt$$Str.fromJsStr(msg)));
        }
      }
    };
  }
}
