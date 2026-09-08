// p8-netloss.js — P8 network-loss-mid-task gate driver (Phase 8).
//
// What it proves: with the runtime healthy and a turn IN FLIGHT, pulling the
// network out from under the device must fail the TURN with a network-shaped
// error (which the app classifies as provider-unreachable, not an auth or
// runtime failure), must not lose the session, and must recover cleanly when
// the network comes back.
//
// Loopback independence is the whole trick: the driver talks to the server on
// 127.0.0.1, which keeps working while wifi is disabled - so the driver still
// sees the server's side of the story while the server's OUTBOUND call to the
// provider dies. That is exactly the "runtime healthy, provider unreachable"
// state Phase 6 required the app to distinguish.
//
// Modes:
//   start  -> create a session, start a multi-second turn, print
//             P8NETSTART <sessionID> <userText> and exit (the host then pulls
//             the network, then runs `wait`)
//   wait <sessionID>  -> poll for the turn's outcome; print
//             P8NETRESULT status=<error|complete> errName=<..> errText=<..> userMsgs=<n>
//   recover <sessionID> -> network restored: one small fresh prompt in the SAME
//             session; the session must still be usable. Prints
//             P8NETRECOVER ok=<0|1> <detail>

import { call, get, post, createSession, promptAsync, waitTurnComplete, assistantText, log } from "./gates-lib.js"

const [mode, sessionArg] = process.argv.slice(2)

async function firstAssistantError(sessionID) {
  const r = await get(`/session/${sessionID}/message`)
  if (!r.ok) return null
  const messages = JSON.parse(r.text)
  const msgs = Array.isArray(messages) ? messages : messages.messages ?? []
  for (const m of msgs) {
    const info = m.info ?? m
    const err = info.error
    if (err && (err.name || err.message)) {
      return { name: err.name ?? "", message: (err.message ?? "").slice(0, 300), status: err.statusCode ?? 0 }
    }
    for (const p of m.parts ?? []) {
      if (p.state?.status === "error" || p.state?.status === "failed") {
        return {
          name: "part-error",
          message: JSON.stringify(p.state?.error ?? p.error ?? p.state ?? {}).slice(0, 300),
          status: 0,
        }
      }
    }
  }
  return null
}

async function userMessageCount(sessionID, needle) {
  const r = await get(`/session/${sessionID}/message`)
  if (!r.ok) return -1
  const messages = JSON.parse(r.text)
  const msgs = Array.isArray(messages) ? messages : messages.messages ?? []
  return msgs.filter((m) => (m.info?.role ?? m.role) === "user" &&
    (m.parts ?? []).some((p) => p.type === "text" && (p.text ?? "").includes(needle))).length
}

if (mode === "start") {
  const marker = "P8NET" + (Date.now() % 100000)
  const s = await createSession("p8 netloss") // returns the id STRING
  const prompt = "Write a short, slow explanation that counts from 1 to 30. " +
    `Include the token ${marker} on the first line. Do not stop early.`
  await promptAsync(s, prompt)
  await new Promise((r) => setTimeout(r, 3000)) // let the turn get in flight
  console.log(`P8NETSTART ${s} ${marker}`)
  process.exit(0)
}

if (mode === "wait") {
  const sessionID = sessionArg
  const deadline = Date.now() + Number(process.env.P8_NET_WAIT_TIMEOUT || 300) * 1000
  let outcome = null
  let sawError = null
  while (Date.now() < deadline) {
    sawError = await firstAssistantError(sessionID)
    if (sawError) { outcome = "error"; break }
    const r = await get(`/session/${sessionID}/message`)
    if (r.ok) {
      const messages = JSON.parse(r.text)
      const msgs = Array.isArray(messages) ? messages : messages.messages ?? []
      const last = msgs[msgs.length - 1]
      const info = last?.info ?? last
      if (info?.role === "assistant" && info?.time?.completed) { outcome = "complete"; break }
    }
    await new Promise((r) => setTimeout(r, 2000))
  }
  const userMsgs = await userMessageCount(sessionID, "P8NET")
  console.log(
    `P8NETRESULT status=${outcome ?? "timeout"} errName=${sawError?.name ?? "-"} ` +
    `errText=${(sawError?.message ?? "-").replace(/\s+/g, " ")} userMsgs=${userMsgs}`,
  )
  process.exit(outcome ? 0 : 1)
}

if (mode === "recover") {
  const sessionID = sessionArg
  try {
    const status = await post(`/session/${sessionID}/prompt_async`, {
      parts: [{ type: "text", text: "Reply with exactly: P8NETRECOVERED and nothing else." }],
    })
    if (status.status !== 204 && status.status !== 200) throw new Error("prompt answered http " + status.status + " " + status.text.slice(0, 120))
    const done = await waitTurnComplete(sessionID, { timeoutMs: 180000 })
    const text = assistantText(done.messages)
    const ok = text.includes("P8NETRECOVERED") && !done.failed
    console.log(`P8NETRECOVER ok=${ok ? 1 : 0} replyChars=${text.length} failed=${done.failed}`)
    process.exit(ok ? 0 : 1)
  } catch (e) {
    console.log(`P8NETRECOVER ok=0 error=${String(e.message ?? e).slice(0, 200)}`)
    process.exit(1)
  }
}

console.log("P8NETUSAGE unknown mode: " + mode)
process.exit(2)
