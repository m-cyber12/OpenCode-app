#!/usr/bin/env bash
# TEST ONLY. Makes a copy of the phase10 arm64 test APK with the Node 26 spike binary
# added as lib/arm64-v8a/libnode26.so. The copy is signed with the committed PUBLIC
# test-only key (app/testonly-debug-signing.p12, alias androiddebugkey), the same key
# that signs the phase10 test APK, so it installs as an update over it. The release
# APK is never touched.
#
# Usage: inject-node26-test-apk.sh <source.apk> <node26-dir> <out.apk> [v2-bundle.zip]
#   <node26-dir> contains bin/node and optionally bin/libc++_shared.so.
#   [v2-bundle.zip] (optional) is the OpenCode v2 Node bundle from
#   spike/opencode-v2/build-v2-bundle.sh; it is stored as assets/node26-v2.zip.
set -Eeuo pipefail
trap 'echo "::error title=node26-test-apk::unplanned failure at line $LINENO: $BASH_COMMAND"' ERR

SRC="$1"; NODE_DIR="$2"; OUT="$3"; V2ZIP="${4:-}"
ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
KS="${TESTONLY_STORE:-app/testonly-debug-signing.p12}"

fatal() { echo "::error title=node26-test-apk::$*"; exit 1; }
[ -f "$SRC" ] || fatal "source APK missing: $SRC"
[ -x "$NODE_DIR/bin/node" ] || fatal "Node 26 binary missing: $NODE_DIR/bin/node"
[ -f "$KS" ] || fatal "test-only signing key missing: $KS"
if [ -n "$V2ZIP" ]; then [ -f "$V2ZIP" ] || fatal "v2 bundle missing: $V2ZIP"; fi
BT="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -n 1)"
ZIPALIGN="${BT}zipalign"
APKSIGNER="${BT}apksigner"
[ -x "$ZIPALIGN" ] && [ -x "$APKSIGNER" ] || fatal "zipalign/apksigner not found in $BT"

WORK="$(mktemp -d)"
cp "$SRC" "$WORK/in.apk"
if unzip -l "$WORK/in.apk" | grep -q 'lib/arm64-v8a/libc++_shared.so'; then
  zip -q -d "$WORK/in.apk" 'lib/arm64-v8a/libc++_shared.so'
  echo "replacing existing lib/arm64-v8a/libc++_shared.so with the NDK one Node 26 was built with"
fi
mkdir -p "$WORK/add/lib/arm64-v8a"
cp "$NODE_DIR/bin/node" "$WORK/add/lib/arm64-v8a/libnode26.so"
if [ -f "$NODE_DIR/bin/libc++_shared.so" ]; then
  cp "$NODE_DIR/bin/libc++_shared.so" "$WORK/add/lib/arm64-v8a/libc++_shared.so"
fi
( cd "$WORK/add" && zip -q -0 "$WORK/in.apk" lib/arm64-v8a/*.so )
if [ -n "$V2ZIP" ]; then
  # Already deflated; store it so the APK does not compress it twice.
  mkdir -p "$WORK/add/assets"
  cp "$V2ZIP" "$WORK/add/assets/node26-v2.zip"
  ( cd "$WORK/add" && zip -q -0 "$WORK/in.apk" assets/node26-v2.zip )
fi
"$ZIPALIGN" -f -P 16 4 "$WORK/in.apk" "$WORK/aligned.apk"
mkdir -p "$(dirname "$OUT")"
# Same signing scheme as phase10/scripts/40-release-verify.sh sign_testonly().
"$APKSIGNER" sign --ks "$KS" --ks-type PKCS12 --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled false --v2-signing-enabled true \
  --v3-signing-enabled true --v4-signing-enabled false \
  --out "$OUT" "$WORK/aligned.apk"

# Same signer as the source, and the Node 26 entry is present.
src_digest="$("$APKSIGNER" verify --print-certs "$SRC" | grep 'certificate SHA-256 digest' | head -n 1)"
out_digest="$("$APKSIGNER" verify --print-certs "$OUT" | grep 'certificate SHA-256 digest' | head -n 1)"
[ -n "$src_digest" ] && [ "$src_digest" = "$out_digest" ] || fatal "signer differs from the source APK"
unzip -l "$OUT" | grep -q 'lib/arm64-v8a/libnode26.so' || fatal "libnode26.so missing from the output APK"
if [ -n "$V2ZIP" ]; then unzip -l "$OUT" | grep -q 'assets/node26-v2.zip' || fatal "v2 bundle missing from the output APK"; fi
echo "signer: $out_digest"
sha256sum "$OUT"
ls -la "$OUT"
