#!/usr/bin/env bash
set -euo pipefail
S=/tmp/claude-1000/-home-nick-Projects-fearless-experiments-fearless-feart-compiler/2a870739-43c5-45d4-b982-c851f2c2147c/scratchpad
OC=/home/nick/Projects/fearless/experiments/benchmarks/ocaml
cd /home/nick/Projects/fearless/experiments/fearless-feart/experiments/benchmarks
mkdir -p $S/new/results-ocaml
for b in mapLight mapHeavy mandelbrot nqueens primes wc grep; do
  want=$(awk -v b=$b '$1==b{$1="";sub(/^ /,"");print}' $OC/expected.txt)
  for pair in "ocaml $OC/bin/recursive-$b" "oxcaml $OC/bin-ox/ox-$b"; do
    set -- $pair; name=$1; bin=$2
    got=$($bin)
    [ "$got" = "$want" ] || { echo "MISMATCH $b $name: got '$got' want '$want'" >&2; exit 1; }
    hyperfine -N --warmup 3 --min-runs 10 --command-name "$b/$name" --export-json "$S/new/results-ocaml/$b-$name.json" "$bin" >/dev/null
    echo "ok $b $name"
  done
done
