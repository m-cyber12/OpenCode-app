// launcher-v2.mjs — entry point for the OpenCode v2 server on Android (Node 26).
//
// Usage: node launcher-v2.mjs <path-to-opencode.mjs> --hostname 127.0.0.1 --port <n>
//
// Why a wrapper: the v2 CLI reads the server password from OPENCODE_SERVER_PASSWORD
// in the environment. An environment value is readable through /proc/<pid>/environ by
// every process running as the app uid. This wrapper reads the password from stdin
// (one line, then EOF) and sets it in-process, so it is not in the exec-time
// environment. The v2 CLI already hides the password from child processes
// (packages/cli/src/env.ts session()).
//
// Policy (Phase 5 / owner 2026-10-31): loopback only. Refuse any other bind here as
// well as in the Kotlin host. There is no mDNS flag in the v2 serve command.

import { pathToFileURL } from "node:url"
import { resolve } from "node:path"

export const LOOPBACK = new Set(["127.0.0.1", "localhost", "::1"])
const MIN_PASSWORD_CHARS = 16
const MAX_LINE_BYTES = 4096

// Parse the arguments after the entry path. Only --hostname and --port are accepted.
export function parseArgs(argv) {
  const out = { hostname: undefined, port: undefined }
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i]
    if (flag !== "--hostname" && flag !== "--port") throw new Error(`unsupported argument: ${flag}`)
    const value = argv[++i]
    if (value === undefined) throw new Error(`missing value for ${flag}`)
    if (flag === "--hostname") out.hostname = value
    else out.port = value
  }
  if (!out.hostname) throw new Error("--hostname is required")
  if (!LOOPBACK.has(out.hostname)) throw new Error(`refusing non-loopback bind: ${out.hostname}`)
  const port = Number(out.port)
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`invalid --port: ${out.port}`)
  return { hostname: out.hostname, port }
}

// Read exactly one line from the stream (the password), then stop reading.
export async function readPasswordLine(stream) {
  const chunks = []
  let size = 0
  for await (const chunk of stream) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    const nl = buf.indexOf(0x0a)
    const take = nl === -1 ? buf : buf.subarray(0, nl)
    chunks.push(take)
    size += take.length
    if (size > MAX_LINE_BYTES) throw new Error("password line too long")
    if (nl !== -1) break
  }
  const line = Buffer.concat(chunks).toString("utf8").replace(/\r$/, "")
  if (line.length < MIN_PASSWORD_CHARS) throw new Error("server password missing or too short")
  return line
}

export async function main(argv, stdin) {
  const [entry, ...rest] = argv
  if (!entry) throw new Error("usage: launcher-v2.mjs <opencode.mjs> --hostname H --port P")
  const { hostname, port } = parseArgs(rest)
  const password = await readPasswordLine(stdin)
  stdin.destroy?.()

  process.env.OPENCODE_SERVER_PASSWORD = password
  // The v2 CLI parses process.argv when its module loads.
  process.argv = [process.argv[0], resolve(entry), "serve", "--hostname", hostname, "--port", String(port)]
  await import(pathToFileURL(resolve(entry)).href)
}

// Run only when executed directly, not when a test imports this file.
const isMain = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href
if (isMain) {
  main(process.argv.slice(2), process.stdin).catch((err) => {
    process.stderr.write(`[launcher-v2] ${err?.message ?? err}\n`)
    process.exit(1)
  })
}
