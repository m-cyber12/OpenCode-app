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
// Shared helpers are phase 4's, imported in place (not copied, not edited): the
// auth/dir plumbing and the assert/gateResult contract must stay identical
// across phases so results are comparable.
let lib
try {
  // On-device layout: `20-integration-gates.sh` stages the drivers flat, beside lib.
  lib = await import("./gates-lib.js")
} catch {
  // Repo layout (host rehearsals): phase 4's file in place, not copied, not edited.
  lib = await import("../../../phase4/scripts/device/gates-lib.js")
}
const { get, post, log, gateResult, assert } = lib

// Fixture route, added after run #16.
//
// Run #16 is the first time this driver reached its own assertions on the device: the
// pre-state was clean, `POST /mcp` returned 200, and upstream's MCP client then reported
//     {"p5-remote-http":{"status":"failed","error":"Failed to get tools"}}
// while the fixture logged `mcp:0 sse:0` — not one session got through. That is a fact
// about the emulator's network path (can the guest open a TCP session to the test host at
// all?), and reporting it as an MCP verdict would mislead in either direction: the client
// would look broken when the wire is, or vice versa. So the route is measured before
// anything is asserted, over each candidate path, and the one that answers is used:
//
//   nat-gateway  http://10.0.2.2:<port>   the host's own address on the emulator's NAT
//   adb-reverse  http://127.0.0.1:<port>  adbd forwards a device port to the same host port
//
// Both probe results and the chosen route are printed, so GATES_SUMMARY.txt names the path
// that carried the traffic. If nothing answers, the driver exits 7: the gate script records
// a SKIP with the probe output, because "the harness could not reach its own fixture" is an
// environment condition and must never be booked as a result about OpenCode's transports.
const ROUTE_CANDIDATES = []
if (process.env.P5_MCP_URL) ROUTE_CANDIDATES.push({ name: "nat-gateway", origin: process.env.P5_MCP_URL })
if (process.env.P5_MCP_URL_LOCAL) ROUTE_CANDIDATES.push({ name: "adb-reverse", origin: process.env.P5_MCP_URL_LOCAL })
if (ROUTE_CANDIDATES.length === 0) ROUTE_CANDIDATES.push({ name: "default", origin: "http://127.0.0.1:4551" })

async function probeRoute(origin) {
  try {
    const r = await fetch(origin + "/health", { signal: AbortSignal.timeout(6000) })
    const body = await r.text()
    return { ok: r.status === 200, text: `HTTP ${r.status} ${body.slice(0, 120)}` }
  } catch (e) {
    const cause = e && e.cause ? e.cause : {}
    return {
      ok: false,
      text: `ERR ${cause.code || cause.name || (e && e.name) || "unknown"}:` +
        String(cause.message || (e && e.message) || "").slice(0, 120),
    }
  }
}

const routeProbe = []
let route = null
for (const c of ROUTE_CANDIDATES) {
  const p = await probeRoute(c.origin)
  routeProbe.push(`${c.name}@${c.origin}=${p.ok ? "reachable" : "unreachable"}(${p.text})`)
  if (!route && p.ok) route = c
}
log(`fixture route: ${routeProbe.join(" | ")} chosen=${route ? route.name : "none"}`)
if (!route) {
  log("GATE16 NO_ROUTE: no path from the device to the fixture — SKIP, not a verdict about OpenCode")
  process.exit(7)
}
const MCP_URL = route.origin
// The negative case stays a genuinely unreachable endpoint *on the same route*, so
// `failed` always means "OpenCode tried to connect and could not", never "the URL was
// nonsense": 4599 is not forwarded, and the NAT address has no listener.
const DEAD_URL = route.name === "nat-gateway" && process.env.P5_MCP_DEAD_URL
  ? process.env.P5_MCP_DEAD_URL
  : `${MCP_URL}:${process.env.P5_MCP_DEAD_PORT || "4599"}/mcp`

// --- fixture-side observability, independent of OpenCode ---------------------
// Upstream's MCP layer swallows the cause of a failed tool list: mcp/index.ts:390-393
// turns any `listTools` rejection into `new Error("Failed to get tools")`, and
// McpCatalog.defs() pipes it through `Effect.catch(() => Effect.void)` — so `GET /mcp`
// reports the label and nothing else, and no server log line accompanies it (verified in
// #17's `opencode-server.log`, which contains no `p5-remote-http` entry at all). Rather
// than touch upstream, the driver performs the *same* exchange itself, in the raw
// JSON-RPC form, over the same route, so the real HTTP-level outcome is visible in the
// evidence; and the fixture logs every request it receives. The two together say which
// of "the request never arrived", "initialize never completed", and "tools/list came
// back unusable" actually happened, and they do not change what the gate asserts.
// This is a diagnostic: nothing here passes or fails the gate.
async function fixtureSnapshot() {
  try {
    const r = await fetch(MCP_URL + "/health", { signal: AbortSignal.timeout(6000) })
    return `HTTP ${r.status} ${(await r.text()).replace(/\s+/g, " ").slice(0, 200)}`
  } catch (e) {
    return `ERR ${String((e && e.message) || e)}`
  }
}

async function rawProbe() {
  const base = { "content-type": "application/json", accept: "application/json, text/event-stream" }
  const seen = []
  try {
    const t0 = Date.now()
    const r = await fetch(MCP_URL + "/mcp", {
      method: "POST",
      headers: base,
      body: JSON.stringify({
        jsonrpc: "2.0", id: 1, method: "initialize",
        params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "p5-raw-probe", version: "1" } },
      }),
      signal: AbortSignal.timeout(15000),
    })
    const sid = r.headers.get("mcp-session-id") || ""
    const body0 = (await r.text()).replace(/\s+/g, " ")
    seen.push(`initialize: HTTP ${r.status} ct=${r.headers.get("content-type")} session=${sid ? "yes" : "NO"} ${Date.now() - t0}ms body=${body0.slice(0, 200)}`)
    if (!sid) throw new Error("no mcp-session-id header on the initialize response")
    const h = { ...base, "mcp-session-id": sid }
    const r1 = await fetch(MCP_URL + "/mcp", {
      method: "POST", headers: h,
      body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
      signal: AbortSignal.timeout(10000),
    })
    seen.push(`initialized: HTTP ${r1.status}`)
    const t1 = Date.now()
    const r2 = await fetch(MCP_URL + "/mcp", {
      method: "POST", headers: h,
      body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} }),
      signal: AbortSignal.timeout(20000),
    })
    const body2 = (await r2.text()).replace(/\s+/g, " ")
    seen.push(`tools/list: HTTP ${r2.status} ct=${r2.headers.get("content-type")} ${Date.now() - t1}ms body=${body2.slice(0, 200)}`)
    await fetch(MCP_URL + "/mcp", { method: "DELETE", headers: h, signal: AbortSignal.timeout(5000) }).catch(() => {})
    log(`raw_mcp OK ${seen.join(" | ")}`)
  } catch (e) {
    const cause = e && e.cause ? ` cause=${String(e.cause.code || e.cause.message || e.cause).slice(0, 160)}` : ""
    log(`raw_mcp FAIL ${seen.join(" | ")} | error=${String((e && e.name) || "ERR")}:${String((e && e.message) || e).slice(0, 200)}${cause}`)
  }
}

log(`fixture snapshot before: ${await fixtureSnapshot()}`)
await rawProbe()
const PROVIDER = process.env.P5_TOOL_PROVIDER || "opencode"
const MODEL = process.env.P5_TOOL_MODEL || "big-pickle"
const STDIO_NAME = process.env.OPENCODE_MCP_STDIO_NAME || "gates-mcp"

const HTTP_NAME = "p5-remote-http"
const SSE_NAME = "p5-remote-sse"
const DEAD_NAME = "p5-remote-dead"
const JSON_NAME = "p5-remote-json"

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
  const t0 = Date.now()
  const r = await post("/mcp", { name, config: { type: "remote", url, enabled: true } })
  // Elapsed matters: upstream's tool list runs under McpCatalog's DEFAULT_TIMEOUT of
  // 30_000 ms (catalog.ts:11), and "the client gave up after ~30 s" is a different finding
  // from "the client failed in 200 ms" even though both surface as `Failed to get tools`.
  log(`POST /mcp ${name} ${url} -> ${r.status} ${r.text.slice(0, 200)} [${Date.now() - t0}ms]`)
  assert(r.ok, `POST /mcp for ${name} accepted (${r.status} ${r.text.slice(0, 200)})`)
}

// Diagnostic only, and deliberately NOT an assertion: register the same fixture with the
// JSON-response mode and report what upstream says. If SSE mode fails here while this
// connects, §2 can state the limitation precisely ("remote MCP over StreamableHTTP works
// against servers that answer in JSON-response mode under Bun for Android; the SSE-framed
// mode is not consumed") without pretending the gate passed, and without any change to
// OpenCode. The gate verdict is unaffected either way.
async function jsonModeProbe() {
  try {
    await addRemote(JSON_NAME, MCP_URL + "/mcp?mode=json")
    let final
    try {
      await waitFor(JSON_NAME, "connected", 40000)
      final = "connected"
    } catch {
      const st = await mcpStatus().catch(() => ({}))
      final = `not-connected(status=${st[JSON_NAME] || "?"} error=${String(st[JSON_NAME + "#error"] || "").slice(0, 140)})`
    }
    log(`DIAGNOSTIC json_mode_status=${final} — reported, not asserted (SSE mode is what G16 measures)`)
    await remove(JSON_NAME)
    return final
  } catch (e) {
    log(`DIAGNOSTIC json_mode_status=error:${String((e && e.message) || e).slice(0, 120)}`)
    return "error"
  }
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
  // Idempotent start: tear down our own entries if a previous run left them
  // configured, so "no remote tools before the servers were added" measures the
  // pre-state of THIS run rather than whatever a stale session left behind.
  for (const n of [HTTP_NAME, SSE_NAME, DEAD_NAME]) {
    const r = await post(`/mcp/${n}/disconnect`)
    if (r.ok) log(`pre-cleanup: disconnected ${n} from an earlier run`)
  }

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
  // Two ids per tool are expected: one per connected remote server (the http one
  // and the sse one), named <sanitized-server>_<tool> by upstream.
  for (const want of ["remote_echo", "remote_marker"]) {
    const hits = added.filter((id) => id.includes(want))
    assert(hits.length >= 2, `${want} present for BOTH remote servers (got ${hits.join(",") || "none"})`)
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
  await jsonModeProbe().catch(() => {})
  try {
    log("final mcp status: " + JSON.stringify(await mcpStatus()))
    // The fixture's own counters after the failed attempt: `mcp:0` here means no session
    // was ever established (so upstream's client never completed `initialize`), while a
    // non-zero count with a `failed` status means the session existed and `tools/list` is
    // what upstream could not use. Either way the label alone would have hidden it.
    log("fixture snapshot after: " + (await fixtureSnapshot()))
  } catch {}
}
await gateResult(ok, "G16")
