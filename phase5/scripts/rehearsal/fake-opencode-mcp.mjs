// Rehearsal harness for phase5/scripts/device/gate-16-mcp-remote.js.
// Mimics ONLY the OpenCode MCP surface the driver touches, and - like the real
// server - it drives actual SDK clients, so status transitions come from genuine
// MCP negotiation rather than a stub.
import http from "node:http"
import { Client } from "@modelcontextprotocol/sdk/client/index.js"
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js"
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js"

const PORT = Number(process.env.FAKE_PORT || 4211)
const servers = new Map()   // name -> {config, status, client, tools}
const sanitize = (v) => v.replace(/[^a-zA-Z0-9_-]/g, "_")

servers.set("gates-mcp", { config: { type: "local" }, status: "connected", tools: [{ name: "echo" }, { name: "write_marker" }] })

async function connectRemote(name, cfg) {
  const client = new Client({ name: "opencode-rehearsal", version: "1.0.0" })
  const url = new URL(cfg.url)
  try {
    await client.connect(new StreamableHTTPClientTransport(url))
  } catch (e) {
    // upstream fallback: streamable http failed -> try legacy SSE
    try {
      await client.connect(new SSEClientTransport(url))
    } catch (e2) {
      return { status: "failed", error: String(e2.message || e.message).slice(0, 200) }
    }
  }
  const { tools } = await client.listTools()
  return { status: "connected", client, tools }
}

function json(res, code, obj) {
  res.writeHead(code, { "content-type": "application/json" })
  res.end(JSON.stringify(obj))
}
function body(req) {
  return new Promise((r) => { let b = ""; req.on("data", (c) => (b += c)); req.on("end", () => r(b ? JSON.parse(b) : {})) })
}

const s = http.createServer(async (req, res) => {
  const auth = req.headers.authorization || ""
  if (!auth.startsWith("Basic ")) return json(res, 401, { error: "Unauthorized" })
  const url = new URL(req.url, "http://x")
  const p = url.pathname
  if (req.method === "GET" && p === "/global/health") return json(res, 200, { healthy: true, version: "1.18.23" })
  if (req.method === "GET" && p === "/mcp") {
    const out = {}
    for (const [k, v] of servers) out[k] = v.status === "failed" ? { status: "failed", error: v.error } : { status: v.status }
    return json(res, 200, out)
  }
  if (req.method === "POST" && p === "/mcp") {
    const b = await body(req)
    const { name, config } = b
    if (!name || !config) return json(res, 400, { error: "name+config required" })
    if (config.type === "local") {
      servers.set(name, { config, status: "connected", tools: [{ name: "echo" }] })
      return json(res, 200, { status: "connected" })
    }
    const r = await connectRemote(name, config)
    servers.set(name, { config, ...r })
    return json(res, 200, { status: r.status })
  }
  let m = p.match(/^\/mcp\/([^/]+)\/(connect|disconnect)$/)
  if (req.method === "POST" && m) {
    const [, name, action] = m
    const e = servers.get(name)
    if (!e) return json(res, 404, { error: "no such mcp server" })
    if (action === "disconnect") {
      await e.client?.close().catch(() => {})
      e.status = "disabled"
      e.client = undefined
      return json(res, 200, { status: "disabled" })
    }
    const r = await connectRemote(name, e.config)
    Object.assign(e, r)
    return json(res, 200, { status: e.status })
  }
  if (req.method === "GET" && p === "/experimental/tool") {
    const tools = [{ id: "bash" }, { id: "edit" }, { id: "read" }]
    for (const [name, e] of servers) {
      if (e.status !== "connected") continue
      for (const t of e.tools ?? []) tools.push({ id: sanitize(name) + "_" + t.name })
    }
    return json(res, 200, tools)
  }
  json(res, 404, { error: "not found: " + p })
})
s.listen(PORT, "127.0.0.1", () => console.log("FAKE_OPENCODE_LISTENING " + PORT))
