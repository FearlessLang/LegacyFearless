package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

public class TestZigProgram {
  @Test void emptyProgram() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> {} }
    """, Base.mutBaseAliases);}

  @Test void printAndPrintln() { okBase(new Res("HelloWorld", "", 0), """
    package test
    Test:Main{ sys -> mut Block[Void]
      .do{ sys.io.print("Hello") }
      .return{ sys.io.println("World") }
      }
    """, Base.mutBaseAliases);}

  @Test void varGetAndSet() { okBase(new Res("42", "", 0), """
    package test
    Test:Main{ sys -> mut Block[Void]
      .let[mut Var[Str]] v = { Vars#[Str]("hello") }
      .do{ v.set("42") }
      .return{ sys.io.println(v.get) }
      }
    """, Base.mutBaseAliases);}

  @Test void listIter() { okBase(new Res("350,350,350,140,140,140", "", 0), """
    package test
    Test: Main{sys -> Block#
      .let l1 = { List#[Nat](35, 52, 84, 14) }
      .assert{l1.iter
        .map{n -> n * 10}
        .find{n -> n == 140}
        .isSome}
      .let[Str] msg = {l1.iter
        .filter{n -> n < 40}
        .flatMap{n -> List#(n, n, n).iter}
        .map{n -> n * 10}
        .str({n -> n.str}, ",")}
      .let io = {sys.io}
      .return {io.println(msg)}
      // prints 350,350,350,140,140,140
    }
    """, Base.mutBaseAliases);}

  @Test void isoPod1() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .return{ Assert!(Usage#(a!) == +0) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod1Consume() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .return{ Assert!(a.consume{.some(n) -> Usage#n, .empty -> +500} == +0) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod2() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .do{ a.next(MutThingy'#(Count.int(+5))) }
      .return{ Assert!(Usage#(a!) == +5) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPod3() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .do{ Block#(a.mutate{ mt -> Block#(mt.n++) }!) }
      .return{ Assert!(Usage#(a!) == +1) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }
  @Test void isoPodNoImmFromPeekOk() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .let[Int] ok = { a.peek[Int]{ .some(m) -> m.rn*.int + +0, .empty -> base.Abort! } }
      .return{Void}
      }
    MutThingy:{ mut .n: mut Count[Int], read .rn: read Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { .n -> n, .rn -> n } }
    """, Base.mutBaseAliases); }
}
