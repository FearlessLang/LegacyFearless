package codegen.java;

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

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static magic.Magic.getLiteral;

record NumOps(NumOp onInt, NumOp onNat, NumOp onByte, NumOp onFloat){
  interface NumOp  { String apply(String[] args); }
  static NumOp errOp= _->{ throw utils.Bug.of("Expected magic to exist in impossible case"); };
  private static final Map<Id.MethName, NumOps> numOps = new LinkedHashMap<>();
  private static Id.MethName m(String name, int arity) { return new Id.MethName(name, arity); }
  private static void put(String name, int arity, NumOp all){
    singlePut(m(name, arity), new NumOps(all, all, all, all));
  }
  private static void putFloat(String name, int arity, NumOp f){
    singlePut(m(name, arity), new NumOps(errOp, errOp, errOp, f));
  }
  private static void put(String name, int arity, NumOp i, NumOp n, NumOp b, NumOp f){
    singlePut(m(name, arity), new NumOps(i, n, b, f));
  }
  private static void singlePut(Id.MethName m, NumOps n){
    assert !numOps.containsKey(m);
    numOps.put(m, n);
  }
  static NumOps emit(Id.MethName m, String[] args){
    return Optional.ofNullable(numOps.get(m))
      .map(ops->ops.checkedApply(m, args))
      .orElseGet(()->{throw utils.Bug.of("Expected magic to exist for: " + m);});
  }
  NumOps checkedApply(Id.MethName m, String[] args){
    if(args.length-1 == m.num()){ return this; }
    throw utils.Bug.of("Arity mismatch for " + m + ": got " + (args.length - 1));
  }
  static String emitInt(Id.MethName m, String... args){ return emit(m,args).onInt().apply(args); }
  static String emitNat(Id.MethName m, String... args){ return emit(m,args).onNat().apply(args); }
  static String emitByte(Id.MethName m, String... args){ return emit(m,args).onByte().apply(args); }
  static String emitFloat(Id.MethName m, String... args){ return emit(m,args).onFloat().apply(args); }
  
  static String[] callArgs(MagicTrait<MIR.E,String> magic, List<? extends MIR.E> args, MIRVisitor<String> gen){
    String self= magic.instantiate().orElseThrow();
    Stream<String> rest= args.stream().map(a -> a.accept(gen, true));
    return Stream.concat(Stream.of(self),rest).toArray(String[]::new);
  }  
  static {
    // conversions
    put(".int",0,
     /*Int*/  a->a[0],
     /*Nat*/  a->a[0],
     /*Byte*/ a->"(long)"+a[0],
     /*Float*/a->"(long)"+a[0]
     );
    put(".nat", 0,
      /*Int*/   a->a[0],
      /*Nat*/   a->a[0],
      /*Byte*/  a->"(long)"+a[0],
      /*Float*/ a->"(long)"+a[0]
    );
    put(".float", 0,
      /*Int*/   a->"(double)"+a[0],
      /*Nat*/   a->"(double)"+a[0],
      /*Byte*/  a->"(double)"+a[0],
      /*Float*/ a->a[0]
    );
    put(".byte", 0,
      /*Int*/   a->"(byte)"+a[0],
      /*Nat*/   a->"(byte)"+a[0],
      /*Byte*/  a->a[0],
      /*Float*/ a->"(byte)"+a[0]
    );
    put(".str", 0,
      /*Int*/   a->"rt.Str.fromJavaStr(Long.toString("+a[0]+"))",
      /*Nat*/   a->"rt.Str.fromJavaStr(Long.toUnsignedString("+a[0]+"))",
      /*Byte*/  a->"rt.Str.fromJavaStr(Integer.toString(Byte.toUnsignedInt("+a[0]+")))",
      /*Float*/ a->"rt.Str.fromTrustedUtf8(rt.Str.wrap(rt.NativeRuntime.floatToStr("+a[0]+")))"
    );

    // arithmetic
    put("+", 1, a-> a[0] + " + " + a[1]);
    put("-", 1, a-> a[0] + " - " + a[1]);
    put("*", 1, a-> a[0] + " * " + a[1]);

    put("/", 1,
      /*Int*/   a-> a[0] + " / " + a[1],
      /*Nat*/   a-> "Long.divideUnsigned(" + a[0] + "," + a[1] + ")",
      /*Byte*/  a-> "Long.divideUnsigned(" + a[0] + "," + a[1] + ")",
      /*Float*/ a-> a[0] + " / " + a[1]
    );
    put("%", 1,
      /*Int*/   a-> a[0] + " % " + a[1],
      /*Nat*/   a-> "Long.remainderUnsigned(" + a[0] + "," + a[1] + ")",
      /*Byte*/  a-> "Long.remainderUnsigned(" + a[0] + "," + a[1] + ")",
      /*Float*/ a-> a[0] + " % " + a[1]
    );
    put("**", 1,
      /*Int*/   a -> "rt.Numbers.pow64(" + a[0] + ", " + a[1] + ")",
      /*Nat*/   a -> "rt.Numbers.pow64(" + a[0] + ", " + a[1] + ")",
      /*Byte*/  a -> "rt.Numbers.pow8("  + a[0] + ", " + a[1] + ")",
      /*Float*/ a -> "Math.pow("      + a[0] + ", " + a[1] + ")"
      );
    put(".abs", 0,
      /*Int*/   a-> "Math.abs(" + a[0] + ")",
      /*Nat*/   a-> a[0],
      /*Byte*/  a-> a[0],
      /*Float*/ a-> "Math.abs(" + a[0] + ")"
    );
    put(".sqrt", 0,
        /*Int*/   a -> "rt.Numbers.intSqrt("  + a[0] + ")",
        /*Nat*/   a -> "rt.Numbers.natSqrt("  + a[0] + ")",
        /*Byte*/  a -> "rt.Numbers.byteSqrt(" + a[0] + ")",
        /*Float*/ a -> "Math.sqrt("        + a[0] + ")"
      );
    
    put(".shiftRight", 1,
      /*Int*/   a-> a[0] + " >> "  + a[1],
      /*Nat*/   a-> a[0] + " >>> " + a[1],
      /*Byte*/  a-> "Byte.toUnsignedInt(%s)>>Byte.toUnsignedInt(%s)".formatted(a[0], a[1]),
      /*Float*/ errOp
    );
    put(".shiftLeft", 1,
      /*Int*/   a-> a[0] + " << "  + a[1],
      /*Nat*/   a-> a[0] + " << " + a[1], //BUG: Was "<<<" but "<<<" does not exists in Java!
      /*Byte*/  a-> "Byte.toUnsignedInt(%s)<<Byte.toUnsignedInt(%s)".formatted(a[0], a[1]),
      /*Float*/ errOp
    );
    put(".xor", 1,
      /*Int*/   a-> a[0] + " ^ " + a[1],
      /*Nat*/   a-> a[0] + " ^ " + a[1],
      /*Byte*/  a-> "Byte.toUnsignedInt(%s) ^ Byte.toUnsignedInt(%s)".formatted(a[0], a[1]),
      /*Float*/ errOp
    );
    put(".bitwiseAnd", 1,
      /*Int*/   a-> a[0] + " & " + a[1],
      /*Nat*/   a-> a[0] + " & " + a[1],
      /*Byte*/  a-> "Byte.toUnsignedInt(%s) & Byte.toUnsignedInt(%s)".formatted(a[0], a[1]),
      /*Float*/ errOp
    );
    put(".bitwiseOr", 1,
      /*Int*/   a-> a[0] + " | " + a[1],
      /*Nat*/   a-> a[0] + " | " + a[1],
      /*Byte*/  a-> "Byte.toUnsignedInt(%s) | Byte.toUnsignedInt(%s)".formatted(a[0], a[1]),
      /*Float*/ errOp
    );

    // comparisons
    put(">", 1,
      /*Int*/   a -> "rt.Numbers.toBool(" + a[0] + ">"  + a[1] + ")",
      /*Nat*/   a -> "rt.Numbers.toBool(Long.compareUnsigned(" + a[0] + "," + a[1] + ")>0)",
      /*Byte*/  a -> "rt.Numbers.toBool(Byte.compareUnsigned("  + a[0] + "," + a[1] + ")>0)",
      /*Float*/ a -> "rt.Numbers.toBool(" + a[0] + ">"  + a[1] + ")"
    );
    put("<", 1,
      /*Int*/   a -> "rt.Numbers.toBool(" + a[0] + "<"  + a[1] + ")",
      /*Nat*/   a -> "rt.Numbers.toBool(Long.compareUnsigned(" + a[0] + "," + a[1] + ")<0)",
      /*Byte*/  a -> "rt.Numbers.toBool(Byte.compareUnsigned("  + a[0] + "," + a[1] + ")<0)",
      /*Float*/ a -> "rt.Numbers.toBool(" + a[0] + "<"  + a[1] + ")"
    );
    put(">=", 1,
      /*Int*/   a -> "rt.Numbers.toBool(" + a[0] + ">=" + a[1] + ")",
      /*Nat*/   a -> "rt.Numbers.toBool(Long.compareUnsigned(" + a[0] + "," + a[1] + ")>=0)",
      /*Byte*/  a -> "rt.Numbers.toBool(Byte.compareUnsigned("  + a[0] + "," + a[1] + ")>=0)",
      /*Float*/ a -> "rt.Numbers.toBool(" + a[0] + ">=" + a[1] + ")"
    );
    put("<=", 1,
      /*Int*/   a -> "rt.Numbers.toBool(" + a[0] + "<=" + a[1] + ")",
      /*Nat*/   a -> "rt.Numbers.toBool(Long.compareUnsigned(" + a[0] + "," + a[1] + ")<=0)",
      /*Byte*/  a -> "rt.Numbers.toBool(Byte.compareUnsigned("  + a[0] + "," + a[1] + ")<=0)",
      /*Float*/ a -> "rt.Numbers.toBool(" + a[0] + "<=" + a[1] + ")"
    );
    put("==", 1,
      /*Int*/   a -> "rt.Numbers.toBool(" + a[0] + "==" + a[1] + ")",
      /*Nat*/   a -> "rt.Numbers.toBool(Long.compareUnsigned(" + a[0] + "," + a[1] + ")==0)",
      /*Byte*/  a -> "rt.Numbers.toBool(Byte.compareUnsigned("  + a[0] + "," + a[1] + ")==0)",
      /*Float*/ a -> "rt.Numbers.toBool(" + a[0] + "==" + a[1] + ")"
    );
    put("!=", 1,
      /*Int*/   a -> "rt.Numbers.toBool(" + a[0] + "!=" + a[1] + ")",
      /*Nat*/   a -> "rt.Numbers.toBool(Long.compareUnsigned(" + a[0] + "," + a[1] + ")!=0)",
      /*Byte*/  a -> "rt.Numbers.toBool(Byte.compareUnsigned("  + a[0] + "," + a[1] + ")!=0)",
      /*Float*/ a -> "rt.Numbers.toBool(" + a[0] + "!=" + a[1] + ")"
    );

    // float predicates
    putFloat(".isNaN", 0,a -> "rt.Numbers.toBool(Double.isNaN(" + a[0] + "))");
    putFloat(".isInfinite", 0, a -> "rt.Numbers.toBool(Double.isInfinite(" + a[0] + "))");
    putFloat(".isPosInfinity", 0,a -> "rt.Numbers.toBool(" + a[0] + " == Double.POSITIVE_INFINITY)");
    putFloat(".isNegInfinity", 0,a -> "rt.Numbers.toBool(" + a[0] + " == Double.NEGATIVE_INFINITY)");

    // asserts & hash
    put(".assertEq", 1,
      /*Int*/   a-> "base._IntAssertionHelper_0.assertEq$imm$fun("   + a[0] + ", " + a[1] + ", null)",
      /*Nat*/   a-> "base._NatAssertionHelper_0.assertEq$imm$fun("   + a[0] + ", " + a[1] + ", null)",
      /*Byte*/  a-> "base._ByteAssertionHelper_0.assertEq$imm$fun("  + a[0] + ", " + a[1] + ", null)",
      /*Float*/ a-> "base._FloatAssertionHelper_0.assertEq$imm$fun(" + a[0] + ", " + a[1] + ", null)"
    );
    put(".assertEq", 2,//TODO: should those be in rt? what is the null?
      /*Int*/   a-> "base._IntAssertionHelper_0.assertEq$imm$fun("   + a[0] + ", " + a[1] + ", " + a[2] + ", null)",
      /*Nat*/   a-> "base._NatAssertionHelper_0.assertEq$imm$fun("   + a[0] + ", " + a[1] + ", " + a[2] + ", null)",
      /*Byte*/  a-> "base._ByteAssertionHelper_0.assertEq$imm$fun("  + a[0] + ", " + a[1] + ", " + a[2] + ", null)",
      /*Float*/ a-> "base._FloatAssertionHelper_0.assertEq$imm$fun(" + a[0] + ", " + a[1] + ", " + a[2] + ", null)"
    );
    put(".hash", 1,
      /*Int*/   a-> a[1] + ".int$mut("  + a[0] + ")",
      /*Nat*/   a-> a[1] + ".int$mut("  + a[0] + ")",
      /*Byte*/  a-> a[1] + ".byte$mut(" + a[0] + ")",
      /*Float*/ a-> a[1] + ".int$mut("  + a[0] + ")"
    );

    // offset //TODO: why only for nat and byte? what offset does?
    put(".offset", 1,
      /*Int*/   errOp,
      /*Nat*/   a-> a[0] + " + " + a[1],
      /*Byte*/  a-> "((byte)"+ "Byte.toUnsignedInt(%s)".formatted(a[0]) + " + " + a[1] + ")",
      /*Float*/ errOp
    );
    
    // float-only extras
    putFloat(".round", 0, a-> "Math.round(" + a[0] + ")");
    put(".ceil", 0,a-> "Math.ceil(" + a[0] + ")" );
    put(".floor", 0,a-> "Math.floor(" + a[0] + ")" );
  }
}
public record JavaMagicImpls(
    MIRVisitor<String> gen,
    Function<MIR.MT,String> getTName,
    ast.Program p) implements magic.MagicImpls<String> {
  @Override public MagicTrait<MIR.E,String> int_(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var lit = getLiteral(p, name);
        try {
          return lit
            .map(lambdaName->lambdaName.startsWith("+") ? lambdaName.substring(1) : lambdaName)
            .map(lambdaName->Long.parseLong(lambdaName.replace("_", ""), 10)+"L")
            .orElseGet(()->"((long)"+e.accept(gen, true)+")").describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Int");
        }
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.of(NumOps.emitInt(m, NumOps.callArgs(this, args, gen)));
        //return Optional.ofNullable(_call(m, args));//TODO: why this was the only call using .ofNullable instead of .of?
      }
    };
  }
  @Override public MagicTrait<MIR.E,String> nat(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var lit = getLiteral(p, name);
        try {
          return lit
            .map(lambdaName->Long.parseUnsignedLong(lambdaName.replace("_", ""), 10)+"L")
            .orElseGet(()->"((long)"+e.accept(gen, true)+")").describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Nat");
        }
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT){
        return Optional.of(NumOps.emitNat(m, NumOps.callArgs(this, args, gen)));
        //return Optional.of(_call(m, args));
      }
    };
  }
  @Override public MagicTrait<MIR.E,String> float_(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return new MagicTrait<>() {

      @Override public Optional<String> instantiate() {
        var lit = getLiteral(p, name);
        try {
          return lit
            .map(lambdaName->Double.parseDouble(lambdaName.replace("_", ""))+"d")
            .orElseGet(()->"((double)"+e.accept(gen, true)+")").describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Float");
        }
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT){
        return Optional.of(NumOps.emitFloat(m, NumOps.callArgs(this, args, gen)));
      }
    };
  }
  @Override public MagicTrait<MIR.E,String> byte_(MIR.E e) {
    var name = e.t().name().orElseThrow();
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        var lit = getLiteral(p, name);
        try {
          // Parse bytes as a long because Fearless bytes are u8 (unsigned), Java bytes are i8 (signed).
          return lit
            .map(lambdaName->"((byte)"+Long.parseUnsignedLong(lambdaName.replace("_", ""), 10)+")")
            .orElseGet(()->"((byte)"+e.accept(gen, true)+")").describeConstable();
        } catch (NumberFormatException ignored) {
          throw Fail.invalidNum(lit.orElse(name.toString()), "Byte");
        }
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT){
        return Optional.of(NumOps.emitByte(m, NumOps.callArgs(this, args, gen)));
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
    return "rt.UTF8.$self"::describeConstable;
  }

  @Override public MagicTrait<MIR.E, String> utf16(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }
      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(".fromCodePoint", 1))) {
          return "rt.Str.fromJavaStr(new String(new int[]{(int)(long)%s}, 0, 1))"
            .formatted(args.getFirst().accept(gen, true))
            .describeConstable();
        }
        if (m.equals(new Id.MethName(".fromSurrogatePair", 2))) {
          return "rt.Str.fromJavaStr(new String(new int[]{(int)(long)%s, (int)(long)%s}, 0, 2))"
            .formatted(args.get(0).accept(gen, true), args.get(1).accept(gen, true))
            .describeConstable();
        }
        return MagicTrait.super.call(m, args, variants, expectedT);
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

  @Override public MagicTrait<MIR.E,String> debug(MIR.E e) {
    return new MagicTrait<>() {

      @Override public Optional<String> instantiate() {
        return Optional.of("rt.Debug.$self");
      }

      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> cheapHash(MIR.E e) {
    return "new rt.CheapHash()"::describeConstable;
  }

  @Override public MagicTrait<MIR.E, String> regexK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() { return Optional.empty(); }
      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), "#", 1))) {
          return Optional.of("new rt.Regex(%s)".formatted(args.getFirst().accept(gen, true)));
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
          return Optional.of(String.format("new rt.Var(%s)", x.accept(gen, true)));
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> isoPodK(MIR.E e) {
    return new MagicTrait<>() {

      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }

      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), "#", 1))) {
          MIR.E x = args.getFirst();
          return Optional.of(String.format("""
            new base._MagicIsoPodImpl_1(){
              private Object x = %s;
              private boolean isAlive = true;

              public base.Bool_0 isAlive$read() { return this.isAlive ? base.True_0.$self : base.False_0.$self; }
              public Object peek$read(base.IsoViewer_2 f) { return this.isAlive ? ((base.IsoViewer_2)f).some$mut(this.x) : ((base.IsoViewer_2)f).empty$mut(); }
              public Object $exclamation$mut() {
                if (!this.isAlive) {
                  base.Error_0.$self.msg$imm(rt.Str.fromJavaStr("Cannot consume an empty IsoPod."));
                  return null;
                }
                this.isAlive = false;
                return this.x;
              }
              public base.Void_0 next$mut(Object x) { this.isAlive = true; this.x = x; return new base.Void_0(){}; }
            }
            """, x.accept(gen, true)));
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> assert_(MIR.E e) {
    return new MagicTrait<>() {

      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }

      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName("._fail", 0))) {
          return Optional.of("""
            (switch (1) { default -> {
              System.err.println("Assertion failed :(");
              System.exit(1);
              yield %s;
            }})
            """.formatted(getJavaRet(expectedT)));
        }
        if (m.equals(new Id.MethName("._fail", 1))) {
          return Optional.of(String.format("""
            (switch (1) { default -> {
              rt.NativeRuntime.printlnErr(%s.utf8());
              System.exit(1);
              yield %s;
            }})
            """, args.getFirst().accept(gen, true), getJavaRet(expectedT)));
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
          return Optional.of("rt.Error.throwFearlessError(%s)".formatted(args.getFirst().accept(gen, true)));
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> tryCatch(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {
        return Optional.of("rt.Try.$self");
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> listK(MIR.E e) {
    return ()->Optional.of("rt.ListK.$self");
  }

  @Override public MagicTrait<MIR.E, String> uListK(MIR.E e) {
    return ()->Optional.of("rt.UListK.$self");
  }

  @Override public MagicTrait<MIR.E, String> mapK(MIR.E e) {
    return new MagicTrait<>() {
      @Override public Optional<String> instantiate() {return Optional.empty();}
      @Override
      public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName(Optional.of(Mdf.imm), ".hashMap", 2))) {
          return "new rt.LinkedHashMap(%s,%s)"
            .formatted(args.getFirst().accept(gen, true), args.get(1).accept(gen, true))
            .describeConstable();
        }
        return MagicTrait.super.call(m, args, variants, expectedT);
      }
    };
  }

  @Override public MagicTrait<MIR.E, String> flowRange(MIR.E e) {
    return ()->Optional.of("rt.flows.Range.$self");
  }

  @Override public MagicTrait<MIR.E,String> pipelineParallelSinkK(MIR.E e) {
    return ()->Optional.of("rt.flows.pipelineParallel.PipelineParallelFlow.WrappedSinkK.$self");
  }

  @Override public MagicTrait<MIR.E, String> dataParallelFlowK(MIR.E e) {
    return ()->Optional.of("rt.flows.dataParallel.DataParallelFlowK.$self");
  }

  @Override public MagicCallable<MIR.E,String> variantCall(MIR.E e) {
    return (m, args, variants, _)->{
      var call = (MIR.MCall) e;
      assert call.variant() == variants; // TODO: if this holds remove the variants param here
      var parallelConstr = FlowSelector.bestParallelConstr(call);

      if (isMagic(Magic.FlowK, call.recv())) {
        if (m.name().equals("#") || m.name().equals(".ofIso")) {
          var listKCall = new MIR.MCall(
            new MIR.CreateObj(Mdf.imm, Magic.FListK),
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
        if (m.name().equals(".range")) {
          assert parallelConstr.isPresent();
          return "rt.flows.FlowCreator.fromFlow(%s, %s)".formatted(
            gen.visitCreateObj(new MIR.CreateObj(Mdf.imm, Magic.DataParallelFlowK), true),
            call.withVariants(EnumSet.of(MIR.MCall.CallVariant.Standard)).accept(gen, true)
          ).describeConstable();
        }
        if (m.name().equals(".ofIsos")) {
          assert parallelConstr.isPresent();
          return "rt.flows.FlowCreator.fromFlow(%s, %s)".formatted(
            gen.visitCreateObj(new MIR.CreateObj(Mdf.imm, Magic.DataParallelFlowK), true),
            call.withVariants(EnumSet.of(MIR.MCall.CallVariant.Standard)).accept(gen, true)
          ).describeConstable();
        }
      }

      if (variants.contains(MIR.MCall.CallVariant.SafeMutSourceFlow)) {
        if (isMagic(Magic.UList, call.recv())) {
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
          var flowMethName = StringIds.$self.getMName(call.mdf(), call.name());
          var argList = args.stream()
            .map(arg->arg.accept(gen, true))
            .collect(Collectors.joining(", "));
          return "rt.flows.FlowCreator.fromFlow(%s, %s.%s(%s))".formatted(
            gen.visitCreateObj(new MIR.CreateObj(Mdf.imm, parallelConstr.get()), true),
            call.recv().accept(gen, true),
            flowMethName,
            argList
          ).describeConstable();
        }
      }
//TODO: can this commented code go?
//      if (isMagic(Magic.SafeFlowSource, call.recv())) {
//        if (parallelConstr.isPresent()) {
//          String parFlow = gen.visitMCall(new MIR.MCall(
//            new MIR.CreateObj(Mdf.imm, parallelConstr.get()),
//            new Id.MethName(Optional.of(Mdf.imm), ".fromOp", 2),
//            List.of(
//              new MIR.MCall(
//                new MIR.CreateObj(Mdf.imm, Magic.SafeFlowSource),
//                new Id.MethName(Optional.of(Mdf.imm), m.name()+"'", 1),
//                args,
//                new MIR.MT.Plain(Mdf.mut, Magic.FlowOp),
//                Mdf.imm,
//                EnumSet.of(MIR.MCall.CallVariant.Standard)
//              ),
//              new MIR.CreateObj(Mdf.imm, new Id.DecId("base.Opt", 1)) // TODO: list size
//            ),
//            new MIR.MT.Plain(Mdf.mut, new Id.DecId("base.flows.Flow", 1)),
//            Mdf.imm,
//            variants
//          ), true);
//          return Optional.of(parFlow);
//        }
//      }
      return Optional.empty();
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
            (switch (1) { default -> {
              System.err.println("Program aborted at:\\n"+java.util.Arrays.stream(Thread.currentThread().getStackTrace()).map(StackTraceElement::toString).collect(java.util.stream.Collectors.joining("\\n")));
              System.exit(1);
              yield %s;
            }})
            """.formatted(getJavaRet(expectedT)));
        }
        return Optional.empty();
      }
    };
  }

  @Override public MagicTrait<MIR.E,String> magicAbort(MIR.E e) {
    return new MagicTrait<>() {

      @Override public Optional<String> instantiate() {
        return Optional.empty();
      }
      @Override public Optional<String> call(Id.MethName m, List<? extends MIR.E> args, EnumSet<MIR.MCall.CallVariant> variants, MIR.MT expectedT) {
        if (m.equals(new Id.MethName("!", 0))) {
          return Optional.of("""
            (switch (1) { default -> {
              System.err.println("No magic code was found at:\\n"+java.util.Arrays.stream(Thread.currentThread().getStackTrace()).map(StackTraceElement::toString).collect(java.util.stream.Collectors.joining("\\n")));
              System.exit(1);
              yield %s;
            }})
            """.formatted(getJavaRet(expectedT)));
        }
        return Optional.empty();
      }
    };
  }

  /** These are specialised for internal Fearless benchmarking, expect weird results if this is not the case. */
  @Override public MagicTrait<MIR.E, String> blackBox(MIR.E e) {
    return "rt.BlackBox.$self"::describeConstable;
  }

  private String getJavaRet(MIR.MT expectedT) {
    String ret = getTName.apply(expectedT);
    return switch (ret) {
      default -> "(%s) null".formatted(ret);
      case "Long", "long", "Double", "double" -> "(%s) 0".formatted(ret);
    };
  }
}