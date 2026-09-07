#!/usr/bin/env bash
# 20-ui-gates.sh - Phase 6 product-UI gates, run on the emulator the orchestrator
# booted. Three instrumented classes, in an order that matters:
#
#   ChatUiGatesTest        deterministic Compose UI gates. Screens are rendered
#                          from fabricated server state (the screens are pure
#                          functions of state - a static check enforces that they
#                          never touch RuntimeManager/AppContainer), so no runtime,
#                          no model and no API key are involved. This is the class
#                          that proves lazy rendering, tool-call cards, permission
#                          prompts, degraded states and accessibility semantics.
#   FirstRunUiGatesTest    the first-run experience on a FRESHLY installed APK:
#                          welcome -> runtime extracts/starts by itself -> create
#                          a project -> chat, with a screenshot per stage. The app
#                          and its test package are uninstalled first so nothing is
#                          inherited from an earlier run.
#   LiveChatUiGatesTest    a real turn through the UI: type in the composer, send,
#                          watch the assistant text stream in, and a shell request
#                          produce an expandable tool card with real output. Uses
#                          the pinned build's key-free default model; it reports
#                          SKIP (never PASS) when no model can serve a turn.
#
# Verdict discipline is Phase 5's: every gate prints "P6_<id> PASS|FAIL|SKIP ::
# detail" from inside the app process, to stdout AND logcat; this script counts
# the deduplicated union of both channels, so a gate that never ran cannot be
# mistaken for one that passed. Screenshots are pulled out of the app's own
# filesDir with run-as + base64 (adb pull cannot read /data/data, and
# /sdcard/Android/data is off limits to the shell uid on API 30+).
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
mkdir -p "$EV" "$EV/screenshots"
SUMMARY="$EV/GATES_SUMMARY.txt"
LOG="$EV/ui-gates.log"
: > "$SUMMARY"; : > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
FILES="/data/data/$PKG/files"
# Verdict file the gates write inside the app's own storage (UiGateSupport.emit).
VERDICT_NAME="p6-verdicts.txt"
EXT_VERDICT="/storage/emulated/0/Android/data/$PKG/files/$VERDICT_NAME"

APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"

PASS=0; FAIL=0; SKIP=0
: > "$EV/p6-ui-lines.txt"
# The live class reports whether a model could actually serve a turn. That marker
# is NOT a PASS/FAIL/SKIP verdict line, so it is collected separately - grepping
# p6-ui-lines.txt for it could never match anything.
: > "$EV/p6-model-lines.txt"

rec() { log "P6-$1 $2${3:+ :: $3}"; echo "P6-$1 $2${3:+ :: $3}" >> "$EV/p6-ui-lines.txt"; }
p6() { # $1=id $2=rc(0 pass, 7 skip, else fail) $3=detail
  case "$2" in
    0) PASS=$((PASS+1)); rec "$1" PASS "$3" ;;
    7) SKIP=$((SKIP+1)); rec "$1" SKIP "$3" ;;
    *) FAIL=$((FAIL+1)); rec "$1" FAIL "$3" ;;
  esac
}

rash() { printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'; }

# Cat the verdict file the gates wrote on the device. This is the PRIMARY verdict
# channel: `println` from an instrumented test is redirected to logcat and never
# reaches `am instrument`'s result stream, and logcat is a ring buffer that a live
# runtime (bun, its MCP servers, a model turn) rotates - run 34134527274 recovered
# every verdict that way but with the tail cut off, e.g. "P6_F1 PASS :: fi".
# run-as needs a debuggable build, which androidTest is; the app-specific external
# copy is the fallback for the case where run-as is refused.
verdict_file_cat() {
  rash "cat files/$VERDICT_NAME" 2>/dev/null | grep -aE '^P6_' || true
  adb shell "cat $EXT_VERDICT" 2>/dev/null | tr -d '\r' | grep -aE '^P6_' || true
}

verdict_file_clear() {
  rash "rm -f files/$VERDICT_NAME" >/dev/null 2>&1 || true
  adb shell "rm -f $EXT_VERDICT" >/dev/null 2>&1 || true
}

install_fresh() {
  log "uninstalling $PKG / $TEST_PKG so the next gate really is a first run"
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
  ls -la "$EV/screenshots" >> "$LOG" 2>&1 || true
}

# Run one instrumented class and fold its P6_* verdict lines into the summary.
# $1 = short name (evidence file suffix), $2 = fully qualified class, $3 = timeout
run_class() {
  # One assignment per line, deliberately. bash expands EVERY word of a `local`
  # (or plain multi-assignment) command before performing ANY of the assignments,
  # so `local name="$1" ... out="$EV/p6-${name}-instrument.log"` read `name`
  # before it existed and `set -u` aborted the whole gate stage: run 34115663777
  # installed both APKs, then died here, so p6-ui-lines.txt was empty and not one
  # P6 verdict existed.
  local name="$1" cls="$2" tmo="$3" rc=0
  local out="$EV/p6-${name}-instrument.log"
  log "=== am instrument $cls ==="
  verdict_file_clear
  # Grow logcat before clearing it: a 256 KiB default buffer does not survive one
  # live-model gate class. Best effort - unsupported devices keep their default.
  adb logcat -G 4M >/dev/null 2>&1 || true
  adb logcat -c >/dev/null 2>&1 || true
  timeout -k 30 "$tmo" adb shell am instrument -w -e class "$cls" "$RUNNER" > "$out" 2>&1 || rc=$?
  log "instrument rc=$rc ($name)"
  # Verdicts from all three channels, deduplicated: the file the gates wrote on the
  # device (primary, never truncated), then runner stdout and logcat as fallbacks.
  { verdict_file_cat
    grep -aoE 'P6_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*' "$out" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P6_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*'
  } | sed 's/[[:space:]]*$//' | sort -u >> "$EV/p6-ui-lines.txt" 2>/dev/null || true
  # Same three channels, for the model-availability marker.
  { verdict_file_cat
    grep -aoE 'P6_MODEL_AVAILABLE [01][^\r]*' "$out" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P6_MODEL_AVAILABLE [01][^\r]*'
  } | sed 's/[[:space:]]*$//' | sort -u >> "$EV/p6-model-lines.txt" 2>/dev/null || true
  # Keep the device-side file itself as evidence.
  verdict_file_cat > "$EV/p6-${name}-verdicts.txt" 2>/dev/null || true
  tail -40 "$out" >> "$LOG" 2>/dev/null || true
  # The runner's own trailer: "OK (n tests)" or "Tests run: n,  Failures: m".
  if grep -aqE '^OK \([0-9]+ test' "$out" 2>/dev/null; then
    log "$name runner trailer: OK"
  else
    log "$name runner trailer: NOT-OK (rc=$rc)"
    grep -aE 'FAILURES|Tests run:|Error in|Exception|Process crashed|INSTRUMENTATION_' "$out" 2>/dev/null | tail -25 >> "$LOG" || true
  fi
  pull_screenshots
  return $rc
}

# Count one gate id from the collected lines. A gate that printed FAIL anywhere is
# FAIL; SKIP only when nothing better was printed; absent = FAIL (it never ran).
verdict_of() { # $1 = gate id
  local id="$1" lines
  lines=$(grep -aE "^${id} (PASS|FAIL|SKIP)" "$EV/p6-ui-lines.txt" 2>/dev/null || true)
  if echo "$lines" | grep -aq "^$id FAIL"; then echo 1; return; fi
  if echo "$lines" | grep -aq "^$id PASS"; then echo 0; return; fi
  if echo "$lines" | grep -aq "^$id SKIP"; then echo 7; return; fi
  echo 1
}
detail_of() { # $1 = gate id
  grep -aE "^$1 (PASS|FAIL|SKIP)" "$EV/p6-ui-lines.txt" 2>/dev/null | tail -1 | sed -E "s/^$1 [A-Z]+(::)? *//" | cut -c1-220
}

[ -f "$APK" ] || { log "FATAL: no app APK at $APK"; p6 BUILD 1 "no-app-apk"; }
[ -f "$TAPK" ] || { log "FATAL: no androidTest APK at $TAPK"; p6 BUILD 1 "no-androidtest-apk"; }

# ---------------------------------------------------------------------------
log "=== stage A: deterministic chat UI gates (no runtime, no model, no key) ==="
install_fresh
CHAT_RC=0
run_class "chat-ui" "ai.opencode.android.ui.ChatUiGatesTest" 1800 || CHAT_RC=1
for g in U1 U2 U3 U4 U5 U6 U7 U8; do
  v=$(verdict_of "P6_$g"); p6 "$g" "$v" "$(detail_of "P6_$g")"
done
[ "$CHAT_RC" = 0 ] || log "note: the chat-UI instrument run itself returned rc=$CHAT_RC"

# ---------------------------------------------------------------------------
log "=== stage B: first-run experience on a freshly installed APK ==="
install_fresh
FR_RC=0
run_class "first-run" "ai.opencode.android.ui.FirstRunUiGatesTest" 3000 || FR_RC=1
for g in F1 F2 F3 F4; do
  v=$(verdict_of "P6_$g"); p6 "$g" "$v" "$(detail_of "P6_$g")"
done
[ "$FR_RC" = 0 ] || log "note: the first-run instrument run itself returned rc=$FR_RC"

# ---------------------------------------------------------------------------
log "=== stage C: live turn through the UI (key-free default model) ==="
LIVE_RC=0
run_class "live-chat" "ai.opencode.android.ui.LiveChatUiGatesTest" 3000 || LIVE_RC=1
for g in L1 L2; do
  v=$(verdict_of "P6_$g"); p6 "$g" "$v" "$(detail_of "P6_$g")"
done
[ "$LIVE_RC" = 0 ] || log "note: the live-chat instrument run itself returned rc=$LIVE_RC"

# ---------------------------------------------------------------------------
log "=== evidence pull ==="
rash "tail -c 60000 '$FILES/log/runtime.log' 2>&1" > "$EV/runtime.log" 2>&1 || true
rash 'for d in "$FILES/xdg/data/opencode/log" "$FILES/xdg/state/opencode/log"; do for f in "$d"/*.log; do [ -e "$f" ] || continue; echo "### $f"; tail -150 "$f"; done; done' \
  > "$EV/opencode-server.log" 2>&1 || true
# The runtime log paths are absolute inside the app sandbox; expand them for the
# inner loop (run-as sh does not expand $FILES inside single quotes).
if [ ! -s "$EV/opencode-server.log" ] || ! grep -q '^###' "$EV/opencode-server.log" 2>/dev/null; then
  rash "for d in '$FILES/xdg/data/opencode/log' '$FILES/xdg/state/opencode/log'; do for f in \$d/*.log; do [ -e \$f ] || continue; echo \"### \$f\"; tail -150 \$f; done; done" \
    > "$EV/opencode-server.log" 2>&1 || true
fi
timeout 90 adb logcat -d -s OpenCode:V OpenCode/gate:V OpenCode/ui:V > "$EV/logcat-OpenCode.txt" 2>&1 || true
timeout 90 adb exec-out screencap -p > "$EV/screenshots/99-final-host-screen.png" 2>/dev/null || true
[ -s "$EV/screenshots/99-final-host-screen.png" ] || rm -f "$EV/screenshots/99-final-host-screen.png" 2>/dev/null || true

SHOTS=$(ls -1 "$EV/screenshots" 2>/dev/null | grep -c '\.png$' || echo 0)
MODEL_AVAILABLE=0
if grep -aqE '^P6_MODEL_AVAILABLE 1' "$EV/p6-model-lines.txt" 2>/dev/null; then
  MODEL_AVAILABLE=1
elif grep -aqE 'P6_MODEL_AVAILABLE 1' "$EV"/p6-*-instrument.log 2>/dev/null; then
  MODEL_AVAILABLE=1
fi
grep -aE 'P6_MODEL_AVAILABLE' "$EV/p6-model-lines.txt" >> "$LOG" 2>/dev/null || true

cat >> "$SUMMARY" <<EOF
P6_SUMMARY $(date -u +%FT%TZ)
ui_gates_pass=$PASS ui_gates_fail=$FAIL ui_gates_skip=$SKIP
chat_ui_instrument_rc=$CHAT_RC first_run_instrument_rc=$FR_RC live_chat_instrument_rc=$LIVE_RC
screenshots=$SHOTS
model_available=$MODEL_AVAILABLE
device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
android_sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
EOF
log "=== SUMMARY ==="; cat "$SUMMARY" | tee -a "$LOG"

RC=0
[ "$FAIL" -eq 0 ] || RC=1
[ "$SHOTS" -ge 1 ] || { log "no screenshots were captured - the first-run flow cannot be shown"; RC=1; }
exit "$RC"
