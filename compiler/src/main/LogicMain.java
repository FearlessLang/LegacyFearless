package main;

import astFull.Package;
import failure.CompileError;
import failure.Fail;
import files.Pos;
import parser.Parser;
import program.TypeSystemFeatures;
import program.inference.InferBodies;
import program.typesystem.TsT;
import wellFormedness.WellFormednessFullShortCircuitVisitor;
import wellFormedness.WellFormednessShortCircuitVisitor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public interface LogicMain {
  InputOutput io();
  HashSet<String> cachedPkg();
  default TypeSystemFeatures typeSystemFeatures() {
    return new TypeSystemFeatures()
      .hygienics(true)
      .literalPromotions(true);
  }

  void cachePackageTypes(ast.Program program);
  String backendName();

  default astFull.Program parse() {
    var cache = load(io().cachedFiles().stream()
      .filter(p -> p.fileName().getFileName().toString().equals("pkgInfo." + backendName() + ".txt"))
      .toList());
    cachedPkg().addAll(cache.keySet());
    var app = load(io().inputFiles());
    var standardLibOverriden = app.entrySet().stream()
      .filter(s->s.getKey().startsWith("base.") || s.getKey().equals("base") || s.getKey().startsWith("rt.") || s.getKey().equals("rt"))
      .flatMap(s->s.getValue().stream()
        .flatMap(pkg->pkg.ps().stream())
        .map(path->new Pos(path.toUri(), 0, 0))
        .map(pos->new Fail.Conflict(pos, s.getKey()))
      ).toList();
    if (!standardLibOverriden.isEmpty()) {
      throw Fail.specialPackageConflict(standardLibOverriden);
    }
    var packages = new HashMap<>(app);
    if(!cachedPkg().contains("base")){
      packages.putAll(load(io().baseFiles()));
    }
    packages.putAll(cache);//Purposely overriding any app also in cache
    return Parser.parseAll(packages, typeSystemFeatures());
  }
  default void wellFormednessFull(astFull.Program fullProgram){
    new WellFormednessFullShortCircuitVisitor()
      .visitProgram(fullProgram)
      .ifPresent(err->{ throw err; });
  }
  default ast.Program inference(astFull.Program fullProgram){
    return InferBodies.inferAll(fullProgram);
  }
  default void wellFormednessCore(ast.Program program){
    program.ds().values().parallelStream()
      .map(dec->new WellFormednessShortCircuitVisitor(program).visitDec(dec))
      .filter(Optional::isPresent)
      .findAny()
      .flatMap(x->x)
      .ifPresent(err->{ throw err; });
  }
  default ConcurrentHashMap<Long, TsT> typeSystem(ast.Program program){
    var acc= new ConcurrentHashMap<Long, TsT>();
    program.typeCheck(acc);
    return acc;
  }
  default void check() {
    var parsed = this.parse();
    this.wellFormednessFull(parsed);
    var inferred = this.inference(parsed);
    this.wellFormednessCore(inferred);
    this.typeSystem(inferred);
  }


  default Map<String, List<Package>> load(List<Parser> files) {
    // Making this parallel is especially useful for cached situations where each package's type info is actually a
    // substantial chunk of work for ANTLR to parse.
    return files.parallelStream()
      .map(p->p.parseFile(CompileError::err))
      .collect(Collectors.groupingBy(Package::name));
  }
}
