#!/usr/bin/env python3
"""Turn the hyperfine JSON exports in `results/` into the summary table.

The three derived columns are the point of the exercise:

  overhead = feart / feart-novpf   what automatic parallelism costs when it
                                   buys nothing (the sequential elision)
  speedup  = feart-novpf / feart   what it buys
  java/feart                       how the two backends compare
"""

import json
import math
import pathlib
import sys

BENCHES = ["mapLight", "mapHeavy", "mandelbrot", "nqueens", "primes", "wc", "grep"]
CONFIGS = ["java", "feart-novpf", "feart"]
RESULTS = pathlib.Path(__file__).parent / "results"


def load(bench, config):
    path = RESULTS / f"{bench}-{config}.json"
    if not path.exists():
        return None
    runs = json.loads(path.read_text())["results"]
    return runs[0]["mean"], runs[0]["stddev"]


def geomean(values):
    values = [v for v in values if v is not None and v > 0]
    if not values:
        return None
    return math.exp(sum(math.log(v) for v in values) / len(values))


def fmt(value, unit=""):
    return "-" if value is None else f"{value:.3f}{unit}"


def main():
    header = ["benchmark", "java", "feart-novpf", "feart", "overhead", "speedup", "java/feart"]
    rows = []
    overheads, speedups, ratios = [], [], []

    for bench in BENCHES:
        means = {c: load(bench, c) for c in CONFIGS}
        if all(v is None for v in means.values()):
            continue
        java = means["java"][0] if means["java"] else None
        novpf = means["feart-novpf"][0] if means["feart-novpf"] else None
        feart = means["feart"][0] if means["feart"] else None

        overhead = feart / novpf if feart and novpf else None
        speedup = novpf / feart if feart and novpf else None
        ratio = java / feart if feart and java else None
        overheads.append(overhead)
        speedups.append(speedup)
        ratios.append(ratio)

        rows.append([
            bench,
            fmt(java, "s"), fmt(novpf, "s"), fmt(feart, "s"),
            fmt(overhead, "x"), fmt(speedup, "x"), fmt(ratio, "x"),
        ])

    if not rows:
        print(f"no results in {RESULTS} -- run ./run.sh first", file=sys.stderr)
        return 1

    rows.append([
        "geomean", "-", "-", "-",
        fmt(geomean(overheads), "x"), fmt(geomean(speedups), "x"), fmt(geomean(ratios), "x"),
    ])

    widths = [max(len(r[i]) for r in [header] + rows) for i in range(len(header))]
    def line(cells):
        return "  ".join(c.ljust(w) if i == 0 else c.rjust(w) for i, (c, w) in enumerate(zip(cells, widths)))

    print(line(header))
    print("  ".join("-" * w for w in widths))
    for row in rows[:-1]:
        print(line(row))
    print("  ".join("-" * w for w in widths))
    print(line(rows[-1]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
