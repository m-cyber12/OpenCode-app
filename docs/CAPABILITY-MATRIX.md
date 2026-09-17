# Capability matrix

Desktop OpenCode (pinned commit `05ea5073`, v1.18.23) versus this Android
build. "Android implementation" says *how* the capability is provided; the
status label follows the project's honesty rules:

- **TESTED** - executed on the emulator (x86_64, API 34) and/or the real arm64
  phone (API 35) by a named gate with recorded evidence.
- **IMPLEMENTED, NOT TESTED** - code exists; the gate that proves it has not
  produced a PASS yet (the reason is given).
- **DEGRADED** - works with a documented, permanent reduction.
- **BLOCKED** - cannot work on Android in this architecture, or fails through
  an upstream defect; reason given.

Nothing in the agent, tools or server is reimplemented: every row's engine is
the upstream TypeScript bundle running under Bun-for-Android.

| Capability | Desktop OpenCode | Android implementation | Status |
|---|---|---|---|
| **Agent loop** (plan/act, multi-step tool use, sub-agents) | in-process TypeScript | identical bundle in the embedded server; app only renders `message.part.*` events | TESTED (turns + tool parts observed: P5 K/G gates, device run 1 real model reply). Live tool card with a funded key: **NOT RE-VERIFIED** since Phase 8 (`BLOCKED-NO-CREDIT`; provider-selection fix awaits the Phase 9 CI run) |
| **Models / providers** (models.dev catalogue, any provider SDK, OpenRouter, bundled `opencode` zero-cost models) | Settings UI + `auth.json` | same provider table (`GET /provider`), key entry in Settings -> Providers -> `PUT /auth/:id` + AndroidKeyStore blob; Phase 9: dispose after credential change, default model from a connected provider | catalogue + auth: TESTED (P5, P8-CLEANUP). Provider-selection fix: IMPLEMENTED, **NOT TESTED** until `P9_PROVSEL_*` runs |
| **Sessions** (create, list, fork, share, persistence) | sqlite under XDG data | same sqlite (`bun:sqlite`) under `files/xdg/data/opencode`; list/create/switch/delete in UI; share is not exposed | TESTED (create/list/switch; persistence across process death P8-SESSIONPERSIST PASS). Share/fork: not exposed, NOT TESTED |
| **Streaming** (SSE `/event`: text deltas, tool parts, status) | SSE | `OpenCodeEventStream` over loopback; deltas rendered incrementally | TESTED (P5 G-gates, P8-PERF ttft path exercised; model half of PERF blocked by missing credit) |
| **File read** (read/glob/grep tools, `/file` API) | native fs + ripgrep binary | same tools; `librg.so` (ripgrep 15.1.0, NDK) | TESTED (P5, P8-LARGE: 2000 files list 163-171 ms, content 58-252 ms) |
| **File write** (write/edit/patch tools) | native fs | same, inside `files/workspaces/<project>` | TESTED (P5 G-gates, P7 W2 boundary) |
| **Shell** (bash tool, `/shell` endpoint) | user's shell, PTY optional | `/system/bin/sh` (mksh) with stdio pipes | TESTED (`shellOk=1`, P5). **DEGRADED**: no PTY -> no interactive terminal feature; no bash/coreutils beyond toybox |
| **Git** (status/diff/commit in tools + UI, snapshots) | system git | `libgit.so` (git 2.48.1 NDK build), used by upstream's snapshot/undo and by the bash tool | TESTED (init/add/commit/diff/log: P5, P8-LARGE). **DEGRADED**: no HTTPS/SSH transport (no clone/fetch/push); projects move via SAF import/export |
| **Permissions** (per-tool ask/allow, rules in config) | dialog in TUI/desktop | `permission.asked` -> bottom sheet; standing policy per project (ask / allow for session); config rules honoured by upstream | TESTED (P6-U3, P7 permission gates) |
| **MCP** (stdio, remote HTTP/SSE, OAuth) | `@modelcontextprotocol/sdk` 1.29 | same client; stdio servers spawned by the runtime's own Bun | stdio: TESTED (P5 G10). Remote HTTP/SSE: **BLOCKED** - upstream #47644 swallows the transport error (P5-G16 red by design, documented). OAuth MCP: NOT TESTED |
| **Project / workspace** (cwd = project, multiple projects, worktrees) | directory chosen at launch | Projects screen; each project = `files/workspaces/<id>` = its own OpenCode instance (`?directory=`); SAF import/export; isolation gate | TESTED (P7 W1-W3). Worktrees: not exposed |
| **Server API** (Hono HTTP + SSE, Basic auth, OpenAPI) | `opencode serve` | identical server on `127.0.0.1:4111`, per-install password; not reachable off-device by design | TESTED (P5-G17 loopback audit, health/auth gates) |
| **Tools** (read, write, edit, bash, glob, grep, list, webfetch, todo, task/sub-agent, question) | built-in | identical; `webfetch` uses device network | file/shell/git tools TESTED; webfetch/task/question: IMPLEMENTED (upstream), NOT TESTED on Android |
| **Configuration** (`opencode.json`, agents, commands, instructions, `AGENTS.md`) | files under project / XDG config | same files under `files/xdg/config/opencode` and the project dir; Settings writes model/permission/shell keys via `/config` | TESTED (P7 memory via `AGENTS.md`, shell/model config). Custom agents/commands: NOT TESTED |
| **Authentication** (API keys, OAuth device flows e.g. Anthropic/GitHub Copilot, `/auth`) | `auth.json` + browser flows | API keys: Settings -> Providers (Keystore-encrypted copy + `PUT /auth`). OAuth/device-code flows: not exposed in the UI | API key: TESTED (P8-CLEANUP, PROVAUTH shape). OAuth: **NOT IMPLEMENTED in UI** (server supports it; no screen drives it) |
| **Memory / persistence** (sessions, messages, snapshots, project memory) | sqlite + git snapshots + `AGENTS.md` | same stores under app-private XDG; project pointer store in the app; survives process kill and app restart | TESTED (P8-SESSIONPERSIST, P7 L1/L2 memory, `ProjectStorePersistenceTest`) |
| **Plugins** (`@opencode-ai/plugin`, npm-installed on demand) | installed by upstream via npm | default package pre-installed in the payload (v7) so no on-device npm runs; version string fixed (TESTED `P9_VERSION`) | seed: IMPLEMENTED, NOT TESTED until run 2. **Extra user plugins: BLOCKED** - on-device Arborist SIGSYS-crashes Bun (run 1: 113 restarts) |
| **File watching** (live reload of external edits) | `@parcel/watcher` | no Android binding; upstream disables it | **DEGRADED** (documented; no functional loss for the chat flow) |
| **Background operation** | n/a | foreground service keeps server + children alive; health stays 200 in background | server survives background: TESTED (P8-BGFG measurement); full turn completing in background: gate FAIL (no verdict line) -> NOT PROVEN |
| **Crash recovery** | manual restart | supervised restart with bounded backoff; corrupted payload re-extraction | TESTED (P8-SERVERKILL, P8-CRASH, P8-CORRUPT, P8-LIFECYCLELOG) |
| **Low storage / large projects** | n/a | honest error + recovery; 2000 files / 50 MB project responsive | TESTED (P8-STORAGE, P8-LARGE) |
| **TUI / desktop windows** | Bubble Tea TUI, Tauri desktop | not applicable; Compose UI instead | n/a |
| **Network loss mid-turn** | error surfaced | classifier renders network-shaped error | **Partially**: cut is effective (turn errors) but upstream reports an empty error string (silent completion) -> P8-NETLOSS FAIL, documented upstream behaviour |

### Android-specific facts

- Android 10+ (API 29), arm64-v8a only for users; x86_64 builds exist for CI.
- Everything lives in app-private storage; no root, no Termux, no user install
  of Node/Bun.
- Test coverage by OS version: API 34 emulator (every CI run) and API 35
  phone. API 29-33: NOT TESTED.
