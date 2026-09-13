#!/bin/sh
# Builds the control client (with the mirror server) for Android phone, into the app as libclient.so.

set -eu

# Change to the directory this script is in
cd "$(dirname "$0")"

# Find the Android NDK root directory
NDK="${ANDROID_NDK_ROOT:-}"
if [ ! -d "$NDK/toolchains/llvm/prebuilt" ]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt | sort -t. -k1,1n -k2,2n -k3,3n | tail -1 | sed 's|/toolchains/llvm/prebuilt||')"
fi

# Find the bin directory for the toolchain
BIN="$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt" | sort | head -n1)/bin"

# Set cargo linker environment variable
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android29-clang"

# Clean previous build artifacts for a 'clean' build
cargo clean --manifest-path client/Cargo.toml

# Build the client
cargo build --release --manifest-path client/Cargo.toml --target aarch64-linux-android --target-dir target/android-client

# Copy the built library to the app
mkdir -p app/src/main/jniLibs/arm64-v8a
cp target/android-client/aarch64-linux-android/release/asterctl app/src/main/jniLibs/arm64-v8a/libclient.so

# List the copied library for confirmation
ls -la app/src/main/jniLibs/arm64-v8a/libclient.so
