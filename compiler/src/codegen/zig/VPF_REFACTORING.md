# VPF Codegen Refactoring Proposals

After implementing n-ary VPF binary decomposition, `ZigSingleCodegen.java` grew to ~950 lines with the VPF section accounting for ~450 lines. These proposals aim to improve maintainability without changing behaviour.

## P1: Extract `VPFCodegen` class (HIGH)

The VPF code is self-contained and accounts for nearly half the file. Extract it into `VPFCodegen.java`.

**What moves:**
- Records: `VPFCallInfo`, `SubExprInfo`
- Detection: `findVPFCall`, `findVPFCallInner`, `buildVPFInfo`, `isFrameAddingExpr`
- Emission: `emitVPFFun`, `emitThiefFunction`, `emitSimpleThiefFunction`, `emitVPFThiefFunction`
- Combiners: `buildThiefCombinerMap`, `emitCombinerFromMap`, `emitCombiner`
- Helpers: `emitExprWithLocalsPrefix`
- Inner class: `ThiefCodegen`
- State: `vpfCounter`

**Interface with parent:** `VPFCodegen(ZigSingleCodegen parent)` — accesses `functions`, `captureStructs`, `id`, `sigBuilder`, `funMap`, `addHashConstant()`.

**Callsite in `visitFun`:**
```java
var vpfInfo = new VPFCodegen(this).findVPFCall(fun.body());
if (vpfInfo != null) {
    vpfCodegen.emitVPFFun(fun, name, paramNames, params, vpfInfo);
    return;
}
```

Brings `ZigSingleCodegen` down to ~500 lines.

## P2: Unify combiner methods (HIGH)

`emitCombiner` and `emitCombinerFromMap` do the same thing — build an `allArgs` array and emit `rt.call(...)`. The only difference is how `resultMap` is populated.

**Fix:** Delete `emitCombiner`. In `emitVPFFun`, build the result map inline:
```java
var resultMap = new HashMap<Integer, String>();
for (int i = 0; i < frameAddingExprs.size(); i++) {
    resultMap.put(frameAddingExprs.get(i).index, i == 0 ? "locals.r1" : "r" + (i + 1));
}
sb.append("return ").append(emitCombinerFromMap(vpf, resultMap, plainExprs)).append(";\n");
```

Removes ~30 lines and the `inThief` boolean parameter.

## P3: Extract shadow frame push helper (MEDIUM)

The shadow frame push block appears identically in `emitVPFFun` and `emitVPFThiefFunction`:

```java
private void emitPushFrame(StringBuilder sb, String hashName,
                            String localsVar, String localsTypeName,
                            String thiefFnName) {
    sb.append("const frame_idx = shadow_stack_mod.pushFrame(.{\n");
    sb.append("    .target_method = ").append(hashName).append(",\n");
    sb.append("    .join_obligation = std.atomic.Value(?*JoinObligation).init(null),\n");
    sb.append("    .child_obligation = std.atomic.Value(?*JoinObligation).init(null),\n");
    sb.append("    .locals = @ptrCast(&").append(localsVar).append("),\n");
    sb.append("    .locals_size = @sizeOf(").append(localsTypeName).append("),\n");
    sb.append("    .thief_fn = &").append(thiefFnName).append(",\n");
    sb.append("});\n");
}
```

Single source of truth if the runtime frame struct changes.

## P4: Extract forwarded obligation wait helper (MEDIUM)

The "wait for forwarded child obligations" loop is duplicated in `emitSimpleThiefFunction` and `emitVPFThiefFunction`:

```java
private void emitWaitForwardedObligations(StringBuilder sb,
                                           List<String> forwardedChildOblFields) {
    for (int i = forwardedChildOblFields.size() - 1; i >= 0; i--) {
        var field = forwardedChildOblFields.get(i);
        sb.append("const fwd_obl_").append(i)
          .append(": ?*JoinObligation = @ptrFromInt(locals.").append(field).append(");\n");
        sb.append("const fwd_r").append(i)
          .append(" = fwd_obl_").append(i)
          .append(".?.wait(worker_mod.getCurrentWorker().?);\n");
    }
}
```

The reverse iteration and naming convention are subtle — having them in one place reduces the chance of divergence.

## P5: Replace `emitExprWithLocalsPrefix` with `ThiefCodegen` (LOW)

`emitExprWithLocalsPrefix` only handles `MIR.X`, blindly prefixing with `locals.`. `ThiefCodegen` does this more correctly by checking `paramNames.contains(x.name())`. In `emitCombinerFromMap`, use a `ThiefCodegen` instance for plain sub-expressions instead, then delete `emitExprWithLocalsPrefix`.

## P6: Add `plainExprs` to `VPFCallInfo` (LOW)

`plainExprs` is recomputed from `vpf.subExprs` in 4 different places:
```java
var plainExprs = vpf.subExprs.stream().filter(s -> !s.isFrameAdding).toList();
```

Add it as a field on `VPFCallInfo` (computed once in `buildVPFInfo`).

## P7: Introduce `ThiefEmitContext` parameter object (LOW)

Several methods take 6-8 parameters. A context record would simplify signatures and recursive calls:
```java
private record ThiefEmitContext(
    String thiefName, String localsName, MIR.Fun fun,
    VPFCallInfo vpf, List<SubExprInfo> allFrameAddingExprs,
    Set<String> funParamNames, List<String> forwardedChildOblFields
) {}
```

## P8: Clean up dead code (LOW)

- `buildVPFInfo` always receives `null` for `boolExpr` — remove the parameter
- `emitCombiner`'s javadoc mentions inlining intrinsics but the method doesn't do this — stale doc
- `visitMCall` has an empty `if` block with "variant calls - not supported yet" — remove or add TODO
- Unused imports: `MethExprKind`, `Streams`, `Function`, `Stream`, static imports of `MethExprKind.Kind.*` and `Magic.getLiteral`

## Recommended order

1. P8 (dead code cleanup — trivial, no risk)
2. P2 (unify combiners — small, high value)
3. P3 + P4 (extract helpers — small, reduces duplication)
4. P5 + P6 (small correctness/hygiene improvements)
5. P1 (extract VPFCodegen — larger, best done after P2-P4 since the code will be cleaner)
6. P7 (parameter object — optional, do if P1 makes it worthwhile)

## Not recommended

**StringBuilder → template engine**: The generated code patterns are irregular enough that a builder abstraction would be either too rigid or too generic. The current approach is explicit about what it emits.
