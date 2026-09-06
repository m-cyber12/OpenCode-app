// remote-mcp-server.mjs — the Phase 5 MCP *transport* fixture.
//
// A real Model Context Protocol server speaking the two network transports,
// built on the pinned @modelcontextprotocol/sdk (the same SDK OpenCode uses for
// its MCP client). Used by the Phase 5 remote-MCP gates:
//
//   /mcp      StreamableHTTP (the transport current MCP clients use)
//   /sse      legacy HTTP+SSE (deprecated transport; still what OpenCode falls
//             back to when a server only offers it)
//   /messages POST target advertised by /sse
//
// It binds 0.0.0.0 on the CI host, and the on-device OpenCode server connects to
// http://10.0.2.2:<port>/mcp — 10.0.2.2 is the emulator's NAT *gateway* interface on
// the host, not the host's loopback, so a 127.0.0.1 bind is unreachable from the
// guest (run #15 never got this far; the driver died on an import path first).
// Reachable-from-the-guest is required for the gate to mean anything — the point is
// that the device connects to a network MCP server living outside itself — and this
// listener is a purpose-built fixture on an ephemeral runner carrying no credentials.
// Nothing about it changes the app's own binding policy, which stays loopback-only
// (that is gate G17/K7's job). Override with P5_MCP_HOST if a runner needs it.
//
// Nothing here is OpenCode's code; it is only a peer for OpenCode's own MCP
// client (MCP.connectRemote -> StreamableHTTPClientTransport, SSE fallback).
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js"
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js"
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js"
import { z } from "zod"
import http from "node:http"

const PORT = Number(process.env.P5_MCP_PORT || "4551")
const HOST = process.env.P5_MCP_HOST || "0.0.0.0"
const MARKER = process.env.P5_MCP_MARKER || "P5_REMOTE_MCP"

function makeServer(name) {
  const server = new McpServer({ name, version: "1.0.0" })
  server.registerTool(
    "remote_echo",
    {
      title: "Remote echo",
      description: "Echo the message with an echo: prefix (Phase 5 remote MCP fixture)",
      inputSchema: { message: z.string() },
    },
    async ({ message }) => ({ content: [{ type: "text", text: `echo:${message}` }] }),
  )
  server.registerTool(
    "remote_marker",
    {
      title: "Remote marker",
      description: "Return a fixed marker string proving a remote MCP round trip",
      inputSchema: {},
    },
    async () => ({ content: [{ type: "text", text: `${MARKER}_OK` }] }),
  )
  return server
}

// ---- StreamableHTTP: stateful, one transport per session --------------------
const httpSessions = new Map()

async function handleStreamable(req, res, url) {
  const sessionId = req.headers["mcp-session-id"]
  let transport = sessionId ? httpSessions.get(sessionId) : undefined

  if (!transport && req.method === "POST") {
    // `?mode=json` selects the other half of the StreamableHTTP contract: the transport
    // answers each POST with a single `application/json` body instead of an SSE-framed
    // stream. Both are spec-legal for a server, so a client that consumes one and not the
    // other is a real, nameable interop fact - which is exactly what run #18 needs to
    // distinguish "this platform cannot read the SSE-mode response" from "upstream's
    // transport is misconfigured". The gate still asserts the SSE mode; the JSON mode is
    // reported by the driver as a diagnostic, never as a substitute pass.
    const jsonMode = url.searchParams.get("mode") === "json"
    const t = new StreamableHTTPServerTransport({
      ...(jsonMode ? { enableJsonResponse: true } : {}),
      sessionIdGenerator: () => crypto.randomUUID(),
      onsessioninitialized: (id) => {
        stats.inits++
        httpSessions.set(id, t)
        console.log(`[p5-mcp] streamable session initialized id=${id} (requests=${stats.requests})`)
      },
      onsessionclosed: (id) => {
        httpSessions.delete(id)
      },
    })
    t.onclose = () => {
      if (t.sessionId) httpSessions.delete(t.sessionId)
    }
    await makeServer("p5-remote-mcp").connect(t)
    transport = t
  }

  if (!transport) {
    // GET/DELETE without a valid session: nothing to serve.
    res.writeHead(404, { "content-type": "application/json" })
    res.end(JSON.stringify({ error: "no such session" }))
    return
  }
  await transport.handleRequest(req, res)
}

// ---- legacy HTTP+SSE: long-lived GET + posted messages ---------------------
const sseSessions = new Map()

function handleSseGet(req, res) {
  const server = makeServer("p5-remote-mcp-sse")
  const transport = new SSEServerTransport("/messages", res)
  sseSessions.set(transport.sessionId, transport)
  // Clean up when the HTTP connection drops. Do NOT also wire server.onclose ->
  // transport.close(): the transport's own close path fires onclose, which would
  // recurse (observed as a stack overflow on client disconnect).
  res.on("close", () => sseSessions.delete(transport.sessionId))
  server
    .connect(transport)
    .then(() => console.log(`[p5-mcp] sse session connected ${transport.sessionId}`))
    .catch((e) => {
      console.error("[p5-mcp] sse connect failed: " + (e && e.message))
      try {
        res.end()
      } catch {}
    })
}

function handleSsePost(req, res) {
  const url = new URL(req.url, "http://" + (req.headers.host || "localhost"))
  const transport = sseSessions.get(url.searchParams.get("sessionId"))
  if (!transport) {
    res.writeHead(400).end("no such sse session")
    return
  }
  transport.handlePostMessage(req, res).catch((e) => console.error("[p5-mcp] sse post failed: " + e.message))
}

const stats = { requests: 0, inits: 0, byes: 0 }

const nodeServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://" + (req.headers.host || "localhost"))
  // One line per request, with the response's own shape, so "the client never arrived",
  // "arrived and answered 200" and "answered but the stream was cut" are three different
  // observable facts instead of one inference. Byte counting wraps write/end rather than
  // consuming the body, so `transport.handlePostMessage` still sees the raw stream - the
  // MCP SDK's server side owns request parsing and must not be fed a buffered copy.
  if (url.pathname !== "/health") stats.requests++
  let bytes = 0
  const size = (c) =>
    typeof c === "string" ? Buffer.byteLength(c)
      : c && (Buffer.isBuffer(c) || c instanceof Uint8Array) ? c.byteLength
      : 0
  const ow = res.write.bind(res), oe = res.end.bind(res)
  res.write = (c, ...a) => { bytes += size(c); return ow(c, ...a) }
  res.end = (c, ...a) => { bytes += size(c); return oe(c, ...a) }
  const t0 = Date.now()
  res.on("finish", () => {
    console.log(`[p5-mcp] ${req.method} ${url.pathname} -> ${res.statusCode} ct=${res.getHeader("content-type") || "-"} bytes=${bytes} sessions=${httpSessions.size} +${Date.now() - t0}ms`)
  })
  res.on("close", () => {
    if (!res.writableEnded) console.log(`[p5-mcp] ${req.method} ${url.pathname} CLOSED without ending (${Date.now() - t0}ms, ${bytes} bytes written)`)
  })
  try {
    if (url.pathname === "/health") {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ healthy: true, marker: MARKER, mcp: httpSessions.size, sse: sseSessions.size, requests: stats.requests, inits: stats.inits }))
      return
    }
    if (url.pathname === "/sse" && req.method === "GET") return handleSseGet(req, res)
    if (url.pathname === "/messages" && req.method === "POST") return handleSsePost(req, res)
    if (url.pathname === "/mcp") return await handleStreamable(req, res, url)
    res.writeHead(404).end("not found")
  } catch (e) {
    console.error("[p5-mcp] handler error: " + (e && e.stack ? e.stack : e))
    try {
      res.writeHead(500, { "content-type": "application/json" })
      res.end(JSON.stringify({ error: String(e && e.message ? e.message : e) }))
    } catch {}
  }
})

nodeServer.listen(PORT, HOST, () => {
  console.log(`P5_MCP_LISTENING http://${HOST}:${PORT}/mcp sse=http://${HOST}:${PORT}/sse marker=${MARKER}`)
})
