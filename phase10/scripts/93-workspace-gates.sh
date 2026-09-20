#!/usr/bin/env bash
# 93-workspace-gates.sh - the Phase 10 continuation stage: prove that the workspace
# is (a) still isolated and (b) no longer a black box.
#
# It runs the Phase 7 isolation class - W1 project lifecycle, W2 workspace
# isolation through OpenCode's own file layer, W3 memory inspectable/removable,
# and the new W4 storage-location + server-writes-there gate - and then hands the
# absolute path W4 reports to 92-workspace-visibility.sh, which checks the same
# directory from OUTSIDE the app with a non-root `adb shell`. Neither half is
# sufficient alone: the app can always say "my files are at /some/path", and a
# shell can always list a directory that the agent never writes to.
#
# Which build: the DEBUG build (applicationId ...opencode.debug), because the
# verdict channel is `run-as` + the app's own files, exactly as in Phase 7/8. The
# storage layout under test is the same code path in every variant; the verified
# artifact for the *layout* claim is the app's own W4 gate, which the signed-build
# device script re-runs on the signed release build.
#
# Phase 10 continuation v3: the default root is `Documents/OpenCode` on shared
# storage, which needs All files access. This script GRANTS it the way the user
# does - `appops set MANAGE_EXTERNAL_STORAGE allow`, no root - BEFORE the gates
# run, so W1-W4 and the external visibility check are exercised against the
# shipping default and not against the fallback. `P10_WS_NO_GRANT=1` runs the same
# gates without the grant on purpose: that pass proves the app states the fallback
# honestly instead of claiming visibility it does not have.
#
# Usage: bash phase10/scripts/93-workspace-gates.sh [--pkg PKG] [--out DIR]
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
PKG="${P10_WS_PKG:-io.github.mcyber12.opencode.debug}"
OUT="${P10_WS_OUT:-$DIR/out/workspace}"
while [ $# -gt 0 ]; do
  case "$1" in
    --pkg) PKG="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,25p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done
mkdir -p "$OUT"
LOG="$OUT/workspace-gates.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

TEST_PKG="$PKG.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
FILES="/data/data/$PKG/files"
VERDICTS="$OUT/p7-verdicts.txt"
: > "$VERDICTS"

PASS=0; FAIL=0; SKIP=0
rec() { echo "$1 $2${3:+ :: $3}" | tee -a "$LOG"; }
rd() { case "$2" in 0) PASS=$((PASS+1)); rec "$1" PASS "$3";; 7) SKIP=$((SKIP+1)); rec "$1" SKIP "$3";; *) FAIL=$((FAIL+1)); rec "$1" FAIL "$3";; esac; }

command -v adb >/dev/null 2>&1 || { echo "FATAL: adb not on PATH"; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "FATAL: no device visible to adb"; exit 2; }

APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { rd W-BUILD 1 "no debug APK built (run :app:assembleDebug first)"; echo "pass=$PASS fail=$FAIL"; exit 1; }
[ -n "$TAPK" ] || { rd W-BUILD 1 "no debug androidTest APK built"; echo "pass=$PASS fail=$FAIL"; exit 1; }

log "installing $PKG + its test APK (keeps data: the runtime the gates talk to is already up)"
adb install -r -g "$APK" >/dev/null 2>&1 || adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$LOG"
adb install -r -g "$TAPK" >/dev/null 2>&1 || adb install -r -g "$TAPK" 2>&1 | tail -2 | tee -a "$LOG"

# The verdict channels Phase 7 uses: the file inside the app (run-as, primary),
# its app-specific external twin, then the runner's stdout and logcat.
rash() { printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'; }
EXT_VERDICTS="/storage/emulated/0/Android/data/$PKG/files/p7-verdicts.txt"
verdict_lines() {
  { rash "cat files/p7-verdicts.txt" 2>/dev/null
    adb shell "cat $EXT_VERDICTS" 2>/dev/null | tr -d '\r'
    grep -aoE 'P7_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*' "$OUT/instrument.log" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P7_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*'
  } | sed 's/[[:space:]]*$//' | grep -aE '^P7_' | sort -u
}

rash "rm -f files/p7-verdicts.txt" >/dev/null 2>&1 || true
adb shell "rm -f $EXT_VERDICTS" >/dev/null 2>&1 || true
adb logcat -G 8M >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

# ---- the storage grant, exactly as the app asks the user for it --------------
# appops is the shell-side equivalent of the user tapping through
# Settings -> Special access -> All files access; a real user grant and this one
# produce the same platform state (`Environment.isExternalStorageManager()==true`),
# which is what the app reads. A release build would additionally need the
# MANAGE_EXTERNAL_STORAGE manifest declaration, which check-apk.py enforces.
if [ "${P10_WS_NO_GRANT:-0}" = "1" ]; then
  log "P10_WS_NO_GRANT=1: leaving All files access ungranted (this pass checks the FALLBACK is reported honestly)"
  adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE deny >/dev/null 2>&1 || true
else
  adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
fi
GRANT_LINE=$(adb shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE 2>&1 | tr -d '\r' | head -1)
log "appops MANAGE_EXTERNAL_STORAGE: ${GRANT_LINE:-<no output>}"
# The path the app will resolve, so the log names it even when the gates fail.
SHARED_PROBE=$(adb shell "ls -ld /storage/emulated/0/Documents/OpenCode 2>&1" | tr -d '\r' | head -1)
log "shared-storage default: ${SHARED_PROBE:-<absent>}"

log "=== am instrument ai.opencode.android.projects.WorkspaceIsolationGatesTest (W1-W4) ==="
ISO_RC=0
timeout -k 30 3600 adb shell am instrument -w \
  -e class ai.opencode.android.projects.WorkspaceIsolationGatesTest "$RUNNER" \
  > "$OUT/instrument.log" 2>&1 || ISO_RC=$?
log "instrument rc=$ISO_RC"
verdict_lines > "$VERDICTS"
log "--- verdict lines ---"
cat "$VERDICTS" | tee -a "$LOG"
grep -aE 'FAILURES|Tests run:|Error in|INSTRUMENTATION_' "$OUT/instrument.log" 2>/dev/null | tail -15 >> "$LOG" || true

emit() { # $1 = gate id suffix (e.g. W2_WORKSPACE_ISOLATION)
  local id="P7_$1" line
  line=$(grep -aE "^${id} " "$VERDICTS" | tail -1)
  case "$line" in
    *" PASS "*) rd "$1" 0 "$(printf '%s' "$line" | sed -E "s/^$id PASS(::)? *//" | cut -c1-400)" ;;
    *" SKIP "*) rd "$1" 7 "$(printf '%s' "$line" | sed -E "s/^$id SKIP(::)? *//" | cut -c1-400)" ;;
    *" FAIL "*) rd "$1" 1 "$(printf '%s' "$line" | sed -E "s/^$id FAIL(::)? *//" | cut -c1-400)" ;;
    *) rd "$1" 1 "gate produced no verdict line (it never ran - see instrument.log)" ;;
  esac
}

emit "W1_PROJECT_LIFECYCLE"
emit "W2_WORKSPACE_ISOLATION"
emit "W3_MEMORY_INSPECTABLE_REMOVABLE"
emit "W4_WORKSPACE_VISIBLE"

# ---- the external vantage point ---------------------------------------------
W4_LINE=$(grep -aE '^P7_W4_WORKSPACE_VISIBLE ' "$VERDICTS" | tail -1)
WS_PROJECT=$(printf '%s' "$W4_LINE" | grep -oE 'project=[A-Za-z0-9._-]+' | head -1 | cut -d= -f2)
# The app reports its resolved root as `wsRoot=...`; the external check verifies
# that path instead of assuming a layout. If the gate never ran, 92 probes.
WS_ROOT=$(printf '%s' "$W4_LINE" | grep -oE 'wsRoot=[^ ]+' | head -1 | cut -d= -f2-)
log "=== visibility from outside the app (non-root adb shell), project='${WS_PROJECT:-<none>}' root='${WS_ROOT:-<probe>}' ==="
VIS_ARGS=(--pkg "$PKG" --out "$OUT/visibility")
[ -n "${WS_PROJECT:-}" ] && VIS_ARGS+=(--project "$WS_PROJECT")
[ -n "${WS_ROOT:-}" ] && VIS_ARGS+=(--root "$WS_ROOT")
bash "$DIR/scripts/92-workspace-visibility.sh" "${VIS_ARGS[@]}" > "$OUT/visibility.log" 2>&1
VIS_RC=$?
grep -aE '^P10D_VISIBILITY_[A-Z_]+ (PASS|FAIL|SKIP)' "$OUT/visibility.log" 2>/dev/null | tee -a "$LOG" >/dev/null
# The visibility script prints its own verdict lines; they are counted here (its
# own process exit status is not enough - a stage with only PASS lines and a
# non-zero rc would hide a missing check).
while read -r id verdict rest; do
  [ -n "${id:-}" ] || continue
  case "$verdict" in
    PASS) PASS=$((PASS+1)) ;;
    SKIP) SKIP=$((SKIP+1)) ;;
    *) FAIL=$((FAIL+1)) ;;
  esac
done < <(grep -aE '^P10D_VISIBILITY_[A-Z_]+ (PASS|FAIL|SKIP)' "$OUT/visibility.log" 2>/dev/null)

# The W4 project directory is a gate fixture, not a user project: remove it so the
# next run starts clean (only when the visibility check has already read it).
if [ -n "${WS_PROJECT:-}" ]; then
  case "$WS_PROJECT" in
    w4-storage-*)
      # Remove the fixture from whichever root the app reported (and from both
      # fallbacks, in case a run moved it), so the next run starts clean.
      for r in "${WS_ROOT:-}" "/storage/emulated/0/Documents/OpenCode" "/storage/emulated/0/Android/data/$PKG/files/workspaces"; do
        [ -n "$r" ] || continue
        adb shell "rm -rf '$r/$WS_PROJECT'" >/dev/null 2>&1 || true
      done
      ;;
  esac
fi

cat >> "$OUT/GATES_SUMMARY.txt" <<EOF
P10-WORKSPACE $(date -u +%FT%TZ) instrument_rc=$ISO_RC visibility_rc=$VIS_RC
pass=$PASS fail=$FAIL skip=$SKIP
EOF
log "pass=$PASS fail=$FAIL skip=$SKIP"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
