package codegen.js;

import codegen.MIR;
import id.Id;
import utils.Box;
import utils.Bug;
import utils.Streams;
import visitors.MIRVisitor;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import magic.FearlessStringHandler;
import magic.Magic;

import static magic.Magic.getLiteral;
public class JsCodegen implements MIRVisitor<String> {
  protected final MIR.Program p;
  private final JsMagicImpls magic;
  protected final Map<MIR.FName, MIR.Fun> funMap;
  public final StringIds id = new StringIds();
  public final HashMap<Id.DecId, String> freshImpls = new HashMap<>();
  public final HashMap<String, String> freshSingletons = new HashMap<>();

  public JsCodegen(MIR.Program p) {
    this.p = p;
    this.magic = new JsMagicImpls(this, p.p());
    this.funMap = p.pkgs().stream()
      .flatMap(pkg->pkg.funs().stream())
      .collect(Collectors.toMap(MIR.Fun::name, f->f));
  }

  public String visitProgram(Id.DecId entry){ throw Bug.unreachable(); }
  public String visitPackage(MIR.Package pkg){ throw Bug.unreachable(); }

  public boolean isLiteral(Id.DecId d) {
    return id.getLiteral(p.p(), d).isPresent();
  }

  public String visitTypeDef(String pkg, MIR.TypeDef def, List<MIR.Fun> funs) {
    var isMagic = pkg.equals("base")
      && def.name().name().endsWith("Instance");
    var isLiteral= isLiteral(def.name());
    if (isMagic || isLiteral) { return ""; }

    String className = id.getFullName(def.name());

    // Singleton
    if (def.singletonInstance().isPresent()) {
      MIR.CreateObj singletonObj = def.singletonInstance().get();
      String implExpr = visitCreateObjNoSingleton(singletonObj, true); // triggers Impl generation
      freshSingletons.put(className, "%s.$self = %s;".formatted(className, implExpr));  // place it after Impl, because we cannot reference a class before its declaration in the same module
    }
    // Static methods
    String staticFuns = "";
    if (!funs.isEmpty()) {
      staticFuns = "\n  " + funs.stream().map(this::visitFun).collect(Collectors.joining("\n  "));
    }
    return """
    export class %s {%s
    }""".formatted(className, staticFuns);
  }

  public String visitCreateObjNoSingleton(MIR.CreateObj createObj, boolean checkMagic) {
    Id.DecId typeId = createObj.concreteT().id();
    String implName = id.getFullName(typeId) + "Impl";
    // Already generated?
    if (!freshImpls.containsKey(typeId)) {
      addFreshImpl(typeId, createObj, implName);
    }
    // Create JS instance expression
    String captures = createObj.captures().stream()
      .map(x -> visitX(x, checkMagic))
      .collect(Collectors.joining(", "));
    return "new " + implName + "(" + captures + ")";
  }

  private String getConstructor(MIR.CreateObj obj) {
    String constructor = "";
    if (!obj.captures().isEmpty()) {
      String args = obj.captures().stream()
        .map(x -> id.varName(x.name()))
        .collect(Collectors.joining(", "));
      String assigns = Arrays.stream(args.split(", "))
        .map(a -> "this." + a + " = " + a + ";")
        .collect(Collectors.joining("\n    "));
      constructor = """
          constructor(%s) {
            %s
          }
        """.formatted(args, assigns);
    }
    return constructor;
  }
  private void addFreshImpl(Id.DecId typeId, MIR.CreateObj obj, String implName) {
    if (freshImpls.containsKey(typeId)) return;
    // Constructor for captured fields
    String constructor = getConstructor(obj);
    // Instance methods
    String instanceMeths = obj.meths().isEmpty() ? "" : "  " +
      obj.meths().stream().map(this::visitMeth).collect(Collectors.joining("\n  "))
      + "\n";
    // Unreachable methods
    String unreachableMeths = obj.unreachableMs().isEmpty() ? "" : "  " +
      obj.unreachableMs().stream().map(this::visitMeth).collect(Collectors.joining("\n  "))
      + "\n";
    String implClass = """
        export class %s {
        %s%s%s}
        """.formatted(implName, constructor, instanceMeths, unreachableMeths);
    freshImpls.put(typeId, implClass);
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
    Id.DecId objId = createObj.concreteT().id();
    var className = getName(objId);
    var singleton= p.of(objId).singletonInstance().isPresent();
    if (singleton) {
      return className + ".$self";
    }
    return visitCreateObjNoSingleton(createObj, checkMagic);
  }

  public <T> String seq(Collection<T> es, Function<T, String> f, String join) {
    return seq(es.stream(), f, join);
  }

  public <T> String seq(Stream<T> s, Function<T, String> f, String join) {
    return s.map(f).collect(Collectors.joining(join));
  }

  // Create JS static method
  public String visitFun(MIR.Fun fun) {
    String funName = getFName(fun.name(), fun.args().size());
    String args = seq(fun.args(), x -> id.varName(x.name()), ", ");
    String bodyStr = fun.body().accept(this, true);
    String maybeReturn = (fun.body() instanceof MIR.Block) ? "" : "return ";

    return String.format("""
      static %s(%s) {
          %s%s;
        }""", funName, args, maybeReturn, bodyStr);
  }

  // Create JS instance method that forwards to static method
  public String visitMeth(MIR.Meth meth) {
    List<String> args = meth.sig().xs().stream()
      .map(x -> id.varName(x.name()))
      .toList();
    String argsStr = String.join(", ", args);
    String methName = id.getMName(meth.sig().mdf(), meth.sig().name(), args.size());
    if (meth.fName().isEmpty()) {
      return "";
    }
    String className = id.getFullName(meth.origin());
    List<String> funArgs = Streams.of(
      meth.sig().xs().stream().map(MIR.X::name).map(id::varName),
      Stream.of("this"),
      meth.captures().stream().map(id::varName).map(x -> "this." + x)
    ).toList();
    String funArgsStr = String.join(", ", funArgs);
    String funName = getFName(meth.fName().orElseThrow(), funArgs.size());
    // Forward all args using ...args and append this
    return String.format("%s(%s) { return %s.%s(%s); }", methName, argsStr, className, funName, funArgsStr);
  }

  @Override
  public String visitBlockExpr(MIR.Block expr, boolean checkMagic) {
    return visitBlockExpr(expr, BlockReturnKind.RETURN);
  }

  private enum BlockReturnKind {
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
    var doIdx = new Box<>(0); // Mutable int wrapper

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
        // Return: always emit `return <expr>;`. In JavaScript, yield is a generator keyword.
        "return " + ret.e().accept(this, true);
      case MIR.Block.BlockStmt.Do do_ ->
        // JS: var doRes0 = <expr>;
        "var doRes%d = %s;\n".formatted(doIdx.update(n -> n + 1), do_.e().accept(this, true));
      case MIR.Block.BlockStmt.Throw throw_ ->
        "r$$Error.throwFearlessError(%s);\n".formatted(throw_.e().accept(this, true));
      case MIR.Block.BlockStmt.Loop loop ->
        """
        while (true) {
          var res = %s.$hash$mut$0();
          if (res == base$$ControlFlowContinue_0.$self || res == base$$ControlFlowContinue_1.$self) { continue; }
          if (res == base$$ControlFlowBreak_0.$self || res == base$$ControlFlowBreak_1.$self) { break; }
          if (res instanceof base$$ControlFlowReturn_1) { base$$ControlFlowReturn_1.$self.value$mut$0(); }
        }
        """.formatted(
          loop.e().accept(this, true)
        );
      case MIR.Block.BlockStmt.If if_ -> {
        // JS: if (<pred> == base$$True_0.$self) { ... }
        var body = this.visitBlockStmt(expr, stmts, doIdx, returnKind);
        if (body.startsWith(returnKind.toString())) {
          body += ";";
        }
        yield """
        if (%s == base$$True_0.$self) { %s }
        """.formatted(if_.e().accept(this, true), body);
      }
      case MIR.Block.BlockStmt.Let let -> "let %s = %s;\n"
        .formatted(id.varName(let.name()), let.value().accept(this, true));
      case MIR.Block.BlockStmt.Var var -> "var %s = base$$Vars_0.$self.$hash$imm$1(%s);\n"
        .formatted(id.varName(var.name()), var.value().accept(this, true));
    };
  }

  // Visit boolean expressions
  @Override public String visitBoolExpr(MIR.BoolExpr expr, boolean checkMagic) {
    String recv = expr.condition().accept(this, checkMagic);

    // No casts needed in JS
    String thenBody = switch (this.funMap.get(expr.then()).body()) {
      case MIR.Block b -> this.inlineBlock(b);
      case MIR.E e -> e.accept(this, checkMagic);
    };
    String elseBody = switch (this.funMap.get(expr.else_()).body()) {
      case MIR.Block b -> this.inlineBlock(b);
      case MIR.E e -> e.accept(this, checkMagic);
    };

    // JS ternary expression
    return "(%s == base$$True_0.$self ? %s : %s)".formatted(recv, thenBody, elseBody);
  }

  // Wrap a block expression (used when a block is used as an expression).
  // Use an IIFE returning the value so the expression evaluates immediately to that value.
  private String inlineBlock(MIR.Block block) {
    String blockCode = visitBlockExpr(block, BlockReturnKind.YIELD);
    return "(() => {\n" + blockCode + "})()";
  }

  public String visitStringLiteral(MIR.CreateObj k) {
    var id = k.concreteT().id();
    var fearlessStr = getLiteral(p.p(), id).orElseThrow();
    var jsStr = new FearlessStringHandler(FearlessStringHandler.StringKind.Unicode)
      .toJavaString(fearlessStr)
      .get();
    // JSON-style escape ensures proper quoting
    var escaped = jsonEscape(jsStr);

    return switch (k.t().mdf()) {
      case mut, iso -> "new rt$$MutStr(" + escaped + ")";
      default -> "rt$$Str.fromJsStr(" + escaped + ")";
    };
  }


  public static String jsonEscape(String s) {
    return "\"" + s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      .replace("\b", "\\b")
      .replace("\f", "\\f") + "\"";
  }

  @Override public String visitX(MIR.X x, boolean checkMagic) {
    return id.varName(x.name());
  }

  public String getName(Id.DecId d) {
    return id.getFullName(d);
  }

  @Override public String visitMCall(MIR.MCall call, boolean checkMagic) {
    if (checkMagic && !call.variant().contains(MIR.MCall.CallVariant.Standard)) {
      var impl = magic.variantCall(call).call(call.name(), call.args(), call.variant(), call.t());
      if (impl.isPresent()) return impl.get();
    }

    var magicImpl = magic.get(call.recv());
    if (checkMagic && magicImpl.isPresent()) {
      var impl = magicImpl.get().call(call.name(), call.args(), call.variant(), call.t());
      if (impl.isPresent()) return impl.get();
    }

    var args = call.args().stream()
      .map(a -> a.accept(this, checkMagic))
      .collect(Collectors.joining(","));

    return "%s.%s(%s)".formatted(
      call.recv().accept(this, checkMagic),
      id.getMName(call.mdf(), call.name(), call.args().size()),
      args
    );
  }
  public String getFName(MIR.FName name, int arity) {
    String methName = id.getMName(name.mdf(), name.m(), arity);
    return methName + "$fun";
  }
}