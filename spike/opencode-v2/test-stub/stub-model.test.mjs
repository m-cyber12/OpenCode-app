// Host test for the scripted model stub. Run: node --test spike/opencode-v2/test-stub/stub-model.test.mjs
import { test } from "node:test"
import assert from "node:assert/strict"
import { startStub, stepFor, sseFor, jsonFor, SCRIPT, FINAL_TEXT } from "./stub-model.mjs"

const user = { role: "user", content: "run the stub" }
const toolResult = (i) => ({ role: "tool", tool_call_id: `call_stub_${i}`, content: "ok" })

test("step counts tool results since the last user message", () => {
  assert.equal(stepFor([user]), 0)
  assert.equal(stepFor([user, { role: "assistant" }, toolResult(0)]), 1)
  // A new user message restarts the script.
  assert.equal(stepFor([user, toolResult(0), toolResult(1), { role: "user", content: "again" }]), 0)
  assert.equal(stepFor(undefined), 0)
})

test("the script runs in order, then answers with text", () => {
  const seen = []
  for (let i = 0; i <= SCRIPT.length; i++) {
    const msgs = [user, ...Array.from({ length: i }, (_, k) => toolResult(k))]
    const sse = sseFor(msgs)
    const m = sse.match(/"name":"([^"]+)","arguments":"((?:[^"\\]|\\.)*)"/)
    seen.push(m ? JSON.parse(`"${m[2]}"`) : `TEXT:${sse.includes(FINAL_TEXT) ? FINAL_TEXT : "?"}`)
  }
  assert.deepEqual(seen, [
    JSON.stringify(SCRIPT[0].args),
    JSON.stringify(SCRIPT[1].args),
    JSON.stringify(SCRIPT[2].args),
    `TEXT:${FINAL_TEXT}`,
  ])
})

test("SSE frames end with [DONE] and every data line is JSON or [DONE]", () => {
  for (const sse of [sseFor([user]), sseFor([user, toolResult(0), toolResult(1), toolResult(2)])]) {
    const lines = sse.split("\n").filter((l) => l.length > 0)
    assert.equal(lines.at(-1), "data: [DONE]")
    for (const line of lines) {
      assert.ok(line.startsWith("data: "), line)
      const body = line.slice(6)
      if (body !== "[DONE]") assert.equal(typeof JSON.parse(body).object, "string")
    }
  }
})

test("a tool call streams the id, name and arguments the provider expects", () => {
  const first = sseFor([user]).split("\n\n")[0]
  const obj = JSON.parse(first.slice(6))
  const call = obj.choices[0].delta.tool_calls[0]
  assert.equal(call.index, 0)
  assert.equal(call.type, "function")
  assert.equal(call.function.name, "shell")
  assert.equal(JSON.parse(call.function.arguments).command, SCRIPT[0].args.command)
})

test("json (non-stream) reply carries the same step", () => {
  const body = jsonFor([user])
  assert.equal(body.choices[0].finish_reason, "tool_calls")
  assert.equal(body.choices[0].message.tool_calls[0].function.name, "shell")
  assert.equal(jsonFor([user, toolResult(0), toolResult(1), toolResult(2)]).choices[0].message.content, FINAL_TEXT)
})

test("HTTP: streamed POST to /v1/chat/completions and the models list", async () => {
  const server = await startStub({ port: 0 })
  try {
    const port = server.address().port
    const base = `http://127.0.0.1:${port}`
    const post = await fetch(`${base}/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer stub" },
      body: JSON.stringify({ model: "stub-model", stream: true, messages: [user] }),
    })
    assert.equal(post.status, 200)
    assert.match(post.headers.get("content-type"), /text\/event-stream/)
    assert.match(await post.text(), /"name":"shell"/)

    const models = await fetch(`${base}/v1/models`)
    assert.equal((await models.json()).data[0].id, "stub-model")

    const bad = await fetch(`${base}/v1/nope`)
    assert.equal(bad.status, 404)
  } finally {
    await new Promise((r) => server.close(r))
  }
})
