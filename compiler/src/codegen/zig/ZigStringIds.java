package codegen.zig;

import id.Id;
import id.Mdf;
import magic.LiteralKind;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// Maps Fearless names to Zig identifiers.
///
/// ## Encoding
///
/// `mangleBase` writes a name one character at a time:
///
/// - An ASCII letter or digit stays as it is.
/// - A `_` stays as `_`. If the next character is `Z`, `U`, `N`, `K` or `D`, the `_` is written
///   `_Zu`.
/// - A character in `ESCAPE` is written as its `_Z<word>` escape. For example, `'` is `_Zapos`.
/// - Any other character is written `_U` and exactly 6 lowercase hex digits of its code point.
/// - A leading `.` is written `_Zdot`. An empty name is written `_Zempty`.
/// - A result that starts with a digit gets the prefix `_N`.
/// - A result that is a Zig keyword gets the prefix `_K`.
///
/// `manglePkg` writes a package name. It writes each `.` as `_` and each `_` as `_Zu`.
///
/// The generator builds these shapes from the encoded parts:
///
/// - A type is `<base>_<gen>`.
/// - A method is `<base>_<num>_<mdf>`.
/// - A function is `<type>_<method>_Zfun`.
/// - A variable is `<base>_m`.
/// - A type with its package is `<package>_D<type>`.
///
/// ## Why two different Fearless names cannot give the same Zig name
///
/// The text `_Z`, `_U`, `_N`, `_K` or `_D` can start only at a place where the generator wrote an
/// escape, a prefix or a separator. A source `_` before `Z`, `U`, `N`, `K` or `D` is written `_Zu`,
/// so a source name cannot give one of these texts by itself. Therefore a reader can decode one
/// encoded name from left to right and never has a choice:
///
/// - The `_Z<word>` escapes, including `_Zu`, are prefix-free, and a `_U` escape has a fixed width.
/// - A `_` that no escape or prefix starts is a source `_`.
/// - An encoded package never holds `_D`. A source `_` is written `_Zu`, and a `.` is written `_`
///   before a package part, which starts with `_` or a lowercase letter. Hence the first `_D` in a
///   type with its package ends the package.
///
/// A shape with several parts is read from the right. The generation, the method number, the
/// modifier, `_Zfun` and `_m` are digits or words from a closed set, so they have one position.
/// The grammar fixes where an escape can follow in each kind of name:
///
/// - A method base starts with an escape. `_Zdot` is followed by a lowercase letter or `_`.
///   The base of an operator holds only escapes.
/// - A type has an escape only before an uppercase letter, a digit, another escape or the end of
///   the base, or inside a string literal. A string literal ends with its escaped closing quote.
///
/// So the type, the generation and the method in a function name have one split.
///
/// No name that the generator writes for itself ends in `_m`, so it cannot equal a variable name.
public final class ZigStringIds {
  public Optional<String> getLiteral(ast.Program p, Id.DecId d) {
    return p.superDecIds(d).stream()
      .map(Id.DecId::name)
      .filter(LiteralKind::isLiteral)
      .findFirst();
  }

  /// The name of a type with its package: the encoded package, then `_D`, then the simple name.
  /// An encoded package never holds `_D`, so the first `_D` ends the package.
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

  /// Encodes a package name for a Zig identifier or a Zig file name.
  /// Each `.` is written `_` and each `_` is written `_Zu`.
  /// For example, `base.caps` is `base_caps` and `my_pkg.a` is `my_Zupkg_a`.
  public static String manglePkg(String pkg) {
    return pkg.replace("_", "_Zu").replace(".", "_");
  }

  /// Writes text as a Zig string literal, with the quotes.
  /// The literal has the same value as the text, whatever characters the text holds.
  ///
  /// - `\` and `"` are written with a leading `\`.
  /// - A newline, a carriage return and a tab are written `\n`, `\r` and `\t`.
  /// - Any other character below U+0020, and U+007F, is written `\x` and 2 lowercase hex digits.
  ///   A Zig string literal cannot hold these characters as raw bytes.
  /// - All other characters stay as they are. The generated files are UTF-8.
  public static String zigString(String text) {
    var sb = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            sb.append("\\x").append("%02x".formatted((int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
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
        var hasNext = i + 1 < n.length();
        var reserved = hasNext && RESERVED_AFTER_UNDERSCORE.indexOf(n.charAt(i + 1)) >= 0;
        sb.append(reserved ? "_Zu" : "_");
      } else {
        var escaped = ESCAPE.get(cp);
        if (escaped != null) {
          sb.append(escaped);
        } else {
          sb.append("_U").append("%06x".formatted(cp));
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

  /// The characters that start an escape, a prefix or a separator when they follow a `_`.
  private static final String RESERVED_AFTER_UNDERSCORE = "ZUNKD";

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
