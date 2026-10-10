# OpenCode for Android

Real [OpenCode](https://github.com/anomalyco/opencode) (pinned commit
`05ea5073`, v1.18.23) running **inside one Android APK** - the upstream
TypeScript server, unmodified, executed by Bun-for-Android, with NDK-built
`git` (HTTPS-capable) and `ripgrep`, supervised by a Kotlin/Compose app that
talks to it over loopback HTTP + SSE. No Termux, no user-installed Node/Bun,
no remote server, no reimplementation of the agent in Kotlin.

| | |
|---|---|
| Package (published) | `io.github.mcyber12.opencode` (debug: `io.github.mcyber12.opencode.debug`) - the Play application ID, permanent at first publish; see [docs/BRANDING.md](docs/BRANDING.md) |
| App version | `1.18.23-phase10` (versionCode 8) - prefix = pinned OpenCode version |
| Android | minSdk 29 (Android 10) - targetSdk 34, compileSdk 34 |
| ABI | **arm64-v8a** ships; **x86_64** for emulator/CI only; armeabi-v7a/x86 refused with an explicit message |
| Runtime | Bun 1.3.14 (official Android/bionic build), git v2.48.1 **with HTTPS** (static curl 8.10.1 + Mbed TLS 3.6.2 in `libgitremotehttp.so`), ripgrep 15.1.0, embedded payload v7 |
| Pins | [`versions.lock`](versions.lock) (mechanically checked against the app and the built payload) |

## What the app does

The Compose UI is a full product around the embedded agent, not a thin debug
shell. Everything below is driven by upstream's own HTTP/SSE API; none of it
re-implements agent logic.

**Conversation.** Dark glass-and-gold design (Sora / Space Grotesk /
JetBrains Mono); streaming replies rendered incrementally with markdown and
syntax-highlighted code; collapsible tool cards (shell output + exit code,
edit diffs with +/- counts, diagnostics); Copy/Like/Dislike under every
completed agent turn; Retry/Undo/Redo on the newest turn; wall-clock,
token and cost footers per turn. The header holds a hamburger menu
(project/session identity, new session, settings, compaction), a model
capsule (starred models + per-model thinking levels, real server ids only)
and a Build/Plan mode selector next to the status orb.

**Working bar = task panel.** While the agent runs, the bar names the task
actually in progress, taken from the current turn's `todowrite` calls, with a
done-counter and a tap-to-unfold checklist (done / active / pending rows).
Finished tasks get a brief green-checkmark hand-off before the next task
fades in. The todo list lives only here - tool cards for `todowrite` are
deliberately not rendered in the transcript.

**Questions & plans.** The upstream `question` tool is enabled for this
client (`OPENCODE_ENABLE_QUESTION_TOOL=1`); when the model asks, the
questions render **inline in the chat** as a stepper card at the tail of the
transcript - one question at a time, single-choice taps auto-advance,
Back/Next/Submit/Skip. Plan mode is model-driven end to end: the model
presents its plan and asks for approval through the question tool; approving
switches the agent to Build and sends the execution prompt. The approval card
sits directly below the plan text, so the whole plan is readable before
deciding. Permission asks (bash, etc.) stay in a non-dismissible bottom
sheet with allow-once / always / reject.

**Projects & storage.** Each project is its own directory and its own
OpenCode instance (`?directory=`). The default root is
`Documents/OpenCode/<project>` on shared storage (with the user's
"All files access" grant), so the agent's files are visible to any file
manager, MTP/USB and `adb` while it works; the app falls back to app-specific
external storage when the grant is refused. In-app file browser, Changes and
Terminal tabs, SAF import/export of whole projects.

**GitHub projects.** A project can be created as a GitHub project: connect
with a personal access token (`repo`, `workflow` scopes), pick a repository
from your list, and the app clones it and puts the project on its own branch
`opencode/<project>` - every session of that project stays on that branch.
The agent pushes **itself** using the bundled HTTPS git (the environment
brief teaches it the `x-access-token` push recipe); the app never auto-pushes.
The token is only injected for GitHub projects - non-GitHub projects can
never see it or your repositories. GitHub projects carry a GitHub mark in the
project list and the chat menu.

**Providers & keys.** Multiple API keys per provider (a keyring), automatic
failover to the next key on limit errors with manual override, connect/import
flows in Settings. Keys are stored as AES-256-GCM blobs under a
non-exportable AndroidKeyStore key and handed to the server via `PUT /auth`.

**Token hygiene.** The app writes an environment brief (`environment.md`,
injected through upstream's own `config.instructions`) that tells the model
what exists on the phone - bun but no node/python, setsid-not-nohup, the git
push recipe, the add-ons shelf, the question-tool contract - so turns are not
burned probing. Session history is auto-compacted via upstream
`/session/.../summarize` when a turn crosses ~40k tokens (with a visible
notice), and a manual "Compact" action lives in the menu.

**Add-ons.** Big artifacts (e.g. wasm compilers) are downloaded once by the
APP - plain HTTP with progress, no model tokens, no API key needed - into
`<workspace root>/.addons/`, where any agent shell can `tar -xzf` them.

**Sandbox preview.** A tiny loopback-only static file server serves the
active project directory to the in-app WebView and the phone's browser with a
real http origin (module scripts, fetch and storage behave), so web projects
render the way a deployed site renders. Nothing off the phone can connect.

## Documents

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | process model, packaging, data layout, how the app drives the server |
| [docs/RUNTIME.md](docs/RUNTIME.md) | embedded runtime: Bun, git, rg, shell, seccomp/W^X shims, lifecycle, troubleshooting |
| [docs/SECURITY.md](docs/SECURITY.md) | secrets (AES-256-GCM under AndroidKeyStore), loopback auth, threat model, known limits |
| [docs/TESTING.md](docs/TESTING.md) | JVM tests, on-device gate suites, CI pipeline, evidence layout, how to read a verdict |
| [docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md) | `Capability / Desktop OpenCode / Android implementation / Status` |
| [docs/RELEASE.md](docs/RELEASE.md) | **signing and release**: keystore, the human signing step, verification, Play App Signing |
| [docs/BRANDING.md](docs/BRANDING.md) | app name/icon, the OpenCode trademark position, and the rename path that never touches the package name |
| [docs/PRIVACY-POLICY.md](docs/PRIVACY-POLICY.md) | what the app does with data, written from the implementation (the listing links to it) |
| [docs/STORE-LISTING.md](docs/STORE-LISTING.md) | the complete Play listing package: descriptions, screenshots, content rating, data safety |
| [docs/THIRD-PARTY-NOTICES.md](docs/THIRD-PARTY-NOTICES.md) | everything inside the APK and its licence, incl. the GPL-2.0 written offer for Git |
| [docs/progress/](docs/progress/) | per-phase reports with evidence (Phase 0-10); the **Phase 10** report (and its appendix B, one dated section per UI round) is the current record |

## Status (honest, as of 2026-10-10)

Every claim of "works" in these docs is backed by an executed gate on an
emulator (x86_64, API 34, GitHub-hosted) and/or a real arm64 phone (Android
15); the label tells you which. Things that were never executed say **NOT
TESTED**; things that cannot be done say **BLOCKED** with the reason. The
most recent CI run of the full pipeline (2026-10-10, run 38065840240):
408 JVM tests green, all 20 on-device UI gates green except the two that
need a funded model key (SKIP without one), real-device driver self-test
136/136.

- **Works (TESTED in CI, emulator)**: embedded server boots and answers
  health over loopback; sessions, messages, SSE streaming; file
  read/write/list; shell via `/system/bin/sh`; local git
  (init/add/commit/diff/log); stdio MCP servers; permissions (per-request
  sheet + standing policy); question-tool cards incl. the inline stepper and
  the plan-approve flow (fixture-driven gates); the working-bar task panel;
  projects/workspaces isolated per directory on the shared-storage root;
  crash -> supervised restart; corrupted payload -> re-extraction;
  low-storage behaviour; session persistence across process death.
- **Works (TESTED on a real phone)**: the GitHub architecture end to end -
  token connect, repo list, clone onto `opencode/<project>`, and a real
  model turn that pushed over HTTPS, opened a PR and merged it
  (2026-10-09). Question cards render on device with a real model turn; the
  full answer round-trip on device is CI-proven but awaiting the owner's
  device pass, like the v9.32 UI round itself.
- **Works only with a funded model key**: the actual LLM turn and live tool
  calls (CI runs without a key SKIP those two gates; the last credit-bearing
  run returned a real model reply). See TESTING.md.
- **Permanent limitations**: remote HTTP/SSE MCP servers fail through an
  upstream defect ([#47644](docs/progress/upstream-issue-47644-mcp-swallowed-error.md));
  no PTY (interactive terminal feature off; the bash tool itself works); git
  speaks **HTTPS only** (no SSH transport); installing *additional* npm
  plugins on device is BLOCKED (Arborist SIGSYS-crashes Bun; the default
  plugin ships pre-installed); the Keystore master key is software-backed on
  the tested phone; toybox `tar` staging is verified on API 34/35 only, not
  API 29. Full list in CAPABILITY-MATRIX.md and RUNTIME.md.

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

Release signing is a human-held upload key (docs/RELEASE.md); CI never signs
a release. A committed **test-only** debug keystore
(`app/testonly-debug-signing.p12`, public password) exists so CI smoke
installs are reproducible - it has nothing to do with the release identity.

## CI / release pipeline

`.github/workflows/phase10-release.yml` is the single pipeline: static checks
-> JVM unit tests -> payload build -> APKs -> fresh emulator -> the full UI
gate suite (which folds in the Phase 5-8 regressions) -> workspace gates ->
release APK/AAB verification -> store-asset checks -> one
`GATES_SUMMARY.txt` naming every gate `PASS/FAIL/SKIP`, committed back to the
branch under `docs/progress/phase10-evidence/`. The automation account used
to develop this repository cannot write under `.github/workflows/`, so
changes to the workflow file itself are applied by a repository owner.

## Repository layout

```
app/          Kotlin/Compose app (runtime supervisor, HTTP/SSE client, UI,
              projects/GitHub/add-ons/preview/providers subsystems)
phase4/       runtime payload build + device gates (Bun/git/rg/shims, manifest)
phase5..8/    integration, UI, workspace/memory, hardening gate suites + drivers
phase9/       release pipeline building blocks (gates, static checks, lock check)
phase10/      current pipeline: orchestrator, release verification, smoke/store
              gates, real-device driver + its self-test
docs/         architecture / runtime / security / testing / capability matrix /
              release / branding / store docs / phase reports with evidence
versions.lock the pins
```
