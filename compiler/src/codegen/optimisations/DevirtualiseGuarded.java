package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import id.Id;
import id.Mdf;
import magic.Magic;
import magic.MagicImpls;
import main.CompilationUnit;
import main.java.ImplInfo;
import program.TypeTable;
import program.typesystem.XBs;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Optimises method calls by adding a conditional branch to one-known VTable implementation before entering
/// the normal dispatch path. This optimisation is usable in more places than some of the more aggressive
/// devirtualisation optimisations we already have due to the guarded check.
/// A call on an object literal or a closed type with only one implementation is devirtualised without a guard.
public class DevirtualiseGuarded implements MIRCloneVisitor {
  private final MagicImpls<?> magic;
  private final ImplInfo cachedImpls;

  private final Set<String> alreadyCompiledPkgs;

  private ast.Program ast;
  private final Set<Id.DecId> candidates = new LinkedHashSet<>();
  private final Map<Id.DecId, List<Id.DecId>> impls = new HashMap<>();
  private final Map<Id.DecId, Boolean> instantiable = new HashMap<>();
  private static final int MAX_GUARD_ARMS = 2;
  private final Map<Id.DecId, MIR.TypeDef> defs = new HashMap<>();
  private final Set<String> bodies = new LinkedHashSet<>();
  private String emitPkg = "";

  private final Set<Id.DecId> targets = new LinkedHashSet<>();
  private int guardedCalls = 0;
  private int directCalls = 0;

  public DevirtualiseGuarded(MagicImpls<?> magic) {
    this(magic, ImplInfo.EMPTY, Set.of());
  }

  public DevirtualiseGuarded(MagicImpls<?> magic, ImplInfo cachedImpls, Set<String> alreadyCompiledPkgs) {
    this.magic = magic;
    this.cachedImpls = cachedImpls;
    this.alreadyCompiledPkgs = Set.copyOf(alreadyCompiledPkgs);
  }

  public Set<Id.DecId> targets() { return targets; }
  public int guardedCalls() { return guardedCalls; }
  public int directCalls() { return directCalls; }

  @Override public MIR.Program visitProgram(MIR.Program p) {
    this.ast = p.p();
    this.impls.clear();
    this.instantiable.clear();
    this.defs.clear();
    this.bodies.clear();
    this.candidates.clear();
    this.candidates.addAll(ast.ds().keySet());
    this.candidates.addAll(ast.inlineDs().keySet());
    for (var pkg : p.pkgs()) {
      for (var def : pkg.defs().values()) { defs.put(def.name(), def); }
      for (var fun : pkg.funs()) {
        bodies.add(key(fun.name().d(), fun.name().m(), fun.name().mdf()));
      }
    }
    return MIRCloneVisitor.super.visitProgram(p);
  }

  @Override public MIR.Package visitPackage(MIR.Package pkg) {
    this.emitPkg = pkg.name();
    return MIRCloneVisitor.super.visitPackage(pkg);
  }

  private boolean isRuntimeImplemented(Id.DecId dec) {
    return MagicImpls.MAGIC_DECS.contains(dec)
      || (isDeclared(dec) && ast.superDecIds(dec).contains(Magic.RuntimeImplemented));
  }

  private boolean isDeclared(Id.DecId dec) {
    return ast.ds().containsKey(dec) || ast.inlineDs().containsKey(dec);
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
    var found = implsOf(declared.get());
    if (found.isEmpty() || found.size() > MAX_GUARD_ARMS) { return visited; }
    if (found.size() > 1 && !isFinal(declared.get())) { return visited; }
    var armed = found.stream().filter(impl -> callable(impl, rewritten)).toList();
    if (armed.isEmpty()) { return visited; }
    var target = armed.getFirst();
    targets.add(target);
    if (found.size() == 1 && canDirectCall(declared.get())) {
      directCalls++;
      return new MIR.DirectCall(rewritten, target);
    }
    guardedCalls++;
    var alt = armed.size() > 1 ? Optional.of(armed.get(1)) : Optional.<Id.DecId>empty();
    alt.ifPresent(targets::add);
    return new MIR.GuardedCall(rewritten, target, alt);
  }

  private boolean canDirectCall(Id.DecId declared) {
    if (isRuntimeImplemented(declared)) { return false; }
    return isFinal(declared) || isEffectivelyFinal();
  }

  private boolean isFinal(Id.DecId declared) {
    if (isRuntimeImplemented(declared)) { return false; }
    var known = cachedImpls.get(declared);
    if (known.map(ImplInfo.Entry::inlineDec).orElseGet(() -> ast.isInlineDec(declared))) {
      return true;
    }
    return known.map(ImplInfo.Entry::sealed).orElseGet(() -> isSealed(declared));
  }

  private boolean isEffectivelyFinal() { return !CompilationUnit.isCached(emitPkg); }

  private boolean sameUnit(Id.DecId target) {
    return CompilationUnit.of(emitPkg).map(unit -> unit.contains(target.pkg())).orElse(true);
  }

  private boolean isSealed(Id.DecId declared) {
    return ast.superDecIds(declared).contains(Magic.Sealed);
  }


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

  /// The declarations that can be the dynamic type of a value of type `declared`. The search stops
  /// when the set is larger than `MAX_GUARD_ARMS`. A call may skip the run-time test of its
  /// receiver only when this set holds one declaration. If a declaration is missing from this
  /// set, a call can go to the wrong method.
  private List<Id.DecId> implsOf(Id.DecId declared) {
    return impls.computeIfAbsent(declared, d -> {
      var found = new LinkedHashSet<Id.DecId>();
      cachedImpls.get(d).ifPresent(known -> found.addAll(known.implIds()));
      for (var candidate : candidates) {
        if (alreadyCompiledPkgs.contains(candidate.pkg())) { continue; }
        if (!candidate.equals(d) && !ast.superDecIds(candidate).contains(d)) { continue; }
        if (!isInstantiable(candidate)) { continue; }
        found.add(candidate);
        if (found.size() > MAX_GUARD_ARMS) { break; }
      }
      return List.copyOf(found);
    });
  }

  private Optional<Id.DecId> soleImplOf(Id.DecId declared) {
    var found = implsOf(declared);
    return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
  }

  /// Whether generated code can make a value with `dec` as its dynamic type.
  ///
  /// An object literal must implement each abstract method that a reference with the capability of
  /// the literal can call. An `imm` literal is the most permissive: no `imm` reference can call a
  /// `mut` method, so the literal can leave it abstract. Every other literal capability needs
  /// at least the methods that an `imm` literal needs. So `dec` has no value when it has an
  /// abstract method that an `imm` literal must implement. Methods that `dec` inherits count.
  ///
  /// A literal that writes no method and names one type makes a value of that type, so it has no
  /// value of its own. The runtime makes the values of a runtime-implemented declaration.
  private boolean isInstantiable(Id.DecId dec) {
    if (isRuntimeImplemented(dec) || isEmptyLiteral(dec)) { return false; }
    return instantiable.computeIfAbsent(dec, d -> {
      var decl = ast.of(d);
      var xbs = XBs.empty().addBounds(decl.gxs(), decl.bounds());
      return ast.meths(xbs, Mdf.recMdf, decl.lambda(), 0).stream()
        .noneMatch(cm -> cm.isAbs() && TypeTable.filterByMdf(Mdf.imm, cm.mdf()));
    });
  }

  private boolean isEmptyLiteral(Id.DecId dec) {
    if (!dec.isFresh()) { return false; }
    var lambda = ast.of(dec).lambda();
    return lambda.meths().isEmpty()
      && lambda.its().stream().filter(it -> !it.name().equals(dec)).count() == 1;
  }

  private boolean callable(Id.DecId target, MIR.MCall call) {
    if (!sameUnit(target)) { return false; }
    if (isRuntimeImplemented(target)) { return false; }
    if (!defs.containsKey(target)) { return false; }
    if (bodies.contains(key(target, call.name(), call.mdf()))) { return true; }
    for (var parent : ast.superDecIds(target)) {
      if (bodies.contains(key(parent, call.name(), call.mdf()))) { return true; }
    }
    return false;
  }
}
