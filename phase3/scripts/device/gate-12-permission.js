// gate-12-permission.js — G12: Permissions/tool approval work (model mode only).
// The bash tool's default permission effect is "ask" (packages/core/src/permission.ts
// evaluate() fallback). We prompt the agent to run a command, wait for the real
// permission.v2.asked event, then reply "once" and verify the command executes.
import { post, get, createSession, promptAsync, waitTurnComplete, assistantText, toolParts, toolOutput, sseWatch, assert, log, gateResult } from "./gates-lib.js"

let ok = true
const sid = await createSession("G12 permission")

// dig a permission request id out of any event payload shape
function findRequestId(value) {
  if (!value || typeof value !== "object") return null
  if (typeof value.id === "string" && value.id.startsWith("per_")) return value
  for (const k of ["data", "syncEvent", "payload", "properties", "permission", "request"]) {
    const hit = findRequestId(value[k])
    if (hit) return hit
  }
  return null
}

const asks = []
const watch = sseWatch({
  timeoutMs: 240000,
  onEvent: (type, data) => {
    if (type !== "permission.asked" && type !== "permission.v2.asked") return
    const req = findRequestId(data)
    if (req) { asks.push(req); log("ASKED id=" + req.id + " permission=" + req.permission + " patterns=" + JSON.stringify(req.patterns)) }
    else log("ASKED (unparsed) " + JSON.stringify(data).slice(0, 300))
  },
})

await promptAsync(sid, "Run the shell command: echo G12_PERM_OK and tell me what it printed.")

// wait for a permission ask (event or /permission list), then reply "once"
const replyDeadline = Date.now() + 120000
let replied = false
while (Date.now() < replyDeadline && !replied) {
  if (asks.length > 0) {
    const req = asks[0]
    const r = await post(`/permission/${req.id}/reply`, { reply: "once" })
    log(`reply ${req.id} HTTP ${r.status} ${r.text.slice(0, 120)}`)
    replied = r.ok
    break
  }
  const lst = await get("/permission")
  if (lst.ok) {
    const pending = JSON.parse(lst.text)
    if (Array.isArray(pending) && pending.length > 0) {
      const req = pending[0]
      log("pending via /permission: " + JSON.stringify(req).slice(0, 300))
      const r = await post(`/permission/${req.id}/reply`, { reply: "once" })
      log(`reply ${req.id} HTTP ${r.status}`)
      replied = r.ok
      break
    }
  }
  await new Promise((res) => setTimeout(res, 2000))
}
try {
  assert(replied, "permission request was asked and replied")
} catch (e) { log(e.message); ok = false }

const { messages, failed } = await waitTurnComplete(sid, { timeoutMs: 180000 })
watch.stop()
await watch.done

const text = assistantText(messages)
const outputs = toolParts(messages, "bash").map(toolOutput).join("\n")
log("--- assistant text ---\n" + text)
log("--- bash tool outputs ---\n" + outputs)
log("asks seen: " + JSON.stringify(asks.map((a) => ({ id: a.id, permission: a.permission }))))
try {
  assert(!failed, "agent turn did not fail")
  assert(text.includes("G12_PERM_OK") || outputs.includes("G12_PERM_OK"), "command output present after approval")
  assert(asks.length >= 1, "permission.v2.asked event observed")
} catch (e) { log(e.message); ok = false }

await gateResult(ok, "G12")
