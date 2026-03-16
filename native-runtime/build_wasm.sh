#!/bin/sh
# WASM Runtime Build
echo "Building WASM runtime..."
wasm-pack build --target web --features wasm
mkdir -p ../artefacts/rt-js/libwasm
cp -r pkg/* ../artefacts/rt-js/libwasm/
cp -r pkg/* ../assets/rt-js/libwasm/