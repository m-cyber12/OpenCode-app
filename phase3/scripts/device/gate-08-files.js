// gate-08-files.js — G8: OpenCode can read/write project files.
// Read path: the real /file + /file/content + /find (ripgrep) endpoints.
// Write path: real write via the server's shell endpoint; in model mode also
// through the agent's write tool with permission auto-approval.
import { call, get, post, createSession, promptAsync, waitTurnComplete, assistantText, makePermissionAutoReplier, sseWatch, assert, log, gateResult } from "./gates-lib.js"

const MODEL = process.argv[2] === "1"
let ok = true

// ---- read via real OpenCode file endpoints ----
const list = await get("/file?path=.")
log(`list HTTP ${list.status}`)
try {
  assert(list.ok, "file list ok")
  const nodes = JSON.parse(list.text)
  const names = (Array.isArray(nodes) ? nodes : []).map((n) => n.name)
  log("files: " + names.join(", "))
  assert(names.includes("README.md"), "README.md listed")
  assert(names.includes("notes.txt"), "notes.txt listed")
  log("G8_READ_OK")
} catch (e) { log(e.message); ok = false }

const content = await get("/file/content?path=README.md")
try {
  assert(content.ok, "file content ok")
  const body = JSON.parse(content.text)
  log("README head: " + body.content.slice(0, 80).replace(/\n/g, " "))
  assert(body.content.includes("G15-E2E-MARKER"), "README content readable")
  log("G8_READ_OK")
} catch (e) { log(e.message); ok = false }

const find = await get("/find?pattern=G15-E2E-MARKER")
try {
  assert(find.ok, "find (ripgrep) ok")
  const matches = JSON.parse(find.text)
  log("rg matches: " + JSON.stringify((Array.isArray(matches) ? matches : []).map((m) => m.path?.text)))
  assert((Array.isArray(matches) ? matches : []).length >= 1, "ripgrep found the marker")
} catch (e) { log(e.message); ok = false }

// ---- write via the server's real shell execution ----
const sessionID = await createSession("G8 files")
const agent = "build"
const wr = await post(`/session/${sessionID}/shell`, { agent, command: "printf 'G8_WRITE_OK\\n' > g8-write.txt && printf 'second line\\n' >> g8-write.txt" })
log(`shell write HTTP ${wr.status}`)
if (wr.ok) {
  await waitTurnComplete(sessionID, { timeoutMs: 60000 })
  const wc = await get("/file/content?path=g8-write.txt")
  try {
    assert(wc.ok, "written file readable")
    const body = JSON.parse(wc.text)
    log("g8-write.txt: " + body.content.replace(/\n/g, " | "))
    assert(body.content.includes("G8_WRITE_OK"), "file content written through OpenCode shell exec")
    log("G8_WRITE_OK")
  } catch (e) { log(e.message); ok = false }
} else { log("shell write failed: " + wr.text.slice(0, 300)); ok = false }

// ---- (model mode) write via the agent's real write tool ----
if (MODEL) {
  const sid = await createSession("G8 agent write")
  const auto = makePermissionAutoReplier({ log, sessionID: sid })
  const watch = sseWatch({ timeoutMs: 600000, onEvent: (t, d) => auto.handle(t, d) })
  await promptAsync(sid, "Write the file g8-agent.txt containing exactly the text G8_AGENT_WRITE_OK. Use the write tool.")
  const { messages } = await waitTurnComplete(sid, { timeoutMs: 180000 })
  watch.stop()
  await watch.done
  log("permissions allowed: " + JSON.stringify(auto.allowed))
  const ac = await get("/file/content?path=g8-agent.txt")
  try {
    assert(ac.ok, "agent-written file readable")
    const body = JSON.parse(ac.text)
    log("g8-agent.txt: " + body.content.replace(/\n/g, " | "))
    assert(body.content.includes("G8_AGENT_WRITE_OK"), "agent write tool wrote the file")
  } catch (e) { log(e.message); ok = false }
} else {
  log("(model mode skipped: no OPENROUTER_API_KEY — agent write-tool part not run)")
}

await gateResult(ok, "G8")
