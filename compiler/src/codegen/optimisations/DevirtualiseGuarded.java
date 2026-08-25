package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import magic.LiteralKind;
import magic.Magic;
import magic.MagicImpls;
import main.CompilationUnit;
import main.java.ImplInfo;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Devirtualises a call from the declarations of one package.
///
/// A type is declared in one package. When that package writes exactly one implementation of it,
/// a call on a receiver of that type becomes a {@link MIR.GuardedCall}: a test of the receiver
/// vtable, the wrapper of that implementation, and the virtual call for anything else. A receiver
/// written as a literal at the call site already names its concrete type, so that call becomes an
/// unconditional {@link MIR.DirectCall}.
///
/// The count reads the types the package declares and the lambdas written inside their method
/// bodies. A factory that returns the only lambda of a type is as common as a type that
/// implements it by name, and both are implementations the package writes.
///
/// The count reads only that one package, which the compiler either compiles now or loaded as
/// cached type information. Any OTHER package can write another implementation, whether it is in
/// this program, beside it, or written years from now. Each of those is the same case: the test
/// fails for such a value and the call goes the virtual way. So nothing here depends on seeing
/// the whole program, and caching changes no answer.
///
/// The guess holds because most implementations of a type sit beside its declaration. Where that
/// is false, the cost is one compare on a call that was virtual anyway.
///
/// {@link RapidTypeAnalysis} cannot work this way. Its target carries no test, so it must see
/// every literal that reaches a receiver, and the literals of a package read back from its type
/// information are absent.
public class DevirtualiseGuarded implements MIRCloneVisitor {
  private final MagicImpls<?> magic;
  private final ImplInfo cachedImpls;
  /// The packages whose implementations `cachedImpls` already holds. Their declarations are read
  /// from that file rather than from the AST, which for such a package carries only what its type
  /// information kept.
  private final Set<String> sidecarPkgs;

  private ast.Program ast;
  /// Every declaration the compiler read: the types a package writes, and the lambdas written
  /// inside their bodies.
  private final Set<Id.DecId> candidates = new LinkedHashSet<>();
  private final Map<Id.DecId, Optional<Id.DecId>> soleImpl = new HashMap<>();
  private final Map<Id.DecId, MIR.TypeDef> defs = new HashMap<>();
  /// Every method that has a body, as (owner, name, receiver modifier).
  private final Set<String> bodies = new LinkedHashSet<>();
  /// The types of the packages lowered from source whose methods are magic stubs. The runtime
  /// makes the values that answer such a call, with a vtable of their own, so the literal beside
  /// the declaration implements nothing. A package read back from its type information holds
  /// stubs for every method and says nothing here: what it implements is read from its sidecar.
  private final Set<Id.DecId> runtimeBacked = new LinkedHashSet<>();

  /// The package whose code is being rewritten. A call site inside a {@link CompilationUnit}
  /// may only name a target of that same unit, because the two are cached together and read
  /// back together.
  private String emitPkg = "";

  private final Set<Id.DecId> targets = new LinkedHashSet<>();
  private int guardedCalls = 0;
  private int directCalls = 0;

  public DevirtualiseGuarded(MagicImpls<?> magic) {
    this(magic, ImplInfo.EMPTY, Set.of());
  }

  /// `cachedImpls` is what every package whose generated code is cached counted about itself
  /// when it was lowered from source. Without it such a package contributes a floor, because its
  /// cached type information keeps method headers and top level declarations only, and most
  /// implementations in the base library are object literals inside a body.
  public DevirtualiseGuarded(MagicImpls<?> magic, ImplInfo cachedImpls, Set<String> sidecarPkgs) {
    this.magic = magic;
    this.cachedImpls = cachedImpls;
    this.sidecarPkgs = Set.copyOf(sidecarPkgs);
  }

  public Set<Id.DecId> targets() { return targets; }
  public int guardedCalls() { return guardedCalls; }
  public int directCalls() { return directCalls; }

  @Override public MIR.Program visitProgram(MIR.Program p) {
    this.ast = (ast.Program) p.p();
    this.soleImpl.clear();
    this.defs.clear();
    this.bodies.clear();
    this.candidates.clear();
    this.candidates.addAll(ast.ds().keySet());
    this.candidates.addAll(ast.inlineDs().keySet());
    this.runtimeBacked.clear();
    for (var pkg : p.pkgs()) {
      for (var def : pkg.defs().values()) { defs.put(def.name(), def); }
      for (var fun : pkg.funs()) {
        bodies.add(key(fun.name().d(), fun.name().m(), fun.name().mdf()));
        if (!sidecarPkgs.contains(pkg.name()) && Magic.isMagicStub(fun.body())) {
          runtimeBacked.add(fun.name().d());
        }
      }
    }
    return MIRCloneVisitor.super.visitProgram(p);
  }

  @Override public MIR.Package visitPackage(MIR.Package pkg) {
    this.emitPkg = pkg.name();
    return MIRCloneVisitor.super.visitPackage(pkg);
  }

  private static String key(Id.DecId owner, Id.MethName name, id.Mdf mdf) {
    return owner + "|" + name + "|" + mdf;
  }

  @Override public MIR.E visitMCall(MIR.MCall call, boolean checkMagic) {
    var visited = MIRCloneVisitor.super.visitMCall(call, checkMagic);
    if (!(visited instanceof MIR.MCall rewritten)) { return visited; }
    if (!isPlain(rewritten)) { return visited; }
    if (magic.get(rewritten.recv()).isPresent()) { return visited; }

    if (rewritten.recv() instanceof MIR.CreateObj literal) {
      var exact = literal.concreteT().id();
      if (!callable(exact, rewritten)) { return visited; }
      targets.add(exact);
      directCalls++;
      return new MIR.DirectCall(rewritten, exact);
    }

    var declared = rewritten.recv().t().name();
    if (declared.isEmpty()) { return visited; }
    var target = soleImplOf(declared.get());
    if (target.isEmpty() || !callable(target.get(), rewritten)) { return visited; }
    targets.add(target.get());
    if (isFinal(declared.get()) || isEffectivelyFinal()) {
      directCalls++;
      return new MIR.DirectCall(rewritten, target.get());
    }
    guardedCalls++;
    return new MIR.GuardedCall(rewritten, target.get());
  }

  /// Whether the one implementation the join found is the only one this call can ever reach,
  /// guaranteed by the declaration rather than by what this compilation happens to hold. Such a
  /// call needs no test, and no later compilation can take that away.
  ///
  /// Two declarations give the guarantee. A named inline declaration, which nothing may
  /// implement. A sealed type, which only its own package may implement, and which therefore
  /// counted every implementation it will ever have.
  private boolean isFinal(Id.DecId declared) {
    var known = cachedImpls.get(declared);
    if (known.map(ImplInfo.Entry::inlineDec).orElseGet(() -> ast.isInlineDec(declared))) {
      return true;
    }
    return known.map(ImplInfo.Entry::sealed).orElseGet(() -> isSealed(declared));
  }

  /// Whether one implementation is enough for THIS compilation, which is true where the
  /// rewritten code never outlives it.
  ///
  /// The join reads a sidecar for every package whose code is cached and the lowered program for
  /// every package compiled now, so between them they name every value this program can make.
  /// That is what {@link RapidTypeAnalysis} computes, reached without any pass reading more than
  /// one package at a time. A package outside every {@link CompilationUnit} is lowered again by
  /// each program that names it, so that answer is the whole answer and a count of one is final.
  ///
  /// A package a unit holds is written once and read by later compilations, which hold packages
  /// this join cannot see. There the count of one is a guess, and the guess carries a test.
  private boolean isEffectivelyFinal() { return !CompilationUnit.isCached(emitPkg); }

  /// Whether `target` is cached with the code being written, so that a name of it survives into
  /// every compilation that reads that code back. A package no unit holds is generated afresh
  /// each time, so it may name anything the compilation holds.
  private boolean sameUnit(Id.DecId target) {
    return CompilationUnit.of(emitPkg).map(unit -> unit.contains(target.pkg())).orElse(true);
  }

  private boolean isSealed(Id.DecId declared) {
    return ast.superDecIds(declared).contains(Magic.Sealed);
  }



  /// Whether the call is an ordinary one this pass may rewrite.
  ///
  /// A VPF parallelisable call is admitted because it stays a plain call. `VPFCodegen` reads
  /// through a `GuardedCall` to find the call it instruments, and the guard is what the
  /// self recursive calls inside such a method need.
  /// A call an earlier pass devirtualised keeps its target. Cloning the call it holds would
  /// let this pass answer for the same call again and put a test in front of a call that
  /// already names its one receiver type.
  @Override public MIR.E visitDirectCall(MIR.DirectCall call, boolean checkMagic) {
    return new MIR.DirectCall(cloneParts(call.original(), checkMagic), call.concreteType());
  }

  private MIR.MCall cloneParts(MIR.MCall call, boolean checkMagic) {
    return new MIR.MCall(
      call.recv().accept(this, checkMagic),
      call.name(),
      call.args().stream().map(e -> e.accept(this, checkMagic)).toList(),
      visitMT(call.t()),
      visitMT(call.originalRet()),
      call.mdf(),
      visitCallVariant(call.variant()));
  }

  private boolean isPlain(MIR.MCall call) {
    return call.variant().contains(MIR.MCall.CallVariant.Standard)
      || call.variant().contains(MIR.MCall.CallVariant.VPFParallelisable);
  }

  /// The one implementation of `declared`, collated from every package this program can see.
  ///
  /// A package is one of two kinds and both are readable. One whose generated code is cached
  /// wrote a sidecar beside it, which holds the count it took when it was lowered from source.
  /// One being compiled now is in the AST. Neither reading is a whole program analysis: each
  /// package counted only itself, and this joins those counts.
  ///
  /// The join is what makes a type effectively final. Where a package declares a type and one
  /// other package implements it, the count is one and the implementation is named, whichever
  /// package each sits in.
  private Optional<Id.DecId> soleImplOf(Id.DecId declared) {
    return soleImpl.computeIfAbsent(declared, d -> {
      var found = new LinkedHashSet<Id.DecId>();
      cachedImpls.get(d).ifPresent(known -> found.addAll(known.implIds()));
      for (var candidate : candidates) {
        // A package that wrote a sidecar counted itself there. Its AST holds only what its type
        // information kept, which is less, so reading it again would lower the count. A cacheable
        // package with no sidecar is being lowered from source now and its AST is complete.
        if (sidecarPkgs.contains(candidate.pkg())) { continue; }
        if (!candidate.equals(d) && !ast.superDecIds(candidate).contains(d)) { continue; }
        if (!hasOwnBody(candidate)) { continue; }
        found.add(candidate);
        if (found.size() > 1) { return Optional.empty(); }
      }
      return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    });
  }

  private static Optional<Id.DecId> soleOf(java.util.List<Id.DecId> impls) {
    return impls.size() == 1 ? Optional.of(impls.getFirst()) : Optional.empty();
  }

  /// Whether a declaration gives a body to every method it writes. A declaration with an
  /// abstract method is an interface, and no value carries its vtable.
  private boolean hasOwnBody(Id.DecId dec) {
    if (runtimeBacked.contains(dec)) { return false; }
    // A lambda that writes no method is the empty literal a Sealed type permits outside its
    // package. Such a literal takes the vtable of its parent, so it is not another implementation.
    var meths = ast.of(dec).lambda().meths();
    return !meths.isEmpty() && meths.stream().noneMatch(m -> m.isAbs());
  }

  /// The instance type of each literal kind. A string, number or float literal is made by the
  /// runtime and carries a vtable the runtime owns, so generated code never writes one. Such a
  /// type has a {@link MIR.TypeDef} like any other, which is why holding a definition is not on
  /// its own a promise that a vtable exists to name.
  private static final Set<Id.DecId> LITERAL_INSTANCES = java.util.Arrays
    .stream(LiteralKind.values())
    .map(LiteralKind::toDecId)
    .collect(java.util.stream.Collectors.toUnmodifiableSet());

  /// Whether codegen holds a wrapper of `target` for this call.
  private boolean callable(Id.DecId target, MIR.MCall call) {
    // A unit's generated code is written once and read by later compilations, none of which hold
    // the other packages of this one. Naming a type outside the unit would leave a reference to
    // a symbol that is absent everywhere but the compilation that wrote it. Inside the unit
    // there is no such gap: the two packages are generated together and cached together, and the
    // name of an anonymous literal is numbered by the unit that writes it.
    if (!sameUnit(target)) { return false; }
    if (Magic.allMagicDecs().contains(target)) { return false; }
    if (runtimeBacked.contains(target)) { return false; }
    if (LITERAL_INSTANCES.contains(target)) { return false; }
    if (!defs.containsKey(target)) { return false; }
    if (bodies.contains(key(target, call.name(), call.mdf()))) { return true; }
    // A type that writes no body of its own for the method still has a wrapper, which calls
    // the body it inherits.
    for (var parent : ast.superDecIds(target)) {
      if (bodies.contains(key(parent, call.name(), call.mdf()))) { return true; }
    }
    return false;
  }
}
