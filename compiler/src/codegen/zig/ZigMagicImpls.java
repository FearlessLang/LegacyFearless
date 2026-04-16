package codegen.zig;

import codegen.MIR;
import failure.Fail;
import id.Id;
import magic.FearlessStringHandler;
import magic.MagicTrait;
import visitors.MIRVisitor;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static magic.Magic.getLiteral;

public record ZigMagicImpls(
    MIRVisitor<String> gen,
    Function<MIR.MT, String> getTName,
    ast.Program p) implements magic.MagicImpls<String> {

  private static final MagicTrait<MIR.E, String> EMPTY = new MagicTrait<>() {
    @Override public Optional<String> instantiate() { return Optional.empty(); }
    @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
      return Optional.empty();
    }
  };

  @Override public MagicTrait<MIR.E, String> nat(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return () -> {
      var lit = getLiteral(p, name);
      try {
        return lit
          .map(lambdaName -> "nat_rt.make(" + Long.parseUnsignedLong(lambdaName.replace("_", ""), 10) + ")")
          .orElseGet(() -> e.accept(gen, true)).describeConstable();
      } catch (NumberFormatException ignored) {
        throw Fail.invalidNum(lit.orElse(name.toString()), "Nat");
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> int_(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return () -> {
      var lit = getLiteral(p, name);
      try {
        return lit
          .map(lambdaName -> lambdaName.startsWith("+") ? lambdaName.substring(1) : lambdaName)
          .map(lambdaName -> "int_rt.make(" + Long.parseLong(lambdaName.replace("_", ""), 10) + ")")
          .orElseGet(() -> e.accept(gen, true)).describeConstable();
      } catch (NumberFormatException ignored) {
        throw Fail.invalidNum(lit.orElse(name.toString()), "Int");
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> str(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return () -> {
      var lit = getLiteral(p, name);
      if (lit.isPresent()) {
        var decoded = new FearlessStringHandler(FearlessStringHandler.StringKind.Unicode)
          .toJavaString(lit.get()).get();
        return Optional.of("str_rt.make_str_from_literal(\"" + escapeZigStr(decoded) + "\")");
      }
      return e.accept(gen, true).describeConstable();
    };
  }

  @Override public MagicTrait<MIR.E, String> vars(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&var_rt.VT_Vars)");
  }

  @Override public MagicTrait<MIR.E, String> listK(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&list_rt.VT_ListFactory)");
  }

  @Override public MagicTrait<MIR.E, String> uListK(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&list_rt.VT_UListFactory)");
  }

  @Override public MagicTrait<MIR.E, String> float_(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> byte_(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> asciiStr(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> debug(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> refK(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> isoPodK(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> assert_(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> cheapHash(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> regexK(MIR.E e) { return EMPTY; }

  private static String escapeZigStr(String s) {
    var sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\x%02x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
