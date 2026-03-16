package codegen.js;

import codegen.MIR;
import id.Id;
import magic.Magic;

public class TypeIds {
  private final JsMagicImpls magic;
  private final StringIds id;

  public TypeIds(JsMagicImpls magic, StringIds id) {
    this.magic = magic;
    this.id = id;
  }

  public String getTName(MIR.MT t, boolean isRet) {
    return switch (t) {
      case MIR.MT.Any ignored -> "Object"; // or "any"
      case MIR.MT.Plain plain -> auxGetTName(plain.id());
      case MIR.MT.Usual usual -> auxGetTName(usual.it().name());
    };
  }

  private String auxGetTName(Id.DecId name) {
    return switch (name.name()) {
      case "base.Int", "base.Nat" -> "number"; // JS number
      case "base.Float" -> "number";          // JS number covers floats
      case "base.Byte" -> "number";           // bytes usually numbers
      case "base.Str" -> "string";             // JS string
//      case "base.Void" -> "void";              // void for no return value
      default -> magicName(name);
    };
  }

  private String magicName(Id.DecId name) {
    if (magic.isMagic(Magic.Int, name)) return "number";
    if (magic.isMagic(Magic.Nat, name)) return "number";
    if (magic.isMagic(Magic.Float, name)) return "number";
    if (magic.isMagic(Magic.Byte, name)) return "number";
    if (magic.isMagic(Magic.Str, name)) return "string";
    return id.getFullName(name); // fully qualified JS name, e.g., "test.Foo"
  }
}
