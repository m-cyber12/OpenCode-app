#!/usr/bin/env bash
# 11-build-remote-mcp.sh — assemble the Phase 5 host-side remote MCP fixture.
#
# Installs the pinned @modelcontextprotocol/sdk (same version as versions.lock and
# as the Phase 4 gates-mcp build, which OpenCode itself also depends on) next to
# phase5/mcp/remote-mcp-server.mjs so the fixture can serve BOTH MCP network
# transports (StreamableHTTP and legacy HTTP+SSE) to the on-device OpenCode
# client. Runs on the CI build host; the result is a plain directory (gitignored
# out/) — nothing here ships in the APK.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
mkdir -p "$OUT/mcp"

MCP_SDK_PIN="1.29.0"
cat > "$OUT/mcp/package.json" <<EOF
{
  "name": "p5-remote-mcp",
  "private": true,
  "type": "module",
  "dependencies": {
    "@modelcontextprotocol/sdk": "$MCP_SDK_PIN",
    "zod": "^3.25.0"
  }
}
EOF
cp "$DIR/mcp/remote-mcp-server.mjs" "$OUT/mcp/remote-mcp-server.mjs"

BUN=""
for c in "$HOME/.bun/bin/bun" "$(command -v bun || true)" "$ROOT/phase4/out/host-bun"; do
  [ -n "$c" ] && [ -x "$c" ] && BUN="$c" && break
done

P4NM="$ROOT/phase4/out/mcp/node_modules"
if [ -d "$P4NM/@modelcontextprotocol/sdk" ]; then
  # Reuse the Phase 4 install (identical pin) — offline, and it keeps both
  # fixtures provably on the same SDK version.
  rm -rf "$OUT/mcp/node_modules"
  cp -a "$P4NM" "$OUT/mcp/node_modules"
  echo "P5_REMOTE_MCP_DEPS reused from phase4/out/mcp/node_modules"
else
  [ -n "$BUN" ] || { echo "FATAL: no host bun to install the MCP SDK (run phase4/scripts/10-build-payload.sh first)"; exit 1; }
  echo "using host bun: $BUN ($("$BUN" --version))"
  (cd "$OUT/mcp" && "$BUN" install 2>&1 | tail -3)
fi

[ -d "$OUT/mcp/node_modules/@modelcontextprotocol/sdk" ] || { echo "FATAL: MCP SDK missing in $OUT/mcp"; exit 1; }
# Smoke check: the fixture must at least load its imports (the gates script then
# starts it for real). Any runner works; node is what the gate uses when present.
RUNNER="$(command -v node || true)"; [ -n "$RUNNER" ] || RUNNER="$BUN"
if [ -n "$RUNNER" ]; then
  P5_MCP_PORT=4598 "$RUNNER" "$OUT/mcp/remote-mcp-server.mjs" > "$OUT/mcp/smoke.log" 2>&1 &
  SMOKE=$!
  UP=0
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if curl -sf --max-time 2 "http://127.0.0.1:4598/health" >/dev/null 2>&1; then UP=1; break; fi
    sleep 1
  done
  kill "$SMOKE" 2>/dev/null || true
  echo "P5_REMOTE_MCP_SMOKE up=$UP (log: $OUT/mcp/smoke.log)"
  [ "$UP" = "1" ] || { echo "FATAL: fixture did not answer /health"; tail -20 "$OUT/mcp/smoke.log"; exit 1; }
fi
echo "P5_REMOTE_MCP_BUILT $OUT/mcp (sdk $MCP_SDK_PIN)"
