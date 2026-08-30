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
// Light diagnostic: run one real `git --version` THROUGH the server with
// OPENCODE_CHILD_TRACE=1 so the ptrace supervisor logs every modern syscall it
// spoofs to -ENOSYS (the numbers that would otherwise SIGSYS). This is the
// evidence that the ptrace+SECCOMP_RET_TRACE child-shim is intercepting the
// zygote filter's trapped calls in the untrusted_app context. Not a blocker.
let diagSid
try {
  diagSid = await createSession("G9 child-shim trace")
  const cmd =
    `OPENCODE_CHILD_TRACE=1 git --version 2>&1; echo "trace_rc=$?"; ` +
    `OPENCODE_CHILD_PROBE=441 /data/data/ai.opencode.android.debug/files/bin/git 2>&1; ` +
    `echo "probe_rc_441=$?"; echo TRACE_DONE`
  const pr = await post(`/session/${diagSid}/shell`, { agent: "build", command: cmd })
  if (pr.ok) {
    const dm = await waitTurnComplete(diagSid, { timeoutMs: 120000 })
    const dout = toolParts(dm.messages).map(toolOutput).join("\n")
    log("--- child-shim ptrace trace (untrusted_app) ---\n" + dout)
  }
} catch (e) {
  log("child-shim trace error: " + e.message)
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
