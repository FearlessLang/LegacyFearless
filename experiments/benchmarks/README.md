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

Geomean row at the bottom. See [Results](#results) for why the `java/feart`
column and its geomean must not be read as a like-for-like backend comparison.

## The benchmarks

Each is a self-contained Fearless project: `main.fear` plus a `pkg.fear` alias
preamble, `package bench`, entry point `bench.Test`. Each prints a checksum.

Sizes are tuned so the `feart` configuration lands in the 2--10 second range;
anything under a second is dominated by process startup. `wc` and `grep` are the
documented exception -- see [Corpus size](#corpus-size).

| Benchmark | Size | Checksum | What it measures |
|---|---|---|---|
| `mapLight` | `n = 1_400_000_000` | `1960000000000000000` | Divide and conquer with trivial per-element work. Leaf is one element, so `feart / feart-novpf` here *is* the cost of VPF. The adversarial case. |
| `mapHeavy` | `n = 1_500_000`, 1000 rounds | `8149048745584505931` | Same skeleton, expensive per-element kernel (xorshift mixing). Parallelism should be a clear win. |
| `mandelbrot` | `2048 x 2048`, 256 iters | `252714035` | Escape-time over the flat pixel range. Irregular per-pixel cost: no static granularity choice is right. |
| `nqueens` | `n = 14` | `365596` | Deep, irregular search tree. Stresses promotion policy on the critical path. |
| `primes` | `n = +1_000_000` | `78498` | Trial division as a flow, so parallelism comes from the flow driver rather than user-level VPF. Cost per element grows with the element. |
| `wc` | corpus, 1 MiB | `1048629 15009 164764` | Word-count monoid over `Str.codepoints`. |
| `grep` | corpus, 1 MiB | `21` | Literal `"needle"` matcher over `Str.codepoints`. |

Several checksums are independently verifiable, which is what validates the
implementations rather than just their self-consistency: `mapLight` is exactly
`n^2`, `primes` is `pi(10^6) = 78498`, `nqueens` is the known 14-queens count,
and `wc`/`grep` match `gencorpus.py`'s own byte, line and match counts.

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
python3 gencorpus.py               # default 1 MiB
python3 gencorpus.py --size-mb 64
```

## Style: direct functional Fearless

The benchmarks are written in direct functional Fearless. Conditionals are
`.if[T]{.then -> …, .else -> …}` and `.match[T]{…}`; intermediate values are
methods or object fields; data is modelled as objects (`Complex`, `Board`,
`Needle`, `WcState`) wherever an object states a contract a bare `Nat` cannot.
`Block#` appears **nowhere on a hot or structural path** -- the single remaining
use is one `.let` of setup in `grep`'s `Test`.

This is not only a matter of taste. The Java backend has an optimisation that
flattens most `Block`s into statements; FeaRT deliberately does **not**, because
the goal is for the functional style to be fast without imperative crutches.
Benchmarking FeaRT on `Block#`-shaped code measures the one style it declines to
special-case.

The effect is large and it is the most robust result in this suite. Rewriting
the original `Block#`-heavy benchmarks into this style, at matched work, made
FeaRT's **sequential** code several times faster:

| Benchmark | elision before | elision after | per-unit-work improvement |
|---|---|---|---|
| `mapHeavy` | 63.29 s @ n=500k | 33.76 s @ n=1.5M | **5.6x** |
| `nqueens` | 17.17 s @ n=13 | 7.57 s @ n=13 | **2.3x** |

So the functional style is not merely idiomatic on FeaRT, it is substantially
the faster style. The `Block#` chain is the slow path.

## Results

Ryzen 9 9950X, 16 physical / 32 logical cores, governor `performance`, idle.
`--warmup 3 --min-runs 10`.

```
benchmark      java  feart-novpf   feart  overhead  speedup  java/feart
----------  -------  -----------  ------  --------  -------  ----------
mapLight     2.510s      26.465s  2.264s    0.086x  11.689x      1.109x
mapHeavy     2.385s      33.759s  2.113s    0.063x  15.980x      1.129x
mandelbrot   0.810s      49.170s  2.800s    0.057x  17.559x      0.289x
nqueens      2.170s      47.241s  2.811s    0.060x  16.806x      0.772x
primes       0.114s       2.644s  2.720s    1.029x   0.972x      0.042x
wc          14.816s       0.469s  0.473s    1.010x   0.990x     31.294x
grep        14.909s       0.605s  0.616s    1.017x   0.983x     24.204x
----------  -------  -----------  ------  --------  -------  ----------
geomean           -            -       -    0.212x   4.719x      1.366x
```

### Finding 1: VPF delivers, where it engages

On the four divide-and-conquer benchmarks VPF is a large, real win over its own
elision, at close to full machine utilisation (parallelism measured as
`user / wall` from hyperfine):

| Benchmark | speedup vs elision | parallelism |
|---|---|---|
| `mandelbrot` | 17.6x | 23.6x |
| `nqueens` | 16.8x | 24.0x |
| `mapHeavy` | 16.0x | 26.6x |
| `mapLight` | 11.7x | 26.6x |

`mapLight` is the adversarial case -- a single arithmetic op per leaf -- and
still returns 11.7x, so promotion overhead is not dominating even at the
smallest useful grain size.

### Finding 2: the scalar gap, not the parallelism, is what is left

The `java/feart` column flatters FeaRT and should not be quoted on its own.
Java has no VPF; on `mapLight`, `mapHeavy`, `mandelbrot` and `nqueens` the JVM
runs these programs **effectively sequentially** (measured parallelism 1.02x,
1.02x, 1.07x, 1.11x). So a `feart` figure that beats `java` is 26 cores beating
one, not a better compiler.

The honest scalar comparison is `java` against `feart-novpf`:

| Benchmark | java | feart-novpf | FeaRT scalar penalty |
|---|---|---|---|
| `mapLight` | 2.510s | 26.465s | **10.5x** |
| `mapHeavy` | 2.385s | 33.759s | **14.2x** |
| `nqueens` | 2.170s | 47.241s | **21.8x** |
| `mandelbrot` | 0.810s | 49.170s | **60.7x** |

FeaRT's generated scalar code remains 10--60x slower than the JVM's JIT output.
`mandelbrot` is the worst case by a wide margin and is the one to attack first:
it is float-heavy and allocates a `Complex` per escape iteration.

This is still a substantial improvement on the pre-rewrite position, where the
elision was ~90x slower than Java. Roughly half of that gap was the `Block#`
style rather than codegen.

### Finding 3: FeaRT's flows do not parallelise; Java's do

`primes`, `wc` and `grep` get their parallelism (if any) from the flow driver
rather than from VPF. On FeaRT all three run at **1.0x parallelism** -- fully
sequential -- while Java runs `primes` at 17.6x and `wc`/`grep` at ~24x.

For `primes` this is a real capability gap: Java extracts 17.6x from
`Flow.range(…).filter(…).count` and FeaRT extracts none, which is most of why
`primes` shows `java/feart` of 0.042x. Whether this is intended fallout of
restricting `.fold` to sequential execution (commit `51d1564`) or an
over-restriction that also caught `.filter`/`.count` is worth checking.

### Finding 4: Java's `.codepoints` is quadratic

`wc` and `grep` are the only benchmarks where FeaRT wins outright (31x, 24x),
and it is not because FeaRT is fast. Java's `.codepoints` scales quadratically
in corpus size while FeaRT's scales linearly:

| Corpus | java `wc` | feart `wc` |
|---|---|---|
| 0.25 MiB | 1.06 s | 0.14 s |
| 0.5 MiB | 3.77 s | 0.24 s |
| 1 MiB | 14.50 s | 0.48 s |
| 2 MiB | 56.40 s | 0.96 s |
| 5 MiB | >270 s | 2.29 s |

Java is 3.9x per doubling (O(n^2)); FeaRT is 2.0x per doubling (O(n)). The Java
run also burns ~24x parallelism doing it -- 32 ForkJoinPool workers accumulating
357 s of CPU for 14.8 s of wall clock -- because `.codepoints` is a DP-eligible
non-terminal flow source, so the backend parallelises an algorithmically
quadratic decode. GC is not involved at all (GC threads accumulate zero CPU).

The likely mechanism is codepoint indexing that rescans from the start of the
string per element, but this has not been confirmed. **This is an open bug, not
a benchmark result.**

Consequently the `java/feart` geomean of 1.366x is meaningless as a summary:
it is dragged to ~1.0 by `wc`/`grep`, which measure a Java bug. Excluding them,
FeaRT is at parity on `mapLight`/`mapHeavy` (only by spending 26 cores) and
behind everywhere else.

## Corpus size

The corpus is 1 MiB. There is **no size that puts both backends in the 2--10 s
band**, because of the quadratic behaviour above: FeaRT needs ~4 MiB to reach
2 s, which costs Java about four minutes per iteration. 1 MiB is the compromise
-- Java 14.8 s (bounded, ~3 min per cell at full run counts), FeaRT 0.47 s, of
which roughly 0.13 s is process startup, so about 0.35 s is real signal.

`wc` and `grep` therefore sit below the band by design. Raise
`DEFAULT_SIZE_MB` in `gencorpus.py` once `.codepoints` on the Java backend is
fixed.

## Writing benchmarks that VPF can actually promote

`ComputeVPFMode` promotes a call only when at least two of
{receiver, arguments} are themselves method calls. Two source-level habits
silently destroy this, and both cost the entire speedup while leaving the
program correct:

1. **Binding the halves.** `.let mid = …` then `this.solve(lo, mid)` makes the
   operands variable references rather than calls. The benchmarks recompute
   `.mid(lo, hi)` at both use sites instead; two integer ops is the right price.
2. **Burying the combining call more than one lambda arm deep.** A combining
   call directly inside a single `.if` arm is promoted; the identical call
   inside a *second*, nested `.if` is not promoted at all.

The second cost `nqueens` all of its parallelism during this rewrite and is
invisible in the source. Measured at n=13:

| `.tryCols` shape | feart | elision | VPF |
|---|---|---|---|
| guard `.if` wrapping the split `.if` | 6.894 s | 6.810 s | **none (1.0x)** |
| single `.if`, guard hoisted to caller | 1.074 s | 7.572 s | **7.05x** |

Same algorithm, same checksum, 6x apart. The rule of thumb: **keep guards out of
the recursive method** -- establish preconditions at the call site so the
recursive method contains exactly one `.if` whose `.else` is the combining call.

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
