// gate-14-reconnect.js — G14: a fresh client reconnects to the recovered session.
// After the server was stopped and restarted (G13) with the same data dirs:
//   * the previous session is still listed (SQLite persistence)
//   * its messages are still retrievable (content recovery)
//   * (model mode) the same session continues — a new prompt lands in the same
//     session and the message list grows (context continuity).
import { get, post, createSession, promptAsync, waitTurnComplete, assistantText, makePermissionAutoReplier, sseWatch, assert, log, gateResult } from "./gates-lib.js"

const MODEL = process.argv[2] === "1"
let ok = true

const sessions = await get("/session")
try {
  assert(sessions.ok, "session list ok")
} catch (e) { log(e.message); ok = false }

const list = JSON.parse(sessions.text)
const titles = (Array.isArray(list) ? list : []).map((s) => s.title)
log("sessions after restart: " + JSON.stringify(titles))

// the G7 session must survive the restart
const g7 = (Array.isArray(list) ? list : []).find((s) => s.title === "G7 shell exec")
try {
  assert(g7 !== undefined, "pre-restart session (G7 shell exec) still listed")
  log("recovered session id: " + g7.id)
} catch (e) { log(e.message); ok = false }

if (g7) {
  const msgs = await get(`/session/${g7.id}/message`)
  try {
    assert(msgs.ok, "session messages retrievable after restart")
    const body = JSON.parse(msgs.text)
    const arr = Array.isArray(body) ? body : body.messages ?? []
    const all = JSON.stringify(arr)
    assert(arr.length >= 2, "message history intact (" + arr.length + " messages)")
    assert(all.includes("G7_SHELL_OK"), "shell output still in history")
    log("G14_HISTORY_OK")
  } catch (e) { log(e.message); ok = false }

  if (MODEL) {
    const before = (() => { try { return JSON.parse(msgs.text).length } catch { return 0 } })()
    const auto = makePermissionAutoReplier({ log, sessionID: g7.id })
    const watch = sseWatch({ timeoutMs: 600000, onEvent: (t, d) => auto.handle(t, d) })
    await promptAsync(g7.id, "Reply with exactly: G14_RESUME_OK")
    const { messages, failed } = await waitTurnComplete(g7.id, { timeoutMs: 180000 })
    watch.stop()
    await watch.done
    const text = assistantText(messages)
    log("--- continued session assistant text ---\n" + text)
    try {
      assert(!failed, "continued turn did not fail")
      assert(text.includes("G14_RESUME_OK"), "new reply present in the SAME session")
      assert(Array.isArray(messages) ? messages.length > before : true, "message list grew")
      log("G14_RESUME_OK")
    } catch (e) { log(e.message); ok = false }
  } else {
    log("(model mode skipped: no OPENROUTER_API_KEY — in-session continuation not exercised)")
  }
}

await gateResult(ok, "G14")
