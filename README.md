# OpenCode for Android

Real [OpenCode](https://github.com/anomalyco/opencode) (pinned commit
`05ea5073`, v1.18.23) running **inside one Android APK** - the upstream
TypeScript server, unmodified, executed by Bun-for-Android, with NDK-built
`git` and `ripgrep`, supervised by a Kotlin/Compose app that talks to it over
loopback HTTP + SSE. No Termux, no user-installed Node/Bun, no remote server,
no reimplementation of the agent in Kotlin.

| | |
|---|---|
| Package | `ai.opencode.android` (debug: `ai.opencode.android.debug`) |
| App version | `1.18.23-phase9` (versionCode 7) - prefix = pinned OpenCode version |
| Android | minSdk 29 (Android 10) - targetSdk 34, compileSdk 34 |
| ABI | **arm64-v8a** ships; **x86_64** for emulator/CI only; armeabi-v7a/x86 refused with an explicit message |
| Runtime | Bun 1.3.14 (official Android/bionic build), git v2.48.1, ripgrep 15.1.0, embedded payload v6 |
| Pins | [`versions.lock`](versions.lock) (mechanically checked against the app and the built payload) |

## Documents

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | process model, packaging, data layout, how the app drives the server |
| [docs/RUNTIME.md](docs/RUNTIME.md) | embedded runtime: Bun, git, rg, shell, seccomp/W^X shims, lifecycle, troubleshooting |
| [docs/SECURITY.md](docs/SECURITY.md) | secrets (AES-256-GCM under AndroidKeyStore), loopback auth, threat model, known limits |
| [docs/TESTING.md](docs/TESTING.md) | JVM tests, on-device gate suites, CI pipeline, evidence layout, how to read a verdict |
| [docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md) | `Capability / Desktop OpenCode / Android implementation / Status` |
| [docs/progress/](docs/progress/) | per-phase reports with evidence (Phase 0-9); the Phase 9 report is the final test report |

## Status (honest, as of 2026-09-15)

Every claim of "works" in these docs is backed by an executed gate on an
emulator (x86_64, API 34, GitHub-hosted) and/or a real arm64 phone (Realme
RMX3830, Android 15); the label tells you which. Things that were never
executed say **NOT TESTED**; things that cannot be done say **BLOCKED** with
the reason.

- **Works (TESTED)**: embedded server boots and answers health over loopback;
  sessions, messages, SSE streaming; file read/write/list; shell via
  `/system/bin/sh`; local git (init/add/commit/diff/log); stdio MCP servers;
  permissions (per-request sheet + standing policy); projects/workspaces
  isolated per directory; crash -> supervised restart; corrupted payload ->
  re-extraction; low-storage behaviour; session persistence across process death.
- **Works only with a funded model key**: the actual LLM turn and tool calls
  (the last credit-bearing run returned a real model reply; CI runs without
  a key SKIP those gates). See TESTING.md.
- **Fixed this release, NOT YET RE-VERIFIED** (the CI run that proves them is
  the Phase 9 workflow, which the repository owner must install - see below):
  1. Provider selection silently falling back to the bundled `opencode`
     provider after adding a key (root cause: no provider-cache invalidation
     after `PUT /auth/:id`; the app now calls `/global/dispose` and picks a
     default model from a *connected* provider).
  2. `@opencode-ai/plugin` install always failing (root cause: the bundle
     reported version `1.18.23-android`, which npm does not have; it now
     reports the bare `1.18.23`).
- **Permanent limitations**: remote HTTP/SSE MCP servers fail through an
  upstream defect ([#47644](docs/progress/upstream-issue-47644-mcp-swallowed-error.md));
  no PTY (interactive terminal feature off; the bash tool itself works);
  git has no network transport (HTTPS/SSH clone/push not available); the
  Keystore master key is software-backed on the tested phone; toybox `tar`
  staging is verified on API 34/35 only, not API 29. Full list in
  CAPABILITY-MATRIX.md and RUNTIME.md.

## Build

```
# 1. embedded runtime payload (needs Linux x86_64, node>=22, cargo, Android NDK 28.2.13676358)
bash phase4/scripts/10-build-payload.sh        # -> phase4/out/engine/{assets,jniLibs}
# 2. app
./gradlew :app:assembleDebug                   # debug APK (both ABIs)
./gradlew :app:assembleRelease :app:bundleRelease   # release APK + AAB (unsigned unless P9_KEYSTORE_* env set)
```

`./gradlew ... -PskipPayload` compiles the app without the payload (unit tests
and compile checks only; the resulting APK cannot run the server).

Release artifact sizes: the debug APK containing both ABIs measured
**143.8 MB** in Phase 8. The arm64-only download from an AAB is roughly half;
the exact per-ABI number is printed by the release pipeline
(`P9-RELEASE note:` lines in `GATES_SUMMARY.txt`).

## CI / release pipeline

`phase9/workflow/phase9-release.yml` is the single pipeline: static checks ->
JVM unit tests -> payload build -> APKs -> fresh emulator -> full Phase 8 suite
(which folds in the Phase 5 and 7 regressions) -> Phase 9 gates -> release
APK/AAB -> one `GATES_SUMMARY.txt` naming every gate `PASS/FAIL/SKIP`. The
automation account used to develop this repository cannot write under
`.github/workflows/`, so the file must be copied there by a repository owner
(instructions in its header). Until that run has happened, the Phase 9 code
changes are labelled NOT TESTED.

## Repository layout

```
app/          Kotlin/Compose app (runtime supervisor, HTTP/SSE client, UI)
phase4/       runtime payload build + device gates (Bun/git/rg/shims, manifest)
phase5..8/    integration, UI, workspace/memory, hardening gate suites + drivers
phase9/       release pipeline (orchestrator, gates, static checks, workflow template)
docs/         architecture / runtime / security / testing / capability matrix / phase reports
versions.lock the pins
```
