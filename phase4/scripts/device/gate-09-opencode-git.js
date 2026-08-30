// gate-09-opencode-git.js — G9: the real Android/Bionic Git executable is
// executed THROUGH the real OpenCode server (the /session/:id/shell endpoint).
// The runtime PATH resolves filesDir/bin/git -> nativeLibraryDir/libgit.so;
// no user-installed Git and no static desktop binary are involved.
import { post, createSession, waitTurnComplete, toolParts, toolOutput, log } from "./gates-lib.js"

// Keep a small diagnostic in the server context so a PATH/packaging regression
// is distinguishable from a Git repository failure.
let diagSid
try {
  diagSid = await createSession("G9 Android Git diagnostic")
  const pr = await post(`/session/${diagSid}/shell`, {
    agent: "build",
    command: "command -v git; ls -l \"$(command -v git)\"; git --version; echo G9_GIT_DIAG_DONE",
  })
  if (pr.ok) {
    const dm = await waitTurnComplete(diagSid, { timeoutMs: 90000 })
    const dout = toolParts(dm.messages).map(toolOutput).join("\n")
    log("--- Android Git diagnostic (server context) ---\n" + dout)
  }
} catch (e) {
  log("Android Git diagnostic error: " + e.message)
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
