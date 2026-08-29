// gate-11-stream.js — G11: OpenCode streaming/SSE/event flow works.
// Part 1 (always): subscribe to /global/event SSE, trigger a real shell
//   execution, assert lifecycle events stream in before completion.
// Part 2 (model mode): trigger a real agent turn and assert text parts stream
//   in progressively (multiple updates of the same text part) plus status
//   transitions busy→idle.
import { post, createSession, promptAsync, waitTurnComplete, sseWatch, watchToolParts, watchTextParts, eventProperties, assert, log, gateResult } from "./gates-lib.js"

const MODEL = process.argv[2] === "1"
let ok = true

// ---- part 1: SSE + shell execution ----
{
  const sid = await createSession("G11 stream")
  const seen = []
  const watch = sseWatch({ onEvent: (t) => seen.push(t) })
  const r = await post(`/session/${sid}/shell`, { agent: "build", command: "echo G11_STREAM_EVENT_OK" })
  log(`shell HTTP ${r.status}`)
  if (!r.ok) { log("shell failed: " + r.text.slice(0, 300)); ok = false }
  await waitTurnComplete(sid, { timeoutMs: 60000 })
  await new Promise((res) => setTimeout(res, 2000))
  watch.stop()
  await watch.done
  const counts = seen.reduce((a, t) => { a[t] = (a[t] ?? 0) + 1; return a }, {})
  log("events seen (" + seen.length + "): " + JSON.stringify(counts))
  try {
    assert(seen.includes("message.part.updated"), "part update events streamed")
    assert(seen.includes("message.updated"), "message update events streamed")
    assert(seen.includes("session.status"), "session.status events streamed")
    assert(seen.length >= 4, "multiple events streamed (" + seen.length + ")")
    log("G11_SSE_OK")
  } catch (e) { log(e.message); ok = false }
}

// ---- part 2 (model mode): streaming text during a real agent turn ----
if (MODEL) {
  const sid = await createSession("G11 model stream")
  const watch = sseWatch({ timeoutMs: 180000 })
  await promptAsync(sid, "Say exactly: G11_STREAM_MODEL_OK")
  const { messages, failed } = await waitTurnComplete(sid, { timeoutMs: 180000 })
  watch.stop()
  const { events } = await watch.done
  const textParts = watchTextParts(events)
  const statuses = events.filter((e) => e.type === "session.status").map((e) => eventProperties(e.data?.payload)?.status?.type)
  log("model events seen (" + events.length + "): " + JSON.stringify(events.map((e) => e.type)))
  log("text parts streamed: " + JSON.stringify(textParts.map((t) => ({ id: t.id, updates: t.updates, text: t.text.slice(0, 60) }))))
  log("session.status sequence: " + JSON.stringify(statuses))
  try {
    assert(!failed, "agent turn did not fail")
    assert(textParts.some((t) => t.text.includes("G11_STREAM_MODEL_OK")), "streamed text contains the reply")
    assert(textParts.some((t) => t.updates >= 2), "text part streamed progressively (>=2 updates)")
    assert(statuses.includes("busy") && statuses.includes("idle"), "status busy→idle observed")
    log("G11_MODEL_STREAM_OK")
  } catch (e) { log(e.message); ok = false }
} else {
  log("(model mode skipped: no OPENROUTER_API_KEY — text-delta streaming not exercised)")
}

await gateResult(ok, "G11")
