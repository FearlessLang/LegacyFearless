package codegen.zig;

import codegen.MIR;

/**
 * Builds the signature strings that get hashed by FNV-1a at comptime in Zig.
 * Must produce the same strings as the hand-written examples in FeaRT's main.zig,
 * e.g. "mut #/1", "read .speak/0".
 */
public final class ZigSigStringBuilder {
  private final ast.Program p;

  public ZigSigStringBuilder(ast.Program p) {
    this.p = p;
  }

  /**
   * Build the signature string for a method.
   * Format: "<mdf> <name>/<arity>"
   */
  public String sigString(MIR.Sig sig) {
    var prettyStr = sig.mdf() + " " + sig.name().name() + "/" + sig.xs().size();
    return "\""+prettyStr.replace("\\", "\\\\").replace("\"", "\\\"")+"\"";
  }

  /**
   * Generate a hash expression for a signature.
   * Use in comptime contexts (VTable const decls).
   * e.g. rt.hash_signature("mut #/1")
   */
  public String hashExpr(MIR.Sig sig) {
    return "rt.hash_signature(" + sigString(sig) + ")";
  }

  /**
   * Generate an inline comptime hash expression for a signature.
   * Use in runtime contexts (function bodies).
   * e.g. comptime rt.hash_signature("mut #/1")
   */
  public String inlineHash(MIR.Sig sig) {
    return "comptime " + hashExpr(sig);
  }
}
