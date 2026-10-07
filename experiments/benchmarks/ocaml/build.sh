#!/usr/bin/env bash
# Builds the OCaml and OxCaml executables for every benchmark in `src/`.
#
# OCaml is the upstream 5.4.1 switch. OxCaml is the 5.2.0+ox switch (Jane
# Street's Flambda2 compiler), and must always be labelled OxCaml. Both builds
# use the benchmark source unchanged. `-O3` is ignored by a compiler without
# Flambda, so passing it to both keeps one command line.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCHES=(mapLight mapHeavy mandelbrot nqueens primes wc grep)

rm -rf "$HERE/build" "$HERE/bin" "$HERE/bin-ox"
mkdir -p "$HERE/bin" "$HERE/bin-ox"

for b in "${BENCHES[@]}"; do
  for variant in ocaml oxcaml; do
    case $variant in
      ocaml)  switch=5.4.1;    out="$HERE/bin/recursive-$b" ;;
      oxcaml) switch=5.2.0+ox; out="$HERE/bin-ox/ox-$b" ;;
    esac
    dir="$HERE/build/$variant/$b"
    mkdir -p "$dir"
    cp "$HERE/src/$b/main.ml" "$dir/main.ml"
    (cd "$dir" && opam exec --switch="$switch" -- ocamlopt -O3 -o "$out" main.ml)
    echo "built $variant $b"
  done
done
