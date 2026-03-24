package codegen.zig;

import codegen.MIR;
import id.Id;
import magic.LiteralKind;

/**
 * Builds the signature strings that get hashed by FNV-1a at comptime in Zig.
 * Must produce the same strings as the hand-written examples in feart's main.zig,
 * e.g. "mut #(Int): Int", "read .speak(): Str".
 */
public final class ZigSigStringBuilder {
  private final ast.Program p;

  public ZigSigStringBuilder(ast.Program p) {
    this.p = p;
  }

  /**
   * Build the signature string for a method.
   * Format: "<mdf> <name>(<paramTypes>): <retType>"
   */
  public String sigString(MIR.Sig sig) {
    var sb = new StringBuilder();
    sb.append(sig.mdf().toString());
    sb.append(' ');
    sb.append(sig.name().name());
    sb.append('(');
    for (int i = 0; i < sig.xs().size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(typeShortName(sig.xs().get(i).t()));
    }
    sb.append("): ");
    sb.append(typeShortName(sig.rt()));
    return sb.toString();
  }

  /**
   * Compute the FNV-1a 64-bit hash of a signature string.
   * Same algorithm as hash_signature in feart's objs.zig.
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
   * Get a short type name for use in signature strings.
   * e.g. "base.Int/0" -> "Int", "animals.Dog/0" -> "Dog"
   */
  private String typeShortName(MIR.MT t) {
    return t.name()
      .map(id -> {
        // Literals like "base.natLit.10" should resolve to "Nat", not "10"
        return LiteralKind.nameToType(id.name())
          .map(it -> it.name().shortName())
          .orElse(id.shortName());
      })
      .orElse("Any");
  }

  /**
   * Generate a Zig hash constant name for a signature.
   * e.g. "H_mut__Zhash_1" for "mut #(Nat): Nat"
   */
  public String hashConstName(MIR.Sig sig, ZigStringIds ids) {
    // Include hash of sig string to disambiguate methods with same name/arity/mdf but different types
    long h = fnv1a(sigString(sig));
    return "H_" + ids.getMName(sig.mdf(), sig.name()) + "_" + Long.toHexString(h);
  }

  /**
   * Generate a Zig hash constant declaration.
   * e.g. const H_mut__Zhash_1 = rt.hash_signature("mut #(Nat): Nat");
   */
  public String hashConstDecl(MIR.Sig sig, ZigStringIds ids) {
    return "const " + hashConstName(sig, ids)
      + " = rt.hash_signature(\"" + escapeZigString(sigString(sig)) + "\");";
  }

  private static String escapeZigString(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
