package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import static codegen.zig.RunZigProgramTests.okBase;
import static utils.RunOutput.Res;

/// `base.Debug`, whose Fearless declaration is an inert placeholder until a
/// backend fills it in. It writes to stderr and needs no `System`, matching
/// `assets/rt/Debug`. The other such capability, `System.rng`, is covered by
/// {@link TestZigRng}.
public class TestZigDebug {
  @Test void debugPrintlnStr() { okBase(new Res("", "hello", 0), """
    package test
    Test:Main{ _ -> Debug.println("hello") }
    """, Base.mutBaseAliases);}

  @Test void debugPrintlnNumber() { okBase(new Res("", "42", 0), """
    package test
    Test:Main{ _ -> Debug.println(42) }
    """, Base.mutBaseAliases);}

  /// A value with no `.str` prints as its type name, as the Java backend's
  /// reflective fallback does.
  @Test void debugPrintlnNoStr() { okBase(new Res("", "test.Thingy/0", 0), """
    package test
    Test:Main{ _ -> Debug.println(Thingy) }
    Thingy:{}
    """, Base.mutBaseAliases);}

  /// `Debug#x` prints and yields `x`, so it can sit inside an expression.
  @Test void debugApplyPassesThrough() { okBase(new Res("7", "7", 0), """
    package test
    Test:Main{ sys -> sys.io.println(Debug#(7 .str)) }
    """, Base.mutBaseAliases);}

  @Test void debugIdentifyPrintsNothing() { okBase(new Res("test.Thingy/0", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(Debug.identify(Thingy)) }
    Thingy:{}
    """, Base.mutBaseAliases);}

  @Test void debugIdentifyStr() { okBase(new Res("base.Str/0", "", 0), """
    package test
    Test:Main{ sys -> sys.io.println(Debug.identify("hi")) }
    """, Base.mutBaseAliases);}
}
