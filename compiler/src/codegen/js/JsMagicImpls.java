package codegen.js;

import codegen.FlowSelector;
import codegen.MIR;
import failure.Fail;
import id.Id;
import id.Mdf;
import magic.Magic;
import magic.MagicCallable;
import magic.MagicTrait;
import utils.Bug;
import visitors.MIRVisitor;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.*;

record JsNumOps(
  JsNumOps.NumOp onInt,
  JsNumOps.NumOp onNat,
  JsNumOps.NumOp onByte,
  JsNumOps.NumOp onFloat
) {

  public interface NumOp { String apply(String[] args); }

  private static final NumOp errOp = _ -> { throw new RuntimeException("Impossible case"); };

  private static final Map<Id.MethName, JsNumOps> numOps = new LinkedHashMap<>();

  private static Id.MethName m(String name, int arity) { return new Id.MethName(name, arity); }

  private static void putFloat(String name, int arity, NumOp f){
    singlePut(m(name, arity), new JsNumOps(errOp, errOp, errOp, f));
  }

  private static void put(String name, int arity, NumOp all) {
    singlePut(m(name, arity), new JsNumOps(all, all, all, all));
  }

  private static void put(String name, int arity, NumOp i, NumOp n, NumOp b, NumOp f) {
    singlePut(m(name, arity), new JsNumOps(i, n, b, f));
  }

  private static void singlePut(Id.MethName m, JsNumOps ops) {
    if (numOps.containsKey(m)) throw new RuntimeException("Duplicate op: " + m);
    numOps.put(m, ops);
  }

  public static JsNumOps emit(Id.MethName m) {
    return Optional.ofNullable(numOps.get(m))
      .orElseThrow(() -> new RuntimeException("Expected magic for: " + m));
  }

  public static String emitInt(Id.MethName m, String... args) {
    return emit(m).onInt().apply(args);
  }

  public static String emitNat(Id.MethName m, String... args) {
    return emit(m).onNat().apply(args);
  }

  public static String emitByte(Id.MethName m, String... args) {
    return emit(m).onByte().apply(args);
  }

  public static String emitFloat(Id.MethName m, String... args) {
    return emit(m).onFloat().apply(args);
  }

  public static String[] callArgs(MagicTrait<MIR.E,String> magic, List<? extends MIR.E> args, MIRVisitor<String> gen){
    String self = magic.instantiate().orElseThrow();
    Stream<String> rest = args.stream().map(a -> a.accept(gen, true));
    return Stream.concat(Stream.of(self), rest).toArray(String[]::new);
  }

  static {
    put(".int", 0,
      a -> a[0],                                      // int
      a -> a[0],                                      // nat
      a -> "(" + a[0] + " & 0xFFn)",                   // byte
      a -> a[0]                 // float
    );
    put(".nat", 0,
      a -> a[0],                                      // int -> nat (no conversion needed)
      a -> a[0],                                      // nat -> nat (identity)
      a -> "(" + a[0] + " & 0xFFn)",                   // byte -> nat (ensure unsigned)
      a -> a[0]                 // float -> nat
    );
    put(".float", 0,
      a -> "(+" + a[0] + ")",                         // int
      a -> "(+" + a[0] + ")",                         // nat
      a -> "(+" + a[0] + ")",                         // byte
      a -> a[0]                                       // float
    );
    put(".byte", 0,
      a -> "(Number(" + a[0] + ") & 0xFF)",        // int -> byte
      a -> "(Number(" + a[0] + ") & 0xFF)",        // nat -> byte
      a -> a[0],                                   // byte -> byte
      a -> "(Math.trunc(" + a[0] + ") & 0xFF)"     // float -> byte
    );
    put(".str", 0, a-> "rt$$Str.numToStr(" + a[0] + ")");

    // Arithmetic
    put("+", 1,
      a -> "rt$$Num.toInt64(" + a[0] + " + " + a[1] + ")",           // int
      a -> "rt$$Num.toNat64(" + a[0] + " + " + a[1] + ")",           // nat
      a -> "rt$$Num.toByte8(" + a[0] + " + " + a[1] + ")",  // byte
      a -> "(" + a[0] + " + " + a[1] + ")"            // float
    );
    put("-", 1,
      a -> "rt$$Num.toInt64(" + a[0] + " - " + a[1] + ")",           // int
      a -> "rt$$Num.toNat64(" + a[0] + " - " + a[1] + ")",           // nat
      a -> "rt$$Num.toByte8(" + a[0] + " - " + a[1] + ")",  // byte
      a -> "(" + a[0] + " - " + a[1] + ")"            // float
    );
    put("*", 1,
      a -> "rt$$Num.toInt64(" + a[0] + " * " + a[1] + ")",           // int
      a -> "rt$$Num.toNat64(" + a[0] + " * " + a[1] + ")",           // nat
      a -> "rt$$Num.toByte8(" + a[0] + " * " + a[1] + ")",  // byte
      a -> "(" + a[0] + " * " + a[1] + ")"            // float
    );
    put("/", 1,
      a -> "(" + a[0] + " / " + a[1] + ")",           // int (BigInt truncating div)
      a -> "(" + a[0] + " / " + a[1] + ")",           // nat
      a -> "((" + a[0] + " / " + a[1] + ") & 0xFF)",  // byte (mask back to 0–255)
      a -> "(" + a[0] + " / " + a[1] + ")"            // float
    );
    put("%", 1,
      a -> "(" + a[0] + " % " + a[1] + ")",           // int
      a -> "(" + a[0] + " % " + a[1] + ")",           // nat
      a -> "((" + a[0] + " % " + a[1] + ") & 0xFF)",  // byte (mask)
      a -> "(" + a[0] + " % " + a[1] + ")"            // float (or use floatMod)
    );
    put("**", 1,
      a -> "rt$$Num.toInt64(" + a[0] + " ** " + a[1] + ")",          // int (BigInt)
      a -> "rt$$Num.toNat64(" + a[0] + " ** " + a[1] + ")",          // nat (BigInt)
      a -> "rt$$Num.toByte8(" + a[0] + " ** " + a[1] + ")", // byte
      a -> "Math.pow(" + a[0] + ", " + a[1] + ")"     // float
    );
    // Comparisons
    put(">", 1,
      a -> "rt$$Num.toBool((" + a[0] + ") > (" + a[1] + "))",     // int
      a -> "rt$$Num.toBool((" + a[0] + ") > (" + a[1] + "))",     // nat
      a -> "rt$$Num.toBool((Number(" + a[0] + ") & 0xFF) > (Number(" + a[1] + ") & 0xFF))", // byte
      a -> "rt$$Num.toBool((" + a[0] + ") > (" + a[1] + "))"      // float
    );

    put("<", 1,
      a -> "rt$$Num.toBool((" + a[0] + ") < (" + a[1] + "))",     // int
      a -> "rt$$Num.toBool((" + a[0] + ") < (" + a[1] + "))",     // nat
      a -> "rt$$Num.toBool((Number(" + a[0] + ") & 0xFF) < (Number(" + a[1] + ") & 0xFF))", // byte
      a -> "rt$$Num.toBool((" + a[0] + ") < (" + a[1] + "))"      // float
    );

    put(">=", 1,
      a -> "rt$$Num.toBool((" + a[0] + ") >= (" + a[1] + "))",    // int
      a -> "rt$$Num.toBool((" + a[0] + ") >= (" + a[1] + "))",    // nat
      a -> "rt$$Num.toBool((Number(" + a[0] + ") & 0xFF) >= (Number(" + a[1] + ") & 0xFF))", // byte
      a -> "rt$$Num.toBool((" + a[0] + ") >= (" + a[1] + "))"     // float
    );

    put("<=", 1,
      a -> "rt$$Num.toBool((" + a[0] + ") <= (" + a[1] + "))",    // int
      a -> "rt$$Num.toBool((" + a[0] + ") <= (" + a[1] + "))",    // nat
      a -> "rt$$Num.toBool((Number(" + a[0] + ") & 0xFF) <= (Number(" + a[1] + ") & 0xFF))", // byte
      a -> "rt$$Num.toBool((" + a[0] + ") <= (" + a[1] + "))"     // float
    );

    put("==", 1,
      a -> "rt$$Num.toBool((" + a[0] + ") === (" + a[1] + "))",   // int
      a -> "rt$$Num.toBool((" + a[0] + ") === (" + a[1] + "))",   // nat
      a -> "rt$$Num.toBool((Number(" + a[0] + ") & 0xFF) === (Number(" + a[1] + ") & 0xFF))", // byte
      a -> "rt$$Num.toBool((" + a[0] + ") === (" + a[1] + "))" // float
    );

    put("!=", 1,
      a -> "rt$$Num.toBool((" + a[0] + ") !== (" + a[1] + "))",   // int
      a -> "rt$$Num.toBool((" + a[0] + ") !== (" + a[1] + "))",   // nat
      a -> "rt$$Num.toBool((Number(" + a[0] + ") & 0xFF) !== (Number(" + a[1] + ") & 0xFF))", // byte
      a -> "rt$$Num.toBool((" + a[0] + ") !== (" + a[1] + "))"   // float
    );
    // Bitwise operations
    put(".shiftRight", 1,
      a -> a[0] + " >> " + a[1],                    // int
      a -> "((" + a[0] + " & ((1n<<64n)-1n)) >> " + a[1] + ")", // nat
      a -> "Number(" + a[0] + " >> " + a[1] + ") & 0xFF", // byte
      errOp                                         // float
    );
    put(".shiftLeft", 1,
      a -> a[0] + " << " + a[1],                    // int
      a -> "((" + a[0] + " << " + a[1] + ") & ((1n<<64n)-1n))", // nat
      a -> "Number(" + a[0] + " << " + a[1] + ") & 0xFF", // byte
      errOp                                         // float
    );
    put(".xor", 1,
      a -> a[0] + " ^ " + a[1],                     // int
      a -> a[0] + " ^ " + a[1],                     // nat
      a -> "Number(" + a[0] + " ^ " + a[1] + ") & 0xFF",  // byte
      errOp                                         // float
    );
    put(".bitwiseOr", 1,
      a -> a[0] + " | " + a[1],                     // int
      a -> a[0] + " | " + a[1],                     // nat
      a -> "Number(" + a[0] + " | " + a[1] + ") & 0xFF",  // byte
      errOp                                         // float
    );
    // Bitwise operations (only for int, nat, byte)
    put(".bitwiseAnd", 1,
      a -> a[0] + " & " + a[1],           // int
      a -> a[0] + " & " + a[1],           // nat
      a -> "Number(" + a[0] + " & " + a[1] + ") & 0xFF",  // byte
      errOp                                           // float
    );
    // Math operations
    put(".abs", 0,
      a -> a[0] + " < 0n ? -(" + a[0] + ") : " + a[0], // BigInt-safe for Int
      a -> a[0],                // nat
      a -> a[0],                // byte
      a -> "Math.abs(" + a[0] + ")"                 // float
    );
    put(".sqrt", 0,
      a -> "rt$$Num.intSqrt("  + a[0] + ")", // int
      a -> "rt$$Num.natSqrt("  + a[0] + ")", // nat
      a -> "rt$$Num.byteSqrt(" + a[0] + ")", // byte
      a -> "Math.sqrt(" + a[0] + ")"                // float
    );
    // Assertion operations
    put(".assertEq", 1,
      a-> "base$$_IntAssertionHelper_0.assertEq$imm$3$fun("   + a[0] + ", " + a[1] + ", null)", // Int
      a-> "base$$_NatAssertionHelper_0.assertEq$imm$3$fun("   + a[0] + ", " + a[1] + ", null)", // Nat
      a-> "base$$_ByteAssertionHelper_0.assertEq$imm$3$fun("  + a[0] + ", " + a[1] + ", null)", // Byte
      a-> "base$$_FloatAssertionHelper_0.assertEq$imm$3$fun(" + a[0] + ", " + a[1] + ", null)" // Float
    );
    put(".assertEq", 2,//TODO: should those be in rt? what is the null?
      a-> "base$$_IntAssertionHelper_0.assertEq$imm$4$fun("   + a[0] + ", " + a[1] + ", " + a[2] + ", null)", // Int
      a-> "base$$_NatAssertionHelper_0.assertEq$imm$4$fun("   + a[0] + ", " + a[1] + ", " + a[2] + ", null)", // Nat
      a-> "base$$_ByteAssertionHelper_0.assertEq$imm$4$fun("  + a[0] + ", " + a[1] + ", " + a[2] + ", null)", // Byte
      a-> "base$$_FloatAssertionHelper_0.assertEq$imm$4$fun(" + a[0] + ", " + a[1] + ", " + a[2] + ", null)" // Float
    );
    put(".hash", 1,
      a-> a[1] + ".int$mut("  + a[0] + ")", // Int
      a-> a[1] + ".int$mut("  + a[0] + ")", // Nat
      a-> a[1] + ".byte$mut(" + a[0] + ")", // Byte
      a-> a[1] + ".int$mut("  + a[0] + ")" // Float
    );
    // Offset operation
    put(".offset", 1,
      errOp,                                        // int
      a -> a[0] + " + " + a[1],                     // nat
      a -> "Number(" + a[0] + " + " + a[1] + ") & 0xFF",  // byte
      errOp                                         // float
    );
    // Float-specific operations
    putFloat(".isNaN", 0,a -> "rt$$Num.toBool(" + "isNaN(" + a[0] + "))");
    putFloat(".isInfinite", 0, a -> "rt$$Num.toBool(" + "!isFinite(" + a[0] + "))");
    putFloat(".isPosInfinity", 0,a -> "rt$$Num.toBool(" + a[0] + " === Infinity)");
    putFloat(".isNegInfinity", 0,a -> "rt$$Num.toBool(" + a[0] + " === -Infinity)");
    // float-only extras
    putFloat(".round", 0, a-> "Math.round(" + a[0] + ")");
    putFloat(".ceil", 0,a-> "Math.ceil(" + a[0] + ")" );
    putFloat(".floor", 0,a-> "Math.floor(" + a[0] + ")" );
  }
}

public record JsMagicImpls(MIRVisitor<String> gen, ast.Program p) implements magic.MagicImpls<String> {
  @Override public MagicTrait<MIR.E, String> int_(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var name = e.t().name().orElseThrow();
        var lit = magic.Magic.getLiteral(p, name);
        try {
          return lit
            .map(lambdaName -> lambdaName.startsWith("+") ? lambdaName.substring(1) : lambdaName)
            .map(lambdaName -> Long.parseLong(lambdaName.replace("_", ""), 10) + "n") // BigInt literal
            .orElseGet(() -> e.accept(gen, true))
            .describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Int");
        }
      }

      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.of(JsNumOps.emitInt(m, JsNumOps.callArgs(this, args, gen)));
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> nat(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var name = e.t().name().orElseThrow();
        var lit = magic.Magic.getLiteral(p, name);
        try {
          return lit
//            .map(lambdaName -> Long.parseUnsignedLong(lambdaName.replace("_", ""), 10) + "n") // BigInt literal
            .map(lambdaName -> {
              var s = lambdaName.replace("_", "");
              var value = new BigInteger(s, 10);
              var maxNat = new BigInteger("18446744073709551615"); // 2^64 - 1
              if (value.compareTo(BigInteger.ZERO) < 0 || value.compareTo(maxNat) > 0)
                throw Fail.invalidNum(s, "Nat"); // for veryLongLongToStr test
              return value.toString() + "n";
            })
            .orElseGet(() -> e.accept(gen, true))
            .describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Nat");
        }
      }

      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args,
                                   EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.of(JsNumOps.emitNat(m, JsNumOps.callArgs(this, args, gen)));
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> float_(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var name = e.t().name().orElseThrow();
        var lit = magic.Magic.getLiteral(p, name);
        try {
          return lit
            .map(lambdaName -> {
              // Parse double and output JS number
              double value = Double.parseDouble(lambdaName.replace("_", ""));
              return String.valueOf(value);
            })
            .orElseGet(() -> "parseFloat(" + e.accept(gen, true) + ")")
            .describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Float");
        }
      }

      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args,
                                   EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.of(JsNumOps.emitFloat(m, JsNumOps.callArgs(this, args, gen)));
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> byte_(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var name = e.t().name().orElseThrow();
        var lit = magic.Magic.getLiteral(p, name);
        try {
          return lit
            .map(lambdaName -> {
              // Parse as unsigned byte (0-255)
              int value = Integer.parseUnsignedInt(lambdaName.replace("_", ""), 10);
              return String.valueOf(value & 0xFF); // Ensure byte range
            })
            .orElseGet(() -> "(" + e.accept(gen, true) + " & 0xFF)") // Byte masking
            .describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Byte");
        }
      }

      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args,
                                   EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.of(JsNumOps.emitByte(m, JsNumOps.callArgs(this, args, gen)));
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> str(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        throw Bug.unreachable();
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> utf8(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        // Use the runtime UTF8 helper singleton we ship in rt-js
        return Optional.of("rt$$UTF8.$self");
      }
      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        // No special call handling for utf8 here; fall back to default
        return MagicTrait.super.call(m, args, variants, expectedT);
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> utf16(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        // no single instantiate value for utf16 magic
        return Optional.empty();
      }

      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args,
                                   EnumSet<MIR.MCall.CallVariant> variants,
                                   MIR.MT expectedT) {
        // two helpers we want to support at codegen time:
        //  - .fromCodePoint( cp )  -> rt$$Str.fromJsStr(String.fromCodePoint(cp))
        //  - .fromSurrogatePair(h, l) -> rt$$Str.fromJsStr(String.fromCodePoint(h, l))
        if (m.equals(new Id.MethName(".fromCodePoint", 1))) {
          String cpExpr = args.get(0).accept(gen, true);
          // String.fromCodePoint accepts number(s) and returns a JS string
          return Optional.of("rt$$Str.fromJsStr(String.fromCodePoint(" + cpExpr + "))");
        }
        if (m.equals(new Id.MethName(".fromSurrogatePair", 2))) {
          String hi = args.get(0).accept(gen, true);
          String lo = args.get(1).accept(gen, true);
          // String.fromCodePoint can accept two code points as well
          return Optional.of("rt$$Str.fromJsStr(String.fromCodePoint(" + hi + ", " + lo + "))");
        }
        return MagicTrait.super.call(m, args, variants, expectedT);
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> isoPodK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }

      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args,
                                   EnumSet<MIR.MCall.CallVariant> variants,
                                   MIR.MT expectedT) {
        // handle the "#/1" constructor that produces an IsoPod-like object
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), "#", 1))) {
          // single-element iso-pod whose internal x is the provided expression
          String xExpr = args.get(0).accept(gen, true);

          // Emit an inline JS "new class { ... }()" expression implementing the API used by backend.
          // Methods: isAlive$read, peek$read(viewer), $exclamation$mut(), next$mut(x).
          // Use base$$True_0.$self / base$$False_0.$self and base$$Void_0.$self as runtime constants.
          String code = String.format("""
          new (class {
            constructor() { this.x = %s; this.isAlive = true; }
            isAlive$read$0() { return this.isAlive ? base$$True_0.$self : base$$False_0.$self; }
            // peek: viewer.some$mut$1(value) or viewer.empty$mut$0()
            peek$read$1(f) { return this.isAlive ? f.some$mut$1(this.x) : f.empty$mut$0(); }
            $exclamation$mut$0() {
              if (!this.isAlive) {
                // throw a runtime Fearless error using runtime helper
                return rt$$Error.throwFearlessError(rt$$Str.fromJsStr("Cannot consume an empty IsoPod."));
              }
              this.isAlive = false;
              return this.x;
            }
            next$mut$1(x) { this.isAlive = true; this.x = x; return base$$Void_0.$self; }
          })()""", xExpr);

          return Optional.of(code);
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> asciiStr(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        throw Bug.todo();
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty();
      }
    };
  }

  @Override public MagicCallable<MIR.E,String> variantCall(MIR.E e) {
    return (m, args, variants, expectedT) -> {
      var call = (MIR.MCall) e;
      assert call.variant() == variants; // same check as Java version

      var parallelConstr = FlowSelector.bestParallelConstr(call);

      if (isMagic(Magic.FlowK, call.recv())) {
        if (m.name().equals("#") || m.name().equals(".ofIso")) {
          var listKCall = new MIR.MCall(
            new MIR.CreateObj(Mdf.imm, Magic.ListK),
            new Id.MethName(Optional.of(Mdf.imm), "#", call.args().size()),
            call.args(),
            new MIR.MT.Plain(Mdf.mut, Magic.FList),
            Mdf.imm,
            EnumSet.of(MIR.MCall.CallVariant.Standard)
          );
          var listFlowCall = new MIR.MCall(
            listKCall,
            new Id.MethName(Optional.of(Mdf.mut), ".flow", 0),
            List.of(),
            call.t(),
            Mdf.mut,
            variants
          );
          return Optional.of(gen.visitMCall(listFlowCall, true));
        }
        if (m.name().equals(".range") || m.name().equals(".ofIsos")) {
          assert parallelConstr.isPresent();
          return Optional.of(
            "rt$$flows.FlowCreator.fromFlow(" +
              gen.visitCreateObj(new MIR.CreateObj(Mdf.imm, Magic.DataParallelFlowK), true) +
              ", " +
              call.withVariants(EnumSet.of(MIR.MCall.CallVariant.Standard)).accept(gen, true) +
              ")"
          );
        }
      }

      if (variants.contains(MIR.MCall.CallVariant.SafeMutSourceFlow)) {
        if (isMagic(Magic.FList, call.recv())) {
          var newVariants = EnumSet.copyOf(variants);
          newVariants.remove(MIR.MCall.CallVariant.SafeMutSourceFlow);
          return Optional.of(gen.visitMCall(new MIR.MCall(
            new MIR.CreateObj(Mdf.imm, Magic.SafeFlowSource),
            new Id.MethName(Optional.of(Mdf.imm), ".fromList", 1),
            List.of(call.recv()),
            call.t(),
            Mdf.imm,
            newVariants
          ), true));
        }
      }

      if (m.equals(new Id.MethName(".flow", 0)) || isMagic(Magic.SafeFlowSource, call.recv())) {
        if (parallelConstr.isPresent()) {
          var flowMethName = StringIds.$self.getMName(call.mdf(), call.name(), call.args().size());
          var argList = args.stream()
            .map(arg -> arg.accept(gen, true))
            .collect(Collectors.joining(", "));
          return "rt$$flows.FlowCreator.fromFlow(%s, %s.%s(%s))".formatted(
            gen.visitCreateObj(new MIR.CreateObj(Mdf.imm, parallelConstr.get()), true),
            call.recv().accept(gen, true),
            flowMethName,
            argList
          ).describeConstable();
        }
      }

      System.err.println("Warning: No JS magic handler found for: " + e +
        "\nFalling back to Fearless JS implementation.");
      return Optional.empty();
    };
  }

  @Override public MagicTrait<MIR.E,String> assert_(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() { return Optional.empty(); }

      @Override
      public Optional<String> call(Id.MethName m,
                                   List<? extends MIR.E> args,
                                   EnumSet<MIR.MCall.CallVariant> variants,
                                   MIR.MT expectedT) {
        // === assert_._fail/0 ===
        if (m.equals(new Id.MethName("._fail", 0))) {
          return Optional.of("""
            (() => {
              console.error("Assertion failed :(");
              if (typeof process !== "undefined") process.exit(1);
              else throw new Error("Assertion failed :(");
            })()
          """);
        }

        // === assert_._fail/1 ===
        if (m.equals(new Id.MethName("._fail", 1))) {
          String msg = args.get(0).accept(gen, true);
          return Optional.of("""
            (() => {
              rt$$IO.$self.printlnErr$mut$1(%s);
              if (typeof process !== "undefined") process.exit(1);
              else throw new Error(%s);
            })()
          """.formatted(msg, msg));
        }

        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> abort(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName("!", 0))) {
          return Optional.of("""
            (function() {
                console.error("Program aborted at:\\n" + new Error().stack);
                if (typeof process !== "undefined") process.exit(1);
                else throw new Error("Program aborted");
              })()
            """);
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> magicAbort(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() { return Optional.empty(); }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName("!", 0))) {
          return Optional.of("""
            (function() {
                console.error("No magic code was found at:\\n" + new Error().stack);
                if (typeof process !== "undefined") process.exit(1);
                else throw new Error("No magic code was found");
              })()
            """);
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> errorK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName("!", 1))) {
          return Optional.of("rt$$Error.throwFearlessError(%s)".formatted(args.getFirst().accept(gen, true)));
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> tryCatch(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        return Optional.of("rt$$Try.$self");
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> flowRange(MIR.E e) {
    return ()->Optional.of("rt$$flows.Range.$self");
  }

  @Override public MagicTrait<MIR.E,String> pipelineParallelSinkK(MIR.E e) {
    return ()->Optional.of("rt$$flows.pipelineParallel.PipelineParallelFlow.WrappedSinkK.$self");
  }

  @Override public MagicTrait<MIR.E, String> dataParallelFlowK(MIR.E e) {
    return ()->Optional.of("rt$$flows.dataParallel.DataParallelFlowK.$self");
  }

  @Override public MagicTrait<MIR.E,String> debug(MIR.E e) {
    return new MagicTrait<>() {

      @Override public Optional<String> instantiate() {
        return Optional.of("rt$$Debug.$self");
      }

      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> cheapHash(MIR.E e) {
    return "new rt$$CheapHash()"::describeConstable;
  }

  @Override public MagicTrait<MIR.E, String> regexK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() { return Optional.empty(); }
      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), "#", 1))) {
          return Optional.of("new rt$$Regex(%s)".formatted(args.getFirst().accept(gen, true)));
        }
        return MagicTrait.super.call(m, args, variants, expectedT);
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> refK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), "#", 1))) {
          MIR.E x = args.getFirst();
          return Optional.of(String.format("new rt$$Var(%s)", x.accept(gen, true)));
        }
        return Optional.empty();
      }
    };
  }
  @Override public MagicTrait<MIR.E, String> listK(MIR.E e) {
    return ()->Optional.of("rt$$ListK.$self");
  }

  @Override public MagicTrait<MIR.E, String> mapK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {return Optional.empty();}
      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), ".hashMap", 2))) {
          return "new rt$$LinkedHashMap(%s,%s)"
            .formatted(args.getFirst().accept(gen, true), args.get(1).accept(gen, true))
            .describeConstable();
        }
        return MagicTrait.super.call(m, args, variants, expectedT);
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> document(MIR.E e) {
    return ()->Optional.of("rt$$Document.$self");
  }
  @Override public MagicTrait<MIR.E, String> documents(MIR.E e) {
    return ()->Optional.of("rt$$Documents.$self");
  }
  @Override public MagicTrait<MIR.E, String> element(MIR.E e) {
    return ()->Optional.of("rt$$Element.$self");
  }
  @Override public MagicTrait<MIR.E, String> event(MIR.E e) {
    return ()->Optional.of("rt$$Event.$self");
  }
}