package tour;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import utils.Base;
import utils.RunOutput;

import static codegen.java.RunJavaProgramTests.ok;
import static tour.TourHelper.*;
public class Ex02StringsAndNumbersTest {
/*
# Strings and numeric types

# Numeric types

The Fearless standard library offers the following numeric types:

- `Nat` : unsigned 64 bit integers
- `Int` : signed 64 bit integers
- `Float` : double precision 64 bit floating points according to specification IEEE 754
- `Num` : Unlimited precision fractional numbers
//Is this all?
//We need to discuss how to fix this: not Stringable due to limitations of the Java codegen target
//I had the same exact problem in 42 and I fixed it.

With `T` in `Nat`,`Int`,`Float`,`Num`, they support the following conventional operations:
``` 
  + (n: T): T,
  - (n: T): T,
  * (n: T): T,
  / (n: T): T,
  **(n: T): T, // there is a good reason for pow to have different signatures
  % (n: T): T, //really, also for floats? Java supports it, do we want to?
  .abs: T, //really? also for Nat?
  > (n: T): Bool,
  < (n: T): Bool,
  >=(n: T): Bool,
  <=(n: T): Bool,
  ==(n: T): Bool,
  .assertEq(actual: T): Void,
  .assertEq(actual: T, message: Str): Void,
```
This means that we can only operate on homogeneous numeric types.
We can sum two `Int` but we can not directly sum `Int` and `Float`.
In other languages, this is allowed through a process called coercion.
We think that coercion is the source of plenty of bugs.
Instead, in Fearless you convert numeric types to each other manually, by using those methods:
```
  read .nat: Nat,
  read .int: Int,
  read .float: Float,
  read .num: Num,
```
All numeric types support all of those methods, so `myInt.int` is just going to just return itself.
They take a `read` receiver, so that if somehow the type system has lost the knowledge that a numeric type is immutable, we can recover it. This is safe since all instances of numeric types are always immutable.
This can happen for example when generic code is used, and integers are passed to satisfy a `read` parameter.

`Int` and `Nat` support also binary operations:

```
  .shiftRight(n: Nat): T,
  .shiftLeft(n: Nat): T,
  .xor(n: T): T,
  .and(n: T): T,
  .or(n: T): T,
```  
It is however quite rare to need those operations in Fearless code.
  
Finally, all the numeric types have a method
```
  read .str: Str,
```
returning a string.
//#   We are using `read` everywhere, since `readH` will transparently work too
//#   due to our read promotion rules (i.e. takes no args & returns an imm)

The code below shows those numeric types in action.
-------------------------*/@Disabled("Disabling because this takes so long to compile due to flexible literal typing.") @Test void numericTypes() { run("""
    Test:Main {sys ->Block#
      .let ten = {10}// the Nat 10, 64 bit unsigned
      .let pTen = {+10} //Int +10, 64 bit signed
      .let mTen = {-10} //Int -10, 64 bit signed
      .let zero = {pTen + mTen}
      .let fTen = {10.0}
      .let fTenAlt = {+10.0}//both forms allowed
    //#re-add      .let frac = {34554329/14456354}//unlimited precision fractional
      .let justNat =  {34554329 / 14456354}
      //# we discussed this at length, the / is good (we considered @ and : as alternatives, decided it was worse)
      .let ok = {+10 + -10}
    //.let err = {+10 + - 10}//error: method Int.+() does not exists
      //# this is less dramatic, since - 10 is really unnatural
      .let mixTypes1 = {fTen + (ten.float)}
      .let mixTypes2 = {fTen.nat + ten} //very different results!
      .return {sys.io.println(ten.str)}
      }
    //prints 10
    """); }/*--------------------------------------------

To defend our choice to have no automatic conversions, consider
the two local bindings `mixTypes1` and `mixTypes2`: they produce different results, especially for very large numbers.
If you want to avoid worrying about representation details, consider just using `Num` all of the time.

# Strings

Fearless `Str` encode sequences of Unicode codepoints, using Unicode (UTF-8) as its internal encoding.
For simplicity Fearless do not have a dedicated codepoint type, and elements of the string are strings of length one.
Indexing operations like `.size`, `.substring` and `.charAt` are codepoint-indexed, so they are stable and Unicode-version-independent.
For extended grapheme clusters (Unicode Annex 29) you can opt in via `.graphemes`, which is Unicode-version-dependent.
Here the methods supported by Str:
```
  read .str: Str,
//#re-add   read .int: mut Action[Int],
//#re-add   read .nat: mut Action[Nat],
//#re-add   read .float: mut Action[Float],
//#re-add   read .num: mut Action[Num],
  .size: Nat, // codepoint count
  .isEmpty: Bool,
  +(other: Stringable): Str,
  ==(other: Str): Bool,
  !=(other: Str): Bool,
  .substring(begin: Nat, end: Nat): Str, // codepoint-indexed
  .substringLast(begin: Nat,end: Nat): Str,
  .charAt(index: Nat): Str, // codepoint-indexed
  .normalise: Str,
//#re-add .startsWith(s: Str): Bool,
//#re-add .endsWith(s: Str): Bool,
//#re-add .indexOf(s: Str): Opt[Nat],
//#re-add .lastIndexOf(s: Str): Opt[Nat],
//#re-add .replaceAll(old:Str,new:Str): Str,
  .assertEq(other: Str): Void,
  .assertEq(other: Str, message: Str): Void,
  .codepoints, // flow over codepoints
  .graphemes,  // flow over extended grapheme clusters
  mut .append(str: Str): Void,
  mut .truncate(index: Nat): Void,
  mut .clear: Void
```
As you can see, strings support '+' to concatenate with other stringables, and the numeric types are all Stringable, so we can 
write code like the following:
-------------------------*/@Disabled("03/12/24") @Test void stringable() { run("""
    //as defined in the standard library
    //Stringable: {read .str: Str, }

    Persons: F[Str,Nat,Person]{name,age -> Person: Stringable{
      .name: Str -> name,
      .age: Nat -> age,
      .str -> "Person["+name+", "+age+"]"
      }}
    Test:Main {sys ->
      UnrestrictedIO#sys.println(Persons#("Bob",43))
      }
    //prints Person[Bob, 43]
    """); }/*--------------------------------------------

Method `Str.substring(begin,end)` takes substrings of a string.
Method `Str.substringLast(begin,end)` is the same but starts from the end, so `"qwertyuiop".substringLast(5,1)` returns the 4 characters string `"yuio"`: the last 5 characters except the last one.
Using those methods with indexes outside the string is an observed bug and will produce an error.

As you can see, Fearless strings also support some mutable operations, notably
  `mut .append(str: Str): Void`
That is, while in Java we have Strings and StringBuilders,
Fearless use immutable and mutable strings. Mutable strings have to be created explicitly, as in `mut"Hello"`.
They can be used as an efficient accumulator, and then turned into an immutable string via promotion.

The method .str also exists on strings in Fearless, but it does not simply return the
identity as it does for numeric types. This of course is crucial when the string is mutable.

Method `Str.int` can be used to convert the string into an integer.
It returns a `mut Action[Int]`. We discuss the Action type in details later, for now just know that you use  `!` to extract the parsed value as shown below

-------------------------*/@Disabled("03/12/24") @Test void parseInt() { run("""
    Test:Main {sys -> Block#
      .let[Str] s= {"43"}
      .let[Int] i = {s.int!}
      .return {Debug.println(43*2)}
      }
    //prints 86
    """); }/*--------------------------------------------*/

//#STOP

  @Test void unsignedUnderflowOverFlow() { ok(new RunOutput.Res("45", "", 0), """
    package test
    Test:Main {sys -> UnrestrictedIO#sys.println(((15 - 20) + 50).str)}
    """, Base.mutBaseAliases);}

  @Test void intDivByZero() { ok(new RunOutput.Res("", "Program crashed with: / by zero[###]", 1), """
    package test
    Test:Main {sys -> Block#(+5 / +0) }
    """, Base.mutBaseAliases);}
  @Test void uIntDivByZero() { ok(new RunOutput.Res("", "Program crashed with: / by zero[###]", 1), """
    package test
    Test:Main {sys -> Block#(5 / 0) }
    """, Base.mutBaseAliases);}
  @Test void floatDivByZero() { ok(new RunOutput.Res("", "", 0), """
    package test
    Test:Main {sys -> Assert!((5.0 / 0.0).isInfinite) }
    """, Base.mutBaseAliases);}

  @Test void nullByteInStr() {ok(new RunOutput.Res("Hi\u0000HelloBeer", "", 0), """
    package test
    Test: Main{sys -> sys.io.println("Hi\\u{0}Hello" + "Beer")}
    """, Base.mutBaseAliases);}
  @Test void nullByteSize() {ok(new RunOutput.Res("8", "", 0), """
    package test
    Test: Main{sys -> sys.io.println("Hi\\u{0}Hello".size.str)}
    """, Base.mutBaseAliases);}
}