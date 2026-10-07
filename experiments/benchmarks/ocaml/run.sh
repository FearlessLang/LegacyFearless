#!/usr/bin/env bash
# Times the OCaml and OxCaml executables with hyperfine.
#
# Every executable's output is compared against `expected.txt` before it is
# timed. That file holds the checksums the Fearless configurations print, so a
# passing run means the OCaml columns compute what the Fearless columns compute.
# The programs read `corpus/` relative to the benchmarks directory, so this
# script runs from there. Results go to `results/<bench>-<variant>.json`.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUITE="$(dirname "$HERE")"
RESULTS="$SUITE/results"

read -r -a BENCHES <<< "${BENCHES:-mapLight mapHeavy mandelbrot nqueens primes wc grep}"
WARMUP="${WARMUP:-3}"
MIN_RUNS="${MIN_RUNS:-10}"

command -v hyperfine >/dev/null 2>&1 || { echo "error: hyperfine is not on PATH" >&2; exit 1; }
[ -d "$HERE/bin" ] || { echo "error: no executables; run $HERE/build.sh" >&2; exit 1; }

mkdir -p "$RESULTS"
cd "$SUITE"

for b in "${BENCHES[@]}"; do
  want=$(awk -v b="$b" '$1==b{$1="";sub(/^ /,"");print}' "$HERE/expected.txt")
  for pair in "ocaml $HERE/bin/recursive-$b" "oxcaml $HERE/bin-ox/ox-$b"; do
    read -r variant bin <<< "$pair"
    got=$("$bin")
    [ "$got" = "$want" ] || { echo "error: $b [$variant] printed '$got', expected '$want'" >&2; exit 1; }
    hyperfine -N --warmup "$WARMUP" --min-runs "$MIN_RUNS" \
      --command-name "$b/$variant" \
      --export-json "$RESULTS/$b-$variant.json" \
      "$bin" >/dev/null
    echo "ok $b $variant"
  done
done
