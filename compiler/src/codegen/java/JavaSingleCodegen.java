package codegen.java;

import codegen.MIR;
import codegen.MethExprKind;
import codegen.ParentWalker;
import id.Id;
import id.Id.DecId;
import magic.FearlessStringHandler;
import magic.Magic;
import rt.NativeRuntime;
import utils.Box;
import utils.Bug;
import utils.Streams;
import visitors.MIRVisitor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static codegen.MethExprKind.Kind.*;
import static magic.Magic.getLiteral;

public class JavaSingleCodegen implements MIRVisitor<String> {
  protected final MIR.Program p;
  protected final Map<MIR.FName, MIR.Fun> funMap;
  private final JavaMagicImpls magic;
  public final HashMap<DecId, String> freshRecords= new HashMap<>();
  public final StringIds id= new StringIds();
  private String pkg;
  public JavaSingleCodegen(MIR.Program p) {
    magic= new JavaMagicImpls(this, t->getTName(t,false), p.p());
    this.p = p;
    this.funMap = p.pkgs().stream()
      .flatMap(pkg->pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f->f));
  }
  public boolean isLiteral(DecId d) {
    return id.getLiteral(p.p(), d).isPresent();
  }
  public String extendsStr(MIR.TypeDef def,String fullName){
    var its = def.impls().stream()
      .map(MIR.MT.Plain::id)
      .filter(e->!isLiteral(e))
      .map(id::getFullName)
      .filter(qualifiedTypeName->!qualifiedTypeName.equals(fullName)) // don't extend ourselves
      .distinct()
      .sorted()
      .collect(Collectors.joining(","));
      return its.isEmpty() ? "" : " extends "+its;
  }
  public <T> String seq(Collection<T> es, Function<T,String> f, String join){
    return seq(es.stream(),f,join);
  }
  public <T> String seq(Stream<T> s, Function<T,String> f, String join){
    return s.map(f).collect(Collectors.joining(join));
  }
  public String visitTypeDef(String pkg, MIR.TypeDef def, List<MIR.Fun> funs) {
    this.pkg = pkg;
    var isMagic = pkg.equals("base")
      && def.name().name().endsWith("Instance");
    var isLiteral= isLiteral(def.name());
    if (isMagic || isLiteral) { return ""; }

    var fullName = id.getFullName(def.name());
    var shortName = id.getSimpleName(def.name());
    var impls = extendsStr(def,fullName);
    var singletonGet = def.singletonInstance()
      .map(objK->shortName
        +" $self = "
        +visitCreateObjNoSingleton(objK, true)
        +";\n")
      .orElse("");
    var leastSpecific = ParentWalker.leastSpecificSigs(p, def);
    var sigs = seq(def.sigs(),sig->visitSig(sig, leastSpecific),"\n");
    var staticFuns = seq(funs,this::visitFun,"\n");    
    return "public interface "+shortName+impls+"{\n"
      + singletonGet + sigs + staticFuns + "}";
  }
  public String visitSig(MIR.Sig sig, Map<Id.MethName, MIR.Sig> leastSpecific) {
    // If params are different in my parent, we need to objectify
    var overriddenSig= this.overriddenSig(sig, leastSpecific);
    if (overriddenSig.isPresent()) {
      return visitSig(overriddenSig.get(), Map.of());
    }
    var args = seq(sig.xs(),this::typePair,", ");
    return getTName(sig.rt(),true)+" "
      +id.getMName(sig.mdf(), sig.name())+"("+args+");\n";
  }
  private String castX(MIR.X x){
    return "("+getTName(x.t(),false)+") "+id.varName(x.name());
  }
  public String visitMeth(MIR.Meth meth, MethExprKind kind, Map<Id.MethName, MIR.Sig> leastSpecific) {
    var overriddenSig = this.overriddenSig(meth.sig(), leastSpecific);
    var toSkip = overriddenSig.isPresent();
    var deleMeth = meth;
    if (toSkip){
      deleMeth = meth.withSig(overriddenSig.get());
      var canSkip = kind.kind() == Unreachable;
      if (canSkip) {
        return visitMeth(deleMeth, Unreachable, Map.of());
      }
      var d = new Delegator(meth.sig(), deleMeth.sig());
      var delegator = visitMeth(deleMeth, d, Map.of());
      var delegate = visitMeth(meth, Delegate, Map.of());
      return delegator+"\n"+delegate+"\n";
    }

    var nameSuffix = kind.kind() == Delegate ? "$Delegate" : "";
    var methName = id.getMName(meth.sig().mdf(), meth.sig().name())+nameSuffix;
    var args = seq(meth.sig().xs(),this::typePair,", ");
    var funArgs = Streams.of(
      meth.sig().xs().stream().map(MIR.X::name).map(id::varName),
      Stream.of("this"),
      meth.captures().stream().map(id::varName).map(x->"this."+x)
      ).collect(Collectors.joining(", "));
    var mustCast = meth.fName().isPresent() 
      && this.funMap.get(meth.fName().get()).ret() instanceof MIR.MT.Any
      && !(meth.sig().rt() instanceof MIR.MT.Any);
    var cast = mustCast ? "("+getTName(meth.sig().rt(),true)+")" : "";

    var realExpr = switch (kind) {
      case Kind k -> switch (k.kind()) {
        case RealExpr, Delegate -> "return %s %s.%s(%s);".formatted(
          cast,
          id.getFullName(meth.origin()),
          getFName(meth.fName().orElseThrow()),
          funArgs);
        case Unreachable -> "throw new java.lang.Error(\"Unreachable code\");";
        case Delegator -> throw Bug.unreachable();
      };
      case MethExprKind.Delegator k -> "return this.%s(%s);".formatted(
        methName+"$Delegate",seq(k.xs(),this::castX,", ")
        );
    };
    return """
      public %s %s(%s) {
        %s
      }
      """.formatted(
        getTName(meth.sig().rt(),true),
        methName,
        args,
        realExpr);
  }

  public String visitFun(MIR.Fun fun) {
    var name = getFName(fun.name());
    var args = seq(fun.args(),this::typePair,", ");
    var body = fun.body().accept(this, true);
    var ret = fun.body() instanceof MIR.Block ? "" : "return ";
    return """
      static %s %s(%s) {
        %s%s;
      }
      """.formatted(getTName(fun.ret(),true), name, args, ret, body);
  }
  @Override public String visitBlockExpr(MIR.Block expr, boolean checkMagic) {
    return visitBlockExpr(expr, BlockReturnKind.RETURN);
  }

  enum BlockReturnKind {
    RETURN {
      @Override public String toString() {
        return "return";
      }
    },
    YIELD {
      @Override public String toString() {
        return "yield";
      }
    }
  }
  private String visitBlockExpr(MIR.Block expr, BlockReturnKind returnKind) {
    var res = new StringBuilder();
    var stmts = new ArrayDeque<>(expr.stmts());
    var doIdx = new Box<>(0);
    while (!stmts.isEmpty()) {
      res.append(this.visitBlockStmt(expr, stmts, doIdx, returnKind));
    }
    return res.toString();
  }

  private String visitBlockStmt(
      MIR.Block expr,
      ArrayDeque<MIR.Block.BlockStmt> stmts,
      Box<Integer> doIdx,
      BlockReturnKind returnKind
  ) {
    var stmt = stmts.poll();
    assert stmt != null;
    return switch (stmt) {
      case MIR.Block.BlockStmt.Return ret ->
        "%s %s".formatted(returnKind, ret.e().accept(this, true));
      case MIR.Block.BlockStmt.Do do_ ->
        "var doRes%s = %s;\n"
        .formatted(doIdx.update(n->n + 1), do_.e().accept(this, true));
      case MIR.Block.BlockStmt.Throw throw_ ->
        "rt.Error.throwFearlessError(%s);\n".formatted(throw_.e().accept(this, true));
      case MIR.Block.BlockStmt.Loop loop -> """
        while (true) {
          var res = %s.$hash$mut();
          if (res == base.ControlFlowContinue_0.$self || res == base.ControlFlowContinue_1.$self) { continue; }
            if (res == base.ControlFlowBreak_0.$self || res == base.ControlFlowBreak_1.$self) { break; }
            if (res instanceof base.ControlFlowReturn_1 rv) { %s (%s) rv.value$mut(); }
          }
        """.formatted(
        loop.e().accept(this, true),
        returnKind,
        getTName(expr.expectedT(),false));
      case MIR.Block.BlockStmt.If if_ -> {
        var body = this.visitBlockStmt(expr, stmts, doIdx, returnKind);
        if (body.startsWith(returnKind.toString())) { body += ";"; }
        yield """
          if (%s == base.True_0.$self) { %s }
          """.formatted(if_.pred().accept(this, true), body);
      }
      case MIR.Block.BlockStmt.Let let -> "var %s = %s;\n"
        .formatted(id.varName(let.name()), let.value().accept(this, true));
      case MIR.Block.BlockStmt.Var var -> "var %s = base.Vars_0.$self.$hash$imm(%s);\n"
        .formatted(id.varName(var.name()), var.value().accept(this, true));
    };
  }

  @Override public String visitCreateObj(MIR.CreateObj createObj, boolean checkMagic) {
    if (magic.isMagic(Magic.Str, createObj.concreteT().id())) {
      return visitStringLiteral(createObj);
    }

    var magicImpl = magic.get(createObj);
    if (checkMagic && magicImpl.isPresent()) {
      var res = magicImpl.get().instantiate();
      if (res.isPresent()) { return res.get(); }
    }
    var objId = createObj.concreteT().id();
    var singleton= p.of(objId).singletonInstance().isPresent();
    if (singleton){ return id.getFullName(objId)+".$self"; }
    return visitCreateObjNoSingleton(createObj, checkMagic);
  }
  public String visitCreateObjNoSingleton(MIR.CreateObj createObj, boolean checkMagic){
    var name= createObj.concreteT().id();
    var recordName= id.getSimpleName(name)+"Impl";
    addFreshRecord(createObj, name, recordName);
    var captures= seq(createObj.captures(),x->visitX(x, checkMagic),", ");
    return "new "+recordName+"("+captures+")";
  }
  private void addFreshRecord(
      MIR.CreateObj createObj, DecId name, String recordName) {
    if(this.freshRecords.containsKey(name)){ return; }
//    assert !this.freshRecords.containsKey(name):
//      "current "+name+" in \n"+this.freshRecords.keySet();
    var leastSpecific = ParentWalker.leastSpecificSigs(p, p.of(name));
    var args = seq(createObj.captures(),this::typePair,", ");
    var ms = seq(createObj.meths(),
      m->this.visitMeth(m,RealExpr,leastSpecific),"\n");
    var unreachableMs = seq(createObj.unreachableMs(),
      m->this.visitMeth(m, Unreachable, leastSpecific),"\n");
    this.freshRecords.put(name, """
      public record %s(%s) implements %s {
        %s
        %s
      }
      """.formatted(
      recordName, args, id.getFullName(name), ms, unreachableMs));
  }
  public String visitStringLiteral(MIR.CreateObj k) {
    var id = k.concreteT().id();
    var fearlessStr = getLiteral(p.p(), id).orElseThrow();
    // We need to turn this into a java string
    var javaStr = new FearlessStringHandler(FearlessStringHandler.StringKind.Unicode)
      .toJavaString(fearlessStr)
      .get();
    byte[] utf8=javaStr.getBytes(StandardCharsets.UTF_8);
    var buf = ByteBuffer.allocateDirect(utf8.length).put(utf8).position(0).asReadOnlyBuffer();
    var recordName = ("Str$"+Long.toUnsignedString(NativeRuntime.hashString(buf), 10)+"$Str$");
    if (!this.freshRecords.containsKey(id)) {
      var utf8Array = IntStream.range(0, utf8.length).mapToObj(i->Byte.toString(utf8[i])).collect(Collectors.joining(","));
      // We do not need to run validateStringOrThrow because Java will never produce an invalid UTF-8 str with getBytes.
      var graphemes = Arrays.stream(NativeRuntime.indexString(buf)).mapToObj(Integer::toString).collect(Collectors.joining(","));

      this.freshRecords.put(new DecId(this.pkg+"."+recordName, 0), """
        final class %s implements rt.Str {
          public static final rt.Str $self = new %s();
          private static final java.nio.ByteBuffer UTF8 = rt.Str.wrap(new byte[]{%s});
          private static final int[] GRAPHEMES = new int[]{%s};
          @Override public java.nio.ByteBuffer utf8() { return UTF8; }
          @Override public int[] graphemes() { return GRAPHEMES; }
        }
        """.formatted(recordName, recordName, utf8Array, graphemes));
    }

    var createExpr = recordName+".$self";
    return switch (k.t().mdf()) {
      case mut,iso -> "new rt.MutStr("+createExpr+")";
      default -> createExpr;
    };
  }

  @Override public String visitX(MIR.X x, boolean checkMagic) {
    return id.varName(x.name());
  }

  @Override public String visitMCall(MIR.MCall call, boolean checkMagic) {
    var mustCast = !call.t().equals(call.originalRet());
    var cast = mustCast ? "(("+getTName(call.t(),false)+")" : "";

    if (checkMagic && !call.variant().contains(MIR.MCall.CallVariant.Standard)) {
      var impl = magic.variantCall(call).call(call.name(), call.args(), call.variant(), call.t());
      if (impl.isPresent()) { return cast+impl.get()+(mustCast ? ")" : ""); }
    }

    var magicImpl = magic.get(call.recv());
    if (checkMagic && magicImpl.isPresent()) {
      var impl = magicImpl.get()
        .call(call.name(), call.args(), call.variant(), call.t());
      if (impl.isPresent()) { return cast+impl.get()+(mustCast ? ")" : ""); }
    }
    var start = cast
      +call.recv().accept(this, checkMagic)
      +"."+id.getMName(call.mdf(), call.name())+"(";
    var args = seq(call.args(),a->a.accept(this, checkMagic),",");
    return start+args+")"+(mustCast ? ")" : "");   
  }

  @Override public String visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    String recv = expr.condition().accept(this, checkMagic);
    boolean mustCast = !this.funMap.get(expr.then()).ret().equals(this.funMap.get(expr.else_()).ret());
    String cast = mustCast ? "("+getTName(expr.t(),true)+")" : "";

    String thenBody = switch (this.funMap.get(expr.then()).body()) {
      case MIR.Block b -> this.inlineBlock(b);
      case MIR.E e -> e.accept(this, checkMagic);
    };
    String elseBody = switch (this.funMap.get(expr.else_()).body()) {
      case MIR.Block b -> this.inlineBlock(b);
      case MIR.E e -> e.accept(this, checkMagic);
    };
    return "(%s(%s == base.True_0.$self ? %s : %s))".formatted(cast, recv, thenBody, elseBody);
  }

  @Override public String visitUpdatableListAsIdFnCall(MIR.UpdatableListAsIdFnCall call, boolean checkMagic) {
    var recv = call.e().recv().accept(this, checkMagic);
    var f = call.e().args().getFirst().accept(this, checkMagic);
    return "rt.UListK.asShallowClone(%s, %s)".formatted(recv, f);
  }

  private String inlineBlock(MIR.Block block) {
    var blockCode = this.visitBlockExpr(block, BlockReturnKind.YIELD);
    return """
      (switch (1) {default -> {
        %s;
      }})
      """.formatted(blockCode);
  }

  private Optional<MIR.Sig> overriddenSig(MIR.Sig sig, Map<Id.MethName, MIR.Sig> leastSpecific) {
    var leastSpecificSig = leastSpecific.get(sig.name());
    if (leastSpecificSig != null && Streams.zip(sig.xs(),leastSpecificSig.xs()).anyMatch((a,b)->!a.t().equals(b.t()))) {
      return Optional.of(leastSpecificSig.withRT(sig.rt()));
    }
    return Optional.empty();
  }

  private String typePair(MIR.X x) {
    return getTName(x.t(),false)+" "+id.varName(x.name());
  }
  public String getFName(MIR.FName name) {
    return
      //id.getFullName(name.d()).replace(".","$")+"$"+
      id.getMName(name.mdf(), name.m())+"$fun";
  }
  public String getTName(MIR.MT t, boolean isRet) {
    return new TypeIds(magic,id).getTName(t,isRet);
  }
  public String visitProgram(DecId entry){ throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg){ throw Bug.unreachable(); }
}