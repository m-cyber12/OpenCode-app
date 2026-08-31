#!/usr/bin/env bash
# 00-run-phase4.sh — single orchestrator for the Phase 4 production runtime
# host gates on a GH Actions runner (full network + KVM + preinstalled Android
# SDK). Builds the embedded payload + APK, boots an emulator, installs the REAL
# app, and runs the host + OpenCode gates against it. Evidence (logs + summary)
# lands in phase4/out/evidence/ and is committed by the workflow.
#
# Lessons from Phases 2/3 applied here:
#   * fix /dev/kvm permissions; prefer the preinstalled SDK;
#   * never swallow exit codes in piped steps (run_c checks PIPESTATUS);
#   * bound every device-wait loop with a timeout;
#   * commit evidence even on failure (workflow if: always()).
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
mkdir -p "$OUT" "$EV"
MAINLOG="$OUT/00-run-phase4.log"
: > "$MAINLOG"
step() { echo; echo "########## $1 ##########"; echo "########## $1 ##########" >> "$MAINLOG"; }
# GitHub Actions logs are not downloadable from the development sandbox this work
# is driven from, so a failed step would otherwise be invisible. On any failure we
# publish a small digest (error-grep + log tail) straight back to the running branch
# through git. Only inside Actions, only on failures, never on a branch we do not own.
FAILURE_PUBLISHED=0
report_failure() {
  [ "${SELF_PUSH:-0}" = 1 ] || return 0
  [ -n "${GITHUB_REF_NAME:-}" ] || return 0
  [ -e /home/runner ] || return 0
  [ "$FAILURE_PUBLISHED" = 1 ] && return 0
  FAILURE_PUBLISHED=1
  local FD="$REPO/docs/progress/ci-failure-digest"
  mkdir -p "$FD"
  { echo "phase4 stage failed: rc=$2"
    echo "commit=$(git -C "$REPO" rev-parse HEAD 2>/dev/null)"
    echo "ref=${GITHUB_REF_NAME}"
    echo "step: $1"
  } > "$FD/CI_FAILURE.txt"
  grep -aE '^(e: file:|FAILURE:|BUILD FAILED|> Task .*FAILED|FATAL|.*: error:|.*: UNRESOLVED |.*Caused by:)' "$MAINLOG" 2>/dev/null | tail -150 > "$FD/errors.txt" || true
  tail -500 "$MAINLOG" > "$FD/log-tail.txt" 2>/dev/null || true
  git -C "$REPO" add "$FD" >/dev/null 2>&1 || true
  git -C "$REPO" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
    commit -q -m "ci: phase4 failure digest (auto)" >/dev/null 2>&1 || true
  git -C "$REPO" fetch -q origin "$GITHUB_REF_NAME" >/dev/null 2>&1 && \
    git -C "$REPO" rebase -q "FETCH_HEAD" >/dev/null 2>&1 || true
  git -C "$REPO" push -q origin "HEAD:refs/heads/$GITHUB_REF_NAME" >/dev/null 2>&1 \
    && echo "CI_FAILURE_PUBLISHED=docs/progress/ci-failure-digest (commit $(git -C "$REPO" rev-parse --short HEAD))" | tee -a "$MAINLOG"
  return 0
}

# See the phase5 orchestrator for why this writes to a file instead of piping to
# tee (a killed build's surviving JVM holds the pipe open and the step never ends)
# and why -k is passed (SIGTERM-ignoring children).
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
  [ "$rc" = 0 ] || { echo "STEP_FAILED rc=$rc: ${*}" | tee -a "$MAINLOG"; report_failure "${*}" "$rc"; exit 1; }
  return 0
}
run() { "$@" 2>&1 | tee -a "$MAINLOG"; }

echo "=== PHASE 4 START $(date -u +%FT%TZ) ===" | tee -a "$MAINLOG"
if [ -n "${OPENROUTER_API_KEY:-}" ]; then echo "model key present (${#OPENROUTER_API_KEY} chars)" | tee -a "$MAINLOG"; fi

step "1/9 environment probe"
run uname -a
run nproc
if [ -e /dev/kvm ]; then sudo chmod 666 /dev/kvm 2>/dev/null || true; echo "KVM: $(ls -la /dev/kvm)"; else echo "KVM_MISSING"; fi
java -version 2>&1 | tee -a "$MAINLOG"

step "2/9 Android SDK resolution (preinstalled preferred)"
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
echo "ANDROID_HOME=$ANDROID_HOME" | tee -a "$MAINLOG"
run ls "$ANDROID_HOME" || echo "no preinstalled SDK"
HAS_EMU=0; HAS_IMG=0
[ -x "$ANDROID_HOME/emulator/emulator" ] && HAS_EMU=1
[ -d "$ANDROID_HOME/system-images" ] && HAS_IMG=1
if [ "$HAS_EMU" = 0 ] || [ "$HAS_IMG" = 0 ]; then
  run_c 1500 "bash '$DIR/scripts/50-install-sdk.sh'"
fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
run adb version

step "3/9 pick system image + create AVD (x86_64 google_apis, highest API)"
IMAGE_DIR=""; BEST=""
for d in $(find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -type d 2>/dev/null | sort -r); do
  rel="${d#"$ANDROID_HOME/system-images/"}"
  api="${rel%%/*}"; rest="${rel#*/}"; tag="${rest%%/*}"; abi="${rest#*/}"
  case "$api" in android-3[0-9]|android-4[0-9]|android-1[0-9]) ;; *) continue;; esac
  [ "$abi" != "x86_64" ] && continue
  s=0; case "$tag" in google_apis) s=3;; default) s=2;; *) s=1;; esac
  key="$api-$s"
  if [ -z "$BEST" ] || [ "$key" \> "$BEST" ]; then BEST="$key"; IMAGE_DIR="$rel"; fi
done
[ -z "$IMAGE_DIR" ] && { echo "FATAL: no usable x86_64 system image"; exit 1; }
echo "selected image: $IMAGE_DIR" | tee -a "$MAINLOG"
API="${IMAGE_DIR%%/*}"; REST="${IMAGE_DIR#*/}"; TAG="${REST%%/*}"; ABI="${REST#*/}"
export ANDROID_AVD_HOME="$ANDROID_HOME/avd"; mkdir -p "$ANDROID_AVD_HOME"
AVDM="$(find "$ANDROID_HOME" -name avdmanager -type f | head -1)"
echo no | "$AVDM" create avd -n phase4 -k "system-images;$API;$TAG;$ABI" --force 2>&1 | tail -3 | tee -a "$MAINLOG"

step "4/9 boot emulator"
sed -i 's/-avd gates/-avd phase4/' "$DIR/scripts/02-boot-emulator.sh"
run_c 1400 "bash '$DIR/scripts/02-boot-emulator.sh'"

step "5/9 build the embedded runtime payload (bun/git/rg + OpenCode bundle)"
# Build both production ABIs. The emulator below exercises x86_64, while the
# arm64-v8a artifacts are required for the first real-device product path and
# are verified by Gradle/APK packaging in the same run.
run_c 3600 "bash '$DIR/scripts/10-build-payload.sh'"
run_c 900 "bash '$DIR/scripts/11-build-mcp.sh'"
ls -la "$DIR/out/engine/jniLibs/x86_64" "$DIR/out/engine/assets" 2>&1 | tee -a "$MAINLOG"

step "6/9 build the APK + run JVM unit tests"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# Cold first run downloads Gradle + all AGP/Compose deps; give it room.
run_c 3000 "'$REPO/gradlew' -p '$REPO' :app:assembleDebug :app:testDebugUnitTest --no-daemon --stacktrace"
APK="$(ls "$REPO/app/build/outputs/apk/debug/"*.apk | head -1)"
[ -f "$APK" ] || { echo "FATAL: APK not produced"; exit 1; }
echo "APK: $APK ($(stat -c%s "$APK") bytes)" | tee -a "$MAINLOG"

step "7/9 verify APK contents, install the real app + launch"
# Ground-truth: confirm the payload asset + exec libs are actually INSIDE the
# APK (a srcDir glitch left the asset out -> on-device FileNotFound).
{
  echo "--- APK lib/ entries ---"; unzip -l "$APK" | grep "lib/" || echo "MISSING lib/"
  echo "--- APK asset entries ---"; unzip -l "$APK" | grep -E "assets/runtime" || echo "MISSING assets/runtime"
} | tee "$EV/apk-contents.txt"
# AAPT may store the gzipped payload decompressed as assets/runtime-payload.tar
# (zip re-compresses it); the app's extractor accepts both names and both
# encodings, so the packaging check matches either.
unzip -l "$APK" | grep -qE "assets/runtime-payload\.tar(\.gz)?$" \
  || { echo "FATAL: runtime-payload asset not packaged in APK"; exit 1; }
unzip -l "$APK" | grep -q "lib/x86_64/libbun.so" \
  || { echo "FATAL: lib/x86_64/libbun.so not packaged in APK"; exit 1; }

adb install -r -g "$APK" 2>&1 | tail -3 | tee -a "$MAINLOG"
adb shell pm list packages | grep ai.opencode | tee -a "$MAINLOG"
# Clear logcat so the gate run captures only this app's boot/process output.
adb logcat -c 2>/dev/null || true
adb install -r -g "$APK" 2>&1 | tail -3 | tee -a "$MAINLOG"
adb shell pm list packages | grep ai.opencode | tee -a "$MAINLOG"
# Clear logcat so the gate run captures only this app's boot/process output.
adb logcat -c 2>/dev/null || true

step "8/9 run Phase 4 gates (host lifecycle + G1..G12/G14/G15)"
# The device gates script reads OPENROUTER_API_KEY from env (passes it in).
set +e
bash "$DIR/scripts/20-device-gates.sh" 2>&1 | tee -a "$MAINLOG"
GATE_RC=${PIPESTATUS[0]}
set -e
echo "device gates rc=$GATE_RC" | tee -a "$MAINLOG"
# Always capture logcat (the OpenCode host tags + native crashes) into evidence.
adb logcat -d 2>/dev/null | grep -E "OpenCode|AndroidRuntime|DEBUG|libc|FATAL|bun|opencode" \
  > "$EV/logcat.txt" 2>&1 || adb logcat -d > "$EV/logcat.txt" 2>&1 || true
echo "logcat captured: $(wc -l < "$EV/logcat.txt" 2>/dev/null || echo 0) lines" | tee -a "$MAINLOG"

step "9/9 collect evidence"
cp "$MAINLOG" "$EV/00-run-phase4.log" 2>/dev/null || true
cp "$OUT/emulator.log" "$EV/" 2>/dev/null || true
cp "$DIR/out/engine/assets/runtime-manifest.json" "$EV/" 2>/dev/null || true
cp "$DIR/out/engine/build.status" "$EV/payload-build.status" 2>/dev/null || true
"$REPO/gradlew" -p "$REPO" :app:dependencies --configuration debugRuntimeClasspath --no-daemon > "$EV/app-dependencies.txt" 2>&1 || true

echo "=== PHASE 4 END $(date -u +%FT%TZ) gates_rc=$GATE_RC ===" | tee -a "$MAINLOG"
cat "$EV/GATES_SUMMARY.txt" 2>/dev/null | tee -a "$MAINLOG"

# ---------------------------------------------------------------------------
# PHASE 5 tail stage. The emulator, payload and APK this script just produced are
# exactly what the Phase 5 client-integration gates need, so they run here rather
# than booting a second device. Phase 4 verdicts are untouched: this stage adds
# its own summary (phase5/out/evidence/GATES_SUMMARY.txt) and its own log.
# The job fails if EITHER phase fails — a green run must mean both did.
step "10/10 phase 5 client-integration gates (staged on this device)"
P5_RC=0
if [ "$GATE_RC" = "0" ]; then
  bash "$REPO/phase5/scripts/00-run-phase5.sh" --stage-after-phase4 2>&1 | tee -a "$MAINLOG"
  P5_RC=${PIPESTATUS[0]}
else
  echo "phase4 gates failed (rc=$GATE_RC); skipping the phase5 tail stage"
  P5_RC=1
fi
echo "=== PHASE 5 (staged) END rc=$P5_RC ===" | tee -a "$MAINLOG"
cat "$REPO/phase5/out/evidence/GATES_SUMMARY.txt" 2>/dev/null | tee -a "$MAINLOG"
if [ "$GATE_RC" != "0" ]; then exit "$GATE_RC"; fi
exit "$P5_RC"
