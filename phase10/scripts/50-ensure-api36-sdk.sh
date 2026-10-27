#!/usr/bin/env bash
# Ensure the exact Android 16 toolchain used by the API 36 verdict board.
#
# The hosted runner image changes independently of this repository. Selecting
# "the highest installed API" therefore cannot prove API 36: it can silently
# run an older cached image or a future image. This script installs and checks
# the exact compile platform and emulator image before Gradle or the AVD starts.
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
mkdir -p "$SDK" "$DIR/out"
LOG="$DIR/out/50-ensure-api36-sdk.log"
: > "$LOG"

find_sdkmanager() {
  find "$SDK/cmdline-tools" -name sdkmanager -type f 2>/dev/null | sort -V | tail -1
}

SDKMAN="$(find_sdkmanager)"
if [ -z "$SDKMAN" ] || [ ! -x "$SDKMAN" ]; then
  echo "sdkmanager absent; installing command-line tools through the existing fallback" | tee -a "$LOG"
  bash "$ROOT/phase4/scripts/50-install-sdk.sh" 2>&1 | tee -a "$LOG"
  SDKMAN="$(find_sdkmanager)"
fi
[ -n "$SDKMAN" ] && [ -x "$SDKMAN" ] || {
  echo "API36_SDK_FAIL sdkmanager is unavailable under $SDK/cmdline-tools" | tee -a "$LOG" >&2
  exit 1
}

export PATH="$(dirname "$SDKMAN"):$SDK/platform-tools:$SDK/emulator:$PATH"
yes | "$SDKMAN" --licenses >/dev/null 2>&1 || true

echo "sdkmanager=$SDKMAN" | tee -a "$LOG"
"$SDKMAN" \
  "platform-tools" \
  "platforms;android-36" \
  "emulator" \
  "system-images;android-36;google_apis;x86_64" \
  2>&1 | tee -a "$LOG"

fail=0
for required in \
  "$SDK/platforms/android-36/android.jar" \
  "$SDK/system-images/android-36/google_apis/x86_64/package.xml" \
  "$SDK/emulator/emulator"; do
  if [ ! -e "$required" ]; then
    echo "API36_SDK_FAIL missing $required" | tee -a "$LOG" >&2
    fail=1
  fi
done
[ "$fail" = 0 ] || exit 1

echo "API36_SDK_READY platform=android-36 image=android-36/google_apis/x86_64" | tee -a "$LOG"
