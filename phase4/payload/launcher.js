// launcher.js — the embedded OpenCode server entrypoint for the Android app.
//
// Mirrors the desktop sidecar (packages/desktop/src/main/server.ts) and the
// proven Phase 2/3 gate launcher: import the real upstream server bundle
// (packages/opencode/src/node.ts built for bun) and call Server.listen on
// loopback with app-provided auth. All paths/env are seeded by the Kotlin host
// (RuntimeEnv) into this process — HOME/XDG_*/TMPDIR/PATH/SHELL and the server
// password. This is an adaptation/glue layer only; the agent loop, tools,
// sessions, permissions, MCP and server API are the real OpenCode code.
const fs = require("fs");
const path = require("path");

// The Kotlin host (RuntimeEnv) exports these absolute paths explicitly so the
// launcher works regardless of cwd/HOME layout.
// filesDir is the flat payload root (bundle at <filesDir>/opencode/dist/node/
// node.js and deps at <filesDir>/node_modules); HOME is filesDir/home.
const filesDir =
  process.env.OPENCODE_FILES_DIR ||
  path.dirname(process.env.HOME || "/data/data/x/files/home");
const bundlePath =
  process.env.OPENCODE_BUNDLE ||
  path.join(filesDir, "opencode", "dist", "node", "node.js");

const port = Number(process.env.OPENCODE_SERVER_PORT || "4111");
const hostname = process.env.OPENCODE_SERVER_HOSTNAME || "127.0.0.1";
process.env.OPENCODE_SERVER_USERNAME ||= "opencode";
if (!process.env.OPENCODE_SERVER_PASSWORD) {
  // The host always supplies this; hard-fail rather than binding unauthenticated.
  console.error("FATAL: OPENCODE_SERVER_PASSWORD not set by the host");
  process.exit(2);
}

// Optional model key from the app secrets dir (never on a command line).
if (!process.env.OPENROUTER_API_KEY) {
  try {
    const key = fs
      .readFileSync(
        process.env.OPENCODE_API_KEY_FILE ||
          path.join(filesDir, "secrets", "openrouter-api-key"),
        "utf8",
      )
      .trim();
    if (key) process.env.OPENROUTER_API_KEY = key;
  } catch {}
}

async function main() {
  const mod = await import(bundlePath);
  const Server = mod.Server;
  const listener = await Server.listen({ port, hostname, cors: [] });
  console.log("SERVER_READY " + listener.url);
}

let stopping = false;
async function shutdown(sig) {
  if (stopping) process.exit(0);
  stopping = true;
  console.log("SERVER_SHUTDOWN " + sig);
  // The listener is not exported back out; signal via process exit after
  // giving the server a beat to flush (the server's own SIGTERM handling in
  // the imported module performs listener.stop()).
  setTimeout(() => process.exit(0), 2500);
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

main().catch((err) => {
  console.error("SERVER_BOOT_FAILED");
  console.error(err && err.stack ? err.stack : String(err));
  process.exit(1);
});
