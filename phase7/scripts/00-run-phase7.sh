#!/usr/bin/env bash
# 00-run-phase7.sh - single orchestrator for the Phase 7 workspace/memory gates.
#
# What Phase 7 has to prove, and therefore what this script does:
#   * workspace isolation is REAL - the agent's file access is restricted to the
#     project directory by OpenCode's own file layer, not just hidden by the UI
#     (two projects are mutually invisible; a read that escapes the directory is
#     refused with "Path escapes the location");
#   * the memory layer (OpenCode's own AGENTS.md rules) is inspectable, editable
#     and removable on the device;
#   * project management (create / rename / delete / adopt) works on the real
#     filesystem;
#   * the Phase 6 product-UI gates that audit what Phase 7 changed (U3 permission
#     ask flow, U7 accessibility) and the live-tool gate (L2) still hold;
#   * every Phase 5 client-integration gate still passes (regression tail), with
#     P5-G16 remaining the documented upstream restriction.
#
# The Actions log bodies are not reachable from the development sandbox this work
# is driven from (Phase 5 finding), so evidence is committed + pushed back to the
# branch on EVERY exit path - including a failed Gradle build, whose compiler
# output is the only artifact anyone will want to read in that case.
#
# Every timeout-bounded step writes to a FILE, never through a pipe: with
# `cmd | tee`, killing cmd on timeout can leave a surviving JVM holding the
# pipe's write end open so tee never sees EOF and the step hangs forever (that is
# what Phase 5 runs #1-#3 did for two hours each).
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
mkdir -p "$OUT" "$EV"
MAINLOG="$OUT/00-run-phase7.log"
: > "$MAINLOG"

PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"

step() { HB_STEP="$1"; echo; echo "########## $1 ##########" | tee -a "$MAINLOG"; }

run_c() {
  local t="$1"; shift
  local logf="$OUT/step.$$.out"
  timeout -k 30 "$t" bash -c "${*}" > "$logf" 2>&1
  local rc=$?
  cat "$logf" 2>/dev/null
  cat "$logf" >> "$MAINLOG" 2>/dev/null
  rm -f "$logf" 2>/dev/null
  if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then
    echo "STEP_TIMEOUT rc=$rc after ${t}s (killed): ${*}" | tee -a "$MAINLOG"
  fi
  [ "$rc" = 0 ] || { echo "STEP_FAILED rc=$rc: ${*}" | tee -a "$MAINLOG"; return 1; }
  return 0
}

echo "=== PHASE 7 START $(date -u +%FT%TZ) ===" | tee -a "$MAINLOG"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"

# ---------------------------------------------------------------------------
# Evidence. Everything a reviewer needs to judge a claim: the orchestrator log,
# the gate summaries, per-class instrument output, the screenshots the UI gates
# captured on device, logcat, the runtime's own log and the static-check output.
collect_evidence() {
  cp "$MAINLOG" "$EV/00-run-phase7.log" 2>/dev/null || true
  cp "$OUT/emulator.log" "$EV/" 2>/dev/null || true
  cp "$ROOT/phase4/out/engine/assets/runtime-manifest.json" "$EV/runtime-manifest.json" 2>/dev/null || true
  mkdir -p "$EV/jvm-unit-tests"
  for f in "$ROOT"/app/build/test-results/testDebugUnitTest/*.xml; do
    if [ -e "$f" ]; then cp "$f" "$EV/jvm-unit-tests/" 2>/dev/null || true; fi
  done
  timeout 90 adb logcat -d > "$EV/logcat.raw" 2>&1 || true
  if [ -s "$EV/logcat.raw" ]; then
    grep -aE "OpenCode|AndroidRuntime|FATAL|bun|TestRunner|Compose" "$EV/logcat.raw" > "$EV/logcat.txt" 2>&1 || true
  else
    : > "$EV/logcat.txt"
  fi
  rm -f "$EV/logcat.raw" 2>/dev/null || true
  # The Phase 5 tail stage keeps its own bundle; copy the verdict files beside
  # ours so one directory answers "did Phase 7 regress Phase 5?".
  if [ -d "$ROOT/phase5/out/evidence" ]; then
    mkdir -p "$EV/phase5-regression"
    cp -r "$ROOT/phase5/out/evidence/." "$EV/phase5-regression/" 2>/dev/null || true
    if [ -f "$ROOT/phase5/out/00-run-phase5.log" ]; then
      cp "$ROOT/phase5/out/00-run-phase5.log" "$EV/phase5-regression/" 2>/dev/null || true
    fi
    echo "phase5-regression: $(ls -1 "$EV/phase5-regression" 2>/dev/null | wc -l) files copied" >> "$MAINLOG" 2>&1 || true
  fi
  cat > "$EV/README.txt" <<'EOF'
Phase 7 evidence: workspace isolation + memory + project management, proven on a
fresh emulator against the app's own embedded OpenCode server.
  00-run-phase7.log             the orchestrator log (read this first on failure)
  GATES_SUMMARY.txt             machine-readable verdicts (P7_SUMMARY line)
  p7-static-checks.log          strings/a11y/lazy-list/ascii/kotlin-comment checks
  p7-isolation-instrument.log   W1/W2/W3 gates (workspace boundary + memory)
  p7-chat-ui-instrument.log     U3/U7 regression (permission ask + accessibility)
  p7-live-chat-instrument.log   L1/L2 (live turn + real tool call through the UI)
  p7-ui-lines.txt               every P6_/P7_ verdict line, deduplicated
  screenshots/                  PNGs captured ON DEVICE by the UI gates
  logcat.txt                    filtered logcat (OpenCode + test runner + crashes)
  runtime.log                   the app's own supervisor log (state machine)
  opencode-server.log           the embedded server's own log tail
  phase5-regression/            Phase 5 gate verdicts re-run on the same device
EOF
}

push_evidence() {
  if [ ! -s "$EV/GATES_SUMMARY.txt" ]; then
    { echo "P7_SUMMARY $(date -u +%FT%TZ)"
      echo "ui_gates_pass=0 ui_gates_fail=1 ui_gates_skip=0"
      echo "note=no gate verdicts produced (see 00-run-phase7.log)"
    } > "$EV/GATES_SUMMARY.txt"
  fi
  [ "${P7_SKIP_PUSH:-0}" = "1" ] && { echo "P7_SKIP_PUSH=1; not committing evidence"; return 0; }
  git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "not a git repo; skipping evidence push"; return 0; }
  step "evidence push"
  DEST="${GITHUB_REF_NAME:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)}"
  mkdir -p "$ROOT/docs/progress/phase7-evidence"
  cp -r "$EV/." "$ROOT/docs/progress/phase7-evidence/" 2>/dev/null || true
  export GIT_TERMINAL_PROMPT=0
  timeout 180 git -C "$ROOT" fetch origin "$DEST" >/dev/null 2>&1 || true
  git -C "$ROOT" add -A docs/progress/phase7-evidence/ 2>/dev/null || true
  if ! git -C "$ROOT" diff --cached --quiet; then
    git -C "$ROOT" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
      commit -m "phase7: workspace/memory evidence (auto)" >/dev/null 2>&1 || true
    timeout 180 git -C "$ROOT" pull --rebase origin "$DEST" >/dev/null 2>&1 || true
    timeout 240 git -C "$ROOT" push origin "HEAD:$DEST" > "$OUT/push.out" 2>&1
    local_prec=$?
    tail -3 "$OUT/push.out" 2>/dev/null
    [ "$local_prec" = 0 ] || echo "PUSH_FAILED rc=$local_prec dest=$DEST"
  else
    echo "no evidence changes to commit"
  fi
}

# ---------------------------------------------------------------------------
# Live progress. A CI step that runs for an hour is invisible from the sandbox
# until the job ends, so a hung step and a merely slow one look identical. Every
# 300s we append a snapshot to docs/progress/phase7-evidence/PROGRESS.txt and
# push it. That path is in the workflow's on.push.paths-ignore, so heartbeats
# cannot re-trigger the workflow; a run whose HEAD commit IS a heartbeat disables
# heartbeats, so even a mispathed push cascades at most one extra run; and
# nothing here rewrites tracked files, so it can never disturb the tree Gradle is
# compiling (a rejected push just loses that tick).
HB_FILE_REL="docs/progress/phase7-evidence/PROGRESS.txt"
HB_PID=""
HB_STEP="startup"
HB_DISABLED=0

heartbeat_once() {
  [ "$HB_DISABLED" = 1 ] && return 0
  [ -n "${GITHUB_REF_NAME:-}" ] || return 0
  [ -e /home/runner ] || return 0
  mkdir -p "$(dirname "$ROOT/$HB_FILE_REL")" 2>/dev/null || return 0
  local cur; cur=$(grep -a '^##########' "$MAINLOG" 2>/dev/null | tail -1 | sed 's/^#* *//; s/ *#*$//')
  { echo "### $(date -u +%FT%TZ)  run=${GITHUB_RUN_ID:-?}  step: ${cur:-$HB_STEP}"
    echo "    head=$(git -C "$ROOT" rev-parse --short HEAD) branch=${GITHUB_REF_NAME} log_lines=$(wc -l < "$MAINLOG" 2>/dev/null || echo 0)"
    echo "    --- error digest ---"
    grep -aE '^(e: file:|w: file:.*(unresolved|error)|FAILURE:|BUILD (FAILED|SUCCESSFUL)|FATAL|STEP_(FAILED|TIMEOUT)|> Task .*FAILED|\* What went wrong|P[67]_[A-Z0-9_]+ (PASS|FAIL|SKIP))' \
      "$MAINLOG" 2>/dev/null | tail -40 | sed 's/^/    /'
    echo "    --- last 40 log lines ---"
    tail -40 "$MAINLOG" 2>/dev/null | sed 's/^/    /'
    echo
  } >> "$ROOT/$HB_FILE_REL" 2>/dev/null || return 0
  git -C "$ROOT" add "$HB_FILE_REL" >/dev/null 2>&1 || return 0
  git -C "$ROOT" diff --cached --quiet && return 0
  git -C "$ROOT" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
    commit -q -m "phase7: CI heartbeat (${cur:-$HB_STEP})" >/dev/null 2>&1 || return 0
  GIT_TERMINAL_PROMPT=0 timeout 180 git -C "$ROOT" push -q origin "HEAD:refs/heads/$GITHUB_REF_NAME" >/dev/null 2>&1 \
    || echo "heartbeat push skipped (branch moved or push refused)" >> "$MAINLOG"
  return 0
}

start_heartbeat() {
  if git -C "$ROOT" log -1 --format=%s 2>/dev/null | grep -q '^phase7: CI heartbeat'; then
    HB_DISABLED=1
    echo "heartbeat disabled: this run was itself triggered by a heartbeat commit" | tee -a "$MAINLOG"
    return 0
  fi
  ( while :; do sleep 300; heartbeat_once; done ) >/dev/null 2>&1 &
  HB_PID=$!
  echo "heartbeat enabled (pid $HB_PID, every 300s -> $HB_FILE_REL)" | tee -a "$MAINLOG"
}

stop_heartbeat() {
  [ -n "$HB_PID" ] && { kill "$HB_PID" 2>/dev/null || true; HB_PID=""; }
  heartbeat_once
}

record_fatal() { # $1=reason
  echo "FATAL: $1" | tee -a "$MAINLOG"
  { echo "P7-BUILD FAIL $1"
    echo "phase7 stopped before the gates ran; see 00-run-phase7.log"
  } > "$EV/GATES_SUMMARY.txt"
  grep -aE '^(e: file:|FAILURE:|BUILD FAILED|> Task .*FAILED|\* What went wrong|.*: error:)' "$MAINLOG" 2>/dev/null \
    | tail -200 > "$EV/compiler-errors.txt" || true
  collect_evidence
  stop_heartbeat
  push_evidence 1
  echo "=== PHASE 7 END $(date -u +%FT%TZ) FATAL: $1 ===" | tee -a "$MAINLOG"
  exit 1
}

# ---------------------------------------------------------------------------
# Bring-up mode: compile + JVM unit tests only (no payload, no emulator, no
# device verdicts). Selected by env P7_GRADLE_ONLY=1 or the tracked marker file.
GRADLE_ONLY=0
[ "${P7_GRADLE_ONLY:-0}" = 1 ] && GRADLE_ONLY=1
if [ "$GRADLE_ONLY" = 0 ] && grep -qs '^1' "$DIR/CI_GRADLE_ONLY" 2>/dev/null; then
  GRADLE_ONLY=1
fi
start_heartbeat

step "1/8 static checks (strings, a11y, lazy lists, ASCII, kotlin comments)"
run_c 600 "bash '$DIR/scripts/30-static-checks.sh'" || record_fatal "static checks failed (see p7-static-checks.log)"
cp "$OUT/static-checks.log" "$EV/p7-static-checks.log" 2>/dev/null || true

if [ "$GRADLE_ONLY" = "1" ]; then
  step "gradle-only mode: compile + JVM unit tests (no emulator, no device gates)"
  run_c 3000 "SKIP_PAYLOAD=1 '$ROOT/gradlew' -p '$ROOT' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest -PskipPayload --no-daemon --stacktrace" \
    || record_fatal "gradle compile/unit tests failed (compiler output in 00-run-phase7.log)"
  { echo "P7 MODE gradle-only (compile + unit tests; NO device verdicts in this run)"
    echo "P7_BUILD pass: :app:compileDebugKotlin + :app:compileDebugAndroidTestKotlin + :app:testDebugUnitTest"
    echo "P7_STATIC pass (see p7-static-checks.log)"
    echo "P7_SUMMARY gates not run"
  } > "$EV/GATES_SUMMARY.txt"
  collect_evidence
  stop_heartbeat
  push_evidence 0
  echo "=== PHASE 7 END $(date -u +%FT%TZ) gradle-only ok ====" | tee -a "$MAINLOG"
  exit 0
fi

step "2/8 fast compile + JVM unit tests (fail before the 30-minute payload build)"
run_c 2400 "SKIP_PAYLOAD=1 '$ROOT/gradlew' -p '$ROOT' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest -PskipPayload --no-daemon --stacktrace" \
  || record_fatal "kotlin compile or JVM unit tests failed (compiler output in 00-run-phase7.log and compiler-errors.txt)"

step "3/8 environment + FRESH emulator"
if [ -e /dev/kvm ]; then sudo chmod 666 /dev/kvm 2>/dev/null || true; echo "KVM: $(ls -la /dev/kvm 2>&1)"; else echo "KVM_MISSING"; fi
if [ ! -x "$SDK/emulator/emulator" ] || [ ! -d "$SDK/system-images" ]; then
  echo "installing emulator + system image via phase4/scripts/50-install-sdk.sh" | tee -a "$MAINLOG"
  run_c 1500 "bash '$ROOT/phase4/scripts/50-install-sdk.sh'" \
    || echo "warn: SDK installer reported failure; the probe below decides" | tee -a "$MAINLOG"
fi
# Reuse phase 5's image selection logic verbatim (same script family, so the two
# phases can never drift to different images): newest android-3x/4x x86_64 image,
# preferring google_apis.
IMAGE_DIR=""; BEST=""
for d in $(find "$SDK/system-images" -mindepth 3 -maxdepth 3 -type d 2>/dev/null | sort -r); do
  rel="${d#"$SDK/system-images/"}"
  api="${rel%%/*}"; rest="${rel#*/}"; tag="${rest%%/*}"; abi="${rest#*/}"
  case "$api" in android-3[0-9]|android-4[0-9]) ;; *) continue;; esac
  [ "$abi" != "x86_64" ] && continue
  s=0; case "$tag" in google_apis) s=3;; default) s=2;; *) s=1;; esac
  key="$api-$s"
  if [ -z "$BEST" ] || [ "$key" \> "$BEST" ]; then BEST="$key"; IMAGE_DIR="$rel"; fi
done
[ -z "$IMAGE_DIR" ] && record_fatal "no usable x86_64 system image under $SDK/system-images"
API="${IMAGE_DIR%%/*}"; REST="${IMAGE_DIR#*/}"; TAG="${REST%%/*}"; ABI="${REST#*/}"
export ANDROID_AVD_HOME="$SDK/avd"; mkdir -p "$ANDROID_AVD_HOME"
AVDM="$(find "$SDK" -name avdmanager -type f | head -1)"
echo "system image: $API/$TAG/$ABI" | tee -a "$MAINLOG"
"$AVDM" delete avd -n phase7 >/dev/null 2>&1 || true
rm -rf "$ANDROID_AVD_HOME/phase7.avd" "$ANDROID_AVD_HOME/phase7.ini" 2>/dev/null || true
echo no | "$AVDM" create avd -n phase7 -k "system-images;$API;$TAG;$ABI" --force 2>&1 | tail -3 | tee -a "$MAINLOG"
cp "$ROOT/phase4/scripts/02-boot-emulator.sh" "$OUT/boot-emulator.sh"
sed -i "s|-avd gates|-avd phase7|; s|-log '[^']*'|-log '$OUT/emulator.log'|; s|-no-snapshot|-no-snapshot -wipe-data|" "$OUT/boot-emulator.sh"
run_c 1800 "bash '$OUT/boot-emulator.sh'" || record_fatal "emulator did not boot"
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
adb shell svc power stayon true >/dev/null 2>&1 || true
{ echo "device: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "android_release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "avd=phase7 (created fresh this run, -wipe-data -no-snapshot)"
} > "$EV/device-facts.txt" 2>&1
cat "$EV/device-facts.txt" | tee -a "$MAINLOG"

step "4/8 embedded runtime payload (reuse phase 4's build when present)"
if [ ! -f "$ROOT/phase4/out/engine/assets/runtime-manifest.json" ]; then
  run_c 3600 "bash '$ROOT/phase4/scripts/10-build-payload.sh'" || record_fatal "payload build failed"
else
  echo "reusing phase4/out/engine payload (payloadVersion is checked on device)" | tee -a "$MAINLOG"
fi

step "5/8 APKs (assembleDebug + assembleDebugAndroidTest)"
run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --no-daemon --stacktrace" \
  || record_fatal "gradle build/tests failed (compiler output in 00-run-phase7.log)"
APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"
[ -f "$APK" ] || record_fatal "no debug APK produced"
[ -f "$TAPK" ] || record_fatal "no androidTest APK produced (gates impossible)"
echo "apk=$APK" | tee -a "$MAINLOG"
echo "testApk=$TAPK" | tee -a "$MAINLOG"

step "6/8 Phase 7 gates (workspace isolation + memory + UI regression + live turn)"
GATE_RC=0
run_c 5400 "bash '$DIR/scripts/20-gates.sh'" || GATE_RC=1
echo "phase7 gates rc=$GATE_RC" | tee -a "$MAINLOG"

step "7/8 Phase 5 regression tail (client-integration gates, staged on this device)"
# Phase 5's steady state is 14 PASS / 1 FAIL where the one FAIL is P5-G16, the
# documented upstream remote-MCP restriction (anomalyco/opencode#47644). A Phase
# 7 change that moves any OTHER gate is a regression, so the tail runs by default
# and its verdict is folded into ours as P7-R5. Skip with P7_SKIP_PHASE5=1 when
# iterating on app-only code and the hour matters.
P5_RC=0
if [ "${P7_SKIP_PHASE5:-0}" = "1" ]; then
  echo "P7_SKIP_PHASE5=1: skipping the Phase 5 regression tail" | tee -a "$MAINLOG"
  P5_RC=7
else
  # Phase 5's STAGED mode needs the local stdio MCP fixture (phase4/out/mcp); a
  # Phase 7 run that reuses phase 4's payload never built it (same trap as Phase
  # 6 run 34115663777). Build it here exactly as phase 4 does; a failure is a
  # warning because the gate itself reports the consequence.
  run_c 900 "bash '$ROOT/phase4/scripts/11-build-mcp.sh'" \
    || echo "warn: phase4 MCP fixture build failed (expect P5-K G10_MCP and P5-R-10 to fail)" | tee -a "$MAINLOG"
  run_c 4500 "P5_SKIP_PUSH=1 bash '$ROOT/phase5/scripts/00-run-phase5.sh' --stage-after-phase4" || P5_RC=1
fi
echo "phase5 regression rc=$P5_RC" | tee -a "$MAINLOG"

step "8/8 evidence"
stop_heartbeat
collect_evidence
bash "$DIR/scripts/40-fold-regression.sh" "$P5_RC" >> "$MAINLOG" 2>&1 || true
cat "$EV/GATES_SUMMARY.txt" 2>/dev/null | tee -a "$MAINLOG"
FINAL_RC="$GATE_RC"
FAILS=$(sed -n 's/^ui_gates_pass=[0-9]* ui_gates_fail=\([0-9]*\).*/\1/p' "$EV/GATES_SUMMARY.txt" 2>/dev/null | head -1)
[ "${FAILS:-0}" = "0" ] || FINAL_RC=1
# NB: the raw phase5 rc is NOT folded in directly - P5-G16 (remote MCP) is red by
# design (documented upstream restriction, anomalyco/opencode#47644), and
# 40-fold-regression.sh has already turned "anything else is red" into a P7-R5
# FAIL above.
echo "=== PHASE 7 END $(date -u +%FT%TZ) rc=$FINAL_RC (gates=$GATE_RC phase5_tail_rc=$P5_RC fails=${FAILS:-?}) ===" | tee -a "$MAINLOG"
push_evidence "$FINAL_RC"
exit "$FINAL_RC"
