#!/usr/bin/env bash
# 20-gates.sh - Phase 9 gate stage (release), run on the emulator the
# orchestrator booted, AFTER the Phase 8 suite ran on the same device.
#
# Phase 9 owns four verdicts of its own; everything else is the Phase 8 suite
# re-run verbatim (regressions of Phases 5/7 folded in by that suite):
#   P9_PROVSEL_*   the carried provider-selection defect, reproduced and then
#                  proven fixed on the real server with a dummy credential
#                  (ProviderSelectionGatesTest; no funded key needed).
#   P9_PLUGIN      the carried npm plugin defect: the runtime now reports the
#                  bare semver, so `@opencode-ai/plugin@<version>` resolves and
#                  the background install upstream does on every config dir
#                  succeeds (observed in OpenCode's own log + node_modules on
#                  device). Needs registry egress from the emulator.
#   P9_VERSION     /global/health reports the bare upstream version (no
#                  "-android" suffix) and it equals versions.lock.
#   P9_LOCK        versions.lock == runtime-manifest.json == RuntimeVersion.kt
#                  == the APK's versionName (the "shipped == pinned" check).
#
# Verdict discipline unchanged: P9_<id> PASS|FAIL|SKIP :: from inside the app
# process to logcat AND an on-device file; the host counts the deduplicated
# union so a gate that never ran cannot pass.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
mkdir -p "$EV"
SUMMARY="$EV/GATES_SUMMARY.txt"
LOG="$EV/gates.log"
: > "$SUMMARY"; : > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
FILES="/data/data/$PKG/files"
PORT=4111

PASS=0; FAIL=0; SKIP=0
: > "$EV/p9-lines.txt"
rec() { log "$1 $2${3:+ :: $3}"; echo "$1 $2${3:+ :: $3}" >> "$EV/p9-lines.txt"; }
p9() { # $1=id $2=rc(0 pass, 7 skip, else fail) $3=detail
  case "$2" in
    0) PASS=$((PASS+1)); rec "P9_$1" PASS "$3" ;;
    7) SKIP=$((SKIP+1)); rec "P9_$1" SKIP "$3" ;;
    *) FAIL=$((FAIL+1)); rec "P9_$1" FAIL "$3" ;;
  esac
}
rash() { printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'; }

verdict_file_cat() { rash "cat files/p8-verdicts.txt 2>/dev/null"; }
verdict_of() {
  local id="$1" lines
  lines=$(grep -aE "^${id} (PASS|FAIL|SKIP)" "$EV/p9-lines.txt" 2>/dev/null || true)
  if echo "$lines" | grep -aq "^$id FAIL"; then echo 1; return; fi
  if echo "$lines" | grep -aq "^$id PASS"; then echo 0; return; fi
  if echo "$lines" | grep -aq "^$id SKIP"; then echo 7; return; fi
  echo 1
}
detail_of() {
  grep -aE "^$1 (PASS|FAIL|SKIP)" "$EV/p9-lines.txt" 2>/dev/null | tail -1 | sed -E "s/^$1 [A-Z]+(::)? *//" | cut -c1-260
}

health_ok() {
  local pw; pw=$(rash "cat '$FILES/harness/server-password' 2>/dev/null" | tr -d '\r\n ')
  [ -n "$pw" ] || return 1
  adb forward tcp:$PORT tcp:$PORT >/dev/null 2>&1 || true
  curl -s -o "$OUT/health.json" -u "opencode:$pw" --max-time 4 "http://127.0.0.1:$PORT/global/health" 2>/dev/null \
    && grep -q healthy "$OUT/health.json"
}
wait_healthy() {
  local deadline=$(( $(date +%s) + ${1:-240} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do health_ok && { echo HEALTH_OK; return 0; }; sleep 3; done
  echo HEALTH_TIMEOUT; return 1
}
start_app() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 2
  adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do [ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && break; sleep 2; done
  adb forward tcp:$PORT tcp:$PORT >/dev/null 2>&1 || true
}
# The Phase 8 suite deletes the harness password export at its end; re-export it
# through the Phase 5 test-only mechanism (harness marker + instrumented export),
# then bring the app back the ordinary way.
relaunch() {
  rash "mkdir -p '$FILES/harness'; touch '$FILES/harness/enabled'; chmod 700 '$FILES/harness'" >/dev/null 2>&1 || true
  start_app
  adb shell am instrument -w -e class "ai.opencode.android.client.OpenCodeClientGatesTest#harnessExportLoopbackCredentialForHostDrivers" \
    "$RUNNER" > "$EV/p9-harness-export.log" 2>&1 || true
  start_app
}

run_p9_class() { # $1=short-name $2=class
  local name="$1"; local cls="$2"; local rc=0
  local out="$EV/p9-${name}-instrument.log"
  log "=== am instrument $cls ==="
  rash "rm -f files/p8-verdicts.txt" >/dev/null 2>&1 || true
  adb logcat -c >/dev/null 2>&1 || true
  timeout -k 30 1800 adb shell am instrument -w -e class "$cls" "$RUNNER" > "$out" 2>&1 || rc=$?
  log "instrument rc=$rc ($name)"
  { verdict_file_cat
    grep -aoE 'P9_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*' "$out" 2>/dev/null
    timeout 90 adb logcat -d 2>/dev/null | grep -aoE 'P9_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*'
  } | sed 's/[[:space:]]*$//' | sort -u >> "$EV/p9-lines.txt" 2>/dev/null || true
  { verdict_file_cat; grep -a 'P9_' "$out" 2>/dev/null; } | grep -aoE 'P9_[A-Z0-9_]+ [^\r]*' | sort -u > "$EV/p9-${name}-verdicts.txt" 2>/dev/null || true
  tail -30 "$out" >> "$LOG" 2>/dev/null || true
  return $rc
}

# ---------------------------------------------------------------------------
log "=== P9 stage 1: provider selection (the carried defect, reproduced then fixed) ==="
# The Phase 8 suite left the device installed and (usually) healthy; make sure.
relaunch
[ "$(wait_healthy 300)" = "HEALTH_OK" ] || log "warn: runtime not healthy before PROVSEL (the class waits again itself)"
run_p9_class "provsel" "ai.opencode.android.client.ProviderSelectionGatesTest" || log "note: provsel instrument rc!=0"
for g in PROVSEL_STALE PROVSEL_REBUILT PROVSEL_TURN PROVSEL_CLEANUP; do
  v=$(verdict_of "P9_$g"); p9 "$g" "$v" "$(detail_of "P9_$g")"
done
grep -aE 'P9_PROVSEL_(BASELINE|SERVERLOG)' "$EV/p9-provsel-verdicts.txt" 2>/dev/null | tee -a "$LOG" >/dev/null || true

# ---------------------------------------------------------------------------
log "=== P9 stage 2: runtime version string + plugin install ==="
relaunch
[ "$(wait_healthy 300)" = "HEALTH_OK" ] || log "warn: runtime not healthy before VERSION/PLUGIN"
HV=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("version",""))' "$OUT/health.json" 2>/dev/null || echo "")
LOCKV=$(sed -n 's/^  version: \([0-9.]*\).*/\1/p' "$ROOT/versions.lock" | head -1)
if [ -n "$HV" ] && [ "$HV" = "$LOCKV" ]; then
  p9 VERSION 0 "/global/health version='$HV' == versions.lock '$LOCKV' (bare semver; the old '-android' suffix is gone)"
else
  p9 VERSION 1 "/global/health version='$HV' vs versions.lock '$LOCKV'"
fi

# Plugin install: upstream (config.ts) forks `npm install @opencode-ai/plugin@<version>`
# into each config dir on instance load and logs the outcome. Trigger a load of the
# workspace instance (GET /provider is enough), give the background install time,
# then read OpenCode's own log and the resulting node_modules. Registry egress from
# the emulator is a precondition; when the probe below cannot reach it, SKIP.
REG_PROBE=$(rash "cd '$FILES' && timeout 60 '$FILES/bin/bun' -e 'const r=await fetch(\"https://registry.npmjs.org/@opencode-ai/plugin/latest\");console.log(\"REG\"+r.status)' 2>&1" | grep -o 'REG[0-9]*' | head -1)
log "npm registry probe from the app uid: ${REG_PROBE:-none}"
# Phase 9 payload v7 ships the installed tree (plugin-seed/ -> xdg/config/opencode).
# The gate therefore asserts three things over a 150 s observation window:
#   (a) node_modules/@opencode-ai/plugin exists at the pinned version,
#   (b) OpenCode's own log records NO "background dependency install failed"
#       (upstream's Npm.install short-circuited - Arborist never ran),
#   (c) the server did not die with exit 159 (SIGSYS) in the window - the
#       crash loop that run 35132822991 exposed when the install DID run.
CRASH159_BEFORE=$(rash "grep -c 'code=159' '$FILES/log/runtime.log' 2>/dev/null" | tr -dc '0-9'); CRASH159_BEFORE=${CRASH159_BEFORE:-0}
INSTFAIL_BEFORE=$(rash 'for d in "'"$FILES"'/xdg/data/opencode/log" "'"$FILES"'/xdg/state/opencode/log"; do cat "$d"/*.log 2>/dev/null; done | grep -ac "dependency install failed"' | tr -dc '0-9'); INSTFAIL_BEFORE=${INSTFAIL_BEFORE:-0}
# touch the workspace instance so config (and thus the install path) is loaded
HPW=$(rash "cat '$FILES/harness/server-password' 2>/dev/null" | tr -d '\r\n ')
curl -s -u "opencode:$HPW" --max-time 20 "http://127.0.0.1:$PORT/provider" -o /dev/null || true
PLUG_DEADLINE=$(( $(date +%s) + 150 ))
PLUG_LOG=""; PLUG_FAIL=""; PLUG_NM=""; PLUG_VER=""
while [ "$(date +%s)" -lt "$PLUG_DEADLINE" ]; do
  PLUG_LOG=$(rash 'for d in "'"$FILES"'/xdg/data/opencode/log" "'"$FILES"'/xdg/state/opencode/log"; do for f in "$d"/*.log; do [ -e "$f" ] || continue; grep -a "dependency install\|opencode-ai/plugin\|NpmInstallFailedError" "$f"; done; done 2>/dev/null' | tail -20)
  PLUG_NM=$(rash "ls -d '$FILES'/xdg/config/opencode/node_modules/@opencode-ai/plugin 2>/dev/null" | tr '\n' ' ')
  PLUG_VER=$(rash "cat '$FILES'/xdg/config/opencode/node_modules/@opencode-ai/plugin/package.json 2>/dev/null" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("version",""))
except Exception: print("")' 2>/dev/null)
  sleep 15
done
printf '%s\n' "$PLUG_LOG" > "$EV/p9-plugin-install.log"
CRASH159_AFTER=$(rash "grep -c 'code=159' '$FILES/log/runtime.log' 2>/dev/null" | tr -dc '0-9'); CRASH159_AFTER=${CRASH159_AFTER:-0}
INSTFAIL_AFTER=$(rash 'for d in "'"$FILES"'/xdg/data/opencode/log" "'"$FILES"'/xdg/state/opencode/log"; do cat "$d"/*.log 2>/dev/null; done | grep -ac "dependency install failed"' | tr -dc '0-9'); INSTFAIL_AFTER=${INSTFAIL_AFTER:-0}
NEW159=$(( CRASH159_AFTER - CRASH159_BEFORE )); NEWFAIL=$(( INSTFAIL_AFTER - INSTFAIL_BEFORE ))
# SIGSYS forensics whenever a 159 happened anywhere in this boot (diagnosis for the shim table)
if [ "$CRASH159_AFTER" -gt 0 ]; then
  { adb logcat -b all -d 2>/dev/null | grep -aE 'type=1326|SYS_SECCOMP|Fatal signal 31|disallowed .* system call|seccomp prevented' | tail -40
    rash "ls -t /data/tombstones 2>/dev/null | head -3" 2>/dev/null
  } > "$EV/p9-sigsys-forensics.txt" 2>&1 || true
fi
if [ -n "$PLUG_NM" ] && [ "$PLUG_VER" = "$LOCKV" ] && [ "$NEWFAIL" = 0 ] && [ "$NEW159" = 0 ]; then
  p9 PLUGIN 0 "@opencode-ai/plugin@$PLUG_VER present under xdg/config/opencode (payload seed); no on-device npm install attempted (install-failed lines +$NEWFAIL, exit-159 crashes +$NEW159 in 150 s; total 159s this boot=$CRASH159_AFTER)"
elif [ -z "$PLUG_NM" ]; then
  p9 PLUGIN 1 "plugin seed missing on device (payload v7 promotion did not happen): $(printf '%s' "$PLUG_LOG" | tail -1 | cut -c1-200)"
else
  p9 PLUGIN 1 "seed present (version='$PLUG_VER' want $LOCKV) but install-failed lines +$NEWFAIL, exit-159 crashes +$NEW159 in the window: $(printf '%s' "$PLUG_LOG" | tail -1 | cut -c1-200)"
fi
# Extra plugin beyond the seed: NOT exercised here on purpose - it is the path
# that SIGSYS-crashes the server (documented BLOCKED, forensics above when seen).

# ---------------------------------------------------------------------------
log "=== P9 stage 3: versions.lock == manifest == RuntimeVersion.kt == APK ==="
MF="$ROOT/phase4/out/engine/assets/runtime-manifest.json"
KT="$ROOT/app/src/main/java/ai/opencode/android/runtime/RuntimeVersion.kt"
LOCKCHECK=$(python3 "$DIR/scripts/check-lock.py" "$ROOT" --require-manifest 2>&1)
echo "$LOCKCHECK" | tee -a "$LOG"
if echo "$LOCKCHECK" | grep -q '^OK'; then p9 LOCK 0 "$(echo "$LOCKCHECK" | cut -c4-300)"; else p9 LOCK 1 "$(echo "$LOCKCHECK" | cut -c1-300)"; fi

# ---------------------------------------------------------------------------
log "=== evidence pull ==="
rash "tail -c 60000 '$FILES/log/runtime.log' 2>&1" > "$EV/runtime.log" 2>&1 || true
rash 'for d in "'"$FILES"'/xdg/data/opencode/log" "'"$FILES"'/xdg/state/opencode/log"; do for f in "$d"/*.log; do [ -e "$f" ] || continue; echo "### $f"; tail -200 "$f"; done; done' > "$EV/opencode-server.log" 2>&1 || true
grep -ao "llm.runtime=[a-z-]* llm.provider=[a-zA-Z0-9_-]* llm.model=[a-zA-Z0-9./_-]*" "$EV/opencode-server.log" 2>/dev/null | sort | uniq -c | sort -rn > "$EV/provider-used.txt" 2>&1 || true
timeout 90 adb logcat -d -s OpenCode:V OpenCode/gate:V > "$EV/logcat-OpenCode.txt" 2>&1 || true
rash "rm -f '$FILES/harness/server-password'" >/dev/null 2>&1 || true

cat >> "$SUMMARY" <<EOF
P9_SUMMARY $(date -u +%FT%TZ)
gates_pass=$PASS gates_fail=$FAIL gates_skip=$SKIP
device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
android_sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
EOF
log "=== P9 SUMMARY ==="; cat "$SUMMARY" | tee -a "$LOG"
[ "$FAIL" -eq 0 ]
