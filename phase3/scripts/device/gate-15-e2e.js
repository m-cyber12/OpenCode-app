// gate-15-e2e.js — G15: end-to-end. "Inspect this project and explain what it
// does" through the REAL agent loop: real model (OpenRouter), real shell, real
// Git, real ripgrep, real file tools, real permission flow (auto-approved).
import { createSession, promptAsync, waitTurnComplete, assistantText, toolParts, toolOutput, makePermissionAutoReplier, sseWatch, watchToolParts, assert, log, gateResult } from "./gates-lib.js"

let ok = true
const sid = await createSession("G15 e2e")

const seen = []
const auto = makePermissionAutoReplier({ log })
const watch = sseWatch({
  timeoutMs: 300000,
  onEvent: (t, d) => { seen.push(t); auto.handle(t, d) },
})

log("--- prompting the real agent ---")
await promptAsync(sid, "Inspect this project and explain what it does.")

const { messages, failed } = await waitTurnComplete(sid, { timeoutMs: 300000 })
watch.stop()
const { events } = await watch.done

const text = assistantText(messages)
const tools = toolParts(messages)
const toolNames = tools.map((p) => (typeof p.tool === "string" ? p.tool : p.tool?.name ?? "?"))
const outputs = tools.map((p) => toolOutput(p).slice(0, 400)).join("\n---\n")
const streamedTools = watchToolParts(events)

log("--- assistant reply (" + text.length + " chars) ---")
log(text)
log("--- tools used: " + JSON.stringify(toolNames) + " ---")
log("--- tool outputs ---")
log(outputs)
log("--- events seen (" + seen.length + "): " + JSON.stringify(seen.reduce((a, t) => { a[t] = (a[t] ?? 0) + 1; return a }, {})))
log("--- tools streamed as events: " + JSON.stringify(streamedTools.parts))
log("--- permissions allowed: " + JSON.stringify(auto.allowed.map((a) => a.permission)))

try {
  assert(!failed, "agent turn did not fail")
  assert(text.length > 120, "explanation is substantive (" + text.length + " chars)")
  assert(
    text.includes("G15-E2E-MARKER") || text.includes("Android") || text.toLowerCase().includes("gate") || text.toLowerCase().includes("opencode"),
    "explanation reflects the actual project",
  )
  const realTools = toolNames.filter((n) => ["bash", "read", "glob", "grep", "write", "edit", "list"].includes(n))
  assert(realTools.length >= 1, "real tools executed: " + JSON.stringify(realTools))
  assert(streamedTools.parts.length >= 1, "tool parts streamed over SSE")
  assert(seen.includes("session.status"), "session.status events streamed")
  assert(seen.includes("message.updated"), "message events streamed")
} catch (e) { log(e.message); ok = false }

await gateResult(ok, "G15")
