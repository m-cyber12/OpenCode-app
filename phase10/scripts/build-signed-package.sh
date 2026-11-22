#!/usr/bin/env bash
# Build, sign, verify and package the release: one zip with the signed APK, the signed AAB,
# checksums, the signing certificate fingerprint, verification output and release notes.
#
# Used by .github/workflows/release-signed.yml (inside the `release` environment, where the
# signing secrets live) and runnable on any machine that holds the upload key:
#
#   RELEASE_KEYSTORE_FILE=~/keys/upload-keystore.p12 \
#   RELEASE_KEYSTORE_PASSWORD=... RELEASE_KEY_ALIAS=upload RELEASE_KEY_PASSWORD=... \
#   RELEASE_CERT_SHA256=AB:CD:...            # optional: refuse anything signed by another key \
#   bash phase10/scripts/build-signed-package.sh --label v0.1.0-beta.1
#
# Requires the embedded runtime payload (phase4/out/engine, see phase4/scripts/10-build-payload.sh).
# The keystore is read from RELEASE_KEYSTORE_FILE by Gradle (app/build.gradle.kts); this script never
# prints a password and never copies the keystore. Every failure stops the script: an unsigned or
# unverified package is never written to the output folder.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
LABEL=""
OUT="$ROOT/phase10/signing-package"
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "== $* =="; }

# ---- 0. inputs ------------------------------------------------------------------------------
[ -n "${RELEASE_KEYSTORE_FILE:-}" ] && [ -f "$RELEASE_KEYSTORE_FILE" ] \
  || die "RELEASE_KEYSTORE_FILE is not set or the file does not exist"
[ -n "${RELEASE_KEYSTORE_PASSWORD:-}" ] || die "RELEASE_KEYSTORE_PASSWORD is not set"
[ -n "${RELEASE_KEY_ALIAS:-}" ] || die "RELEASE_KEY_ALIAS is not set"
[ -f "$ROOT/phase4/out/engine/assets/runtime-manifest.json" ] \
  || die "the embedded runtime payload is missing (phase4/out/engine). Build it first."

GRADLE_FILE="$ROOT/app/build.gradle.kts"
VNAME=$(grep -E '^[[:space:]]*versionName = "' "$GRADLE_FILE" | head -1 | cut -d'"' -f2)
VCODE=$(grep -E '^[[:space:]]*versionCode = [0-9]+' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+$')
APPID=$(grep -E '^[[:space:]]*applicationId = "' "$GRADLE_FILE" | head -1 | cut -d'"' -f2)
[ -n "$VNAME" ] && [ -n "$VCODE" ] && [ -n "$APPID" ] || die "could not read versionName/versionCode/applicationId"
LABEL="${LABEL:-$VNAME}"
SAFE_LABEL=$(printf '%s' "$LABEL" | tr -c 'A-Za-z0-9._-' '-')
COMMIT=$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
find_tool() { find "$SDK/build-tools" -type f -name "$1" 2>/dev/null | sort -V | tail -1; }
APKSIGNER="$(find_tool apksigner)"
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || die "apksigner not found under \$ANDROID_HOME/build-tools"

# ---- 1. build ---------------------------------------------------------------------------------
step "gradle release APK + AAB (signed by Gradle with the configured key)"
( cd "$ROOT" && ./gradlew :app:assembleRelease :app:bundleRelease --no-daemon --stacktrace ) \
  || die "gradle release build failed"

APK_SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
AAB_SRC="$ROOT/app/build/outputs/bundle/release/app-release.aab"
[ -f "$APK_SRC" ] || die "no release APK at $APK_SRC"
[ -f "$AAB_SRC" ] || die "no release AAB at $AAB_SRC"

# ---- 2. verify the signatures -----------------------------------------------------------------
step "verify the APK signature (v2/v3 required for targetSdk 30+)"
"$APKSIGNER" verify --verbose --print-certs "$APK_SRC" > "$TMP/apk-signature.txt" 2>&1 \
  || { cat "$TMP/apk-signature.txt"; die "apksigner rejects the APK"; }
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' "$TMP/apk-signature.txt" \
  || die "the APK has no v2 signature"
APK_CERT=$(sed -n 's/^.*Signer #1 certificate SHA-256 digest: //p' "$TMP/apk-signature.txt" | head -1 | tr -d ' ')
[ -n "$APK_CERT" ] || die "could not read the APK certificate fingerprint"

step "verify the AAB signature"
command -v jarsigner >/dev/null 2>&1 || die "jarsigner not found (install a JDK)"
jarsigner -verify "$AAB_SRC" > "$TMP/aab-signature.txt" 2>&1 \
  || { cat "$TMP/aab-signature.txt"; die "jarsigner rejects the AAB"; }
grep -q 'jar verified' "$TMP/aab-signature.txt" || die "the AAB is not verified"
AAB_CERT=$(keytool -printcert -jarfile "$AAB_SRC" 2>/dev/null | sed -n 's/^.*SHA256: //p' | head -1 | tr -d ' ')
[ -n "$AAB_CERT" ] || die "could not read the AAB certificate fingerprint"
[ "$APK_CERT" = "$AAB_CERT" ] || die "the APK and the AAB are signed by different keys"

if [ -n "${RELEASE_CERT_SHA256:-}" ]; then
  EXPECT=$(printf '%s' "$RELEASE_CERT_SHA256" | tr -d ' :' | tr 'a-f' 'A-F')
  GOT=$(printf '%s' "$APK_CERT" | tr -d ' :' | tr 'a-f' 'A-F')
  [ "$EXPECT" = "$GOT" ] || die "signed by a different key than RELEASE_CERT_SHA256 expects (got $APK_CERT)"
  echo "certificate matches RELEASE_CERT_SHA256"
fi

# ---- 3. inspect the package (identity, icon, payload, permissions, not debuggable) -----------
step "inspect the APK and AAB"
ABIS=$(unzip -Z1 "$APK_SRC" | grep '^lib/' | cut -d/ -f2 | sort -u | tr '\n' ' ' | sed 's/ *$//')
[ -n "$ABIS" ] || die "the APK has no native libraries (the payload is missing from it)"
ABI_TAG=$(printf '%s' "$ABIS" | tr ' ' '+')
python3 "$ROOT/phase10/scripts/check-apk.py" "$APK_SRC" \
  --expect-package "$APPID" --expect-version-name "$VNAME" --expect-version-code "$VCODE" \
  --expect-signed --expect-not-debuggable --expect-icon --expect-payload \
  --expect-min-sdk 29 --expect-target-sdk 36 > "$TMP/apk-inspect.txt" 2>&1 \
  || { cat "$TMP/apk-inspect.txt"; die "check-apk.py found a problem in the APK"; }
python3 "$ROOT/phase10/scripts/check-apk.py" "$AAB_SRC" \
  --expect-package "$APPID" --expect-version-name "$VNAME" --expect-version-code "$VCODE" \
  --expect-payload > "$TMP/aab-inspect.txt" 2>&1 \
  || { cat "$TMP/aab-inspect.txt"; die "check-apk.py found a problem in the AAB"; }

# ---- 4. package -------------------------------------------------------------------------------
step "package"
PKG="opencode-android-$SAFE_LABEL"
STAGE="$TMP/stage/$PKG"
mkdir -p "$STAGE"
APK_NAME="$PKG-$ABI_TAG.apk"
AAB_NAME="$PKG.aab"
cp "$APK_SRC" "$STAGE/$APK_NAME"
cp "$AAB_SRC" "$STAGE/$AAB_NAME"
APK_BYTES=$(stat -c%s "$STAGE/$APK_NAME"); AAB_BYTES=$(stat -c%s "$STAGE/$AAB_NAME")

MANIFEST_INFO=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print("OpenCode %s (commit %s), git %s, ripgrep %s, payload %s" % (m.get("opencodeVersion","?"), str(m.get("opencodeCommit","?"))[:12], m.get("gitVersion","?"), m.get("rgVersion","?"), m.get("payloadVersion","?")))' \
  "$ROOT/phase4/out/engine/assets/runtime-manifest.json" 2>/dev/null || echo "see runtime-manifest.json")

{
  echo "OpenCode for Android - signed release package"
  echo "label:        $LABEL"
  echo "version:      $VNAME (versionCode $VCODE)"
  echo "package:      $APPID"
  echo "source:       commit $COMMIT"
  echo "built (UTC):  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "native ABIs:  $ABIS"
  echo "runtime:      $MANIFEST_INFO"
  echo "signing key:  $APK_CERT"
  echo
  echo "Files"
  echo "  $APK_NAME   sideload APK ($APK_BYTES bytes)"
  echo "  $AAB_NAME   Play App Bundle, upload this to Google Play ($AAB_BYTES bytes)"
  echo "  SHA256SUMS.txt, VERIFY.txt"
  echo
  echo "Install (sideload): open the APK on the phone and allow installs from this source."
  echo "Update: the same signing key must be used for every release; the fingerprint above must not change."
} > "$STAGE/RELEASE-NOTES.txt"

{
  echo "apksigner verify (APK):"; cat "$TMP/apk-signature.txt"
  echo; echo "jarsigner verify (AAB):"; cat "$TMP/aab-signature.txt"
  echo; echo "check-apk.py (APK):"; cat "$TMP/apk-inspect.txt"
  echo; echo "check-apk.py (AAB):"; cat "$TMP/aab-inspect.txt"
} > "$STAGE/VERIFY.txt"

( cd "$STAGE" && sha256sum "$APK_NAME" "$AAB_NAME" RELEASE-NOTES.txt VERIFY.txt > SHA256SUMS.txt )
( cd "$STAGE" && sha256sum -c SHA256SUMS.txt > /dev/null ) || die "checksum self-test failed"

mkdir -p "$OUT"
ZIP="$OUT/$PKG-signed.zip"
rm -f "$ZIP"
( cd "$TMP/stage" && zip -qr "$ZIP" "$PKG" )
unzip -tq "$ZIP" > /dev/null || die "the zip does not read back"

echo
echo "PACKAGE OK: $ZIP"
echo "  $(sha256sum "$ZIP")"
echo "  signing key SHA-256: $APK_CERT"
