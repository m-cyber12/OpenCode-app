// p8-upgrade-tools.js — prove an existing app-data tree gains the current tools.
//
// The host gate deliberately leaves the current extraction marker in place but
// removes the Python stdlib, the Node/npm/Python links, and writes the exact stale
// "NO node" brief shipped by the previous build. After relaunch this driver uses
// OpenCode's own /shell endpoint — the same environment/path as the bash tool —
// and accepts only real output from every restored capability.

import { createSession, post } from "./gates-lib.js"

const checks = [
  "BRIEF_AGENT_OK",
  "SHELL_COREUTILS_AGENT_OK",
  "BUN_AGENT_OK",
  "GIT_AGENT_OK",
  "RG_AGENT_OK",
  "CURL_AGENT_OK",
  "WGET_AGENT_OK",
  "JQ_AGENT_OK",
  "OPENSSH_AGENT_OK",
  "NODE_AGENT_OK v24.",
  "NPM_AGENT_OK 11.",
  "PYTHON_AGENT_OK 3.13.",
]

const command = [
  "grep -q 'Node.js 24' \"$XDG_CONFIG_HOME/opencode/environment.md\" && ! grep -qi 'NO node' \"$XDG_CONFIG_HOME/opencode/environment.md\" && printf 'BRIEF_AGENT_OK\\n'",
  "sha256sum </dev/null >/dev/null && printf 'SHELL_COREUTILS_AGENT_OK\\n'",
  "bun --version >/dev/null && printf 'BUN_AGENT_OK\\n'",
  "git --version >/dev/null && printf 'GIT_AGENT_OK\\n'",
  "rg --version >/dev/null && printf 'RG_AGENT_OK\\n'",
  "curl --version >/dev/null && printf 'CURL_AGENT_OK\\n'",
  "wget --version >/dev/null && printf 'WGET_AGENT_OK\\n'",
  "jq --version >/dev/null && printf 'JQ_AGENT_OK\\n'",
  "ssh -V >/dev/null 2>&1 && printf 'OPENSSH_AGENT_OK\\n'",
  "node -e \"console.log('NODE_AGENT_OK', process.version)\"",
  "printf 'NPM_AGENT_OK '; npm --version",
  "python3 -c \"import sys; print('PYTHON_AGENT_OK', sys.version.split()[0])\"",
].join("; ")

async function main() {
  const sid = await createSession("p8 upgrade tool restoration")
  const response = await post(`/session/${sid}/shell`, { command, agent: "build" })
  if (!response.ok) {
    console.log(`P8UPGRADETOOLS ok=0 http=${response.status} missing=all`)
    process.exitCode = 1
    return
  }
  const missing = checks.filter((needle) => !response.text.includes(needle))
  const compact = response.text.replace(/\s+/g, " ").slice(0, 600)
  console.log(
    `P8UPGRADETOOLS ok=${missing.length === 0 ? 1 : 0} checks=${checks.length - missing.length}/${checks.length} ` +
      `missing=${missing.length ? missing.join(",") : "none"} output=${compact}`,
  )
  if (missing.length) process.exitCode = 1
}

await main().catch((error) => {
  console.log(`P8UPGRADETOOLS ok=0 error=${String(error?.message || error).slice(0, 240)}`)
  process.exitCode = 1
})
