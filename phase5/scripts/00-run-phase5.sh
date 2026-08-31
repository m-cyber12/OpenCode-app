#!/usr/bin/env bash
# 00-run-phase5.sh — single orchestrator for the Phase 5 OpenCode-integration
# gates (app-as-client) on a GitHub Actions runner with KVM + the Android SDK.
#
# Two ways to run:
#   --stage-after-phase4   the emulator is already booted and the debug APK is
#                          already installed by phase4/scripts/00-run-phase4.sh
#                          (that is how CI reaches this today: the Phase 4 suite
#                          tail-calls this one, so the payload/APK/emulator work
#                          is not repeated).
#   (no flags)             standalone: build the payload if needed, build both
#                          APKs, boot an emulator, install, then run the gates.
#
# Every timeout-bounded step is recorded, and evidence is committed + pushed on
# EVERY exit path — including a failed Gradle build, whose compiler output is the
# only artifact anyone will want to read in that case (phase 4 lesson: never let
# an early failure swallow the log).
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
STAGED=0
for a in "$@"; do
  case "$a" in
    --stage-after-phase4) STAGED=1 ;;
    *) echo "ignoring unknown argument: $a" ;;
  esac
done
# Fast-iteration mode. The Actions console is the normal way to read a build
# failure, but it is not reachable from the sandbox this work is driven from, so
# verdicts and logs are published back through git instead. While the client code
# is being brought up, a tracked marker file (phase5/CI_GRADLE_ONLY containing
# "1") makes a standalone run compile + unit-test only: no payload build, no
# emulator, no gates. Delete/zero the marker for a full device run.
GRADLE_ONLY=0
[ "${P5_GRADLE_ONLY:-0}" = 1 ] && GRADLE_ONLY=1
if [ "$GRADLE_ONLY" = 0 ] && grep -qs '^1' "$DIR/CI_GRADLE_ONLY" 2>/dev/null; then
  GRADLE_ONLY=1
fi
if [ "$STAGED" = 1 ]; then GRADLE_ONLY=0; fi
mkdir -p "$OUT" "$EV"
MAINLOG="$OUT/00-run-phase5.log"
: > "$MAINLOG"
GATE_RC=0
step() { HB_STEP="$1"; echo; echo "########## $1 ##########" | tee -a "$MAINLOG"; }
run_c() { timeout "$1" bash -c "${*:2}" 2>&1 | tee -a "$MAINLOG"; local rc=${PIPESTATUS[0]}; [ "$rc" = 0 ] || { echo "STEP_FAILED rc=$rc: ${*:2}" | tee -a "$MAINLOG"; return 1; }; return 0; }
PKG="ai.opencode.android.debug"

echo "=== PHASE 5 START $(date -u +%FT%TZ) staged=$STAGED ===" | tee -a "$MAINLOG"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"

# ---- evidence collection + push (used by every exit path) ------------------
collect_evidence() {
  cp "$MAINLOG" "$EV/00-run-phase5.log" 2>/dev/null || true
  cp "$OUT/emulator.log" "$EV/" 2>/dev/null || true
  cp "$ROOT/phase4/out/engine/assets/runtime-manifest.json" "$EV/runtime-manifest.json" 2>/dev/null || true
  # JVM unit-test results are supporting evidence only (never runtime evidence).
  mkdir -p "$EV/jvm-unit-tests"
  for f in "$ROOT"/app/build/test-results/testDebugUnitTest/*.xml; do
    if [ -e "$f" ]; then cp "$f" "$EV/jvm-unit-tests/" 2>/dev/null || true; fi
  done
  adb logcat -d 2>/dev/null | grep -aE "OpenCode|AndroidRuntime|FATAL|bun" > "$EV/logcat.txt" 2>&1 || true
  cat > "$EV/README.txt" <<'EOF'
Phase 5 evidence: the Android app driving the on-device OpenCode server as a
real client (loopback-only binding, OpenCode's own API/events/permissions/MCP,
Keystore-held credentials).
  00-run-phase5.log             the orchestrator log (read this first on failure)
  p5-01-device.txt              device + installed-app facts
  p5-04-instrument-export.txt   Keystore password exported for the host drivers
  p5-k-instrument.log           Kotlin instrumented client gates (K1..K8) output
  p5-k-gates.log                per-gate P5_* PASS/FAIL lines from the app process
  rerun-gate-*.log              phase-4 gate drivers re-run verbatim (G6/G7/G10/G11/G12)
  p5-16-mcp-remote.log          remote MCP transports (StreamableHTTP + SSE + failure case)
  p5-16-fixture-host.log        the host-side MCP server used by that gate
  p5-17-loopback.txt            /proc/net/tcp{,6} + udp audit, launcher bind lines
  p5-17-external-connect.txt    device-side socket probe: loopback open, external refused
  p5-18-credentials.txt         credentials at rest (ciphertext blobs, auth.json mode)
  p5-19-secret-scan.txt         APK/payload/source secret scan results
  jvm-unit-tests/               JVM unit tests (supporting, not runtime, evidence)
  GATES_SUMMARY.txt             machine-readable verdicts (P5_SUMMARY line)
EOF
}

push_evidence() { # $1=exit-code-to-report
  if [ ! -s "$EV/GATES_SUMMARY.txt" ]; then
    { echo "P5_SUMMARY $(date -u +%FT%TZ)"; echo "gates_pass=0 gates_fail=1 gates_skip=0"; echo "note=no gate verdicts produced (see 00-run-phase5.log)"; } > "$EV/GATES_SUMMARY.txt"
  fi
  [ "${P5_SKIP_PUSH:-0}" = "1" ] && { echo "P5_SKIP_PUSH=1; not committing evidence"; return 0; }
  git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "not a git repo; skipping evidence push"; return 0; }
  step "evidence push"
  DEST="${GITHUB_REF_NAME:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)}"
  mkdir -p "$ROOT/docs/progress/phase5-evidence"
  cp -r "$EV/." "$ROOT/docs/progress/phase5-evidence/" 2>/dev/null || true
  git -C "$ROOT" fetch origin "$DEST" >/dev/null 2>&1 || true
  git -C "$ROOT" add -A docs/progress/phase5-evidence/ 2>/dev/null || true
  if ! git -C "$ROOT" diff --cached --quiet; then
    git -C "$ROOT" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
      commit -m "phase5: client-integration evidence (auto)" >/dev/null 2>&1 || true
    git -C "$ROOT" pull --rebase origin "$DEST" >/dev/null 2>&1 || true
    git -C "$ROOT" push origin "HEAD:$DEST" 2>&1 | tail -3 || echo "PUSH_FAILED dest=$DEST"
  else
    echo "no evidence changes to commit"
  fi
}

# ---------------------------------------------------------------------------
# Live progress. A CI step that takes tens of minutes is invisible from the
# development sandbox until the job ends (its log/artifact bodies are served from
# hosts the sandbox cannot reach), and a hung step is indistinguishable from a slow
# one. So every few minutes we commit a small PROGRESS.txt - the log tail - into the
# evidence directory and push it. That directory is in the workflow's paths-ignore,
# so these commits cannot re-trigger the workflow, and the push happens from a
# linked worktree so it never touches the tree Gradle is building.
HB_WT="/tmp/phase5-heartbeat-wt"
HB_LAST=""
heartbeat_once() {
  [ -n "${GITHUB_REF_NAME:-}" ] || return 0
  [ -e /home/runner ] || return 0
  { echo "phase5 CI heartbeat  $(date -u +%FT%TZ)  (run ${GITHUB_RUN_ID:-?}, step: ${HB_STEP:-?})"
    echo "branch=${GITHUB_REF_NAME}  head=$(git -C "$ROOT" rev-parse --short HEAD)"
    echo "log lines so far=$(wc -l < "$MAINLOG" 2>/dev/null || echo 0)"
    echo
    echo "--- last 45 lines of the orchestrator log ---"
    tail -45 "$MAINLOG" 2>/dev/null
  } > "$HB_WT/PROGRESS.txt" 2>/dev/null || return 0
  local h; h=$(md5sum "$HB_WT/PROGRESS.txt" 2>/dev/null | cut -c1-12) || return 0
  [ "$h" = "$HB_LAST" ] && return 0
  HB_LAST="$h"
  git -C "$HB_WT" add PROGRESS.txt >/dev/null 2>&1 || return 0
  git -C "$HB_WT" -c user.name="arena-ai-coding-agent[bot]" \
      -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
      commit -q -m "phase5: CI heartbeat ($HB_STEP)" >/dev/null 2>&1 || return 0
  git -C "$HB_WT" fetch -q origin "$GITHUB_REF_NAME" >/dev/null 2>&1 && \
    git -C "$HB_WT" rebase -q "FETCH_HEAD" >/dev/null 2>&1 || true
  git -C "$HB_WT" push -q origin "HEAD:refs/heads/$GITHUB_REF_NAME" >/dev/null 2>&1 || true
  return 0
}
start_heartbeat() {
  [ -n "${GITHUB_REF_NAME:-}" ] || return 0
  [ -e /home/runner ] || return 0
  rm -rf "$HB_WT" 2>/dev/null
  git -C "$ROOT" worktree add --detach "$HB_WT" HEAD >/dev/null 2>&1 || { echo "heartbeat: no worktree"; return 0; }
  ( while :; do sleep 240; heartbeat_once; done ) >/dev/null 2>&1 &
  HB_PID=$!
  echo "heartbeat enabled (pid $HB_PID, every 240s -> docs/progress/phase5-evidence/PROGRESS.txt)"
}
stop_heartbeat() {
  if [ -n "${HB_PID:-}" ]; then kill "$HB_PID" 2>/dev/null || true; HB_PID=""; fi
  heartbeat_once   # one last snapshot at each phase boundary
  git -C "$ROOT" worktree remove --force "$HB_WT" >/dev/null 2>&1 || true
  git -C "$ROOT" worktree prune >/dev/null 2>&1 || true
}

# A fatal pre-gate failure still records + publishes, so a red run is readable
# from the repo and not only from the Actions console.
record_fatal() { # $1=reason
  echo "FATAL: $1" | tee -a "$MAINLOG"
  { echo "P5-BUILD FAIL $1"; echo "phase5 stopped before the gates ran; see 00-run-phase5.log"; } > "$EV/GATES_SUMMARY.txt"
  collect_evidence
  stop_heartbeat
  push_evidence 1
  echo "=== PHASE 5 END $(date -u +%FT%TZ) FATAL: $1 ===" | tee -a "$MAINLOG"
  exit 1
}

start_heartbeat

if [ "$GRADLE_ONLY" = "1" ]; then
  step "gradle-only mode: compile + JVM unit tests (no emulator, no device gates)"
  run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --no-daemon --stacktrace" \
    || record_fatal "gradle build/tests failed (compiler output in 00-run-phase5.log)"
  { echo "P5 MODE gradle-only (compile + unit tests; no device verdicts in this run)"
    echo "P5_BUILD pass: :app:assembleDebug + :app:assembleDebugAndroidTest + :app:testDebugUnitTest"
    echo "P5_SUMMARY gates not run"
  } > "$EV/GATES_SUMMARY.txt"
  collect_evidence
  stop_heartbeat
  push_evidence 0
  echo "=== PHASE 5 END $(date -u +%FT%TZ) gradle-only ok ===" | tee -a "$MAINLOG"
  exit 0
fi

if [ "$STAGED" = "0" ]; then
  step "1/6 environment + emulator (standalone mode)"
  if [ -e /dev/kvm ]; then sudo chmod 666 /dev/kvm 2>/dev/null || true; echo "KVM: $(ls -la /dev/kvm 2>&1)"; else echo "KVM_MISSING"; fi
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
  echo no | "$AVDM" create avd -n phase5 -k "system-images;$API;$TAG;$ABI" --force 2>&1 | tail -3 | tee -a "$MAINLOG"
  # Phase 4's proven boot script, pointed at this phase's AVD name + log path.
  cp "$ROOT/phase4/scripts/02-boot-emulator.sh" "$OUT/boot-emulator.sh"
  sed -i "s|-avd gates|-avd phase5|; s|-log '[^']*'|-log '$OUT/emulator.log'|" "$OUT/boot-emulator.sh"
  run_c 1500 "bash '$OUT/boot-emulator.sh'" || record_fatal "emulator did not boot"

  step "2/6 payload (reuse phase 4's build when it is present)"
  if [ ! -f "$ROOT/phase4/out/engine/assets/runtime-manifest.json" ]; then
    run_c 3600 "bash '$ROOT/phase4/scripts/10-build-payload.sh'" || record_fatal "payload build failed"
  else
    echo "reusing phase4/out/engine payload (payloadVersion is checked on device)" | tee -a "$MAINLOG"
  fi
  run_c 900 "bash '$ROOT/phase4/scripts/11-build-mcp.sh'" || echo "warn: phase4 MCP build failed (G10 halves may skip)" | tee -a "$MAINLOG"

  step "3/6 APKs + JVM unit tests"
  run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --no-daemon --stacktrace" \
    || record_fatal "gradle build/tests failed (compiler output in 00-run-phase5.log)"
  APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
  TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"
  [ -f "$APK" ] || record_fatal "no debug APK produced"
  [ -n "$TAPK" ] || record_fatal "no androidTest APK produced (instrumented client gates impossible)"
  adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$MAINLOG"
  adb install -r -g "$TAPK" 2>&1 | tail -2 | tee -a "$MAINLOG"
  adb logcat -c 2>/dev/null || true
else
  step "1/6 staged mode: device + payload are phase 4's; build only what Phase 5 needs"
  adb devices 2>&1 | tee -a "$MAINLOG"
  adb shell pm list packages 2>/dev/null | grep ai.opencode | tee -a "$MAINLOG" || echo "warn: app package not found"
  APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
  echo "app apk: ${APK:-none} (built + installed by the phase 4 stage)" | tee -a "$MAINLOG"

  step "2/6 (staged) nothing to rebuild before the test APK"
  :

  step "3/6 androidTest APK + JVM unit tests"
  run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --no-daemon --stacktrace" \
    || record_fatal "gradle build/tests failed (compiler output in 00-run-phase5.log)"
  tapk="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"
  [ -n "$tapk" ] || record_fatal "no androidTest APK produced; instrumented gates impossible"
  adb install -r -g "$tapk" 2>&1 | tail -2 | tee -a "$MAINLOG"
  adb logcat -c 2>/dev/null || true
fi

step "4/6 host-side remote MCP fixture (pinned MCP SDK)"
run_c 900 "bash '$DIR/scripts/11-build-remote-mcp.sh'" || echo "warn: remote MCP fixture build failed (P5-G16 records the reason)" | tee -a "$MAINLOG"

step "5/6 phase 5 integration gates"
# Gates run with their own exit code captured here; this script deliberately
# never enables `set -e` so evidence collection always happens (phase 4 lesson).
bash "$DIR/scripts/20-integration-gates.sh" 2>&1 | tee -a "$MAINLOG"
GATE_RC=${PIPESTATUS[0]}
echo "phase5 gates rc=$GATE_RC" | tee -a "$MAINLOG"

step "6/6 evidence"
stop_heartbeat
collect_evidence
echo "=== PHASE 5 END $(date -u +%FT%TZ) gates_rc=$GATE_RC ===" | tee -a "$MAINLOG"
cat "$EV/GATES_SUMMARY.txt" 2>/dev/null | tee -a "$MAINLOG"
push_evidence "$GATE_RC"
exit "$GATE_RC"
