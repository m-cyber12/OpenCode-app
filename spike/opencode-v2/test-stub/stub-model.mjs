// TEST ONLY (spike/opencode-v2). A scripted OpenAI-compatible chat server on loopback.
//
// Why: the Phase 2 invariants (config guard, no-exec shell rewrite) only run inside a
// model tool call, and no model is reachable on the phone without a paid key. This stub
// stands in for the model. It speaks the streamed /chat/completions format the v2
// openai-compatible provider sends (always `stream: true`), and replays one fixed script
// for every new user message:
//   step 0  shell: create ./stub-hello.sh (a shebang script, not executable)
//   step 1  shell: run ./stub-hello.sh      (no-exec rewrite should run it through sh)
//   step 2  shell: write opencode.json      (config guard should prompt, never auto-allow)
//   step 3+ text: "stub script finished"
// The step is the number of tool results since the last user message, so a new prompt
// starts the script again.
//
// Not a product component. Loopback only. Bearer auth is accepted but not checked.
//
// Run on host:  node stub-model.mjs <port>
import http from "node:http"

export const MODEL_ID = "stub-model"

export const SCRIPT = [
  { name: "shell", args: { command: "printf '#!/bin/sh\\necho stub-ran\\n' > stub-hello.sh" } },
  { name: "shell", args: { command: "./stub-hello.sh" } },
  { name: "shell", args: { command: "printf '{}\\n' > opencode.json" } },
]
export const FINAL_TEXT = "stub script finished"

// Step = tool results since the last user message. Exported for tests.
export function stepFor(messages) {
  const list = Array.isArray(messages) ? messages : []
  const lastUser = list.map((m) => m?.role).lastIndexOf("user")
  return list.slice(lastUser + 1).filter((m) => m?.role === "tool").length
}

function chunk(delta, finish, extra = {}) {
  return `data: ${JSON.stringify({
    id: "stub-completion",
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model: MODEL_ID,
    // A frame with no delta (the usage frame) has an empty choices list, as OpenAI sends it.
    choices: delta === undefined ? [] : [{ index: 0, delta, finish_reason: finish ?? null }],
    ...extra,
  })}\n\n`
}

const USAGE = { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 }

// Build the SSE body for one reply. Exported for tests.
export function sseFor(messages) {
  const step = stepFor(messages)
  const item = SCRIPT[step]
  if (item) {
    const id = `call_stub_${step}`
    return (
      chunk({
        role: "assistant",
        content: null,
        tool_calls: [{ index: 0, id, type: "function", function: { name: item.name, arguments: JSON.stringify(item.args) } }],
      }, undefined) +
      chunk({}, "tool_calls") +
      chunk(undefined, undefined, { usage: USAGE }) +
      "data: [DONE]\n\n"
    )
  }
  return (
    chunk({ role: "assistant", content: FINAL_TEXT }, undefined) +
    chunk({}, "stop") +
    chunk(undefined, undefined, { usage: USAGE }) +
    "data: [DONE]\n\n"
  )
}

// Non-streamed reply, same script. Exported for tests.
export function jsonFor(messages) {
  const step = stepFor(messages)
  const item = SCRIPT[step]
  const message = item
    ? {
        role: "assistant",
        content: null,
        tool_calls: [{ id: `call_stub_${step}`, type: "function", function: { name: item.name, arguments: JSON.stringify(item.args) } }],
      }
    : { role: "assistant", content: FINAL_TEXT }
  return {
    id: "stub-completion",
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: MODEL_ID,
    choices: [{ index: 0, message, finish_reason: item ? "tool_calls" : "stop" }],
    usage: USAGE,
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on("data", (c) => chunks.push(c))
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")))
    req.on("error", reject)
  })
}

export function createStub({ log = () => {} } = {}) {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1")
    if (req.method === "GET" && url.pathname.endsWith("/models")) {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ object: "list", data: [{ id: MODEL_ID, object: "model" }] }))
      return
    }
    if (req.method !== "POST" || !url.pathname.endsWith("/chat/completions")) {
      res.writeHead(404, { "content-type": "application/json" })
      res.end(JSON.stringify({ error: { message: `stub: no route ${req.method} ${url.pathname}` } }))
      return
    }
    let body
    try {
      body = JSON.parse(await readBody(req))
    } catch {
      res.writeHead(400, { "content-type": "application/json" })
      res.end(JSON.stringify({ error: { message: "stub: body is not JSON" } }))
      return
    }
    const messages = body.messages ?? []
    const step = stepFor(messages)
    log(`[stub-model] request step=${step} messages=${messages.length} stream=${body.stream === true}`)
    if (body.stream === true) {
      res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" })
      res.end(sseFor(messages))
    } else {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify(jsonFor(messages)))
    }
  })
}

export function startStub({ port, host = "127.0.0.1", log } = {}) {
  const server = createStub({ log })
  return new Promise((resolve, reject) => {
    server.once("error", reject)
    server.listen(port, host, () => resolve(server))
  })
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.argv[2] ?? 4799)
  startStub({ port, log: (l) => console.error(l) }).then((s) => {
    console.error(`[stub-model] listening on 127.0.0.1:${s.address().port}`)
  })
}
