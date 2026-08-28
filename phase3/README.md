# Phase 3 — Runtime Gates (G1–G15)

Automated, repeatable emulator tests that empirically prove each required
runtime capability of the architecture: Android native host → execution layer →
minimal userspace → real shell → real Bun → **real OpenCode** (agent loop, API,
shell, files, Git, MCP, streaming, permissions) — all on a real Android
emulator in CI. No mocks, no fake components, no remote fallback.

## Gates

| Gate | What it proves | How (all real execution) |
|------|----------------|--------------------------|
| G1 | Android native host launches the execution layer | bionic ELF (`bun`) executed from the Android filesystem; `platform=android` |
| G2 | Execution layer boots the minimal userspace | env/XDG dirs, `/proc`, tmp write, bundled `rg` + `git`, `bun:sqlite` |
| G3 | Real shell executes commands | `/system/bin/sh` (mksh): variables, pipes, redirects, exit codes |
| G4 | Real Bun/runtime executes successfully | fs, `child_process` spawn, fetch availability, `bun:sqlite` |
| G5 | Real OpenCode starts locally | pinned server bundle boots → `SERVER_READY http://127.0.0.1:4111/` |
| G6 | OpenCode server health endpoint responds | `GET /global/health` → `200 {"healthy":true,...}` |
| G7 | OpenCode can execute a shell command | real `POST /session/:id/shell` (server spawns `/system/bin/sh` via `ChildProcess`) |
| G8 | OpenCode can read/write project files | real `/file`, `/file/content`, `/find` (ripgrep); write via server shell exec + (model mode) the agent's `write` tool |
| G9 | Real Git works (init/status/add/commit/diff/branches) | static musl git v2.48.1 executed on-device **and** through the OpenCode server |
| G10 | MCP stdio child process works | OpenCode's own MCP client ↔ real `@modelcontextprotocol/sdk` stdio server; SDK client round-trip; (model mode) agent calls the MCP tool |
| G11 | OpenCode streaming/SSE/event flow works | `/global/event` SSE: `session.next.shell.started/ended`; (model mode) `text.delta` + `step.ended` |
| G12 | Permissions/tool approval work | agent bash call → real `permission.v2.asked` → reply `once` → command runs |
| G13 | OpenCode process can be stopped and restarted | SIGTERM → port down → restart → health 200, same data dirs |
| G14 | App restart/reconnect to the recovered session | fresh client lists the pre-restart session, reads its history, (model mode) continues it |
| G15 | End-to-end: "Inspect this project and explain what it does" | real agent + real model (OpenRouter) + real shell/git/rg/file tools |

## How to run

1. **Install the workflow (once):** copy `phase3/workflow/phase3-gates.yml`
   to `.github/workflows/phase3-gates.yml` on branch
   `arena/01a049f8-opencode-app`. The session bot token cannot push workflow
   files, so this step needs a user with repo write access.
2. **Add model credentials (optional but needed for full G12/G15):** add a
   repo secret `OPENROUTER_API_KEY`. Without it, model-driven parts report
   BLOCKED (root cause: no model credentials) and everything else still runs.
3. Push anything to `arena/01a049f8-opencode-app` (or use
   `workflow_dispatch`). The suite runs headless on `ubuntu-latest` with the
   preinstalled Android SDK/emulator (KVM), builds the pinned artifacts,
   boots an Android 14 x86_64 emulator, runs all gates on-device, and commits
   evidence to `docs/progress/phase3-evidence/` (gate-NN logs +
   `GATES_SUMMARY.txt`).

## What runs where

- `phase3/scripts/00-run-gates.sh` — orchestrator (probe → SDK → AVD → boot →
  artifacts → gates → evidence)
- `phase3/scripts/01-prepare-artifacts.sh` — pinned artifact builder:
  Bun-for-Android 1.3.14 (x86_64 + arm64 inventory), OpenCode bundle at the
  pinned commit `05ea5073` (v1.18.23 — never drifts to `dev` HEAD), static musl
  ripgrep 15.1.0, **static musl git v2.48.1** (Phase 2 blocker fixed:
  `make NO_PERL=YesPlease` instead of `./configure`), MCP SDK 1.29.0 +
  stdio test server/client, jsonc-parser + PTY stubs
- `phase3/scripts/03-run-gates.sh` — host driver (adb root, pushes payloads,
  runs the on-device runner, pulls evidence)
- `phase3/scripts/device/gates-runner.sh` — on-device runner: G1–G15, per-gate
  logs, `GATES_SUMMARY.txt` (PASS/FAIL/BLOCKED per gate)
- `phase3/scripts/device/gate-*.js` — per-gate drivers (Bun on-device) against
  the real OpenCode HTTP API (`/session`, `/session/:id/shell`,
  `/session/:id/prompt_async`, `/global/event` SSE, `/permission`, `/mcp`,
  `/file`, `/find`)
- `phase3/versions.gates.lock` — Phase 3 pin list

## Carried items from Phase 2 — resolution status

- **Static git build** → fixed (see `01-prepare-artifacts.sh` §6): root cause
  was git's `configure` refusing to run without perl; building the Makefile
  directly with `NO_PERL=YesPlease` + static musl works. Proven on-device in G9.
- **PTY (node-pty/bun-pty)** → investigated: the bash tool
  (`packages/core/src/tool/bash.ts`) spawns the configured shell with stdio
  pipes and `detached: true` — **no PTY is required** for shell execution
  (G3/G7/G9/G15). PTY is only used by the lazy `Pty` service for the interactive
  terminal feature, which remains a documented degradation (stubs kept).
- **Version pin discipline** → the artifact builder checks out the exact pinned
  commit `05ea5073` and fails loudly otherwise; `UPSTREAM_COMMIT.txt` is
  recorded in every evidence bundle.
