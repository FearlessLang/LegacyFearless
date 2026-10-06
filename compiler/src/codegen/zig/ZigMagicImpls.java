package codegen.zig;

import codegen.MIR;
import failure.Fail;
import id.Id;
import id.Mdf;
import magic.FearlessStringHandler;
import magic.Magic;
import magic.MagicTrait;
import visitors.MIRVisitor;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

  /// A primitive receiver type.
  /// `module` is the runtime module that implements the type.
  /// `inPlaceMethods` holds the methods of the type whose calls can stay in a call expression.
  /// Each method has the text that the dispatch of the runtime module tests: `<capability> <name>/<arity>`.
  private record PrimitiveType(Id.DecId type, String module, Set<String> inPlaceMethods) {}

  /// The set of the methods in a text with one method on each line. Blank lines and the white space around a method do not count.
  private static Set<String> methods(String lines) {
    return lines.lines()
      .map(String::strip)
      .filter(line -> !line.isEmpty())
      .collect(Collectors.toUnmodifiableSet());
  }

  /// The primitive receiver types: Nat, Int, Float and Byte.
  /// A call to any method of these types goes through the runtime module of the type.
  ///
  /// A method is in the list of its type when it always gives a result and has no effect that a program can observe.
  /// A call to such a method can stay in a call expression, so it can run after an operand to its right.
  /// A method that is not in the list is safe: its call gets a temp, so it runs in operand order.
  ///
  /// Each list has the lines of the dispatch switch of the runtime module of its type, in the same order, less these methods:
  /// - `/` and `%` on Nat, Int and Byte fail when the divisor is 0. `/` on Int also fails for the least Int and -1.
  /// - `.shiftLeft` and `.shiftRight` on Nat fail when the count is 64 or more.
  /// - `.sqrt` on Int fails when the receiver is negative. `.abs` on Int fails for the least Int.
  /// - `.assertEq` with one or two arguments fails when the two values differ.
  /// - `.hash` changes its hasher, and the hasher can run any code.
  /// - `.str` makes a new Str.
  ///
  /// `/` and `%` on Float are in the list: a divisor of 0 gives an IEEE result.
  private static final List<PrimitiveType> PRIMITIVE_TYPES = List.of(
    new PrimitiveType(Magic.Nat, "nat_rt", methods("""
      imm +/1
      imm -/1
      imm */1
      imm **/1
      imm .abs/0
      imm .sqrt/0
      imm >/1
      imm </1
      imm >=/1
      imm <=/1
      imm ==/1
      imm !=/1
      read .int/0
      read .nat/0
      read .float/0
      read .byte/0
      imm .xor/1
      imm .bitwiseAnd/1
      imm .bitwiseOr/1
      imm .offset/1
      """)),
    new PrimitiveType(Magic.Int, "int_rt", methods("""
      imm +/1
      imm -/1
      imm */1
      imm **/1
      imm >/1
      imm </1
      imm >=/1
      imm <=/1
      imm ==/1
      imm !=/1
      read .int/0
      read .nat/0
      read .float/0
      read .byte/0
      imm .shiftLeft/1
      imm .shiftRight/1
      imm .xor/1
      imm .bitwiseAnd/1
      imm .bitwiseOr/1
      """)),
    new PrimitiveType(Magic.Float, "float_rt", methods("""
      imm +/1
      imm -/1
      imm */1
      imm //1
      imm %/1
      imm **/1
      imm .abs/0
      imm .sqrt/0
      imm >/1
      imm </1
      imm >=/1
      imm <=/1
      imm ==/1
      imm !=/1
      imm .round/0
      imm .ceil/0
      imm .floor/0
      imm .isNaN/0
      imm .isInfinite/0
      imm .isPosInfinity/0
      imm .isNegInfinity/0
      read .int/0
      read .nat/0
      read .byte/0
      read .float/0
      """)),
    new PrimitiveType(Magic.Byte, "byte_rt", methods("""
      imm +/1
      imm -/1
      imm */1
      imm **/1
      imm .abs/0
      imm .sqrt/0
      imm >/1
      imm </1
      imm >=/1
      imm <=/1
      imm ==/1
      imm !=/1
      imm .shiftLeft/1
      imm .shiftRight/1
      imm .xor/1
      imm .bitwiseAnd/1
      imm .bitwiseOr/1
      imm .offset/1
      read .int/0
      read .nat/0
      read .float/0
      read .byte/0
      """)));

  private Optional<PrimitiveType> primitiveType(MIR.E receiver) {
    return PRIMITIVE_TYPES.stream()
      .filter(primitive -> isMagic(primitive.type(), receiver))
      .findFirst();
  }

  /// The runtime module that implements the receiver of a call: `nat_rt`, `int_rt`, `float_rt` or `byte_rt`.
  /// Empty when the receiver is not a Nat, Int, Float or Byte.
  public Optional<String> primitiveModule(MIR.E receiver) {
    return primitiveType(receiver).map(PrimitiveType::module);
  }

  /// True when the receiver of the call is a primitive type and the list of that type has the method of the call.
  /// The key of a method has its capability, its name and its arity.
  /// A call whose name has no capability is not in any list.
  public boolean isInPlacePrimitiveCall(MIR.MCall call) {
    return call.name().mdf()
      .map(capability -> ZigSigStringBuilder.sigText(capability, call.name().name(), call.args().size()))
      .flatMap(text -> primitiveType(call.recv()).map(primitive -> primitive.inPlaceMethods().contains(text)))
      .orElse(false);
  }

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
        return Optional.of("str_rt." + ctor + "(" + ZigStringIds.zigString(decoded) + ")");
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

  private String ownedArg(MIR.E e) {
    if (e instanceof MIR.X) {
      return generateShare.apply(e.accept(gen, true), e.t());
    }
    return e.accept(gen, true);
  }
}
