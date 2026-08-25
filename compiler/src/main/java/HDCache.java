package main.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ast.T.Dec;
import codegen.MIR;
import main.LogicMain;
import utils.IoErr;
import ast.T;

public record HDCache(Path code, ast.Program program, String backend) {
  public static void cachePackageTypes(LogicMain main, ast.Program program) {
    Map<String,List<T.Dec>> mapped= program.ds().values().stream()
     .filter(d->!main.cachedPkg().contains(d.name().pkg()))
     .collect(Collectors.groupingBy(d->d.name().pkg()));
    mapped.forEach((key, value)->{
      var cache = new HDCache(main.io().output(), program, main.backendName());
      cache.cacheTypeInfo(key, value);
    });
    new HDCache(main.io().output(), program, main.backendName()).cacheBase(main.io().cachedBase());
  }
  
  public HDCache{ assert Files.exists(code) && Files.isDirectory(code):code; }
  
  public void cacheBase(Path cachedBase){ IoErr.of(()->_cacheBase(cachedBase)); }
  private void _cacheBase(Path cachedBase) throws IOException {
    assert Files.exists(cachedBase) && Files.isDirectory(cachedBase) : cachedBase+" "+code;
    Path basePath = code.resolve("base");
    // Check if basePath exists and is a directory
    if (!Files.exists(basePath)){ return; }
    assert Files.isDirectory(basePath);
    Path targetPath = cachedBase.resolve("base");
     if (!Files.exists(targetPath)) {
      Files.move(basePath, targetPath);
    }
  }
  public void cacheTypeInfo(String pkgName, List<Dec> decs) {
    var pkg = code.resolve(pkgName.replace(".","/"));
    IoErr.of(() -> Files.createDirectories(pkg));
    var file=decs.stream().map(d->new DecTypeInfo().visitDec(d)).toList();
    String tot="package "+pkgName+"\n"+String.join("", file);
    IoErr.of(()->Files.writeString(pkg.resolve("pkgInfo." + backend + ".txt"),tot));
  }

  /// What every type this package declares is implemented by, beside its type information. See
  /// {@link ImplInfo} for why `pkgInfo` cannot hold it. Written from the lowered program, so a
  /// caller holds one.
  public void cacheImplInfo(String pkgName, MIR.Program mir) {
    var pkg = code.resolve(pkgName.replace(".","/"));
    IoErr.of(() -> Files.createDirectories(pkg));
    var text = ImplInfo.of(pkgName, program, mir).write();
    IoErr.of(()->Files.writeString(pkg.resolve(ImplInfo.fileName(backend)),text));
  }
}

/*
If cached exists,
load cached instead of /base into the program.

At code generation time, if cached exists,
do not regenerate anything in the base pkg

*/