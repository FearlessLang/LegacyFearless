#!/bin/sh
# RT Lib
CROSS_CONTAINER_OPTS="--platform linux/amd64" cross build --features 'runtime-only' --release --target x86_64-unknown-linux-gnu
cp target/x86_64-unknown-linux-gnu/release/libnative_rt.so ../artefacts/rt/libnative/amd64-libnative_rt.so

cross build  --features 'runtime-only' --release --target aarch64-unknown-linux-gnu
cp target/aarch64-unknown-linux-gnu/release/libnative_rt.so ../artefacts/rt/libnative/arm64-libnative_rt.so

CROSS_CONTAINER_OPTS="--platform linux/amd64" cross build  --features 'runtime-only' --release --target x86_64-pc-windows-gnu
cp target/x86_64-pc-windows-gnu/release/native_rt.dll ../artefacts/rt/libnative/amd64-native_rt.dll

cross build  --features 'runtime-only' --release --target aarch64-apple-darwin
cp target/aarch64-apple-darwin/release/libnative_rt.dylib ../artefacts/rt/libnative/arm64-libnative_rt.dylib

cross build  --features 'runtime-only' --release --target x86_64-apple-darwin
cp target/x86_64-apple-darwin/release/libnative_rt.dylib ../artefacts/rt/libnative/amd64-libnative_rt.dylib

# Compiler Lib
CROSS_CONTAINER_OPTS="--platform linux/amd64" cross build --features 'compiler-only' --release --target x86_64-unknown-linux-gnu
cp target/x86_64-unknown-linux-gnu/release/libnative_rt.so ../artefacts/rt/libnative/amd64-libnative_compiler.so

cross build  --features 'compiler-only' --release --target aarch64-unknown-linux-gnu
cp target/aarch64-unknown-linux-gnu/release/libnative_rt.so ../artefacts/rt/libnative/arm64-libnative_compiler.so

CROSS_CONTAINER_OPTS="--platform linux/amd64" cross build  --features 'compiler-only' --release --target x86_64-pc-windows-gnu
cp target/x86_64-pc-windows-gnu/release/native_rt.dll ../artefacts/rt/libnative/amd64-native_compiler.dll

cross build  --features 'compiler-only' --release --target aarch64-apple-darwin
cp target/aarch64-apple-darwin/release/libnative_rt.dylib ../artefacts/rt/libnative/arm64-libnative_compiler.dylib

cross build  --features 'compiler-only' --release --target x86_64-apple-darwin
cp target/x86_64-apple-darwin/release/libnative_rt.dylib ../artefacts/rt/libnative/amd64-libnative_compiler.dylib

# C-ABI staticlib (feature `capi`) for the Zig runtime. Output naming:
#   static/<arch>-<os>-libnative_rt.a   (arch: amd64/arm64, os: linux/macos/windows)
mkdir -p ../artefacts/rt/libnative/static

# Host (amd64 linux): built with the standard toolchain so the .a stays linkable
# against glibc <= 2.34 (the version Zig pins to). Falls back to `cross` only if
# the host target is unavailable locally.
cargo build --features 'capi' --release --target x86_64-unknown-linux-gnu
cp target/x86_64-unknown-linux-gnu/release/libnative_rt.a ../artefacts/rt/libnative/static/amd64-linux-libnative_rt.a

cross build --features 'capi' --release --target aarch64-unknown-linux-gnu
cp target/aarch64-unknown-linux-gnu/release/libnative_rt.a ../artefacts/rt/libnative/static/arm64-linux-libnative_rt.a

cross build --features 'capi' --release --target aarch64-apple-darwin
cp target/aarch64-apple-darwin/release/libnative_rt.a ../artefacts/rt/libnative/static/arm64-macos-libnative_rt.a

cross build --features 'capi' --release --target x86_64-apple-darwin
cp target/x86_64-apple-darwin/release/libnative_rt.a ../artefacts/rt/libnative/static/amd64-macos-libnative_rt.a

CROSS_CONTAINER_OPTS="--platform linux/amd64" cross build --features 'capi' --release --target x86_64-pc-windows-gnu
cp target/x86_64-pc-windows-gnu/release/libnative_rt.a ../artefacts/rt/libnative/static/amd64-windows-libnative_rt.a
