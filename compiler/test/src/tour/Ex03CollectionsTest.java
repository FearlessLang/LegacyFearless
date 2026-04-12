package tour;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;

import static utils.RunOutput.Res;
import static codegen.java.RunJavaProgramTests.*;
import static tour.TourHelper.*;

public class Ex03CollectionsTest {
  /*-----

Now we can show optionals: `Opt[E]`.
`Opt[E]` is simply modeling that a value can be present or not.
An optional is the simplest form of collection, and it is basically
a collection of zero or one element.
An empty optional is not representing a mistake/error/problem.
We think that understanding the full code of optional is a great way to learn Fearless and to
demystify fearless collections.
-------------------------*/@Disabled("03/12/24") @Test void optional() { run("""
  Opts: {
    #[T](x: T): mut Opt[T] -> {.match(m) -> m.some(x)},
    }
  Opt[T]: _Opt[T]{
    .match(m)       -> m.empty,
    .map(f)         -> this.match(f),
    ||(default)     -> this.match{.some(x) -> x, .empty -> default#},
    |(default)      -> this.match{.some(x) -> x, .empty -> default},
    !               -> this.match{.some(x) -> x, .empty -> Error.msg "Opt was empty"},
    .flow           -> this.match{.empty -> Flow#, .some(x) -> Flow#x)},
  
    read .isEmpty: Bool -> this.match{.empty -> True,  .some(_) -> False},
    read .isSome: Bool  -> this.match{.empty -> False, .some(_) -> True},
    imm .imm: Opt[imm T] -> this.match{.empty -> {}, .some(x) -> Opts#x},
    }
  _Opt[T]: Sealed{
    mut  .match[R](m: mut OptMatch[T, R]): R,
    read .match[R](m: mut OptMatch[read/imm T, R]): R,
    imm  .match[R](m: mut OptMatch[imm T, R]): R,
  
    mut  .map[R](f: mut OptMap[T, R]):          mut Opt[R],
    read .map[R](f: mut OptMap[read/imm T, R]): mut Opt[R],
    imm  .map[R](f: mut OptMap[imm T, R]):      mut Opt[R],
  
    mut  ||(default: mut MF[T]):          T,
    read ||(default: mut MF[read/imm T]): read/imm T,
    imm  ||(default: mut MF[imm T]):      imm T,
  
    mut  |(default: T):          T,
    read |(default: read/imm T): read/imm T,
    imm  |(default: imm T):      imm T,
  
    mut  !: T,
    read !: read/imm T,
    imm  !: imm T,
  
    mut  .flow: mut Flow[T],
    read .flow: mut Flow[read/imm T],
    imm  .flow: mut Flow[imm T],
    }
  
  OptMatch[T,R]: { mut .some(x: T): R, mut .empty: R }
  OptMap[T,R]: OptMatch[T, mut Opt[R]]{
    mut #(t: T): R,
    .some(x) -> Opts#(this#x),
    .empty -> {}
    }
  """); }/*--------------------------------------------

As you can see, our implementation of optionals is reasonably compact,
but there are a lot of type signatures. In order to understand
a library you need to understand the type signatures and the
behavior of the methods.

When writing documentation for a library, there are two main approaches: describing the behaviour of each method with
examples, or just showing the signatures of the methods. Both approaches have their place. For code which is largely just
a wrapper around data, where signatures clearly show the operations occurring, simply showing the code may be the best approach.
On the other hand, if the library encodes complex behaviour, often with many different conditions on input data, etc. then
going by examples and by the documentation of the individual methods is best.

In the code above we show a very interesting pattern using `Opt` and `_Opt`.
As you can see, `_Opt` defines three versions for many methods
`_Opt` is never used directly by the user, but cotains th

Here we show some example code using Optionals
-------------------------*/@Test void optionalEx1() { run("""
  Students: F[Str,Nat,Student]{name, age -> Student: {
    .name: Str -> name,
    .age: Nat -> age,
    .str: Str -> "Name: "+name+", Age: "+(age.str),
    }}
  SchoolRoll: {
    #(name: Str): Opt[Student] -> name == "Nick" ? {
      .then -> Opts#(Students#("Nick", 25)),
      .else -> {}
      }}
  Test: Main{sys -> Block#
    .let[mut IO] io = {UnrestrictedIO#sys}
    .let[Student] student = {SchoolRoll#"Marco" || {Students#("Anonymous", 0)}}
    .return {io.println(student.str)}
    }
  //prints Name: Anonymous, Age: 0
  """); }/*--------------------------------------------
-------------------------*/@Test void optionalEx2() { run("""
  Students: F[Str,Nat,Student]{name, age -> Student: {
    .name: Str -> name,
    .age: Nat -> age,
    .str: Str -> "Name: "+name+", Age: "+(age.str),
    }}
  SchoolRoll: {
    #(name: Str): Opt[Student] -> name == "Nick" ? {
      .then -> Opts#(Students#("Nick", 25)),
      .else -> {}
      }}
  Test: Main{sys -> Block#
    .let[mut IO] io = {UnrestrictedIO#sys}
    .let[Student] student = {SchoolRoll#"Nick" || {Students#("Anonymous", 0)}}
    .return {io.println(student.str)}
    }
  //prints Name: Nick, Age: 25
  """); }/*--------------------------------------------
-------------------------*/@Test void optionalEx3() { run("""
  Students: F[Str,Nat,Student]{name, age -> Student: {
    .name: Str -> name,
    .age: Nat -> age,
    }}
  SchoolRoll: {
    #(name: Str): Opt[Student] -> name == "Nick" ? {
      .then -> Opts#(Students#("Nick", 25)),
      .else -> {}
      }}
  Test: Main{sys -> Block#
    .let[mut IO] io = {UnrestrictedIO#sys}
    .let sum = {SumAgeIfStudent#(SchoolRoll, List#("Marco", "Nick"))}
    .return {io.println(sum.str)}
    }
  SumAgeIfStudent: {#(roll: SchoolRoll, names: List[Str]): Nat -> names.flow
    .flatMap{name -> roll#name.flow}
    .map{student -> student.age}
    .fold[Nat]({0}, {a, b -> a + b})
    }
  //prints 25
  """); }/*--------------------------------------------

  o1 || {o2 || {o3 || {v}}}


//Int,Nat,Str,Optional,HasStr,Ordered/Comparator,List,
//Much later,Next: importance of closing

*/

/*
----Lists----
Lists manipulation is crucial in most software development
Fearless offers a generic List[E] type.
`List#[Int](5, 10, 15)`, or just `List#(5, 10, 15)` will create
a list with 3 elements.
To start simple, how can we print this list?
In Fearless there is no top level Object/Any type and thus there is no 
default way to turn an element into a string.
Thus while `5.str` makes perfect sense, but `List#(5, 10, 15).str`
could not possibly work: such method would need to have a way to convert
the elements in strings, as shown below.
-------------------------*/@Disabled @Test void listNums1() { run("""
    package test
    Test:Main {sys -> Block#
      .let myList= {List#[Int](5, 10, 15)}
      .var  myStr= {myList.str {n->n.str}}
      .var  myStr= {myList.str ::str}
      .return{UnrestrictedIO#sys.println(myStr)}
      }
    //prints [5, 10, 15]
    """); }/*--------------------------------------------
For more control, we can use flows.
Indeed lists are mostly used with flows:
an elegant way to concatenate operations and produce results.
Here we first map the elements to strings using 'number.str'
//@Nick, I now think  #(Flow.join ", ") was a terrible idea, look the new one!
//may be too gimmiky?

-------------------------*/@Disabled @Test void listNums2() { run("""
    package test
    Test:Main {sys -> Block#
      .let myList= {List#[Int](5, 10, 15)}
      .let myStr= {myList.flow
        .map{n -> n.str}
        #"; "//ok and also #Sum/#USum/#FSum, so Flow.str/Flow.sum disappear
        }
      .let myStr= {myList.flow.map::str#"; "} //options
      .let myStr= {myList.flow.map{::str}#"; "}
      .return{UnrestrictedIO#sys.println(myStr)}//we agreed parenthesis needed
      }
    //prints 5; 10; 15
    """); }/*--------------------------------------------
Lists can be mutated, and elements can be added after the list
is created.
We can use `.add` and `.get` as we would expect from most other languages. 
Overall, random access to list elements while possible is discouraged
in fearless and should be replaced with flow access when possible.
Of course, there are still situations where random access is crucial.
Note how .get can not possibly succeed for elements outside of the
size of the list. In this case a dynamic error is thrown. 
-------------------------*/@Disabled("03/12/24") @Test void addAndGetFromList() {run("""
    package test
    Test:Main {sys -> Block#
      .let list = {List#[Str]}//YES spaces on both sides of =?
      .do {list.add("YAY!")}
      .var  yay = {list.get(0)}
      .return {UnrestrictedIO#sys.println(yay)}
      }
    //prints YAY!
    """); }/*--------------------------------------------
Lists can be created with elements, and elements can be added later.
the method `.size` gives us the current size.
Fearless standard library consistently uses the word `size`
to talk about sizes of collections, flows, results etc.
Using `length`/`count` is discouraged.
-------------------------*/@Disabled("03/12/24") @Test void listSize() {run("""
    package test
    Test:Main {sys -> Block#
      .let list = {List#(5, 10, 15)}
      .do {list.add(20)}
      .assert({list.size == 4u})
      .done
      }
    """); }/*--------------------------------------------
TODO: is there an earler .return{Void} to discuss .done?
Map are a more complex kind of data structure, and while not as common as lists,
they are crucial for programming.
Fearless offers many kinds of Maps, here we show a few options.
Map keys must be immutable objects, element can be anything.
Many maps are on Str or Int types
//Int or Num?? I'm happy with Int, but we have tons of 'Num' around
//norm for non cyclic?
-------------------------*/@Disabled("03/12/24") @Test void mapOfNameToAge() {run("""
    package test
    Person:Ordered[Person]{
      .name:Str, .age:Nat
      .order-> CPerson.with this
      }
    FPerson:F[Str,Nat,Person]{n,a->{.name->n,.age->a}}
    
    PersonF:F[Str,Nat,Person]{n,a->{.name->n,.age->a}}
    Persons#(a,b) //accepted
    Persons.compare //accepted
    Ints
    Opts#bob
    NewOpts#foo
    UnrestrictedIO#sys
    IOs#sys //
    Opt[T]
    NewHtml.a
    //Unsign instead of Nat accepted
    //Compare instead of Comparator accepted
    //CompareUnsign   accepted
    Nat
    ComparePerson
    //CPerson:Compare[Person]{ p1,p2->p1.age>p2.age }
    CPerson:Comparator[Person]{ p1,p2->CNat#{p->p.age}#(p1,p2) }
    CPerson:Comparator[Person]{ p1,p2->
      p1.age.compareWith(p2.age)//better
      .and{p1.name.compareWith(p2.name)}
      }
    Persons:{
      compare:Compare[Person]->//worst
        Strs.compare#{p->p.name}
          .and{Unsigns.compare#{p->p.age}}
    }
    Test:Main {sys -> Block#
      .let print= {mut _:{mut #(s:Str):Void->UnrestrictedIO#sys.println(s)}}
      //.let print= {mut Fresh:{mut #(s:Str):Void->UnrestrictedIO#sys.println(s)}}
      .let mapStr = {Map.str(
        "Alice", 24,
        "Bob", 30)}
      .do{ mapStr.put("Bob",30) }
      .let bobAgeOpt = {map.get("Bob")}
      .if {bobAgeOpt?}.do{print#(bobAgeOpt!.str)}
      .do{bobAgeOpt.flow.for{age->print#(age.str)}}
      .let mapBase1 = {Map#(CPerson,
        FPerson#("Alice",24), "50032345b",
        FPerson#("Bob",30),   "50032211c"
        )}
      .let mapBase2 = {Map#({
          .eq(k1, k2) -> k1.name == k2.name,//For more control
          .hash(k) -> k.age.hash//nope
          },
        FPerson#("Alice",24), "50032345b",
        FPerson#("Bob",30),   "50032211c"
        )}
 
     // or:
      .do {map.put("Bob", 30)}
      .let bobAgeOpt = {map.get("Bob")}
      .let bobAge = {bobAgeOpt!}
      .return {UnrestrictedIO#sys.println(bobAge.str)}
      }
      //30
    """);}

  // Our maps/sets are linked hashmaps/linked hashsets and thus have deterministic iteration order.
  @Disabled("03/12/24") @Test void mapFlow() { ok(new Res("30", "", 0), """
    package test
    Test:Main {sys -> Block#
      .let map = {Map.str("Alice",24, "Bob",30)}
      // if we have destructuring:
      // .let ageSum = {map.flow.map{[key, val] -> val}#(Flow.sum)}
      // .let ageSum = {map.flow.map{kv -> kv.val}#(Flow.sum)}
//      .return {map#(Map.str "\\n") // Map.str requires Map[Stringable, Stringable]
      .return {map.flow
        .map{kv -> kv.key + ": " + (kv.val.str)}
        .join("\\n")
        }
      }
    """, Base.mutBaseAliases);}

  @Test void subList() {ok(new Res("21 vs. 5", "", 0), """
    package test
    Test:Main {sys -> Block#
      .let[List[Nat]] l = {List.consumeUList(UList#[Nat](1, 2, 3, 4) + 5 + 6)}
      .let sum1 = {l.flow#(Flow.uSum)}
      .let sub = {ListViews.subList(l, 1, 3)}
      .let sum2 = {sub.flow#(Flow.uSum)}
      .return {sys.io.println(sum1.str + " vs. "+ (sum2.str))}
      }
    """, Base.mutBaseAliases);}

  @Test void mappedList() {ok(new Res("21 vs. 8", "", 0), """
    package test
    Test:Main {sys -> Block#
      .let[List[Nat]] l = {List.consumeUList(UList#[Nat](1, 2, 3, 4) + 5 + 6)}
      .let sum1 = {l.flow#(Flow.uSum)}
      .let sub = {ListViews.indexMap(l, List.consumeUList(UList#[Nat] + 0 + 0 + 1 + 1 + 0 + 0))}
      .let sum2 = {sub.flow#(Flow.uSum)}
      .return {sys.io.println(sum1.str + " vs. "+ (sum2.str))}
      }
    """, Base.mutBaseAliases);}

  @Test void offsetLookup() {ok(new Res(), """
    package test
    Test:Main {sys -> Block#
      .let[List[Nat]] l = {List#[Nat](1, 2, 3, 4)}
      .let[Nat] a = {l.get(5 .offset -2)}
      .let[Nat] b = {l.get(1 .offset +1)}
      .do {4 .assertEq(a)}
      .do {3 .assertEq(b)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}

  @Test void slotGetOrFill() {ok(new Res("1337\n1337", "", 0), """
    package test
    Test:Main {sys -> Block#
      .let io = {sys.io}
      .let slot = {Slots#[Nat]}
      .do {io.println(slot.getOrFill{1337}.str)}
      .do {io.println(slot.getOrFill{1234}.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}
  @Test void slotEnsureFill() {ok(new Res("1337", "", 0), """
    package test
    Test:Main {sys -> Block#
      .let slot = {Slots#[Nat]}
      .do {slot.ensureFull{1337}}
      .do {slot.ensureFull{1234}}
      .do {sys.io.println(slot.opt!.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}
  @Test void slotUpdate() {ok(new Res("1337\n1337\n2674", "", 0), """
    package test
    Test:Main {sys -> Block#
      .let io = {sys.io}
      .let slot = {Slots#[Nat]}
      .do {io.println(slot.getOrFill{1337}.str)}
      .do {io.println(slot.getOrFill{1234}.str)}
      .do {io.println(slot.get{
        .some(x) -> x * 2,
        .empty -> 0,
        }.str)}
      .return {{}}
      }
    """, Base.mutBaseAliases);}
}
