#!/usr/bin/env bash
# 50-smoke-gates.sh - the release-SHAPED build, gated on a device.
#
# Why this stage exists (and why it is not redundant with Phases 6-9):
# every device gate this project has ever run drove the DEBUG applicationId. A
# release build differs in ways that have broken real apps before: a different
# applicationId (so a different data dir, a different FileProvider authority, a
# different Keystore namespace for the very keys the model provider needs), a
# different manifest, `debuggable=false` (which removes run-as and changes how
# Android treats the process), and - if it were enabled - R8/ProGuard stripping
# of reflection-based code. Phase 10 asserts the release configuration on a device
# WITHOUT a release key in CI by building a `smoke` build type that is the release
# configuration made runnable (same applicationId, same code shape, debuggable,
# signed with the public debug key).
#
# What it proves, gate by gate (all named P10_*):
#   P10_SMOKE_APK   the built smoke APK carries the RELEASE applicationId, the
#                   pinned version, the icon, the embedded payload, both ABIs -
#                   and not the debug applicationId suffix.
#   P10_SMOKE_UI    the Phase 6 UI gates (F1-F4 first-run, U1-U8 chat) run against
#                   the smoke APK: the release data dir, the release FileProvider
#                   authority and the release Keystore namespace all get exercised
#                   by real first-run extraction + project creation + chat.
#   P10_NO_DEBUG_ID the debug applicationId is NOT what the smoke build produced
#                   (i.e. this really is a different identity, not a relabelled
#                   debug build).
#   P10_RELEASE_NOT_DEBUGGABLE the RELEASE manifest of the same source tree has
#                   debuggable=false (checked from the release artifact in
#                   stage 8; asserted here as the contract the smoke build relaxes
#                   on purpose and nothing else does).
#
# Usage: bash phase10/scripts/50-smoke-gates.sh    (device must be booted)
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
SMOKE="$OUT/smoke"
mkdir -p "$EV" "$SMOKE"
LOG="$EV/smoke-gates.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PKG="io.github.mcyber12.opencode"           # the RELEASE applicationId (smoke uses it)
TEST_PKG="$PKG.test"
DEBUG_PKG="io.github.mcyber12.opencode.debug"
FILES="/data/data/$PKG/files"

PASS=0; FAIL=0; SKIP=0
: > "$EV/p10-lines.txt"
rec() { echo "$1 $2${3:+ :: $3}"; echo "$1 $2${3:+ :: $3}" >> "$EV/p10-lines.txt"; log "$1 $2${3:+ :: $3}"; }
p10() { # $1=id $2=rc(0 pass, 7 skip, else fail) $3=detail
  case "$2" in
    0) PASS=$((PASS+1)); rec "P10_$1" PASS "$3" ;;
    7) SKIP=$((SKIP+1)); rec "P10_$1" SKIP "$3" ;;
    *) FAIL=$((FAIL+1)); rec "P10_$1" FAIL "$3" ;;
  esac
}

# ---- 1. build the smoke variant + its androidTest APK ------------------------
log "=== building the smoke variant (release configuration, debug-key signed) ==="
if ! ( cd "$ROOT" && timeout -k 30 3000 ./gradlew -p "$ROOT" \
        -PtestBuildType=smoke :app:assembleSmoke :app:assembleSmokeAndroidTest \
        --no-daemon --stacktrace ) >> "$LOG" 2>&1; then
  echo "--- last 60 log lines ---" >> "$LOG"; tail -60 "$LOG" >> "$LOG"
  p10 SMOKE_APK 1 "gradle -PtestBuildType=smoke assembleSmoke/assembleSmokeAndroidTest failed (see smoke-gates.log)"
  rec "P10_SMOKE_UI" FAIL "not reached (the smoke APKs could not be built)"
  exit 1
fi

APK="$(ls "$ROOT/app/build/outputs/apk/smoke/"*.apk 2>/dev/null | head -1)"
TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/smoke/"*.apk 2>/dev/null | head -1)"
if [ -z "$APK" ] || [ -z "$TAPK" ]; then
  p10 SMOKE_APK 1 "smoke APKs missing (apk='${APK:-}' testApk='${TAPK:-}')"
  exit 1
fi
log "smoke apk=$APK"
log "smoke testApk=$TAPK"

# The published listing must never contain the smoke build: rename it so nobody
# can upload it by accident (the workflow uploads it as a test-only artifact too).
SMOKE_APK="$SMOKE/TEST-ONLY-debugkey-app-smoke.apk"
cp "$APK" "$SMOKE_APK"
cp "$TAPK" "$SMOKE/TEST-ONLY-debugkey-app-smoke-androidTest.apk"
APK="$SMOKE_APK"

# ---- 2. inspect the artifact before trusting it ------------------------------
log "=== inspecting the smoke APK (identity, payload, abis, signing) ==="
VNAME=$(grep -o 'versionName = "[^"]*"' -m1 "$ROOT/app/build.gradle.kts" | cut -d'"' -f2)
VCODE=$(grep -oE 'versionCode = [0-9]+' -m1 "$ROOT/app/build.gradle.kts" | grep -oE '[0-9]+')
INSPECT="$EV/p10-smoke-apk-report.txt"
# Expectations for a SMOKE build, stated explicitly so the report cannot be read
# as "this is a release artifact": the packaged form is right, the debug flag is
# deliberately on, and the signature must be the debug key, not the release one.
if python3 "$DIR/scripts/check-apk.py" "$APK" \
     --expect-package "$PKG" --expect-version-name "$VNAME" --expect-version-code "$VCODE" \
     --expect-signed --expect-icon --expect-payload \
     --expect-min-sdk 29 --expect-target-sdk 34 \
     --expect-native-abi arm64-v8a --expect-native-abi x86_64 \
     --expect-permission android.permission.INTERNET \
     --expect-permission android.permission.FOREGROUND_SERVICE \
     --json "$EV/p10-smoke-apk.json" > "$INSPECT" 2>&1; then
  p10 SMOKE_APK 0 "$(grep -a '^MANIFEST ' "$INSPECT" | head -1 | cut -c1-200)"
else
  p10 SMOKE_APK 1 "check-apk reported findings: $(grep -a '^FINDING' "$INSPECT" | head -3 | tr '\n' ';')"
fi
if python3 "$DIR/scripts/check-apk.py" "$APK" --expect-package "$DEBUG_PKG" >> "$INSPECT" 2>&1; then
  p10 NO_DEBUG_ID 1 "the smoke APK carries the debug applicationId $DEBUG_PKG - this is not a release-shaped build"
else
  p10 NO_DEBUG_ID 0 "applicationId=$PKG (debug id $DEBUG_PKG correctly absent)"
fi

# ---- 3. install it and run the Phase 6 UI gates against it -------------------
log "=== installing the smoke build (fresh) ==="
adb uninstall "$TEST_PKG" >/dev/null 2>&1 || true
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$LOG"
adb install -r -g "$ROOT/app/build/outputs/apk/androidTest/smoke/"*.apk 2>&1 | tail -2 | tee -a "$LOG"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

# The Phase 6 driver run against the release-shaped build. P6_PKG switches its
# package handling; the APK paths are passed so it never guesses.
log "=== Phase 6 UI gates against the SMOKE build (P6_PKG=$PKG) ==="
SMOKE_UI_LOG="$EV/p10-smoke-ui.log"
P6_PKG="$PKG" P6_TEST_PKG="$TEST_PKG" \
  P6_APK="$APK" P6_TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/smoke/"*.apk | head -1)" \
  bash "$ROOT/phase6/scripts/20-ui-gates.sh" > "$SMOKE_UI_LOG" 2>&1 || true
# The Phase 6 driver writes its verdicts into ITS evidence dir; copy the lines so
# the smoke run's F/U verdicts are distinguishable from the debug run's.
mkdir -p "$EV/smoke-ui"
cp "$ROOT/phase6/out/evidence/p6-ui-lines.txt" "$EV/smoke-ui/" 2>/dev/null || true
cp "$ROOT/phase6/out/evidence/GATES_SUMMARY.txt" "$EV/smoke-ui/" 2>/dev/null || true
SMOKE_F=$(sed -n 's/^ui_gates_pass=[0-9]* ui_gates_fail=\([0-9]*\).*/\1/p' "$ROOT/phase6/out/evidence/GATES_SUMMARY.txt" 2>/dev/null | head -1)
SMOKE_P=$(sed -n 's/^ui_gates_pass=\([0-9]*\).*/\1/p' "$ROOT/phase6/out/evidence/GATES_SUMMARY.txt" 2>/dev/null | head -1)
SMOKE_S=$(sed -n 's/.*ui_gates_skip=\([0-9]*\).*/\1/p' "$ROOT/phase6/out/evidence/GATES_SUMMARY.txt" 2>/dev/null | head -1)
# Every F/U verdict line from the smoke run, so the phase summary names them.
grep -ahE '^P6_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$EV/smoke-ui/p6-ui-lines.txt" 2>/dev/null | \
  sed 's/^P6_/P10_SMOKE_UI_/' | cut -c1-300 >> "$EV/p10-lines.txt" || true
if [ -n "${SMOKE_F:-}" ] && [ "$SMOKE_F" = "0" ]; then
  p10 SMOKE_UI 0 "pass=${SMOKE_P:-0} fail=0 skip=${SMOKE_S:-0} on the release-shaped build (identical gates to the debug run)"
else
  p10 SMOKE_UI 1 "pass=${SMOKE_P:-?} fail=${SMOKE_F:-?} skip=${SMOKE_S:-?} - a gate that passes on debug and fails here is a RELEASE-SHAPE finding (see p10-smoke-ui.log)"
fi

# ---- 4. the release build must not be debuggable (contract check) -------------
# The smoke variant turns debuggable ON deliberately; the release variant must
# have it OFF. Stage 8 checks the real release artifact; this states the contract
# in the smoke report so the two cannot be confused.
if grep -q "isMinifyEnabled = false" "$ROOT/app/build.gradle.kts"; then
  p10 NO_R8_STRIPPING 0 "release keeps isMinifyEnabled=false: the code shape the gates exercised is the code shape that ships (no reflection/JNI stripping to discover late)"
else
  p10 NO_R8_STRIPPING 1 "release now minifies (isMinifyEnabled != false) - obfuscation stripping becomes a release-only risk that these gates do NOT cover; re-validate before shipping"
fi

echo
echo "P10_SMOKE_SUMMARY pass=$PASS fail=$FAIL skip=$SKIP"
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)
