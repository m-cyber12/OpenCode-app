# Embedded runtime

What runs inside the APK, how it is built, how it is started and kept alive,
and what to do when it does not. Every "verified" statement names the gate or
evidence bundle; anything without one is labelled.

## Pinned components

| Component | Version | Source / build | Verified on |
|---|---|---|---|
| OpenCode | commit `05ea5073be96` (v1.18.23, `anomalyco/opencode` `dev`) | `Bun.build` of `packages/opencode` -> `dist/node/node.js`, target `bun` (needed for upstream's `#db` -> `bun:sqlite` condition) | emulator API 34 x86_64; phone API 35 arm64 |
| Bun | 1.3.14 | official `@oven/bun-linux-{x64,aarch64}-android` npm packages, bionic-linked, `PT_INTERP /system/bin/linker64` | same |
| git | v2.48.1 | upstream source, Android NDK 28.2.13676358, `NO_PERL NO_CURL NO_OPENSSL NO_EXPAT`, bionic | same (init/add/commit/diff/log: P5 G-gates, P8-LARGE) |
| ripgrep | 15.1.0 | upstream source, cargo, `x86_64/aarch64-linux-android`, pcre2 | same |
| shell | `/system/bin/sh` (Android's mksh) | not bundled; OpenCode `shell` config points to it | same (bash tool via `/shell` endpoint, P5, P8-PERF `shellOk=1`) |
| payload | v7 | `runtime-payload.tar.gz` + `runtime-manifest.json` (+ pre-seeded `@opencode-ai/plugin` tree) | v7 TESTED in CI run 2 (`P9_PLUGIN PASS`, zero exit-159); run 2 also showed v7 re-extracting on every launch because the verifier looked for the seed at its pre-promotion path - fixed (`installedLocation`), pending run 3 |

`versions.lock` is the source of truth; `phase9/scripts/check-lock.py`
asserts `RuntimeVersion.kt`, `app/build.gradle.kts` and the built manifest
agree with it (static check + device gate `P9_LOCK`).

## Build (`phase4/scripts/10-build-payload.sh`)

Linux x86_64 host (the GitHub runner image): node >= 22, cargo, Android NDK
28.2.13676358 (pinned; r29 links the x86_64 child shim as `ET_EXEC`, which
Android cannot exec - see `versions.lock` `ci_toolchain`). Steps: sparse clone
of the pinned commit -> `bun install` -> `Bun.build` with defines
`OPENCODE_VERSION="1.18.23"`, `OPENCODE_CHANNEL="android"` -> collect wasm +
runtime `node_modules` -> tar.gz -> manifest with per-file sha256 -> download
Bun Android builds -> build git/rg/shims with the NDK -> rename all executables
to `lib<name>.so` per ABI.

**Phase 9 change (payload v5 -> v6):** the define used to be
`OPENCODE_VERSION="1.18.23-android"`. Upstream (`packages/opencode/src/config/config.ts`)
installs `@opencode-ai/plugin@<OPENCODE_VERSION>` when a plugin is configured;
that version does not exist on npm, so every plugin install ended in
`NpmInstallFailedError` (observed on the real device in Phase 8). The bundle
now reports the bare `1.18.23` (`GET /global/health` `version` field; gate
`P9_VERSION`). The channel remains `android`, which upstream only uses for
`isPreview`/user-agent.

## Startup sequence (supervisor)

```
IDLE -> CHECKING_ABI (arm64-v8a | x86_64 else UNSUPPORTED_DEVICE)
     -> EXTRACTING   (marker .extracted-v6 present and manifest sha256s verify? skip : extract)
     -> STARTING     (exec shim -> LD_PRELOAD seccomp shim -> bun launcher.js -> OpenCode serve
                      --hostname 127.0.0.1 --port 4111, Basic auth opencode:<per-install password>)
     -> HEALTHY      (GET /global/health 2xx + "healthy"; bounded wait)
crash/exit  -> CRASHED_RESTARTING (backoff 1 s, 2 s, ... 30 s cap, jitter, max 8) -> STARTING | FATAL
stop        -> STOPPING (SIGTERM, then SIGKILL, then /proc sweep for launcher.js children) -> IDLE
```

Measured windows (Phase 8, `runtime.log`): emulator first launch ~9.6 s
(EXTRACTING -> HEALTHY incl. extraction), warm median ~2.5 s; phone first
launch 13-21.8 s supervisor window, server STARTING -> HEALTHY ~6.4 s on the
second launch. Warm restart after SIGKILL on the emulator: 22 s (P8-SERVERKILL).

Single-instance guarantees: one `RuntimeService`, a pidfile, a `/proc` sweep
for stray `launcher.js` processes, and the single port bind - verified by
P8-SERVERKILL / P8-CRASH / P8-LIFECYCLELOG (0 illegal transitions).

### Environment handed to the server (`RuntimeEnv`)

`HOME=files/home`, `XDG_{DATA,CONFIG,STATE,CACHE}_HOME=files/xdg/...`,
`TMPDIR=files/xdg/tmp`, `PATH=files/bin:/system/bin`, `OPENCODE_SERVER_PORT=4111`,
`OPENCODE_SECCOMP_SHIM=<nativeLibraryDir>/libseccompshim.so`, plus the
per-install Basic-auth password. Nothing is world-readable; everything is
under the app UID.

### Seccomp / W^X shims

Android's app seccomp policy kills processes that issue certain syscalls
(SIGSYS); statically-linked desktop binaries die this way, which is why every
executable here is bionic-linked and why an `LD_PRELOAD` shim intercepts the
remaining offenders. The exec shim exists because `LD_PRELOAD` must be set
*before* Bun starts and Bun re-execs its children (shell, git, rg, MCP) with
the same environment. A "seccomp shim: ..." warning in `runtime.log` is
informational, not fatal (Phase 8 note). Details and the evidence chain:
`docs/progress/phase4-seccomp-state.md`.

## Runtime feature map on Android

| Feature | Android | Note |
|---|---|---|
| bash tool | works via `/system/bin/sh` with stdio pipes | no PTY; interactive terminal feature (upstream `Pty` service) is unavailable |
| git | local operations only | no curl/openssl -> no HTTPS/SSH clone/fetch/push; import/export projects through SAF instead |
| file watching | disabled | `@parcel/watcher` has no Android binding; upstream falls back gracefully |
| MCP stdio | works | P5 G10 (real `@modelcontextprotocol/sdk` server spawned by the runtime's own bun) |
| MCP remote HTTP/SSE | **fails** | upstream #47644 swallows the transport error; permanent until upstream fixes |
| plugins (`@opencode-ai/plugin`) | pre-installed in the payload (v7) | **on-device `npm install` (any extra plugin) SIGSYS-crashes Bun** - exit 159, supervised restart; documented BLOCKED until the syscall is added to the shim table (`P9_PLUGIN` forensics) |
| sqlite | `bun:sqlite` | Bun 1.3.14 has no `node:sqlite`; the bundle targets `bun` |

## Android versions and ABIs

- minSdk **29** (Android 10): required for the W^X model (exec only from
  `nativeLibraryDir`). targetSdk/compileSdk 34.
- **Tested**: API 34 emulator (x86_64, every CI run), API 35 real phone
  (arm64-v8a, Realme RMX3830, eight Phase 8 runs).
- **NOT TESTED**: API 29-33 on real hardware. In particular the *test
  harness's* toybox `tar xz` staging path is verified on 34/35 only; the
  product's own extraction does not use toybox (it uses the app's Kotlin tar
  reader), so this gap affects the test harness more than the product, but it
  is unverified either way.
- armeabi-v7a / x86: refused at `AbiGate` with an on-screen message (no
  64-bit-less Bun exists).

## Footprint

- Debug APK, both ABIs: **143.8 MB** (Phase 8 CI). Per-ABI numbers from an AAB
  are printed by the release pipeline (`P9-RELEASE note:` lines).
- Extracted `filesDir` after first run: **~208 MB** on the emulator (opencode
  bundle 89.5 MB, xdg 9.5 MB, rest = extracted payload + workspaces).
- App process PSS on the phone: ~88 MB. Server process memory and steady-state
  CPU: **NOT MEASURED** (Phase 8 gap carried; no Phase 9 profiling pass ran).

## Troubleshooting

| Symptom | Where to look | Likely cause / action |
|---|---|---|
| "Unsupported device" screen | `AbiGate` | 32-bit-only device. Nothing to do. |
| `(re)extracting` on every launch (`extraction invalid (missing ...)` each start, +4-10 s) | `runtime.log` | the verifier expected a manifest entry at a path the extractor promotes elsewhere (payload v7 `plugin-seed/` -> `xdg/config/opencode/`); fixed by `PayloadExtractor.installedLocation`. A fresh install should log `extraction complete` once. |
| Stuck in EXTRACTING, then FATAL | `files/log/runtime.log`: `manifest mismatch` / `sha256` | corrupted payload or low storage; the app re-extracts on the next launch (P8-CORRUPT). Free space if `ENOSPC`. |
| STARTING -> CRASHED_RESTARTING loop -> FATAL | `runtime.log` (child stderr captured), `files/log/crashes/` | SIGSYS = seccomp (should not happen with shims; file with the crash capture); `EADDRINUSE` = a stray process (the sweep should have killed it; force-stop the app). |
| HEALTHY but chat says the model service is unreachable | Settings -> Diagnostics; `xdg/state/opencode/log/` | device has no network, or provider outage. Classified as NETWORK from the server's own error. |
| Added a key but turns use the wrong model / return nothing | Settings -> Providers (should list the provider as connected after save) | pre-Phase-9 builds needed an app restart (provider cache); v6 disposes the instance on save. If it persists, check `session.error` in the OpenCode log for `ProviderModelNotFoundError`. |
| Empty assistant turn, no error shown | OpenCode log | upstream completes provider failures as silent empty turns (Phase 8 §7/§13); the raw provider text, when present, is in the disclosure of the error card. Known upstream behaviour. |
| Server restarts every ~8 s (`code=159` in `runtime.log`, `_cacache` lines before it) | `runtime.log`, OpenCode log `dependency install failed` | upstream is running `npm install` for a plugin on-device and Arborist hits a trapped syscall. Payload v7 pre-seeds the default plugin so this path is not entered; remove any extra `plugin:` entry from `opencode.json` to stop the loop. |
| Remote MCP server "connected" but no tools | - | upstream #47644; use a stdio MCP server instead. |
| Background turn stops | notification | the foreground service must be allowed to run (battery optimisation exemption); the server itself keeps answering health in the background (P8-BGFG measurement). |

Logs the app can share from Settings -> Diagnostics: `runtime.log` (supervisor
transitions), the last crash capture, and the OpenCode server log directory.
Secrets are never written to any of them (`LoopbackAudit` and the Phase 8
secret-handling check).
