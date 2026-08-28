// gates-lib.js — shared helpers for the Phase 3 gate drivers (run under Bun on-device).
// Talks to the REAL OpenCode HTTP API on 127.0.0.1 (the same protocol the desktop app uses).
const BASE = "http://127.0.0.1:4111"
const AUTH = "Basic " + Buffer.from("opencode:gates-password").toString("base64")
const DIRECTORY = "/data/local/tmp/gates/project"

export function log(...args) {
  console.log(...args)
}

export async function call(method, path, body, { directory = DIRECTORY } = {}) {
  const sep = path.includes("?") ? "&" : "?"
  const url = BASE + path + sep + "directory=" + encodeURIComponent(directory)
  const r = await fetch(url, {
    method,
    headers: {
      authorization: AUTH,
      "content-type": "application/json",
      ...(body === undefined ? {} : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await r.text()
  return { status: r.status, text, ok: r.status >= 200 && r.status < 300 }
}

export async function get(path, opts) { return call("GET", path, undefined, opts) }
export async function post(path, body, opts) { return call("POST", path, body, opts) }

export function assert(cond, msg) {
  if (!cond) throw new Error("ASSERT_FAILED: " + msg)
  log("assert ok: " + msg)
}

// Event type derivation for the /global/event stream (verified against the real
// server): frames are `event: message` + `data: {directory, project, payload}`,
// where payload.type is the legacy event type (e.g. "message.updated",
// "message.part.updated", "session.status", "permission.asked"). Durable events
// also appear as payload.type "sync" with payload.syncEvent.type ending in a
// version suffix (".1", ".2"...) which we strip.
export function deriveEventType(payload) {
  if (!payload || typeof payload !== "object") return ""
  const t = payload.syncEvent?.type ?? payload.type ?? ""
  return String(t).replace(/\.\d+$/, "")
}

// property payload for an event frame (legacy: payload.properties; sync: payload.syncEvent.data)
export function eventProperties(payload) {
  return payload?.syncEvent?.data ?? payload?.properties ?? {}
}

// Subscribe to the global SSE event stream. Returns a handle:
//   { done: Promise<{timedOut, events}>, stop(): void, events: [] }
// events entries: { type, data } where type is the derived legacy type and data
// is the full parsed frame. onEvent(type, data) fires per event. stop() aborts
// promptly (call it once the turn you are watching completes).
export function sseWatch({ timeoutMs = 120000, onEvent } = {}) {
  const state = { events: [], stopped: false, timedOut: false }
  const ac = new AbortController()
  let timer
  const finish = (timedOut) => {
    state.timedOut = timedOut
    clearTimeout(timer)
    state.doneResolve({ timedOut, events: state.events })
  }
  state.done = new Promise((res) => { state.doneResolve = res })
  timer = setTimeout(() => { ac.abort(); finish(true) }, timeoutMs)
  let buf = ""
  let data = ""
  const fire = (raw) => {
    let parsed
    try { parsed = JSON.parse(raw) } catch { parsed = raw }
    const type = deriveEventType(parsed?.payload)
    state.events.push({ type, data: parsed })
    try { onEvent?.(type, parsed) } catch (e) { log("sse onEvent error: " + e.message) }
    data = ""
  }
  fetch(BASE + "/global/event", { headers: { authorization: AUTH }, signal: ac.signal })
    .then(async (r) => {
      const reader = r.body.getReader()
      const dec = new TextDecoder()
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buf += dec.decode(value, { stream: true })
        let idx
        while ((idx = buf.indexOf("\n\n")) !== -1) {
          const chunk = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          for (const line of chunk.split("\n")) {
            if (line.startsWith("data:")) data = line.slice(5).trim()
          }
          if (data) fire(data)
        }
      }
    })
    .catch((e) => { if (e.name !== "AbortError") log("sse error: " + e.message) })
    .finally(() => finish(state.timedOut || ac.signal.aborted))
  state.stop = () => {
    if (state.stopped) return
    state.stopped = true
    ac.abort()
  }
  return state
}

// Create a session bound to the fixture directory.
export async function createSession(title) {
  const r = await post("/session", { title })
  if (!r.ok) throw new Error("session create failed: " + r.status + " " + r.text.slice(0, 300))
  const info = JSON.parse(r.text)
  assert(info.id && info.id.startsWith("ses_"), "session id present")
  return info.id
}

// Send a prompt asynchronously (the agent loop starts server-side).
// Payload verified against the pinned upstream (05ea5073): PromptInput is
// { parts: TextPartInput[] } — a bare { text } body is rejected with
// 'Missing key at ["parts"]' (observed on the emulator, CI run #1).
export async function promptAsync(sessionID, text) {
  const r = await post(`/session/${sessionID}/prompt_async`, { parts: [{ type: "text", text }] })
  if (r.status !== 204 && !r.ok) throw new Error("prompt_async failed: " + r.status + " " + r.text.slice(0, 300))
  log("prompt_async accepted (" + r.status + ")")
}

// Poll session messages until the last message is a completed assistant message
// (no part still running). Returns the WithParts array [{info, parts}].
export async function waitTurnComplete(sessionID, { timeoutMs = 240000, pollMs = 3000 } = {}) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const r = await get(`/session/${sessionID}/message`)
    if (r.ok) {
      const messages = JSON.parse(r.text)
      const msgs = Array.isArray(messages) ? messages : messages.messages ?? []
      if (msgs.length > 0) {
        const last = msgs[msgs.length - 1]
        const info = last.info ?? last
        const parts = last.parts ?? []
        const running = parts.some((p) => ["running", "queued", "pending"].includes(p.state?.status))
        const done = info.role === "assistant" && (info.time?.completed || !running)
        const failed = parts.some((p) => p.state?.status === "error" || p.state?.status === "failed")
        if (done || failed) {
          // diagnostic dump — shows exactly what the server stored for this
          // turn (empty text parts, error parts, tool states, ...)
          log("=== messages dump (turn done, failed=" + failed + ") ===")
          log(JSON.stringify(msgs.map((m) => ({
            role: m.info?.role ?? m.role,
            parts: (m.parts ?? []).map((p) => ({
              type: p.type,
              state: p.state?.status,
              text: (p.text ?? "").slice(0, 120),
              tool: typeof p.tool === "string" ? p.tool : p.tool?.name,
              callID: p.callID,
              input: JSON.stringify(p.state?.input ?? p.input ?? null).slice(0, 200),
              output: JSON.stringify(p.state?.output ?? p.state?.metadata?.output ?? null).slice(0, 200),
              error: p.state?.error ?? p.error ?? null,
            })),
          }))).slice(0, 3000))
          return { messages: msgs, failed }
        }
      }
    }
    if (Date.now() > deadline) throw new Error("waitTurnComplete timeout")
    await new Promise((res) => setTimeout(res, pollMs))
  }
}

export function assistantText(messages) {
  return (messages || [])
    .filter((m) => (m.info ?? m).role === "assistant")
    .map((m) => (m.parts ?? []).map((p) => (p.type === "text" ? p.text ?? "" : "")).join(""))
    .join("\n")
}

export function toolParts(messages, toolName) {
  const out = []
  for (const m of messages || []) {
    for (const p of m.parts ?? []) {
      if (p.type === "tool" && (!toolName || p.tool === toolName || p.tool?.name === toolName)) out.push(p)
    }
  }
  return out
}

export function toolOutput(p) {
  const s = p.state ?? {}
  return s.output ?? s.metadata?.output ?? ""
}

// Dig a permission request (id starting with "per_") out of any SSE payload
// shape (legacy frames: payload.properties; durable: payload.syncEvent.data).
export function findPermissionRequest(value) {
  if (!value || typeof value !== "object") return null
  if (typeof value.id === "string" && value.id.startsWith("per_")) return value
  for (const k of ["data", "syncEvent", "payload", "properties", "permission", "request"]) {
    const hit = findPermissionRequest(value[k])
    if (hit) return hit
  }
  return null
}

// Auto-reply to permission requests as they arrive: fires on every
// `permission.asked` (legacy) / `permission.v2.asked` event and replies "once"
// (records what it allowed).
export function makePermissionAutoReplier({ log } = {}) {
  const allowed = []
  return {
    allowed,
    handle: async (type, data) => {
      if (type !== "permission.asked" && type !== "permission.v2.asked") return
      const req = findPermissionRequest(data) ?? {}
      const id = req.id
      if (!id) { log?.("permission asked without id: " + JSON.stringify(data).slice(0, 200)); return }
      log?.(`permission.asked id=${id} session=${req.sessionID ?? "?"} permission=${req.permission ?? "?"} patterns=${JSON.stringify(req.patterns ?? [])}`)
      const r = await post(`/permission/${id}/reply`, { reply: "once" })
      if (!r.ok) log?.("permission reply failed: " + r.status + " " + r.text.slice(0, 200))
      else allowed.push({ id, permission: req.permission, patterns: req.patterns })
    },
  }
}

// Summarize tool-part lifecycle from message.part.updated frames:
// returns [{tool, status, callID}] plus the latest output per call.
export function watchToolParts(events) {
  const seen = []
  const outputs = {}
  for (const e of events) {
    if (e.type !== "message.part.updated") continue
    const part = eventProperties(e.data?.payload)?.part
    if (part?.type !== "tool") continue
    const entry = seen.find((s) => s.callID === part.callID)
    const rec = { tool: part.tool, status: part.state?.status, callID: part.callID, input: part.state?.input }
    if (entry) Object.assign(entry, rec)
    else seen.push(rec)
    if (part.state?.status === "completed") outputs[part.callID] = part.state.output ?? part.state.metadata?.output ?? ""
    else if (part.state?.status === "running" && part.state?.metadata?.output !== undefined) outputs[part.callID] = part.state.metadata.output
  }
  return { parts: seen, outputs }
}

// Track assistant text parts streamed via message.part.updated:
// returns [{partID, text}] merged per part id.
export function watchTextParts(events) {
  const map = new Map()
  for (const e of events) {
    if (e.type !== "message.part.updated") continue
    const part = eventProperties(e.data?.payload)?.part
    if (part?.type !== "text" || part.synthetic) continue
    const prev = map.get(part.id)
    map.set(part.id, { id: part.id, text: part.text ?? "", updates: (prev?.updates ?? 0) + 1 })
  }
  return [...map.values()]
}

export async function gateResult(ok, marker) {
  log(ok ? `${marker}_PASS` : `${marker}_FAIL`)
  process.exit(ok ? 0 : 1)
}

export function now() { return new Date().toISOString() }
