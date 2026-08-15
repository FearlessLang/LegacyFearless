# FeaRT benchmark suite

Wall-clock measurements of the FeaRT (Zig) backend against the original Java
backend, and of FeaRT against its own **sequential elision**.

The design follows Westrick et al., *Automatic Parallelism Management*
(POPL'24). The idea borrowed from that paper is the sequential elision
baseline: compile the same source with the parallelism machinery removed, and
report `T(parallel-machinery-on) / T(elision)` as the *overhead* of automatic
parallelism alongside `T(elision) / T(parallel)` as the *speedup*. In FeaRT the
elision is a flag, not a source rewrite -- `--no-vpf` compiles the heartbeat and
VPF system out.

## Configurations

| Config | Flags |
|---|---|
| `feart` | `--feart` |
| `feart-novpf` | `--feart --no-vpf` (sequential elision) |
| `java` | *(none)* |

All three run at full logical core count. FeaRT and the JVM both intentionally
use every core, so that is the fair comparison; there is no thread-count sweep.

## Running

```sh
mvn -f ../../compiler package -DskipTests   # if the jar is stale
./run.sh                                    # builds, checks checksums, times, reports
python3 report.py                           # re-print the table from results/
```

`run.sh` needs `hyperfine` on `PATH`. Knobs: `BENCHES`, `WARMUP`, `MIN_RUNS`,
`JAR`.

For each `(benchmark, config)` it clears `out/`, builds out of band, runs the
binary once and compares stdout against the `feart` configuration's, then times
it with hyperfine. A checksum mismatch aborts the run: silent divergence would
invalidate every number. The Java configuration is timed via the same
`java -cp … base.FearlessMain` command the compiler's own `--run` constructs
(`LogicMainJava`/`MakeJavaProcess`), so the timing does not include the compiler
re-checking the project on every iteration.

## Reading the table

```
benchmark   java   feart-novpf   feart   overhead   speedup   java/feart
```

* **overhead** = `feart / feart-novpf`. What automatic parallelism costs when
  it buys nothing.
* **speedup** = `feart-novpf / feart`. What it buys.
* **java/feart** — how the two backends compare.

Geomean row at the bottom.

## The benchmarks

Each is a self-contained Fearless project: `main.fear` plus a `pkg.fear` alias
preamble, `package bench`, entry point `bench.Test`. Each prints a checksum.

Sizes are tuned so the `feart` configuration lands in the 2--10 second range;
anything under a second is dominated by process startup.

| Benchmark | Size | Checksum | What it measures |
|---|---|---|---|
| `mapLight` | `n = 400_000_000` | `160000000000000000` | Divide and conquer with trivial per-element work. Leaf is one element, so `feart / feart-novpf` here *is* the cost of VPF. The adversarial case. |
| `mapHeavy` | `n = 500_000`, 1000 rounds | `10994161719475208166` | Same skeleton, expensive per-element kernel (xorshift mixing). Parallelism should be a clear win. |
| `mandelbrot` | `2048 x 2048`, 256 iters | `252714035` | Escape-time over the flat pixel range. Irregular per-pixel cost: no static granularity choice is right. |
| `nqueens` | `n = 14` | `365596` | Deep, irregular search tree. Stresses promotion policy on the critical path. |
| `primes` | `n = 500_000` | `41538` | Trial division as a flow, so parallelism comes from the flow driver rather than user-level VPF. Cost per element grows with the element. |
| `wc` | corpus | `<bytes> <lines> <words>` | Word-count monoid over `Str.codepoints`. **Blocked on FeaRT, see below.** |
| `grep` | corpus | line count | Literal `"needle"` matcher over `Str.codepoints`. **Blocked on FeaRT, see below.** |

Per the paper, the benchmarks are *fully parallel*: no manual granularity
control, no constant thresholds, recursion down to a leaf of one element. The
runtime, not the programmer, decides where to stop.

### Corpus

`wc` and `grep` read `corpus/corpus.txt`, produced by `gencorpus.py`. It is
seeded (`random.Random(20240101)`), so the corpus is bit-identical on every
machine and the checksums are comparable across machines as well as backends.
`"needle"` is planted on ~0.1% of lines and excluded from the vocabulary, so
the match count is stable and non-trivial.

```sh
python3 gencorpus.py               # default 0.5 MiB
python3 gencorpus.py --size-mb 64
```

The default is small because `Str.codepoints` currently costs on the order of
14 microseconds per character on the Java backend -- 1 MiB takes about 14
seconds. Raise it once that improves.

## Known blocker: `Str.codepoints` folds are wrong on FeaRT

`wc` and `grep` are excluded from `run.sh`'s default `BENCHES` because they
cannot pass the checksum gate. Minimal reproduction:

```
Test: Main {sys -> sys.io.println("abcdef".codepoints.fold[Nat]({0}, {a, _ -> a + 1}).str)}
```

* Java backend: `6` (correct)
* FeaRT: `3`

`"abcdef".codepoints.count` gives `6` on both, so it is the fold rather than the
source. `.codepoints` is given `DataParallelFlow | PipelineParallelFlow` in
`MIRInjectionVisitor.getVariants`, and the parallel fold path appears to return
one split's result rather than the whole. With a user-defined `imm` state object
as the accumulator, the same path instead dies at runtime with
`Failed to dispatch to bench.Fear90$/0 with method 14216550741596947392`, which
is `h("imm ==/1")` -- something in that path calls `==` on the accumulator.

Both programs are correct on the Java backend and are kept here as written; run
them with `BENCHES="wc grep" ./run.sh` once the runtime is fixed.

## Measurement hygiene

* CPU governor set to `performance`: `sudo cpupower frequency-set -g performance`.
  `run.sh` warns if it is not. Effects at the 5% scale are otherwise lost in
  boost-clock variance.
* Machine otherwise idle.
* Reference box: Ryzen 9 9950X, 16 physical / 32 logical cores, single socket.

## The `Clock` capability

Timing from inside a Fearless program uses `sys.clock.monotonic`, added for this
suite. It is an object capability rather than a magic singleton because of the
determinism contract: an `imm` method taking only `imm` arguments must be
deterministic given its arguments, and a clock is not. So the reading method has
a `mut` receiver reached from `System`, which also means a clock reading cannot
be captured into anything you want VPF'd.

It rides the existing `_System` magic machinery and needs no compiler changes:
`assets/base/caps/clock.fear`, `assets/rt/Clock.java` for the Java backend, and
`assets/feaRT/src/runtime/intrinsics/caps/clock.zig` for FeaRT.

The benchmarks here do not use it -- wall clock via hyperfine is the headline
number, and startup cost is real cost -- but it is what an in-process self-timed
mode would be built on.

## Out of scope

No hand-written Zig reference implementations, no thread-count sweep, no
`msort`/`bfs`/`delaunay` ports.
