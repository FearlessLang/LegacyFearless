package codegen.zig;

import codegen.MIR;
import failure.Fail;
import id.Id;
import magic.FearlessStringHandler;
import magic.MagicTrait;
import visitors.MIRVisitor;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static magic.Magic.getLiteral;

record ZigNumOps(ZigNumOps.NumOp onNat) {
  interface NumOp { String apply(String[] args); }
  static final String POISON = "(rt.FatPtr{ .data = .{ .int = 0xDEAD }, .vt = @ptrFromInt(0) })";

  private static final Map<Id.MethName, ZigNumOps> numOps = new LinkedHashMap<>();
  private static Id.MethName m(String name, int arity) { return new Id.MethName(name, arity); }
  private static void put(String name, int arity, NumOp nat) {
    assert !numOps.containsKey(m(name, arity));
    numOps.put(m(name, arity), new ZigNumOps(nat));
  }
  static ZigNumOps emit(Id.MethName m, String[] args) {
    return Optional.ofNullable(numOps.get(m))
      .orElseGet(() -> { throw utils.Bug.of("Expected magic to exist for: " + m); });
  }
  static String emitNat(Id.MethName m, String... args) { return emit(m, args).onNat().apply(args); }
  /** Try to emit a Nat/Int magic operation; returns empty if the method is not in the table. */
  static Optional<String> tryEmitNat(Id.MethName m, String... args) {
    return Optional.ofNullable(numOps.get(m)).map(ops -> ops.onNat().apply(args));
  }
  static String zigBool(String condition) {
    return "(if (" + condition + ") rt.obj_k_singleton(&VT_True_0) else rt.obj_k_singleton(&VT_False_0))";
  }

  static String[] callArgs(MagicTrait<MIR.E, String> magic, List<? extends MIR.E> args, MIRVisitor<String> gen) {
    String self = magic.instantiate().orElseThrow();
    Stream<String> rest = args.stream().map(a -> a.accept(gen, true));
    return Stream.concat(Stream.of(self), rest).toArray(String[]::new);
  }

  static {
    // conversions
    put(".int", 0, a -> "int_rt.make(@bitCast(nat_rt.deref(" + a[0] + ")))"); // Nat→Int conversion
    put(".nat", 0, a -> a[0]); // identity
    put(".float", 0, a -> POISON); // not supported
    put(".byte", 0, a -> POISON); // not supported
    put(".str", 0, a -> "str_rt.int_to_str(" + a[0] + ")");

    // arithmetic
    put("+", 1, a -> "nat_rt.add(" + a[0] + ", " + a[1] + ")");
    put("-", 1, a -> "nat_rt.sub(" + a[0] + ", " + a[1] + ")");
    put("*", 1, a -> "nat_rt.mul(" + a[0] + ", " + a[1] + ")");
    put("/", 1, a -> "nat_rt.div(" + a[0] + ", " + a[1] + ")");
    put("%", 1, a -> "nat_rt.mod(" + a[0] + ", " + a[1] + ")");
    put("**", 1, a -> POISON); // pow not yet implemented
    put(".abs", 0, a -> "nat_rt.abs(" + a[0] + ")");
    put(".sqrt", 0, a -> POISON); // not yet implemented

    // bitwise
    put(".shiftLeft", 1, a -> "nat_rt.shift_left(" + a[0] + ", " + a[1] + ")");
    put(".shiftRight", 1, a -> "nat_rt.shift_right(" + a[0] + ", " + a[1] + ")");
    put(".xor", 1, a -> "nat_rt.bitwise_xor(" + a[0] + ", " + a[1] + ")");
    put(".bitwiseAnd", 1, a -> "nat_rt.bitwise_and(" + a[0] + ", " + a[1] + ")");
    put(".bitwiseOr", 1, a -> "nat_rt.bitwise_or(" + a[0] + ", " + a[1] + ")");

    // comparisons
    put(">", 1, a -> zigBool("nat_rt.deref(" + a[0] + ") > nat_rt.deref(" + a[1] + ")"));
    put("<", 1, a -> zigBool("nat_rt.deref(" + a[0] + ") < nat_rt.deref(" + a[1] + ")"));
    put(">=", 1, a -> zigBool("nat_rt.deref(" + a[0] + ") >= nat_rt.deref(" + a[1] + ")"));
    put("<=", 1, a -> zigBool("nat_rt.deref(" + a[0] + ") <= nat_rt.deref(" + a[1] + ")"));
    put("==", 1, a -> zigBool("nat_rt.deref(" + a[0] + ") == nat_rt.deref(" + a[1] + ")"));
    put("!=", 1, a -> zigBool("nat_rt.deref(" + a[0] + ") != nat_rt.deref(" + a[1] + ")"));

    // assertEq - crash for now (uses strings)
    put(".assertEq", 1, a -> POISON);
    put(".assertEq", 2, a -> POISON);

    // hash - crash for now
    put(".hash", 1, a -> POISON);

    // offset (Nat-specific)
    put(".offset", 1, a -> "nat_rt.add(" + a[0] + ", " + a[1] + ")");
  }
}

class ZigStrOps {
  interface StrOp { String apply(String[] args); }
  private static final Map<Id.MethName, StrOp> strOps = new LinkedHashMap<>();
  private static Id.MethName m(String name, int arity) { return new Id.MethName(name, arity); }
  private static void put(String name, int arity, StrOp op) {
    strOps.put(m(name, arity), op);
  }
  static String emit(Id.MethName m, String... args) {
    return Optional.ofNullable(strOps.get(m))
      .map(op -> op.apply(args))
      .orElse(ZigNumOps.POISON);
  }
  /** Try to emit a Str magic operation; returns empty if the method is not in the table. */
  static Optional<String> tryEmit(Id.MethName m, String... args) {
    return Optional.ofNullable(strOps.get(m)).map(op -> op.apply(args));
  }
  static {
    put(".str", 0, a -> a[0]); // identity: Str.str returns self
    put(".size", 0, a -> "str_rt.str_size(" + a[0] + ")");
    put(".isEmpty", 0, a -> ZigNumOps.zigBool("str_rt.deref_str(" + a[0] + ").len == 0"));
    put("+", 1, a -> "str_rt.str_concat(" + a[0] + ", " + a[1] + ")");
    put("==", 1, a -> ZigNumOps.zigBool("std.mem.eql(u8, str_rt.deref_str(" + a[0] + "), str_rt.deref_str(" + a[1] + "))"));
    put("!=", 1, a -> ZigNumOps.zigBool("!std.mem.eql(u8, str_rt.deref_str(" + a[0] + "), str_rt.deref_str(" + a[1] + "))"));
    put(".assertEq", 1, a -> ZigNumOps.POISON); // needs error handling
    put(".assertEq", 2, a -> ZigNumOps.POISON);
  }
}

public record ZigMagicImpls(
    MIRVisitor<String> gen,
    Function<MIR.MT, String> getTName,
    ast.Program p) implements magic.MagicImpls<String> {

  @Override public MagicTrait<MIR.E, String> nat(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var lit = getLiteral(p, name);
        try {
          return lit
            .map(lambdaName -> "nat_rt.make(" + Long.parseUnsignedLong(lambdaName.replace("_", ""), 10) + ")")
            .orElseGet(() -> e.accept(gen, true)).describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Nat");
        }
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty(); // Handled by runtime dispatch in objs.zig
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> int_(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var lit = getLiteral(p, name);
        try {
          return lit
            .map(lambdaName -> lambdaName.startsWith("+") ? lambdaName.substring(1) : lambdaName)
            .map(lambdaName -> "int_rt.make(" + Long.parseLong(lambdaName.replace("_", ""), 10) + ")")
            .orElseGet(() -> e.accept(gen, true)).describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Int");
        }
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty(); // Handled by runtime dispatch in objs.zig
      }
    };
  }

  // All other magic types: crash at runtime
  private MagicTrait<MIR.E, String> crashMagic(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() { return Optional.empty(); }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        // Use a poison FatPtr (null vt) — crashes only if dispatched
        return Optional.of(ZigNumOps.POISON);
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> float_(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> byte_(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> str(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var lit = getLiteral(p, name);
        if (lit.isPresent()) {
          var decoded = new FearlessStringHandler(FearlessStringHandler.StringKind.Unicode)
            .toJavaString(lit.get()).get();
          return Optional.of("str_rt.make_str_from_literal(\"" + escapeZigStr(decoded) + "\")");
        }
        return e.accept(gen, true).describeConstable();
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty(); // Handled by runtime dispatch in objs.zig
      }
    };
  }
  @Override public MagicTrait<MIR.E, String> asciiStr(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> debug(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> refK(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> isoPodK(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> assert_(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> cheapHash(MIR.E e) { return crashMagic(e); }
  @Override public MagicTrait<MIR.E, String> regexK(MIR.E e) { return crashMagic(e); }

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
