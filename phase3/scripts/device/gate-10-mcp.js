// gate-10-mcp.js — G10: MCP stdio child process works.
// 1) OpenCode's own MCP client connects to a real stdio MCP server (the bundled
//    @modelcontextprotocol/sdk server) and reports it connected via /mcp.
// 2) A real SDK client round-trips listTools + callTool over stdio on-device.
// 3) (model mode) the agent uses the MCP echo tool and reports the result.
import { get, createSession, promptAsync, waitTurnComplete, assistantText, makePermissionAutoReplier, sseWatch, assert, log, gateResult } from "./gates-lib.js"
import { spawn } from "node:child_process"

const MODEL = process.argv[2] === "1"
let ok = true
const MCP_NAME = "gates-mcp"

// ---- 1) OpenCode's own MCP client ----
let statusText = ""
let connected = false
for (let i = 0; i < 15; i++) {
  const r = await get("/mcp")
  if (r.ok) {
    statusText = r.text
    try {
      const map = JSON.parse(r.text)
      const st = map[MCP_NAME]
      log(`mcp status attempt ${i}: ${JSON.stringify(st)}`)
      if (st?.status === "connected") { connected = true; break }
      if (st?.status === "failed") { log("MCP connect failed: " + st.error); break }
    } catch (e) { log("mcp parse error: " + e.message) }
  }
  await new Promise((res) => setTimeout(res, 4000))
}
try {
  assert(connected, `OpenCode reports ${MCP_NAME} connected`)
  log("MCP_STATUS_OK " + statusText.slice(0, 200))
} catch (e) { log(e.message); ok = false }

// ---- 2) direct SDK round-trip over stdio (real child process + JSON-RPC) ----
// NOTE: spawn with a missing cwd fails with ENOENT at posix_spawn (observed in
// CI run #1 when mcp/ had not been pushed) — create the cwd defensively.
import { mkdirSync } from "node:fs"
try { mkdirSync("/data/local/tmp/gates/mcp", { recursive: true }) } catch {}
const out = await new Promise((resolve, reject) => {
  const child = spawn("/data/local/tmp/gates/bin/bun", ["/data/local/tmp/gates/mcp/mcp-roundtrip.js"], { cwd: "/data/local/tmp/gates/mcp" })
  let so = "", se = ""
  child.stdout.on("data", (d) => { so += d; process.stdout.write("[mcp-roundtrip] " + d) })
  child.stderr.on("data", (d) => { se += d; process.stderr.write("[mcp-roundtrip:err] " + d) })
  child.on("exit", (code) => resolve({ code, so, se }))
  child.on("error", reject)
})
try {
  assert(out.code === 0, "mcp roundtrip child exited 0 (got " + out.code + ")")
  assert(out.so.includes("G10_MCP_ROUNDTRIP_OK"), "SDK client got the echo tool result")
  assert(out.so.includes('"echo"'), "tools/list includes echo")
} catch (e) { log(e.message); ok = false }

// ---- 3) (model mode) agent uses the MCP tool ----
if (MODEL) {
  const sid = await createSession("G10 mcp agent")
  const auto = makePermissionAutoReplier({ log })
  const watch = sseWatch({ timeoutMs: 600000, onEvent: (t, d) => auto.handle(t, d) })
  await promptAsync(sid, 'Use the MCP tool "echo" with the message G10_MCP_AGENT_OK and tell me what it returned.')
  const { messages, failed } = await waitTurnComplete(sid, { timeoutMs: 180000 })
  watch.stop()
  await watch.done
  const text = assistantText(messages)
  log("--- assistant text ---\n" + text)
  try {
    assert(!failed, "agent turn did not fail")
    assert(text.includes("G10_MCP_AGENT_OK") && text.includes("echo"), "agent reported the MCP tool result")
  } catch (e) { log(e.message); ok = false }
} else {
  log("(model mode skipped: no OPENROUTER_API_KEY — agent MCP-tool part not run)")
}

await gateResult(ok, "G10")
