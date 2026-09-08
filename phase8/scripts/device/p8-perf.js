// p8-perf.js — P8 performance-measurement driver (Phase 8).
//
// Measures (on device, as the app uid, against the real loopback server):
//   * session API ops (create / list / status)
//   * file API ops (list / content)
//   * OpenCode's own shell endpoint (the same execution path the bash tool
//     uses - deterministic, model-free)
//   * agent streaming: time-to-first-token + full-turn latency over the SSE
//     stream (default key-free model, or the provisioned model when
//     P8_PERF_MODEL is set - the host then also passes P8_PERF_KEY)
//
// Runtime/server STARTUP timing is NOT measured here: the host parses the
// supervisor's own runtime.log (its lines are timestamped) and the app's
// launch-to-healthy window, because that window begins before this process
// exists. Memory/CPU/storage are host-collected (dumpsys meminfo, top, du).
//
// Prints one line:
//   P8PERF ok=<0|1> model=<..> createMs=.. listMs=.. statusMs=.. filelistMs=..
//   contentMs=.. shellMs=.. ttftMs=.. turnMs=.. streamedParts=<n>

import { call, get, post, createSession, promptAsync, waitTurnComplete, sseWatch, watchTextParts, eventProperties, log } from "./gates-lib.js"

const MODEL = process.env.P8_PERF_MODEL || ""
const KEY = process.env.P8_PERF_KEY || ""

async function timed(fn) {
  const a = Date.now()
  const v = await fn()
  return { v, ms: Date.now() - a }
}

async function main() {
  if (MODEL && KEY) {
    const prov = await call("PUT", `/auth/openrouter`, { type: "api", key: KEY })
    if (!prov.ok) throw new Error("provider auth push http " + prov.status)
  }

  const create = await timed(() => createSession("p8 perf"))
  const list = await timed(async () => {
    const r = await get("/session?limit=50")
    if (!r.ok) throw new Error("list http " + r.status)
    return r.text
  })
  const status = await timed(async () => {
    const r = await get("/session/status")
    if (!r.ok) throw new Error("status http " + r.status)
    return r.text
  })
  const filelist = await timed(async () => {
    const r = await get("/file?path=")
    if (!r.ok) throw new Error("filelist http " + r.status)
    return r.text
  })
  const content = await timed(async () => {
    const r = await get("/file/content?path=README.md")
    if (!r.ok) throw new Error("content http " + r.status)
    return r.text
  })

  // OpenCode's own shell endpoint: the real execution path, no model involved.
  let shellMs = -1
  let shellOk = false
  try {
    const sh = await timed(async () => {
      const r = await post(`/session/${create.v}/shell`, {
        command: "echo P8SHELLPERF && date +%s%3N",
        agent: "build",
      })
      if (!r.ok) throw new Error("shell http " + r.status)
      return r.text
    })
    shellMs = sh.ms
    shellOk = sh.v.includes("P8SHELLPERF")
  } catch (e) {
    log("shell op failed: " + String(e.message ?? e).slice(0, 160))
  }

  // Streaming TTFT + full turn over the real SSE stream. onEvent fires the
  // moment a frame arrives, so "time of first text part-update" is a real
  // measured TTFT, not a reconstruction.
  let ttftMs = -1
  let turnMs = -1
  let streamedParts = 0
  let streamTimedOut = false
  const modelUsed = MODEL || "default(key-free)"
  try {
    let firstTextAt = -1
    const t0 = Date.now()
    const watch = sseWatch({
      timeoutMs: 240000,
      onEvent: (type, data) => {
        if (firstTextAt < 0 && type === "message.part.updated") {
          const part = eventProperties(data?.payload)?.part
          if (part && (part.type === "text" || typeof part.text === "string")) firstTextAt = Date.now()
        }
      },
    })
    const body = { parts: [{ type: "text", text: "Reply with exactly: P8PERFSTREAM and nothing else." }] }
    if (MODEL) {
      // The harness file holds the OpenRouter model id; some ids already carry
      // a "openrouter/" prefix. Strip ONLY that prefix (OpenCodeApi.ModelRef's
      // bareModelID rule) - OpenRouter model ids themselves contain slashes.
      let bare = MODEL
      if (bare.startsWith("openrouter/")) bare = bare.slice("openrouter/".length)
      body.model = { providerID: "openrouter", modelID: bare }
    }
    const pr = await post(`/session/${create.v}/prompt_async`, body)
    if (pr.status !== 204 && pr.status !== 200) throw new Error("prompt http " + pr.status + " " + pr.text.slice(0, 120))
    const { events, timedOut } = await watch.done
    streamTimedOut = timedOut
    turnMs = Date.now() - t0
    streamedParts = watchTextParts(events).length
    ttftMs = firstTextAt > 0 ? firstTextAt - t0 : -1
  } catch (e) {
    log("stream failed: " + String(e.message ?? e).slice(0, 160))
  }

  const ok = create.ms >= 0 && list.ms >= 0 && status.ms >= 0 && filelist.ms >= 0 &&
    content.ms >= 0 && shellOk && turnMs > 0 && !streamTimedOut
  console.log(
    `P8PERF ok=${ok ? 1 : 0} model=${modelUsed} createMs=${create.ms} listMs=${list.ms} statusMs=${status.ms} ` +
    `filelistMs=${filelist.ms} contentMs=${content.ms} shellMs=${shellMs} shellOk=${shellOk ? 1 : 0} ` +
    `ttftMs=${ttftMs} turnMs=${turnMs} streamedParts=${streamedParts} streamTimedOut=${streamTimedOut ? 1 : 0}`,
  )
  process.exit(ok ? 0 : 1)
}

main().catch((e) => {
  console.log(`P8PERF ok=0 error=${String(e.message ?? e).slice(0, 200)}`)
  process.exit(1)
})
