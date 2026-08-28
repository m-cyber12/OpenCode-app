#!/usr/bin/env bash
# 03-run-gates.sh — drives the Phase 3 gates on the emulator via adb and captures evidence.
# Runs on the GH Actions runner. Outputs into phase3/out/.
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$DIR/out"
LOG="$OUT/03-run-gates.log"
GATES_DIR="/data/local/tmp/gates"
mkdir -p "$OUT"

ADB() { adb "$@"; }
log() { echo "$@" | tee -a "$LOG"; }

echo "=== GATES RUN START $(date -u +%FT%TZ) ===" | tee "$LOG"

# --- root adb + bounded device wait (never block forever) ---
ADB root || true
DEVICE_ONLINE=0
for i in $(seq 1 30); do
  if adb get-state 2>/dev/null | grep -q device; then DEVICE_ONLINE=1; log "device online after ~$((i*3))s"; break; fi
  sleep 3
done
if [ "$DEVICE_ONLINE" != 1 ]; then
  log "NO_DEVICE: emulator is not online — aborting (see emulator.log)"
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
} 2>&1 | tee -a "$LOG"

# --- push artifacts ---
# adb push semantics: pushing a local dir to a NON-existent remote path creates
# that path and copies the CONTENTS into it (proven in Phase 2). If the remote
# dir already exists, adb nests (remote/name) — so wipe the layout first.
{
  echo "### PUSH ARTIFACTS"
  ADB shell "rm -rf $GATES_DIR/opencode $GATES_DIR/node_modules $GATES_DIR/mcp $GATES_DIR/device $GATES_DIR/out $GATES_DIR/logs"
  ADB shell "mkdir -p $GATES_DIR/bin $GATES_DIR/project"
  ADB push "$OUT/bin/bun" "$GATES_DIR/bin/bun" | tail -1
  ADB push "$OUT/bin/rg" "$GATES_DIR/bin/rg" | tail -1
  ADB push "$OUT/bin/git" "$GATES_DIR/bin/git" | tail -1
  ADB push "$DIR/scripts/device" "$GATES_DIR/device" | tail -1
  ADB push "$OUT/opencode" "$GATES_DIR/opencode" | tail -1
  ADB push "$OUT/node_modules" "$GATES_DIR/node_modules" | tail -1
  ADB push "$OUT/mcp" "$GATES_DIR/mcp" | tail -1
  ADB shell "chmod 755 $GATES_DIR/bin/bun $GATES_DIR/bin/rg $GATES_DIR/bin/git && echo PUSHED"
} 2>&1 | tee -a "$LOG"

# --- pass the model key (if any) without putting it on any command line ---
# The key is written to a 600-perm file on the device; the server reads it there.
KEYFILE_LOCAL="$OUT/.api-key.tmp"
: > "$KEYFILE_LOCAL"
if [ -n "${OPENROUTER_API_KEY:-}" ]; then
  printf '%s' "$OPENROUTER_API_KEY" > "$KEYFILE_LOCAL"
  ADB push "$KEYFILE_LOCAL" "$GATES_DIR/.api-key" | tail -1
  ADB shell "chmod 600 $GATES_DIR/.api-key && echo KEY_PUSHED"
fi
rm -f "$KEYFILE_LOCAL"

# --- model id (overridable, e.g. via the OPENROUTER_MODEL workflow env) ---
printf '%s' "${OPENROUTER_MODEL:-nvidia/nemotron-3-ultra-550b-a55b:free}" > "$KEYFILE_LOCAL"
ADB push "$KEYFILE_LOCAL" "$GATES_DIR/.model" | tail -1
rm -f "$KEYFILE_LOCAL"

# --- run the gates on device ---
{
  echo "### RUN GATES (see gates-runner.device.log for the raw device transcript)"
  ADB shell "sh $GATES_DIR/device/gates-runner.sh" | tee "$OUT/gates-runner.device.log"
} 2>&1 | tee -a "$LOG"

# --- fetch device-side results back for evidence ---
{
  echo "### FETCH DEVICE RESULTS"
  ADB pull "$GATES_DIR/out" "$OUT/device-out" 2>&1 | tail -2
  ADB pull "$GATES_DIR/server.log" "$OUT/server.log" 2>&1 | tail -1 || true
  ADB shell "ls -la $GATES_DIR/data/opencode $GATES_DIR/config/opencode 2>/dev/null" | tee "$OUT/device-storage.txt"
  ADB shell "cat $GATES_DIR/config/opencode/opencode.jsonc 2>/dev/null" | tee "$OUT/device-config.txt"
  ADB shell "cat $GATES_DIR/out/GATES_SUMMARY.txt 2>/dev/null" | tee "$OUT/GATES_SUMMARY.device.txt"
} 2>&1 | tee -a "$LOG"

echo "=== GATES RUN END $(date -u +%FT%TZ) ===" | tee -a "$LOG"
