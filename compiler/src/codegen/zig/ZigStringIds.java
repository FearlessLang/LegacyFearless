package codegen.zig;

import id.Id;
import id.Mdf;
import magic.LiteralKind;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ZigStringIds {
  public Optional<String> getLiteral(ast.Program p, Id.DecId d) {
    return p.superDecIds(d).stream()
      .map(Id.DecId::name)
      .filter(LiteralKind::isLiteral)
      .findFirst();
  }

  public String getFullName(Id.DecId d) {
    return manglePkg(d.pkg()) + "_D" + getSimpleName(d);
  }

  public String getSimpleName(Id.DecId d) {
    return mangleBase(d.shortName()) + "_" + d.gen();
  }

  public String getMName(Mdf mdf, Id.MethName m) {
    return mangleBase(m.name()) + "_" + m.num() + "_" + mdf;
  }

  public String getFName(codegen.MIR.FName name) {
    return getSimpleName(name.d()) + "_" + getMName(name.mdf(), name.m()) + "_Zfun";
  }

  public String varName(String name) {
    var mangled = mangleBase(name) + "_m";
    if (ZIG_KEYWORDS.contains(mangled)) {
      return "_K" + mangled;
    }
    return mangled;
  }

  private String manglePkg(String pkg) {
    // Package names like "base.caps" -> "base_Dcaps"
    return pkg.replace(".", "_D");
  }

  private String mangleBase(String name) {
    if (name.isEmpty()) return "_Zempty";
    var sb = new StringBuilder();
    // If starts with '.', strip it and prefix with _Zdot
    var n = name;
    if (n.startsWith(".")) {
      sb.append("_Zdot");
      n = n.substring(1);
    }
    for (int i = 0; i < n.length(); ) {
      int cp = n.codePointAt(i);
      if (isAlphabetic(cp) || isDigit(cp)) {
        sb.appendCodePoint(cp);
      } else if (cp == '_') {
        sb.append('_');
      } else {
        var escaped = ESCAPE.get(cp);
        if (escaped != null) {
          sb.append(escaped);
        } else {
          sb.append("_U").append(Integer.toHexString(cp));
        }
      }
      i += Character.charCount(cp);
    }
    var result = sb.toString();
    // Ensure doesn't start with a digit
    if (!result.isEmpty() && Character.isDigit(result.charAt(0))) {
      result = "_N" + result;
    }
    if (ZIG_KEYWORDS.contains(result)) {
      result = "_K" + result;
    }
    return result;
  }

  private boolean isAlphabetic(int cp) {
    return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
  }

  private boolean isDigit(int cp) {
    return cp >= '0' && cp <= '9';
  }

  private static final Map<Integer, String> ESCAPE = Map.ofEntries(
    Map.entry((int)'#', "_Zhash"),
    Map.entry((int)'+', "_Zplus"),
    Map.entry((int)'-', "_Zminus"),
    Map.entry((int)'*', "_Zstar"),
    Map.entry((int)'/', "_Zslash"),
    Map.entry((int)'\\', "_Zbslash"),
    Map.entry((int)'|', "_Zpipe"),
    Map.entry((int)'!', "_Zbang"),
    Map.entry((int)'@', "_Zat"),
    Map.entry((int)'$', "_Zdollar"),
    Map.entry((int)'%', "_Zpercent"),
    Map.entry((int)'^', "_Zcaret"),
    Map.entry((int)'&', "_Zamp"),
    Map.entry((int)'?', "_Zquest"),
    Map.entry((int)'~', "_Ztilde"),
    Map.entry((int)'<', "_Zlt"),
    Map.entry((int)'>', "_Zgt"),
    Map.entry((int)'=', "_Zeq"),
    Map.entry((int)':', "_Zcolon"),
    Map.entry((int)'\'', "_Zapos"),
    Map.entry((int)'.', "_Zdot")
  );

  private static final Set<String> ZIG_KEYWORDS = Set.of(
    "addrspace", "align", "allowzero", "and", "anyerror", "anyframe",
    "anyopaque", "anytype", "asm", "async", "await",
    "break", "callconv", "catch", "comptime", "const", "continue",
    "defer", "else", "enum", "errdefer", "error", "export", "extern",
    "false", "fn", "for", "if", "inline",
    "linksection", "noalias", "nosuspend", "null",
    "opaque", "or", "orelse",
    "packed", "pub", "resume", "return",
    "struct", "suspend", "switch",
    "test", "threadlocal", "true", "try", "type",
    "undefined", "union", "unreachable", "usingnamespace",
    "var", "void", "volatile", "while"
  );
}
