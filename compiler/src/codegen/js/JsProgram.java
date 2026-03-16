package codegen.js;

import codegen.MIR;
import id.Id;
import main.js.LogicMainJs;
import utils.IoErr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record JsProgram(List<JsFile> files) {
  public JsProgram(LogicMainJs main, MIR.Program program) {
    this(new ToJsProgram(main, program).of());
  }

  public void writeJsFiles(Path output) {
    IoErr.of(() -> _writeJsFiles(output));
  }

  private void _writeJsFiles(Path output) throws IOException {
    for (var file : files) {
      Path fullPath = output.resolve(file.path());
      String fileName = file.path().getFileName().toString();

      if (fileName.endsWith(".wasm")) {
        // Special handling for WASM files
        Files.createDirectories(fullPath.getParent());
        Files.write(fullPath, file.code().getBytes(StandardCharsets.ISO_8859_1),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      } else {
        // Normal JS files
        Files.createDirectories(fullPath.getParent());
        Files.write(fullPath, file.code().getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      }
    }
  }
}

record ToJsProgram(LogicMainJs main, MIR.Program program) {
  public List<JsFile> of(){
    ArrayList<JsFile> jsFiles = generateFiles();
    List<JsFile> magicFiles = main.io().magicJsFiles(); // rt-js/*
    assert !magicFiles.isEmpty() : "Failed to read magic files";
    jsFiles.addAll(magicFiles);
    return Collections.unmodifiableList(jsFiles);
  }
  // tiny record for staged files
  record Staged(Id.DecId typeId, StringBuilder content) {}

  private void generateInterfaceClasses(JsCodegen gen, Map<Path, Staged> staged) {
    for (MIR.Package pkg : program.pkgs()) {
      String pkgPath = pkg.name().replace(".", "/") + "/";
      for (MIR.TypeDef def : pkg.defs().values()) {
        Id.DecId typeId = def.name();
        var funsList = pkg.funs().stream()
          .filter(f -> f.name().d().equals(typeId))
          .toList();

        String typeDefContent = gen.visitTypeDef(pkg.name(), def, funsList);
        if (typeDefContent.isEmpty()) continue;

        String fileName = gen.id.getSimpleName(typeId) + ".js";
        Path filePath = Path.of(pkgPath).resolve(fileName);

        StringBuilder sb = new StringBuilder();
        sb.append(typeDefContent);
        staged.put(filePath, new Staged(typeId, sb));
      }
    }
  }

  private void generateImplementationClasses(JsCodegen gen, Map<Path, Staged> staged) {
    for (var e : gen.freshImpls.entrySet()) {
      Id.DecId typeId = e.getKey();
      String implContent = e.getValue();
      Path filePath = Path.of(typeId.pkg().replace(".", "/"))
        .resolve(gen.id.getSimpleName(typeId) + ".js");
      StringBuilder sb;
      if (staged.containsKey(filePath)) {
        sb = staged.get(filePath).content();
      } else {
        sb = new StringBuilder();
        staged.put(filePath, new Staged(typeId, sb));
      }
      sb.append("\n\n").append(implContent);
      // If there’s a queued singleton assignment, append it now
      String className = gen.id.getFullName(typeId);
      if (gen.freshSingletons.containsKey(className)) {
        sb.append("\n").append(gen.freshSingletons.get(className));
      }
    }
  }

  private void finalizeWithImports(JsCodegen gen, Map<Path, Staged> staged, List<JsFile> jsFiles) {
    for (var entry : staged.entrySet()) {
      Path filePath = entry.getKey();
      Staged stagedInfo = entry.getValue();
      Id.DecId typeId = stagedInfo.typeId();
      String combinedContent = stagedInfo.content().toString();

      String importLines = computeImports(typeId.pkg(), gen.id.getFullName(typeId), combinedContent);

      jsFiles.add(new JsFile(filePath, importLines + combinedContent));
    }
  }

  public ArrayList<JsFile> generateFiles() {
    var jsFiles = new ArrayList<JsFile>();
    var gen = new JsCodegen(program);
    // tiny record for staged files
    Map<Path, Staged> staged = new LinkedHashMap<>();
    // --- First pass: generate interface defs ---
    generateInterfaceClasses(gen, staged);
    // --- Second pass: attach Impl classes ---
    generateImplementationClasses(gen, staged);
    // --- Finalize files with imports ---
    finalizeWithImports(gen, staged, jsFiles);
    return jsFiles;
  }

  private static final Pattern STRING_OR_COMMENT = Pattern.compile(
    "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`|//.*?$|/\\*.*?\\*/)",
    Pattern.DOTALL | Pattern.MULTILINE
  );

  private String computeImports(String pkg, String className, String content) {
    Set<String> importLines = new TreeSet<>();
//    boolean needsEnsureWasm = false;

    // Strip out strings and comments
    String sanitized = STRING_OR_COMMENT.matcher(content).replaceAll(" ");
    // Match flattened names like base$$iter$$Sum_0, base$$iter$$Sum_0Impl, base$$_InfoToJson_0, test$$Test_0
    Pattern p = Pattern.compile(
      "([a-z][a-z0-9_]*(?:\\$\\$[a-z][a-z0-9_]*)*\\$\\$[A-Za-z_][A-Za-z0-9_$]*_\\d+)(Impl)?"
    );
    Matcher m = p.matcher(sanitized);
    while (m.find()) {
      String base = m.group(1);
      String impl = m.group(2);
      String dep = (impl != null) ? base + "Impl" : base;
      if (dep.equals(className) || dep.equals(className + "Impl")) continue;

      String importPath = getRelativeImportPath(pkg, base);
      importLines.add("import { " + dep + " } from \"" + importPath + "\";");
    }

    // runtime helpers
    Pattern rt = Pattern.compile("\\brt\\$\\$([A-Za-z0-9_]+)\\b");
    Matcher rm = rt.matcher(sanitized);
    while (rm.find()) {
      String dep = "rt$$" + rm.group(1);
      String importPath = getRelativeImportPath(pkg, "rt-js/" + rm.group(1));
      importLines.add("import { " + dep + " } from \"" + importPath + "\";");
//      if (dep.equals("rt$$NativeRuntime")) needsEnsureWasm = true;
    }

    if (importLines.isEmpty()) { return ""; }

    StringBuilder importBlock = new StringBuilder();
    for (String imp : importLines) {
      importBlock.append(imp).append("\n");
    }
//    if (needsEnsureWasm) {
//      importBlock.append("(async function(){ await rt$$NativeRuntime.ensureWasm(); })();\n\n"); // now only the rt-js/* use rt$$NativeRuntime
//    }
    return importBlock.append("\n").toString();
  }

  private String getRelativeImportPath(String pkg, String encodedDep) {
    String[] parts = encodedDep.split("\\$\\$");
    String depPkg = String.join("/", Arrays.copyOf(parts, parts.length - 1));
    String depFile = parts[parts.length - 1];
    String depPath = depPkg.isEmpty() ? depFile : depPkg + "/" + depFile;

    // compute relative path from currentPkg
    String currentPath = pkg.isEmpty() ? "" : pkg.replace(".", "/");
    int depth = currentPath.isEmpty() ? 0 : currentPath.split("/").length;

    StringBuilder prefix = new StringBuilder();
    if (depth == 0) {
      prefix.append("./");
    } else {
      for (int i = 0; i < depth; i++) {
        prefix.append("../");
      }
    }
    return prefix + depPath + ".js";
  }

}