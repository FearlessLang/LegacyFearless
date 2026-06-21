package codegen.zig;

import org.junit.jupiter.api.Test;
import utils.Base;

import java.util.List;
import java.util.Map;

import static codegen.zig.RunZigProgramTests.okBase;
import static codegen.zig.RunZigProgramTests.okBaseInDir;
import static utils.RunOutput.Res;

/// Filesystem IO (`accessR`/`accessW`/`accessRW`, `readStr`) and the `Env`
/// capability (`launchArgs`).
public class TestZigFileIO {
  /// Building a `readStr` action does not touch the filesystem: the file does
  /// not exist, yet no error surfaces because the action is lazy.
  @Test void readStrIsLazy() { okBase(new Res("built", "", 0), """
    package test
    Test:Main{sys -> mut Block[Void]
      .let[mut Action[Str]] a = { sys.io.accessR(List#[Str]("nope.txt")).readStr }
      .return{ sys.io.println("built") }
      }
    """, Base.mutBaseAliases); }

  /// Running `readStr` on a missing file delivers `m.info`.
  @Test void readStrNotFound() { okBaseInDir(new Res("", "File not found: [###]", 0), Map.of(), List.of(), """
    package test
    Test:Main{sys -> sys.io.accessR(List#[Str]("nope.txt")).readStr.run{
      .ok(s) -> sys.io.println(s),
      .info(i) -> sys.io.printlnErr(i.msg),
      }}
    """, Base.mutBaseAliases); }

  /// Running `readStr` on an existing file delivers its contents to `m.ok`.
  @Test void readStrReadsFile() { okBaseInDir(new Res("hello", "", 0), Map.of("data.txt", "hello"), List.of(), """
    package test
    Test:Main{sys -> sys.io.accessR(List#[Str]("data.txt")).readStr.run{
      .ok(s) -> sys.io.println(s),
      .info(i) -> sys.io.printlnErr(i.msg),
      }}
    """, Base.mutBaseAliases); }

  /// Navigating out of a scoped path handle with `..` is rejected with a
  /// deterministic error naming the scope. The escaping access runs inside a
  /// `Try` fed `sys.io.iso` so the captured capability recovers to `mut`,k
  /// letting the error be caught rather than crashing the program. The lambda
  /// resolves to a `Str` (a `Try` result must be `imm`); the access throws
  /// before `readStr.run` is reached, so the value itself is never produced.
  @Test void accessScopeEscape() { okBase(new Res("", "[###]scoped under[###]", 0), """
    package test
    Test:Main{sys -> sys.io.printlnErr(
      Try#(sys.io.iso, {io -> io.accessRW(List#[Str]("sub")).accessR(List#[Str]("..")).readStr.run{
        .ok(s) -> s,
        .info(i) -> i.msg,
        }}).info!.msg
      )}
    """, Base.mutBaseAliases); }

  /// `Env.launchArgs` exposes the command-line arguments (argv[0] excluded).
  @Test void envLaunchArgsSize() { okBaseInDir(new Res("2", "", 0), Map.of(), List.of("alpha", "beta"), """
    package test
    Test:Main{sys -> sys.io.println(sys.io.env.launchArgs.size .str)}
    """, Base.mutBaseAliases); }

  @Test void envLaunchArgsValues() { okBaseInDir(new Res("alpha,beta", "", 0), Map.of(), List.of("alpha", "beta"), """
    package test
    Test:Main{sys -> sys.io.println(sys.io.env.launchArgs.iter.str({s -> s.str}, ","))}
    """, Base.mutBaseAliases); }
}
