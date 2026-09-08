// p8-hist.js — P8 large-history gate driver (Phase 8).
//
// Runs N sequential agent turns in ONE session and measures per-turn latency
// plus the tail cost: listing the session's messages once the history is
// large. The gate's claim is about the server + persistence layer surviving a
// long history; the model is a means, not the subject. With P8_HIST_MODEL set
// the turns name that model (the host re-provisions the run's provider
// credential before stage D, so the auth store already holds it); otherwise
// the turns use the server's default model.
//
// Prints: P8HIST ok=<0|1> turns=<n> avgMs=<..> maxMs=<..> failedTurns=<k>
//         tailListMs=<..> tailMessages=<m>

import { get, post, createSession, waitTurnComplete, log } from "./gates-lib.js"

const N = Number(process.env.P8_HIST_TURNS || 40)
const PER_TURN_MS = Number(process.env.P8_HIST_PER_TURN_MS || 120000)
const MODEL = process.env.P8_HIST_MODEL || ""

function modelBody() {
  if (!MODEL) return null
  // The harness file holds the OpenRouter model id; some ids already carry
  // an "openrouter/" prefix. Strip ONLY that prefix (ModelRef.bareModelID
  // rule) - OpenRouter model ids themselves contain slashes.
  let bare = MODEL
  if (bare.startsWith("openrouter/")) bare = bare.slice("openrouter/".length)
  return { model: { providerID: "openrouter", modelID: bare } }
}

async function main() {
  const s = await createSession("p8 history")
  const latencies = []
  let failedTurns = 0
  for (let i = 1; i <= N; i++) {
    const t0 = Date.now()
    try {
      const body = { parts: [{ type: "text", text: `Turn ${i} of ${N}: reply with exactly: P8HIST ${i}` }] }
      const mb = modelBody()
      if (mb) Object.assign(body, mb)
      const pr = await post(`/session/${s.id}/prompt_async`, body)
      if (pr !== 204 && pr !== 200) throw new Error("prompt_async http " + pr)
      const done = await waitTurnComplete(s.id, { timeoutMs: PER_TURN_MS, pollMs: 1500, stablePolls: 2 })
      latencies.push(Date.now() - t0)
      if (done.failed) failedTurns++
    } catch (e) {
      failedTurns++
      latencies.push(Date.now() - t0)
      log(`turn ${i} failed: ${String(e.message ?? e).slice(0, 120)}`)
    }
  }
  const avg = Math.round(latencies.reduce((a, b) => a + b, 0) / Math.max(1, latencies.length))
  const max = Math.max(0, ...latencies)
  const t1 = Date.now()
  const r = await get(`/session/${s.id}/message?limit=200`)
  const tailListMs = Date.now() - t1
  let tailMessages = 0
  if (r.ok) {
    const messages = JSON.parse(r.text)
    tailMessages = (Array.isArray(messages) ? messages : messages.messages ?? []).length
  }
  const ok = failedTurns === 0 && tailMessages >= N * 2
  console.log(
    `P8HIST ok=${ok ? 1 : 0} turns=${N} avgMs=${avg} maxMs=${max} failedTurns=${failedTurns} ` +
    `tailListMs=${tailListMs} tailMessages=${tailMessages}`,
  )
  process.exit(ok ? 0 : 1)
}

main().catch((e) => {
  console.log(`P8HIST ok=0 turns=${N} error=${String(e.message ?? e).slice(0, 200)}`)
  process.exit(1)
})
