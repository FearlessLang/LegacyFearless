# Fearless Flows: A Comprehensive Guide

Flows are Fearless's primary abstraction for processing sequences of data. They are a **push-based reactive stream pipeline** with automatic parallelism — the same user-facing code can execute sequentially, with pipeline parallelism, or with data parallelism, depending on compile-time type analysis and runtime heuristics.

This document covers the full stack: the Fearless stdlib traits, the compiler codegen magic, and the Java runtime implementations.

---

## Table of Contents

1. [Core Abstractions](#1-core-abstractions)
2. [Flow Construction](#2-flow-construction)
3. [Non-Terminal Operators](#3-non-terminal-operators)
4. [Terminal Operators](#4-terminal-operators)
5. [Stop Logic](#5-stop-logic)
6. [Error Handling](#6-error-handling)
7. [Flow Reuse & Duplication](#7-flow-reuse--duplication)
8. [The Three Execution Modes](#8-the-three-execution-modes)
9. [The Codegen Magic Layer](#9-the-codegen-magic-layer)
10. [Runtime Degradation](#10-runtime-degradation)
11. [The Full Journey: From Fearless to Execution](#11-the-full-journey-from-fearless-to-execution)
12. [Test Examples](#12-test-examples)

---

## 1. Core Abstractions

The flow system is built around three core traits and two supporting abstractions:

### `FlowOp[E]` — The Data Source

`FlowOp` is the internal data source abstraction, analogous to Java's `Spliterator`. It produces elements by pushing them into a `_Sink`.

```fearless
FlowOp[E:*]: {
  mut .step(sink: mut _Sink[E]): Void,
  mut .stopUp: Void,
  mut .isRunning: Bool,
  mut .for(downstream: mut _Sink[E]): Void,
  mut .isFinite: Bool -> True,
  mut .split: mut Opt[mut FlowOp[E]] -> {},
  read .canSplit: Bool -> False,
}
```

| Method | Purpose |
|--------|---------|
| `.step(sink)` | Push one element to the sink |
| `.stopUp` | Accept early termination from downstream |
| `.isRunning` | Whether more elements are available |
| `.for(downstream)` | Bulk-push all elements (optimizable tight loop) |
| `.isFinite` | Whether the source has a finite number of elements |
| `.split` | Split the source for data-parallel fork-join |
| `.canSplit` | Whether splitting is possible |

The default `.for` implementation loops calling `.step` until `isRunning` is false, then calls `downstream.stopDown`:

```fearless
mut .for(downstream: mut _Sink[E]): Void -> Block#
  .loop {Block#
    .if {this.isRunning.not} .return {Block#(downstream.stopDown, ControlFlow.break)}
    .do {this.step(downstream)}
    .return {ControlFlow.continue}
  }
  .return {Void},
```

Sources can override `.for` with optimized implementations (e.g., `FiniteRangeOp` uses a tight `for` loop in Java).

### `_Sink[T]` — The Data Consumer

`_Sink` receives elements pushed from upstream. It has three methods representing the three things that can happen to a consumer:

```fearless
_Sink[T:*]: {
  mut #(x: T): Void,
  mut .pushError(info: Info): Void,
  mut .stopDown: Void,
}
```

| Method | Purpose |
|--------|---------|
| `#(x)` | Receive an element |
| `.pushError(info)` | Receive an error from upstream |
| `.stopDown` | Receive notification that the source is exhausted |

### `Flow[E]` — The Public API

`Flow[E]` is what users interact with. It wraps a `FlowOp` and provides chainable non-terminal and terminal operators:

```fearless
Flow[E:*]: Sealed, Extensible[Flow[E]], _NonTerminalOps[E], _TerminalOps[E]{
  .self -> this,
  mut .let[DR,R](x, cont): R -> ...,
  mut .join(j: Joinable[E]): E -> j.join(this),
  mut .unwrapOp(unwrap: mut _UnwrapFlowToken): mut FlowOp[E],
  mut .only: mut Action[E] -> ...,
  mut .get: E -> this.only!,
  mut .opt: mut Opt[E] -> ...,
}
```

The `_UnwrapFlowToken` is a private trait that prevents external code from extracting the inner `FlowOp` — only the flow system itself can unwrap flows.

### `_FlowFactory` — The Factory Interface

All three execution modes implement this shared interface:

```fearless
_FlowFactory: {
  .fromOp[E:*](source: mut FlowOp[E], size: Opt[Nat]): mut Flow[E],
}
```

The three implementations are:
- **`_SeqFlow`** — sequential execution
- **`_PipelineParallelFlow`** — pipeline parallel execution
- **`_DataParallelFlow`** — data parallel execution (entirely `Magic!`)

### `_SinkDecorator` — The Parallelism Strategy

This is the key abstraction that makes the same operator code work across all execution modes:

```fearless
_SinkDecorator: {#[E:*](sink: mut _Sink[E]): mut _Sink[E]}
```

A `_SinkDecorator` wraps sinks. The execution mode determines the wrapper:
- **Sequential:** `{s -> s}` (identity — no wrapping)
- **Pipeline parallel:** `_PipelineParallelSink` (Magic! — wraps sink in a virtual-thread-backed message queue)
- **Data parallel:** `{s -> s}` (identity — parallelism is at the source level, not between operators)

Every operator takes a `_SinkDecorator` parameter. This is how the same `_Map`, `_Filter`, etc. code runs in all three modes.

---

## 2. Flow Construction

### From Literal Values

```fearless
Flow#[Nat](1, 2, 3)           // 2+ elements: delegates to List#(...).flow
Flow#[Nat](42)                 // single element: special single-element FlowOp
Flow#[Nat]                     // empty: EmptyFlow
```

Single-element flows use a `Var[Opt[E]]` that is atomically swapped to empty on first step:

```fearless
_SeqFlow#[E](e: E): mut Flow[E] -> Block#
  .let seq = { Vars#(Opts#e) }
  .let source = {{'self
    .isRunning -> seq.get.isSome,
    .stopUp -> seq := {},
    .step(downstream) -> seq.swap(mut Opt[E]).match{
      .some(x) -> Block#(downstream#x, self.stopUp, downstream.stopDown),
      .empty -> {},
    },
  }}
  .return {this.fromOp(source, Opts#1)},
```

### From Iso Values

For mutable elements, `Flow.ofIso` accepts `iso` values to safely transfer ownership:

```fearless
Flow.ofIso[Person](iso alice, iso bob)  // transfers ownership into the flow
```

### From Lists

```fearless
myList.flow  // calls _SafeSource.fromList internally
```

`_SafeSource.fromList` creates a cursor-based `FlowOp` that iterates over the list's backing `UList`. It supports `.split` by dividing the cursor range at the midpoint — enabling data parallelism:

```fearless
_SafeSource.fromList'[E](list: mut UList[E], start: Nat, end: Nat): mut FlowOp[E] -> Block#
  .let cursor = {Count.nat(start)}
  .let endCursor = {Count.nat(end)}
  .return {{'self
    .isRunning -> cursor.get < (endCursor.get),
    .stopUp -> cursor := (endCursor.get),
    .step(downstream) -> Block#
      .if {self.isRunning.not} .return{downstream.stopDown}
      .do {downstream#(list.get(cursor.get))}
      .do {cursor++}
      .if {self.isRunning.not} .return{downstream.stopDown}
      .return {},
    .split -> self.canSplit ? {
      .else -> {},
      .then -> Block#
        .let mid = {cursor.get + ((endCursor.get - (cursor.get)) / 2)}
        .let end' = {endCursor.swap(mid)}
        .return {Opts#(this.fromList'(list, mid, end'))},
    },
    .canSplit -> endCursor.get - (cursor.get) > 1,
  }},
```

### From Ranges

```fearless
Flow.range(+1, +100)    // finite range [1, 100)
Flow.range(+0)           // infinite range [0, ...)
```

Ranges are `Magic!` in Fearless, implemented in Java by `rt.flows.Range`. Finite ranges support splitting; infinite ranges do not.

### From Custom FlowOps

```fearless
Flow.fromOp(myFlowOp)          // always sequential
Flow.fromOp(myFlowOp, 100)     // sequential, with known size
Flow.fromMutSource(myFlowOp)   // collects to list first to prevent concurrent modification
```

`Flow.fromOp` always creates sequential flows. Only flows from specific stdlib types (List, UList, Range) can be automatically parallelized.

---

## 3. Non-Terminal Operators

All non-terminal operators are defined in `_NonTerminalOps[E]` and return a new `mut Flow[R]`:

| Operator | Signature | Description |
|----------|-----------|-------------|
| `.filter` | `(predicate: read F[E, Bool])` | Keep elements matching predicate |
| `.map` | `(f: read F[E, R])` | Transform each element |
| `.map` (ctx) | `(ctx: iso ToIso[C], f: read F[iso C, E, R])` | Map with splittable context |
| `.peek` | `(f: read F[E, Void])` | Side-effect without transforming (sugar for map) |
| `.mapFilter` | `(f: read F[E, mut Opt[R]])` | Map + filter in one step (sugar) |
| `.flatMap` | `(f: read F[E, mut Flow[R]])` | One-to-many mapping |
| `.actor` | `(state: iso S, f: read ActorImpl[S,E,R])` | Stateful transformation (imm output) |
| `.actorMut` | `(state: iso S, f: read ActorImplMut[S,E,R])` | Stateful transformation (any output) |
| `.limit` | `(n: Nat)` | Take first n elements |
| `.scan` | `(acc: imm S, f: read F[S,E,S])` | Running accumulation (sugar for actor) |
| `.assumeFinite` | none | Mark infinite flow as finite |

### How Operators Work Internally

Every operator follows the same pattern. Here is `_Map` as the canonical example:

```fearless
_Map: {
  #[E,R](sinkDecorator: _SinkDecorator, upstream: mut FlowOp[E], f: read F[E, R]): mut FlowOp[R] -> Block#
    .let sink = {Slots#[mut _Sink[E]]}
    .return{{
      .step(downstream) ->
        upstream.step(sink.getOrFill{this.impl(downstream, f, sinkDecorator)}),
      .for(downstream) ->
        upstream.for(sink.getOrFill{this.impl(downstream, f, sinkDecorator)}),
      .stopUp -> upstream.stopUp,
      .isRunning -> upstream.isRunning,
      .split -> upstream.split.map{right -> this#[E,R](sinkDecorator, right, f)},
      .canSplit -> upstream.canSplit,
      .isFinite -> upstream.isFinite,
    }},
  .impl[E,R](downstream: mut _Sink[R], f: read F[E, R], sinkDecorator: _SinkDecorator): mut _Sink[E] ->
    sinkDecorator#{
      #(e) -> downstream#(f#e),
      .stopDown -> downstream.stopDown,
      .pushError(info) -> downstream.pushError(info),
    },
}
```

Key patterns:

1. **Sink caching via `Slots`**: The intermediate sink is created lazily and cached. The comment in `operators.fear` emphasizes: *"REUSE SINK OBJECTS! THE IDENTITY IS IMPORTANT FOR WHEN THEY ARE RAN IN PARALLEL!!!!!"*

2. **SinkDecorator application**: The `sinkDecorator#` call wraps the functional sink. For sequential this is identity; for pipeline parallel it wraps the sink in a virtual-thread-backed queue.

3. **Split propagation**: `.split` delegates to the upstream source and wraps the result in a new operator instance with the same function. This is how data parallelism works — each chunk gets its own copy of the operator chain.

4. **Stop passthrough**: `.stopUp` delegates directly to `upstream.stopUp`. The sink's `.stopDown` delegates to `downstream.stopDown`.

### `_Filter`

Same structure as `_Map`, but the sink conditionally forwards elements:

```fearless
#(e) -> predicate#e ? {.then -> downstream#e, .else -> {}},
```

### `_FlatMap`

More complex — it violates the one-element-per-step contract because it flattens inner flows. The `.flatten` method iterates the inner flow's `FlowOp` using `.for`:

```fearless
.flatten[R](toFlatten: mut FlowOp[R], downstream, op, sinkDecorator): Void ->
  toFlatten.for(sinkDecorator#[R]{
    #(e) -> op.isRunning ? {.then -> downstream#e, .else -> toFlatten.stopUp},
    .stopDown -> toFlatten.stopUp,
    .pushError(info) -> downstream.pushError(info),
  }),
```

If the outer flow stops while iterating an inner flow (e.g., due to a downstream `.limit`), `op.isRunning` returns false and the inner flow is stopped via `toFlatten.stopUp`.

### `_Limit`

Maintains a `Count[Nat]` remaining counter. Decrements on each element and calls `stopUp` when exhausted. Forces `.isFinite -> True`:

```fearless
_Limit: {
  #[E](sinkDecorator, upstream, n): mut FlowOp[E] -> Block#
    .let remaining = {Count.nat(n)}
    .return {{ 'runner
      .stopUp -> Block#(remaining := 0, upstream.stopUp),
      .isRunning -> remaining* > 0 & (upstream.isRunning),
      .isFinite -> True,
      .step(downstream) -> ...
        sinkDecorator#[E]{
          #(e) -> Block#
            .if {remaining.get <= 0} .return {runner.stopUp}
            .do {downstream#e}
            .if {remaining.update{r -> r - 1} <= 1} .return {runner.stopUp}
            .return {},
          .pushError(info) -> remaining.get == 0 ? {
            .then -> {},
            .else -> downstream.pushError(info),
          },
        }
    }}
}
```

### `_Actor`

The most powerful operator. Takes `iso` state and a callback that receives a downstream sink, the state, and each element. The callback returns `ActorRes.continue` or `ActorRes.stop`:

```fearless
ActorImpl[S:*,E:*,R:*]: { read #(downstream: mut _ActorSink[imm R], state: S, e: E): ActorRes }
ActorRes: Sealed{
  .continue: ActorRes -> ...,
  .stop: ActorRes -> ...,
}
```

The actor can emit zero or more elements per input element, and can self-terminate:

```fearless
#(e) -> isRunning.get ? {
  .then -> f#(actorSink, state, e).match{
    .continue -> {},
    .stop -> Block#(downstream.stopDown, isRunning := False),
  },
  .else -> op.stopUp,
},
```

### Context-Aware Map

The two-argument `.map(ctx, f)` takes an `iso ToIso[C]` context that can be split for data parallelism:

```fearless
mut .map[C,R](ctx: iso ToIso[C], f: read F[iso C, E, R]): mut Flow[R]
```

When the operator's `.split` is called, the context's `.iso` method creates a fresh copy for the new chunk:

```fearless
.split -> upstream.split.map{right -> this#[C,E,R](sinkDecorator, right, ctx'.iso, f)},
```

---

## 4. Terminal Operators

Terminal operators consume the flow and produce a result. All are defined in `_TerminalOps[E]`:

| Terminal | Signature | Description |
|----------|-----------|-------------|
| `.first` | `: mut Opt[E]` | First element |
| `.last` | `: mut Opt[E]` | Last element (via fold) |
| `.find` | `(predicate): mut Opt[E]` | First matching element |
| `.findMap` | `(f): mut Opt[R]` | Find and transform first match |
| `.fold` | `(acc: iso MF[S], f: read F[S,E,S]): S` | Reduce to single value |
| `.count` | `: Nat` | Count elements |
| `.size` | `: Opt[Nat]` | Known size (non-consuming) |
| `.any` | `(predicate): Bool` | Short-circuit: any match? |
| `.all` | `(predicate): Bool` | Short-circuit: all match? |
| `.none` | `(predicate): Bool` | Short-circuit: no match? |
| `.for` | `(f: read F[E, Void]): Void` | For-each side-effect |
| `.list` | `: mut List[E]` | Collect to list |
| `.max` | `(compare): mut Opt[E]` | Maximum element |
| `.join` | `(j: Joinable[E]): E` | Join elements (e.g., string join) |

All terminals check `.isFinite` and reject infinite flows:

```fearless
.if {upstream.isFinite.not} .error {TerminalOnInfiniteError#}
```

### Terminal Pattern: `_Fold`

```fearless
_Fold[S,E]: F[mut FlowOp[E], S, read F[S,E,S], _SinkDecorator, S]{
  upstream, acc, f, sinkDecorator -> Block#
    .if {upstream.isFinite.not} .error {TerminalOnInfiniteError#}
    .var[S] res = {acc}
    .var[Bool] stopped = {False}
    .do {upstream.for(sinkDecorator#[E]{'runner
      .stopDown -> Block#(stopped := True, upstream.stopUp),
      .pushError(info) -> stopped.get ? {.then -> {}, .else -> Error!info},
      #(e) -> res := (f#(res.get, e)),
    })}
    .do {upstream.stopUp}
    .return {res.get}
}
```

### Terminal Pattern: `_First` (Early Exit)

```fearless
_First[E]: F[mut FlowOp[E], _SinkDecorator, mut Opt[E]]{
  upstream, sinkDecorator -> Block#
    .if {upstream.isFinite.not} .error {TerminalOnInfiniteError#}
    .let res = {Slots#[E]}
    .var[Bool] stopped = {False}
    .do {upstream.for(sinkDecorator#{'runner
      .stopDown -> Block#(stopped := True, upstream.stopUp),
      .pushError(info) -> stopped.get ? {.then -> {}, .else -> Error!info},
      #(e) -> Block#
        .do {res.ensureFull{e}}
        .return {runner.stopDown},
    })}
    .do {upstream.stopUp}
    .return {res.opt}
}
```

On receiving the first element, `_First` stores it, then immediately calls `runner.stopDown` on itself — triggering early termination of the entire pipeline.

### `EmptyFlow`

`EmptyFlow[E]` short-circuits all operations. Terminals return empty/zero results immediately:

```fearless
EmptyFlow[E:*]: Flow[E]{
  .filter(p) -> this,
  mut .map[R](f: read F[E, R]): mut Flow[R] -> mut EmptyFlow[R],
  .first -> {},
  .fold(acc, _) -> acc#,
  .list -> List#,
  .count -> 0,
  // ...
}
```

---

## 5. Stop Logic

The flow system has **two stop signals** that travel in opposite directions:

```
Source (FlowOp) ──elements──▶ Operator Sinks ──elements──▶ Terminal Sink
       ◀──stopUp──                              ◀──stopUp──
       ──stopDown──▶                            ──stopDown──▶
```

- **`stopUp`** — travels **upstream** (consumer → source). "I don't need more elements."
- **`stopDown`** — travels **downstream** (source → consumer). "I have no more elements."

### Natural Exhaustion

When a source runs out of elements, the default `.for` loop calls `downstream.stopDown`:

```fearless
.if {this.isRunning.not} .return {Block#(downstream.stopDown, ControlFlow.break)}
```

`stopDown` propagates downstream through each operator's sink (which delegates to its downstream sink), eventually reaching the terminal.

### Early Termination

Early termination is initiated by the consumer side. For example, `_First`:

1. First element arrives at the terminal sink via `#(e)`
2. Element is stored in a `Slots`
3. `runner.stopDown` is called (the sink calls its own `.stopDown`)
4. `.stopDown` sets `stopped := True` and calls `upstream.stopUp`
5. `upstream.stopUp` propagates up through each operator to the source
6. Source's `isRunning` becomes false; the `.for` loop breaks
7. After `.for` returns, `upstream.stopUp` is called again as a safety net

### Stop Propagation Through Operators

**Passthrough operators** (Map, Filter) are transparent to stop signals:

```fearless
// FlowOp level:
.stopUp -> upstream.stopUp,
// Sink level:
.stopDown -> downstream.stopDown,
```

**`_Limit`** is the primary stop initiator among operators. When its counter reaches zero, it calls `runner.stopUp`, which sets `remaining := 0` and calls `upstream.stopUp`.

**`_Actor`** can self-terminate via `ActorRes.stop`:

```fearless
.stop -> Block#(downstream.stopDown, isRunning := False),
```

The actor's `.stopUp` guards against double-stopping:

```fearless
.stopUp -> op.isRunning ? {
  .then -> Block#(isRunning := False, upstream.stopUp),
  .else -> {}
},
```

**`_FlatMap`** manages stop for both outer and inner flows. If the outer flow stops while iterating an inner flow, the inner flow is killed:

```fearless
#(e) -> op.isRunning ? {
  .then -> downstream#e,
  .else -> toFlatten.stopUp  // outer stopped → kill inner
},
```

### Stop in Parallel Contexts: Eventual Consistency

In sequential execution, `stopUp` takes effect immediately — the source is on the same thread, so after `stopUp` returns, no more elements will be produced. In parallel execution, **stop is eventual, not immediate**. Elements may continue to arrive after a stop signal is sent, and the system is designed to handle this gracefully.

#### Pipeline Parallel

In pipeline parallel, each stage runs on its own virtual thread connected by a bounded `ArrayBlockingQueue`. When `stopUp` is triggered:

1. The stop signal is submitted as a `Message.Stop` to the upstream stage's queue
2. But the upstream stage may have already submitted elements that are sitting in the queue
3. The upstream stage may even be blocked trying to submit more elements (queue full)
4. After `Stop` is submitted, `join()` blocks until the upstream thread processes it

The `WrappedSink` handles this with a `softClose()` mechanism — once soft-closed, the sink silently discards any further elements or errors. The `Subject`'s run loop continues draining the queue but `processDataMsg` short-circuits after `softClosed` is set. This means in-flight elements between stages are consumed from the queue but not forwarded.

The `stopped` boolean in terminal sinks provides a second layer of defense: even if an element or error slips through a race, the terminal ignores it.

#### Data Parallel

In data parallel, multiple worker threads process separate chunks simultaneously. Stop uses an `AtomicBoolean isDPRunning` shared across all workers:

```java
isDPRunning.set(false);         // Signal all workers
source_m$.stopUp$mut();         // Also propagate to original source
```

Workers check this flag with **relaxed reads** (`getPlain()`) for performance — there is no strong happens-before guarantee. A worker may process several extra elements before observing the flag change. This is a deliberate trade-off: exact cancellation precision is unnecessary because:

- Each worker's output goes into its own `BufferSink`, not directly to the real downstream
- The `FlushWorker` drains buffers in chunk order — if the flow has already stopped, the FlushWorker receives a `StopToken` and stops draining, so extra elements in later buffers are simply abandoned
- `BufferSink.stopDown()` is a **no-op** — it swallows stop signals from individual chunks to prevent one chunk from prematurely stopping the shared downstream

Workers that haven't started yet check `isRunning` at the top of `run()` and bail immediately. Workers mid-processing will continue until their source's `.for` loop checks `isRunning` at the next iteration boundary.

#### Why This Matters

Because stop is eventual in parallel contexts, every consumer of elements must be prepared to receive data after it has logically stopped. This is why:

- Terminal sinks gate error propagation with `stopped.get ? {.then -> {}, .else -> Error!info}`
- `_Limit` checks `remaining.get <= 0` at the top of its element handler before processing
- `_Actor` checks `isRunning.get` before invoking the callback
- `_FlatMap`'s flatten method checks `op.isRunning` before forwarding each inner element
- `InfiniteRangeOp` uses `volatile boolean isRunning` for cross-thread visibility

The principle is: **every stage must tolerate receiving elements after stop, and must silently discard them**. The `_SinkDecorator` mechanism ensures this is handled uniformly — the same operator code works correctly whether stop is synchronous (sequential) or eventual (parallel).

### Stop Signal Summary

| Component | `stopUp` | `stopDown` |
|-----------|----------|------------|
| Source (list) | Sets cursor to end | Called when source exhausts |
| Source (single) | Clears element storage | Called after delivering the one element |
| `_Map` / `_Filter` | Delegates to `upstream.stopUp` | Delegates to `downstream.stopDown` |
| `_FlatMap` | Delegates to `upstream.stopUp` | Sets `isRunning := False`, then `downstream.stopDown` |
| `_Limit` | Sets remaining to 0, then `upstream.stopUp` | Delegates to `downstream.stopDown` |
| `_Actor` | Guarded: if running, `isRunning := False` + `upstream.stopUp` | On `ActorRes.stop`: `downstream.stopDown` |
| Terminal (`_First`) | Safety call after `.for` returns | On first element: stores result, self-calls `.stopDown` |
| Terminal (`_Fold`) | Safety call after `.for` returns | Never self-initiates |

---

## 6. Error Handling

### `pushError`

Errors propagate downstream via `_Sink.pushError(info)`. Each operator forwards errors to its downstream sink:

```fearless
.pushError(info) -> downstream.pushError(info),
```

### Error Swallowing After Stop

All terminal sinks follow this pattern:

```fearless
.pushError(info) -> stopped.get ? {
  .then -> {},          // SWALLOWED: error arrived after stop
  .else -> Error!info,  // PROPAGATED: error arrived before stop
},
```

Once a flow is stopped, errors from in-flight elements are silently discarded. This is critical for parallel execution where elements may still be in transit when the flow stops. Only errors that occur *before* the stop signal are treated as fatal.

`_Limit` similarly swallows errors once its counter reaches 0:

```fearless
.pushError(info) -> remaining.get == 0 ? {
  .then -> {},
  .else -> downstream.pushError(info),
},
```

### Verified by Tests

```fearless
// Error BEFORE stop → crashes
Flow#[Nat](1, 2, 3)
  .map{x -> Block#
    .if {x.nat == 2} .do {Error.msg (x.str)}
    .return {x.nat * 10}
  }
  #(Flow.uSum)
// Result: crash with "2"

// Error AFTER stop → swallowed
Flow#[Nat](1, 2, 3)
  .map{x -> Block#
    .if {x.nat == 2} .do {Error.msg (x.str)}
    .return {x.nat * 10}
  }
  .limit(1)
  #(Flow.uSum)
// Result: 10 (error on element 2 is swallowed because limit already stopped)
```

This behavior is consistent across sequential, pipeline parallel, and data parallel execution — all three modes are tested.

### Deterministic vs Nondeterministic Errors

Fearless distinguishes between two categories of exceptions, and flows handle them very differently:

**Deterministic errors** (`FearlessError`) are raised by Fearless-level `Error.msg` / `Error!info`. These are "expected" errors — user code explicitly threw them. In flows, they are caught, converted to `pushError` calls, and propagated downstream through the sink chain. The terminal sink decides whether to re-throw them (before stop) or swallow them (after stop). This keeps them within the flow's error handling machinery and ensures the first error in element order is the one that surfaces.

**Nondeterministic errors** (`StackOverflowError`, `OutOfMemoryError`, other JVM-level exceptions) are *not* catchable by Fearless `Try`. These bypass the `pushError` mechanism entirely. In parallel flows, they are caught by the `UncaughtExceptionHandler` on the worker thread and stored in an `AtomicReference<RuntimeException>`. After all workers finish, the stored exception is rethrown — it bubbles up as a `RuntimeException`, which crashes the program rather than being handleable by Fearless error handling.

#### How Each Execution Mode Handles Errors

**Sequential:** Deterministic errors propagate directly through the call stack. The terminal's `pushError` handler decides: re-throw if before stop, swallow if after stop. Nondeterministic errors (e.g., stack overflow) propagate normally as uncaught exceptions.

**Pipeline Parallel:** Each stage runs on its own virtual thread, so exceptions can't propagate directly across thread boundaries. Instead:

1. **`SafeSource`** wraps the source `FlowOp` and catches `FearlessError` and `ArithmeticException` during `step`/`for`, converting them to `pushError` calls on the downstream sink.

2. **`Subject.processDataMsg`** catches `FearlessError` thrown by `downstream.#(data)`. When caught, the subject soft-closes the sink (discarding future messages) and calls `downstream.pushError(err.info)`:
   ```java
   try {
     downstream.$hash$mut(data);
   } catch (FearlessError err) {
     softClosed = true;
     try {
       downstream.pushError$mut(err.info);
     } catch (FearlessError err1) {
       throw new DeterministicFearlessError(err1.info);
     }
   }
   ```

3. **`DeterministicFearlessError`** is a special subclass of `FearlessError`. It exists for a subtle reason: when a nested flow's `pushError` call *itself* throws a `FearlessError` (because the terminal re-throws it as `Error!info`), that secondary throw must be recognized as deterministic and propagated through the pipeline without being wrapped in a `RuntimeException`. If it were wrapped, it would become a nondeterministic error — uncatchable by `Try` and guaranteed to crash the program.

   The `WrappedSink.stopDown` method catches `DeterministicFearlessError` separately and rethrows it directly:
   ```java
   try {
     subject.submit(Message.Stop.INSTANCE);
     subject.join();
   } catch (DeterministicFearlessError fe) {
     throw fe;  // Propagate as-is — this is a user error, not a system crash
   } catch (Throwable exception) {
     throw new RuntimeException(message, exception);  // Everything else → nondeterministic
   }
   ```

4. **Nondeterministic errors** (StackOverflow, etc.) on a worker thread are caught by the `stopDown`/`join` path and wrapped in a `RuntimeException`. These always crash the program because they represent JVM-level failures that Fearless cannot recover from.

**Data Parallel:** Each worker thread catches `FearlessError` independently:

```java
try {
  source.for$mut(downstream);
} catch (FearlessError err) {
  downstream.pushError$mut(err.info);  // Convert to pushError on this chunk's BufferSink
} catch (ArithmeticException err) {
  downstream.pushError$mut(Infos.msg(err.getMessage()));
}
```

The error is pushed to the chunk's `BufferSink`. When the `FlushWorker` flushes that buffer in order, it forwards the `pushError` to the real downstream sink. This means the first chunk to have an error (in chunk order, not time order) wins — ensuring deterministic error reporting.

Nondeterministic errors are caught by the thread's `UncaughtExceptionHandler`:
```java
final Thread.UncaughtExceptionHandler handler = (_,err) -> {
  var message = err.getMessage();
  if (err instanceof StackOverflowError) { message = "Stack overflowed"; }
  exception.compareAndSet(null, new RuntimeException(message, err));
};
```

Only the first nondeterministic exception is stored (`compareAndSet(null, ...)`). After all workers finish and buffers are flushed, it is rethrown.

#### Verified by Tests

```fearless
// Deterministic error: pushError propagates first, Error.msg is ignored because pushError already stopped
Flow#[Nat](1, 2, 3)
  .actor[Void,Nat](iso Void,{next,_,x -> Block#
    .if {x.nat == 2} .do {next.pushError(Infos.msg "hello")}
    .if {x.nat == 3} .do {Error.msg (x.str)}
    .do {next#(x.nat * 10)}
    .return {{}}
  })
  .fold[Nat]({0}, {a, x -> a + x})
// Crash: "hello" (pushError arrives first, Error.msg "3" is swallowed post-stop)

// Nondeterministic error: always crashes, even after stop
Flow#[Nat](1, 2, 3)
  .map{x -> Block#
    .if {x.nat == 2} .do {StackOverflow#}
    .return {x.nat * 10}
  }
  .limit(1)
  #(Flow.uSum)
// Crash: "Stack overflowed" (nondeterministic errors bubble past the limit)

// Data parallel: first error in element order wins
Flow.range(+0, +100_000)
  .map{i -> Error.msg[Int] (i.str)}
  .list
// Crash: "0" (element 0's error surfaces, not whichever chunk finished first)
```

---

## 7. Flow Reuse & Duplication

### Single-Use Enforcement

Flows are single-use. `_RestrictFlowReuse` wraps every flow with a boolean guard:

```fearless
_CheckFlowReuse: {
  #(isTail: mut Var[Bool]): Void -> isTail.get ? {
    .then -> isTail := False,
    .else -> Error.msg "This flow cannot be reused. Consider collecting it to a list first.",
  },
}
```

Every operation checks and flips this boolean. A second operation on the same flow throws an error:

```fearless
.let x = {Flow#[Nat](1,2,3).map{x -> x * 10}}
.let sum = {x#(Flow.uSum)}        // OK: first use
.let bigSum = {x.map{y -> y * 10}} // CRASH: "This flow cannot be reused"
```

### Flow Duplication via `.let`

`.let` enables consuming a flow multiple times by lazily materializing it to a list:

```fearless
Flow#[Int](+5, +10, +15)
  .let[Int,Str] f2 = {f1 -> f1# #(Flow.sum)}
  .map{n -> n + f2}
  .map{n -> n.str}
  .join(" ")
// Result: "35 40 45"
```

The implementation (`_LazyFlowDuplicators`) collects the flow to a list on first use, then creates fresh flows from that list for subsequent uses:

```fearless
_LazyFlowDuplicators: {
  #[E](flow: mut Flow[E]): mut _LazyFlowDuplicator[E] -> Block#
    .var collected = {mut Opt[mut List[E]]}
    .return {mut _LazyFlowDuplicator[E]: MF[mut Flow[E]]{
      # -> collected.get.match{
        .some(collected') -> collected'.flow,
        .empty -> Block#
          .let collected' = {flow.list}
          .do {collected := (Opts#collected')}
          .return {collected'.flow}
      },
    }}
}
```

---

## 8. The Three Execution Modes

### 8.1 Sequential (`_SeqFlow`)

Sequential is the simplest mode. All operations run on a single thread with the identity sink decorator `{s -> s}`:

```fearless
_SeqFlow: _FlowFactory{
  .fromOp[E](source: mut FlowOp[E], size: Opt[Nat]): mut Flow[E] -> _RestrictFlowReuse#{'self
    .filter(p) -> this.fromOp(_Filter#({s->s}, source, p), {}),
    .map(f) -> this.fromOp(_Map#({s->s}, source, f), size),
    .flatMap(f) -> this.fromOp(_FlatMap#({s->s}, source, f), {}),
    .actor[S,R](state, f) -> this.fromOp(_Actor#[S,E,R]({s->s}, source, state, f), {}),
    .limit(n) -> this.fromOp(_Limit#({s->s}, source, n), _LimitSize#(n, size)),
    // terminals
    .first -> _First[E]#(source, {s->s}),
    .findMap(f) -> _FindMap[E,R]#(source, f, {s->s}),
    .fold(acc, f) -> _Fold[S,E]#(source, acc#, f, {s->s}),
    // ...
  },
}
```

Every non-terminal recursively calls `this.fromOp(...)` to stay in the sequential factory. Elements flow directly from source through operator sinks to the terminal — no threading, no queues.

### 8.2 Pipeline Parallel (`_PipelineParallelFlow`)

Pipeline parallelism runs each operator stage on its own **virtual thread**, connected by bounded message queues.

#### Fearless Side

The Fearless definition is structurally identical to `_SeqFlow`, but uses `_PipelineParallelSink` instead of `{s->s}`:

```fearless
_PipelineParallelFlow: _FlowFactory{
  .fromOp[E](source, size): mut Flow[E] -> _RestrictFlowReuse#{'self
    .filter(p) -> this.fromOp(_Filter#(_PipelineParallelSink, source, p), {}),
    .map(f) -> this.fromOp(_Map#(_PipelineParallelSink, source, f), size),
    // ...same pattern for all operators...
    .first -> _First[E]#(source, _PipelineParallelSink),
    .fold(acc, f) -> _Fold[S,E]#(source, acc#, f, _PipelineParallelSink),
  },
}
```

`_PipelineParallelSink` is declared as `Magic!`:

```fearless
_PipelineParallelSink: _SinkDecorator{s -> Magic!}
```

#### Java Runtime

The magic is implemented by `PipelineParallelFlow.java`:

**`WrappedSink`** — the thread boundary between pipeline stages:
- On construction, creates a `Subject` (virtual-thread worker + bounded queue)
- `#(x)` submits the element to the queue (may block if full)
- `stopDown()` submits a `Stop` message, then joins the worker thread

**`Subject`** — per-stage virtual thread worker:
```java
final class Subject implements Runnable {
    private final _Sink_1 downstream;
    private final BlockingQueue<Object> buffer = new ArrayBlockingQueue<>(512);
    private final Thread worker;
}
```

- Buffer: `ArrayBlockingQueue<>(512)` — bounded, provides backpressure
- Thread: `Thread.ofVirtual().start(this)` — Java virtual threads (Project Loom)
- Backpressure: when the queue is full, the producer creates a `CompletableFuture` and waits up to 50ms for the consumer to drain

The `run()` loop processes messages until a `Stop` is received:
```java
while (true) {
    var msg = // take from queue
    if (msg == Message.Stop.INSTANCE) { downstream.stopDown$mut(); break; }
    if (msg instanceof Message.Error info) { processError(info); continue; }
    processDataMsg(msg);
}
```

**Error handling** is sophisticated: if `downstream.#(data)` throws a `FearlessError`, the subject soft-closes the sink and pushes the error downstream as a message. `DeterministicFearlessError` is a special subclass for nested flow errors that must be rethrown directly.

For a pipeline like `source.map(f).filter(p).fold(...)`:
- Source runs on the calling thread
- Map's downstream sink runs on virtual thread 1
- Filter's downstream sink runs on virtual thread 2
- Each boundary has its own 512-element queue

**SafeSource wrapping**: `PipelineParallelFlowK` wraps the source `FlowOp` in a `SafeSource` that catches exceptions during `step` and `for`, converting them to `pushError` calls. This prevents source-thread crashes from killing the pipeline.

#### When Pipeline Parallel is Chosen

Pipeline parallel is used when:
- The flow source is **infinite** (can't split data for DP)
- The collection is **very small** (< 4 elements — not worth fork-join overhead)
- A **stateful operator** (actor, limit, context-capturing map/peek) is encountered in a data-parallel flow (DP converts to PP)

### 8.3 Data Parallel (`_DataParallelFlow`)

Data parallelism splits the input data into chunks and processes each chunk through the entire operator chain on separate threads, then merges results in order.

#### Fearless Side

The entire `_DataParallelFlow` is `Magic!`:

```fearless
_DataParallelFlow: _FlowFactory{
  .fromOp[E](source: mut FlowOp[E], size: Opt[Nat]): mut Flow[E] -> Magic!,
}
```

#### Java Runtime — DataParallelFlow

`DataParallelFlow.java` implements the flow object. Non-terminal operators stay in DP mode:

```java
public Flow_1 map$mut(F_2 f_m$) {
  return $this.fromOp$imm(_Map_0.$self.$hash$imm(s->s, source_m$, f_m$), this.size_m$);
}
```

**Stateful operators convert to pipeline parallel** via `ConvertFromDataParallel`:

```java
public Flow_1 actor$mut(Object state_m$, ActorImpl_3 f_m$) {
  return ConvertFromDataParallel.of(this, size_m$).actor$mut(state_m$, f_m$);
}
```

#### Data Splitting — `SplitTasks`

`FlowOp.split()` recursively halves a data source. `SplitTasks.of(source, n)` splits up to `n` times:

```java
public static List<FlowOp_1> of(FlowOp_1 task, int n) {
  if (n == 1) { return List.of(task); }
  return Collections.unmodifiableList(split(List.of(task), n, n));
}
```

Splitting propagates through operators. For example, `_Filter` delegates to its upstream:
```fearless
.split -> upstream.split.map{right -> this#(sinkDecorator, right, predicate)},
```

Range splits at the midpoint: `mid = cursor + (size / 2)`, creating `FiniteRangeOp(mid, end)` and shortening the original to `[cursor, mid)`.

#### EODWorker — The Active Parallelism Strategy

EOD ("Explosive Ordnance Disposal") is the active data-parallel strategy. It uses semaphore-bounded virtual threads:

```java
private static final int TASKS_PER_CORE = 4;
private static final int N_CPUS = Runtime.getRuntime().availableProcessors();
public static final int PARALLELISM_POTENTIAL = TASKS_PER_CORE * N_CPUS;
public static final Semaphore AVAILABLE_PARALLELISM = new Semaphore(PARALLELISM_POTENTIAL);
```

The execution strategy:
1. Split source into `max(PARALLELISM_POTENTIAL / 2, 2)` chunks upfront
2. For each chunk, try to acquire a semaphore permit
3. If acquired: spawn a virtual thread to process the chunk
4. If no permit: run the chunk sequentially under `IS_SEQUENTIALISED` (preventing nested parallelism)
5. Last chunk always runs on the current thread
6. Wait for all workers via `CountDownLatch`
7. Flush buffered results in order

#### BufferSink — Ordered Output

Each worker writes to its own `BufferSink` backed by a `LinkedBlockingQueue`. A `FlushWorker` virtual thread drains these buffers **in registration order**, forwarding elements to the real downstream sink. This guarantees **deterministic output ordering** despite parallel processing.

Critically, `BufferSink.stopDown()` is a **no-op** — it swallows the stop signal from each chunk. The real `stopDown` is only sent after all chunks are flushed:

```java
public void stop(_Sink_1 original) {
  toFlush.add(FlusherElement.StopToken.$self);
  thread.join();
  original.stopDown$mut();  // Only now
}
```

#### Data Parallel Stop: The `isDPRunning` Flag

An `AtomicBoolean isDPRunning` is shared across all worker threads:

```java
@Override public Void_0 stopUp$mut() {
  isDPRunning.set(false);         // Signal all workers
  return source_m$.stopUp$mut();  // Propagate to original source
}
```

Workers check this flag (with relaxed reads for performance) before and during processing. A worker that finds the flag set to false will bail out. This provides eventual cancellation — workers may process a few extra elements before stopping.

#### DP-to-PP Bridge

When a data-parallel flow encounters a stateful operator, `ConvertFromDataParallel` bridges to pipeline parallel:

```java
static Flow_1 of(DataParallelFlow source, Opt_1 size) {
  var dpSource = source.getDataParallelSource();
  var dpConsumer = new DPSource(dpSource);
  return _PipelineParallelFlow_0.$self.fromOp$imm(dpConsumer, size);
}
```

This spawns a virtual thread to run the DP source, feeding elements into a `LinkedBlockingDeque`. The PP flow consumes from this queue. The DP portion still runs in parallel, but the stateful operator runs single-threaded.

---

## 9. The Codegen Magic Layer

The compiler intercepts flow-related calls and rewrites them to enable parallelism.

### Call Variant Assignment (`MIRInjectionVisitor`)

The compiler annotates `.flow()` calls and `Flow#(...)` constructors with `CallVariant` flags based on the source type and element mutability:

| Source | Element mdf | Variants |
|--------|-------------|----------|
| `List[E].flow` (read/imm) | any | DP + PP |
| `List[E].flow` (mut) | read/imm | DP + PP |
| `List[E].flow` (mut) | mut | Standard only |
| `UList[E].flow` (mut) | read/imm | DP + PP + SafeMutSource |
| `Str.flow` | N/A | DP + PP |
| `Flow.range(s, e)` (finite) | N/A | DP + PP + SafeMutSource |
| `Flow.range(s)` (infinite) | N/A | PP + SafeMutSource |
| `Flow#[E](...)` | read/imm | DP + PP + SafeMutSource |

**The key rule: mutable elements on mutable sources are always sequential** because parallel execution could cause data races.

### Parallel Constructor Selection (`FlowSelector`)

```java
static Optional<Id.DecId> bestParallelConstr(MIR.MCall call) {
  if (!call.canParallelise()) { return Optional.empty(); }
  return Optional.of(
    call.variant().contains(CallVariant.DataParallelFlow)
      ? Magic.DataParallelFlowK
      : Magic.PipelineParallelFlowK
  );
}
```

Data parallel is preferred when both DP and PP are available.

### `JavaMagicImpls.variantCall()` — The Rewriting Engine

This method intercepts flow calls and rewrites them:

**`Flow#[E](e1, e2, ...)`** is rewritten to `List#(e1, e2, ...).flow()`:
```java
if (isMagic(Magic.FlowK, call.recv()) && m.name().equals("#")) {
  var listKCall = new MIR.MCall(Magic.FListK, "#", call.args(), ...);
  var listFlowCall = new MIR.MCall(listKCall, ".flow", List.of(), ...);
  return gen.visitMCall(listFlowCall, true);
}
```

**`Flow.range(start, end)`** is wrapped with `FlowCreator.fromFlow`:
```java
if (m.name().equals(".range")) {
  return "rt.flows.FlowCreator.fromFlow(%s, %s)".formatted(
    gen.visitCreateObj(Magic.DataParallelFlowK, true),
    call.withVariants(Standard).accept(gen, true)
  );
}
```

**Generic `.flow()` calls** with parallel variants:
```java
if (m.equals(".flow") && parallelConstr.isPresent()) {
  return "rt.flows.FlowCreator.fromFlow(%s, %s.flow())".formatted(
    gen.visitCreateObj(parallelConstr.get(), true),
    call.recv().accept(gen, true)
  );
}
```

**`SafeMutSourceFlow` on UList**: redirects to `_SafeSource.fromList(list)` instead of the normal `.flow()`, creating a `FlowOp` with split support without cloning:
```java
if (variants.contains(SafeMutSourceFlow) && isMagic(Magic.UList, call.recv())) {
  return gen.visitMCall(new MIR.MCall(
    Magic.SafeFlowSource, ".fromList", List.of(call.recv()), ...
  ), true);
}
```

---

## 10. Runtime Degradation

`FlowCreator` is the runtime decision-maker that can **degrade** a flow's execution mode. It only ever degrades (DP → PP → Seq), never upgrades.

```java
static Flow_1 fromFlowOp(_FlowFactory_0 intended, FlowOp_1 op, long size) {
  // 1. Nested parallelism → force sequential
  if (IS_SEQUENTIALISED.isBound()) { return _SeqFlow.fromOp(op, optSize); }

  // 2. Infinite + DataParallel → PipelineParallel
  if (op.isFinite == False && intended instanceof DataParallelFlowK) {
    return PipelineParallelFlowK.fromOp(op, Opt.empty);
  }

  // 3. Unknown size → use intended
  if (size < 0) { return intended.fromOp(op, Opt.empty); }

  // 4. Size 2 → keep intended (potential fork-join binary split)
  if (size == 2) { return intended.fromOp(op, optSize); }

  // 5. Size <= 1 → sequential
  if (size <= 1) { return _SeqFlow.fromOp(op, optSize); }

  // 6. Size < 4 + DataParallel → PipelineParallel
  if (size < 4 && intended instanceof DataParallelFlowK) {
    return PipelineParallelFlowK.fromOp(op, optSize);
  }

  // 7. Default: use intended
  return intended.fromOp(op, optSize);
}
```

The `IS_SEQUENTIALISED` `ScopedValue` is bound by data-parallel workers when executing chunks inline (due to semaphore exhaustion). This prevents unbounded parallel nesting.

---

## 11. The Full Journey: From Fearless to Execution

### Example: `Flow#[Nat](1, 2, 3).map{x -> x * 10}#(Flow.uSum)`

**Compile time:**
1. `Flow#[Nat](1, 2, 3)` — elements are `imm Nat`
2. `MIRInjectionVisitor` assigns variants: `{DataParallelFlow, PipelineParallelFlow, SafeMutSourceFlow}`
3. `FlowSelector` picks `DataParallelFlowK` (DP preferred when available)
4. `variantCall` rewrites to: `FlowCreator.fromFlow(DataParallelFlowK.$self, List#(1,2,3).flow())`

**Runtime:**
1. `List#(1,2,3).flow()` creates a sequential flow via `_SafeSource.fromList`
2. `FlowCreator.fromFlow(DataParallelFlowK, seqFlow)`:
   - Unwraps the `FlowOp` from the sequential flow
   - `size = 3`, not infinite, not sequentialised
   - `size < 4 && intended instanceof DataParallelFlowK` → **degrades to PipelineParallel**
3. `PipelineParallelFlowK.fromOp(op, Opts#3)` creates a `_PipelineParallelFlow`
4. `.map{x -> x * 10}` creates `_Map` with `_PipelineParallelSink` decorator
5. `#(Flow.uSum)` calls `fold({0}, {acc, e -> acc + e})` which calls `_Fold`
6. `_Fold` calls `upstream.for(sink)` — the map's `for` calls the source's `for`
7. Source pushes elements through `WrappedSink` message queues between stages
8. Result: `60`

### Example: `Flow#[mut Nat](mut 1, mut 2, mut 3).map{x -> x.nat * 10}#(Flow.uSum)`

**Compile time:**
1. Elements are `mut Nat` on a `mut` source
2. `MIRInjectionVisitor` assigns variants: `{Standard}` (mut elements → no parallelism)
3. No parallel constructor selected
4. Codegen emits: `list.flow()` directly (no `FlowCreator` wrapping)

**Runtime:**
1. `List#(mut 1, mut 2, mut 3).flow()` creates a sequential flow
2. `.map`, `.fold` all use `{s->s}` identity decorator
3. Everything runs synchronously on one thread
4. Result: `60`

### Example: Large list with actor

```fearless
List.consumeUList(UList.withCapacity(10) + 1 + 2 + ... + 10).flow
  .actor[Void,Nat](iso Void, {next,_,x -> ... })
  .fold(...)
```

**Compile time:**
1. `List[Nat].flow` on a `mut` list with `imm` elements → variants: `{DP, PP}`
2. `FlowSelector` picks `DataParallelFlowK`

**Runtime:**
1. `FlowCreator.fromFlow(DataParallelFlowK, list.flow())` — size=10, DP-capable
2. `DataParallelFlowK.fromOp(...)` creates a `DataParallelFlow`
3. `.actor(...)` triggers `ConvertFromDataParallel.of(this, size)` — **DP converts to PP**
4. The DP portion (source iteration) still runs in parallel across chunks
5. The actor runs single-threaded on the PP side
6. `.fold(...)` consumes the PP flow

---

## 12. Test Examples

### Basic Operations

```fearless
// Sum
Flow#[Int](+5, +10, +15)#(Flow.sum)  // 30

// Filter + sum
Flow#[Int](+5, +10, +15).filter{n -> n > +5}#(Flow.sum)  // 25

// Map
Flow#[Int](+5, +10, +15).map{n -> n * +10}#(Flow.sum)  // 300

// Chained operators
As[List[Nat]]#(List#(5, 10, 15, 50)).flow
  .map{n -> n * 10}.map{n -> n * 10}
  .flatMap{n -> As[List[Nat]]#(List#(n + 1, n + 2, n + 3, n + 4)).flow}
  .map{n -> n * 10}
  .fold[Nat]({0}, {acc, n -> acc + n})  // 320400
```

### FlatMap + Limit

```fearless
Flow.range(-125, +496)
  .flatMap{n -> Flow.range(n, n + +5).limit(3)}
  .filter{n -> n > +100}
  .limit(10)
  .map{n -> n.str}
  .join(", ")
// "101, 101, 102, 101, 102, 103, 102, 103, 104, 103"
```

### Actor (Stateful Transformation)

```fearless
Flow#[Int](+5, +10, +15)
  .actor[mut Var[Int], Int](Vars#[Int]+1, {downstream, state, n -> Block#
    .do {state := (state* + n)}
    .if {state.get > +16} .return{Block#(downstream#(+500), {})}
    .do {downstream#(+42)}
    .do {downstream#n}
    .return {{}}})
  .map{n -> n.str}
  .join(" ")
// "42 5 42 10 500"
```

### Scan (Running Accumulation)

```fearless
Flow#[Nat](5, 10, 15)
  .scan[Str]("!", {acc, n -> acc + (n.str)})
  .map{n -> n.str}
  .join(" ")
// "!5 !510 !51015"
```

### Flow Duplication

```fearless
Flow#[Int](+5, +10, +15)
  .let[Int,Str] f2 = {f1 -> f1# #(Flow.sum)}
  .map{n -> n + f2}
  .map{n -> n.str}
  .join(" ")
// "35 40 45"
```

### Context-Aware Map

```fearless
Flow#[Nat](1, 2, 3, 4)
  .map[Ctx,Str](Ctxs#(Count.nat 0), {ctx, x -> ctx.n.str + (x.str)})
  .join " "
// "01 12 23 34"
// Context splits across parallel chunks, each getting its own counter
```

### Error Semantics

```fearless
// Error before stop → propagates
Flow#[Nat](1, 2, 3)
  .map{x -> Block#
    .if {x.nat == 2} .do {Error.msg (x.str)}
    .return {x.nat * 10}}
  #(Flow.uSum)
// Crash: "2"

// Error after stop → swallowed
Flow#[Nat](1, 2, 3)
  .map{x -> Block#
    .if {x.nat == 2} .do {Error.msg (x.str)}
    .return {x.nat * 10}}
  .limit(1)
  #(Flow.uSum)
// Result: 10

// Data parallel: first error wins
Flow.range(+0, +100_000)
  .map{i -> Error.msg[Int] (i.str)}
  .list
// Crash: "0" (the first element's error is reported)
```

### Rolling Average with Scan

```fearless
students.flow
  .flatMap{::grades.flow}
  .map{::score}
  .scan(
    Stats,
    {acc, n -> Stats{
      .avg -> (acc.avg * (acc.n) + n) / (acc.n + 1.0),
      .n -> acc.n + 1.0
    }}
  )
  .map{stats -> "The average grade for " + (stats.n.nat.str) + " students is " + (stats.avg.str)}
  .last!
// "The average grade for 3 students is 82.13333333333334"
```

### Sequential vs Parallel (Forced by Element Mutability)

```fearless
// mut elements → always sequential
Flow#[mut Nat](mut 1, mut 2, mut 3).map{x -> x.nat * 10}#(Flow.uSum)

// imm elements → can be parallelized
Flow#[Nat](1, 2, 3).map{x -> x * 10}#(Flow.uSum)
```

### Recursive Fork-Join with Flows

```fearless
Fib: {
  .seq(n: Nat): Nat -> Block#
    .if {n <= 1} .return {n}
    .return {Fib.seq(n - 1) + (Fib.seq(n - 2))},
  .flow(n: Nat): Nat -> Block#
    .if {n <= 35} .return {this.seq(n)}
    .return {As[List[Nat]]#(List#(n - 1, n - 2)).flow
      .map{n' -> Fib.flow(n')}
      .fold[Nat]({0}, {a,b -> a + b})
    },
}
Fib.flow(50)  // 12586269025 — computed with parallel recursive decomposition
```

This creates a 2-element flow `[49, 48]`. Since size=2 is preserved by `FlowCreator` as a potential fork-join case, the two recursive calls can run in parallel.

---

## Architecture Diagram

```
                    ┌─────────────────────────────────────────────┐
                    │              User Code (Fearless)           │
                    │   Flow#[Nat](1,2,3).map{...}.fold(...)      │
                    └──────────────────┬──────────────────────────┘
                                       │
                    ┌──────────────────▼──────────────────────────┐
                    │         Compiler (MIRInjectionVisitor)      │
                    │   Assigns CallVariants based on types/mdfs  │
                    │   {DataParallelFlow, PipelineParallelFlow}  │
                    └──────────────────┬──────────────────────────┘
                                       │
                    ┌──────────────────▼──────────────────────────┐
                    │      Codegen (JavaMagicImpls.variantCall)   │
                    │   Rewrites: FlowCreator.fromFlow(DPK, ...)  │
                    │   FlowSelector picks best parallel constr   │
                    └──────────────────┬──────────────────────────┘
                                       │
                    ┌──────────────────▼──────────────────────────┐
                    │      Runtime (FlowCreator.fromFlowOp)       │
                    │   Degradation: DP → PP → Seq                │
                    │   Based on: size, finiteness, nesting       │
                    └──────┬───────────┬───────────┬──────────────┘
                           │           │           │
              ┌────────────▼──┐  ┌─────▼───────┐  ┌▼───────────────┐
              │   _SeqFlow    │  │   PP Flow   │  │   DP Flow      │
              │  {s -> s}     │  │  WrappedSink│  │  EODWorker     │
              │  single thread│  │  VThreads   │  │  SplitTasks    │
              │               │  │  queues     │  │  BufferSink    │
              └───────────────┘  └─────────────┘  └────────────────┘
```
