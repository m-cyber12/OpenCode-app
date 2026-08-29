#!/usr/bin/env bash
# 53-evidence.sh — assemble the evidence bundle (logs + provenance, no binaries)
# and scrub any model-key material (sk-or-v1-...) from every captured file.
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$DIR/out"
EVID="$OUT/evidence"
mkdir -p "$EVID"
for f in "$OUT"/*.log; do [ -f "$f" ] && cp "$f" "$EVID/"; done
[ -f "$OUT/opencode/UPSTREAM_COMMIT.txt" ] && cp "$OUT/opencode/UPSTREAM_COMMIT.txt" "$EVID/"
[ -f "$OUT/bun-x64.sha256" ] && cp "$OUT/bun-x64.sha256" "$EVID/"
[ -f "$OUT/bun-arm64.sha256" ] && cp "$OUT/bun-arm64.sha256" "$EVID/"
[ -f "$OUT/rg.sha256" ] && cp "$OUT/rg.sha256" "$EVID/"
[ -f "$OUT/git.sha256" ] && cp "$OUT/git.sha256" "$EVID/"
[ -f "$OUT/git.status" ] && cp "$OUT/git.status" "$EVID/"
[ -f "$OUT/git.upstream.commit.txt" ] && cp "$OUT/git.upstream.commit.txt" "$EVID/"
[ -f "$OUT/models-dev.json" ] && cp "$OUT/models-dev.json" "$EVID/models-dev.snapshot.json"
[ -f "$OUT/mcp/deps.txt" ] && cp "$OUT/mcp/deps.txt" "$EVID/mcp-deps.txt"
[ -f "$OUT/bin/ARTIFACT_SOURCE.txt" ] && cp "$OUT/bin/ARTIFACT_SOURCE.txt" "$EVID/ARTIFACT_SOURCE.txt"
[ -f "$OUT/mcp/install.status" ] && cp "$OUT/mcp/install.status" "$EVID/mcp-install.status"
[ -f "$OUT/prep.log" ] && cp "$OUT/prep.log" "$EVID/prep.log"
[ -f "$DIR/../versions.lock" ] && cp "$DIR/../versions.lock" "$EVID/versions.lock"
cp "$DIR/versions.gates.lock" "$EVID/versions.gates.lock" 2>/dev/null || true

# device-side per-gate logs + summary + config/storage
if [ -d "$OUT/device-out" ]; then
  cp -r "$OUT/device-out/." "$EVID/device/" 2>/dev/null || true
fi
[ -f "$EVID/device/GATES_SUMMARY.txt" ] && cp "$EVID/device/GATES_SUMMARY.txt" "$EVID/GATES_SUMMARY.txt"
[ -f "$OUT/device-config.txt" ] && cp "$OUT/device-config.txt" "$EVID/device-config.txt"
[ -f "$OUT/device-storage.txt" ] && cp "$OUT/device-storage.txt" "$EVID/device-storage.txt"
[ -f "$OUT/server.log" ] && cp "$OUT/server.log" "$EVID/server.log"
if [ -d "$OUT/device-server-log" ]; then
  cp -r "$OUT/device-server-log" "$EVID/server-log/"
fi

# --- key scrubbing (defense in depth; keys should never have been logged) ---
if grep -rl "sk-or-v1-" "$EVID" 2>/dev/null; then
  echo "WARNING: key material found in evidence — scrubbing"
  grep -rl "sk-or-v1-" "$EVID" | xargs sed -i 's/sk-or-v1-[A-Za-z0-9]*/sk-or-v1-REDACTED/g'
fi

{
  echo "--- evidence inventory $(date -u +%FT%TZ)"
  ls -la "$EVID"
  echo "--- GATES_SUMMARY"
  cat "$EVID/GATES_SUMMARY.txt" 2>/dev/null || echo "(missing — gates did not finish)"
  echo "--- gates-runner.device.log tail"
  tail -40 "$EVID/gates-runner.device.log" 2>/dev/null || true
  echo "--- server.log tail (device)"
  tail -20 "$EVID/server.log" 2>/dev/null || true
} > "$EVID/inventory.txt" 2>&1
echo "EVIDENCE_READY"
