#!/usr/bin/env bash
# run-host-rehearsal.sh — host-side rehearsal of the Phase 5 remote-MCP gate.
#
# THIS IS NOT DEVICE EVIDENCE AND MUST NOT BE QUOTED AS SUCH. It exercises the
# gate *driver* and the MCP *fixture* on one host: fake-opencode-mcp.mjs serves
# the small OpenCode MCP surface the driver touches (GET /mcp, POST /mcp,
# POST /mcp/:name/{connect,disconnect}, GET /experimental/tool) and, like the
# real server, drives genuine @modelcontextprotocol/sdk clients, so the status
# transitions the driver asserts come from real MCP negotiation. It proves the
# wiring and expectations are self-consistent; it proves nothing about Android,
# the emulator, seccomp, Keystore, or the on-device server.
#
# What it caught during development: gate-16 importing ./gates-lib.js (which only
# exists in phase4/scripts/device), and a stack-overflow in the fixture's SSE
# teardown.
#
# Usage: bash phase5/scripts/rehearsal/run-host-rehearsal.sh
# Needs: node (or bun) + phase5/out/mcp/node_modules (run
#        phase5/scripts/11-build-remote-mcp.sh first).
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
P5="$(cd "$DIR/../.." && pwd)"
ROOT="$(cd "$P5/.." && pwd)"
WORK="$P5/out/rehearsal"
mkdir -p "$WORK"
NM="$P5/out/mcp/node_modules"
[ -d "$NM/@modelcontextprotocol/sdk" ] || { echo "FATAL: $NM missing - run phase5/scripts/11-build-remote-mcp.sh"; exit 1; }

RUNNER="$(command -v node || true)"
[ -n "$RUNNER" ] || RUNNER="$(command -v bun || true)"
[ -n "$RUNNER" ] || { echo "FATAL: node or bun required"; exit 1; }

# Let bare `import "@modelcontextprotocol/sdk/..."` resolve from the rehearsal dir.
ln -sfn "$NM" "$WORK/node_modules"

FAKE_PORT=${FAKE_PORT:-4211}
FIX_PORT=${FIX_PORT:-4551}
DEAD_PORT=$((FIX_PORT + 48))

cleanup() {
  [ -n "${FIX_PID:-}" ] && kill "$FIX_PID" 2>/dev/null
  [ -n "${FAKE_PID:-}" ] && kill "$FAKE_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

# Run the fixture from out/mcp, where its node_modules sits next to it.
"$RUNNER" "$P5/out/mcp/remote-mcp-server.mjs" > "$WORK/fixture.log" 2>&1 &
FIX_PID=$!
FAKE_PORT="$FAKE_PORT" "$RUNNER" "$DIR/fake-opencode-mcp.mjs" > "$WORK/fake.log" 2>&1 &
FAKE_PID=$!

for _ in 1 2 3 4 5 6 7 8 9 10; do
  curl -sf --max-time 2 "http://127.0.0.1:$FIX_PORT/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf --max-time 2 "http://127.0.0.1:$FIX_PORT/health" >/dev/null 2>&1 || { echo "FATAL: fixture not listening"; tail -20 "$WORK/fixture.log"; exit 1; }
echo "fixture up on $FIX_PORT; fake opencode on $FAKE_PORT"

OPENCODE_BASE="http://127.0.0.1:$FAKE_PORT" \
OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=rehearsal \
OPENCODE_DIRECTORY="$WORK" \
P5_MCP_URL="http://127.0.0.1:$FIX_PORT" \
P5_MCP_DEAD_URL="http://127.0.0.1:$DEAD_PORT/mcp" \
  "$RUNNER" "$P5/scripts/device/gate-16-mcp-remote.js" 2>&1 | tee "$WORK/gate16.log"
RC=${PIPESTATUS[0]}
echo "--- fixture log ---"; tail -5 "$WORK/fixture.log"
if [ "$RC" = "0" ]; then
  echo "REHEARSAL_PASS (host-side wiring only; NOT device evidence)"
else
  echo "REHEARSAL_FAIL rc=$RC"
fi
exit "$RC"
