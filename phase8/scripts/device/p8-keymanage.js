// p8-keymanage.js — re-provision / revoke the run's provider credential
// (Phase 8, host-driven).
//
// Why this exists: the instrumented CLEANUP gate (stage C) removes the short-
// lived CI key from the Keystore and the OpenCode auth store, but the host
// driver gates of stages D/E (session persistence, network loss, large
// project, large history, perf stream) still need REAL model round-trips.
// The host therefore re-pushes the key (it is staged in the harness dir by
// the orchestrator, base64-over-stdin, and deleted again at run end) BEFORE
// stage D, and revokes it AFTER stage E. This driver only ever touches the
// server's own auth store over loopback - the app's Keystore is not involved
// (that side was already proven clean by the instrumented CLEANUP gate).
//
// Modes:
//   provision -> P8KEYPROV ok=<0|1>
//   revoke    -> P8KEYREVOKED ok=<0|1>
//
// The key path comes from the environment (P8_KEY_FILE) so the credential
// never appears on a command line.

import { call, createSession, log } from "./gates-lib.js"

const KEY_FILE = process.env.P8_KEY_FILE || ""

async function main() {
  const mode = process.argv[2]
  if (mode === "provision") {
    if (!KEY_FILE) {
      console.log("P8KEYPROV ok=0 error=no P8_KEY_FILE")
      process.exit(1)
    }
    const raw = await Bun.file(KEY_FILE).text()
    const key = raw.trim()
    if (!key) {
      console.log("P8KEYPROV ok=0 error=empty key file")
      process.exit(1)
    }
    const r = await call("PUT", "/auth/openrouter", { type: "api", key })
    if (!r.ok) {
      console.log(`P8KEYPROV ok=0 error=provider auth push http ${r.status}`)
      process.exit(1)
    }
    console.log("P8KEYPROV ok=1")
    process.exit(0)
  }
  if (mode === "revoke") {
    const r = await call("DELETE", "/auth/openrouter")
    const ok = r.ok && r.text.trim() === "true"
    console.log(`P8KEYREVOKED ok=${ok ? 1 : 0} http=${r.status} body='${r.text.trim().slice(0, 40)}'`)
    process.exit(ok ? 0 : 1)
  }
  if (mode === "probe-net") {
    // Egress diagnostics for the "model silence" question. In two consecutive
    // runs, turns on the openrouter key hung with NO server-side error while
    // the key-free default provider answered in stage A. A 401/402 from the
    // key would surface as an error; pure silence means the request never
    // completes - so probe each host directly from the app's own network
    // namespace: per-host results separate a bad key/account (hosts reachable,
    // only the model turn hangs) from emulator egress problems (host(s)
    // unreachable).
    const hosts = [
      ["openrouter", "https://openrouter.ai/api/v1/models"],
      ["opencode", "https://opencode.ai/"],
      ["control", "https://www.google.com/"],
    ]
    for (const [name, url] of hosts) {
      const t0 = Date.now()
      try {
        const r = await fetch(url, { signal: AbortSignal.timeout(15000) })
        await r.body?.cancel?.()
        console.log(`P8NETPROBE ${name} http=${r.status} ms=${Date.now() - t0}`)
      } catch (e) {
        console.log(`P8NETPROBE ${name} error=${String(e.name || e.message || e).slice(0, 80)} ms=${Date.now() - t0}`)
      }
    }
    // Round 10: the AUTHENTICATED variant. /auth/key answers 401 for a dead
    // key and 200 + metadata (incl. is_free/remaining) for a live one, so one
    // line separates "bad key" from "egress problem" AND tells us whether the
    // account is free-tier (tool-call capable models may need credits).
    const keyFile = process.env.P8_KEY_FILE || ""
    if (keyFile) {
      let key = ""
      try { key = (await Bun.file(keyFile).text()).trim() } catch {}
      if (key) {
        const t0 = Date.now()
        try {
          const r = await fetch("https://openrouter.ai/api/v1/auth/key", {
            headers: { Authorization: "Bearer " + key },
            signal: AbortSignal.timeout(15000),
          })
          let info = ""
          try {
            const j = await r.json()
            const d = (j && j.data) || {}
            info = ` free=${d.is_free ?? "?"} remaining=${d.remaining ?? "?"} limit=${d.limit ?? "?"}`
          } catch {}
          console.log(`P8NETPROBE_AUTH net http=${r.status}${info} ms=${Date.now() - t0}`)
        } catch (e) {
          console.log(`P8NETPROBE_AUTH net error=${String(e.name || e.message || e).slice(0, 80)} ms=${Date.now() - t0}`)
        }
      }
    }
    process.exit(0)
  }
  if (mode === "probe-server") {
    // The decisive version of probe-net: run the SAME probes in the SERVER's
    // own process context. Round 8 showed the run-as shell reaches openrouter
    // (http=200) while the server's model POSTs failed with APIError status=0
    // in some windows and worked in others (P8HIST 40/40). A child of the
    // server (via OpenCode's own /shell endpoint - the same path the bash tool
    // uses) sees the server's environment, proxy settings, and network state;
    // comparing it against the run-as probe isolates the server process.
    const FILES = process.env.P8_APP_FILES || "/data/data/ai.opencode.android.debug/files"
    const BUN = process.env.OPENCODE_BUN_BIN || (FILES + "/bin/bun")
    const probeSrc = [
      'for (const [n, u] of [["openrouter", "https://openrouter.ai/api/v1/models"],',
      '                       ["opencode", "https://opencode.ai/"],',
      '                       ["control", "https://www.google.com/"]]) {',
      '  const t0 = Date.now()',
      '  try {',
      '    const r = await fetch(u, { signal: AbortSignal.timeout(15000) })',
      '    await r.body?.cancel?.()',
      '    console.log("P8NETPROBE_SERVER " + n + " http=" + r.status + " ms=" + (Date.now() - t0))',
      '  } catch (e) {',
      '    console.log("P8NETPROBE_SERVER " + n + " error=" + String(e.name || e.message || e).slice(0, 60) + " ms=" + (Date.now() - t0))',
      '  }',
      '}',
      `const __kf = "${FILES}/harness/model-key";`,
      'let __key = ""; try { __key = (await Bun.file(__kf).text()).trim(); } catch {}',
      'if (__key) {',
      '  const ta = Date.now()',
      '  try {',
      '    const ra = await fetch("https://openrouter.ai/api/v1/auth/key", { headers: { Authorization: "Bearer " + __key }, signal: AbortSignal.timeout(15000) })',
      '    let info = ""',
      '    try { const j = await ra.json(); const d = (j && j.data) || {}; info = " free=" + (d.is_free ?? "?") + " remaining=" + (d.remaining ?? "?") + " limit=" + (d.limit ?? "?") } catch {}',
      '    console.log("P8NETPROBE_AUTH server http=" + ra.status + info + " ms=" + (Date.now() - ta))',
      '  } catch (e) {',
      '    console.log("P8NETPROBE_AUTH server error=" + String(e.name || e.message || e).slice(0, 60) + " ms=" + (Date.now() - ta))',
      '  }',
      '}',
      "",
    ].join("\n")
    const probePath = FILES + "/tmp/p8netprobe-server.js"
    await Bun.write(probePath, probeSrc)
    const sid = await createSession("p8 netprobe server")
    const r = await call("POST", `/session/${sid}/shell`, {
      command: `${BUN} ${probePath} 2>&1; rm -f ${probePath}`,
      agent: "build",
    })
    if (!r.ok) {
      console.log(`P8NETPROBE_SERVER shell http=${r.status} body=${r.text.slice(0, 200)}`)
      process.exit(0)
    }
    const lines = r.text.split("\n").filter((l) => l.includes("P8NETPROBE_SERVER"))
    if (lines.length === 0) {
      console.log("P8NETPROBE_SERVER shell http=200 body=" + r.text.slice(0, 300))
    } else {
      for (const l of lines) console.log(l.trim())
    }
    process.exit(0)
  }
  log("usage: p8-keymanage.js provision|revoke|probe-net|probe-server")
  process.exit(2)
}

main().catch((e) => {
  console.log("P8KEYPROV ok=0 error=" + String(e.message ?? e).slice(0, 160))
  process.exit(1)
})
