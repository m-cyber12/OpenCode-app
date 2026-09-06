// p5-model-probe.js - decide, by measurement, whether a live agent turn can run.
//
// Why this exists: the Phase 5 re-runs of the Phase 3 drivers (G7/G10/G11/G12) and the
// in-app K2/K5 turns need to know whether a model can actually serve a prompt. Phase 4
// could infer that from the presence of OPENROUTER_API_KEY; Phase 5 is deliberately
// key-free (the pinned build ships the free `opencode/big-pickle` default), so the answer
// has to come from the server itself.
//
// Asking `GET /provider` whether it has a `default` cannot be that answer: upstream
// derives `default` from the models.dev catalog for EVERY provider
// (Provider.defaultModelIDs, packages/opencode/src/provider/provider.ts:1132 ->
// sort(Object.values(item.models))[0].id), so the key is present whether or not anything
// can serve a turn; and `connected`
// (packages/opencode/src/server/routes/instance/httpapi/handlers/provider.ts:60) is
// `id in Provider.list() || auth.json[id]`, which depends on SDK-init behaviour this gate
// must not reason about indirectly.
//
// So: send one tiny prompt through OpenCode's own public API and look for an assistant
// text part. No model name is hardcoded - the server picks its default.
//
// It runs ON DEVICE under the payload's own bun, as the app uid, because the OpenCode
// server is a child of the app process: from inside that process loopback is guaranteed
// reachable, while a host-side `adb forward` may not be (run #14: every host-side request
// died with ECONNRESET the moment an `am instrument` call replaced the app process).
//
// Prints exactly one verdict line:
//   PROBE ok :: model=<id>            -> model-dependent assertions may run
//   PROBE unavailable :: <reason>     -> they stay skipped, with the reason on record
const BASE = process.env.OPENCODE_BASE || "http://127.0.0.1:4111"
const USER = process.env.OPENCODE_SERVER_USERNAME || "opencode"
const PASS = process.env.OPENCODE_SERVER_PASSWORD || ""
const DIR = process.env.OPENCODE_DIRECTORY || ""
const DEADLINE = Date.now() + Number(process.env.P5_MODEL_PROBE_TIMEOUT || 150) * 1000
const START = Date.now()
const TOKEN = "P5PROBEOK"
const PROMPT = `Reply with exactly this token and nothing else: ${TOKEN}`

const auth = "Basic " + btoa(`${USER}:${PASS}`)

async function call(method, path, body) {
  const url = BASE + path + (path.includes("?") ? "&" : "?") + "directory=" + encodeURIComponent(DIR)
  const init = { method, headers: { authorization: auth } }
  if (body !== undefined) {
    init.headers["content-type"] = "application/json"
    init.body = JSON.stringify(body)
  }
  const r = await fetch(url, init)
  const text = await r.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* keep raw below */ }
  return { status: r.status, json, text: text.slice(0, 400) }
}

function bail(reason) {
  console.log(`PROBE unavailable :: ${reason}`)
  process.exit(1)
}

async function main() {
  if (!PASS) bail("no server password in the environment")
  const h = await call("GET", "/global/health")
  if (h.status !== 200 || h.json?.healthy !== true) bail(`health -> http ${h.status} ${h.text}`)
  const created = await call("POST", "/session", { title: "P5 model pre-flight" })
  const sid = created.json?.id
  if (!sid) bail(`POST /session -> http ${created.status} ${created.text}`)
  const sent = await call("POST", `/session/${encodeURIComponent(sid)}/prompt_async`, {
    parts: [{ type: "text", text: PROMPT }],
  })
  if (sent.status !== 200 && sent.status !== 204) bail(`prompt_async -> http ${sent.status} ${sent.text}`)

  while (Date.now() < DEADLINE) {
    await new Promise((r) => setTimeout(r, 3000))
    const got = await call("GET", `/session/${encodeURIComponent(sid)}/message`)
    const items = Array.isArray(got.json) ? got.json : (got.json?.data ?? [])
    for (const m of items) {
      const info = m.info ?? m
      if (info?.role !== "assistant") continue
      if (info.error) bail(`assistant turn errored: ${JSON.stringify(info.error).slice(0, 300)}`)
      const text = (m.parts ?? [])
        .filter((p) => p?.type === "text")
        .map((p) => p.text || "")
        .join("")
      if (text.includes(TOKEN)) {
        console.log(`PROBE ok :: model=${info.modelID || info.model || "?"} exact-token reply, session=${sid}`)
        return
      }
      if (text.trim()) {
        // A chatty model that did not echo the token verbatim still proves the turn ran.
        console.log(`PROBE ok :: model=${info.modelID || info.model || "?"} assistant text present (token not verbatim), session=${sid}`)
        return
      }
    }
  }
  bail(`no assistant reply within ${Math.round((Date.now() - START) / 1000)}s; session=${sid}`)
}

main().catch((e) => bail(`${e?.name || "error"}: ${e?.message || String(e)}`))
