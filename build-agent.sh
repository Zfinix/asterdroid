#!/bin/sh
# Builds the aster binary for the phone and drops it into the app as libaster.so.
#
# Rust's stock Android target keeps native thread-local storage off, so every
# thread-local costs one of bionic's 128 pthread keys and the embedded Python
# aborts with "out of TLS keys". The target spec here turns it on, which LLVM
# lowers to emulated TLS on Android (a single key for everything), and that
# needs __emutls_get_address from the NDK's compiler-rt, which rustc's
# -nodefaultlibs link would otherwise leave out.

set -eu

cd "$(dirname "$0")"

# Set up ASTER repo location.
ASTER_REPO="${ASTER_REPO:-$PWD/../aster}"
if [ ! -f "$ASTER_REPO/Cargo.toml" ]; then
  echo "no aster workspace at $ASTER_REPO; set ASTER_REPO to a checkout" >&2
  exit 1
fi

# Locate Android NDK root.
NDK="${ANDROID_NDK_ROOT:-}"
if [ ! -d "$NDK/toolchains/llvm/prebuilt" ]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt 2>/dev/null | \
         sort -t. -k1,1n -k2,2n -k3,3n | tail -1 | sed 's|/toolchains/llvm/prebuilt||')"
fi

HOST="$(ls "$NDK/toolchains/llvm/prebuilt")"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
RT="$(ls "$NDK/toolchains/llvm/prebuilt/$HOST/lib/clang/"*/lib/linux/libclang_rt.builtins-aarch64-android.a 2>/dev/null | head -1)"

export ANDROID_NDK_ROOT="$NDK"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android29-clang"
export CC_aarch64_linux_android="$BIN/aarch64-linux-android29-clang"
export CXX_aarch64_linux_android="$BIN/aarch64-linux-android29-clang++"
export RANLIB_aarch64_linux_android="$BIN/llvm-ranlib"

# Setup and build static libffi for RustPython's ctypes, if necessary.
FFI_DIR="$PWD/target/android-tls/libffi"
FFI_A="$FFI_DIR/lib/libffi.a"
if [ ! -f "$FFI_A" ]; then
  mkdir -p "$FFI_DIR/src"
  curl -sL https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz | \
    tar xz -C "$FFI_DIR/src" --strip-components=1
  (
    cd "$FFI_DIR/src"
    ./configure --host=aarch64-linux-android --prefix="$FFI_DIR" \
      CC="$BIN/aarch64-linux-android29-clang" \
      AR="$BIN/llvm-ar" \
      RANLIB="$BIN/llvm-ranlib" \
      --disable-shared --enable-static --disable-docs >/dev/null
    make -j8 >/dev/null
    make install >/dev/null
  )
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=$RT -L $FFI_DIR/lib"
# build-std and JSON target specs are nightly features; rust-src must be installed.
export RUSTC_BOOTSTRAP=1

cargo build --release -p aster-cli --bin aster \
  --manifest-path "$ASTER_REPO/Cargo.toml" \
  --target target-spec/aarch64-linux-android.json \
  --target-dir target/android-tls \
  -Zbuild-std=std,panic_abort \
  -Zjson-target-spec

mkdir -p app/src/main/jniLibs/arm64-v8a
cp target/android-tls/aarch64-linux-android/release/aster app/src/main/jniLibs/arm64-v8a/libaster.so
ls -la app/src/main/jniLibs/arm64-v8a/libaster.so
