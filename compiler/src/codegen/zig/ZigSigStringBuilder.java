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
    return sig.mdf().toString() + " " + sig.name().name() + "/" + sig.xs().size();
  }

  /**
   * Compute the FNV-1a 64-bit hash of a signature string.
   * Same algorithm as hash_signature in FeaRT's objs.zig.
   */
  public long sigHash(MIR.Sig sig) {
    return fnv1a(sigString(sig));
  }

  /**
   * Compute FNV-1a hash directly from a string.
   */
  public static long fnv1a(String str) {
    long hash = -3750763034362895579L; // 14695981039346656037 as signed
    long prime = 1099511628211L;
    for (int i = 0; i < str.length(); i++) {
      hash ^= (long) str.charAt(i);
      hash *= prime;
    }
    return hash;
  }

  /**
   * Generate a hash expression for a signature, without comptime prefix.
   * Use in comptime contexts (VTable const decls).
   * e.g. rt.hash_signature("mut #/1")
   */
  public String hashExpr(MIR.Sig sig) {
    return "rt.hash_signature(\"" + escapeZigString(sigString(sig)) + "\")";
  }

  /**
   * Generate an inline comptime hash expression for a signature.
   * Use in runtime contexts (function bodies).
   * e.g. comptime rt.hash_signature("mut #/1")
   */
  public String inlineHash(MIR.Sig sig) {
    return "comptime " + hashExpr(sig);
  }

  private static String escapeZigString(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
