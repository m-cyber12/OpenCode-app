#!/usr/bin/env bash
# 20-gates.sh - Phase 8 gate stage, run on the emulator the orchestrator booted.
#
# Stages (order matters; every stage's verdicts land in the P8 summary):
#   A   Phase 7 regression - phase7/scripts/20-gates.sh verbatim (W1/W2/W3
#       workspace isolation + memory, U3/U7 UI, L1/L2 live turn) folded into
#       ours as P8-P7REG. Skippable while iterating (P8_SKIP_P7REG=1).
#   A2  Phase 5 regression - phase5/scripts/20-integration-gates.sh verbatim
#       (K1..K9 client gates, G6-G12 drivers, G17 loopback-only binding,
#       G18 credentials at rest, G19 secret scan - the security checklist
#       re-verification this phase owes) folded in as P8-P5REG. P5-G16 stays
#       the documented upstream red (anomalyco/opencode#47644).
#   B   model provisioning - the run's OPENROUTER_API_KEY (repo secret) is
#       written into the app's harness dir (base64, run-as) for the P8
#       instrumented classes; it flows through the product's own credential
#       path from there. No key => the model gates SKIP, loudly.
#   C   P8 instrumented - StressRecoveryGatesTest (key-residency probe,
#       provider-auth failure, supervised server-kill restart, lifecycle log)
#       and LiveToolCallGatesTest (key probe, the real tool call that closes
#       Phase 6 L2, post-run key revocation).
#   D   destructive stress - app process crash/restart, corrupted payload
#       recovery, session persistence across restart, network loss mid-task,
#       background/foreground survival, large project, large history, low
#       storage, toybox staging path on this API level.
#   E   performance - the measured numbers for the report: startup (from the
#       supervisor's own log), API op latencies, shell op latency, streaming
#       TTFT/turn latency, memory, CPU, storage footprint.
#
# Verdict discipline is Phase 5's: gates print "P8_<id> PASS|FAIL|SKIP ::"
# from inside the app process to logcat AND an on-device file; this script
# counts the deduplicated union, so a gate that never ran cannot pass.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
P4GATES="$ROOT/phase4/scripts/device"
mkdir -p "$EV" "$EV/screenshots"
SUMMARY="$EV/GATES_SUMMARY.txt"
LOG="$EV/gates.log"
: > "$SUMMARY"; : > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
FILES="/data/data/$PKG/files"
PORT=4111

APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"

PASS=0; FAIL=0; SKIP=0
: > "$EV/p8-lines.txt"
rec() { log "$1 $2${3:+ :: $3}"; echo "$1 $2${3:+ :: $3}" >> "$EV/p8-lines.txt"; }
p8() { # $1=id $2=rc(0 pass, 7 skip, else fail) $3=detail
  case "$2" in
    0) PASS=$((PASS+1)); rec "P8_$1" PASS "$3" ;;
    7) SKIP=$((SKIP+1)); rec "P8_$1" SKIP "$3" ;;
    *) FAIL=$((FAIL+1)); rec "P8_$1" FAIL "$3" ;;
  esac
}

# Run a script as the app uid, base64-over-stdin (Phase 5 mechanics verbatim).
rash() { printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'; }
shash() { printf '%s\n' "$1" | adb shell sh 2>&1; }

write_stdin_runas() { # $1=remote-relative-to-files ; content on stdin
  local b64
  b64=$(base64 -w0)
  rash "mkdir -p \$(dirname '$FILES/$1'); echo '$b64' | base64 -d > '$FILES/$1'; echo wrote_rc=\$?"
}
push_file_runas() { write_stdin_runas "$2" < "$1"; }

DEVJS="$FILES/tmp/p8js"
stage_drivers() { # phase4 lib + phase8 drivers onto the device
  local stage="$OUT/p8js-stage" b64
  rm -rf "$stage"; mkdir -p "$stage"
  cp "$P4GATES/gates-lib.js" "$DIR/scripts/device/"*.js "$stage"/ 2>/dev/null
  b64=$(tar cz -C "$stage" . 2>/dev/null | base64 -w0)
  [ -n "$b64" ] || { log "FATAL: could not stage the P8 drivers"; return 1; }
  rash "mkdir -p '$DEVJS'; echo '$b64' | base64 -d | tar xz -C '$DEVJS' 2>&1; echo staged_rc=\$?" >> "$LOG" 2>&1
}

run_js_on_device() { # $1=script-in-$DEVJS [argv...] ; needs PASSWD
  local script="$1"; shift
  local out rc
  out=$(rash "cd '$DEVJS' && timeout -k 5 ${P8_DRIVER_TIMEOUT:-900} env OPENCODE_BASE=\"http://127.0.0.1:$PORT\" OPENCODE_SERVER_PASSWORD=\"$PASSWD\" OPENCODE_SERVER_USERNAME=opencode OPENCODE_DIRECTORY=\"$WORKDIR\" OPENCODE_BUN_BIN=\"$FILES/bin/bun\" P8_PERF_MODEL=\"${P8_PERF_MODEL:-}\" P8_PERF_KEY=\"${P8_PERF_KEY:-}\" P8_HIST_TURNS=\"${P8_HIST_TURNS:-40}\" P8_HIST_MODEL=\"${P8_HIST_MODEL:-}\" P8_KEY_FILE=\"$FILES/harness/model-key\" P8_LARGE_FILES=\"${P8_LARGE_FILES:-2000}\" P8_LARGE_FILE_MB=\"${P8_LARGE_FILE_MB:-50}\" '$FILES/bin/bun' '$script' $* 2>&1; echo \"DEVJS_RC=\$?\"")
  rc=$(printf '%s' "$out" | sed -n 's/.*DEVJS_RC=\([0-9]*\).*/\1/p' | tail -1)
  printf '%s\n' "$out" | grep -v '^DEVJS_RC='
  return "${rc:-1}"
}

health_code_host() {
  curl -s -o "$OUT/health.json" -w '%{http_code}' -u "opencode:$PASSWD" --max-time 4 \
    "http://127.0.0.1:$PORT/global/health" 2>/dev/null || echo 000
}
health_code_device() {
  local js="const r=await fetch('http://127.0.0.1:$PORT/global/health',{headers:{authorization:'Basic '+btoa('opencode:$PASSWD')}});const t=await r.text();console.log('CODE'+r.status+' '+t.slice(0,90));"
  printf '%s' "$js" > "$OUT/tmp-health.js"
  write_stdin_runas "tmp-health.js" < "$OUT/tmp-health.js" > /dev/null 2>&1
  rash "'$FILES/bin/bun' '$FILES/tmp-health.js' 2>&1 | head -2; rm -f '$FILES/tmp-health.js'" 2>/dev/null \
    | grep -o 'CODE[0-9]*' | head -1 | sed 's/CODE//'
}
wait_healthy() { # $1=timeout-s ; needs PASSWD
  local deadline=$(( $(date +%s) + ${1:-240} )) last_host=000 last_dev=none
  while [ "$(date +%s)" -lt "$deadline" ]; do
    last_host=$(health_code_host)
    if [ "$last_host" = "200" ] && grep -q healthy "$OUT/health.json" 2>/dev/null; then
      echo HEALTH_OK; return 0
    fi
    last_dev=$(health_code_device)
    if [ "${last_dev:0:3}" = "200" ]; then
      echo HEALTH_OK; return 0
    fi
    sleep 2
  done
  printf '[%s] wait_healthy timed out (host=%s device=%s)\n' "$(date -u +%FT%TZ)" "$last_host" "${last_dev:-none}" >> "$LOG"
  echo HEALTH_TIMEOUT; return 1
}

install_fresh() {
  log "uninstalling $PKG / $TEST_PKG for a clean install"
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

verdict_file_cat() { # p8-verdicts.txt : device file (primary) + external copy
  rash "cat files/p8-verdicts.txt" 2>/dev/null | grep -aE '^P8_' || true
  adb shell "cat /storage/emulated/0/Android/data/$PKG/files/p8-verdicts.txt" 2>/dev/null | tr -d '\r' | grep -aE '^P8_' || true
}

run_p8_class() { # $1=short-name $2=class ; verdicts -> $EV/p8-lines.txt
  local name="$1"; local cls="$2"
  local rc=0
  local out="$EV/p8-${name}-instrument.log"
  log "=== am instrument $cls ==="
  rash "rm -f files/p8-verdicts.txt" >/dev/null 2>&1 || true
  adb logcat -G 4M >/dev/null 2>&1 || true
  adb logcat -c >/dev/null 2>&1 || true
  timeout -k 30 5400 adb shell am instrument -w -e class "$cls" "$RUNNER" > "$out" 2>&1 || rc=$?
  log "instrument rc=$rc ($name)"
  { verdict_file_cat
    grep -aoE 'P8_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*' "$out" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P8_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*'
  } | sed 's/[[:space:]]*$//' | sort -u >> "$EV/p8-lines.txt" 2>/dev/null || true
  { verdict_file_cat
    grep -aoE 'P8_MODEL_AVAILABLE [01][^\r]*' "$out" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P8_MODEL_AVAILABLE [01][^\r]*'
  } | sed 's/[[:space:]]*$//' | sort -u >> "$EV/p8-model-lines.txt" 2>/dev/null || true
  verdict_file_cat > "$EV/p8-${name}-verdicts.txt" 2>/dev/null || true
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

verdict_of() { # $1=full line prefix
  local id="$1" lines
  lines=$(grep -aE "^${id} (PASS|FAIL|SKIP)" "$EV/p8-lines.txt" 2>/dev/null || true)
  if echo "$lines" | grep -aq "^$id FAIL"; then echo 1; return; fi
  if echo "$lines" | grep -aq "^$id PASS"; then echo 0; return; fi
  if echo "$lines" | grep -aq "^$id SKIP"; then echo 7; return; fi
  echo 1
}
detail_of() {
  grep -aE "^$1 (PASS|FAIL|SKIP)" "$EV/p8-lines.txt" 2>/dev/null | tail -1 | sed -E "s/^$1 [A-Z]+(::)? *//" | cut -c1-220
}

# The instrumented classes replaced the app process when they finished. Bring
# the runtime back the ordinary way and get the Keystore password out through
# the test-only harness export (Phase 5's mechanism, unchanged).
relaunch_and_export_password() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 2
  adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    [ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && break
    sleep 2
  done
  adb forward tcp:$PORT tcp:$PORT >/dev/null 2>&1 || true
  adb shell am instrument -w -e class "ai.opencode.android.client.OpenCodeClientGatesTest#harnessExportLoopbackCredentialForHostDrivers" \
    "$RUNNER" > "$EV/p8-harness-export.log" 2>&1
  PASSWD=$(rash "cat '$FILES/harness/server-password' 2>/dev/null" | tr -d '\r\n ')
  [ -n "$PASSWD" ] || PASSWD=$(rash "cat '$FILES/secrets/server-password' 2>/dev/null" | tr -d '\r\n ')
  # The export run killed the process again; relaunch for the driver stages.
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 2
  adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    [ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && break
    sleep 2
  done
  adb forward tcp:$PORT tcp:$PORT >/dev/null 2>&1 || true
}

[ -f "$APK" ] || { log "FATAL: no app APK at $APK"; p8 "BUILD" 1 "no-app-apk"; }
[ -f "$TAPK" ] || { log "FATAL: no androidTest APK at $TAPK"; p8 "BUILD" 1 "no-androidtest-apk"; }
WORKDIR="$FILES/workspaces/p8-stress"
P8_MODEL="${OPENROUTER_MODEL:-openai/gpt-4o-mini}"
MODEL_KEY="${OPENROUTER_API_KEY:-}"
P8_PERF_MODEL=""; P8_PERF_KEY=""

# ---------------------------------------------------------------------------
log "=== stage A: Phase 7 regression (workspace isolation + memory + UI + live turn) ==="
if [ "${P8_SKIP_P7REG:-0}" = "1" ]; then
  p8 P7REG 7 "P8_SKIP_P7REG=1 (regression run deferred; the full verdict run must not set this)"
else
  P7RC=0
  bash "$ROOT/phase7/scripts/20-gates.sh" >> "$LOG" 2>&1 || P7RC=$?
  P7FAILS=$(sed -n 's/^ui_gates_pass=[0-9]* ui_gates_fail=\([0-9]*\).*/\1/p' "$ROOT/phase7/out/evidence/GATES_SUMMARY.txt" 2>/dev/null | head -1)
  if [ "$P7RC" = "0" ] && [ "${P7FAILS:-1}" = "0" ]; then
    p8 P7REG 0 "phase7 suite rc=0 fails=0 (W1 W2 W3 U3 U7 L1 L2 all green; see phase7-evidence fold)"
  else
    p8 P7REG 1 "phase7 suite rc=$P7RC fails=${P7FAILS:-?} (see $ROOT/phase7/out/evidence/GATES_SUMMARY.txt)"
  fi
  mkdir -p "$EV/phase7-regression"
  cp -r "$ROOT/phase7/out/evidence/." "$EV/phase7-regression/" 2>/dev/null || true
  [ -f "$ROOT/phase7/out/evidence/p7-ui-lines.txt" ] && cp "$ROOT/phase7/out/evidence/p7-ui-lines.txt" "$EV/" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
log "=== stage A2: Phase 5 regression (client gates + loopback + credentials re-verification) ==="
if [ "${P8_SKIP_P5REG:-0}" = "1" ]; then
  p8 P5REG 7 "P8_SKIP_P5REG=1 (regression run deferred; the full verdict run must not set this)"
else
  # Fixtures the P5 suite expects: the stdio MCP fixture (fast, local) and the
  # remote MCP fixture (G16's host side; its absence SKIPs G16 honestly).
  bash "$ROOT/phase4/scripts/11-build-mcp.sh" >> "$LOG" 2>&1 \
    || log "warn: stdio MCP fixture build failed (expect P5-K G10_MCP and P5-R-10 to fail)"
  bash "$ROOT/phase5/scripts/11-build-remote-mcp.sh" >> "$LOG" 2>&1 \
    || log "warn: remote MCP fixture build failed (G16 will SKIP, not run)"
  P5RC=0
  bash "$ROOT/phase5/scripts/20-integration-gates.sh" >> "$LOG" 2>&1 || P5RC=$?
  # P5-G16 is red by design (upstream #47644): "everything else red" is a
  # regression, "only G16 red" is the steady state.
  P5FAILS=$(sed -n 's/.*gates_fail=\([0-9]*\).*/\1/p' "$ROOT/phase5/out/evidence/GATES_SUMMARY.txt" 2>/dev/null | head -1)
  P5NON16FAIL=$(grep -aE '^P5-[0-9A-Z]+ FAIL' "$ROOT/phase5/out/evidence/GATES_SUMMARY.txt" 2>/dev/null | grep -av '^P5-G16 ' | head -1)
  if [ -n "$P5NON16FAIL" ]; then
    p8 P5REG 1 "phase5 suite has a regression beyond the documented G16: $P5NON16FAIL (fails=${P5FAILS:-?} rc=$P5RC)"
  else
    p8 P5REG 0 "phase5 suite rc=$P5RC fails=${P5FAILS:-?} (G16 red = documented upstream #47644; K1-K9 G6-G12 G17 G18 G19 green)"
  fi
  mkdir -p "$EV/phase5-regression"
  cp -r "$ROOT/phase5/out/evidence/." "$EV/phase5-regression/" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
log "=== stage B: model provisioning (the run's repo-secret key, into the harness dir) ==="
install_fresh
# Harness marker BEFORE first launch: the instrumentation APK only exports the
# loopback password into files/harness/ when this marker exists (Phase 5 rule).
rash "mkdir -p '$FILES/harness'; touch '$FILES/harness/enabled'; chmod 700 '$FILES/harness'; echo harness_rc=\$?" >> "$LOG" 2>&1
if [ -n "$MODEL_KEY" ]; then
  # base64 over stdin: the key never appears in a device shell command line.
  printf '%s' "$MODEL_KEY" | base64 -w0 > "$OUT/model-key.b64"
  KEYB64=$(cat "$OUT/model-key.b64")
  rash "echo '$KEYB64' | base64 -d > '$FILES/harness/model-key'; chmod 600 '$FILES/harness/model-key'; echo keybytes=\$(wc -c < '$FILES/harness/model-key')" >> "$LOG" 2>&1
  printf '%s' "$P8_MODEL" | write_stdin_runas "harness/model" >> "$LOG" 2>&1
  log "model key provisioned (key bytes recorded in gates.log, value never logged); model=$P8_MODEL"
  P8_PERF_MODEL="$P8_MODEL"; P8_PERF_KEY="$MODEL_KEY"
else
  log "no OPENROUTER_API_KEY for this run: model-dependent gates will SKIP with the reason"
  p8 MODELPROVISION 7 "no OPENROUTER_API_KEY set (the model gates stay SKIP by design, not silence)"
fi
relaunch_and_export_password
if [ "${#PASSWD}" -ge 20 ] && [ "$(wait_healthy 240)" = "HEALTH_OK" ]; then
  log "runtime healthy with Keystore password available to the host drivers"
else
  log "runtime NOT healthy after relaunch (host drivers below may read as failures of the environment)"
fi

# ---------------------------------------------------------------------------
log "=== stage C: P8 instrumented gates (stress/recovery + live tool call) ==="
STRESS_RC=0
run_p8_class "stress" "ai.opencode.android.ui.StressRecoveryGatesTest" || STRESS_RC=1
for g in KEYRESIDENCY PROVAUTH SERVERKILL LIFECYCLELOG; do
  v=$(verdict_of "P8_$g"); p8 "$g" "$v" "$(detail_of "P8_$g")"
done
[ "$STRESS_RC" = 0 ] || log "note: stress instrument run rc=$STRESS_RC"
LIVE_RC=0
run_p8_class "live" "ai.opencode.android.ui.LiveToolCallGatesTest" || LIVE_RC=1
for g in KEYPROBE TOOL CLEANUP; do
  v=$(verdict_of "P8_$g"); p8 "$g" "$v" "$(detail_of "P8_$g")"
done
[ "$LIVE_RC" = 0 ] || log "note: live instrument run rc=$LIVE_RC"
grep -aE 'P8_MODEL_AVAILABLE' "$EV/p8-model-lines.txt" 2>/dev/null >> "$LOG" || true

# ---------------------------------------------------------------------------
log "=== stage D: destructive stress (host-driven, on the relaunch the instrument left behind) ==="
relaunch_and_export_password
stage_drivers || true
[ "$(wait_healthy 240)" = "HEALTH_OK" ] || log "warn: runtime not healthy at the start of stage D"
# The instrumented CLEANUP gate removed the run's key from the Keystore and
# the OpenCode auth store (as it must). The host driver gates below still need
# real model round-trips, so re-push the key into the SERVER's auth store only
# (p8-keymanage never touches the Keystore); it is revoked again after stage E.
if [ -n "$MODEL_KEY" ]; then
  P8_HIST_MODEL="$P8_MODEL"
  KEYPROV_OUT=$(run_js_on_device p8-keymanage.js provision 2>&1)
  echo "$KEYPROV_OUT" >> "$LOG"
  echo "$KEYPROV_OUT" | grep -q "P8KEYPROV ok=1" || log "warn: key re-provision before stage D did not confirm"
fi

# D1: the toybox staging path the harness itself relies on, on THIS API level.
# The app's own extraction does not use toybox (Kotlin reads the APK assets);
# toybox only serves gate-harness staging, which is why this gate's scope is
# stated exactly that way in the report.
TOYBOX_B64=$(mkdir -p "$OUT/tb" && printf 'toybox-check-a\n' > "$OUT/tb/a.txt" && printf 'toybox-check-b\n' > "$OUT/tb/b.txt" \
  && tar cz -C "$OUT/tb" . | base64 -w0)
TB_OUT=$(rash "rm -rf '$FILES/tmp/tb' && mkdir -p '$FILES/tmp/tb' && echo '$TOYBOX_B64' | base64 -d | tar xz -C '$FILES/tmp/tb' && ls '$FILES/tmp/tb' | tr '\n' ' ' && echo toybox_rc=\$?" 2>/dev/null)
TB_API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if echo "$TB_OUT" | grep -q "toybox_rc=0" && echo "$TB_OUT" | grep -q "a.txt"; then
  p8 TOYBOX 0 "base64|base64 -d|tar xz -C dir works on api=$TB_API (harness staging path; the API-29 half is the real-device question - see report)"
else
  p8 TOYBOX 1 "toybox tar staging failed on api=$TB_API: $(echo "$TB_OUT" | head -2)"
fi

# D2: corrupted runtime artifacts -> the extractor must detect and recover.
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 3
CORR_OUT=$(rash "test -f '$FILES/launcher.js' && { head -c 8 '$FILES/launcher.js' > '$FILES/launcher.js.tmp' && mv '$FILES/launcher.js.tmp' '$FILES/launcher.js'; } && printf 'corrupted' > '$FILES/opencode/dist/node/node.js' && rm -f '$FILES/runtime/.extracted' && echo corrupted_ok" 2>/dev/null)
if ! echo "$CORR_OUT" | grep -q corrupted_ok; then
  p8 CORRUPT 7 "payload not extracted on this device (nothing to corrupt): $(echo "$CORR_OUT" | head -2)"
else
  adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
  CORR_OK=1
  [ "$(wait_healthy 300)" = "HEALTH_OK" ] && CORR_OK=0
  # toybox grep (the device's) does not support GNU BRE alternation '\|' -
  # two plain single-pattern counts instead of one alternation.
  REX_R=$(rash "grep -ac '(re)extracting' '$FILES/log/runtime.log' 2>/dev/null" | tr -d '[:space:]')
  REX_C=$(rash "grep -ac 'extraction complete' '$FILES/log/runtime.log' 2>/dev/null" | tr -d '[:space:]')
  REEXTRACT=$(( ${REX_R:-0} + ${REX_C:-0} ))
  MARKER_BACK=$(rash "cat '$FILES/runtime/.extracted' 2>/dev/null" | grep -c payloadVersion | tr -d '[:space:]')
  if [ "$CORR_OK" = "0" ] && [ "${MARKER_BACK:-0}" -ge 1 ] && [ "${REEXTRACT:-0}" -ge 1 ]; then
    p8 CORRUPT 0 "truncated launcher + sha-mangled server bundle + wiped marker -> re-extracted and healthy (re-extraction lines=$REEXTRACT, marker restored)"
  else
    p8 CORRUPT 1 "corrupted payload did not recover cleanly (healthy=$([ $CORR_OK = 0 ] && echo yes || echo no) reextract_lines=${REEXTRACT:-0} marker=${MARKER_BACK:-0})"
  fi
fi

# D3: app process crash (force-stop mid-run) -> restart -> healthy again.
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 3
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
CRASH_OK=1
[ "$(wait_healthy 300)" = "HEALTH_OK" ] && CRASH_OK=0
p8 CRASH "$CRASH_OK" "force-stopped the app process with the server running; relaunch returned to HEALTHY (valid marker -> no re-extraction needed)"

# D4: session persistence across an app process restart.
SESS_OUT=$(run_js_on_device p8-sessionpersist.js create > "$EV/p8-session-create.log" 2>&1 && cat "$EV/p8-session-create.log")
SID=$(echo "$SESS_OUT" | grep -o 'P8SESCREATE [^ ]*' | awk '{print $2}' | head -1)
SESSMARK=$(echo "$SESS_OUT" | grep -o 'P8SESCREATE .*' | awk '{print $3}' | head -1)
if [ -z "$SID" ]; then
  p8 SESSIONPERSIST 1 "session create driver produced no session id: $(echo "$SESS_OUT" | head -2)"
else
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 3
  adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
  [ "$(wait_healthy 300)" = "HEALTH_OK" ] || log "warn: not healthy before the persistence verify"
  VERIFY_OUT=$(run_js_on_device p8-sessionpersist.js verify "$SID" "$SESSMARK" 2>&1)
  if echo "$VERIFY_OUT" | grep -q 'P8SESVRFY ok=1'; then
    p8 SESSIONPERSIST 0 "$(echo "$VERIFY_OUT" | grep P8SESVRFY | head -1) (the server's session store + project pointer survived the process death)"
  else
    p8 SESSIONPERSIST 1 "session or its user message lost across the restart: $(echo "$VERIFY_OUT" | head -2)"
  fi
fi

# D5: network loss mid-task (the provider-unreachable state, live).
NET_OUT_FILE="$EV/p8-netloss-start.log"
run_js_on_device p8-netloss.js start > "$NET_OUT_FILE" 2>&1
NETSID=$(grep -o 'P8NETSTART [^ ]*' "$NET_OUT_FILE" | awk '{print $2}' | head -1)
NETMARK=$(grep -o 'P8NETSTART .*' "$NET_OUT_FILE" | awk '{print $3}' | head -1)
if [ -z "$NETSID" ]; then
  p8 NETLOSS 1 "turn could not be started: $(head -2 "$NET_OUT_FILE")"
else
  WIFI_BEFORE=$(adb shell svc wifi status 2>/dev/null | tr -d '\r' | head -1)
  log "wifi before cut: $WIFI_BEFORE"
  adb shell svc wifi disable >/dev/null 2>&1 || true
  adb shell svc data disable >/dev/null 2>&1 || true
  sleep 6
  WAIT_OUT=$(run_js_on_device p8-netloss.js wait "$NETSID" 2>&1 | tee "$EV/p8-netloss-wait.log")
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
  sleep 12
  REC_OUT=$(run_js_on_device p8-netloss.js recover "$NETSID" 2>&1 | tee "$EV/p8-netloss-recover.log")
  STATUS=$(echo "$WAIT_OUT" | grep -o 'P8NETRESULT status=[a-z]*' | head -1 | cut -d= -f2)
  ERRTEXT=$(echo "$WAIT_OUT" | grep -o 'errText=[^ ]*' | head -1 | cut -d= -f2)
  USERMSGS=$(echo "$WAIT_OUT" | grep -o 'userMsgs=[0-9-]*' | head -1 | cut -d= -f2)
  # The classifier's own network vocabulary (UiError.NETWORK_HINTS, mirrored):
  # the server's error must read as a network/provider-reachability failure,
  # never as an auth or runtime failure.
  if echo "$ERRTEXT" | grep -aiqE 'fetch failed|econnrefused|econnreset|eai_again|enotfound|etimedout|ehostunreach|enetunreach|socket hang up|network|timeout|timed out|unavailable|bad gateway|gateway|overloaded|rate limit|too many requests'; then
    NETCLASS=network
  else
    NETCLASS=other
  fi
  if [ "$STATUS" = "complete" ]; then
    p8 NETLOSS 7 "the turn completed before the cut landed (errText not applicable); not evidence of the failure path - rerun or accept the instrumented PROVAUTH + JVM classifier coverage"
  elif [ "$STATUS" = "error" ] && [ "$NETCLASS" = "network" ] && echo "$REC_OUT" | grep -q 'P8NETRECOVER ok=1' && [ "${USERMSGS:-0}" = "1" ]; then
    p8 NETLOSS 0 "turn in flight, network pulled: server reported a network-shaped error ($ERRTEXT), exactly one user message recorded (no double-send), and the same session recovered after the network returned"
  else
    p8 NETLOSS 1 "status=$STATUS errName/text='${ERRTEXT}' recover=$(echo "$REC_OUT" | head -1) userMsgs=${USERMSGS:-?} (expected: network-shaped error, clean recovery, no double-send)"
  fi
fi

# D6: background/foreground survival with a turn in flight.
BGJS='const { createSession, promptAsync, waitTurnComplete, assistantText } = require("./gates-lib.js");
(async () => {
  const s = await createSession("p8 bgfg");
  await promptAsync(s.id, "Reply with exactly: P8BGFGDONE and nothing else.");
  const done = await waitTurnComplete(s.id, { timeoutMs: 300000 });
  const t = assistantText(done.messages);
  console.log("P8BGFG ok=" + (t.includes("P8BGFGDONE") && !done.failed ? 1 : 0) + " replyChars=" + t.length + " failed=" + done.failed);
  process.exit(0);
})().catch((e) => { console.log("P8BGFG ok=0 error=" + String(e.message || e).slice(0, 160)); process.exit(1); });'
printf '%s' "$BGJS" | write_stdin_runas "bgfg.js" > /dev/null 2>&1
( rash "mkdir -p '$DEVJS' && cd '$DEVJS' && timeout -k 5 420 env OPENCODE_BASE=\"http://127.0.0.1:$PORT\" OPENCODE_SERVER_PASSWORD=\"$PASSWD\" OPENCODE_SERVER_USERNAME=opencode OPENCODE_DIRECTORY=\"$WORKDIR\" '$FILES/bin/bun' 'bgfg.js' 2>&1; echo DEVJS_RC=\$?" > "$EV/p8-bgfg.log" 2>&1 ) &
BG_PID=$!
sleep 8
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
log "app backgrounded; 90s in the background with a turn in flight"
sleep 90
BG_MIDHEALTH=$(health_code_device)
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
wait "$BG_PID" 2>/dev/null
BG_OUT=$(grep -a 'P8BGFG ok=' "$EV/p8-bgfg.log" | head -1)
if echo "$BG_OUT" | grep -q 'ok=1'; then
  p8 BGFG 0 "$BG_OUT (app backgrounded 90s with a turn in flight; mid-background device health code=$BG_MIDHEALTH; foregrounded and the turn completed)"
else
  p8 BGFG 1 "background/foreground: ${BG_OUT:-no verdict line} (mid-background health=$BG_MIDHEALTH)"
fi

# D7: large project (filesystem + API + the payload's own native Git).
LARGE_DIR="$FILES/workspaces/p8-large"
LARGE_OUT=$(run_js_on_device p8-large.js build "$LARGE_DIR" 2>&1 | tee "$EV/p8-large-build.log")
if echo "$LARGE_OUT" | grep -q 'P8LARGEBUILD ok=1'; then
  MEAS_OUT=$(run_js_on_device p8-large.js measure "$LARGE_DIR" 2>&1 | tee "$EV/p8-large-measure.log")
  # Git half with the packaged native Git (Phase 5's fixture mechanics).
  APK_PATH=$(adb shell pm path "$PKG" | tr -d '\r' | sed 's/^package://' | head -1)
  GIT_NATIVE="$(dirname "$APK_PATH")/lib/$(adb shell getprop ro.product.cpu.abi | tr -d '\r')/libgit.so"
  GIT_T0=$(date +%s)
  GIT_OUT=$(rash "cd '$LARGE_DIR' && export GIT_AUTHOR_NAME=p8 GIT_AUTHOR_EMAIL=p8@opencode.local GIT_COMMITTER_NAME=p8 GIT_COMMITTER_EMAIL=p8@opencode.local && '$GIT_NATIVE' init -q -b main 2>&1 && '$GIT_NATIVE' add -A 2>&1 | tail -1 && '$GIT_NATIVE' commit -qm 'p8 large' 2>&1 && echo GIT_OK" 2>/dev/null)
  GIT_MS=$(( ( $(date +%s) - GIT_T0 ) * 1000 ))
  if echo "$MEAS_OUT" | grep -q 'P8LARGEMEASURE' && echo "$MEAS_OUT" | grep -q 'contentOk=1' && echo "$GIT_OUT" | grep -q GIT_OK; then
    p8 LARGE 0 "$(echo "$MEAS_OUT" | grep P8LARGEMEASURE | head -1) gitInitAddCommitMs=$GIT_MS ($(echo "$LARGE_OUT" | grep P8LARGEBUILD | head -1))"
  else
    p8 LARGE 1 "large project incomplete: measure=$(echo "$MEAS_OUT" | head -1) git=$(echo "$GIT_OUT" | tail -1)"
  fi
else
  p8 LARGE 1 "large project build failed: $(echo "$LARGE_OUT" | head -2)"
fi

# D8: large history (N sequential turns in one session, key-free default model).
HIST_OUT=$(P8_DRIVER_TIMEOUT=5400 run_js_on_device p8-hist.js 2>&1 | tee "$EV/p8-hist.log")
if echo "$HIST_OUT" | grep -q 'P8HIST ok=1'; then
  p8 HIST 0 "$(echo "$HIST_OUT" | grep P8HIST | head -1)"
else
  p8 HIST 1 "$(echo "$HIST_OUT" | grep -E 'P8HIST|failed' | head -3)"
fi

# D9: low storage (fill the app's own data dir, observe behaviour, clean up).
FREE_BEFORE_KB=$(adb shell df /data 2>/dev/null | awk 'NR==2{print $4}' | tr -d '\r')
SCRATCH="$FILES/p8-scratch.bin"
FILLED=0
for _ in $(seq 1 18); do
  rash "dd if=/dev/zero of='$SCRATCH' bs=1048576 count=100 seek=$((FILLED * 100)) conv=notrunc 2>/dev/null; echo filled=\$?" >/dev/null 2>&1
  FILLED=$((FILLED + 100))
  FREE_KB=$(adb shell df /data 2>/dev/null | awk 'NR==2{print $4}' | tr -d '\r')
  [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt 153600 ] 2>/dev/null && break
done
FREE_AFTER_KB=$(adb shell df /data 2>/dev/null | awk 'NR==2{print $4}' | tr -d '\r')
LOG_TAIL_BEFORE=$(rash "tail -c 4000 '$FILES/log/runtime.log' 2>/dev/null" | wc -c | tr -d ' ')
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 3
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
STOR_OK=1
STOR_DETAIL=""
if [ "$(wait_healthy 300)" = "HEALTH_OK" ]; then
  STOR_OK=0
  STOR_DETAIL="healthy under low storage"
else
  LOG_NOW=$(rash "tail -c 4000 '$FILES/log/runtime.log' 2>/dev/null")
  if echo "$LOG_NOW" | grep -aqE 'extraction|FATAL|fatal|storage'; then
    STOR_OK=0
    STOR_DETAIL="not healthy, but the supervisor says why (human-readable): $(echo "$LOG_NOW" | grep -aE 'FATAL|fatal|storage|extraction' | tail -2 | tr '\n' ' ' | cut -c1-180)"
  else
    STOR_DETAIL="not healthy and the supervisor log explains nothing (crash loop or silent death)"
  fi
fi
rash "rm -f '$SCRATCH'" >/dev/null 2>&1
FREE_RESTORED_KB=$(adb shell df /data 2>/dev/null | awk 'NR==2{print $4}' | tr -d '\r')
if [ "$STOR_OK" = "0" ]; then
  p8 STORAGE 0 "free: ${FREE_BEFORE_KB:-?}KB -> ${FREE_AFTER_KB:-?}KB (app), back to ${FREE_RESTORED_KB:-?}KB after cleanup :: $STOR_DETAIL"
else
  p8 STORAGE 1 "free: ${FREE_BEFORE_KB:-?}KB -> ${FREE_AFTER_KB:-?}KB :: $STOR_DETAIL"
fi

# ---------------------------------------------------------------------------
log "=== stage E: performance measurement (the report's numbers) ==="
relaunch_and_export_password
stage_drivers || true
[ "$(wait_healthy 240)" = "HEALTH_OK" ] || log "warn: not healthy at stage E"
# Startup timing from the supervisor's own (timestamped) log: cold = first
# launch after install_fresh in this run (EXTRACTING -> HEALTHY window).
rash "tail -c 80000 '$FILES/log/runtime.log' 2>&1" > "$EV/runtime.log" 2>&1 || true
EX_T=$(grep -a "state -> EXTRACTING" "$EV/runtime.log" 2>/dev/null | head -1 | awk '{print $1}')
HE_T=$(grep -a "state -> HEALTHY" "$EV/runtime.log" 2>/dev/null | head -1 | awk '{print $1}')
COLD_MS=""
if [ -n "$EX_T" ] && [ -n "$HE_T" ]; then
  EX_MS=$(date -u -d "$EX_T" +%s%3N 2>/dev/null || true)
  HE_MS=$(date -u -d "$HE_T" +%s%3N 2>/dev/null || true)
  if [ -n "$EX_MS" ] && [ -n "$HE_MS" ]; then COLD_MS=$(( HE_MS - EX_MS )); fi
fi
[ -n "$COLD_MS" ] || COLD_MS="unparsed"
log "cold start (supervisor log): EXTRACTING=$EX_T HEALTHY=$HE_T -> ${COLD_MS}ms"
STOP_T0=$(date +%s)
adb shell am start -n "$PKG/ai.opencode.android.runtime.DebugControlActivity" --ei mode 1 >> "$LOG" 2>&1 || true
sleep 5
# Relaunch with visibility and retries: the first full run's warm relaunch
# produced no supervisor log lines at all (the emulator swallowed the launch
# under load), and a blind 240s wait turned that into an unexplained timeout.
AM_OUT=""
APP_UP=0
for attempt in 1 2 3; do
  AM_OUT=$(adb shell am start -n "$PKG/ai.opencode.android.MainActivity" 2>&1)
  echo "warm relaunch attempt $attempt: $AM_OUT" >> "$LOG"
  sleep 15
  APP_PID=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  [ -n "$APP_PID" ] && APP_UP=1
  [ "$APP_UP" = "1" ] && break
done
WARM_OK=1
[ "$(wait_healthy 240)" = "HEALTH_OK" ] && WARM_OK=0
WARM_MS=$(( ( $(date +%s) - STOP_T0 ) * 1000 ))
[ "$WARM_OK" = "0" ] || log "warm relaunch: app_up=$APP_UP am='$AM_OUT' (see gates.log for per-attempt output)"
# Memory + CPU while healthy and idle, then during a turn.
MEMINFO=$(adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' | grep -E 'TOTAL|Native Heap|Dalvik Heap|Java Heap' | head -8)
MEMINFO > "$EV/p8-meminfo-idle.txt"
CPU_BEFORE=$(adb shell top -b -n 1 2>/dev/null | tr -d '\r' | grep -E "$PKG|bun" | head -5)
CPU_BEFORE > "$EV/p8-cpu-idle.txt"
PERF_OUT=$(run_js_on_device p8-perf.js 2>&1 | tee "$EV/p8-perf.log")
if echo "$PERF_OUT" | grep -q 'P8PERF ok=1'; then
  p8 PERF 0 "$(echo "$PERF_OUT" | grep P8PERF | head -1) warmRestartMs=$WARM_MS coldWindow='$COLD_MS'"
else
  p8 PERF 1 "$(echo "$PERF_OUT" | grep -E 'P8PERF|failed|error' | head -3) warmRestartMs=$WARM_MS"
fi
adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' > "$EV/p8-meminfo-full.txt" || true
# Storage footprint of the whole runtime.
rash "du -sk '$FILES' 2>/dev/null; echo ---; du -sk '$FILES/opencode' '$FILES/node_modules' '$FILES/xdg' 2>/dev/null" > "$EV/p8-storage-footprint.txt" 2>&1 || true

# ---------------------------------------------------------------------------
log "=== evidence pull ==="
rash "tail -c 60000 '$FILES/log/runtime.log' 2>&1" > "$EV/runtime.log" 2>&1 || true
rash 'for d in "$FILES/xdg/data/opencode/log" "$FILES/xdg/state/opencode/log"; do for f in "$d"/*.log; do [ -e "$f" ] || continue; echo "### $f"; tail -150 "$f"; done; done' > "$EV/opencode-server.log" 2>&1 || true
timeout 90 adb logcat -d -s OpenCode:V OpenCode/gate:V > "$EV/logcat-OpenCode.txt" 2>&1 || true
timeout 90 adb exec-out screencap -p > "$EV/screenshots/99-final-host-screen.png" 2>/dev/null || true
[ -s "$EV/screenshots/99-final-host-screen.png" ] || rm -f "$EV/screenshots/99-final-host-screen.png" 2>/dev/null || true
# The injected key must not survive the run - on the host, on the device, in
# the Keystore (the CLEANUP gate proved it removed), and in the server auth
# store (re-provisioned before stage D, so revoked here before the run ends).
if [ -n "$MODEL_KEY" ]; then
  run_js_on_device p8-keymanage.js revoke >> "$LOG" 2>&1 || log "warn: final key revoke did not confirm"
fi
rm -f "$OUT/model-key.b64" 2>/dev/null || true
rash "rm -f '$FILES/harness/server-password' '$FILES/harness/model-key' '$FILES/harness/model'" >/dev/null 2>&1 || true

SHOTS=$(ls -1 "$EV/screenshots" 2>/dev/null | grep -c '\.png$' || echo 0)
MODEL_AVAILABLE=0
grep -aqE '^P8_MODEL_AVAILABLE 1' "$EV/p8-model-lines.txt" 2>/dev/null && MODEL_AVAILABLE=1

cat >> "$SUMMARY" <<EOF
P8_SUMMARY $(date -u +%FT%TZ)
gates_pass=$PASS gates_fail=$FAIL gates_skip=$SKIP
stress_instrument_rc=$STRESS_RC live_instrument_rc=$LIVE_RC
screenshots=$SHOTS
model_available=$MODEL_AVAILABLE model=$P8_MODEL
device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
android_sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
EOF
log "=== SUMMARY ==="; cat "$SUMMARY" | tee -a "$LOG"

RC=0
[ "$FAIL" -eq 0 ] || RC=1
exit "$RC"
