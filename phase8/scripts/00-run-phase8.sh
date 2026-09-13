#!/usr/bin/env bash
# 00-run-phase8.sh - single orchestrator for the Phase 8 hardening/test suite.
#
# What Phase 8 has to prove, and therefore what this script does:
#   * the Phase 7 and Phase 5 gate suites still pass on this branch (regression,
#     including the loopback/credentials security re-verification);
#   * the product survives real-world failure: a rejected provider key is
#     reported as an auth failure, a SIGKILLed server is restarted by the
#     supervisor with bounded backoff, corrupted payload artifacts are
#     re-extracted, sessions survive process death, network loss mid-task
#     fails the turn with a network-shaped error and recovers, the app
#     survives backgrounding, large projects and histories stay responsive,
#     low storage degrades honestly;
#   * a REAL model round-trip with a real tool call (the Phase 6 L2 carry-over),
#     using the run's short-lived repo-secret key, revoked afterwards;
#   * performance is MEASURED, not guessed (startup, API/shell/file ops,
#     streaming TTFT, memory, CPU, storage);
#   * key residency (secure hardware vs software keystore) is measured per
#     device - the report states exactly what each device proves.
#
# CI_GRADLE_ONLY (env P8_GRADLE_ONLY=1 or the tracked marker file
# phase8/CI_GRADLE_ONLY containing "1") = compile + JVM unit tests only: the
# fast loop for bringing up new Kotlin. It is not a verdict: a gradle-only run
# publishes no device evidence and says so.
#
# Every timeout-bounded step writes to a FILE, never through a pipe (Phase 5
# lesson: a killed `cmd | tee` leaves a surviving JVM holding the pipe open).
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
mkdir -p "$OUT" "$EV"
MAINLOG="$OUT/00-run-phase8.log"
: > "$MAINLOG"

PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"

step() { HB_STEP="$1"; echo; echo "########## $1 ##########" | tee -a "$MAINLOG"; }

run_c() {
  local t="$1"; shift
  local logf="$OUT/step.$$.out"
  timeout -k 60 "$t" bash -c "${*}" > "$logf" 2>&1
  local rc=$?
  tail -c 200000 "$logf" 2>/dev/null
  cat "$logf" >> "$MAINLOG" 2>/dev/null
  rm -f "$logf" 2>/dev/null
  if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then
    echo "STEP_TIMEOUT rc=$rc after ${t}s (killed): ${*}" | tee -a "$MAINLOG"
  fi
  [ "$rc" = 0 ] || { echo "STEP_FAILED rc=$rc: ${*}" | tee -a "$MAINLOG"; return 1; }
  return 0
}

echo "=== PHASE 8 START $(date -u +%FT%TZ) ===" | tee -a "$MAINLOG"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"

collect_evidence() {
  cp "$MAINLOG" "$EV/00-run-phase8.log" 2>/dev/null || true
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
  cat > "$EV/README.txt" <<'EOF'
Phase 8 evidence: hardening + test matrix, proven on a fresh emulator (plus the
real-device suite the user runs from their own machine - see
phase8/scripts/90-real-device-suite.sh and the report).
  00-run-phase8.log             the orchestrator log (read this first on failure)
  GATES_SUMMARY.txt             machine-readable verdicts (P8_SUMMARY line)
  p8-static-checks.log          strings/a11y/lazy-list/ascii/kotlin-comment checks
  p8-stress-instrument.log      stress/recovery gates (KEYRESIDENCY PROVAUTH SERVERKILL LIFECYCLELOG)
  p8-live-instrument.log        live model gates (KEYPROBE TOOL CLEANUP) - the L2 close
  p8-*-verdicts.txt             per-class verdict files (the gates' own channel)
  p8-lines.txt                  every P8_ verdict line, deduplicated
  screenshots/                  PNGs captured ON DEVICE by the UI gates
  runtime.log                   the app's own supervisor log (state machine)
  opencode-server.log           the embedded server's own log tail
  p8-meminfo-*.txt p8-cpu-*.txt p8-storage-footprint.txt   measured perf
  phase7-regression/            Phase 7 gate verdicts re-run on the same device
  phase5-regression/            Phase 5 gate verdicts re-run (security re-verification)
  logcat.txt                    filtered logcat
EOF
}

push_evidence() {
  if [ ! -s "$EV/GATES_SUMMARY.txt" ]; then
    { echo "P8_SUMMARY $(date -u +%FT%TZ)"
      echo "gates_pass=0 gates_fail=1 gates_skip=0"
      echo "note=no gate verdicts produced (see 00-run-phase8.log)"
    } > "$EV/GATES_SUMMARY.txt"
  fi
  [ "${P8_SKIP_PUSH:-0}" = "1" ] && { echo "P8_SKIP_PUSH=1; not committing evidence"; return 0; }
  git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "not a git repo; skipping evidence push"; return 0; }
  step "evidence push"
  DEST="${GITHUB_REF_NAME:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)}"
  mkdir -p "$ROOT/docs/progress/phase8-evidence"
  cp -r "$EV/." "$ROOT/docs/progress/phase8-evidence/" 2>/dev/null || true
  export GIT_TERMINAL_PROMPT=0
  timeout 180 git -C "$ROOT" fetch origin "$DEST" >/dev/null 2>&1 || true
  git -C "$ROOT" add -A docs/progress/phase8-evidence/ 2>/dev/null || true
  if ! git -C "$ROOT" diff --cached --quiet; then
    git -C "$ROOT" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
      commit -m "phase8: hardening/test evidence (auto)" >/dev/null 2>&1 || true
    timeout 180 git -C "$ROOT" pull --rebase origin "$DEST" >/dev/null 2>&1 || true
    timeout 240 git -C "$ROOT" push origin "HEAD:$DEST" > "$OUT/push.out" 2>&1
    local rc
    rc=$?
    tail -3 "$OUT/push.out" 2>/dev/null
    [ "$rc" = 0 ] || echo "PUSH_FAILED rc=$rc dest=$DEST"
  else
    echo "no evidence changes to commit"
  fi
}

# ---------------------------------------------------------------------------
# Live progress (Phase 7's heartbeat, same invariants: the evidence path is
# paths-ignored, a heartbeat-triggered run disables its own heartbeat, and no
# tracked file other than the progress file is ever rewritten).
HB_FILE_REL="docs/progress/phase8-evidence/PROGRESS.txt"
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
    grep -aE '^(e: file:|w: file:.*(unresolved|error)|FAILURE:|BUILD (FAILED|SUCCESSFUL)|FATAL|STEP_(FAILED|TIMEOUT)|> Task .*FAILED|\* What went wrong|P[5678]_[A-Z0-9_]+ (PASS|FAIL|SKIP))' \
      "$MAINLOG" 2>/dev/null | tail -40 | sed 's/^/    /'
    echo "    --- last 40 log lines ---"
    tail -40 "$MAINLOG" 2>/dev/null | sed 's/^/    /'
    echo
  } >> "$ROOT/$HB_FILE_REL" 2>/dev/null || return 0
  git -C "$ROOT" add "$HB_FILE_REL" >/dev/null 2>&1 || return 0
  git -C "$ROOT" diff --cached --quiet && return 0
  git -C "$ROOT" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
    commit -q -m "phase8: CI heartbeat (${cur:-$HB_STEP})" >/dev/null 2>&1 || return 0
  GIT_TERMINAL_PROMPT=0 timeout 180 git -C "$ROOT" push -q origin "HEAD:refs/heads/$GITHUB_REF_NAME" >/dev/null 2>&1 \
    || echo "heartbeat push skipped (branch moved or push refused)" >> "$MAINLOG"
  return 0
}

start_heartbeat() {
  if git -C "$ROOT" log -1 --format=%s 2>/dev/null | grep -q '^phase8: CI heartbeat'; then
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
  { echo "P8-BUILD FAIL $1"
    echo "phase8 stopped before the gates ran; see 00-run-phase8.log"
  } > "$EV/GATES_SUMMARY.txt"
  grep -aE '^(e: file:|FAILURE:|BUILD FAILED|> Task .*FAILED|\* What went wrong|.*: error:)' "$MAINLOG" 2>/dev/null \
    | tail -200 > "$EV/compiler-errors.txt" || true
  collect_evidence
  stop_heartbeat
  push_evidence 1
  echo "=== PHASE 8 END $(date -u +%FT%TZ) FATAL: $1 ===" | tee -a "$MAINLOG"
  exit 1
}

# ---------------------------------------------------------------------------
# Bring-up mode: compile + JVM unit tests only (no payload, no emulator, no
# device verdicts).
GRADLE_ONLY=0
[ "${P8_GRADLE_ONLY:-0}" = "1" ] && GRADLE_ONLY=1
if [ "$GRADLE_ONLY" = "0" ] && grep -qs '^1' "$DIR/CI_GRADLE_ONLY" 2>/dev/null; then
  GRADLE_ONLY=1
fi
start_heartbeat

step "1/7 static checks (strings, a11y, lazy lists, ASCII, kotlin comments)"
run_c 600 "bash '$DIR/scripts/30-static-checks.sh'" || record_fatal "static checks failed (see p8-static-checks.log)"
cp "$OUT/static-checks.log" "$EV/p8-static-checks.log" 2>/dev/null || true

if [ "$GRADLE_ONLY" = "1" ]; then
  step "gradle-only mode: compile + JVM unit tests (no emulator, no device gates)"
  run_c 3000 "SKIP_PAYLOAD=1 '$ROOT/gradlew' -p '$ROOT' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest -PskipPayload --no-daemon --stacktrace" \
    || record_fatal "gradle compile/unit tests failed (compiler output in 00-run-phase8.log)"
  { echo "P8 MODE gradle-only (compile + unit tests; NO device verdicts in this run)"
    echo "P8_BUILD pass: :app:compileDebugKotlin + :app:compileDebugAndroidTestKotlin + :app:testDebugUnitTest"
    echo "P8_STATIC pass (see p8-static-checks.log)"
    echo "P8_SUMMARY gates not run"
  } > "$EV/GATES_SUMMARY.txt"
  collect_evidence
  stop_heartbeat
  push_evidence 0
  echo "=== PHASE 8 END $(date -u +%FT%TZ) gradle-only ok ===" | tee -a "$MAINLOG"
  exit 0
fi

step "2/7 fast compile + JVM unit tests (fail before the 30-minute payload build)"
run_c 2400 "SKIP_PAYLOAD=1 '$ROOT/gradlew' -p '$ROOT' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest -PskipPayload --no-daemon --stacktrace" \
  || record_fatal "kotlin compile or JVM unit tests failed (compiler output in 00-run-phase8.log and compiler-errors.txt)"

step "3/7 environment + FRESH emulator"
if [ -e /dev/kvm ]; then sudo chmod 666 /dev/kvm 2>/dev/null || true; echo "KVM: $(ls -la /dev/kvm 2>&1)"; else echo "KVM_MISSING"; fi
if [ ! -x "$SDK/emulator/emulator" ] || [ ! -d "$SDK/system-images" ]; then
  echo "installing emulator + system image via phase4/scripts/50-install-sdk.sh" | tee -a "$MAINLOG"
  run_c 1500 "bash '$ROOT/phase4/scripts/50-install-sdk.sh'" \
    || echo "warn: SDK installer reported failure; the probe below decides" | tee -a "$MAINLOG"
fi
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
"$AVDM" delete avd -n phase8 >/dev/null 2>&1 || true
rm -rf "$ANDROID_AVD_HOME/phase8.avd" "$ANDROID_AVD_HOME/phase8.ini" 2>/dev/null || true
echo no | "$AVDM" create avd -n phase8 -k "system-images;$API;$TAG;$ABI" --force 2>&1 | tail -3 | tee -a "$MAINLOG"
cp "$ROOT/phase4/scripts/02-boot-emulator.sh" "$OUT/boot-emulator.sh"
sed -i "s|-avd gates|-avd phase8|; s|-log '[^']*'|-log '$OUT/emulator.log'|; s|-no-snapshot|-no-snapshot -wipe-data|" "$OUT/boot-emulator.sh"
run_c 1800 "bash '$OUT/boot-emulator.sh'" || record_fatal "emulator did not boot"
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
adb shell svc power stayon true >/dev/null 2>&1 || true
{ echo "device: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "android_release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "avd=phase8 (created fresh this run, -wipe-data -no-snapshot)"
} > "$EV/device-facts.txt" 2>&1
cat "$EV/device-facts.txt" | tee -a "$MAINLOG"

step "4/7 embedded runtime payload (reuse phase 4's build when present)"
if [ ! -f "$ROOT/phase4/out/engine/assets/runtime-manifest.json" ]; then
  run_c 3600 "bash '$ROOT/phase4/scripts/10-build-payload.sh'" || record_fatal "payload build failed"
else
  echo "reusing phase4/out/engine payload (payloadVersion is checked on device)" | tee -a "$MAINLOG"
fi

step "5/7 APKs (assembleDebug + assembleDebugAndroidTest + unit tests)"
run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --no-daemon --stacktrace" \
  || record_fatal "gradle build/tests failed (compiler output in 00-run-phase8.log)"
APK="$(ls "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)"
TAPK="$(ls "$ROOT/app/build/outputs/apk/androidTest/debug/"*.apk 2>/dev/null | head -1)"
[ -f "$APK" ] || record_fatal "no debug APK produced"
[ -f "$TAPK" ] || record_fatal "no androidTest APK produced (gates impossible)"
echo "apk=$APK" | tee -a "$MAINLOG"
echo "testApk=$TAPK" | tee -a "$MAINLOG"

step "6/7 Phase 8 gates (regressions + stress + live model + perf)"
GATE_RC=0
run_c 30000 "bash '$DIR/scripts/20-gates.sh'" || GATE_RC=1
echo "phase8 gates rc=$GATE_RC" | tee -a "$MAINLOG"

step "7/7 evidence"
stop_heartbeat
collect_evidence
cat "$EV/GATES_SUMMARY.txt" 2>/dev/null | tee -a "$MAINLOG"
FINAL_RC="$GATE_RC"
FAILS=$(sed -n 's/^gates_pass=[0-9]* gates_fail=\([0-9]*\).*/\1/p' "$EV/GATES_SUMMARY.txt" 2>/dev/null | head -1)
[ "${FAILS:-0}" = "0" ] || FINAL_RC=1
echo "=== PHASE 8 END $(date -u +%FT%TZ) rc=$FINAL_RC (gates=$GATE_RC fails=${FAILS:-?}) ===" | tee -a "$MAINLOG"
push_evidence "$FINAL_RC"
exit "$FINAL_RC"
