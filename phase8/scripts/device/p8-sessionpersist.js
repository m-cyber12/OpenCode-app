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

// Read the user message carrying `marker` back from the server, if present.
async function userMessagePresent(sessionID, marker) {
  const mr = await get(`/session/${sessionID}/message`)
  if (!mr.ok) return false
  let msgs = []
  try {
    const parsed = JSON.parse(mr.text)
    msgs = Array.isArray(parsed) ? parsed : parsed.messages ?? []
  } catch {
    return false
  }
  return msgs.some(
    (m) =>
      (m.info?.role ?? m.role) === "user" &&
      (m.parts ?? []).some((p) => p.type === "text" && (p.text ?? "").includes(marker)),
  )
}

if (mode === "create") {
  const marker = "P8SESS" + (Date.now() % 1000000)
  // createSession returns the session id STRING (not an object) - the whole
  // first two runs sent prompts to /session/undefined/prompt_async (500).
  const sid = await createSession("p8 persistence")
  // The turn may or may not complete (model availability is irrelevant here):
  // the USER message is recorded by the server at queue time, and that is what
  // must survive the restart. A prompt failure must not sink the gate's
  // evidence - report it, keep the session id, let verify decide.
  let promptErr = ""
  try {
    await promptAsync(sid, "Remember this marker for later: " + marker + ". Reply ok.")
  } catch (e) {
    promptErr = String(e.message ?? e).slice(0, 120)
    log("prompt after create failed (continuing, persistence is about the stored user message): " + promptErr)
  }

  // ---- Round 18: PROVE THE PRECONDITION BEFORE THE KILL ---------------------
  // `prompt_async` answers 204 the moment the turn is QUEUED - not when the
  // user message has been written to the session store. The gate used to
  // force-stop the app immediately after that 204, so a FAIL could mean either
  // "persistence is broken" (product bug) or "the message was never stored in
  // the first place" (a race in this harness). Those need opposite fixes, and
  // the old verdict could not tell them apart - which is why
  // `sessionFound=true userMessageFound=false` sat unexplained for 8 rounds.
  //
  // Poll until the server itself reports the user message, and publish that
  // fact. If it is not there before the kill, the later FAIL is OUR race, and
  // `stored=0` says so in the verdict line.
  let stored = false
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    if (await userMessagePresent(sid, marker)) {
      stored = true
      break
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  if (!stored) log("WARNING: the user message was NOT readable before the restart - a later FAIL is this race, not persistence")
  console.log(`P8SESCREATE ${sid} ${marker} promptErr=${promptErr} stored=${stored ? 1 : 0}`)
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
  // Round 18: the store is opened lazily after a cold start, so give the
  // server the same grace on the way back that `create` gave it on the way in.
  // Without this a slow reopen reads as "data lost".
  let userMsgFound = false
  if (found) {
    const deadline = Date.now() + 15000
    while (Date.now() < deadline) {
      if (await userMessagePresent(sessionID, marker)) {
        userMsgFound = true
        break
      }
      await new Promise((r) => setTimeout(r, 500))
    }
  }
  const ok = found && userMsgFound
  console.log(`P8SESVRFY ok=${ok ? 1 : 0} sessionsSeen=${sessions.length} sessionFound=${found} userMessageFound=${userMsgFound}`)
  process.exit(ok ? 0 : 1)
}

console.log("P8SESSUSAGE unknown mode: " + mode)
process.exit(2)
