// gate-07-shell.js — G7: OpenCode executes a shell command.
// Uses the real server endpoint POST /session/:id/shell, which runs the real
// configured shell (/system/bin/sh) via ChildProcess inside the server, then
// records the output as a tool part — no model required.
import { call, post, get, createSession, waitTurnComplete, toolParts, toolOutput, sseWatch, assert, log, gateResult } from "./gates-lib.js"

let ok = true
const sessionID = await createSession("G7 shell exec")
log("G7_SESSION_ID=" + sessionID)
console.log("G7_SESSION_ID=" + sessionID)

// resolve the agent name (default "build"; fall back to the first listed)
let agent = "build"
const probe = await post(`/session/${sessionID}/shell`, { agent, command: "echo probe" })
if (!probe.ok) {
  const m = probe.text.match(/Available agents: ([^"}\]]+)/)
  if (m) {
    agent = m[1].trim().split(/\s*,\s*/)[0]
    log("agent fallback: " + agent)
  } else {
    log("agent probe failed: " + probe.status + " " + probe.text.slice(0, 300))
  }
}

// watch the SSE stream for shell lifecycle events
const seen = new Set()
const toolLifecycle = []
const watch = sseWatch({
  onEvent: (t, frame) => {
    seen.add(t)
    if (t === "message.part.updated") {
      const part = frame?.payload?.properties?.part ?? frame?.payload?.syncEvent?.data?.part
      if (part?.type === "tool" && part.tool === "bash") {
        toolLifecycle.push(part.state?.status)
      }
    }
  },
})

const command = "echo G7_SHELL_OK from=$(pwd) uname=$(uname -m) shell=$0 && ls -1 | head -3"
const r = await post(`/session/${sessionID}/shell`, { agent, command })
log(`shell endpoint HTTP ${r.status}`)
if (!r.ok) { log("shell endpoint failed: " + r.text.slice(0, 400)); ok = false }

const { messages } = await waitTurnComplete(sessionID, { timeoutMs: 60000 })
const parts = toolParts(messages)
log("tool parts: " + parts.length)
for (const p of parts) log("  tool=" + JSON.stringify(p.tool) + " state.status=" + p.state?.status)
const outputs = parts.map(toolOutput).join("\n")
log("--- tool outputs ---\n" + outputs)

try {
  assert(outputs.includes("G7_SHELL_OK"), "shell output contains G7_SHELL_OK")
  assert(parts.some((p) => p.state?.status === "completed"), "tool part completed")
} catch (e) { log(e.message); ok = false }

// let the SSE watcher collect events, then stop it
await new Promise((res) => setTimeout(res, 3000))
watch.stop()
const w = await watch.done
log("SSE events seen: " + JSON.stringify([...seen]))
log("bash tool part lifecycle (streamed): " + JSON.stringify(toolLifecycle))
try {
  assert(seen.has("message.part.updated"), "SSE message.part.updated events streamed")
  assert(seen.has("session.status"), "SSE session.status events streamed")
  assert(toolLifecycle.includes("running") && toolLifecycle.includes("completed"), "bash tool part streamed running→completed")
} catch (e) { log(e.message); ok = false }

await gateResult(ok, "G7")
