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
// It binds 127.0.0.1 on the CI host. The emulator reaches host loopback through
// the NAT gateway address 10.0.2.2, so the on-device OpenCode server connects to
// http://10.0.2.2:<port>/mcp — outbound HTTP from the device to the test host,
// no cloud gateway involved.
//
// Nothing here is OpenCode's code; it is only a peer for OpenCode's own MCP
// client (MCP.connectRemote -> StreamableHTTPClientTransport, SSE fallback).
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js"
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js"
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js"
import { z } from "zod"
import http from "node:http"

const PORT = Number(process.env.P5_MCP_PORT || "4551")
const HOST = process.env.P5_MCP_HOST || "127.0.0.1"
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

async function handleStreamable(req, res) {
  const sessionId = req.headers["mcp-session-id"]
  let transport = sessionId ? httpSessions.get(sessionId) : undefined

  if (!transport && req.method === "POST") {
    const t = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => crypto.randomUUID(),
      onsessioninitialized: (id) => {
        httpSessions.set(id, t)
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

const nodeServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://" + (req.headers.host || "localhost"))
  try {
    if (url.pathname === "/health") {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ healthy: true, marker: MARKER, mcp: httpSessions.size, sse: sseSessions.size }))
      return
    }
    if (url.pathname === "/sse" && req.method === "GET") return handleSseGet(req, res)
    if (url.pathname === "/messages" && req.method === "POST") return handleSsePost(req, res)
    if (url.pathname === "/mcp") return await handleStreamable(req, res)
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
