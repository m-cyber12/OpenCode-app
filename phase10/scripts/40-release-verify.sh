#!/usr/bin/env bash
# 40-release-verify.sh - build the release APK + AAB the way CI must (UNSIGNED)
# and inspect the actual bytes before they are published as artifacts.
#
# This stage answers, from the artifact itself rather than from the build log:
#   * is the applicationId the pinned one (and NOT the debug suffix)?
#   * do versionCode/versionName match versions.lock?
#   * is debuggable=false in the release manifest (Play rejects release artifacts
#     that are debuggable)?
#   * is the artifact UNSIGNED? (In CI it must be: docs/RELEASE.md s8. If a
#     keystore ever appears in CI this gate fails loudly instead of quietly
#     signing with something.)
#   * does it carry the app icon, the embedded runtime payload, both ABIs, and
#     only the permissions the app declares?
#
# Usage: bash phase10/scripts/40-release-verify.sh     (needs the payload staged)
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
REL="$OUT/release"
SMOKE="$OUT/smoke"
mkdir -p "$EV" "$REL" "$SMOKE"
LOG="$EV/release-verify.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

: > "$EV/p10-lines.txt.tmp"
# The rec() names MUST carry the P10_ prefix: the orchestrator folds and counts
# verdict lines with `grep '^P10_[A-Z0-9_]+ (PASS|FAIL|SKIP)'`, and unprefixed
# names made every verdict of this stage invisible to it - run #7 shipped
# RELEASE_AAB FAIL next to "phase10_gate_fails=0" and a GREEN job, which is the
# worst failure mode this project knows (a gate that cannot fail the build).
rec() { echo "P10_$1 $2${3:+ :: $3}"; echo "P10_$1 $2${3:+ :: $3}" >> "$EV/p10-release-lines.txt"; log "$1 $2${3:+ :: $3}"; }
PASS=0; FAIL=0
p10() { case "$2" in 0) PASS=$((PASS+1)); rec "$1" PASS "$3" ;; *) FAIL=$((FAIL+1)); rec "$1" FAIL "$3" ;; esac; }

# Extract the EXPECTED identity from build.gradle.kts, anchored at the start of
# the line: the file carries a prose comment containing `// versionName = "<pinned
# OpenCode version>-phase10"`, and an unanchored grep found the comment first, so
# every --expect-version-name in CI compared against that placeholder (run #6).
# Anchored + head -1 sees only real assignments, which are never indented-away
# inside a comment.
VNAME=$(grep -E '^[[:space:]]*versionName = "' "$ROOT/app/build.gradle.kts" | head -1 | cut -d'"' -f2)
VCODE=$(grep -E '^[[:space:]]*versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | head -1 | grep -oE 'versionCode = [0-9]+' | grep -oE '[0-9]+')
APPID=$(grep -E '^[[:space:]]*applicationId = "' "$ROOT/app/build.gradle.kts" | head -1 | cut -d'"' -f2)
[ -n "$VNAME" ] && [ -n "$VCODE" ] && [ -n "$APPID" ] \
  || { echo "FATAL: could not extract versionName/versionCode/applicationId from app/build.gradle.kts" >&2; exit 2; }
DEBUG_APPID="$APPID.debug"
log "expected identity: applicationId=$APPID versionName=$VNAME versionCode=$VCODE"

log "=== gradle :app:assembleRelease :app:bundleRelease ==="
if ! ( cd "$ROOT" && timeout -k 30 3000 ./gradlew -p "$ROOT" \
        :app:assembleRelease :app:bundleRelease --no-daemon --stacktrace ) >> "$LOG" 2>&1; then
  p10 RELEASE 1 "assembleRelease/bundleRelease failed (see release-verify.log)"
  cp "$LOG" "$EV/p10-release-verify.log" 2>/dev/null || true
  exit 1
fi
# The Gradle block prints one signing line per build; keep it in the evidence.
grep -a "RELEASE SIGNING:" "$LOG" | tail -2 >> "$EV/release-listing.txt" 2>/dev/null || true

APK_SRC="$(ls "$ROOT/app/build/outputs/apk/release/"*.apk 2>/dev/null | head -1)"
AAB_SRC="$(ls "$ROOT/app/build/outputs/bundle/release/"*.aab 2>/dev/null | head -1)"
[ -n "$APK_SRC" ] || { p10 RELEASE 1 "no release APK produced"; exit 1; }
[ -n "$AAB_SRC" ] || { p10 RELEASE 1 "no AAB produced"; exit 1; }
APK="$REL/$(basename "$APK_SRC")"
AAB="$REL/$(basename "$AAB_SRC")"
cp "$APK_SRC" "$APK"; cp "$AAB_SRC" "$AAB"
log "apk=$(basename "$APK") ($(stat -c%s "$APK") bytes)"
log "aab=$(basename "$AAB") ($(stat -c%s "$AAB") bytes)"

# ---- APK: identity, payload, permissions, signature state --------------------
#
# MANAGE_EXTERNAL_STORAGE is REQUIRED, not incidental (Phase 10 continuation v3):
# the product promise is that project files are ordinary user-visible files in
# Documents/OpenCode, readable by any file manager while the agent works, with no
# export step. Nothing narrower reaches that folder by path, and the runtime needs
# a real path (see the manifest comment and docs/ARCHITECTURE.md). The check is
# two-sided on purpose: the legacy READ/WRITE_EXTERNAL_STORAGE permissions must NOT
# come back - they are dead on API 29+ and would mean the storage story regressed
# to the pre-scoped-storage model.
APK_REPORT="$EV/p10-release-apk-report.txt"
APK_ARGS=(--expect-package "$APPID" --expect-version-name "$VNAME" --expect-version-code "$VCODE"
          --expect-not-debuggable --expect-icon --expect-payload
          --expect-min-sdk 29 --expect-target-sdk 36
          --expect-native-abi arm64-v8a --expect-native-abi x86_64
          --expect-permission android.permission.INTERNET
          --expect-permission android.permission.FOREGROUND_SERVICE
          --expect-permission android.permission.FOREGROUND_SERVICE_SPECIAL_USE
          --expect-permission android.permission.POST_NOTIFICATIONS
          --expect-permission android.permission.MANAGE_EXTERNAL_STORAGE
          --expect-no-permission android.permission.READ_EXTERNAL_STORAGE
          --expect-no-permission android.permission.WRITE_EXTERNAL_STORAGE
          --expect-no-permission android.permission.ACCESS_FINE_LOCATION
          --json "$EV/p10-release-apk.json")
if python3 "$DIR/scripts/check-apk.py" "$APK" "${APK_ARGS[@]}" > "$APK_REPORT" 2>&1; then
  p10 RELEASE_APK 0 "$(grep -a '^MANIFEST ' "$APK_REPORT" | head -1 | cut -c1-220)"
else
  p10 RELEASE_APK 1 "check-apk findings: $(grep -a '^FINDING' "$APK_REPORT" | head -4 | tr '\n' '; ')"
fi
p10 RELEASE_APK_DETAIL 0 "$(grep -a '^CONTENTS ' "$APK_REPORT" | head -1 | tr -s ' ' | cut -c1-240)"

# ---- size budget: Play's 200 MB base-module cap -----------------------------
#
# The userland is growing - a shell and ~190 coreutils now, python and a real
# node under discussion - and 200 MB is a wall, not a target: over it, tools
# have to move into a dynamic feature module, which is a different product
# rather than a bigger number. So it gets measured on every release build.
#
# Conservative on purpose: Play's cap is on what ONE device downloads, and a
# device gets one ABI, so the universal APK (both ABIs) is an upper bound on
# every split it can produce. If this fits, every split fits. 180 MB warns so
# the wall is visible while there is still room to plan around it.
SIZE_DETAIL="$(python3 - "$APK_REPORT" <<'PY'
import re, sys
txt = open(sys.argv[1], errors="replace").read()
m = re.search(r"^ARTIFACT .* bytes=(\d+)", txt, re.M)
mb = int(m.group(1)) / 1048576.0 if m else -1.0
# non-greedy up to " payload=": the field itself contains spaces
# ("arm64-v8a(8 libs,126.4MB)"), so \S+ would stop at the first one.
c = re.search(r"native_abis=(.+?) payload=", txt)
nat = c.group(1).replace(" ", "") if c else "unknown"
print("apkMb=%.1f capMb=200 headroomMb=%.1f native=%s" % (mb, 200.0 - mb, nat))
PY
)"
SIZE_MB="$(printf '%s' "$SIZE_DETAIL" | sed -n 's/.*apkMb=\([0-9.]*\).*/\1/p')"
if [ -z "$SIZE_MB" ]; then
  p10 SIZE 1 "could not measure the release APK size (no ARTIFACT bytes= line); the cap is unchecked"
elif awk -v m="$SIZE_MB" 'BEGIN{exit !(m > 200)}'; then
  p10 SIZE 1 "OVER Play's 200 MB base-module cap: $SIZE_DETAIL"
elif awk -v m="$SIZE_MB" 'BEGIN{exit !(m > 180)}'; then
  p10 SIZE 0 "within the cap but close: $SIZE_DETAIL (plan the next tool against this number)"
else
  p10 SIZE 0 "$SIZE_DETAIL"
fi

# Signature state, asserted explicitly in BOTH directions: CI must never sign,
# and a release artifact must never be published unsigned *by the human step*.
# In CI the expected state is unsigned, so that is what is asserted here; the
# human's signed artifact is verified by sign-release-local.sh + check-apk.py
# --expect-signed (docs/RELEASE.md s4/s5).
# Written to its OWN report file (run #6 appended this second checker run into
# the first report with ">>", so the file held two concatenated reports, the
# "UNSIGNED" verdict's PASS/FAIL was decided by findings that belonged to the
# identity run, and the evidence was unreadable without diffing it).
UNSIGNED_REPORT="$EV/p10-release-apk-unsigned-report.txt"
if python3 "$DIR/scripts/check-apk.py" "$APK" --expect-unsigned > "$UNSIGNED_REPORT" 2>&1; then
  p10 UNSIGNED 0 "CI artifact is UNSIGNED, as required: signing is the human step on a machine that holds the key (docs/RELEASE.md); $(grep -a '^SIGNATURE ' "$UNSIGNED_REPORT" | head -1)"
else
  p10 UNSIGNED 1 "the CI release APK is SIGNED - CI must never receive signing material. Investigate before publishing anything; $(grep -a '^FINDING' "$UNSIGNED_REPORT" | head -2 | tr '\n' ';' | cut -c1-200)"
fi

# ---- installable TEST-ONLY release APKs --------------------------------------
#
# The instrumentable smoke APK is deliberately debuggable. D8 therefore emits
# debug-shaped dex and its universal file is ~27 MiB larger than the exact
# release APK. It is evidence, not the best daily-install artifact. Sign COPIES
# of the already-inspected, non-debuggable unsigned release with the committed
# PUBLIC test-only key so the owner gets the same release bytes in an installable
# package, while the real release output above remains unsigned and untouched.
# The key is the same one that signs smoke, so this installs as an update without
# wiping app data. An arm64-only copy drops the emulator ABI for normal phones;
# the universal copy remains the compatibility fallback.
APKSIGNER="$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -1)"
ZIPALIGN="$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/build-tools" -type f -name zipalign 2>/dev/null | sort -V | tail -1)"
TEST_STORE="$ROOT/app/testonly-debug-signing.p12"
TEST_ALIAS="androiddebugkey"
TEST_PASS="android" # public Android debug password; never release signing material
TEST_UNIVERSAL="$SMOKE/TEST-ONLY-debugkey-app-release-universal.apk"
TEST_ARM64="$SMOKE/TEST-ONLY-debugkey-app-release-arm64-v8a.apk"
TEST_WORK="$OUT/testonly-release-work"
rm -rf "$TEST_WORK" && mkdir -p "$TEST_WORK"

sign_testonly() { # $1 unsigned/aligned input, $2 output
  "$APKSIGNER" sign \
    --ks "$TEST_STORE" --ks-type PKCS12 --ks-key-alias "$TEST_ALIAS" \
    --ks-pass "pass:$TEST_PASS" --key-pass "pass:$TEST_PASS" \
    --v1-signing-enabled false --v2-signing-enabled true \
    --v3-signing-enabled true --v4-signing-enabled false \
    --out "$2" "$1"
}

TEST_SIGN_TOOLS_OK=1
[ -x "$APKSIGNER" ] && [ -x "$ZIPALIGN" ] && [ -f "$TEST_STORE" ] || TEST_SIGN_TOOLS_OK=0
if [ "$TEST_SIGN_TOOLS_OK" = "0" ]; then
  p10 TESTONLY_RELEASE 1 "cannot produce installable release copy: apksigner='$APKSIGNER' zipalign='$ZIPALIGN' key=$([ -f "$TEST_STORE" ] && echo present || echo missing)"
  p10 TESTONLY_ARM64 1 "not reached because test-only signing tools/key were unavailable"
else
  # Universal: zipalign a copy, then v2/v3-sign it. The source APK remains the
  # byte-identical unsigned artifact whose hash/report were recorded above.
  UNIVERSAL_ALIGNED="$TEST_WORK/release-universal-aligned.apk"
  UNIVERSAL_REPORT="$EV/p10-testonly-release-universal-report.txt"
  UNIVERSAL_OK=1
  "$ZIPALIGN" -P 16 -f 4 "$APK" "$UNIVERSAL_ALIGNED" >> "$LOG" 2>&1 || UNIVERSAL_OK=0
  [ "$UNIVERSAL_OK" = 1 ] && sign_testonly "$UNIVERSAL_ALIGNED" "$TEST_UNIVERSAL" >> "$LOG" 2>&1 || UNIVERSAL_OK=0
  UNIVERSAL_SIG="$EV/p10-testonly-release-universal-signature.txt"
  SMOKE_SIG="$EV/p10-smoke-signature-for-update-compare.txt"
  [ "$UNIVERSAL_OK" = 1 ] && "$APKSIGNER" verify --verbose --print-certs "$TEST_UNIVERSAL" > "$UNIVERSAL_SIG" 2>&1 || UNIVERSAL_OK=0
  [ "$UNIVERSAL_OK" = 1 ] && "$APKSIGNER" verify --verbose --print-certs "$SMOKE/TEST-ONLY-debugkey-app-smoke.apk" > "$SMOKE_SIG" 2>&1 || UNIVERSAL_OK=0
  UNIVERSAL_CERT=$(sed -n 's/^.*Signer: certificate SHA-256 digest: //p' "$UNIVERSAL_SIG" 2>/dev/null | head -1)
  SMOKE_CERT=$(sed -n 's/^.*Signer: certificate SHA-256 digest: //p' "$SMOKE_SIG" 2>/dev/null | head -1)
  [ -n "$UNIVERSAL_CERT" ] && [ "$UNIVERSAL_CERT" = "$SMOKE_CERT" ] || UNIVERSAL_OK=0
  if [ "$UNIVERSAL_OK" = 1 ] && python3 "$DIR/scripts/check-apk.py" "$TEST_UNIVERSAL" \
      --expect-package "$APPID" --expect-version-name "$VNAME" --expect-version-code "$VCODE" \
      --expect-signed --expect-not-debuggable --expect-icon --expect-payload \
      --expect-min-sdk 29 --expect-target-sdk 36 \
      --expect-native-abi arm64-v8a --expect-native-abi x86_64 \
      --json "$EV/p10-testonly-release-universal.json" > "$UNIVERSAL_REPORT" 2>&1; then
    UNIVERSAL_BYTES=$(stat -c%s "$TEST_UNIVERSAL")
    if [ "$UNIVERSAL_BYTES" -le $((200 * 1024 * 1024)) ]; then
      p10 TESTONLY_RELEASE 0 "installable non-debuggable universal APK bytes=$UNIVERSAL_BYTES MiB=$(awk -v b="$UNIVERSAL_BYTES" 'BEGIN{printf "%.1f",b/1048576}') signed=v2/v3 cert_matches_smoke=${UNIVERSAL_CERT:0:16}..."
    else
      p10 TESTONLY_RELEASE 1 "signed universal APK exceeds 200 MiB: bytes=$UNIVERSAL_BYTES"
    fi
  else
    p10 TESTONLY_RELEASE 1 "zipalign/sign/inspect failed: $(grep -a '^FINDING' "$UNIVERSAL_REPORT" 2>/dev/null | head -3 | tr '\n' ';' | cut -c1-220)"
  fi

  # arm64 phone convenience artifact: remove ONLY lib/x86_64 from the already-
  # inspected release, realign, sign, then prove arm64 exists and x86_64 does not.
  ARM_WORK="$TEST_WORK/release-arm64-work.apk"
  ARM_ALIGNED="$TEST_WORK/release-arm64-aligned.apk"
  ARM_REPORT="$EV/p10-testonly-release-arm64-report.txt"
  ARM_OK=1
  cp "$APK" "$ARM_WORK" || ARM_OK=0
  [ "$ARM_OK" = 1 ] && zip -q -d "$ARM_WORK" 'lib/x86_64/*' >> "$LOG" 2>&1 || ARM_OK=0
  [ "$ARM_OK" = 1 ] && "$ZIPALIGN" -P 16 -f 4 "$ARM_WORK" "$ARM_ALIGNED" >> "$LOG" 2>&1 || ARM_OK=0
  [ "$ARM_OK" = 1 ] && sign_testonly "$ARM_ALIGNED" "$TEST_ARM64" >> "$LOG" 2>&1 || ARM_OK=0
  ARM_SIG="$EV/p10-testonly-release-arm64-signature.txt"
  [ "$ARM_OK" = 1 ] && "$APKSIGNER" verify --verbose --print-certs "$TEST_ARM64" > "$ARM_SIG" 2>&1 || ARM_OK=0
  ARM_CERT=$(sed -n 's/^.*Signer: certificate SHA-256 digest: //p' "$ARM_SIG" 2>/dev/null | head -1)
  [ -n "$ARM_CERT" ] && [ "$ARM_CERT" = "$SMOKE_CERT" ] || ARM_OK=0
  if [ "$ARM_OK" = 1 ] && \
      ! unzip -Z1 "$TEST_ARM64" | grep -q '^lib/x86_64/' && \
      python3 "$DIR/scripts/check-apk.py" "$TEST_ARM64" \
        --expect-package "$APPID" --expect-version-name "$VNAME" --expect-version-code "$VCODE" \
        --expect-signed --expect-not-debuggable --expect-icon --expect-payload \
        --expect-min-sdk 29 --expect-target-sdk 36 --expect-native-abi arm64-v8a \
        --json "$EV/p10-testonly-release-arm64.json" > "$ARM_REPORT" 2>&1; then
    ARM_BYTES=$(stat -c%s "$TEST_ARM64")
    if [ "$ARM_BYTES" -le $((200 * 1024 * 1024)) ]; then
      p10 TESTONLY_ARM64 0 "installable arm64-only APK bytes=$ARM_BYTES MiB=$(awk -v b="$ARM_BYTES" 'BEGIN{printf "%.1f",b/1048576}') x86_64=absent signed=v2/v3 cert_matches_smoke=${ARM_CERT:0:16}..."
    else
      p10 TESTONLY_ARM64 1 "signed arm64-only APK exceeds 200 MiB: bytes=$ARM_BYTES"
    fi
  else
    p10 TESTONLY_ARM64 1 "arm64 trim/align/sign/inspect failed or x86_64 survived: $(grep -a '^FINDING' "$ARM_REPORT" 2>/dev/null | head -3 | tr '\n' ';' | cut -c1-220)"
  fi

  # The smoke artifact upload already includes this directory. Refresh its hash
  # list and append exact guidance after the two new files exist.
  ( cd "$SMOKE" && sha256sum ./*.apk > sha256.txt ) 2>/dev/null || true
  cat >> "$SMOKE/INSTALL-README.txt" <<EOF

SMALLER INSTALLABLE RELEASE-SHAPED TEST APKs
============================================
PREFERRED FOR A NORMAL ARM64 PHONE:
  TEST-ONLY-debugkey-app-release-arm64-v8a.apk

UNIVERSAL FALLBACK (ARM64 + X86_64):
  TEST-ONLY-debugkey-app-release-universal.apk

These are non-debuggable copies of the exact inspected release APK, signed with
the SAME committed public test-only key as the smoke APK. They update the smoke
install without wiping its app data. They are convenience/test builds, never
Play artifacts and never trust-bearing releases. The original smoke APK remains
larger because debuggable instrumentation emits debug-shaped dex; keep it only
when running the instrumentation suite.

THIS RUN:
  arm64-only: $(stat -c%s "$TEST_ARM64" 2>/dev/null || echo missing) bytes
  universal:  $(stat -c%s "$TEST_UNIVERSAL" 2>/dev/null || echo missing) bytes
EOF
fi
rm -rf "$TEST_WORK"

# ---- AAB: identity + payload (protobuf manifest; see check-apk.py) -----------
AAB_REPORT="$EV/p10-release-aab-report.txt"
if python3 "$DIR/scripts/check-apk.py" "$AAB" \
      --expect-package "$APPID" --expect-version-name "$VNAME" \
      --expect-native-abi arm64-v8a --expect-payload \
      --json "$EV/p10-release-aab.json" > "$AAB_REPORT" 2>&1; then
  p10 RELEASE_AAB 0 "$(grep -a '^CONTENTS ' "$AAB_REPORT" | head -1 | tr -s ' ' | cut -c1-240)"
else
  p10 RELEASE_AAB 1 "check-apk findings: $(grep -a '^FINDING' "$AAB_REPORT" | head -4 | tr '\n' '; ')"
fi

# ---- hashes: what the human will sign ----------------------------------------
( cd "$REL" && sha256sum ./*.apk ./*.aab 2>/dev/null ) > "$EV/release-sha256.txt" || true
( cd "$REL" && ls -la ) > "$EV/release-listing.txt" 2>&1 || true
log "sha256:"; cat "$EV/release-sha256.txt" | tee -a "$LOG"

# How to sign THIS apk on a phone, learned the hard way (owner report,
# 2026-09-27): this app targets SDK 36, and Android refuses to install any
# APK targeting 30+ whose signature is not scheme v2 or newer - and a
# v1-style signing pass rewrites the zip, which also breaks the 4-byte
# alignment Android 11+ requires of resources.arsc. Both mistakes end in the
# same dialog: "App not installed as package appears to be invalid". Two
# widely used phone tools fail exactly this way: Termux's `pkg install
# apksigner` (a 2016 v1-only zip-signer, NOT Google's apksigner) and any GUI
# signer left on its v1 default. The rule fits in one line - V1 OFF, V2 ON -
# because v2/v3 signing only appends a signing block and never touches the
# zip entries Gradle already aligned. Written next to the APK so the
# artifact carries its own instructions.
cp "$EV/release-sha256.txt" "$REL/sha256.txt" 2>/dev/null || true
cat > "$REL/HOW-TO-SIGN-ON-PHONE.txt" <<'EOF'
SIGNING app-release-unsigned.apk ON A PHONE (no computer, no adb)
=================================================================

THE ONE RULE: sign with scheme V2 (and optionally V3). V1 must be OFF.

Why: this app targets Android 16 (SDK 36). Android refuses to install any
APK targeting SDK 30+ without a v2+ signature, and a v1 signing pass
rewrites the zip and breaks the resources.arsc alignment Android 11+
demands. Either way the phone says "App not installed as package appears
to be invalid" - the APK in this folder is fine; the signing step did it.

KNOWN-BAD TOOLS (produce exactly that error):
  - Termux `pkg install apksigner`  -> third-party v1-only tool from 2016,
    NOT Google's apksigner. Never use it for this APK.
  - Any GUI signer with V1 enabled by default and no zipalign step.

KNOWN-GOOD PHONE PATH (GUI, no commands):
  MT Manager -> open app-release-unsigned.apk -> Sign:
    [ ] V1 signature   OFF
    [x] V2 signature   ON
    [x] V3 signature   ON (optional)
  Sign with YOUR OWN keystore and keep the keystore file + password:
  every future update must use the same key or Android forces a
  reinstall (uninstall = in-app sessions lost; Documents/OpenCode
  projects survive).

VERIFY THE DOWNLOAD FIRST (Termux):
  sha256sum app-release-unsigned.apk
  -> must equal the line in sha256.txt beside this file.

NO-SIGNING ALTERNATIVE: the CI artifact `opencode-android-smoke-test-only`
contains TEST-ONLY-debugkey-app-release-arm64-v8a.apk and
TEST-ONLY-debugkey-app-release-universal.apk. Both are already v2/v3 signed,
non-debuggable release-shaped test builds. They share a committed public test
key, so they are conveniences for testing, never trust-bearing releases. The
same folder's larger app-smoke APK is only needed for instrumentation.

The .aab file cannot be installed on a phone; it is Play Store input only.
EOF
# The exact bytes of THIS build, appended dynamically - the check that
# separates "signing did it" from "the download/extract did it" without adb.
{
  echo ""
  echo "WHAT YOU DOWNLOAD FROM GITHUB IS A ZIP: extract it first."
  echo "Sign ONLY app-release-unsigned.apk - NOT the .zip, NOT the .aab."
  echo ""
  echo "THIS BUILD'S EXACT BYTES (check before signing):"
  ( cd "$REL" && ls -l app-release-unsigned.apk | awk '{print "  size:   " $5 " bytes"}' )
  ( cd "$REL" && sha256sum app-release-unsigned.apk | awk '{print "  sha256: " $1}' )
} >> "$REL/HOW-TO-SIGN-ON-PHONE.txt"
log "wrote $REL/HOW-TO-SIGN-ON-PHONE.txt + sha256.txt (phone-signing rules + this build's exact bytes)"

echo
echo "P10_RELEASE_SUMMARY pass=$PASS fail=$FAIL"
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)
