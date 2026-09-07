#!/usr/bin/env bash
# 20-gates.sh - Phase 7 gate stage, run on the emulator the orchestrator booted.
# Three instrumented classes, in an order that matters:
#
#   WorkspaceIsolationGatesTest   the phase's own acceptance evidence, model-free
#                                 and deterministic: project lifecycle (W1), the
#                                 workspace boundary proven through OpenCode's own
#                                 file layer (W2 - listing is scoped to the
#                                 instance directory and an escaping read is
#                                 refused with "Path escapes the location"), and
#                                 the memory layer's inspect/edit/remove (W3).
#                                 These are P7_* verdicts written to
#                                 files/p7-verdicts.txt on the device.
#   ChatUiGatesTest               regression re-run of the deterministic Compose
#                                 UI gates; U3 (the permission ask reply flow) and
#                                 U7 (interactive-element accessibility audit)
#                                 are folded into the Phase 7 summary because
#                                 Phase 7 changed the Settings + Projects screens
#                                 and the ask surface those gates audit.
#   LiveChatUiGatesTest           a real turn through the UI; L2 is the Phase 6
#                                 carry-over (a real tool call) whose prompt was
#                                 strengthened this phase. Both are P6_* verdicts.
#
# Verdict discipline is Phase 5's: every gate prints "P7_<id> PASS|FAIL|SKIP ::"
# or "P6_<id> ..." from inside the app process, to stdout AND logcat AND an
# on-device file; this script counts the deduplicated union of those channels, so
# a gate that never ran cannot be mistaken for one that passed. Screenshots are
# pulled out of the app's own filesDir with run-as + base64.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
mkdir -p "$EV" "$EV/screenshots"
SUMMARY="$EV/GATES_SUMMARY.txt"
LOG="$EV/gates.log"
: > "$SUMMARY"; : > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
FILES="/data/data/$PKG/files"
EXT_VERDICT_P6="/storage/emulated/0/Android/data/$PKG/files/p6-verdicts.txt"
EXT_VERDICT_P7="/storage/emulated/0/Android/data/$PKG/files/p7-verdicts.txt"

APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"

PASS=0; FAIL=0; SKIP=0
: > "$EV/p7-ui-lines.txt"     # every P6_ / P7_ verdict line, deduplicated
: > "$EV/p7-model-lines.txt"  # the P6_MODEL_AVAILABLE marker, collected separately

rec() { log "$1 $2${3:+ :: $3}"; echo "$1 $2${3:+ :: $3}" >> "$EV/p7-ui-lines.txt"; }
p7() { # $1=id $2=rc(0 pass, 7 skip, else fail) $3=detail
  case "$2" in
    0) PASS=$((PASS+1)); rec "$1" PASS "$3" ;;
    7) SKIP=$((SKIP+1)); rec "$1" SKIP "$3" ;;
    *) FAIL=$((FAIL+1)); rec "$1" FAIL "$3" ;;
  esac
}

rash() { printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'; }

# Cat a verdict file the gates wrote on the device (primary channel). run-as needs
# a debuggable build, which androidTest is; the app-specific external copy is the
# fallback for the case where run-as is refused.
verdict_file_cat() { # $1 = verdict file name (p6-verdicts.txt / p7-verdicts.txt)
  rash "cat files/$1" 2>/dev/null | grep -aE '^P[67]_' || true
  adb shell "cat /storage/emulated/0/Android/data/$PKG/files/$1" 2>/dev/null | tr -d '\r' | grep -aE '^P[67]_' || true
}

verdict_file_clear() { # $1 = verdict file name
  rash "rm -f files/$1" >/dev/null 2>&1 || true
  adb shell "rm -f /storage/emulated/0/Android/data/$PKG/files/$1" >/dev/null 2>&1 || true
}

install_fresh() {
  log "uninstalling $PKG / $TEST_PKG so the gates start from a clean install"
  adb uninstall "$TEST_PKG" >/dev/null 2>&1 || true
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  sleep 2
  adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$LOG"
  adb install -r -g "$TAPK" 2>&1 | tail -2 | tee -a "$LOG"
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  sleep 2
}

pull_screenshots() {
  local names n count=0
  names=$(rash "ls -1 '$FILES/screenshots' 2>/dev/null")
  for n in $names; do
    case "$n" in *.png) ;; *) continue ;; esac
    if rash "base64 '$FILES/screenshots/$n' 2>/dev/null" | tr -d '\n\r ' | base64 -d > "$EV/screenshots/$n" 2>/dev/null \
       && [ -s "$EV/screenshots/$n" ]; then
      count=$((count+1))
    else
      rm -f "$EV/screenshots/$n" 2>/dev/null || true
    fi
  done
  log "screenshots pulled: $count"
}

# Run one instrumented class and fold its P6_/P7_ verdict lines into the summary.
# $1 = short name, $2 = fully qualified class, $3 = verdict file, $4 = timeout
run_class() {
  local name="$1"; local cls="$2"; local vfile="$3"; local tmo="$4"
  local rc=0
  local out="$EV/p7-${name}-instrument.log"
  log "=== am instrument $cls ==="
  verdict_file_clear "$vfile"
  # Grow logcat before clearing it: a 256 KiB default buffer does not survive one
  # live-model gate class. Best effort - unsupported devices keep their default.
  adb logcat -G 4M >/dev/null 2>&1 || true
  adb logcat -c >/dev/null 2>&1 || true
  timeout -k 30 "$tmo" adb shell am instrument -w -e class "$cls" "$RUNNER" > "$out" 2>&1 || rc=$?
  log "instrument rc=$rc ($name)"
  # Verdicts from all three channels, deduplicated: the file the gates wrote on the
  # device (primary, never truncated), then runner stdout and logcat as fallbacks.
  { verdict_file_cat "$vfile"
    grep -aoE 'P[67]_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*' "$out" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P[67]_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*'
  } | sed 's/[[:space:]]*$//' | sort -u >> "$EV/p7-ui-lines.txt" 2>/dev/null || true
  # Same three channels, for the model-availability marker.
  { verdict_file_cat "$vfile"
    grep -aoE 'P6_MODEL_AVAILABLE [01][^\r]*' "$out" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P6_MODEL_AVAILABLE [01][^\r]*'
  } | sed 's/[[:space:]]*$//' | sort -u >> "$EV/p7-model-lines.txt" 2>/dev/null || true
  # Keep the device-side file itself as evidence.
  verdict_file_cat "$vfile" > "$EV/p7-${name}-verdicts.txt" 2>/dev/null || true
  tail -40 "$out" >> "$LOG" 2>/dev/null || true
  if grep -aqE '^OK \([0-9]+ test' "$out" 2>/dev/null; then
    log "$name runner trailer: OK"
  else
    log "$name runner trailer: NOT-OK (rc=$rc)"
    grep -aE 'FAILURES|Tests run:|Error in|Exception|Process crashed|INSTRUMENTATION_' "$out" 2>/dev/null | tail -25 >> "$LOG" || true
  fi
  pull_screenshots
  return $rc
}

# Count one gate line prefix from the collected lines. A gate that printed FAIL
# anywhere is FAIL; SKIP only when nothing better was printed; absent = FAIL.
verdict_of() { # $1 = full line prefix (e.g. "P7_W2_WORKSPACE_ISOLATION")
  local id="$1" lines
  lines=$(grep -aE "^${id} (PASS|FAIL|SKIP)" "$EV/p7-ui-lines.txt" 2>/dev/null || true)
  if echo "$lines" | grep -aq "^$id FAIL"; then echo 1; return; fi
  if echo "$lines" | grep -aq "^$id PASS"; then echo 0; return; fi
  if echo "$lines" | grep -aq "^$id SKIP"; then echo 7; return; fi
  echo 1
}
detail_of() { # $1 = full line prefix
  grep -aE "^$1 (PASS|FAIL|SKIP)" "$EV/p7-ui-lines.txt" 2>/dev/null | tail -1 | sed -E "s/^$1 [A-Z]+(::)? *//" | cut -c1-220
}

[ -f "$APK" ] || { log "FATAL: no app APK at $APK"; p7 "P7-BUILD" 1 "no-app-apk"; }
[ -f "$TAPK" ] || { log "FATAL: no androidTest APK at $TAPK"; p7 "P7-BUILD" 1 "no-androidtest-apk"; }

# ---------------------------------------------------------------------------
log "=== stage A: workspace isolation + memory (model-free, deterministic) ==="
install_fresh
ISO_RC=0
run_class "isolation" "ai.opencode.android.projects.WorkspaceIsolationGatesTest" "p7-verdicts.txt" 3000 || ISO_RC=1
for g in "W1_PROJECT_LIFECYCLE" "W2_WORKSPACE_ISOLATION" "W3_MEMORY_INSPECTABLE_REMOVABLE"; do
  v=$(verdict_of "P7_$g"); p7 "P7_$g" "$v" "$(detail_of "P7_$g")"
done
[ "$ISO_RC" = 0 ] || log "note: the isolation instrument run itself returned rc=$ISO_RC"

# ---------------------------------------------------------------------------
log "=== stage B: deterministic UI regression (bottom sheet + accessibility) ==="
CHAT_RC=0
run_class "chat-ui" "ai.opencode.android.ui.ChatUiGatesTest" "p6-verdicts.txt" 1800 || CHAT_RC=1
for g in U3 U7; do
  v=$(verdict_of "P6_$g"); p7 "P6_$g" "$v" "$(detail_of "P6_$g")"
done
[ "$CHAT_RC" = 0 ] || log "note: the chat-UI instrument run itself returned rc=$CHAT_RC"

# ---------------------------------------------------------------------------
log "=== stage C: live turn through the UI (key-free default model) ==="
LIVE_RC=0
run_class "live-chat" "ai.opencode.android.ui.LiveChatUiGatesTest" "p6-verdicts.txt" 3600 || LIVE_RC=1
for g in L1 L2; do
  v=$(verdict_of "P6_$g"); p7 "P6_$g" "$v" "$(detail_of "P6_$g")"
done
[ "$LIVE_RC" = 0 ] || log "note: the live-chat instrument run itself returned rc=$LIVE_RC"

# ---------------------------------------------------------------------------
log "=== evidence pull ==="
rash "tail -c 60000 '$FILES/log/runtime.log' 2>&1" > "$EV/runtime.log" 2>&1 || true
rash 'for d in "$FILES/xdg/data/opencode/log" "$FILES/xdg/state/opencode/log"; do for f in "$d"/*.log; do [ -e "$f" ] || continue; echo "### $f"; tail -150 "$f"; done; done' \
  > "$EV/opencode-server.log" 2>&1 || true
if [ ! -s "$EV/opencode-server.log" ] || ! grep -q '^###' "$EV/opencode-server.log" 2>/dev/null; then
  rash "for d in '$FILES/xdg/data/opencode/log' '$FILES/xdg/state/opencode/log'; do for f in \$d/*.log; do [ -e \$f ] || continue; echo \"### \$f\"; tail -150 \$f; done; done" \
    > "$EV/opencode-server.log" 2>&1 || true
fi
timeout 90 adb logcat -d -s OpenCode:V OpenCode/gate:V OpenCode/ui:V > "$EV/logcat-OpenCode.txt" 2>&1 || true
timeout 90 adb exec-out screencap -p > "$EV/screenshots/99-final-host-screen.png" 2>/dev/null || true
[ -s "$EV/screenshots/99-final-host-screen.png" ] || rm -f "$EV/screenshots/99-final-host-screen.png" 2>/dev/null || true

SHOTS=$(ls -1 "$EV/screenshots" 2>/dev/null | grep -c '\.png$' || echo 0)
MODEL_AVAILABLE=0
if grep -aqE '^P6_MODEL_AVAILABLE 1' "$EV/p7-model-lines.txt" 2>/dev/null; then
  MODEL_AVAILABLE=1
elif grep -aqE 'P6_MODEL_AVAILABLE 1' "$EV"/p7-*-instrument.log 2>/dev/null; then
  MODEL_AVAILABLE=1
fi
grep -aE 'P6_MODEL_AVAILABLE' "$EV/p7-model-lines.txt" >> "$LOG" 2>/dev/null || true

cat >> "$SUMMARY" <<EOF
P7_SUMMARY $(date -u +%FT%TZ)
ui_gates_pass=$PASS ui_gates_fail=$FAIL ui_gates_skip=$SKIP
isolation_instrument_rc=$ISO_RC chat_ui_instrument_rc=$CHAT_RC live_chat_instrument_rc=$LIVE_RC
screenshots=$SHOTS
model_available=$MODEL_AVAILABLE
device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
android_sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
EOF
log "=== SUMMARY ==="; cat "$SUMMARY" | tee -a "$LOG"

RC=0
[ "$FAIL" -eq 0 ] || RC=1
exit "$RC"
