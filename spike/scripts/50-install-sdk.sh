#!/usr/bin/env bash
# 50-install-sdk.sh — fallback: install Android SDK from scratch (only used if the
# preinstalled SDK on the runner is missing).
set -uo pipefail
SPIKE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SPIKE_DIR/out"
mkdir -p "$ANDROID_HOME" /tmp/sdkdl
cd /tmp/sdkdl

echo "--- downloading cmdline-tools"
curl -fsSLo cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
  || curl -fsSLo cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
ls -la cmdtools.zip
unzip -qo cmdtools.zip -d "$ANDROID_HOME"
find "$ANDROID_HOME/cmdline-tools" -maxdepth 2 -name sdkmanager -o -name avdmanager 2>/dev/null

# cmdline-tools expects <root>/cmdline-tools/latest/... ; normalize
if [ -d "$ANDROID_HOME/cmdline-tools/bin" ] && [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/bin" "$ANDROID_HOME/cmdline-tools/latest/bin"
  mv "$ANDROID_HOME/cmdline-tools/lib" "$ANDROID_HOME/cmdline-tools/latest/lib" 2>/dev/null || true
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
echo "--- sdkmanager licenses"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
echo "--- sdkmanager install"
sdkmanager "platform-tools" "emulator" "system-images;android-34;google_apis;x86_64" 2>&1 | tee -a "$OUT/50-install-sdk.log"
echo "SDK_INSTALL_DONE"
