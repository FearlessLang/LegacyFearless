package codegen.java;

import main.CompilerFrontEnd;
import main.InputOutput;
import main.Main;
import main.java.LogicMainJava;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;
import utils.Err;

import java.util.Arrays;

public class TestJavaOptimisations {
  void ok(String expected, String fileName, String... content) {
    assert content.length > 0;
    Main.resetAll();
    var vb = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var main = LogicMainJava.of(InputOutput.programmaticAuto(Arrays.asList(content)), vb);
    var fullProgram = main.parse();
    main.wellFormednessFull(fullProgram);
    var program = main.inference(fullProgram);
    main.wellFormednessCore(program);
    var resolvedCalls = main.typeSystem(program);
    var mir = main.lower(program,resolvedCalls);
    var code = main.codeGeneration(mir);
    var fileCode = code.files().stream()
      .filter(f->f.toUri().toString().endsWith(fileName))
      .map(JavaFile::code)
      .findFirst().orElseThrow();
    Err.strCmp(expected, fileCode);
  }

  @Test void blockLetDoRet() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 fear[###]$_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 fear[###]$_m$, test.Test_0 $this) {
      var n_m$ = 5L;
    var doRes1 = test.ForceGen_0.$self.$hash$imm();
    return base.Void_0.$self;
    }
    }
    """, "/test/Test_0.java", """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test:base.Main {_ -> Block#
     .let[Int] n = {+5}
     .do {ForceGen#}
     .return {Void}
     }
    ForceGen: {#: Void -> {}}
    """);}
  @Test void blockVarDoRet() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 fear[###]$_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 fear[###]$_m$, test.Test_0 $this) {
      var n_m$ = base.Vars_0.$self.$hash$imm(5L);
    var doRes1 = test.ForceGen_0.$self.$hash$imm();
    return base.Void_0.$self;
    }
    }
    """, "/test/Test_0.java", """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test:base.Main {_ -> Block#
     .var[Int] n = {+5}
     .do {ForceGen#}
     .return {Void}
     }
    ForceGen: {#: Void -> {}}
    """);}
  @Test void blockOpenIsoDoRet() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 fear[###]$_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 fear[###]$_m$, test.Test_0 $this) {
      var n_m$ = 5L;
    var doRes1 = test.ForceGen_0.$self.$hash$imm();
    return base.Void_0.$self;
    }
    }
    """, "/test/Test_0.java", """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test:base.Main {_ -> Block#
     .openIso[iso Int] n = (iso +5)
     .do {ForceGen#}
     .return {Void}
     }
    ForceGen: {#: Void -> {}}
    """);}
  @Test void blockOpenIsoAlwaysGoesFirst() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 fear[###]$_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 fear[###]$_m$, test.Test_0 $this) {
      var n_m$ = 5L;
    var doRes1 = test.ForceGen_0.$self.$hash$imm();
    return base.Void_0.$self;
    }
    }
    """, "/test/Test_0.java", """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test:base.Main {_ -> Block#
     .do {ForceGen#}
     .openIso[iso Int] n = (iso +5)
     .return {Void}
     }
    ForceGen: {#: Void -> {}}
    """);}
  @Test void blockError() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 fear[###]$_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 fear[###]$_m$, test.Test_0 $this) {
      var n_m$ = 5L;
    var n2_m$ = 10L;
    var n3_m$ = base.Vars_0.$self.$hash$imm(15L);
    if (rt.Numbers.toBool(((long)((long)n3_m$.get$mut()))==((long)((long)((long)n_m$)) + n2_m$)) == base.True_0.$self) { rt.Error.throwFearlessError(base.Infos_0.$self.msg$imm(Str$3882878235102293474$Str$.$self));
     }
    var doRes1 = test.ForceGen_0.$self.$hash$imm();
    return base.Void_0.$self;
    }
    }
    """, "/test/Test_0.java", """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test:base.Main {_ -> Block#
     .openIso[Int] n = (iso +5)
     .let[Int] n2 = {+10}
     .var[Int] n3 = {+15}
     .if {n3.get == (n.int + n2)} .error {base.Infos.msg "oh no"}
     .do {ForceGen#}
     .return {Void}
     }
    ForceGen: {#: Void -> {}}
    """);}

  @Test void blockRet() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 fear[###]$_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 fear[###]$_m$, test.Test_0 $this) {
      return base.Void_0.$self;
    }
    }
    """, "test/Test_0.java", """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    Test: base.Main{_ -> Block#
     .return {Void}
     }
    """);}

  @Test void boolExprBlock() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 fear[###]$_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 fear[###]$_m$, test.Test_0 $this) {
      return ((base.True_0.$self == base.True_0.$self ? (switch (1) {default -> {
      yield base.Void_0.$self;
    }})
     : (switch (1) {default -> {
      yield base.Void_0.$self;
    }})
    ));
    }
    }
    """, "test/Test_0.java", """
    package test
    alias base.Int as Int, alias base.Str as Str, alias base.Block as Block, alias base.Void as Void,
    alias base.True as True,
    Test: base.Main{_ -> True ? {
      .then -> Block#.return {Void},
      .else -> Block#.return {Void},
      }}
    """);}

  @Test void incrementLoop() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 sys_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 sys_m$, test.Test_0 $this) {
      var n_m$ = ((base.Count_1)base.Count_0.$self.int$imm(0L));
    while (true) {
      var res = new Fear[###]$_0Impl(n_m$).$hash$mut();
      if (res == base.ControlFlowContinue_0.$self || res == base.ControlFlowContinue_1.$self) { continue; }
        if (res == base.ControlFlowBreak_0.$self || res == base.ControlFlowBreak_1.$self) { break; }
        if (res instanceof base.ControlFlowReturn_1 rv) { return (base.Void_0) rv.value$mut(); }
      }
    return base.Void_0.$self;
    }
    }
    """, "test/Test_0.java", """
    package test
    Test:Main {sys -> Block#
      .let n = {Count.int(+0)}
      .loop {Block#
        .if {n.get == +10} .return {ControlFlow.break}
        .do {Block#(n++)}
        .return {ControlFlow.continue}
        }
//      .assert {n.get == +10}
      .return {Void}
      }
    """, Base.mutBaseAliases);}

  @Test void earlyReturnLoop() { ok("""
    package test;
    public interface Foo_0{
    Foo_0 $self = new Foo_0Impl();
    rt.Str $hash$imm();
    static rt.Str $hash$imm$fun(test.Foo_0 $this) {
      var n_m$ = ((base.Count_1)base.Count_0.$self.int$imm(0L));
    while (true) {
      var res = new Fear[###]$_0Impl(n_m$).$hash$mut();
      if (res == base.ControlFlowContinue_0.$self || res == base.ControlFlowContinue_1.$self) { continue; }
        if (res == base.ControlFlowBreak_0.$self || res == base.ControlFlowBreak_1.$self) { break; }
        if (res instanceof base.ControlFlowReturn_1 rv) { return (rt.Str) rv.value$mut(); }
      }
    return Str$1004811944375518034$Str$.$self;
    }
    }
    """, "test/Foo_0.java", """
    package test
    Test:Main {sys -> (UnrestrictedIO#sys).println(Foo#)}
    Foo: {#: Str -> Block#
      .let n = {Count.int(+0)}
      .loop {Block#
        .if {n.get == +10} .return {ControlFlow.return[Str](n.get.str)}
        .do {Block#(n++)}
        .return {ControlFlow.continueWith[Str]}
        }
      .return {"Boo :("}
      }
    """, Base.mutBaseAliases);}

  @Test void earlyReturnLoopEarlyExit() { ok("""
    package test;
    public interface Foo_0{
    Foo_0 $self = new Foo_0Impl();
    rt.Str $hash$imm();
    static rt.Str $hash$imm$fun(test.Foo_0 $this) {
      var n_m$ = ((base.Count_1)base.Count_0.$self.int$imm(0L));
    while (true) {
      var res = new Fear[###]$_0Impl(n_m$).$hash$mut();
      if (res == base.ControlFlowContinue_0.$self || res == base.ControlFlowContinue_1.$self) { continue; }
        if (res == base.ControlFlowBreak_0.$self || res == base.ControlFlowBreak_1.$self) { break; }
        if (res instanceof base.ControlFlowReturn_1 rv) { return (rt.Str) rv.value$mut(); }
      }
    return Str$1004811944375518034$Str$.$self;
    }
    }
    """, "test/Foo_0.java", """
    package test
    Test:Main{sys -> (UnrestrictedIO#sys).println(Foo#)}
    Foo: {#: Str -> Block#
      .let n = {Count.int(+0)}
      .loop {Block#
        .if {n.get == +10} .return {ControlFlow.return[Str](n.get.str)}
        .do {Block#(n++)}
        .return {ControlFlow.breakWith[Str]}
        }
      .return {"Boo :("}
      }
    """, Base.mutBaseAliases);}

  @Disabled
  @Test void methodChainDevirtualisation() { ok("""
    """, "", """
    package test
    A: {.m1(a: A): A}
    B: {.m1: A -> A{a0 -> a0}.m1(A{.m1(a1) -> A{.m1(a2) -> a1}})}
    Test: Main{sys -> Void}
    """, Base.mutBaseAliases); }

  @Test void dataParallelFlow() { ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 sys_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 sys_m$, test.Test_0 $this) {
      return sys_m$.io$mut().println$mut(((rt.Str)((base.flows.Flow_1)((base.flows.Flow_1)rt.flows.FlowCreator.fromFlow(rt.flows.dataParallel.DataParallelFlowK.$self, Str$3297469917561599766$Str$.$self.codepoints$imm())).map$mut(test.Fear[###]$_0.$self)).join$mut(Str$14492805990617963705$Str$.$self)));
    }
    }
    """, "/test/Test_0.java", """
    package test
    Test: Main{sys -> sys.io.println("Hello".codepoints
      .map{ch -> ch == "H" ? {.then -> "J", .else -> ch}}
      .join ""
      )}
    """, Base.mutBaseAliases);}

  @Test void asIdFnUList() {ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 sys_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 sys_m$, test.Test_0 $this) {
      return base.Block_0.$self.$hash$imm(rt.UListK.asShallowClone(((base.UList_1)rt.UListK.$self.$hash$imm()), test.Fear[###]$_0.$self));
    }
    }
    """, "/test/Test_0.java", """
    package test
    Test: Main{sys -> Block#(UList#[Nat].as{x->x})}
    """, Base.mutBaseAliases);}
  @Test void asIdFnList() {ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 sys_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 sys_m$, test.Test_0 $this) {
      return base.Block_0.$self.$hash$imm(((base.List_1)rt.ListK.$self.$hash$imm()));
    }
    }
    """, "/test/Test_0.java", """
    package test
    Test: Main{sys -> Block#(List#[Nat].as{x->x})}
    """, Base.mutBaseAliases);}
  @Test void asNonIdFn() {ok("""
    package test;
    public interface Test_0 extends base.Main_0{
    Test_0 $self = new Test_0Impl();
    base.Void_0 $hash$imm(base.caps.System_0 sys_m$);
    static base.Void_0 $hash$imm$fun(base.caps.System_0 sys_m$, test.Test_0 $this) {
      return base.Block_0.$self.$hash$imm(((base.List_1)rt.ListK.$self.$hash$imm()).as$read(test.Fear[###]$_0.$self));
    }
    }
    """, "/test/Test_0.java", """
    package test
    Test: Main{sys -> Block#(List#[Nat].as{x->x * 2})}
    """, Base.mutBaseAliases);}
}
