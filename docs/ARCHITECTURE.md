# Architecture

OpenCode for Android is **the upstream OpenCode server, unchanged, running as a
child process of an Android app**, plus a thin Kotlin/Compose client that
speaks the same HTTP + SSE API the desktop app speaks. There is no port of the
agent loop to Kotlin, no proot, no compatibility layer, and no network hop: the
model provider is the only remote party.

```
+-------------------------------------------------------------------------+
| Android app process  (ai.opencode.android, Kotlin/Compose)              |
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
| `projects/`, `memory/` | project (workspace) store under `files/workspaces/<id>`, SAF import/export, `AGENTS.md`-based project memory (OpenCode's own mechanism). |
| `ui/` | Compose screens: welcome/ABI/runtime states, projects, chat (streaming transcript, tool cards, permission sheet), settings (providers, models, permission policy, diagnostics). Screens are pure functions of repository state (enforced by a static check). |

### Embedded runtime (`phase4/scripts/10-build-payload.sh` -> `phase4/out/engine`)

| Artifact | Packaging | Why |
|---|---|---|
| `libbun.so` | JNI lib per ABI (`@oven/bun-linux-{x64,aarch64}-android` 1.3.14) | Android only allows `exec` from `nativeLibraryDir` (W^X, API 29+); JNI libs land there automatically. Bun's Android build is bionic-linked, so the zygote seccomp policy is satisfied. |
| `libgit.so`, `librg.so` | JNI libs, built from source with NDK 28.2.13676358 | real git 2.48.1 (`NO_PERL`, no curl/openssl: local repo ops only) and ripgrep 15.1.0 (OpenCode expects an `rg` binary). |
| `libexecshim.so`, `libchildshim.so`, `libseccompshim.so` | JNI libs | exec shim launches Bun with `LD_PRELOAD` set; the seccomp shim neutralises syscalls Android's app policy forbids (BPF child filter on x86_64, preload on arm64). See RUNTIME.md. |
| `runtime-payload.tar.gz` | asset | `opencode/dist/node/node.js` (Bun.build of upstream `packages/opencode`, target `bun`, defines `OPENCODE_VERSION="1.18.23"`, `OPENCODE_CHANNEL="android"`), `*.wasm` (tree-sitter, photon), `node_modules` needed at runtime, `launcher.js`. |
| `runtime-manifest.json` | asset | pins (`opencodeCommit/opencodeVersion/bunVersion/gitVersion/rgVersion/payloadVersion`), per-file sha256 + size, tarball sha256. Extraction is refused on mismatch; `payloadVersion` bump forces re-extraction. |

### Data layout (app-private, `/data/data/ai.opencode.android/files`)

```
runtime/opencode/dist/node/node.js  extracted + validated payload
runtime/.extracted-v6               extraction marker (payloadVersion)
bin/{bun,git,rg} -> nativeLibraryDir/lib{bun,git,rg}.so
home/                               HOME for the server
xdg/{data,config,state,cache,tmp}/  XDG_* dirs (OpenCode's auth.json, config, sqlite, logs)
workspaces/<project-id>/            one directory per project (= one OpenCode instance each)
secrets/<name>.enc                  AES-256-GCM blobs (server password, provider keys) under AndroidKeyStore
log/runtime.log, log/crashes/       supervisor log + crash captures
```

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
