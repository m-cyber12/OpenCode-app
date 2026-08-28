// health-check.js — on-device verification of the real OpenCode HTTP API via bun fetch.
// Exits 0 only when /global/health answers; also exercises sessions + config + spawn.
const base = "http://127.0.0.1:4111"
const auth = "Basic " + btoa("opencode:spike-password")
let ok = true

async function call(path, opts = {}) {
  try {
    const r = await fetch(base + path, {
      headers: { authorization: auth, "content-type": "application/json", ...(opts.headers ?? {}) },
      ...opts,
    })
    const t = await r.text()
    console.log(`HTTP ${r.status} ${path} -> ${t.slice(0, 220)}`)
    if (r.status >= 500) ok = false
    return t
  } catch (e) {
    console.log(`HTTP FAIL ${path} -> ${e.message}`)
    ok = false
    return ""
  }
}

await call("/global/health")
const session = await call("/session", { method: "POST", body: JSON.stringify({ title: "spike-on-device" }) })
if (session.includes("ses_")) await call("/session")
await call("/config")

// spawn test — the same process model the bash tool uses (cross-spawn -> child_process)
const { spawn } = await import("node:child_process")
const child = spawn("/system/bin/sh", ["-c", "echo SPAWN_OK shell=$$ && pwd && ls -la README.md"], {
  cwd: "/data/local/tmp/spike/project",
})
child.stdout.on("data", (d) => console.log("SPAWN stdout: " + d.toString().trim()))
child.stderr.on("data", (d) => console.log("SPAWN stderr: " + d.toString().trim()))
const code = await new Promise((res) => child.on("exit", res))
console.log("SPAWN exit code: " + code)
if (code !== 0) ok = false
console.log(ok ? "HEALTH_CHECK_PASS" : "HEALTH_CHECK_FAIL")
process.exit(ok ? 0 : 1)
