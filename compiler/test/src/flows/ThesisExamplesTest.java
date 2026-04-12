package flows;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.java.RunJavaProgramTests.ok;
import static utils.RunOutput.Res;

public class ThesisExamplesTest {
  private static final String LLIST = """
    package test
    LList[E:imm,mut,read]: {
      mut .match[R:iso,imm,mut,mutH,read,readH](m: mut LListMatch[E,R]): R -> m.empty,
      read .match[R:iso,imm,mut,mutH,read,readH](m: mut LListMatchRead[E,R]): R -> m.empty,
    
      mut .head: mut Opt[E] -> {},
      read .head: mut Opt[read/imm E] -> {},
    
      mut .tail: mut LList[E] -> this,
      read .tail: read LList[E] -> this,
    
      read .isEmpty: Bool -> True,
    
      mut ++(other: mut LList[E]): mut LList[E] -> other,
      read ++(other: read LList[read/imm E]): read LList[read/imm E] -> other,
    
      mut +(e: E): mut LList[E] -> this ++ (mut LList[E].pushFront(e)),
      read +(e: E): read LList[read/imm E] -> this ++ (read LList[read/imm E].pushFront(e)),
    
      mut .pushFront(e: E): mut LList[E] -> {
        .match(m) -> m.elem(this, e),
        mut .head: mut Opt[E] -> Opts#e,
        read .head: mut Opt[read/imm E] -> Opts#[read/imm E]e,
        .tail -> this,
        ++(other) -> (this ++ other).pushFront(e),
        .isEmpty -> False,
      },
      read .pushFront(e: E): read LList[E] -> {
        .match(m) -> m.elem(this, e),
        .head -> Opts#[read/imm E]e,
        .tail -> this,
        ++(other) -> (this ++ other).pushFront(e),
        .isEmpty -> False,
      },
    
      mut .iter: mut Iter[E] -> Block#
        .var[mut LList[E]] cursor = {this}
        .return {{.next -> cursor.swap(cursor.get.tail).head}},
      read .iter: mut Iter[read/imm E] -> Block#
        .var[read LList[E]] cursor = {this}
        .return {{.next -> cursor.swap(cursor.get.tail).head}},
    
      mut .flowOp: mut FlowOp[E] -> Block#
        .var[mut LList[E]] cursor = {this}
        .return {{'op
          .step(sink) -> cursor.swap(cursor.get.tail).head.match{
            .some(e) -> sink#(e) ? {
              .then -> {},
              .else -> cursor.set({}),
            },
            .empty -> {},
          },
          .for(sink) -> cursor.get.isEmpty.not ? {
            .then -> Block#(op.step(sink), op.for(sink)),
            .else -> {},
          },
        }},
      read .flowOp: mut FlowOp[read/imm E] -> Block#
        .var[read LList[E]] cursor = {this}
        .return {{'op
          .step(sink) -> cursor.swap(cursor.get.tail).head.match{
            .some(e) -> sink#(e) ? {
              .then -> {},
              .else -> cursor.set({}),
            },
            .empty -> {},
          },
          .for(sink) -> cursor.get.isEmpty.not ? {
            .then -> Block#(op.step(sink), op.for(sink)),
            .else -> {},
          },
        }},
    }
    LListMatch[T:imm,mut,read, R:iso,imm,mut,mutH,read,readH]: {
      mut .empty: R,
      mut .elem(list: mut LList[T], e: T): R,
    }
    LListMatchRead[T:imm,mut,read, R:iso,imm,mut,mutH,read,readH]: {
      mut .empty: R,
      mut .elem(list: read LList[T], e: read/imm T): R,
    }
    FlowOp[E:imm,mut,read]: {
      mut .for(sink: mut Sink[E]): Void,
      mut .step(sink: mut Sink[E]): Void,
    }
    Sink[E:imm,mut,read]: {
      mut #(e: E): Bool,
    }
    """;

  @Test void flowSemanticD1() {ok(new Res("a,b,c", "", 0), """
    package test
    alias base.Debug as D,
    Only3: {#: Str -> LList[Str] + "a" + "b" + "c" + "d"
      .flow
      .limit(3)
      .peek{e -> e == "d" ? {
        .then -> Error.msg "flow did not stop",
        .else -> {}
      }}
      .join ","
    }
    Test: Main{sys -> sys.io.println(Only3#)}
    """, Base.mutBaseAliases);}
  @Test void flowSemanticD1List() {ok(new Res("a,b,c", "", 0), """
    package test
    alias base.Debug as D,
    Only3: {#(list: read UList[Str]): Str -> list
      .flow
      .limit(3)
      .peek{e -> e == "d" ? {
        .then -> Error.msg "flow did not stop",
        .else -> {}
      }}
      .join ","
    }
    Test: Main{sys -> Block#
      .let[mut UList[Str]] list = {UList#}
      .do {list.add "a"}
      .do {list.add "b"}
      .do {list.add "c"}
      .do {list.add "d"}
      .return {sys.io.println(Only3#list)}
      }
    """, Base.mutBaseAliases);}

  @Test void flowSemanticD2() {ok(new Res("c", "", 0), """
    package test
    alias base.Debug as D,
    Only3: {#: Str -> LList[Str] + "a" + "b" + "c" + "d"
      .flow
      .peek{e -> e == "d" ? {
        .then -> Error.msg "flow did not stop",
        .else -> {}
      }}
      .first{e -> e == "c"}!
    }
    Test: Main{sys -> sys.io.println(Only3#)}
    """, Base.mutBaseAliases);}

  @Test void internalIteratorFirstDoubleDigit() {ok(new Res("14", "", 0), """
    package test
    DoubleDigitOrHigher: {.first(nums: mut FlowOp[Nat]): mut Opt[Nat] ->
      Block#
        .var[mut Opt[Nat]] result = {{}}
        .do {nums.for{n -> n > 9 ? {
          .then -> Block#(result.set(Opts#n), False),
          .else -> True,
        }}}
        .return {result.get}
    }
    Test: Main{sys -> Block#
      .let list = {LList[Nat] + 1 + 2 + 14 + 3 + 32}
      .let res = {DoubleDigitOrHigher.first(list.flowOp)}
      .do {sys.io.println(res!.str)}
      .return {{}}
      }
    """, LLIST, """
    package test
    alias base.Opt as Opt,
    alias base.Opts as Opts,
    alias base.iter.Iter as Iter,
    alias base.Nat as Nat,
    alias base.Bool as Bool,
    alias base.True as True, alias base.False as False,
    alias base.Block as Block,
    alias base.Main as Main,
    alias base.Void as Void,
    """);}

  @Test void players() {ok(new Res("Bob:0, Alice:0, Charlie:0", "", 0), """
    package test
    Players: F[Str, Nat, mut Player]{name, score -> Block#
      .var[Str] name' = {name}
      .var[Nat] score' = {score}
      .return {mut Player: {
        read .name: Str -> name'.get,
        mut .name(name'': Str): Void -> name'.set(name''),
        read .score: Nat -> score'.get,
        mut .score(score'': Nat): Void -> score'.set(score''),
      }}
    }
    NamesToPlayers: {#(names: List[Str]): mut List[mut Player] ->
      names.flow // type: mut Flow[Str]
        .map{name -> Players#(name, 0)} // type: mut Flow[mut Player]
        .list
    }
    Test: Main{sys -> Block#
      .let[List[Str]] list = {List.consumeUList(UList#[Str] + "Bob" + "Alice" + "Charlie")}
      .let res = {NamesToPlayers#list}
      .do {sys.io.println(res.flow.map{p -> p.name + ":" + (p.score.str)}.join ", ")}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Disabled
  @Test void moleculeExample() {ok(new Res("", "", 0), """
    package test
    alias base.CompareNats as CompareNats,
    
    Samples: F[Nat,Sample]{id -> Sample: {
      .str: Str -> "{id: " + (id.str) + "}",
      .analyseSample: mut List[Molecule] -> Block#
        .var[Nat] n = {100}
        .let[mut List[Molecule]] res = {List.withCapacity(n.get)}
        .loop {Block#
          .if {n.get == 0} .return {ControlFlow.breakWith[mut List[Molecule]]}
          .do {Block#(n <- {n' -> n' - 1})}
          .do {res.add Molecule{.weight -> 2323}}
          .return {ControlFlow.continueWith[mut List[Molecule]]}
        }
        .return {res},
    }}
    Molecule: {
      .weight: Nat,
      .str: Str -> "Molecule(" + (this.weight.str) + ")",
      }
    GenerateSamples: {#: mut List[Sample] -> Block#
      .var[Nat] n = {10_000}
      .let[mut List[Sample]] res = {List.withCapacity(n.get)}
      .loop {Block#
        .if {n.get == 0} .return {ControlFlow.breakWith[mut List[Sample]]}
        .do {Block#(n <- {n' -> n' - 1})}
        .do {res.add(Samples#(n.get))}
        .return {ControlFlow.continueWith[mut List[Sample]]}
      }
      .return {res},
    }
    
    HeaviestMolecules: {#(samples: List[Sample]): List[Molecule] ->
      samples.flow
        .map{sample -> sample.analyseSample}
        .map{molecules -> molecules.flow.max{m1, m2 -> CompareNats#(m1.weight, m2.weight)}}
        .flatMap{optMax -> optMax.flow}
        .list
    }
    
    Test: Main{sys -> Block#
      .let[List[Sample]] ss = {GenerateSamples#}
      .let[List[Molecule]] ms = {HeaviestMolecules#ss}
      .return{sys.io.println(ms.flow.map{m -> m.str}.join ",")}
    }
    """, Base.mutBaseAliases);}

  @Test void covariantReturnTypes() {ok(new Res("test.A/0\ntest.B1/0\ntest.B2/0", "", 0), """
    package test
    alias base.Block as Block,
    alias base.Debug as D,
    alias base.Main as Main,
    alias base.True as True,
    alias base.Bool as Bool,
    
    A: {.self: A -> this}
    B1: A{}
    B2: A{.self: B2 -> B2}
    
    Is: {
      .a(a: A): Bool -> True,
      .b1(b1: B1): Bool -> True,
      .b2(b2: B2): Bool -> True,
    }
    Test: Main{sys -> Block#
      .let io = {sys.io}
//      .do {io.println(Is.b2(B2.self).str)}
      .do {io.println(D.identify(A.self))}
      .do {io.println(D.identify(B1.self))}
      .do {io.println(D.identify(B2.self))}
      .return {{}}
    }
    """);}

  @Test void packages() {ok(new Res(), """
    package test
    alias base.Bool as B,
    alias base.True as True,
    alias base.False as False,
    alias base.List as List,
    alias base.List[base.Str] as LStr,
    
    BoolExample: {#: B -> True .and False .or base.True}
    // returns True
    
    Test: base.Main{sys -> {}}
    """);}

  @Test void isoFlexMethTypeEx1() {ok(new Res(), """
    package test
    alias base.Todo as Todo,
    A: {
      read .m1(a1: mut A, a2: imm A): iso B -> Bs#(a1.compute(a2), mut A),
      mut .compute(a: A): mut A -> this, // some in-place mutation
    }
    Bs: {
      #(a1: mutH A, a2: mut A): mut B -> mut B{a2},
    }
    B: {mut .a: mut A}
    
    Test: Main{_ -> {}}
    """, Base.mutBaseAliases);}
  @Disabled @Test void isoFlexMethTypeEx1UsingPromotion() {ok(new Res(), """
    package test
    alias base.Todo as Todo,
    A: {
      read .m1(a1: mut A, a2: imm A): iso B -> A'.m1(a1, a2),
      mut .compute(a: A): mut A -> this, // some in-place mutation
    }
    A': {
      .m1(a1: mutH A, a2: imm A): iso B -> this.m1'(a1, a2),
      .m1'(a1: mut A, a2: imm A): iso A -> Bs#(this.m1''(a1, a2), mut A),
      .m1''(a1: mut A, a2: imm A): mutH A -> a1.compute(a2),
    }
    Bs: {
      #(a1: mutH A, a2: mut A): mut B -> mut B{a2},
    }
    B: {mut .a: mut A}
    
    Test: Main{_ -> {}}
    """, Base.mutBaseAliases);}
}
