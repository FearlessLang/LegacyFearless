package codegen.zig;

import codegen.MIR;
import magic.Magic;
import codegen.optimisations.RcFreeTypes;
import codegen.optimisations.ScalarSumTypes;
import id.Id.DecId;

import java.util.*;
import java.util.function.Function;

/// Unboxed values and their conversions to and from boxes.
interface ZigCodegenValues extends ZigCodegenContext {
  /// A value passed unboxed: a primitive, a sum tag, or a niche-carried pointer.
  record Scalar(String zigType, String rtModule, ScalarSumTypes.Shape sum) {
    int bytes() { return zigType.equals("u8") ? 1 : zigType.equals("rt.FatPtr") ? 16 : 8; }
    boolean isSum() { return sum != null; }
    boolean isNiche() { return sum != null && sum.repr() == ScalarSumTypes.Repr.NICHE; }
  }

  /// Niche payloads can be any storage mode, including primitives, so they share dynamically.
  RcFreeTypes.Strategy NICHE_RC = RcFreeTypes.Strategy.DYNAMIC;
  Map<DecId, Scalar> SCALARS = Map.of(
    Magic.Nat, new Scalar("u64", "nat_rt", null),
    Magic.Int, new Scalar("i64", "int_rt", null),
    Magic.Float, new Scalar("f64", "float_rt", null),
    Magic.Byte, new Scalar("u8", "byte_rt", null));

  default Optional<Scalar> scalarOf(MIR.MT t) {
    var primitive = t.name().flatMap(d -> Optional.ofNullable(SCALARS.get(d)));
    if (primitive.isPresent()) { return primitive; }
    return scalarSums().shape(t).map(shape -> new Scalar(
      shape.repr() == ScalarSumTypes.Repr.NICHE ? "rt.FatPtr" : "u8", null, shape));
  }

  default Optional<Scalar> scalarSumOf(MIR.MT t) {
    return scalarOf(t).filter(Scalar::isSum);
  }

  default String toScalar(Scalar scalar, String boxed) {
    if (!scalar.isSum()) { return scalar.rtModule() + ".deref(" + boxed + ")"; }
    var value = freshName("fear_sum_boxed_");
    var block = freshName("fear_sum_encode_");
    if (scalar.isNiche()) {
      var carrier = scalar.sum().capturingVariant();
      return block + ": { const " + value + " = " + boxed + "; break :" + block + " if ("
        + guardTest(value, carrier.id()) + ") rt.deref(" + capturesRef(carrier.id()) + ", "
        + value + ")." + id().varName(carrier.captures().getFirst())
        + " else rt.sum_0captures(); }";
    }
    var out = new StringBuilder(block + ": { const " + value + " = " + boxed + "; break :" + block + " ");
    for (int i = 0; i < scalar.sum().variantIds().size(); i++) {
      if (i > 0) { out.append(" else "); }
      out.append("if (").append(value).append(".vt == &")
        .append(vtableRef(scalar.sum().variantIds().get(i))).append(") @as(u8, ").append(i).append(")");
    }
    // The shape lists every variant, so the fallback is unreachable.
    out.append(" else { std.debug.assert(false); unreachable; }; }");
    return out.toString();
  }

  /// Reads a boxed value as a scalar, taking ownership of a niche payload.
  default String toScalarOwned(Scalar scalar, String boxed) {
    if (!scalar.isNiche()) { return toScalar(scalar, boxed); }
    var value = freshName("fear_sum_owned_");
    var payload = freshName("fear_sum_payload_");
    var result = freshName("fear_sum_kept_");
    var block = freshName("fear_sum_take_");
    return block + ": {\nconst " + value + " = " + boxed + ";\n"
      + "const " + payload + " = " + toScalar(scalar, value) + ";\n"
      + "const " + result + " = " + generateShare(payload, NICHE_RC) + ";\n"
      + generateDecrement(value, NICHE_RC) + ";\n"
      + "break :" + block + " " + result + ";\n}";
  }

  default String ownedNiche(String code) {
    var value = freshName("fear_sum_lentv_");
    var block = freshName("fear_sum_own_");
    return block + ": {\nconst " + value + " = " + code + ";\nbreak :" + block + " "
      + generateShare(value, NICHE_RC) + ";\n}";
  }

  default String toBoxed(Scalar scalar, String value) {
    if (!scalar.isSum()) { return scalar.rtModule() + ".make(" + value + ")"; }
    if (scalar.isNiche()) {
      var carrier = scalar.sum().capturingVariant();
      var name = freshName("fear_sum_niche_");
      var payload = freshName("fear_sum_held_");
      var block = freshName("fear_sum_box_");
      var some = freshName("fear_sum_some_");
      return block + ": {\nconst " + name + " = " + value + ";\n"
        + "break :" + block + " if (rt.is_sum_0captures(" + name + ")) rt.obj_k_singleton(&"
        + vtableRef(scalar.sum().capturelessVariant().id()) + ") else " + some + ": {\n"
        + "const " + payload + " = " + generateShare(name, NICHE_RC) + ".box_transient();\n"
        + "defer " + generateDecrement(payload, NICHE_RC) + ";\n"
        + "break :" + some + " rt.obj_k(" + capturesRef(carrier.id()) + ", &"
        + vtableRef(carrier.id()) + ", .{ ." + id().varName(carrier.captures().getFirst())
        + " = " + payload + " });\n};\n}";
    }
    var out = new StringBuilder("switch (").append(value).append(") {");
    for (int i = 0; i < scalar.sum().variantIds().size(); i++) {
      out.append(i).append(" => rt.obj_k_singleton(&")
        .append(vtableRef(scalar.sum().variantIds().get(i))).append("), ");
    }
    out.append("else => { std.debug.assert(false); unreachable; }, }");
    return out.toString();
  }

  /// Boxes a scalar, releasing the owned value.
  default String toBoxedOwned(Scalar scalar, String value) {
    if (!scalar.isNiche()) { return toBoxed(scalar, value); }
    var name = freshName("fear_sum_moved_");
    var box = freshName("fear_sum_movedbox_");
    var block = freshName("fear_sum_move_");
    return block + ": {\nconst " + name + " = " + value + ";\n"
      + "const " + box + " = " + toBoxed(scalar, name) + ";\n"
      + generateDecrement(name, NICHE_RC) + ";\n"
      + "break :" + block + " " + box + ";\n}";
  }

  default String withBorrowedBox(Scalar scalar, String value, Function<String, String> use) {
    if (!scalar.isNiche()) { return use.apply(toBoxed(scalar, value)); }
    var tmp = freshName("fear_sum_lent_");
    var block = freshName("fear_sum_lend_");
    return block + ": {\nconst " + tmp + " = " + toBoxed(scalar, value) + ";\n"
      + "defer " + generateDecrement(tmp, NICHE_RC) + ";\n"
      + "break :" + block + " " + use.apply(tmp) + ";\n}";
  }

  /// Boxes a scalar for one use, then releases it.
  default String borrowedBox(Scalar scalar, String value, List<String> prelude) {
    if (!scalar.isNiche()) { return toBoxed(scalar, value); }
    var tmp = freshName("fear_sum_lent_");
    prelude.add("const " + tmp + " = " + toBoxed(scalar, value) + ";");
    prelude.add("defer " + generateDecrement(tmp, NICHE_RC) + ";");
    return tmp;
  }

  default String generateBoxBorrowed(String expression, MIR.MT type) {
    return generateBoxOwned(generateShare(expression, type));
  }

  default String generateBoxOwned(String expression) {
    var label = "fear_blk_" + nextBlock();
    var value = "fear_box_" + nextBlock();
    return label + ": {\nconst " + value + " = " + expression
      + ";\nbreak :" + label + " " + value + ".box_transient();\n}";
  }
}
