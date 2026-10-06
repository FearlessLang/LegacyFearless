package codegen.zig;

import codegen.MIR;
import codegen.optimisations.RcFreeTypes;
import java.util.OptionalInt;

/// Reference-count operations by [RcFreeTypes.Strategy]. NONE emits nothing.
interface ZigCodegenRc extends ZigCodegenContext {
  /// Counting strategy for a type. Cached packages assume downstream code can extend open types, so those count dynamically.
  default RcFreeTypes.Strategy rcStrategy(MIR.MT type) {
    if (emitTargetPkg() == null || main.CompilationUnit.isCached(emitTargetPkg())) {
      return rcFree().strategyForever(type);
    }
    return rcFree().strategy(type);
  }

  default RcFreeTypes.Strategy rcStrategy(MIR.E expression) {
    return rcStrategy(expression.t());
  }

  default boolean isRcFree(MIR.E expression) {
    return rcStrategy(expression) == RcFreeTypes.Strategy.NONE;
  }

  default OptionalInt standInSelfSlot(MIR.FName name) {
    return standInArms().selfSlot(name);
  }

  /// Parameters use their type strategy, except a conditional arm's receiver slot, which holds a singleton of another type.
  default RcFreeTypes.Strategy paramStrategy(MIR.FName name, int index, MIR.MT type) {
    var standIn = standInSelfSlot(name);
    return standIn.isPresent() && standIn.getAsInt() == index
      ? RcFreeTypes.Strategy.DYNAMIC
      : rcStrategy(type);
  }

  default String generateShare(String expression, MIR.MT type) {
    return generateShare(expression, rcStrategy(type));
  }

  default String generateShare(String expression, RcFreeTypes.Strategy strategy) {
    return switch (strategy) {
      case NONE -> expression;
      case HEAP -> expression + ".share_heap()";
      case HEAP_OR_TRANSIENT -> expression + ".share_heap_or_transient()";
      case DYNAMIC -> expression + ".share()";
    };
  }

  default String generateDecrement(String expression, MIR.MT type) {
    return generateDecrement(expression, rcStrategy(type));
  }

  /// Emits a decrement, or nothing when the type needs no counting.
  default String generateDecrement(String expression, RcFreeTypes.Strategy strategy) {
    var method = switch (strategy) {
      case NONE -> null;
      case HEAP -> "rc_decrement_heap";
      case HEAP_OR_TRANSIENT -> "rc_decrement_heap_or_transient";
      case DYNAMIC -> "rc_decrement";
    };
    return method == null ? "" : expression + "." + method + "()";
  }

  default String generateDecrementAs(String expression, MIR.MT type, String releasingWorkerId) {
    return generateDecrementAs(expression, rcStrategy(type), releasingWorkerId);
  }

  default String generateDecrementAs(
      String expression,
      RcFreeTypes.Strategy strategy,
      String releasingWorkerId
  ) {
    var method = switch (strategy) {
      case NONE -> null;
      case HEAP -> "rc_decrement_heap_as";
      case HEAP_OR_TRANSIENT -> "rc_decrement_heap_or_transient_as";
      case DYNAMIC -> "rc_decrement_as";
    };
    return method == null ? "" : expression + "." + method + "(" + releasingWorkerId + ")";
  }
}
