// launch-server.js — starts the real OpenCode server, mirroring the desktop sidecar
// (packages/desktop/src/main/sidecar.ts: start()). Runs under Bun-for-Android.
const bundlePath = process.env.OPENCODE_BUNDLE ?? "/data/local/tmp/spike/opencode/dist/node/node.js"
process.env.OPENCODE_SERVER_USERNAME ??= "opencode"
process.env.OPENCODE_SERVER_PASSWORD ??= "spike-password"
const { Server } = await import(bundlePath)
const listener = await Server.listen({ port: 4111, hostname: "127.0.0.1", cors: [] })
console.log("SERVER_READY " + listener.url)
process.on("SIGTERM", async () => { await listener.stop(); process.exit(0) })
process.on("SIGINT", async () => { await listener.stop(); process.exit(0) })
