// launch-server.js — starts the real OpenCode server on the emulator.
// Mirrors the desktop sidecar (packages/desktop/src/main/server.ts): imports the
// server bundle and calls Server.listen. Reads the model key from a 600-perm
// file (never from a command line), so the key never shows up in logs.
const GATES_DIR = process.env.GATES_DIR ?? "/data/local/tmp/gates"
const bundlePath = process.env.OPENCODE_BUNDLE ?? `${GATES_DIR}/opencode/dist/node/node.js`
const port = Number(process.env.OPENCODE_SERVER_PORT ?? "4111")

process.env.OPENCODE_SERVER_USERNAME ??= "opencode"
process.env.OPENCODE_SERVER_PASSWORD ??= "gates-password"
if (!process.env.OPENROUTER_API_KEY) {
  try {
    const key = require("fs").readFileSync(`${GATES_DIR}/.api-key`, "utf8").trim()
    if (key) process.env.OPENROUTER_API_KEY = key
  } catch {}
}

const { Server } = await import(bundlePath)
const listener = await Server.listen({ port, hostname: "127.0.0.1", cors: [] })
console.log("SERVER_READY " + listener.url)
process.on("SIGTERM", async () => { await listener.stop(); process.exit(0) })
process.on("SIGINT", async () => { await listener.stop(); process.exit(0) })
