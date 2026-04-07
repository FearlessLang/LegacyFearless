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

public record HDCache(Path code, ast.Program program) {
  public static void cachePackageTypes(LogicMain main, ast.Program program) {
    Map<String,List<T.Dec>> mapped= program.ds().values().stream()
     .filter(d->!main.cachedPkg().contains(d.name().pkg()))
     .collect(Collectors.groupingBy(d->d.name().pkg()));
    mapped.forEach((key, value)->new HDCache(main.io().output(), program).cacheTypeInfo(key, value));
    new HDCache(main.io().output(), program).cacheBase(main.io().cachedBase());
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
    IoErr.of(()->Files.writeString(pkg.resolve("pkgInfo.txt"),tot));
  }
}

/*
If cached exists,
load cached instead of /base into the program.

At code generation time, if cached exists,
do not regenerate anything in the base pkg

*/