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

// The server password reaches this process on stdin (the app writes one line
// and closes the pipe). It is then NOT in the exec-time environment, which
// /proc/<pid>/environ exposes to every process running as the same app UID.
// An environment value is still accepted, for the host gate scripts only.
const SECRET_ENV = "OPENCODE_SERVER_PASSWORD";
let serverPassword = process.env.OPENCODE_SERVER_PASSWORD || "";
if (!serverPassword) {
  try {
    serverPassword = readSecretLine();
  } catch (err) {
    console.error("FATAL: could not read the server password from stdin: " + (err && err.message));
    process.exit(2);
  }
}
if (!serverPassword) {
  // The host always supplies this; hard-fail rather than binding unauthenticated.
  console.error("FATAL: no server password (stdin line or OPENCODE_SERVER_PASSWORD)");
  process.exit(2);
}
protectSecretEnv(serverPassword);

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

// Read one line from stdin (fd 0) without buffering past the newline. The
// host writes the password and closes the pipe, so EOF also ends the line.
function readSecretLine() {
  const chunks = [];
  const one = Buffer.alloc(1);
  for (;;) {
    let n;
    try {
      n = fs.readSync(0, one, 0, 1, null);
    } catch (err) {
      if (err.code === "EAGAIN") {
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10);
        continue;
      }
      if (err.code === "EOF") break;
      throw err;
    }
    if (n === 0 || one[0] === 0x0a) break;
    chunks.push(one[0]);
    if (chunks.length > 4096) throw new Error("secret line too long");
  }
  return Buffer.from(chunks).toString("utf8").replace(/\r$/, "").trim();
}

// Keep the server password out of everything this process starts (the shell
// tool, MCP servers, LSP servers, git). Upstream reads OPENCODE_SERVER_PASSWORD
// once, when its modules load, so the value must still be readable in-process.
// What the children see is controlled here:
//  - process.env becomes a view that hides the key from enumeration. Spreads
//    ({...process.env}), child_process defaults (named and default imports)
//    and cross-spawn then leave it out. Direct reads still return the value.
//  - Bun.spawn/spawnSync with no env option copy the native environment, not
//    process.env (tested), so they get an explicit copy without the key.
// Tested under Bun 1.3.14 (see phase4/payload/launcher.test.mjs).
function protectSecretEnv(secret) {
  process.env[SECRET_ENV] = secret;
  const real = process.env;
  process.env = new Proxy(real, {
    ownKeys: (target) => Reflect.ownKeys(target).filter((k) => k !== SECRET_ENV),
    getOwnPropertyDescriptor: (target, k) =>
      k === SECRET_ENV ? undefined : Reflect.getOwnPropertyDescriptor(target, k),
  });
  const scrubbed = (env) => {
    const out = { ...(env || process.env) };
    delete out[SECRET_ENV];
    return out;
  };
  if (typeof Bun !== "undefined") {
    for (const name of ["spawn", "spawnSync"]) {
      const original = Bun[name];
      if (typeof original !== "function") continue;
      Bun[name] = function (first, second, ...rest) {
        if (first && typeof first === "object" && !Array.isArray(first)) {
          return original.call(this, { ...first, env: scrubbed(first.env) }, second, ...rest);
        }
        const opts = second || {};
        return original.call(this, first, { ...opts, env: scrubbed(opts.env) }, ...rest);
      };
    }
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
