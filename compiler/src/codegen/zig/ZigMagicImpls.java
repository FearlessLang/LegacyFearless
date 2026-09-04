package codegen.zig;

import codegen.MIR;
import failure.Fail;
import id.Id;
import id.Mdf;
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
    ast.Program p,
    java.util.function.BiFunction<String, MIR.MT, String> generateShare) implements magic.MagicImpls<String> {

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
          // `nat_rt.make` takes a u64. The top half of the Nat range comes back from
          // parseUnsignedLong as a negative long, which Zig rejects rather than wrapping.
          .map(lambdaName -> "nat_rt.make(" + Long.toUnsignedString(Long.parseUnsignedLong(lambdaName.replace("_", ""), 10)) + ")")
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
        var ctor = e.t().mdf().isMut() ? "make_mut_str_from_literal" : "make_str_from_literal";
        return Optional.of("str_rt." + ctor + "(\"" + escapeZigStr(decoded) + "\")");
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

  @Override public MagicTrait<MIR.E, String> float_(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return () -> {
      var lit = getLiteral(p, name);
      try {
        return lit
          .map(lambdaName -> Double.parseDouble(lambdaName.replace("_", "")))
          // The bit pattern, so -0.0, subnormals, NaN and infinities all survive exactly.
          .map(d -> String.format("float_rt.make(@as(f64, @bitCast(@as(u64, 0x%016x))))", Double.doubleToRawLongBits(d)))
          .orElseGet(() -> e.accept(gen, true)).describeConstable();
      } catch (NumberFormatException ignored) {
        throw Fail.invalidNum(lit.orElse(name.toString()), "Float");
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> byte_(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return () -> {
      var lit = getLiteral(p, name);
      try {
        return lit
          .map(lambdaName -> "byte_rt.make(" + (Long.parseUnsignedLong(lambdaName.replace("_", ""), 10) & 0xff) + ")")
          .orElseGet(() -> e.accept(gen, true)).describeConstable();
      } catch (NumberFormatException ignored) {
        throw Fail.invalidNum(lit.orElse(name.toString()), "Byte");
      }
    };
  }
  @Override public MagicTrait<MIR.E, String> asciiStr(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> debug(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&debug_rt.VT_Debug)");
  }
  @Override public MagicTrait<MIR.E, String> refK(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> isoPodK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() { return Optional.empty(); }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), "#", 1))) {
          return Optional.of("isopod_rt.make(" + ownedArg(args.getFirst()) + ")");
        }
        return Optional.empty();
      }
    };
  }
  @Override public MagicTrait<MIR.E, String> errorK(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&errors.VT_ErrorK)");
  }

  @Override public MagicTrait<MIR.E, String> tryCatch(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&try_rt.VT_Try)");
  }

  @Override public MagicTrait<MIR.E, String> abort(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&errors.VT_Abort)");
  }

  @Override public MagicTrait<MIR.E, String> magicAbort(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&errors.VT_Magic)");
  }

  @Override public MagicTrait<MIR.E, String> flowK(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&flow_rt.VT_FlowFactory)");
  }
  @Override public MagicTrait<MIR.E, String> feartDriverK(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&flow_rt.VT_FeartDriver)");
  }
  @Override public MagicTrait<MIR.E, String> flowRange(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> pipelineParallelSinkK(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> dataParallelFlowK(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> assert_(MIR.E e) { return EMPTY; }
  @Override public MagicTrait<MIR.E, String> cheapHash(MIR.E e) {
    return () -> Optional.of("hash_rt.make_cheap_hash()");
  }
  @Override public MagicTrait<MIR.E, String> regexK(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&regex_rt.VT_Regexs)");
  }
  @Override public MagicTrait<MIR.E, String> mapK(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&map_rt.VT_Maps)");
  }
  @Override public MagicTrait<MIR.E, String> utf16(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&str_rt.VT_UTF16)");
  }
  @Override public MagicTrait<MIR.E, String> utf8(MIR.E e) {
    return () -> Optional.of("rt.obj_k_singleton(&str_rt.VT_UTF8)");
  }

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

  private String ownedArg(MIR.E e) {
    if (e instanceof MIR.X) {
      return generateShare.apply(e.accept(gen, true), e.t());
    }
    return e.accept(gen, true);
  }
}
