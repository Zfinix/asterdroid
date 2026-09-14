#!/bin/sh
# Builds a signed release of asterdroid.
#
#   ./release.sh apk                  signed release APK
#   ./release.sh aab                  signed release App Bundle
#   ./release.sh apk -v 0.2.0 -c 2    set the version, then build
#   ./release.sh version              print the current version
#   ./release.sh notes                print the release notes for it
#   ./release.sh check                verify the toolchain and signing setup
#
# The native binaries are rebuilt by default, because a release that ships a
# stale libaster.so is the failure this exists to prevent. -s reuses whatever
# is already in jniLibs.

set -eu

cd "$(dirname "$0")"

ROOT="$PWD"
GRADLE="$ROOT/app/build.gradle.kts"
ASTER_REPO="${ASTER_REPO:-$ROOT/../aster}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

CMD=apk
OUT="$ROOT/dist"
SKIP_NATIVE=0
INSTALL=0
SET_NAME=""
SET_CODE=""

die() { echo "error: $*" >&2; exit 1; }

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    apk|aab|version|notes|check|help) CMD="$1" ;;
    -v|--version) [ $# -ge 2 ] || die "-v needs a version"; SET_NAME="$2"; shift ;;
    -c|--code)    [ $# -ge 2 ] || die "-c needs a version code"; SET_CODE="$2"; shift ;;
    -o|--out)     [ $# -ge 2 ] || die "-o needs a directory"; OUT="$2"; shift ;;
    -s|--skip-native) SKIP_NATIVE=1 ;;
    -i|--install)     INSTALL=1 ;;
    -h|--help)        CMD=help ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

read_version() {
  VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
  VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)"
  [ -n "$VERSION_NAME" ] || die "cannot read versionName from $GRADLE"
}

write_version() {
  tmp="$GRADLE.tmp"
  sed -e "s/versionCode = [0-9][0-9]*/versionCode = $2/" \
      -e "s/versionName = \"[^\"]*\"/versionName = \"$1\"/" "$GRADLE" > "$tmp"
  mv "$tmp" "$GRADLE"
}

check_sdk() {
  [ -n "$SDK" ] || die "ANDROID_HOME is not set"
  [ -d "$SDK" ] || die "ANDROID_HOME is not a directory: $SDK"
  [ -x "$ROOT/gradlew" ] || die "gradlew is missing or not executable"
}

check_signing() {
  [ -f "$ROOT/keystore.properties" ] || \
    die "keystore.properties is missing; the release build has no key to sign with"
  store="$(sed -n 's/^storeFile=//p' "$ROOT/keystore.properties")"
  [ -n "$store" ] || die "keystore.properties has no storeFile"
  case "$store" in /*) ;; *) store="$ROOT/$store" ;; esac
  [ -f "$store" ] || die "signing key not found: $store"
  for k in storePassword keyAlias keyPassword; do
    v="$(sed -n "s/^$k=//p" "$ROOT/keystore.properties")"
    [ -n "$v" ] || die "keystore.properties has an empty $k"
  done
}

build_native() {
  [ -f "$ASTER_REPO/Cargo.toml" ] || \
    die "no aster workspace at $ASTER_REPO; set ASTER_REPO to a checkout"
  echo "==> agent (libaster.so)"
  "$ROOT/build-agent.sh"
  echo "==> client (libclient.so)"
  "$ROOT/build-client.sh"
}

build_tools() {
  ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -1
}

case "$CMD" in
  help) usage; exit 0 ;;
esac

read_version

if [ -n "$SET_NAME" ] || [ -n "$SET_CODE" ]; then
  [ -n "$SET_NAME" ] || SET_NAME="$VERSION_NAME"
  [ -n "$SET_CODE" ] || SET_CODE="$VERSION_CODE"
  write_version "$SET_NAME" "$SET_CODE"
  read_version
  echo "version: $VERSION_NAME ($VERSION_CODE)"
fi

case "$CMD" in
  version)
    echo "$VERSION_NAME ($VERSION_CODE)"
    exit 0
    ;;
  notes)
    found=""
    for f in "$ROOT/docs/release-notes/v$VERSION_NAME.md" \
             "$ROOT"/docs/release-notes/v"$VERSION_NAME".*.md; do
      [ -f "$f" ] && found="$f" && break
    done
    [ -n "$found" ] || die "no release notes for $VERSION_NAME in docs/release-notes/"
    cat "$found"
    exit 0
    ;;
  check)
    check_sdk
    check_signing
    echo "sdk:      $SDK"
    echo "ndk:      $(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
    echo "build-tools: $(build_tools)"
    echo "aster:    $ASTER_REPO"
    echo "version:  $VERSION_NAME ($VERSION_CODE)"
    echo "signing:  ok"
    exit 0
    ;;
  apk) TASK=assembleRelease; ART="$ROOT/app/build/outputs/apk/release/app-release.apk"; EXT=apk ;;
  aab) TASK=bundleRelease;   ART="$ROOT/app/build/outputs/bundle/release/app-release.aab"; EXT=aab ;;
esac

check_sdk
check_signing

if [ "$SKIP_NATIVE" -eq 0 ]; then
  build_native
else
  [ -f "$ROOT/app/src/main/jniLibs/arm64-v8a/libaster.so" ] || \
    die "no libaster.so in jniLibs; drop -s to build it"
  [ -f "$ROOT/app/src/main/jniLibs/arm64-v8a/libclient.so" ] || \
    die "no libclient.so in jniLibs; drop -s to build it"
  echo "==> reusing jniLibs"
fi

echo "==> gradle $TASK"
"$ROOT/gradlew" --console=plain "$TASK"

[ -f "$ART" ] || die "gradle finished but produced no artifact at $ART"

mkdir -p "$OUT"
DEST="$OUT/asterdroid-$VERSION_NAME.$EXT"
cp "$ART" "$DEST"

if [ "$EXT" = apk ]; then
  APKSIGNER="$(build_tools)apksigner"
  if [ -x "$APKSIGNER" ]; then
    echo "==> signature"
    "$APKSIGNER" verify --print-certs "$DEST" | sed -n '1,4p'
  fi
fi

if [ "$INSTALL" -eq 1 ]; then
  echo "==> adb install"
  adb install -r "$DEST"
fi

echo
echo "built $DEST"
echo "size  $(du -h "$DEST" | cut -f1)"
if command -v sha256sum >/dev/null 2>&1; then
  echo "sha256 $(sha256sum "$DEST" | cut -d' ' -f1)"
else
  echo "sha256 $(shasum -a 256 "$DEST" | cut -d' ' -f1)"
fi
