# Architecture

OpenCode for Android is **the upstream OpenCode server, unchanged, running as a
child process of an Android app**, plus a thin Kotlin/Compose client that
speaks the same HTTP + SSE API the desktop app speaks. There is no port of the
agent loop to Kotlin, no proot, no compatibility layer, and no network hop: the
model provider is the only remote party.

```
+-------------------------------------------------------------------------+
| Android app process  (io.github.mcyber12.opencode, Kotlin/Compose)              |
|                                                                         |
|  UI (Compose)  <--StateFlow--  OpenCodeRepository  <--HTTP/SSE--+       |
|      |                             |  OpenCodeApi / EventStream |       |
|      v                             |                            |       |
|  RuntimeService (foreground) --> RuntimeManager (supervisor)    |       |
|      AbiGate -> PayloadExtractor -> RuntimeProcess -> HealthChecker      |
+----------------------------------------------------------------|--------+
                       fork/exec (same UID, app-private files)   | 127.0.0.1:4111
+----------------------------------------------------------------v--------+
| bun (libbun.so, bionic)  launcher.js -> opencode/dist/node/node.js      |
|   upstream OpenCode server @05ea5073: Hono HTTP API, SSE, sessions,     |
|   agent loop, tools, MCP client, provider SDKs, sqlite (bun:sqlite)     |
|   children: /system/bin/sh (bash tool), libgit.so, librg.so, MCP stdio  |
+-------------------------------------------------------------------------+
                                            |
                                            v  HTTPS (the only remote traffic)
                                   model provider (OpenRouter, Anthropic, ...)
```

## Components

### App shell (`app/src/main/java/ai/opencode/android`)

| Package | Role |
|---|---|
| `runtime/` | `RuntimeService` (foreground service, type `specialUse`) owns `RuntimeManager`, the supervisor state machine (`RuntimeStateMachine`, 17 legal transitions: `IDLE -> CHECKING_ABI -> EXTRACTING -> STARTING -> HEALTHY -> (CRASHED_RESTARTING | STOPPING) -> ...`). `AbiGate` refuses 32-bit ABIs with a message; `PayloadExtractor` extracts + sha256-verifies the payload; `RuntimeProcess` spawns Bun through the exec shim; `HealthChecker` polls `GET /global/health` with Basic auth (readiness = HTTP 2xx + body "healthy", never "process launched"); `RestartBackoff` 1 s..30 s, max 8 attempts, then `FATAL`. `RuntimeEnv` builds the child's environment (app-private `HOME`, XDG dirs, `PATH`, `OPENCODE_SERVER_PORT`, shim paths). `Secrets`/`security/SecretStore` hold the per-install server password. |
| `client/` | `OpenCodeApi` (blocking HttpURLConnection client for the upstream routes actually used: health, sessions, messages, prompt, providers, auth, files, shell, permissions, config), `OpenCodeEventStream` + `SseAccumulator` (the `/event` SSE stream to typed `EventFrame`s), `Transcript` (message/part reducer), `OpenCodeRepository` (the single state owner behind the UI: runtime state, sessions, live transcript, providers, permissions, errors), `UiError` (offline/auth/network classifier), `LoopbackGuard` (refuses any non-loopback base URL), `DefaultModelHint` (Phase 9: default model must come from a *connected* provider). |
| `projects/`, `memory/` | project (workspace) store under the app-specific external root `<externalFilesDir>/workspaces/<id>` (app-private `files/workspaces/<id>` before the Phase 10 continuation, migrated on first start), SAF import/export/publish, in-app file browser, `AGENTS.md`-based project memory (OpenCode's own mechanism). |
| `ui/` | Compose screens: welcome/ABI/runtime states, projects, chat (streaming transcript, tool cards, permission sheet), settings (providers, models, permission policy, diagnostics). Screens are pure functions of repository state (enforced by a static check). |

### Embedded runtime (`phase4/scripts/10-build-payload.sh` -> `phase4/out/engine`)

| Artifact | Packaging | Why |
|---|---|---|
| `libbun.so` | JNI lib per ABI (`@oven/bun-linux-{x64,aarch64}-android` 1.3.14) | Android only allows `exec` from `nativeLibraryDir` (W^X, API 29+); JNI libs land there automatically. Bun's Android build is bionic-linked, so the zygote seccomp policy is satisfied. |
| `libgit.so`, `librg.so` | JNI libs, built from source with NDK 28.2.13676358 | real git 2.48.1 (`NO_PERL`, no curl/openssl: local repo ops only) and ripgrep 15.1.0 (OpenCode expects an `rg` binary). |
| `libexecshim.so`, `libchildshim.so`, `libseccompshim.so` | JNI libs | exec shim launches Bun with `LD_PRELOAD` set; the seccomp shim neutralises syscalls Android's app policy forbids (BPF child filter on x86_64, preload on arm64). See RUNTIME.md. |
| `runtime-payload.tar.gz` | asset | `opencode/dist/node/node.js` (Bun.build of upstream `packages/opencode`, target `bun`, defines `OPENCODE_VERSION="1.18.23"`, `OPENCODE_CHANNEL="android"`), `*.wasm` (tree-sitter, photon), `node_modules` needed at runtime, `launcher.js`. |
| `runtime-manifest.json` | asset | pins (`opencodeCommit/opencodeVersion/bunVersion/gitVersion/rgVersion/payloadVersion`), per-file sha256 + size, tarball sha256. Extraction is refused on mismatch; `payloadVersion` bump forces re-extraction. |

### Data layout (runtime app-private; projects app-specific external)

*(Phase 10 renamed the published applicationId from `ai.opencode.android` to
`io.github.mcyber12.opencode`; the Kotlin namespace - the source package - is
still `ai.opencode.android`, which is internal and not user-visible. The private
storage path follows the applicationId.)*

```
runtime/opencode/dist/node/node.js  extracted + validated payload
runtime/.extracted-v6               extraction marker (payloadVersion)
bin/{bun,git,rg} -> nativeLibraryDir/lib{bun,git,rg}.so
home/                               HOME for the server
xdg/{data,config,state,cache,tmp}/  XDG_* dirs (OpenCode's auth.json, config, sqlite, logs)
secrets/<name>.enc                  AES-256-GCM blobs (server password, provider keys) under AndroidKeyStore
log/runtime.log, log/crashes/       supervisor log + crash captures
```

Everything above stays app-private (`/data/data/io.github.mcyber12.opencode/files`,
mode 0700) - it is machinery, not the user's work.

**Project files are the exception, and deliberately so** (Phase 10 continuation v3):

```
/storage/emulated/0/Documents/OpenCode/<project-id>/
                    one directory per project (= one OpenCode instance each)
```

This is the default, and it is the product decision the whole storage story now
turns on: a project is an ordinary folder on shared storage, so the Files app, any
file manager, MTP/USB and a non-root `adb shell` can open the agent's output while
it is being written, with no export or publish step. Two earlier layouts were
rejected on evidence rather than taste:

* `files/workspaces/<project-id>` (app-private) — no file manager, no MTP browse
  and no `adb shell` can read it. The agent wrote real files into a sealed box,
  which contradicted the product's own premise;
* `Android/data/<applicationId>/files/workspaces/<project-id>` — reachable by
  `adb`/MTP, but on Android 11+ the platform blocks *apps* (including every file
  manager) from browsing another app's `Android/data`, so the files were still not
  user-visible on the phone.

Writing to `Documents/OpenCode` needs All files access (`MANAGE_EXTERNAL_STORAGE`)
on Android 11+, which the app asks for in its own storage panel with the reason
attached. Nothing narrower reaches that folder *by path*, and the runtime needs a
path: OpenCode is a POSIX process (git, bun, ripgrep, its own `bash` tool) that
`chdir`s into the project directory. A SAF tree gives a `content://` grant, not a
path, so a SAF-only root would mean either re-engineering the runtime's file layer
(diverging from upstream, out of scope) or handing the agent a directory it cannot
write to. A SAF folder is accepted as the live root only when it RESOLVES to a real
path on primary storage (verified by writing a probe file); SD cards and cloud
providers are refused with the reason shown, not silently accepted.

The fallbacks, and what each one costs:

| Mode | Location | File manager | adb / PC | When |
|---|---|---|---|---|
| `PUBLIC` | `Documents/OpenCode` | yes | yes | default, once All files access is granted |
| `CHOSEN` | a folder the user picked (SAF, primary volume) | yes | yes | the user wants a specific folder |
| `APP_EXTERNAL` | `Android/data/<applicationId>/files/workspaces` | Android 10 only | yes | All files access refused |
| `INTERNAL` | `files/workspaces` (app-private) | no | no | no usable external storage at all |

`RuntimePaths` decides the mode from the platform (not from a preference), the
Files screen renders it as data and never invents a visibility claim, and the
device gates assert it per mode. An install that predates the change has its
projects moved once — only into an empty new root, never overwriting a name, and
only removing a source directory that is provably empty (`ProjectMigration`); the
user's active project pointer is keyed by name, so it follows the move.

One more honest note: with All files access held, the app can technically reach
shared storage broadly. It reads and writes only inside the project root it
manages (plus a folder the user chooses), and that is stated in the manifest
comment, the in-app storage panel and `PRIVACY-POLICY.md` — the permission is not
free, and this is what it is used for.

The in-app file browser (reading through OpenCode's own `/file` API) and the
`Export a copy...` action (SAF `OpenDocumentTree`) remain: the first shows exactly
what the agent sees, the second is how a user takes a snapshot somewhere else
entirely.

## Request flow (one turn)

1. UI calls `repository.send(text)`; repository posts `POST /session/:id/prompt_async?directory=<workspace>` with the selected model (`providerID/modelID`) when one is set.
2. Upstream runs the agent loop in-process: provider SDK call over HTTPS, tools (`read`/`write`/`edit`/`bash`/`grep`/`glob`/`webfetch`/MCP), permission requests.
3. Everything the UI shows arrives on `GET /event` (SSE): `message.part.updated`, `permission.asked`, `session.status`, `session.error`. `Transcript` reduces the parts; permission prompts surface as a sheet (or are answered by the standing policy); `session.error` becomes a classified `UiError`.
4. Session/message history is upstream's own sqlite store under `xdg/data/opencode`; the app keeps only a project pointer and UI preferences.

## Provider selection (Phase 9 fix)

Upstream builds its provider table once per instance from `auth.json` + config
and caches it; `PUT /auth/:id` only writes the file. The desktop app calls
`global.dispose()` right after changing credentials; the Android app did not,
so a newly added key was invisible until restart and turns fell back to the
bundled `opencode` provider (or, with an explicit model, failed with
`ProviderModelNotFoundError` reported only on `session.error`). The repository
now (a) disposes after every credential change, (b) re-reads `GET /provider`,
(c) chooses the default model from a **connected** provider
(`DefaultModelHint`), never from the first catalogue entry. Verified by
`ProviderSelectionGatesTest` (P9_PROVSEL_*) - status in TESTING.md.

## What is deliberately *not* here

- No Kotlin reimplementation of any OpenCode feature; the app only renders and drives the server.
- No remote/companion server: the client refuses any non-loopback URL (`LoopbackGuard`).
- No user-installed runtime: Termux, Node, Bun are neither required nor used.
- No network transport in git, no PTY, no file watcher (`@parcel/watcher` has no Android binding; upstream degrades gracefully). See CAPABILITY-MATRIX.md.
