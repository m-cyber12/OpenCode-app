# PHASE 1 — SOURCE / RUNTIME INVESTIGATION REPORT

**Phase:** 1 (research only — no product code written)
**Date:** 2026-08-27
**Branch:** `arena/01a04483-opencode-app`
**Base commit:** `b005d34b5b95d4889369a3e1b81a01e5ad6b6068`
**Upstream OpenCode inspected:** `sst/opencode` @ commit `05ea5073be967c779d326929b2de6228dda4159d`
  (HEAD of `main` at inspection time; package manifests carry version `1.18.23`)

---

## 0. Executive summary

OpenCode is **not Bun-only**. The current source ships two runtime surfaces:

1. **Bun** — the CLI. Shipped as a single standalone executable built with
   `Bun.build({ compile })` (`packages/opencode/script/build.ts`).
2. **Node.js** — the headless server. Built by `packages/opencode/script/build-node.ts`
   into a Node ESM bundle (`dist/node/node.js`) exporting `Server.listen`,
   `Config.get`, `bootstrap`, `Database`. **The production Electron desktop app
   runs this Node build in an Electron utility process**
   (`packages/desktop/src/main/server.ts` → `utilityProcess.fork(sidecar.js)` →
   `virtual:opencode-server` → `dist/node/node.js`). This is direct, in-repo
   evidence that the real OpenCode agent server runs under **plain Node/V8**,
   not just Bun.

That is the single most important finding for Android: the embedded path can
follow the **Node-on-Android** route (V8 is Android's own JS engine; there is
long-standing precedent for building Node for Android) rather than
cross-compiling Bun (possible but heavy — see §6).

The agent loop, tools, shell, Git, MCP, SQLite storage, and the HTTP/WebSocket
server API are all plain-JS + `child_process` + filesystem + SQLite + HTTP.
The only platform-native components in the server path are **Node itself**,
**`@lydell/node-pty`** (PTY addon, used for the terminal feature),
**`@parcel/watcher`** (file-watcher addon, graceful fallback), and the external
**`git`** / **POSIX shell** / **`ripgrep`** binaries the agent shells out to.

Per the honesty protocol this entire phase is research/static inspection:
every runtime claim is **NOT TESTED** until Phase 2 executes it on an Android
emulator/device. No product code was written.

---

## 1. Pinned upstream & version evidence

| Component | Version / pin | Evidence in repo |
|-----------|---------------|------------------|
| OpenCode source | commit `05ea5073` | `git rev-parse HEAD` of inspected clone |
| OpenCode package version | `1.18.23` | `packages/core/package.json`, `packages/opencode/package.json` (`"version"`) |
| Bun (dev toolchain) | `bun@1.3.14` | root `package.json` `packageManager` |
| Bun types | `1.3.13` | root `package.json` catalog `@types/bun` |
| Node toolchain types | `24.12.2` | root `package.json` catalog `@types/node` |
| `node:sqlite` usage (⇒ Node ≥ 22.5) | — | `packages/core/src/database/sqlite.node.ts`, `packages/opencode/package.json` `imports.#db.node` |
| PTY addon | `@lydell/node-pty@1.2.0-beta.12` | root `package.json` catalog + `packages/desktop/package.json` optionalDependencies |
| File watcher | `@parcel/watcher@2.5.1` | `packages/core/package.json` |
| ripgrep (bundled download) | `15.1.0` | `packages/core/src/ripgrep/binary.ts` (`const VERSION`) |
| Native file search (Bun-only) | `@ff-labs/fff-bun@0.9.4` | `packages/core/package.json` |
| Bun PTY | `bun-pty@0.4.8` | `packages/core/package.json` |
| MCP SDK | `@modelcontextprotocol/sdk@1.29.0` | `packages/opencode/package.json` |
| Effect (core framework) | `4.0.0-beta.83` | root `package.json` catalog |
| HTTP framework | `hono@4.10.7` | root `package.json` catalog |
| SQLite ORM | `drizzle-orm@1.0.0-rc.2` | root `package.json` catalog |
| WebSocket | `ws@8.21.0` | `packages/opencode/package.json` |
| mDNS (serve/share) | `bonjour-service@1.3.0` | `packages/opencode/package.json` |
| process spawn | `cross-spawn@7.0.6` | root `package.json` catalog |
| path lookup | `which@6.0.1` | `packages/core/package.json` |
| XDG paths | `xdg-basedir@5.1.0` | `packages/core/package.json` |
| tree-sitter (WASM) | `tree-sitter-bash@0.25.0`, `tree-sitter-powershell@0.25.10`, `web-tree-sitter@0.25.10` | `packages/opencode/package.json` |
| image processing (WASM) | `@silvia-odwyer/photon-node@0.3.4` | `packages/opencode/package.json` |
| Electron (desktop, Node host) | `42.3.3` | `packages/desktop/package.json` |

A draft `versions.lock` capturing these pins (and marking what is still
provisional) is written at the repository root as `versions.lock`.

---

## 2. Answers to the eight questions (with evidence)

### Q1. What runtime executes OpenCode? Is Bun required, and for what exactly?

**Two runtimes are supported by the current source.** Bun is required only for
the CLI/TUI; the agent **server** already runs on Node in the shipped desktop app.

- **Bun (CLI).** `packages/opencode/script/build.ts` runs
  `Bun.build({ compile: { target: "bun-linux-arm64" | "bun-linux-x64" | "bun-linux-arm64-musl" | ... darwin | win32, outfile: "dist/<name>/bin/opencode" } })`,
  embedding the Bun runtime + JSC into a single executable. Bun-only APIs used:
  - `bun:sqlite` — `packages/core/src/database/sqlite.bun.ts`
  - `bun-pty` — `packages/core/src/pty/pty.bun.ts`
  - `@ff-labs/fff-bun` native file search — `packages/core/src/filesystem/fff.bun.ts`
  - `Bun.$`, `Bun.stdin`, `Bun.stringWidth`, `Bun.hash` — CLI/TUI
    (`packages/opencode/src/cli/cmd/run/*`, `cli/cmd/tui.ts`, `cli/cmd/run.ts`,
    `packages/core/src/skill/discovery.ts`).
- **Node (server).** `packages/opencode/script/build-node.ts`:
  ```
  Bun.build({ target: "node", entrypoints: ["./src/node.ts"], outdir: "./dist/node",
    format: "esm", external: ["jsonc-parser", "@lydell/node-pty"], ... })
  ```
  `packages/opencode/src/node.ts` exports `Config`, `Server`, `bootstrap`, `Database`.
  `packages/desktop/electron.vite.config.ts` resolves `virtual:opencode-server` to
  `../opencode/dist/node/node.js`, and `packages/desktop/src/main/server.ts`
  forks it with `utilityProcess.fork(sidecar.js)` — i.e. **under Node/V8 in
  production**. `packages/desktop/scripts/prebuild.ts` invokes `bun script/build-node.ts`
  before the desktop build.

Conditional wiring that makes both runtimes work from one codebase:
`packages/opencode/package.json` `imports` (`#db` → `db.bun.ts` / `db.node.ts`) and
`packages/core/package.json` `imports` (`#sqlite`, `#pty`, `#fff` → `*.bun.ts` / `*.node.ts`).

**Conclusion for Android:** Bun is **not required** for the agent server. We
take the Node path (the one the desktop app already ships), so the Android
runtime need is **Node for Android (arm64)**, not Bun. Bun would only be needed
for the TUI and the native FFF fuzzy search, neither of which the Android chat
UI needs (FFF already falls back to ripgrep — see §2.7/Q7).

### Q2. Which dependencies are native (not pure JS/TS)?

**Server/Node path (platform-native):**
- Node.js itself (V8 + libuv + OpenSSL + `node:sqlite` built in).
- `@lydell/node-pty` — N-API C++ addon (fork of node-pty); PTY. Used via
  `packages/core/src/pty/pty.node.ts`; wired into `packages/core/src/location-services.ts`
  (terminal per location) and routed to WebSockets in
  `packages/core/src/pty/protocol.ts`.
- `@parcel/watcher` — N-API C++ addon (inotify/FSEvents); loaded dynamically in
  `packages/core/src/filesystem/watcher.ts`. **Graceful fallback:** if the binding
  fails to load the service logs an error and returns an empty service.

**External binaries the agent executes at runtime (native, not Node deps):**
- `git` (all Git operations shell out via `AppProcess`; see Q3).
- a POSIX shell (`sh`/`bash`/`zsh`/`dash`/`ksh`) for the `bash` tool.
- `ripgrep` v15.1.0 (downloaded ELF at runtime if absent; see Q3).
- `tar` (transiently, only to extract the ripgrep archive on first use).

**Bun-path-only natives (not needed for the Android server):**
- `@ff-labs/fff-bun` native file finder, `bun-pty`, `@opentui/core` platform
  binaries (TUI renderer — `packages/opencode/src/cli/cmd/run/*`).

**WASM (portable — no platform-specific build needed):**
- `@silvia-odwyer/photon-node` (`photon_rs_bg.wasm`, `packages/opencode/src/image/image.ts`).
- `web-tree-sitter` + `tree-sitter-bash`/`tree-sitter-powershell`
  (`packages/opencode/src/tool/shell.ts`, dynamic `import()` of `.wasm`).
  The desktop build copies `*.wasm` out of `dist/node` — `packages/desktop/electron.vite.config.ts`
  plugin `opencode:copy-server-assets`.

**Everything else is pure JS/TS:** `effect`, `hono`, `zod`, `drizzle-orm`,
`ai` + `@ai-sdk/*` providers, `ws`, `cross-spawn`, `which`, `glob`, `minimatch`,
`diff`, `jsonc-parser`, `gray-matter`, `turndown`, `@octokit/*`, etc.

### Q3. Which binaries are required at runtime?

| Binary | Role | How located | Evidence |
|--------|------|-------------|----------|
| OpenCode server runtime | host | embedded (Node build / Bun compile) | `packages/opencode/src/node.ts`, `script/build-node.ts`, `script/build.ts` |
| `git` | all Git ops | `which("git")`-style lookup via `AppProcess` | `packages/core/src/git.ts` (spawns `git` subcommands) |
| POSIX shell | `bash` tool | resolved shell (`/etc/shells` → `which`) | `packages/core/src/shell.ts`, `packages/core/src/tool/bash.ts` |
| `rg` (ripgrep) | grep/glob tools | system `rg` → cached → **downloads** `ripgrep-15.1.0-<platform>` from GitHub releases | `packages/core/src/ripgrep/binary.ts` |
| `tar` | ripgrep first-use extraction | spawned `tar -xzf` | `packages/core/src/ripgrep/binary.ts` `extract()` |
| MCP local server command | MCP stdio | user-configured `mcp.command[]` | `packages/opencode/src/mcp/index.ts` `connectLocal` |
| (optional) `ssh`, `gh` | only those integrations | — | — |

ripgrep platform selection is hardcoded in
`packages/core/src/ripgrep/binary.ts` `PLATFORM`: **arm64-linux →
`aarch64-unknown-linux-gnu`** (glibc). That glibc build will not run on Android's
bionic; Phase 2/3 must substitute a static musl `rg` (ripgrep publishes musl
builds for some targets — exact aarch64 asset to be confirmed) or build `rg`
with the NDK, and pre-bundle it (avoiding the runtime download + `tar` step).

### Q4. Dependencies requiring Linux-specific behavior, glibc/musl, child processes, ptrace/seccomp?

- **ptrace / seccomp / prctl / chroot / mount / unshare: none in OpenCode's own
  code.** Grep across `packages/*/src` finds no use of those syscalls (only
  unrelated strings, e.g. i18n "unshare" session labels). OpenCode imposes no
  seccomp/ptrace requirement of its own.
- **Child processes: central.** `cross-spawn` via `effect/unstable/process`
  (`packages/core/src/cross-spawn-spawner.ts`), with **process groups** on
  POSIX (`detached: process.platform !== "win32"`, kill via `process.kill(-pid)`
  in `packages/core/src/shell.ts` `killTree`). The `bash` tool spawns the shell
  with `detached: true, forceKillAfter: 3s` (`packages/core/src/tool/bash.ts`).
- **glibc/musl:** the official Bun binary is built per-libc
  (`opencode-linux-arm64`, `opencode-linux-arm64-musl`, etc.;
  `packages/opencode/script/build.ts` `allTargets`), and `OPENCODE_LIBC` selects
  the `@parcel/watcher-linux-*-{glibc,musl}` binding
  (`packages/core/src/filesystem/watcher.ts`). On Android neither applies — we
  need **bionic (NDK)** builds or **static musl** binaries.
- **PTY/termios:** `@lydell/node-pty` allocates a PTY (termios, TIOCGWINSZ) for
  the terminal feature — needs `/dev/ptmx`/`/dev/pts` under the app sandbox
  (unverified on Android; Phase 2).
- **File locking:** OpenCode uses lockfiles in the state dir (pure fs, heartbeat +
  stale-lease), **not** `flock(2)` — `packages/core/src/util/flock.ts`.
- **mDNS/UDP multicast:** `bonjour-service` publishes `opencode-<port>` at
  `opencode.local` for `serve`/`share` (`packages/opencode/src/server/mdns.ts`);
  all errors are swallowed, so it degrades to disabled.

### Q5. Filesystem features that may be unavailable on Android?

| Feature | Used for | Android status | Mitigation |
|---------|----------|----------------|------------|
| `exec()` of files in app-private **writable** dirs | running bundled tools | **Blocked** on Android 10+ (W^X, API 29+) | run from `nativeLibraryDir` (APK-extracted, exec-allowed) |
| `/etc/shells` | shell discovery | absent | `shell.ts` already falls back to `/bin/bash /bin/zsh /bin/sh`; point `SHELL` at bundled shell |
| `/proc/cpuinfo` | AVX2 detect in launcher | restricted for apps | x64-only concern; irrelevant on arm64 |
| `/proc/net` | (netstat etc.) | restricted | not used by core |
| XDG base dirs | config/data/cache/state/tmp | present only if env set | `xdg-basedir` honors `XDG_*_HOME`; set them to app-private paths |
| `os.homedir()` / `os.tmpdir()` | `Global` paths | values exist but not meaningful | override via env / `Global.layerWith` |
| `/dev/ptmx`, `/dev/pts/*` | PTY terminal | may be restricted in sandbox | verify in Phase 2; degrade terminal feature if needed |
| inotify | file watching | kernel-supported; binding won't match bionic | existing graceful fallback (watcher disabled) |
| mDNS multicast | serve/share discovery | may be blocked | already degrades silently |

`packages/core/src/global.ts` creates `~/.local/share|.cache|.config|.state/opencode`
(and `<tmp>/opencode`) at import time and supports `Flag.OPENCODE_CONFIG_DIR`
override — the Android host can pre-seed `HOME`/`XDG_*`/`TMPDIR` env to keep all
of this inside the app sandbox.

### Q6. Which MCP transports / process models does OpenCode actually use?

`packages/opencode/src/mcp/index.ts` (using `@modelcontextprotocol/sdk` client):

1. **stdio (local MCP):** `new StdioClientTransport({ stderr: "pipe", command, args, cwd, env })`
   — spawns the configured `mcp.command[]` as a **child process**; `cwd` resolves
   against the workspace; `env = process.env + mcp.environment` (+ `BUN_BE_BUN=1`
   when the command is `opencode` itself). Config schema:
   `packages/core/src/v1/config/mcp.ts` `Local` (`type: "local"`).
2. **HTTP (StreamableHTTP) and SSE (remote MCP):** `StreamableHTTPClientTransport`
   / `SSEClientTransport` with optional OAuth (local callback server defaulting
   to `127.0.0.1:19876`).

So: **local MCP = child-process spawning of a user-configured command; remote
MCP = plain HTTP/SSE client.** No kernel features beyond `spawn`. On Android,
local MCP servers would require their own runtimes (npx/uvx/…) to be bundled;
remote MCP works unchanged.

### Q7. Which parts can run unchanged on an Android-hosted Linux userspace?

Assuming Node-for-Android boots, the following run **unchanged** (pure JS + fs +
SQLite + HTTP + spawn):

- Agent loop, sessions, permissions, model routing, streaming, config,
  `question`/tools registry, patch/edit/write/read/glob/grep tools, skill
  system, snapshots, memory.
- SQLite storage via `node:sqlite` (`packages/core/src/database/sqlite.node.ts`,
  `packages/opencode/package.json` `#db` node condition).
- HTTP server API + WebSocket event stream + Basic-auth
  (`packages/opencode/src/server/server.ts`, `packages/server/src/auth.ts`).
- `@opencode-ai/sdk` typed client (`packages/sdk/js`) — the protocol the Android
  UI can consume.
- Credential storage (SQLite-backed; `packages/core/src/credential/sql.ts` — no
  keychain/keytar dependency).
- Remote MCP (HTTP/SSE) and all WASM components (image, tree-sitter).
- The `bash` tool itself (spawns a POSIX shell) **once a shell binary is present**.

### Q8. Which parts need Android-specific adaptation?

1. **Runtime host:** bundle a Node (arm64-v8a) built for Android (JNI `libnode.so`
   or a forked `node` ELF from `nativeLibraryDir`), instead of the Bun CLI.
2. **Native addons:** rebuild `@lydell/node-pty` (and optionally `@parcel/watcher`)
   against the Android NDK / bionic for arm64 — or accept the watcher's graceful
   fallback and stub/replace node-pty.
3. **Bundled tools (must be in-APK, Core Rule 2):** static `git`, a static POSIX
   shell (busybox/toybox/mksh), static-musl `rg` for arm64 — all extracted to
   `nativeLibraryDir` (exec-allowed), not the writable data dir (W^X).
4. **Bootstrap env:** set `HOME`, `XDG_*`, `TMPDIR`, `PATH`, `SHELL`,
   `OPENCODE_SERVER_PASSWORD`, `OPENCODE_CLIENT` before `Server.listen`.
5. **Latent Bun-only spots reachable from the server path** (e.g. `Bun.hash` in
   `packages/core/src/skill/discovery.ts`): audit + shim for Node. The desktop
   app works under Node, but rare paths (remote skill pull) may be latent —
   Phase 2 must exercise them.
6. **Terminal/PTY feature:** confirm `/dev/ptmx` access in the sandbox; otherwise
   document a degraded terminal (Core Rule 6 requires explicit documentation).
7. **`share`/mDNS discovery:** disable or adapt (already degrades silently).
8. **MCP local servers:** document that stdio MCP needs bundled runtimes; remote
   MCP unchanged.

---

## 3. Dependency map (summary table)

| Component | Type | Runtime role | Android implication | Status |
|-----------|------|--------------|---------------------|--------|
| OpenCode server (`src/node.ts`) | TS → Node bundle | agent + API | run under Node-for-Android | **NOT TESTED** |
| Node.js (V8/libuv/OpenSSL/node:sqlite) | native runtime | host | build for Android arm64 | **NOT TESTED** |
| `@lydell/node-pty` | N-API addon | PTY terminal | rebuild for bionic/arm64 | **NOT TESTED** |
| `@parcel/watcher` | N-API addon | file events | optional; graceful fallback | **NOT TESTED** |
| `git` (external) | ELF | Git tool | bundle static arm64 | **NOT TESTED** |
| POSIX shell (external) | ELF | bash tool | bundle static | **NOT TESTED** |
| `ripgrep` 15.1.0 (external) | ELF | grep/glob | substitute static musl arm64; pre-bundle | **NOT TESTED** |
| `node:sqlite` / `bun:sqlite` | built-in | storage | Node ≥ 22.5 | **NOT TESTED** |
| MCP SDK (stdio/HTTP/SSE) | JS | MCP | remote OK; local needs runtimes | **NOT TESTED** |
| `@ff-labs/fff-bun` | native (Bun) | file search | not needed (ripgrep fallback) | **NOT TESTED** |
| WASM (photon, tree-sitter) | WASM | image/shell highlight | portable, ship assets | **NOT TESTED** |
| `bonjour-service` | JS/UDP | serve/share mDNS | degrade/disable | **NOT TESTED** |

---

## 4. Termux / Android-constraints research (reference only — Termux is never a dependency)

> Per Core Rule 2, Termux is researched only to understand the problem space;
> nothing below is a dependency of this project.

### How Termux works (why it can run native code)
- Termux ships its own prefix (`/data/data/com.termux/files/usr`) with its own
  package manager and **bionic-linked** (Android-libc) binaries; it does **not**
  chroot or use namespaces. `libtermux-exec` (LD_PRELOAD) rewrites shebangs and
  rpaths so standard ELF binaries run on Android's `linker64`.
- Termux historically targets API ≤ 28 to sidestep the Android 10 exec
  restriction (see next), and distributes some packages as APKs whose payloads
  land in the JNI lib dir precisely because that directory is marked executable.

### Android execution restrictions that matter
- **W^X / API 29+:** apps targeting Android 10+ **cannot `execve()` files in the
  writable app home directory** (W^X violation); executable code must come from
  the APK. The workaround is to place native binaries in
  `context.getApplicationInfo().nativeLibraryDir` (extracted read-only from the
  APK, exec-allowed). *This dictates our packaging: `git`, shell, `rg`, and the
  runtime all live in `nativeLibraryDir`.* [1](https://developer.android.com/about/versions/10/behavior-changes-10)
- **seccomp:** app processes run under a seccomp-bpf filter; Android 15
  tightened it (e.g. glibc `set_robust_list` → `SIGSYS` under proot). Any
  bundled runtime must be audited against the app syscall filter. [2](https://xdaforums.com/t/running-native-glibc-debian-binaries-on-android-15-without-proot.4788725/)
- **proot:** classic proot intercepts syscalls via `ptrace(TRACEME)`, which is
  blocked/restricted for normal apps and additionally broken by Android 15
  seccomp. LD_PRELOAD-based alternatives (`proroot`) avoid ptrace. **Implication:
  we should prefer bionic-native/static binaries and avoid proot entirely; if a
  compatibility layer ever becomes necessary, ptrace-based proot is the risky
  option, not the default.** [3](https://github.com/coderredlab/proroot) [4](https://github.com/termux/proot-distro)
- **targetSdk & OS spread (13/14/15/16):** the exec/W^X rule binds from API 29
  regardless of targetSdk; the main differences across Android 13→16 are
  progressively stricter seccomp and storage model. We will target a modern API
  but **side-load** rather than require Play (Play's targetSdk mandates don't
  block us for a sideloaded APK).

### Runtime precedents (what's proven possible, and at what cost)
- **Bun on Android:** upstream Bun has **no Android target** and upstream has
  declined it ("not planned"). The MIT project `guysoft/opencode-termux`
  cross-compiled **Bun v1.2.13 + WebKit/JSC + ICU** for Android aarch64 by
  patching ~33 files, then transplanted the serialized module graph onto the
  Android Bun binary (because `bun build --compile` has no Android target).
  Feasible but a **permanent patch burden**. [5](https://github.com/guysoft/opencode-termux)
- **Node on Android:** `nodejs-mobile` (JaneaSystems) ships `libnode.so` for
  `arm64-v8a` (latest v0.3.3 is an old Node line), plus a `prebuild-for-nodejs-mobile`
  tool that compiles native addons for `android-arm64` (default SDK 24).
  Community build scripts exist but predate modern Node. **OpenCode's `node:sqlite`
  requirement (Node ≥ 22.5) means we cannot use the stale nodejs-mobile lib as-is;
  we will likely need to build a current Node for Android ourselves — the
  single largest Phase 2 risk.** [6](https://github.com/janeasystems/nodejs-mobile/releases)
  [7](https://github.com/nodejs-mobile/prebuild-for-nodejs-mobile)

### ABI scope (Core Rule 7 / phase instruction)
- **Priority: `arm64-v8a`.** All current Android phones are arm64.
- **`x86_64` only for emulator testing** (convenience), not a shipping target.
- **Unsupported:** `armeabi-v7a` (32-bit — dropped by modern V8/Node and Bun),
  `x86` (32-bit). Documented here rather than pursued.

---

## 5. Candidate runtime architecture (UNVALIDATED — Phase 2 must prove each link)

```
┌────────────────────────────────────────────────────────────────┐
│  Native Android app (Kotlin/Compose)                            │
│   • project picker / chat UI                                    │
│   • launches + supervises the embedded runtime                  │
└───────────────┬────────────────────────────────────────────────┘
                │ 127.0.0.1 HTTP + WebSocket (Basic auth, app-generated
                │ password) — same protocol as the Electron desktop app
                ▼
┌────────────────────────────────────────────────────────────────┐
│  Embedded OpenCode server  (REAL OpenCode, `src/node.ts` build) │
│   Server.listen + bootstrap   —  agent loop, tools, sessions,   │
│   permissions, MCP, SQLite storage, WebSocket event stream      │
└───────────────┬────────────────────────────────────────────────┘
                │ Node.js (V8) for Android arm64  (JNI libnode.so,
                │  or forked `node` ELF from nativeLibraryDir)
                ▼
┌────────────────────────────────────────────────────────────────┐
│  Minimal bundled userspace (all static, in nativeLibraryDir)    │
│   • static git            • static POSIX shell (sh)             │
│   • static musl ripgrep   • node-pty (bionic/arm64)             │
│   • env seeded by app: HOME, XDG_*, TMPDIR, PATH, SHELL,        │
│     OPENCODE_SERVER_PASSWORD                                     │
└────────────────────────────────────────────────────────────────┘
```

Key design choices (all unvalidated):
1. **Node, not Bun**, as the embedded runtime — justified by the in-repo
   evidence that the production desktop app already runs the OpenCode server on
   Node (`packages/desktop`, §0/Q1). V8 is Android's engine, and Node-on-Android
   has a (stale but real) precedent to build on.
2. **No proot, no chroot, no glibc userspace** — build everything bionic-native
   (NDK) or static-musl so the pieces run directly in the app sandbox, avoiding
   the ptrace/seccomp minefield (§4).
3. **Everything executable ships inside the APK and extracts to
   `nativeLibraryDir`** to satisfy W^X (§4).
4. The Kotlin UI speaks the **OpenCode SDK protocol** over localhost — no
   reimplementation of the agent (Core Rule 3), full feature surface preserved
   (Core Rule 6).

The biggest unproven risks (Phase 2's empirical targets): (a) building/booting a
current Node on Android arm64, (b) `/dev/ptmx` PTY access in the sandbox,
(c) node-pty compiled for bionic, (d) the `Bun.*` latent spots under Node, and
(e) app seccomp compatibility.

---

## 6. Honesty-protocol labeling

| Claim | Label |
|-------|-------|
| OpenCode server runs under Node (Electron desktop precedent) | **NOT TESTED** on Android; static evidence only (in-repo files) |
| OpenCode CLI is a Bun `--compile` standalone | **NOT TESTED** (static inspection of `script/build.ts`) |
| All eight Q1–Q8 answers above | **NOT TESTED** (source/static + web research; nothing executed) |
| Native component inventory (node-pty, parcel/watcher, ripgrep, git, shell) | **NOT TESTED** (static dependency analysis) |
| Termux/Android-constraints findings | **NOT TESTED** on-device (external references; Phase 2 will verify) |
| Bun can be cross-compiled for Android (prior art) | **NOT TESTED** by this project (external report only) |
| Node-on-Android feasibility for a current Node | **NOT TESTED** (open risk, Phase 2) |
| This report + draft `versions.lock` | **IMPLEMENTED** (files written) |

Nothing is marked TESTED; nothing is BLOCKED — the investigation found a viable
embedded path (Node-on-Android), with proof deferred to Phase 2/3 as required.

---

## 7. Stop condition

Phase 1 ends here: the dependency map (§2/§3) and candidate architecture (§5)
are written up. No spike, no Android/Gradle code, and no runtime execution were
started in this run — that is Phase 2. The next session should read this report
(and `phase0-repo-audit-report.md`) before beginning Phase 2.
