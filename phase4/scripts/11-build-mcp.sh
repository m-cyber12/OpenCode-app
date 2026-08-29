#!/usr/bin/env bash
# 11-build-mcp.sh — build the real @modelcontextprotocol/sdk stdio test server
# used by the G10 gate against the production server (same recipe as Phase 3,
# relocated to phase4/out/mcp). Runs on the networked build host.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$DIR/out"
mkdir -p "$OUT/mcp"
export PATH="$HOME/.bun/bin:$PATH"
command -v bun >/dev/null 2>&1 || { echo "bun host missing (run 10-build-payload.sh first)"; exit 1; }

MCP_SDK_PIN="1.29.0"
cat > "$OUT/mcp/package.json" <<EOF
{
  "name": "gates-mcp",
  "private": true,
  "type": "module",
  "dependencies": {
    "@modelcontextprotocol/sdk": "$MCP_SDK_PIN",
    "zod": "^3.25.0",
    "zod-to-json-schema": "^3.25.1"
  }
}
EOF
(cd "$OUT/mcp" && bun install 2>&1 | tail -3)
[ -d "$OUT/mcp/node_modules/@modelcontextprotocol/sdk" ] || { echo "FATAL: MCP SDK missing"; exit 1; }

cat > "$OUT/mcp/mcp-server.js" <<'EOF'
// gates-mcp — real MCP stdio server (@modelcontextprotocol/sdk), used by G10:
// the production OpenCode server connects to it as a local MCP server.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { z } from "zod"

const server = new McpServer({ name: "gates-mcp", version: "1.0.0" })
server.registerTool(
  "echo",
  { title: "Echo", description: "Echo the message with an echo: prefix", inputSchema: { message: z.string() } },
  async ({ message }) => ({ content: [{ type: "text", text: `echo:${message}` }] }),
)
server.registerTool(
  "write_marker",
  { title: "Write marker", description: "Write a text file on the host filesystem", inputSchema: { path: z.string(), content: z.string() } },
  async ({ path, content }) => {
    const fs = await import("node:fs")
    fs.writeFileSync(path, content)
    return { content: [{ type: "text", text: `wrote:${path}` }] } },
)
await server.connect(new StdioServerTransport())
EOF

cat > "$OUT/mcp/mcp-roundtrip.js" <<'EOF'
// gates-mcp round-trip: a standalone SDK client drives the real server over
// stdio (proves spawn + JSON-RPC under the app uid). Paths come from env.
import { Client } from "@modelcontextprotocol/sdk/client/index.js"
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js"
const bun = process.env.OPENCODE_BUN_BIN || "bun"
const dir = process.env.OPENCODE_MCP_DIR || "."
const transport = new StdioClientTransport({ command: bun, args: [dir + "/mcp-server.js"], cwd: dir })
const client = new Client({ name: "gates-mcp-roundtrip", version: "1.0.0" })
await client.connect(transport)
const { tools } = await client.listTools()
console.log("TOOLS=" + JSON.stringify(tools.map((t) => t.name)))
const res = await client.callTool({ name: "echo", arguments: { message: "G10_MCP_ROUNDTRIP_OK" } })
console.log("CALL=" + JSON.stringify(res))
await client.close()
EOF
echo "MCP_READY: $(du -sh "$OUT/mcp/node_modules" | cut -f1)"
