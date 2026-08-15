package codegen.zig;

import codegen.MIR;
import codegen.ParentWalker;
import id.Id;
import main.InputOutput;
import main.zig.LogicMainZig;
import main.Main;
import org.junit.jupiter.api.Test;
import utils.IoErr;
import utils.ResolveResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Mearless objects have no inheritance. Every callable method exists in on the object,
/// and inheritance is modeled by sharing a Mearless Function as the implementation of that method.
public class TestZigVTableCompleteness {
  private static final Pattern FEARLESS_TYPE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*/\\d+");

  /// `<rt impl for pkg.Name/N>`, used by the vtables that stand in for a
  /// Fearless type without being it (`Var`, `IsoPod`). They still have to serve
  /// that type's whole surface, so the named type is what gets checked.
  private static final Pattern RT_IMPL_FOR = Pattern.compile("<rt impl for (.+)>");

  private static final Pattern VTABLE_DECL = Pattern.compile("objs\\.VTable\\s*=\\s*\\.\\{");
  private static final Pattern TYPE_NAME_FIELD = Pattern.compile("\\.type_name\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern METHOD_NAMES_FIELD = Pattern.compile("\\.method_names\\s*=\\s*&\\.\\{");
  private static final Pattern DISPATCH_FN = Pattern.compile("pub fn dispatch\\s*\\(");
  private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");
  private static final Pattern HASH_CALL = Pattern.compile("\\bh\\(\"([^\"]*)\"\\)");

  @Test void handAuthoredVTablesAreComplete() {
    var claimed = scanRuntime();
    assertTrue(claimed.size() > 10, "Found suspiciously few FeaRT vtables: " + claimed.keySet());

    var mir = loadBase();
    var sigBuilder = new ZigSigStringBuilder(mir.p());
    var problems = new ArrayList<String>();

    claimed.forEach((typeName, found) -> {
      var decId = toDecId(typeName);
      MIR.TypeDef typeDef;
      try {
        typeDef = mir.of(decId);
      } catch (java.util.NoSuchElementException e) {
        problems.add(typeName + ": names no type in the base program. Either fix the "
          + "type_name or, for a runtime-only helper, use the <runtime ...> form.");
        return;
      }
      var expected = ParentWalker.leastSpecificSigs(mir, typeDef).values().stream()
        .map(sig -> unquote(sigBuilder.sigString(sig)))
        .collect(Collectors.toCollection(TreeSet::new));
      var missing = new TreeSet<>(expected);
      missing.removeAll(found.signatures);
      if (!missing.isEmpty()) {
        problems.add(typeName + " (" + String.join(", ", found.sources) + ") is missing "
          + missing.size() + " method(s): " + String.join(", ", missing));
      }
    });

    if (!problems.isEmpty()) {
      fail("Hand-authored FeaRT vtables are incomplete. A vtable must list every method of "
        + "its type, including inherited ones and ones with a Fearless default body.\n"
        + String.join("\n", problems));
    }
  }

  /// The signatures a Fearless type is served by, and the Zig declarations that
  /// supply them (for the failure message).
  private record Claim(Set<String> signatures, Set<String> sources) {
    Claim() { this(new LinkedHashSet<>(), new LinkedHashSet<>()); }
  }

  private Map<String, Claim> scanRuntime() {
    var root = ZigCompiler.feartRoot().resolve("src").resolve("runtime");
    List<Path> files;
    try (var walk = Files.walk(root)) {
      files = walk.filter(p -> p.toString().endsWith(".zig")).sorted().toList();
    } catch (IOException e) {
      throw new RuntimeException("Could not walk " + root, e);
    }

    var claimed = new LinkedHashMap<String, Claim>();
    for (var file : files) {
      var src = IoErr.of(() -> Files.readString(file));
      var label = root.relativize(file).toString();
      for (var vt : vtablesIn(src)) {
        var claimedType = claimedTypeOf(vt.typeName);
        if (claimedType == null) { continue; }
        var claim = claimed.computeIfAbsent(claimedType, k -> new Claim());
        claim.signatures.addAll(vt.methodNames);
        claim.sources.add(label);
        // The primitive types bypass vtable lookup: their `dispatch` switch is
        // the real method table, so its `h("...")` arms count as slots.
        if (vt.isPrimitive) {
          var arms = dispatchArms(src);
          claim.signatures.addAll(arms);
        }
      }
    }
    return claimed;
  }

  /// The Fearless type a `type_name` claims to serve, or null when it names a
  /// runtime-only helper with no Fearless analogue.
  private String claimedTypeOf(String typeName) {
    if (FEARLESS_TYPE_NAME.matcher(typeName).matches()) { return typeName; }
    var rtImpl = RT_IMPL_FOR.matcher(typeName);
    if (rtImpl.matches() && FEARLESS_TYPE_NAME.matcher(rtImpl.group(1)).matches()) {
      return rtImpl.group(1);
    }
    return null;
  }

  private record ZigVTable(String typeName, List<String> methodNames, boolean isPrimitive) {}

  private List<ZigVTable> vtablesIn(String src) {
    var out = new ArrayList<ZigVTable>();
    var decl = VTABLE_DECL.matcher(src);
    while (decl.find()) {
      var body = balanced(src, decl.end() - 1);
      if (body == null) { continue; }
      var name = TYPE_NAME_FIELD.matcher(body);
      if (!name.find()) { continue; }
      out.add(new ZigVTable(name.group(1), methodNamesIn(body),
        body.contains(".storage_mode = .primitive")));
    }
    return out;
  }

  private List<String> methodNamesIn(String body) {
    var field = METHOD_NAMES_FIELD.matcher(body);
    if (!field.find()) { return List.of(); }
    var list = balanced(body, field.end() - 1);
    if (list == null) { return List.of(); }
    var out = new ArrayList<String>();
    var lit = STRING_LITERAL.matcher(list);
    while (lit.find()) { out.add(lit.group(1)); }
    return out;
  }

  private List<String> dispatchArms(String src) {
    var fn = DISPATCH_FN.matcher(src);
    if (!fn.find()) { return List.of(); }
    var brace = src.indexOf('{', fn.end());
    if (brace < 0) { return List.of(); }
    var body = balanced(src, brace);
    if (body == null) { return List.of(); }
    var out = new ArrayList<String>();
    var call = HASH_CALL.matcher(body);
    while (call.find()) { out.add(call.group(1)); }
    return out;
  }

  /// The text between the brace at `open` and its match, exclusive. Zig has no
  /// brace-bearing string escapes in these declarations, so counting suffices.
  private String balanced(String src, int open) {
    var depth = 0;
    for (var i = open; i < src.length(); i++) {
      var c = src.charAt(i);
      if (c == '{') { depth++; }
      else if (c == '}') {
        depth--;
        if (depth == 0) { return src.substring(open + 1, i); }
      }
    }
    return null;
  }

  private Id.DecId toDecId(String typeName) {
    var slash = typeName.lastIndexOf('/');
    return new Id.DecId(typeName.substring(0, slash), Integer.parseInt(typeName.substring(slash + 1)));
  }

  private String unquote(String quoted) {
    return quoted.substring(1, quoted.length() - 1);
  }

  /// The front end up to lowering, for the smallest program that pulls in all of
  /// `base`. Stopping before code generation keeps this off the Zig toolchain.
  private MIR.Program loadBase() {
    Main.resetAll();
    var workingDir = ResolveResource.freshTmpPath();
    IoErr.of(() -> Files.createDirectories(workingDir));
    var io = InputOutput.programmatic(
      "test.Test",
      List.of(),
      List.of("package test\nTest:base.Main{ _ -> {} }\n"),
      workingDir,
      ResolveResource.artefact("/cachedBase")
    );
    var logicMain = LogicMainZig.of(io, new main.CompilerFrontEnd.Verbosity(
      false, false, main.CompilerFrontEnd.ProgressVerbosity.None));
    var fullProgram = logicMain.parse();
    logicMain.wellFormednessFull(fullProgram);
    var program = logicMain.inference(fullProgram);
    logicMain.wellFormednessCore(program);
    var resolvedCalls = logicMain.typeSystem(program);
    return logicMain.lower(program, resolvedCalls);
  }
}
