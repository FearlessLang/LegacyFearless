#!/usr/bin/env bash
# Times every benchmark under every backend configuration with hyperfine.
#
# Builds happen out of band so that what is timed is the program, not the
# compiler. Every configuration's stdout is compared against the `feart`
# configuration's before anything is timed: identical checksums are what makes
# the three columns of the report comparable at all.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${JAR:-$HERE/../../compiler/target/fearless.jar}"
ENTRY=bench.Test
RESULTS="$HERE/results"

read -r -a BENCHES <<< "${BENCHES:-mapLight mapHeavy mandelbrot nqueens primes wc grep}"
CONFIGS=(feart feart-novpf java)
REFERENCE=feart

WARMUP="${WARMUP:-3}"
MIN_RUNS="${MIN_RUNS:-10}"

config_flags() {
  case "$1" in
    feart)       echo "--feart" ;;
    feart-novpf) echo "--feart --no-vpf" ;;
    java)        echo "" ;;
    *) echo "unknown config: $1" >&2; exit 1 ;;
  esac
}

# The command that runs an already-built benchmark. FeaRT emits a native
# executable; the Java backend is launched with the same command the compiler's
# own `--run` builds in LogicMainJava (`MakeJavaProcess.makeJavaCommand`), so
# that timing does not include the compiler re-checking the project on every
# iteration.
run_command() {
  local bench=$1 config=$2 out="$HERE/$1/out"
  case "$config" in
    feart|feart-novpf) printf '%s' "$out/Test" ;;
    java) printf '%s -cp %s:%s --enable-preview --enable-native-access=ALL-UNNAMED -ea base.FearlessMain %s' \
            "$JAVA_BIN" "$out" "$out" "$ENTRY" ;;
  esac
}

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "error: $1 is not on PATH" >&2; exit 1; }
}

require hyperfine
require java
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
[ -f "$JAR" ] || { echo "error: no compiler jar at $JAR (run: mvn -f compiler package -DskipTests)" >&2; exit 1; }

governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)
if [ "$governor" != performance ]; then
  echo "warning: CPU governor is '$governor', not 'performance'." >&2
  echo "         Run: sudo cpupower frequency-set -g performance" >&2
fi

# The corpora are generated, not committed: they are a function of the seed alone,
# so regenerating gives a bit-identical file and the checksums stay comparable
# across machines. `wc` and `grep` read the 1 MiB one; `wc-big` and `grep-big`
# read the 16 MiB one, at which size the JVM is warm and startup is ~2%.
for size in 1 16; do
  case $size in
    1) corpus=corpus.txt ;;
    *) corpus=corpus$size.txt ;;
  esac
  if [ ! -f "$HERE/corpus/$corpus" ]; then
    echo "corpus/$corpus missing; generating it (this takes a moment)"
    python3 "$HERE/gencorpus.py" --size-mb "$size" --out "$HERE/corpus/$corpus"
  fi
done

mkdir -p "$RESULTS"
cd "$HERE"

for bench in "${BENCHES[@]}"; do
  echo
  echo "=== $bench"
  reference_output=

  for config in "${CONFIGS[@]}"; do
    flags=$(config_flags "$config")
    echo "--- building $bench [$config]"
    # A stale out/ is not always invalidated, and the three configurations write
    # different things into it, so it is cleared rather than reused.
    rm -rf "$HERE/$bench/out"
    # shellcheck disable=SC2086
    java -jar "$JAR" "$HERE/$bench" --build $flags -e "$ENTRY" --quiet

    cmd=$(run_command "$bench" "$config")
    output=$(eval "$cmd")

    if [ "$config" = "$REFERENCE" ]; then
      reference_output=$output
      echo "    checksum: $output"
    elif [ "$output" != "$reference_output" ]; then
      echo "error: $bench [$config] disagrees with [$REFERENCE]" >&2
      echo "  $REFERENCE: $reference_output" >&2
      echo "  $config: $output" >&2
      exit 1
    fi

    hyperfine --warmup "$WARMUP" --min-runs "$MIN_RUNS" \
      --command-name "$bench/$config" \
      --export-json "$RESULTS/$bench-$config.json" \
      "$cmd"
  done
done

echo
python3 "$HERE/report.py"
