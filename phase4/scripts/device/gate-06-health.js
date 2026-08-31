// gate-06-health.js — G6: real health endpoint check (same call the desktop app makes).
import { call } from "./gates-lib.js"

const r = await call("GET", "/global/health")
console.log(`HTTP ${r.status} /global/health -> ${r.text.slice(0, 300)}`)
const body = JSON.parse(r.text)
const ok = r.status === 200 && body.healthy === true && typeof body.version === "string"
console.log(ok ? "G6_PASS" : "G6_FAIL")
process.exit(ok ? 0 : 1)
