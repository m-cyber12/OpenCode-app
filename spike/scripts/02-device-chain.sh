#!/usr/bin/env bash
# 02-device-chain.sh — drives the whole spike on the emulator via adb and captures evidence.
# Runs on the GitHub Actions runner. Outputs into spike/out/ (logs + markers).
set -uo pipefail
SPIKE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SPIKE_DIR/out"
LOG="$OUT/device-chain.log"
mkdir -p "$OUT"

ADB() { adb "$@"; }

echo "=== SPIKE CHAIN START $(date -u +%FT%TZ) ===" | tee "$LOG"

# --- root adb + bounded device wait (never block forever) ---
ADB root || true
DEVICE_ONLINE=0
for i in $(seq 1 30); do
  if adb get-state 2>/dev/null | grep -q device; then DEVICE_ONLINE=1; echo "device online after ~$((i*3))s"; break; fi
  sleep 3
done
if [ "$DEVICE_ONLINE" != 1 ]; then
  echo "NO_DEVICE: emulator is not online — aborting device chain (see emulator.log)"
  exit 1
fi

# --- 0. Android host facts ---
{
  echo "### [0] ANDROID HOST FACTS"
  echo "-- id:"; ADB shell id
  echo "-- release:"; ADB shell getprop ro.build.version.release | tr -d '\r'
  echo "-- sdk:"; ADB shell getprop ro.build.version.sdk | tr -d '\r'
  echo "-- abi:"; ADB shell getprop ro.product.cpu.abi | tr -d '\r'
  echo "-- uname:"; ADB shell uname -a
  echo "-- kernel cmdline (selinux):"; ADB shell cat /proc/cmdline | tr -d '\r'
  echo "-- shell:"; ADB shell 'ls -la /system/bin/sh; /system/bin/sh -c "echo SHELL_INTERP_OK"'
} 2>&1 | tee -a "$LOG"

# --- push artifacts ---
{
  echo "### PUSH ARTIFACTS"
  ADB shell mkdir -p /data/local/tmp/spike/bin /data/local/tmp/spike/project /data/local/tmp/spike/stubs
  ADB push "$OUT/bun" /data/local/tmp/spike/bin/bun | tail -1
  ADB push "$OUT/rg" /data/local/tmp/spike/bin/rg | tail -1
  [ -f "$OUT/git" ] && ADB push "$OUT/git" /data/local/tmp/spike/bin/git | tail -1 || echo "no git artifact"
  ADB push "$SPIKE_DIR/scripts/device/device-chain.sh" /data/local/tmp/spike/device-chain.sh | tail -1
  ADB push "$SPIKE_DIR/scripts/device/launch-server.js" /data/local/tmp/spike/launch-server.js | tail -1
  ADB push "$SPIKE_DIR/scripts/device/health-check.js" /data/local/tmp/spike/health-check.js | tail -1
  ADB push "$OUT/opencode" /data/local/tmp/spike/opencode | tail -1
  ADB push "$OUT/node_modules" /data/local/tmp/spike/node_modules | tail -1
  ADB shell 'chmod 755 /data/local/tmp/spike/bin/bun /data/local/tmp/spike/bin/rg; [ -f /data/local/tmp/spike/bin/git ] && chmod 755 /data/local/tmp/spike/bin/git; echo "PUSHED"'
} 2>&1 | tee -a "$LOG"

# --- run the chain on device (captured to device-chain.device.log too) ---
{
  echo "### RUN DEVICE CHAIN (see device-chain.device.log for the raw device transcript)"
  ADB shell 'sh /data/local/tmp/spike/device-chain.sh' | tee "$OUT/device-chain.device.log"
} 2>&1 | tee -a "$LOG"

echo "=== SPIKE CHAIN END $(date -u +%FT%TZ) ===" | tee -a "$LOG"

# --- fetch device-side logs back for evidence ---
{
  echo "### FETCH DEVICE LOGS"
  ADB pull /data/local/tmp/spike/server.log "$OUT/server.log" 2>&1 | tail -1
  ADB shell 'ls -la /data/local/tmp/spike/data/opencode /data/local/tmp/spike/config/opencode 2>/dev/null' | tee "$OUT/device-storage.txt"
  ADB shell 'cat /data/local/tmp/spike/config/opencode/opencode.jsonc 2>/dev/null' | tee "$OUT/device-config.txt"
} 2>&1 | tee -a "$LOG"

# --- assemble the evidence bundle (logs + provenance only, no binaries) ---
bash "$SPIKE_DIR/scripts/53-evidence.sh"
echo "EVIDENCE_READY"
