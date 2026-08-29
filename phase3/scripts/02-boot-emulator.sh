#!/usr/bin/env bash
# 02-boot-emulator.sh — boot the gates AVD headless and wait for boot completion.
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$DIR/out"
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_HOME/avd}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

EMU="$ANDROID_HOME/emulator/emulator"

# GH runners ship /dev/kvm but the runner user may lack permissions — fix it.
if [ -e /dev/kvm ]; then
  sudo chmod 666 /dev/kvm 2>/dev/null || true
fi

echo "--- emulator accel-check"
"$EMU" -accel-check 2>&1 | tee -a "$OUT/02-boot-emulator.log" || true

echo "--- starting emulator (headless)"
nohup "$EMU" -avd gates -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 2048 -cores 2 -accel auto \
  > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
echo "emulator pid=$EMU_PID"

adb start-server 2>&1 | tee -a "$OUT/02-boot-emulator.log"

BOOTED=0
for i in $(seq 1 200); do   # up to ~20 minutes (TCG fallback is slow)
  DEVS="$(adb devices 2>/dev/null | grep -c 'emulator-' || true)"
  B="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$B" = "1" ]; then BOOTED=1; echo "BOOT_COMPLETED after ~$((i*6))s"; break; fi
  if ! kill -0 "$EMU_PID" 2>/dev/null && [ "$i" -gt 5 ]; then
    echo "EMULATOR_PROCESS_DIED at ~$((i*6))s"; break
  fi
  sleep 6
done

echo "--- boot state"
adb devices | tee -a "$OUT/02-boot-emulator.log"
if [ "$BOOTED" = 1 ]; then
  echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "EMULATOR_BOOTED"
else
  echo "EMULATOR_BOOT_FAILED (process died or timed out)"
  tail -30 "$OUT/emulator.log"
  exit 1
fi
