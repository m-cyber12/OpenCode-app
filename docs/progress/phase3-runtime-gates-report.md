# Phase 3 — Runtime Gates (G1–G15): Final Report

**Status: ✅ ALL 15 GATES PASS** (real Android emulator, real components, real model — no mocks)

- Final CI run: **`33220439459`** — "phase3: fix premature turn completion + cross-session permission replies"
- Job: `gates` on `ubuntu-latest`, **11m36s, green** (all steps ✓)
- Evidence commit: **`cec8e59`** → `docs/progress/phase3-evidence/` (per-gate logs, server log, message dumps, prep/git build logs, provenance hashes)
- Summary file: `docs/progress/phase3-evidence/GATES_SUMMARY.txt`

```
GATE_01: PASS execution-layer
GATE_02: PASS userspace
GATE_03: PASS real-shell
GATE_04: PASS runtime
GATE_05: PASS opencode-start
GATE_06: PASS health-endpoint
GATE_07: PASS opencode-shell-exec
GATE_08: PASS file-read-write
GATE_09: PASS real-git
GATE_10: PASS mcp-stdio
GATE_11: PASS streaming-sse
GATE_12: PASS permissions
GATE_13: PASS stop-restart
GATE_14: PASS reconnect
GATE_15: PASS end-to-end
GATES_SUMMARY 2026-08-28T23:38:04Z
total=15 pass=15 fail=0 blocked=0
model_available=1
```

## Environment under test

| Component | Value | Evidence |
|---|---|---|
| Emulator | Android 14 (API 34), x86_64, google_apis, headless, KVM | `03-run-gates.log`, `emulator.log` |
| Execution layer | Bun for Android **1.3.14** (`@oven/bun-linux-x64-android`, bionic ELF) | `bun-x64.sha256`, G1 log |
| OpenCode server | Bundle of upstream `src/node.ts` @ **`05ea5073`** (v1.18.23, pinned — not drifted `dev`) | `UPSTREAM_COMMIT.txt`, `00-run-gates.log` |
| Shell | `/system/bin/sh` (Android mksh) | G3 log |
| git | **v2.48.1**, x86_64 **musl-static** (built from source; `NO_PERL=YesPlease NO_REGEX=NeedsStartEnd`), aarch64 glibc-static as inventory | `git.status`, `git.upstream.commit.txt` |
| ripgrep | 15.1.0 x86_64 musl-static | `rg.sha256` |
| MCP | Real `@modelcontextprotocol/sdk` **1.29.0** stdio server + client | `mcp-deps.txt`, G10 log |
| Model | `nvidia/nemotron-3-ultra-550b-a55b:free` via OpenRouter (repo secret `OPENROUTER_API_KEY`) | `device-config.txt`, `models-dev.snapshot.json` |
| Permissions | `bash`/`edit`/`webfetch` = `ask` in `opencode.jsonc` | `device-config.txt` |

## Gate-by-gate results

| Gate | Result | What executed (from the device logs) |
|---|---|---|
| G1 execution-layer | **PASS** | Android facts (`release=14 sdk=34 abi=x86_64`), bionic ELF `bun` executed from the Android filesystem, `platform=android` |
| G2 userspace | **PASS** | env/XDG dirs, `/proc` (kernel 6.1.23-android14), tmp writable, `ripgrep 15.1.0`, `git version 2.48.1`, `bun:sqlite` row read |
| G3 real-shell | **PASS** | `/system/bin/sh` (mksh): variable expansion, pipe rc propagation, redirect write, exit code capture |
| G4 runtime | **PASS** | Bun 1.3.14 on Android: fs, `child_process`, fetch, `bun:sqlite` |
| G5 opencode-start | **PASS** | `SERVER_READY http://127.0.0.1:4111/` from the pinned bundle |
| G6 health-endpoint | **PASS** | `GET /global/health` → healthy |
| G7 shell-exec | **PASS** | Real `POST /session/:id/shell` → bash tool `completed`, output `G7_SHELL_OK … shell=/system/bin/sh`; SSE showed `message.part.updated` lifecycle `running→completed` |
| G8 file-read-write | **PASS** | `/file` list, `/file/content`, `/find` (ripgrep found the marker); write via server shell exec; **agent's real `write` tool** wrote `g8-agent.txt` = `G8_AGENT_WRITE_OK` (permission ask `edit g8-agent.txt` → reply once) |
| G9 real-git | **PASS** | `git version 2.48.1` on device; full init/status/add/commit/diff/branch cycle; through the OpenCode server too — `BRANCHES: feature/g9, * main`, log `a2d7e59 g9: commit from the Android runtime` |
| G10 mcp-stdio | **PASS** | OpenCode's own MCP client reports `gates-mcp connected`; SDK client round-trip `TOOLS=[echo, write_marker]`, `CALL=echo:G10_MCP_ROUNDTRIP_OK`; **agent used the MCP tool** (`gates-mcp_echo` → `echo:G10_MCP_AGENT_OK`) and reported it |
| G11 streaming-sse | **PASS** | `/global/event` SSE: 30+ events per shell turn; model turn: **45 events**, `message.part.delta` + `message.part.updated` streaming the text part **progressively (4 updates)**; `session.status` `busy→idle` |
| G12 permissions | **PASS** | Real `permission.asked` (bash `echo G12_PERM_OK`, own session) → reply `once` → tool executed, output `G12_PERM_OK` present in final text |
| G13 stop-restart | **PASS** | SIGTERM → `SERVER_STOPPED_AFTER_TERM`, port down → restart → health 200 with same data dirs |
| G14 reconnect | **PASS** | Pre-restart session (`G7 shell exec`) still listed after restart; history intact (4 messages, shell output present); **continued in the same session** → `G14_RESUME_OK` appended |
| G15 end-to-end | **PASS** | Prompt "Inspect this project and explain what it does" → agent ran `glob` + 5 `read`s and produced an **801-char accurate explanation** (fixture project, Android emulator, G1–G15 purpose); 245 SSE events, tool parts streamed |

## How the gates run

1. **`00-run-gates.sh`** (orchestrator, on the GitHub Actions runner): probe → SDK check → AVD create → boot emulator → **`01-prepare-artifacts.sh`** (bundle build @ pinned commit, bun, rg, static git, MCP) → **`03-run-gates.sh`** (adb push + layout verification + on-device runner) → **`53-evidence.sh`** → summary.
2. On device, **`gates-runner.sh`** (mksh) runs 15 gate drivers (`gate-*.js` under Bun) against the live server, each writing its own log; the summary is pulled back and committed to `docs/progress/phase3-evidence/` even when a run fails.
3. Any `GATE …: FAIL` fails the CI job; `BLOCKED` is accepted only with a documented root cause (none remain).

## Failure history and fixes (honest log)

The suite was built by iterating on real failures, each captured from device evidence:

| CI run | Outcome | Root cause (from evidence) | Fix |
|---|---|---|---|
| `33215077537` | workflow rejected | YAML: unquoted step name contained `lesson: never` (colon+space) → `mapping values are not allowed here` | Quote step names, ASCII-ify template |
| `33217004464` | 8 PASS / 7 FAIL | (a) `01-prepare` died at the aarch64 rg download but the orchestrator's `run()` swallowed the exit code → gates ran against **missing git + empty mcp**; (b) `prompt_async` sent `{text}` → HTTP 400 `Missing key at ["parts"]`; (c) G10 cwd missing | `run_c` fail-fast; `promptAsync` sends `{parts:[{type:"text",…}]}`; device layout verification; evidence additions |
| `33217799555` | prep aborted (loudly) | aarch64 rg asset is **`-linux-gnu`**, not `-musl` (404, verified via release API); musl git build failed: **`REG_STARTEND`** missing in musl regex | Use the gnu asset (inventory-only); add `NO_REGEX=NeedsStartEnd`; auto-fallback musl→gcc-static; prep self-log + `git.status` into evidence |
| `33218434370` | layout check fired | `adb push` **nested** `mcp/mcp/…` because `mcp/` was pre-created remotely | Never pre-create pushed dirs; fail fast with the missing-file list |
| `33219004187` | 10 PASS / 5 FAIL | All model gates "completed" **instantly with empty text**: `waitTurnComplete` saw mid-reasoning messages (`step-start`/`reasoning` parts carry **no `state`**) as done; real turns were still running when G13 stopped the server → `error=Aborted` (server log). Plus G12 replied to **G8's** still-running session's ask (global event watch) | Turn is final only when the last message is a *completed* assistant message **and** the list is stable across 3 polls; permission replier + G12 filter by session id; message dumps + server-log pull for diagnosis |
| `33219703148` | 10 PASS / 5 FAIL | Diagnostics run — confirmed both root causes above via dumps + `server-log/opencode.log` | (see next) |
| **`33220439459`** | **15 PASS / 0 FAIL** | — | — |

## Carry-over items (Phase 2 → Phase 3)

1. **Real static git for arm64 (G9)** — **RESOLVED**: static git v2.48.1 built from source in CI. x86_64 musl-static **runs on the emulator** (all git gates green); aarch64 glibc-static built as product inventory (musl.cc cross toolchain is unavailable in this sandbox, apt `gcc-aarch64-linux-gnu` fallback used on the runner — see `git.status`).
2. **Does any gate need a real PTY?** — **RESOLVED (no)**: verified at the source level (`tool/bash.ts` spawns the configured shell with stdio pipes) and empirically — G7/G12/G15 executed real shell commands with zero PTY involvement. The `node-pty`/`bun-pty` stubs remain documented degradation for the interactive terminal feature only.
3. **Pinned commit verification** — **DONE**: server bundle built and verified at **`05ea5073`** (v1.18.23), not drifted `dev` HEAD.

## Honesty notes

- All 15 gates executed real components on the emulator; the model-dependent parts (G8-write, G10-MCP-agent, G11-stream-model, G12, G14-resume, G15) ran against the real OpenRouter model with the provided key (`model_available=1`). No mocks, no static-analysis passes, no hardcoding of expected outputs.
- The model key never appears in logs: pushed as a 600-perm file, read by the server from disk, scrubbed in `53-evidence.sh` as defense-in-depth.
- Known product gaps (documented degradation, unchanged by Phase 3): no Android build for `node-pty`/`bun-pty` (PTY-only terminal feature), watcher backend unsupported on Android (graceful fallback), `@opencode-ai/plugin` install warning in server log (harmless, background-only).
- Local sandbox dry-runs cannot be the source of truth for G1–G3 (host binaries instead of Android bionic); CI is authoritative and passed.

## Stop condition

The Phase 3 prompt required: *do not begin Phase 4 until G1–G15 all show a result.*
**Result: G1–G15 all PASS (0 FAIL, 0 BLOCKED) on the real emulator in CI.** Phase 4 may begin.

The gate suite remains fully automated: every push to `arena/01a049f8-opencode-app` (or `workflow_dispatch`) re-runs it and commits fresh evidence.
