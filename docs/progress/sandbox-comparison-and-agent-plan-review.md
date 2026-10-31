# Sandbox comparison (Claude Code vs OpenCode-Android), fixes, and feature status

Date: 2026-10-31. Branch `arena/01a0b9d5-opencode-app`.

Part 1 compares the two sandboxes and records the two safety fixes made after the owner's answers
on 2026-10-31 (server password out of the child environment; config writes always prompt).
Part 2 is a feature-status checklist against `docs/agent.md`, which is the owner's wish list for
features, not a specification of this app.

Evidence labels: **verified** = run in this sandbox against the real upstream code, or in the
repo's gates; **code-verified** = read in the code, not run; **device pending** = needs the phone.

## Sources and coverage

Claude Code side:
- Claude Code docs, "Configure the sandboxed Bash tool" (`https://code.claude.com/docs/en/sandboxing`). Read: chunks 0–3 of 54. **Not yet read:** chunks 4–53 (credentials, managed settings, Windows, environment scrub). Claims that depend on those are marked unverified.
- The runtime that Claude Code builds on, `@anthropic-ai/sandbox-runtime` 0.0.81 (npm): README and `dist/sandbox/*`. Claude Code's own binary was not inspected.

OpenCode-Android side:
- `docs/SECURITY.md`, `docs/RUNTIME.md` (read in full); app code; upstream `packages/opencode` at `05ea5073` (`src/permission/index.ts`, `src/tool/shell.ts`, `src/server/auth.ts`, `src/flag/flag.ts`, `test/permission/`).
- **Not yet read:** `docs/progress/phase4-seccomp-state.md`, the sandbox parts of `docs/ARCHITECTURE.md`, `docs/CAPABILITY-MATRIX.md`.

---

## Part 1 - Sandbox comparison

### 1.1 What each one is

| | Claude Code sandbox | OpenCode-Android |
|---|---|---|
| What it is | An OS-enforced boundary around shell commands the agent runs (the Bash tool) and the processes they start | No per-command boundary. The shell runs as the app's own Linux user (UID) with the app's full permissions |
| Enforcement | macOS: Seatbelt (`sandbox-exec`). Linux/WSL2: bubblewrap and socat bridges, plus a seccomp component (the runtime README lists these; the seccomp rules were not inspected). Native Windows: the runtime package has an alpha path (WFP filters, restricted account); Claude Code's docs say native Windows runs commands unsandboxed | Android's app sandbox: one UID per app, app-private files at mode 0700, no root. Nothing restricts what the shell does inside that UID |
| Configuration | Settings and the `/sandbox` command; managed settings can lock it; `failIfUnavailable` makes a missing sandbox an error | Nothing to configure. The shell cannot be switched off or narrowed |
| Filesystem | Writes: working directory, per-user temp directory and added directories only. Reads: most of the machine by default (docs); deny and allow lists can change that | Writes: anything the app UID can write. Projects live in `Documents/OpenCode` (`ProjectStore`), which needs All-files access. With All-files access, any folder on shared storage is writable. Reads: app-private files, shared storage when granted, and anything else the UID can read |
| Protected paths | Write-denied even inside allowed paths: `.bashrc`, `.zshrc`, `.gitconfig`, `.git/hooks/`, `.git/config`, `.vscode/`, `.idea/`, `.claude/commands/`, `.claude/agents/`, `.mcp.json`, and others (runtime README: mandatory deny list) | Config guard (1.6): `opencode.json`, `.mcp.json` and `.opencode/` always prompt for writes. No other paths are protected |
| Network | No route out except a host proxy (HTTP and SOCKS5) whose allowlist starts empty (docs). A new host prompts in regular mode. The runtime README describes the proxy bridge on Linux, not checked here | Unrestricted. The app holds `INTERNET`; nothing filters the shell's connections. **Kept as is by owner decision (2026-10-31).** |
| Unix sockets | Restricted by default, with `allowUnixSockets` for exceptions (docs). The docs warn that an allowed socket such as `docker.sock` is powerful | Not restricted by the app |
| Credentials | `credentials.envVars` and `credentials.files` in `deny` or `mask` mode. Mask: the command sees a sentinel and the proxy substitutes the real value on TLS egress to listed hosts (runtime README) | The server password is no longer in the child environment (1.5). The shell can still read provider keys in `xdg/data/opencode/auth.json` (same UID, `SECURITY.md` says so). The GitHub token is in the child environment by design (1.7) |
| Environment variables | **Unverified** (docs chunks unread). A scrub option may exist; the name I recorded from memory, `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB`, is not confirmed | Inherited (`...process.env` in `shell.ts`), except the server password (1.5) |
| Permission model | Auto-allow and regular modes (docs). Details of each mode are in unread chunks | Upstream ask / allow / deny. The app sets `ask` for any of `bash`, `edit`, `read`, `webfetch`, `external_directory` that the config leaves unset (`enforceAskDefaults`). The user can allow for the session. The config guard (1.6) makes config writes the exception to that session allow |
| Escape hatch | The model can retry a failed command outside the sandbox (`dangerouslyDisableSandbox`); approval depends on the mode. `allowUnsandboxedCommands: false` turns the retry off | None needed: nothing is sandboxed. Every command already runs with the app's full rights once approved |
| Failure handling | `failIfUnavailable` controls what happens when the sandbox cannot run (docs; default not confirmed in what I read). The runtime README says violations are logged on macOS only | No sandbox state exists, so nothing can fail open. No violation reporting for the shell; the only trace is the chat transcript |
| Parts that run outside the sandbox | Documented: file tools, MCP servers, hooks and the `!` shell mode | Everything runs in the app UID: shell, `edit`, MCP servers, the Git and ripgrep binaries |
| Platform dependencies | `bubblewrap`, `socat` on Linux | None extra. Depends on Android policy: `noexec` on shared storage (found on the phone; route A fix, device-verified) |
| Limits the docs name | Domain fronting; exfiltration through allowed hosts such as `github.com`; `allowUnixSockets` can expose powerful services; `enableWeakerNetworkIsolation` and `allowAppleEvents` open exceptions; a weaker nested mode for Docker | `SECURITY.md` "Known limits": the shell runs as the app UID with the app's network access |
| Evidence | Runtime repository tests and CI (not reviewed in this pass) | Device gates (P5 to P10), JVM tests, payload hashes; the two fixes below have host tests and an end-to-end run against the real upstream server |

### 1.2 Where OpenCode-Android is stronger

- **Separation from other apps is enforced by the kernel.** On a stock phone the shell cannot read another app's private data and has no root. Claude Code's Linux sandbox runs on the user's own account, where the default read rules allow most of the home directory unless a deny rule is set (docs).
- **No dependency that can fail open.** The shell cannot be turned off, so no missing dependency can leave commands unsandboxed. In Claude Code, `failIfUnavailable` decides what happens when the sandbox is missing.
- **Simpler approval story.** Every command is judged at the prompt. There is no mode that quietly widens approvals.
- **The server is authenticated and loopback-only** (`LoopbackGuard` in the app, a `BIND_AUDIT` line in the launcher log, Basic auth with a password set at launch, secrets in the Keystore). Claude Code has no comparable local server to protect.

### 1.3 Where OpenCode-Android is weaker

Ranked by severity for the stated threat model (untrusted model output, prompt injection from repository files and tool output, a stock phone):

1. **Unrestricted egress.** A prompt-injected or misbehaving command can send any file it can read to any host. Claude Code blocks this by default (the allowlist starts empty) and prompts for new hosts. **Owner: keep network access for now.** Option kept for later: a `VpnService` filter (1.8).
2. **Secrets readable by the model.** `auth.json` (provider keys) can be read from the shell. The server password is fixed (1.5). The GitHub token is readable by design (1.7).
3. **No write boundary inside the app.** With All-files access, the shell can write anywhere on shared storage. Claude Code limits writes to the project and temp directories.
4. **Approval is the only control.** Once the user chooses "allow for session" for `bash`, the shell is unbounded for the rest of the session, except for the config files guarded in 1.6.
5. **Protected paths are few.** Only config and extension paths are guarded (1.6). Claude Code protects shell rc files, `.git/hooks` and more.
6. **No violation reporting.** A blocked action is invisible to the model and the user. Claude Code's runtime keeps a violation log, which its README describes as macOS-only (not run here).
7. **Misleading vocabulary.** The UI calls the project folder "the sandbox" (`EmptyChatCopy`: "The sandbox is a real folder on your phone"). Users will reasonably assume an OS boundary exists. None does. Not changed yet.

### 1.4 Finding: the server password was in the shell's environment (fixed in payload 9)

Before the fix:
- `RuntimeEnv` put `OPENCODE_SERVER_PASSWORD` in the server's environment.
- Upstream runs every shell command with `{...process.env, ...}` (`shell.ts`, line 424), so the model could read the password with `env`.
- Other apps on the device can reach `127.0.0.1:4111`; the password is what stops them.

Second cause, found while testing: the exec-time environment block is readable by same-UID processes through `/proc/<pid>/environ`. Deleting the key from `process.env` does not remove it from that block (tested under Bun: the key stayed in `/proc/self/environ`). So the password has to stay out of the exec environment altogether.

### 1.5 The fix (payload 9)

- **Delivery:** the app now writes the password to the launcher's stdin, one line, then closes the pipe (`RuntimeProcess.start`). The launcher reads that line (`readSecretLine`). The environment value is still accepted, for the gate scripts only (they set it themselves).
- **Children:** the launcher installs a view of `process.env` that hides the key from enumeration. Spreads, the `child_process` default env (named and default imports) and `Bun.$` then leave it out. Direct reads still return the value, so upstream still authenticates. `Bun.spawn`/`spawnSync` with no `env` option copy the native environment (host experiment), so the launcher wraps them to pass an explicit copy without the key. `cross-spawn` was checked in a scratch test only; it is not in the committed test.
- **Tests:**
  - `phase4/payload/launcher.test.mjs`: 4 cases, run under Bun 1.3.14 with the real launcher and a fake bundle. Password from stdin: the server reads it, and no child (spread, `child_process`, `Bun.spawn`, `Bun.spawnSync`, `Bun.$`) sees it. Environment mode: children still do not see it. No password: the launcher refuses to start. Control: without the launcher, the key is visible to children, so the probe is sensitive. **4 of 4 pass.**
  - End-to-end against the real upstream server (bundle built from `05ea5073` with the noexec and guard patches; Bun 1.3.14; launcher with stdin password; no seccomp shim in this test run; `OPENCODE_DISABLE_MODELS_FETCH=1`; a placeholder provider in `opencode.json` so the shell route accepts a request; the model is never called):
    - `GET /global/health`: 200 with the right password; 401 with none; 401 with a wrong one.
    - `POST /session/:id/shell` with `env`: `OPENCODE_SERVER_PASSWORD` appears 0 times. The server's `/proc/<pid>/environ` contains it 0 times.
    - Control (same server in environment mode): the server's `/proc/<pid>/environ` contains it 1 time, so the probe does detect a leak. Children still show 0.
    - **Device pending:** the Kotlin stdin handoff runs only on the phone (and in the CI emulator gates). Kotlin was not compiled in this sandbox (no JDK); CI compiles it.

### 1.6 Finding and fix: config writes could grant code execution (fixed in payload 9)

What upstream does (verified by reading `packages/opencode/src/permission/index.ts` at `05ea5073`):
- `evaluate()` takes the last matching rule (`findLast`). Session approvals (`approved`) are checked after the configured rules, so they win.
- The edit, write and patch tools send `always: ["*"]`. One session "allow for session" on any edit therefore allowed every later write, including `opencode.json` (which can add `permission` rules and local `mcp` servers that run commands) and `.mcp.json`.
- Shell approvals use a prefix pattern, such as `cat *`, so a write to `opencode.json` could ride on a prefix approval.
- **Worse than first thought:** the same ordering means a session "always" overrides a configured `deny` for the same permission. The guard fixes this only for the guarded files (see the last test case below).

The fix (`phase4/payload/patches/permission-guard.ts`, applied by `apply-permission-guard.py`, four exact-text anchors in `index.ts`):
- For requests with permission `edit`, `bash` or `external_directory` whose pattern names `opencode.json`, `opencode.jsonc`, `.mcp.json`, `.opencode` or `config/opencode`, session approvals are not consulted. The request is judged by configured rules only, which default to `ask`.
- A session "always" on such a request approves only that request.
- A pending guarded request is never unblocked by another request's approval.
- Configured rules still apply. A configured `allow` for `.opencode/plans/*.md` (plan mode) still applies, so plan mode does not prompt.

Tests (`phase4/payload/patches/permission-guard.test.ts`, 8 cases, in upstream's own permission harness):
- With the guard: the full upstream permission suite plus these 8, **114 pass, 0 fail** (Bun 1.3.14, checked-out upstream tree).
- Without the guard, 6 of the 7 cases that protect config files fail. The ordinary-edit case passes both ways, which is the intended control.
- The configured-`deny` case fails without the guard, which confirms the ordering problem above.

Limits (also in `permission-guard.ts`):
- The shell check is textual. A command that writes the file without naming it (for example by building the name in a variable, or through `python -c`) is not caught. The guard is a prompt on likely writes, not a boundary.
- Edits are checked by the path the tool was given. A symlink to a config file is not caught.
- A configured `deny` for ordinary paths can still be overridden by a session approval. Upstream behaviour, unchanged here.

### 1.7 Decision needed: the GitHub token is in the shell's environment (by design)

`RuntimeEnv` puts the GitHub connector token in the server environment as `key`, so git push works from the shell. The token is readable by the shell, and with unrestricted network access it could leave the device. This was a deliberate design choice and is not changed here. Options: keep it; or give git the token through a credential helper that never puts it in the environment (the model could still run git, but could not print the token).

### 1.8 What would close the remaining gaps on Android

Claude Code's approach relies on Linux namespaces, which an unrooted app does not have. The Android options are:

- **Egress (kept off for now, per owner):** a `VpnService` that routes the app UID's traffic through a local filter with an allowlist. This enforces the rule at the network layer. A proxy that depends on proxy environment variables is bypassed by any program that ignores them (my analysis; not a documented limit of the runtime). Costs: the user must accept the VPN consent, and it conflicts with the user's own VPN.
- **Process and file isolation:** an `android:isolatedProcess` service runs under its own UID with no access to app data; it could host the shell with the project folder passed in. Large change; not tested. (Android feature, not checked against this app's manifest.)
- **Path rules:** a path filter in the same payload patch that already rewrites commands. A text filter cannot see what a program does, so it is a guard rail, not a boundary.
- **Unix sockets and syscalls:** the existing seccomp shim could also restrict sockets for the shell. Its coverage needs study first (`phase4-seccomp-state.md`, not yet read).

### 1.9 Summary

Claude Code's sandbox is a per-command OS boundary with a network allowlist, protected paths and credential masking (as documented; the parts I did not read are noted above). OpenCode-Android has no per-command boundary. It has stronger separation between apps, but inside the app everything is open after one approval. This pass fixed two gaps: the server password is out of the shell's reach, and config writes always prompt. Network access stays open by owner decision. The GitHub token question (1.7) and the device checks are open.

---

## Part 2 - Feature status against `docs/agent.md`

`docs/agent.md` is the owner's list of features the app should have. It is not a specification of this codebase, so the table only records what exists today.

Status key: **Yes** = present and tested on the phone or in CI; **Partial** = some of it exists; **No** = not present.

| Section | Feature | Status | Note |
|---|---|---|---|
| A | Task orchestrator with explicit states | No | Upstream sessions only |
| A | Acceptance criteria before implementation | No | Prompt text only |
| B | Isolated Git worktrees per task | No | |
| B | Keep user changes, detect conflicts, roll back | Partial | Upstream has a revert feature (seen in source; not tested on the phone). No conflict detection |
| B | Terminal commands and project scripts | Yes | Bash tool through `/system/bin/sh` |
| B | Permissions and limits on execution | Partial | Approval prompts, session allow, and the config guard (1.6). No filesystem or network enforcement; no per-command time or resource limits |
| C | Independent verification (tests, lint, type checks, review) | No | The agent runs its own tests through the shell |
| C | Agent cannot declare its own work verified | No | |
| D | Evidence ledger per task | No | The `phase*-evidence` folders cover the product's release gates, not user tasks |
| E | Project intelligence (index, dependencies, conventions) | Partial | Environment brief and skill loading. No index |
| F | Model adapter layer, fallback, local models | Partial | Model picker and provider keys. Fallback not checked |
| G | Policy engine independent of the agent | Partial | Ask defaults enforced in the app; config guard for config writes (1.6) |
| P0-1 | Mission Control | No | |
| P0-2 | Proof of Work with explicit states | No | |
| P0-3 | Safe Git workspaces | No | Same as B |
| P0-4 | Model routing by task | No | Manual choice only |
| P0-5 | Trust and permission layer | Partial | See Part 1 |
| P1-6 | Project Brain | Partial | As E |
| P1-7 | Change impact simulator | No | |
| P1-8 | Adversarial code review | No | |
| P1-9 | Self-healing with retry limits | No | Runtime restarts with backoff exist (`RestartBackoff`); that is a different thing |
| P1-10 | Visual QA | Partial | Preview works; responsive width check passed on the phone (F2). Console capture not checked |
| P1-11 | Intent to tests | No | |
| P1-12 | Background engineering | Partial | `RuntimeService` (foreground service) keeps the server running; no background engineering tasks |
| 13 | Personal engineering benchmark | No | `PROMPTS.md` (27 phone tests) is a start |
| 14 | One-click delivery (requirement to PR) | Partial | GitHub connector; commit and push from the phone (G1) |
| 15 | Skills marketplace | Partial | Project skills load (C1). No marketplace |

Most of what exists is upstream OpenCode's own feature set (sessions, tools, revert, MCP, skills, permission prompts). Most of what `agent.md` adds on top is not built. That is expected for a wish list.

---

## Open items

1. **Device check for payload 9:** the password handoff over stdin, the guarded permission prompts on the phone, and re-extraction on upgrade. CI emulator gates cover the handoff; the owner's phone run covers the rest.
2. **GitHub token in the shell environment** (1.7): keep as designed, or move to a credential helper.
3. **Textual shell guard** (1.6): accept as a prompt, or add an OS-level control later.
4. **Read the remaining Claude Code docs** (chunks 4–53) before claiming anything about credential scrubbing or managed policy.
5. **Naming:** "sandbox" in the UI (1.3 item 7). Not changed.
