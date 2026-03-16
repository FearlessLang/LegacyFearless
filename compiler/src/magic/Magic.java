package magic;

import ast.Program;
import failure.CompileError;
import id.Id;
import visitors.FullEAntlrVisitor;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import ast.E.Lambda;
import ast.E.Lambda.LambdaId;
import ast.T.Dec;

public class Magic {
  public static final Id.DecId Main = new Id.DecId("base.Main", 0);
  public static final Id.DecId Sealed = new Id.DecId("base.Sealed", 0);
  public static final Id.DecId Nat = new Id.DecId("base.Nat", 0);
  public static final Id.DecId Int = new Id.DecId("base.Int", 0);
  public static final Id.DecId Bool = new Id.DecId("base.Bool", 0);
  public static final Id.DecId Float = new Id.DecId("base.Float", 0);
  public static final Id.DecId Byte = new Id.DecId("base.Byte", 0);
  public static final Id.DecId Str = new Id.DecId("base.Str", 0);
  public static final Id.DecId UTF8 = new Id.DecId("base.UTF8", 0);
  public static final Id.DecId UTF16 = new Id.DecId("base.UTF16", 0);
  public static final Id.DecId AsciiStr = new Id.DecId("base.AsciiStr", 0);
  public static final Id.DecId Debug = new Id.DecId("base.Debug", 0);
  public static final Id.DecId CheapHash = new Id.DecId("base.CheapHash", 0);
  public static final Id.DecId RegexK = new Id.DecId("base.Regexs", 0);
  public static final Id.DecId BlackBox = new Id.DecId("base.benchmarking.BlackBox", 0);

  public static final Id.DecId BlockK = new Id.DecId("base.Block", 0);
  public static final Id.DecId Block = new Id.DecId("base.Block", 1);
  public static final Id.DecId ReturnStmt = new Id.DecId("base.ReturnStmt", 1);
  public static final Id.DecId Condition = new Id.DecId("base.Condition", 0);
  public static final Id.DecId Continuation = new Id.DecId("base.Continuation", 3);


  public static final Id.DecId RefK = new Id.DecId("base._MagicVarImpl", 0);
  public static final Id.DecId IsoPodK = new Id.DecId("base._MagicIsoPodImpl", 0);
  public static final Id.DecId Assert = new Id.DecId("base.Assert", 0);
  public static final Id.DecId Abort = new Id.DecId("base.Abort", 0);
  public static final Id.DecId MagicAbort = new Id.DecId("base.Magic", 0);
  public static final Id.DecId ErrorK = new Id.DecId("base.Error", 0);
  public static final Id.DecId Try = new Id.DecId("base.Try", 0);
  public static final Id.DecId CapTryK = new Id.DecId("base.caps.CapTrys", 0);

  public static final Id.DecId FlowK = new Id.DecId("base.flows.Flow", 0);
  public static final Id.DecId FlowOp = new Id.DecId("base.flows.FlowOp", 1);
  public static final Id.DecId FlowRange = new Id.DecId("base.flows._FlowRange", 0);
  public static final Id.DecId PipelineParallelSinkK = new Id.DecId("base.flows._PipelineParallelSink", 0);
  public static final Id.DecId PipelineParallelFlowK = new Id.DecId("rt.flows.pipelineParallel.PipelineParallelFlowK", 0);
  public static final Id.DecId DataParallelFlowK = new Id.DecId("base.flows._DataParallelFlow", 0);
  public static final Id.DecId FList = new Id.DecId("base.List", 1);
  public static final Id.DecId UList = new Id.DecId("base.List", 1);
  public static final Id.DecId ListK = new Id.DecId("base.List", 0);
  public static final Id.DecId LList = new Id.DecId("base.LList", 1);
  public static final Id.DecId SafeFlowSource = new Id.DecId("base.flows._SafeSource", 0);

  public static final Id.DecId MapK = new Id.DecId("base.Maps", 0);
  public static final Id.DecId Document = new Id.DecId("base.Document", 0);
  public static final Id.DecId Documents = new Id.DecId("base.Documents", 0);
  public static final Id.DecId Element = new Id.DecId("base.Element", 0);
  public static final Id.DecId Event = new Id.DecId("base.Event", 0);

  public static astFull.T.Dec getFullDec(Function<Id.DecId, astFull.T.Dec> resolve, Id.DecId id) {
    var base = _getDec(resolve, id);
    return base.map(b -> b.withName(id)).orElse(null);
  }

  public static Dec getDec(Function<Id.DecId, Dec> resolve, Id.DecId id) {
    var base = _getDec(resolve, id);
    return base.map(b -> createMagicTrait(b, id)).orElse(null);
  }

  private static Dec createMagicTrait(Dec b, Id.DecId id) {
    Lambda l = b.lambda();
    LambdaId lid = l.id();
    assert lid.id().name().endsWith("Instance");
    assert l.its().size() == 1 : l;
    // instance, kind   0.5  anon:base._FloatInstance, base.Float
    var its = List.of(lid.toIT(), l.its().getFirst());
    l = l.withId(lid.withId(id)).withITs(its);
    return b.withLambda(l);
  }
  private static String toSimpleName(String fullName){
    var pkg=FullEAntlrVisitor.extractPackageName(fullName);
    assert pkg.length()>1;
    return fullName.substring(pkg.length()+1);
  }
  public static Optional<String> getLiteral(Program p, Id.DecId d){
    return getFullLiteral(p, d).map(Magic::toSimpleName);
  }
  public static Optional<String> getFullLiteral(Program p, Id.DecId d){
    if(LiteralKind.isLiteral(d.name())){ return Optional.of(d.name()); }
    var supers = p.superDecIds(d);
    return supers.stream()
      .filter(dec -> LiteralKind.isLiteral(dec.name()))
      .map(Id.DecId::name)
      .findFirst();
  }

  public static boolean isNakedLiteral(String name) {
    return isStringLiteral(name) || isNumberLiteral(name);
  }

  public static boolean isStringLiteral(String name) {
    return name.startsWith("\"");
  }

  public static boolean isNumberLiteral(String name) {
    return Character.isDigit(name.charAt(0)) || name.startsWith("-") || name.startsWith("+");
  }

  public static Optional<CompileError> validateIfLiteral(Id.DecId id) {
    var kind= LiteralKind.match(id.name());
    if(kind.isEmpty()){ return Optional.empty(); }
    return kind.flatMap(k->k.validate(id.name()).map(Supplier::get));
  }
  static boolean strValidation(String input, char terminator){
    boolean noBorders=input.length() < 2
      || input.charAt(0) != terminator || input.charAt(input.length() - 1) != terminator;
    if (noBorders){ return false; }
    for (int i = 1; i < input.length() - 1; i++) {
      if (input.charAt(i) != terminator){ continue; }
      if (input.charAt(i - 1) != '\\'){ return false; }
    }
    return true;
  }
  private static <T> Optional<T> _getDec(Function<Id.DecId, T> resolve, Id.DecId id) {
    return LiteralKind.match(id.name())
      .map(LiteralKind::toDecId)
      .map(resolve::apply);
  }
}
