#!/usr/bin/env bash
# sign-release-local.sh - the human signing step, scripted (docs/RELEASE.md s4).
#
# WHY THIS EXISTS: signing is the one step an automated system must never do, and
# it is also the step where a mistake ships an unsigned or wrongly-signed app.
# This script makes the procedure mechanical while keeping every secret in the
# terminal: passwords are read with `read -s` (never a command-line argument,
# never an environment variable that could be dumped, never a log file), and the
# keystore is only ever opened by apksigner/jarsigner.
#
# It signs the artifacts CI ALREADY BUILT - the bytes Phase 10 gated - and
# verifies the result, printing the certificate fingerprint for the record.
#
# Requirements: Android SDK build-tools on PATH (apksigner, zipalign) + a JDK
# (jarsigner, keytool). Install via Android Studio's SDK Manager or
# `sdkmanager "build-tools;34.0.0" "platform-tools"`.
#
# Usage:
#   bash phase10/scripts/sign-release-local.sh \
#     --apk app-release-unsigned.apk [--aab app-release.aab] \
#     --keystore ~/keys/upload-keystore.jks --alias upload [--out phase10/signing]
#
# What it will NOT do: create a keystore (docs/RELEASE.md s2 has the keytool
# command), copy or back up your keystore, or write a password anywhere.
set -uo pipefail

APK=""; AAB=""; KS=""; ALIAS="upload"; OUT="phase10/signing"
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --aab) AAB="${2:-}"; shift 2 ;;
    --keystore) KS="${2:-}"; shift 2 ;;
    --alias) ALIAS="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done

fail() { echo "FATAL: $*" >&2; exit 1; }

[ -n "$APK" ] || fail "--apk is required (the app-release-unsigned.apk from the Phase 10 run)"
[ -f "$APK" ] || fail "APK not found: $APK"
[ -n "$KS" ] || fail "--keystore is required"
[ -f "$KS" ] || fail "keystore not found: $KS"
command -v apksigner >/dev/null 2>&1 || fail "apksigner not on PATH (build-tools; see this file's header)"
command -v keytool >/dev/null 2>&1 || fail "keytool not on PATH (a JDK is required)"
if [ -n "$AAB" ]; then
  [ -f "$AAB" ] || fail "AAB not found: $AAB"
  command -v jarsigner >/dev/null 2>&1 || fail "jarsigner not on PATH (a JDK is required)"
fi

# The keystore must be OUTSIDE the repository. Signing material inside a git
# working tree is one `git add -A` away from being published; refuse it here
# rather than relying on .gitignore alone.
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
case "$(cd "$(dirname "$KS")" && pwd)/$(basename "$KS")" in
  "$REPO_ROOT"/*) fail "the keystore is inside the repository ($REPO_ROOT). Move it out: signing material must never be in the tree, and .gitignore is not a security control." ;;
esac

mkdir -p "$OUT" || fail "cannot create $OUT"
REPORT="$OUT/signing-report.txt"
: > "$REPORT"

log() { echo "$*" | tee -a "$REPORT"; }

# ---- the keystore's identity, before it is used -----------------------------
log "=== Phase 10 local signing $(date -u +%FT%TZ) ==="
log "keystore: $(basename "$KS") (path deliberately not logged in full)"
printf 'Keystore password: '; read -rs KSPASS; echo
printf 'Key password (Enter if the same as the keystore): '; read -rs KEYPASS; echo
[ -n "$KEYPASS" ] || KEYPASS="$KSPASS"

if ! keytool -list -keystore "$KS" -alias "$ALIAS" -storepass "$KSPASS" > "$OUT/keystore-entries.txt" 2>&1; then
  fail "cannot open the keystore with that password, or alias '$ALIAS' does not exist (see $OUT/keystore-entries.txt)"
fi
CERT_SHA=$(keytool -list -v -keystore "$KS" -alias "$ALIAS" -storepass "$KSPASS" 2>/dev/null \
  | grep -a "SHA256:" | head -1 | sed 's/.*SHA256: *//')
log "alias=$ALIAS certificate_sha256=${CERT_SHA:-unknown}"

# ---- APK: align, then sign --------------------------------------------------
# Alignment first: signing an unaligned APK and aligning afterwards would break
# the signature. `zipalign -p` page-aligns the native libraries (needed because
# extractNativeLibs=true moves them at install time).
APK_OUT="$OUT/$(basename "${APK%-unsigned.apk}")-signed.apk"
if command -v zipalign >/dev/null 2>&1; then
  zipalign -p -f 4 "$APK" "$OUT/aligned.apk" || fail "zipalign failed"
  log "zipalign: OK (page-aligned)"
else
  cp "$APK" "$OUT/aligned.apk"
  log "zipalign: NOT FOUND - signed as-is (AGP already aligned the 4-byte boundaries; verify with 'zipalign -c -v 4')"
fi
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KSPASS" --key-pass "pass:$KEYPASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK_OUT" "$OUT/aligned.apk" || fail "apksigner sign failed"
rm -f "$OUT/aligned.apk"
log "signed APK: $(basename "$APK_OUT") ($(stat -c%s "$APK_OUT" 2>/dev/null || stat -f%z "$APK_OUT") bytes)"

# ---- AAB: JAR signing (Play re-signs the delivered APKs) --------------------
if [ -n "$AAB" ]; then
  cp "$AAB" "$OUT/$(basename "$AAB")"
  AAB_OUT="$OUT/$(basename "$AAB")"
  printf '%s\n' "$KSPASS" | jarsigner -keystore "$KS" -storepass:stdin -sigalg SHA256withRSA \
    -digestalg SHA-256 "$AAB_OUT" "$ALIAS" >/dev/null 2>&1 || fail "jarsigner failed for the AAB"
  log "signed AAB: $(basename "$AAB_OUT") ($(stat -c%s "$AAB_OUT" 2>/dev/null || stat -f%z "$AAB_OUT") bytes)"
fi

# ---- verify, and record -----------------------------------------------------
apksigner verify --print-certs --verbose "$APK_OUT" > "$OUT/apksigner-verify.txt" 2>&1 \
  || fail "apksigner verify FAILED - do not upload this artifact (see $OUT/apksigner-verify.txt)"
VERIFIED_SHA=$(grep -a "certificate SHA-256 digest" "$OUT/apksigner-verify.txt" | head -1 | sed 's/.*digest: *//')
log "apksigner verify: OK signer_sha256=$VERIFIED_SHA"
if [ -n "$CERT_SHA" ] && [ -n "$VERIFIED_SHA" ] && [ "$CERT_SHA" != "$VERIFIED_SHA" ]; then
  fail "the signer's certificate does not match the keystore alias's certificate - investigate before uploading"
fi

# check-apk.py confirms the artifact's identity as well as its signature state.
CHECK="$(cd "$(dirname "$0")" && pwd)/check-apk.py"
for f in "$APK_OUT" "$OUT/$(basename "${AAB:-}")"; do
  [ -f "$f" ] || continue
  python3 "$CHECK" "$f" --expect-signed --expect-package io.github.mcyber12.opencode \
    --expect-version-name "$(grep -o 'versionName = "[^"]*"' -m1 "$REPO_ROOT/app/build.gradle.kts" | cut -d'"' -f2)" \
    >> "$REPORT" 2>&1 || echo "NOTE: check-apk.py reported a finding for $(basename "$f") - read $REPORT"
done

sha256sum "$APK_OUT" > "$OUT/release-sha256.txt" 2>/dev/null || shasum -a 256 "$APK_OUT" > "$OUT/release-sha256.txt"
[ -f "$OUT/$(basename "${AAB:-}")" ] && { sha256sum "$OUT/$(basename "$AAB")" >> "$OUT/release-sha256.txt" 2>/dev/null || shasum -a 256 "$OUT/$(basename "$AAB")" >> "$OUT/release-sha256.txt"; }

cat >> "$REPORT" <<EOF

Next steps (docs/RELEASE.md):
  1. install the signed APK on a real arm64 phone and run
     phase10/scripts/90-real-device-signed.sh --apk $APK_OUT
  2. upload the signed AAB to the Play Console
  3. keep this report: signer_sha256 above is the value every future release must show
EOF
echo
echo "Done. Report: $REPORT"
echo "Signed artifacts are in $OUT (that directory is gitignored - do not commit them)."
