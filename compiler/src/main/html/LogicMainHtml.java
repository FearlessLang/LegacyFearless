package main.html;

import ast.Program;
import codegen.html.HtmlDocgen;
import main.InputOutput;
import main.LogicMain;
import utils.IoErr;
import utils.ResolveResource;

import java.nio.file.Files;
import java.util.HashSet;

public interface LogicMainHtml extends LogicMain {
  static LogicMainHtml of(InputOutput io){
    var cachedPkg = new HashSet<String>();
    return new LogicMainHtml(){
      public InputOutput io(){ return io; }
      public HashSet<String> cachedPkg(){ return cachedPkg; }
      @Override public String backendName() { return "html"; }
    };
  }

  default HtmlDocgen.ProgramDocs generateDocs() {
    var parsed = this.parse();
    this.wellFormednessFull(parsed);
    var inferred = this.inference(parsed);
    this.wellFormednessCore(inferred);
    return new HtmlDocgen(inferred).visitProgram();
  }

  default void writeDocs() {
    var docs = generateDocs();
    var styleCss = ResolveResource.getAndReadAsset("/style.css");
    var highlightingJs = ResolveResource.getAndReadAsset("/highlighting.js");
    var root = io().output().resolve("docs");
    IoErr.of(()->{
      Files.createDirectories(root);
      Files.writeString(root.resolve(docs.fileName()), docs.index());
      Files.writeString(root.resolve("style.css"), styleCss);
      Files.writeString(root.resolve("highlighting.js"), highlightingJs);
      for (var pkg : docs.docs()) {
        var links = pkg.links();
        Files.writeString(root.resolve(pkg.fileName()), pkg.index(links));
        for (var trait : pkg.traits()) {
          Files.writeString(root.resolve(trait.fileName()), trait.html(links));
        }
      }
    });
  }

  @Override default void cachePackageTypes(Program program) {}
}
