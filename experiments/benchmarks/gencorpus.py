#!/usr/bin/env python3
"""Generate the deterministic text corpus used by the `wc` and `grep` benchmarks.

The corpus is a function of the seed alone, so every machine that runs this
script gets a bit-identical `corpus/corpus.txt` and the benchmarks' checksums
are comparable across machines as well as across backends.
"""

import argparse
import pathlib
import random

SEED = 20240101
VOCAB_SIZE = 2000
WORDS_PER_LINE = (8, 14)
NEEDLE = "needle"
NEEDLE_RATE = 0.001
DEFAULT_SIZE_MB = 1

CONSONANTS = "bcdfghjklmnpqrstvwxyz"
VOWELS = "aeiou"


def build_vocabulary(rng):
    """A fixed pronounceable vocabulary, with the needle excluded so that the
    only occurrences in the corpus are the ones planted deliberately."""
    words = set()
    while len(words) < VOCAB_SIZE:
        syllables = rng.randint(1, 3)
        word = "".join(
            rng.choice(CONSONANTS) + rng.choice(VOWELS) + (rng.choice(CONSONANTS) if rng.random() < 0.4 else "")
            for _ in range(syllables)
        )
        if word != NEEDLE:
            words.add(word)
    return sorted(words)


def generate(out_path, size_bytes):
    rng = random.Random(SEED)
    vocab = build_vocabulary(rng)
    written = 0
    lines = 0
    needles = 0
    with out_path.open("w", encoding="ascii", newline="\n") as f:
        while written < size_bytes:
            count = rng.randint(*WORDS_PER_LINE)
            words = [rng.choice(vocab) for _ in range(count)]
            if rng.random() < NEEDLE_RATE:
                words[rng.randrange(count)] = NEEDLE
                needles += 1
            line = " ".join(words) + "\n"
            f.write(line)
            written += len(line)
            lines += 1
    return written, lines, needles


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size-mb", type=float, default=DEFAULT_SIZE_MB,
                        help=f"approximate corpus size in MiB (default {DEFAULT_SIZE_MB}; small "
                             "because Str.codepoints costs microseconds per character)")
    parser.add_argument("--out", type=pathlib.Path,
                        default=pathlib.Path(__file__).parent / "corpus" / "corpus.txt")
    args = parser.parse_args()

    args.out.parent.mkdir(parents=True, exist_ok=True)
    written, lines, needles = generate(args.out, int(args.size_mb * 1024 * 1024))
    print(f"{args.out}: {written} bytes, {lines} lines, {needles} lines containing '{NEEDLE}'")


if __name__ == "__main__":
    main()
