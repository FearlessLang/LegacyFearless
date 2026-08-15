package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;
import utils.ResolveResource;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/** Fundamentals (entry point, IO) and container magic: {@code Var}, {@code List},
 * {@code IsoPod}. */
public class TestZigContainers {
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
  /// `.look` wraps `.peek` in an `Action`, so it is only reachable if the
  /// Fearless-bodied slot is registered.
  @Test void isoPodLook() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+3))) }
      .return{ Assert!(a.look[Int]{ m -> m.rn*.int }! == +3) }
      }
    MutThingy:{ mut .n: mut Count[Int], read .rn: read Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { .n -> n, .rn -> n } }
    """, Base.mutBaseAliases); }

  /// `.isDead` is `this.isAlive.not`, and only flips once the pod is consumed.
  @Test void isoPodIsDead() { okBase(new Res("alive/dead", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .let[Str] before = { a.isDead ? { .then -> "dead", .else -> "alive" } }
      .let[Int] taken = { Usage#(a!) }
      .let[Str] after = { a.isDead ? { .then -> "dead", .else -> "alive" } }
      .return{ sys.io.println(before + "/" + after) }
      }
    Usage:{ #(m: iso MutThingy): Int -> (m.n*) }
    MutThingy:{ mut .n: mut Count[Int] }
    MutThingy':{ #(n: mut Count[Int]): mut MutThingy -> { n }  }
    """, Base.mutBaseAliases); }

  /// `:=` is the operator spelling of `.next`.
  @Test void isoPodAssign() { okBase(new Res("", "", 0), """
    package test
    Test:Main{ _ -> Block#
      .let[mut IsoPod[MutThingy]] a = { IsoPod#[MutThingy](MutThingy'#(Count.int(+0))) }
      .do{ a := (MutThingy'#(Count.int(+9))) }
      .return{ Assert!(Usage#(a!) == +9) }
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

  @Test void simpleJson() { okBase(new Res("""
    "Hello!!!\\nHow are you?"
    "Hello!!!\\nHow 吣are吣 you?"
    "Hello!!!\\nHow 吣are吣 you? 𝄞"
    []
    [[[[]], [], true]]
    ["abc", "def", true, false, null]
    ["abc", "def", true, [false], 42.1337, null, []]
    {}
    {"single": true}
    ["ab\\\\c", "def", {}, {"a": "fearless", "b": {"a": true}}]
    {"value": 12345678901234567000}
    """, """
    Invalid string found, expected JSON.
    Unknown fragment in JSON code:
    tru at 1:6
    Invalid string found, expected JSON.
    Unexpected 'true' when parsing a JSON object at 1:6
    """, 0), ResolveResource.test("/json/main.fear"), ResolveResource.test("/json/pkg.fear")); }

  // === LinkedHashMap (Maps.hashMap) ===

  /// Insertion order is preserved, and updating an existing key replaces its
  /// value in place without moving it (keys stay `one,two,three`).
  @Test void mapInsertionOrder() { okBase(new Res("one,two,three=1,2,30", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut LinkedHashMap[Str,Nat]] m = { Maps.hashMap[Str,Nat]({k1,k2 -> k1 == k2}, {k -> k}) }
      .do{ m.put("one", 1) }
      .do{ m.put("two", 2) }
      .do{ m.put("three", 3) }
      .do{ m.put("three", 30) }
      .let[Str] ks = { m.keys.join(",") }
      .let[Str] vs = { m.values.map{v -> v.str}.join(",") }
      .return{ sys.io.println(ks + "=" + vs) }
      }
    """, Base.mutBaseAliases); }

  /// `.get` finds present keys and yields empty for absent ones; `.remove`
  /// returns the old value and drops the entry (keys become `one,three`).
  @Test void mapGetRemove() { okBase(new Res("2/2/none/one,three", "", 0), """
    package test
    Test:Main{ sys -> Block#
      .let[mut LinkedHashMap[Str,Nat]] m = { Maps.hashMap[Str,Nat]({k1,k2 -> k1 == k2}, {k -> k}) }
      .do{ m.put("one", 1) }
      .do{ m.put("two", 2) }
      .do{ m.put("three", 3) }
      .let[Str] got = { m.get("two").match{.some(v) -> v.str, .empty -> "none"} }
      .let[Str] removed = { m.remove("two").match{.some(v) -> v.str, .empty -> "none"} }
      .let[Str] gone = { m.get("two").match{.some(v) -> v.str, .empty -> "none"} }
      .let[Str] ks = { m.keys.join(",") }
      .return{ sys.io.println(got + "/" + removed + "/" + gone + "/" + ks) }
      }
    """, Base.mutBaseAliases); }
}
