// gate-16-mcp-remote.js — Phase 5 gate G16: OpenCode's MCP *network* transports,
// driven against the on-device app server by a real host-side MCP server.
//
// What it proves (and nothing else is asserted):
//   1. remote StreamableHTTP  -> `type:"remote"` config connects to /mcp
//   2. remote legacy HTTP+SSE  -> the same server exposed at /sse connects via
//                                 OpenCode's SSE fallback path
//   3. the tools of a remote server actually reach the agent tool registry
//      (measured through GET /experimental/tool, so it is naming-agnostic)
//   4. connect/disconnect lifecycle flips `GET /mcp` status both ways
//   5. an unreachable remote server reports status "failed" with a real error —
//      i.e. MCP was not globally crippled to make loopback work
//   6. the Phase 4 stdio MCP server ("gates-mcp") stays connected, so remote
//      support did not regress the local transport
//
// The device reaches the CI host through the emulator NAT gateway (10.0.2.2),
// which is what P5_MCP_URL is set to. No cloud endpoint is involved.
import { get, post, log, gateResult, assert } from "./gates-lib.js"

const MCP_URL = process.env.P5_MCP_URL || "http://10.0.2.2:4551"
const DEAD_URL = process.env.P5_MCP_DEAD_URL || "http://10.0.2.2:4599/mcp"
const PROVIDER = process.env.P5_TOOL_PROVIDER || "opencode"
const MODEL = process.env.P5_TOOL_MODEL || "big-pickle"
const STDIO_NAME = process.env.OPENCODE_MCP_STDIO_NAME || "gates-mcp"

const HTTP_NAME = "p5-remote-http"
const SSE_NAME = "p5-remote-sse"
const DEAD_NAME = "p5-remote-dead"

async function mcpStatus() {
  const r = await get("/mcp")
  if (!r.ok) throw new Error("GET /mcp -> " + r.status + " " + r.text.slice(0, 200))
  const obj = JSON.parse(r.text)
  const out = {}
  for (const [k, v] of Object.entries(obj)) {
    out[k] = typeof v === "string" ? v : (v?.status ?? "unknown")
    if (v && typeof v === "object" && v.error) out[k + "#error"] = String(v.error).slice(0, 300)
  }
  return out
}

async function waitFor(name, wanted, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs
  let last = {}
  while (Date.now() < deadline) {
    last = await mcpStatus()
    if (last[name] === wanted) return last
    await new Promise((r) => setTimeout(r, 2000))
  }
  throw new Error(`timeout waiting for ${name}=${wanted} (last: ${JSON.stringify(last)})`)
}

async function toolIds() {
  const r = await get(`/experimental/tool?provider=${PROVIDER}&model=${MODEL}`)
  if (!r.ok) throw new Error("GET /experimental/tool -> " + r.status + " " + r.text.slice(0, 300))
  const parsed = JSON.parse(r.text)
  const list = Array.isArray(parsed) ? parsed : (parsed.tools ?? [])
  return list.map((t) => (typeof t === "string" ? t : (t.id ?? t.name ?? ""))).filter(Boolean)
}

async function addRemote(name, url) {
  const r = await post("/mcp", { name, config: { type: "remote", url, enabled: true } })
  log(`POST /mcp ${name} ${url} -> ${r.status} ${r.text.slice(0, 200)}`)
  assert(r.ok, `POST /mcp for ${name} accepted (${r.status} ${r.text.slice(0, 200)})`)
}

async function remove(name) {
  // Tear the client down only; `POST /mcp` config is in-memory so nothing
  // persists past this run (the next server start reads the config file).
  const r = await post(`/mcp/${name}/disconnect`)
  log(`POST /mcp/${name}/disconnect -> ${r.status}`)
}

let ok = true
const notes = []
try {
  const before = await mcpStatus()
  log("mcp status before: " + JSON.stringify(before))
  const idsBefore = await toolIds()
  log(`tool ids before (${idsBefore.length}): ${idsBefore.slice(0, 12).join(", ")}`)
  assert(
    idsBefore.every((id) => !/remote_echo|remote_marker/.test(id)),
    "remote MCP tool ids absent before the servers were added",
  )

  // ---- 1) StreamableHTTP -------------------------------------------------
  await addRemote(HTTP_NAME, MCP_URL + "/mcp")
  const st = await waitFor(HTTP_NAME, "connected")
  log("mcp status after http add: " + JSON.stringify(st))
  assert(st[HTTP_NAME] === "connected", `${HTTP_NAME} status is connected (StreamableHTTP)`)
  notes.push("streamable_http=connected")

  // ---- 2) legacy HTTP+SSE fallback --------------------------------------
  await addRemote(SSE_NAME, MCP_URL + "/sse")
  const st2 = await waitFor(SSE_NAME, "connected")
  log("mcp status after sse add: " + JSON.stringify(st2))
  assert(st2[SSE_NAME] === "connected", `${SSE_NAME} status is connected (SSE fallback)`)
  notes.push("http_sse=connected")

  // ---- 3) tools registered in the agent registry ------------------------
  const idsAfter = await toolIds()
  const added = idsAfter.filter((id) => !idsBefore.includes(id))
  log(`tool ids added by the remote servers (${added.length}): ${added.join(", ")}`)
  for (const want of ["remote_echo", "remote_marker"]) {
    assert(
      added.some((id) => id.includes(want)),
      `tool list now contains a ${want} tool from the remote server`,
    )
    assert(
      added.filter((id) => id.includes(want)).length >= 1,
      `${want} reachable through BOTH remote transports (>=1 id)`,
    )
  }
  notes.push("tools_registered=" + added.length)

  // ---- 4) disconnect / connect lifecycle --------------------------------
  let r = await post(`/mcp/${HTTP_NAME}/disconnect`)
  log(`POST /mcp/${HTTP_NAME}/disconnect -> ${r.status}`)
  const stOff = await waitFor(HTTP_NAME, "disabled", 30000).catch(() => mcpStatus())
  log(`status after disconnect: ${JSON.stringify(stOff)}`)
  assert(stOff[HTTP_NAME] !== "connected", `${HTTP_NAME} is not connected after disconnect`)
  r = await post(`/mcp/${HTTP_NAME}/connect`)
  log(`POST /mcp/${HTTP_NAME}/connect -> ${r.status}`)
  const stOn = await waitFor(HTTP_NAME, "connected", 60000)
  assert(stOn[HTTP_NAME] === "connected", `${HTTP_NAME} reconnects on demand`)
  notes.push("lifecycle=disconnect+connect")

  // ---- 5) unreachable remote must report "failed" -----------------------
  await addRemote(DEAD_NAME, DEAD_URL)
  const stDead = await waitFor(DEAD_NAME, "failed", 60000).catch(() => mcpStatus())
  log(`dead server status: ${JSON.stringify(stDead)}`)
  assert(stDead[DEAD_NAME] === "failed", `${DEAD_NAME} reports failed for an unreachable server (got ${stDead[DEAD_NAME]})`)
  notes.push("unreachable=failed")

  // ---- 6) stdio transport still works ------------------------------------
  const stAll = await mcpStatus()
  log("mcp status final: " + JSON.stringify(stAll))
  assert(stAll[STDIO_NAME] === "connected", `${STDIO_NAME} (local stdio) still connected — remote support did not regress stdio`)
  notes.push("stdio=connected")

  await remove(SSE_NAME)
  await remove(DEAD_NAME)
  log("GATE16 remote MCP transports OK — " + notes.join(" "))
} catch (e) {
  ok = false
  log("GATE16 ERROR: " + (e && e.stack ? e.stack : e))
  try {
    log("final mcp status: " + JSON.stringify(await mcpStatus()))
  } catch {}
}
await gateResult(ok, "G16")
