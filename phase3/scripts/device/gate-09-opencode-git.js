// gate-09-opencode-git.js — G9 (integration half): git executed THROUGH the real
// OpenCode server (the /session/:id/shell endpoint spawns the shell inside the
// server process; the shell resolves the same bundled static git from PATH).
import { post, createSession, waitTurnComplete, toolParts, toolOutput, log } from "./gates-lib.js"

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
