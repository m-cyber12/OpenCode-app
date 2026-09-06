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

// Phase 5 policy: this launcher exists to serve the Android app over loopback
// ONLY. A non-loopback bind would expose the agent (and its shell/MCP child
// processes) to the local network, and there is no product switch for that yet,
// so it is refused here as well as in the Kotlin host (defense in depth: the env
// var is the only way in, and both sides check it).
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "::1", "ip6-localhost"]);
const requestedHostname = process.env.OPENCODE_SERVER_HOSTNAME || "127.0.0.1";
if (!LOOPBACK_HOSTS.has(requestedHostname)) {
  console.error(
    "SERVER_BIND_REJECTED hostname=" + requestedHostname +
      " reason=loopback-only-policy (allowed: " + [...LOOPBACK_HOSTS].join(",") + ")",
  );
  process.exit(3);
}
const hostname = requestedHostname;
// mDNS discovery is what would advertise this server on the LAN. Upstream only
// publishes when `mdns: true` AND the hostname is non-loopback
// (server.ts setupMdns); we never pass mdns, and the hostname is loopback.
const mdnsRequested = process.env.OPENCODE_MDNS === "1";
if (mdnsRequested) {
  console.error("SERVER_MDNS_REFUSED reason=loopback-only-policy (no LAN discovery while bound to " + hostname + ")");
}
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

// Android app-uid seccomp compatibility (see phase4/payload/native/seccomp-shim.c).
// Android's per-app seccomp filter turns newer syscalls into a fatal SIGSYS
// instead of ENOSYS (observed: x86_64 syscall 441 = epoll_pwait2 killing the
// server on its first event-loop wait). dlopen the tiny NDK-built shim and call
// opencode_seccomp_init() BEFORE the OpenCode bundle is imported; the shim's
// SIGSYS handler then maps every trapped syscall to ENOSYS so Bun's own
// fallbacks engage (epoll_pwait2 -> epoll_pwait, close_range -> fd loop, ...).
// Loaded synchronously so it is active before any server code runs.
function installSeccompShim() {
  const shimPath = process.env.OPENCODE_SECCOMP_SHIM;
  if (!shimPath) {
    console.log("[seccomp] no shim path env (OPENCODE_SECCOMP_SHIM); skipping");
    return;
  }
  if (!fs.existsSync(shimPath)) {
    console.error("[seccomp] shim not found at " + shimPath + "; server may hit SIGSYS");
    return;
  }
  try {
    // Some Bun-for-Android builds provide bun:ffi. dlopen() returns a symbol
    // handle; this is only a backstop because the exec shim already
    // LD_PRELOADs the library before Bun's native startup.
    const { dlopen } = require("bun:ffi");
    const stem = shimPath.endsWith(".so") ? shimPath.slice(0, -3) : shimPath;
    const handle = dlopen(stem, {
      opencode_seccomp_init: { args: [], returns: "int" },
    });
    const rc = handle.symbols.opencode_seccomp_init();
    if (rc === 0) console.log("[seccomp] shim installed OK (" + shimPath + ")");
    else console.error("[seccomp] opencode_seccomp_init returned " + rc);
  } catch (e) {
    console.error("[seccomp] failed to load shim: " + (e && e.message ? e.message : e));
  }
}

async function main() {
  console.error("[launcher] main entered platform=" + process.platform + " arch=" + process.arch);
  installSeccompShim();
  console.error("[launcher] importing OpenCode bundle");
  const mod = await import(bundlePath);
  console.error("[launcher] OpenCode bundle imported");
  const Server = mod.Server;
  console.error("[launcher] binding " + hostname + ":" + port);
  // `cors: []` => no Access-Control-Allow-Origin at all; mdns is intentionally
  // not passed (see above), so nothing is advertised on the local network.
  const listener = await Server.listen({ port, hostname, cors: [] });
  console.log(
    "SERVER_BOUND url=" + listener.url + " hostname=" + hostname + " port=" + port +
      " mdns=disabled cors=none auth=basic user=" + process.env.OPENCODE_SERVER_USERNAME,
  );
  auditSockets();
}

// Kernel ground truth for the bind: the local address of every LISTEN socket on
// our port. A wildcard bind would read 00000000:<port> (v4) / all-zero (v6).
// Best-effort: Android 10+ may deny /proc/net/* to the app uid, which is exactly
// why the Kotlin side (RuntimeIntegration) also probes reachability behaviourally.
function auditSockets() {
  const hexPort = port.toString(16).toUpperCase().padStart(4, "0");
  const rows = [];
  for (const table of ["/proc/net/tcp", "/proc/net/tcp6"]) {
    let text = "";
    try {
      text = fs.readFileSync(table, "utf8");
    } catch (e) {
      rows.push("audit " + path.basename(table) + "=unreadable(" + (e.code || e.message) + ")");
      continue;
    }
    const listen = text
      .split("\n")
      .slice(1)
      .filter((l) => l.includes(":" + hexPort) && /\s0A\s/.test(l));
    rows.push("audit " + path.basename(table) + "_listen=" + (listen.length ? listen.length : 0));
    for (const l of listen) {
      const local = (l.trim().split(/\s+/)[1] || "").toUpperCase();
      const localAddr = local.split(":")[0] || "";
      const loopbackV4 = localAddr === "0100007F";
      const loopbackV6 = table.endsWith("tcp6") && localAddr === "00000000000000000000000001000000";
      rows.push(
        "audit " + path.basename(table) + " local=" + local +
          " verdict=" + (loopbackV4 || loopbackV6 ? "LOOPBACK" : "NOT_LOOPBACK"),
      );
    }
  }
  console.log("BIND_AUDIT " + rows.join(" "));
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
