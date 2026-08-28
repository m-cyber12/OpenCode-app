#!/usr/bin/env bash
# 53-evidence.sh — assemble the evidence bundle (logs + provenance, no binaries).
set -uo pipefail
SPIKE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SPIKE_DIR/out"
EVID="$OUT/evidence"
mkdir -p "$EVID"
for f in "$OUT"/*.log; do [ -f "$f" ] && cp "$f" "$EVID/"; done
[ -f "$OUT/bun.sha256" ] && cp "$OUT/bun.sha256" "$EVID/"
[ -f "$OUT/opencode/UPSTREAM_COMMIT.txt" ] && cp "$OUT/opencode/UPSTREAM_COMMIT.txt" "$EVID/"
[ -f "$SPIKE_DIR/versions.spike.lock" ] && cp "$SPIKE_DIR/versions.spike.lock" "$EVID/"
[ -f "$SPIKE_DIR/../versions.lock" ] && cp "$SPIKE_DIR/../versions.lock" "$EVID/versions.lock"
{
  echo "--- evidence inventory $(date -u +%FT%TZ)"
  ls -la "$EVID"
  echo "--- device chain log tail"
  tail -30 "$EVID/device-chain.device.log" 2>/dev/null || true
  echo "--- server log tail (device)"
  tail -20 "$EVID/server.log" 2>/dev/null || true
} > "$EVID/inventory.txt" 2>&1
echo "EVIDENCE_READY"
