#!/usr/bin/env bash
# 00-run-all.sh — single orchestrator for the Phase 2 spike on the GH runner.
# Everything is logged into spike/out/ and committed as evidence by the workflow.
set -uo pipefail

SPIKE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SPIKE_DIR/out"
mkdir -p "$OUT"
MAINLOG="$OUT/00-run-all.log"
: > "$MAINLOG"
step() { echo; echo "########## $1 ##########"; echo "########## $1 ##########" >> "$MAINLOG"; }
run() { timeout 1500 "$@" 2>&1 | tee -a "$MAINLOG"; }   # per-phase cap (25 min); failures still logged

echo "=== RUN-ALL START $(date -u +%FT%TZ) ===" | tee -a "$MAINLOG"

# ---------------------------------------------------------------------------
step "1/7 environment probe"
run uname -a
run nproc
run free -h
if [ -e /dev/kvm ]; then echo "KVM_PRESENT"; ls -la /dev/kvm; else echo "KVM_MISSING"; fi
run java -version
echo "java=$(command -v java || true) JAVA_HOME=${JAVA_HOME:-unset}"

# ---------------------------------------------------------------------------
step "2/7 Android SDK resolution (preinstalled on GH runners)"
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
run ls -la "$ANDROID_HOME" || echo "NO PREINSTALLED SDK AT $ANDROID_HOME"

HAS_EMU=0; HAS_IMG=0
[ -x "$ANDROID_HOME/emulator/emulator" ] && HAS_EMU=1
[ -d "$ANDROID_HOME/system-images" ] && HAS_IMG=1
echo "preinstalled emulator=$HAS_EMU system-images=$HAS_IMG"

if [ "$HAS_EMU" = 0 ] || [ "$HAS_IMG" = 0 ]; then
  echo "Preinstalled SDK incomplete — installing fresh (fallback path)."
  run bash "$SPIKE_DIR/scripts/50-install-sdk.sh"
else
  echo "Using preinstalled Android SDK."
fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
run adb version
run "$ANDROID_HOME/emulator/emulator" -version
run ls "$ANDROID_HOME/system-images"
find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -type d | sort | tee -a "$MAINLOG"

# ---------------------------------------------------------------------------
step "3/7 pick system image + create AVD"
IMAGE_DIR=""
BEST=""
for d in $(find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -type d 2>/dev/null | sort -r); do
  rel="${d#"$ANDROID_HOME/system-images/"}"     # android-34/google_apis/x86_64
  api="${rel%%/*}"; rest="${rel#*/}"; tag="${rest%%/*}"; abi="${rest#*/}"
  case "$api" in android-3[0-9]|android-4[0-9]|android-1[0-9]) ;; *) continue;; esac
  [ "$abi" != "x86_64" ] && continue
  s=0; case "$tag" in google_apis) s=3;; default) s=2;; *) s=1;; esac
  # prefer: highest api, then tag quality
  key="$api-$s"
  if [ -z "$BEST" ] || [ "$key" \> "$BEST" ]; then BEST="$key"; IMAGE_DIR="$rel"; fi
done
echo "selected image: $IMAGE_DIR"
[ -z "$IMAGE_DIR" ] && { echo "FATAL: no usable x86_64 system image found"; exit 1; }
API="${IMAGE_DIR%%/*}"; REST="${IMAGE_DIR#*/}"; TAG="${REST%%/*}"; ABI="${REST#*/}"
echo "API=$API TAG=$TAG ABI=$ABI"

export ANDROID_AVD_HOME="$ANDROID_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
AVDM="$(find "$ANDROID_HOME" -name avdmanager -type f | head -1)"
echo "avdmanager=$AVDM"
AVD_OK=0
if [ -n "$AVDM" ]; then
  if run "$AVDM" create avd -n spike -k "system-images;$API;$TAG;$ABI" --force; then
    echo "AVD_CREATED_VIA_AVDMANAGER"; AVD_OK=1
  else
    echo "avdmanager create failed; falling back to manual AVD config"
  fi
fi
if [ "$AVD_OK" = 0 ]; then
  run bash "$SPIKE_DIR/scripts/51-manual-avd.sh" "$API" "$TAG" "$ABI"
fi
run ls -la "$ANDROID_AVD_HOME"
run cat "$ANDROID_AVD_HOME/spike.avd/config.ini"

# ---------------------------------------------------------------------------
step "4/7 boot emulator"
run bash "$SPIKE_DIR/scripts/52-boot-emulator.sh"

# ---------------------------------------------------------------------------
step "5/7 prepare spike artifacts (bundle, runtime, tools)"
run bash "$SPIKE_DIR/scripts/01-prepare-artifacts.sh"

# ---------------------------------------------------------------------------
step "6/7 run the chain on the emulator"
run bash "$SPIKE_DIR/scripts/02-device-chain.sh"

step "7/7 evidence bundle"
run bash "$SPIKE_DIR/scripts/53-evidence.sh"

echo "=== RUN-ALL END $(date -u +%FT%TZ) rc=$? ===" | tee -a "$MAINLOG"
