// gate-09-opencode-git.js — G9 (integration half): git executed THROUGH the real
// OpenCode server (the /session/:id/shell endpoint spawns the shell inside the
// server process; the shell resolves the same bundled static git from PATH).
import { post, createSession, waitTurnComplete, toolParts, toolOutput, log } from "./gates-lib.js"

// ---- Diagnostic: which syscall does the static git/rg child trap? ----------
// Run in the REAL untrusted_app context (spawned by the server; run-as uses a
// permissive policy and cannot reproduce the bug). For each candidate nr invoke
// the child-shim probe: it installs our BPF ENOSYS filter then issues the raw
// syscall. ENOSYS (38) / a natural errno => the call is covered/allowed; a
// missing RESULT line (process killed) => Android's outer filter TRAPs it.
let diagSid
try {
  diagSid = await createSession("G9 seccomp probe")
  const nrs = [
    441, 436, 327, 328, 435, 439, 332, 21,          // already mapped (controls)
    // legacy FS syscalls bionic implements via *at (so the app filter traps
    // the raw legacy forms): the static musl git/rg may call these raw. The
    // probe child dies (probe_rc=159, no RESULT line) on the one Android traps.
    2, 4, 6, 82, 83, 84, 87, 88, 89, 90, 92,        // open/stat/lstat/rename/mkdir/rmdir/unlink/symlink/readlink/chmod/chown
    159, 268, 258, 318, 334,                        // getrlimit?/fchmodat/newfstatat/getrandom/rseq
  ]
  const cmd =
    `for n in ${nrs.join(" ")}; do ` +
    `echo "=== nr=$n ==="; OPENCODE_CHILD_PROBE=$n /data/data/ai.opencode.android.debug/files/bin/git 2>&1; ` +
    `echo "probe_rc_$n=$?"; done; echo PROBE_BATCH_DONE`
  const pr = await post(`/session/${diagSid}/shell`, { agent: "build", command: cmd })
  if (pr.ok) {
    const dm = await waitTurnComplete(diagSid, { timeoutMs: 180000 })
    const dout = toolParts(dm.messages).map(toolOutput).join("\n")
    log("--- seccomp syscall probe (untrusted_app) ---\n" + dout)
  }
} catch (e) {
  log("seccomp probe error: " + e.message)
}

const sid = await createSession("G9 git via opencode")
const r = await post(`/session/${sid}/shell`, {
  agent: "build",
  command: "git status --short && echo BRANCHES && git branch && echo LOG && git log --oneline -3",
})
log("shell HTTP " + r.status)
if (r.ok) {
  const { messages } = await waitTurnComplete(sid, { timeoutMs: 90000 })
  const out = toolParts(messages).map(toolOutput).join("\n")
  log("--- git via OpenCode output ---\n" + out)
  if (out.includes("feature/g9") && out.includes("gates fixture")) {
    log("G9_OPENCODE_GIT_OK")
    process.exit(0)
  }
  log("G9_OPENCODE_GIT_MISSING_EXPECTED_OUTPUT")
  process.exit(1)
} else {
  log("shell failed: " + r.text.slice(0, 300))
  process.exit(1)
}
