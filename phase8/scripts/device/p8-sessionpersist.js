// p8-sessionpersist.js — P8 session-persistence gate driver (Phase 8).
//
// Sessions are the server's (OpenCode's own SQLite under the app's private
// XDG dirs). The gate proves they survive an APP PROCESS RESTART: the host
// creates a session + user message here, force-stops the app (process AND
// supervisor die with it), relaunches, and asks this driver to verify the
// session and its user message are exactly where they were.
//
// Modes:
//   create -> P8SESCREATE <sessionID> <marker>
//   verify <sessionID> <marker> -> P8SESVRFY ok=<0|1> sessionsSeen=<n>

import { get, post, createSession, promptAsync, log } from "./gates-lib.js"

const [mode, sessionArg, markerArg] = process.argv.slice(2)

if (mode === "create") {
  const marker = "P8SESS" + (Date.now() % 1000000)
  const s = await createSession("p8 persistence")
  // The turn may or may not complete (model availability is irrelevant here):
  // the USER message is recorded by the server at queue time, and that is what
  // must survive the restart. A prompt failure must not sink the gate's
  // evidence - report it, keep the session id, let verify decide.
  let promptErr = ""
  try {
    await promptAsync(s.id, "Remember this marker for later: " + marker + ". Reply ok.")
  } catch (e) {
    promptErr = String(e.message ?? e).slice(0, 120)
    log("prompt after create failed (continuing, persistence is about the stored user message): " + promptErr)
  }
  console.log(`P8SESCREATE ${s.id} ${marker} promptErr=${promptErr}`)
  process.exit(0)
}

if (mode === "verify") {
  const sessionID = sessionArg
  const marker = markerArg
  const r = await get("/session?limit=50")
  let sessions = []
  if (r.ok) {
    const parsed = JSON.parse(r.text)
    sessions = Array.isArray(parsed) ? parsed : parsed.sessions ?? []
  }
  const found = sessions.some((s) => (s.id ?? s.info?.id) === sessionID)
  let userMsgFound = false
  if (found) {
    const mr = await get(`/session/${sessionID}/message`)
    if (mr.ok) {
      const messages = JSON.parse(mr.text)
      const msgs = Array.isArray(messages) ? messages : messages.messages ?? []
      userMsgFound = msgs.some((m) => (m.info?.role ?? m.role) === "user" &&
        (m.parts ?? []).some((p) => p.type === "text" && (p.text ?? "").includes(marker)))
    }
  }
  const ok = found && userMsgFound
  console.log(`P8SESVRFY ok=${ok ? 1 : 0} sessionsSeen=${sessions.length} sessionFound=${found} userMessageFound=${userMsgFound}`)
  process.exit(ok ? 0 : 1)
}

console.log("P8SESSUSAGE unknown mode: " + mode)
process.exit(2)
