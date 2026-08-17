package codegen.optimisations;

import codegen.MIR;
import codegen.MIRCloneVisitor;
import magic.MagicImpls;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class OptimisationBuilder {
  private final List<MIRCloneVisitor> passes = new ArrayList<>();
  private final MagicImpls<?> magic;
  public OptimisationBuilder(MagicImpls<?> magic) {
    this.magic = magic;
  }
  public MIR.Program run(MIR.Program p) {
    var p_ = p;
    for (var pass : this.passes) {
      p_ = pass.visitProgram(p_);
    }
    return p_;
  }
  public OptimisationBuilder withBoolIfOptimisation() {
    passes.add(new BoolIfOptimisation(magic));
    return this;
  }
  public OptimisationBuilder withDevirtualiseByRTA(Predicate<String> cachedPackage) {
    return withOptimisation(new DevirtualiseByRTA(magic, cachedPackage));
  }
  public OptimisationBuilder withBlockOptimisation() {
    passes.add(new BlockOptimisation(magic));
    return this;
  }
  public OptimisationBuilder withAsIdFnOptimisation() {
    return withOptimisation(new AsIdFnOptimisation(magic));
  }
  public OptimisationBuilder withBoxingOptimisation() {
    return withOptimisation(new BoxingOptimisation(magic));
  }
  public OptimisationBuilder withOptimisation(MIRCloneVisitor optimisation) {
    passes.add(optimisation);
    return this;
  }
}
