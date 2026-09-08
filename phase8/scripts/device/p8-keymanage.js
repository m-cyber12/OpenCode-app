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

import { call, log } from "./gates-lib.js"

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
  log("usage: p8-keymanage.js provision|revoke")
  process.exit(2)
}

main().catch((e) => {
  console.log("P8KEYPROV ok=0 error=" + String(e.message ?? e).slice(0, 160))
  process.exit(1)
})
