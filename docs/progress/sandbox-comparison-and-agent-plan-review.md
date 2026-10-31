# Sandbox comparison (Claude Code vs OpenCode-Android) and review of `docs/agent.md`

Date: 2026-10-31. Branch `arena/01a0b9d5-opencode-app`.

This is an analysis, not a change to the product. Device behaviour is marked where it is
verified and where it is only code-verified or still unverified.

## Sources and coverage

Claude Code side:
- Claude Code docs, "Configure the sandboxed Bash tool" (`https://code.claude.com/docs/en/sandboxing`). Read: chunks 0–3 of 54 (overview, default rules, auto-allow and regular modes, unsandboxed retry). **Not yet read:** chunks 4–53 (credentials, managed settings, Windows, environment scrub). Claims that depend on those chunks are marked "unverified".
- The runtime that Claude Code builds on, `@anthropic-ai/sandbox-runtime` 0.0.81 (npm): README and `dist/sandbox/*`. Claude Code's own binary was not inspected.

OpenCode-Android side:
- `docs/SECURITY.md` (read in full), `docs/RUNTIME.md` (read in full), app code (`RuntimeEnv.kt`, `OpenCodeRepository.kt`, `OpenCodeApi.kt`), and the pinned upstream `packages/opencode/src/tool/shell.ts` (`05ea5073`).
- **Not yet read:** `docs/progress/phase4-seccomp-state.md`, the sandbox parts of `docs/ARCHITECTURE.md`, `docs/CAPABILITY-MATRIX.md`.

---

## Part 1 - Sandbox comparison

### 1.1 What each one is

| | Claude Code sandbox | OpenCode-Android |
|---|---|---|
| What it is | An OS-enforced boundary around shell commands the agent runs (the Bash tool) and the processes they start | No per-command boundary. The shell runs as the app's own Linux user (UID) with the app's full permissions |
| Enforcement | macOS: Seatbelt (`sandbox-exec`). Linux/WSL2: bubblewrap (namespaces) and socat bridges; seccomp is an optional component that blocks `AF_UNIX` socket creation. Native Windows: the runtime package has an alpha path (WFP filters, restricted account); Claude Code's docs say native Windows runs commands unsandboxed | Android's app sandbox: one UID per app, app-private files at mode 0700, no root. Nothing restricts what the shell does inside that UID |
| Configuration | Settings and the `/sandbox` command; managed settings can lock it; `failIfUnavailable` makes a missing sandbox an error | Nothing to configure. The shell cannot be switched off or narrowed |
| Filesystem | Writes: working directory, per-user temp directory and added directories only. Reads: almost the whole machine by default (docs table); deny and allow lists can change that | Writes: anything the app UID can write. Without All-files access: app-private storage and the app's own external folder. With All-files access (the default project root `Documents/OpenCode`): any folder on shared storage. Reads: app-private files, shared storage when granted, and anything else the UID can read |
| Protected paths | Write-denied even inside allowed paths: `.bashrc`, `.zshrc`, `.gitconfig`, `.git/hooks/`, `.git/config`, `.vscode/`, `.idea/`, `.claude/commands/`, `.claude/agents/`, `.mcp.json`, and others (runtime README, "Mandatory Deny Paths") | None. The shell can write any file in the project, including `opencode.json` and `.git/` |
| Network | No direct route out. Traffic goes through a host proxy (HTTP and SOCKS5) with an allowlist that starts empty. A new host prompts (regular mode) or is judged by a classifier (auto mode). On Linux the command runs in a network namespace with no route out and reaches the proxy through a socket bridge. The runtime also checks resolved addresses against deny rules | Unrestricted. The app holds `INTERNET`; nothing filters the shell's connections |
| Unix sockets | Blocked by the optional seccomp component on Linux; `allowUnixSockets` opens exceptions | Not restricted by the app |
| Credentials | `credentials.envVars` and `credentials.files` in `deny` or `mask` mode. Mask: the command sees a sentinel and the proxy substitutes the real value on TLS egress to listed hosts (runtime README) | None. The shell can read provider keys in `xdg/data/opencode/auth.json` (same UID; `SECURITY.md` says so) and the server password in its environment (see 1.4) |
| Environment variables | Inherited from Claude Code by default. The docs describe a scrub option (`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB`); **unverified**, in unread chunks | Inherited in full: upstream `shellEnv` spreads `process.env` into every command. This includes `OPENCODE_SERVER_PASSWORD` (see 1.4) |
| Permission model | Auto-allow mode: sandboxed commands run without a prompt. Prompts remain for commands that need to leave the sandbox and for `ask` rules. Regular mode: every Bash command prompts | Upstream ask / allow / deny. The app enforces `ask` as the default for `bash`, `edit`, `read`, `webfetch`, `external_directory` (`enforceAskDefaults`). The user can allow for the session. Approval is by command text, plus upstream's path heuristics for `external_directory` |
| Escape hatch | The model can retry a failed command outside the sandbox (`dangerouslyDisableSandbox`); approval depends on the mode. `allowUnsandboxedCommands: false` turns the retry off | None needed: nothing is sandboxed. Every command already runs with the app's full rights once approved |
| Failure handling | If the sandbox cannot start, commands run unsandboxed unless `failIfUnavailable` is set. Violations are reported to the model; the runtime keeps a violation log on macOS | No sandbox state exists, so nothing can fail open. No violation reporting for the shell; the only trace is the chat transcript |
| Parts that run outside the sandbox | Documented: file tools, MCP servers, hooks and the `!` shell mode | Everything runs in the app UID: shell, `edit`, MCP servers, the Git and ripgrep binaries |
| Platform dependencies | `bubblewrap`, `socat` on Linux | None extra. Depends on Android policy: `noexec` on shared storage (found on the phone, see 1.5) |
| Limits the docs or runtime README name | Domain fronting; exfiltration through allowed hosts such as `github.com`; `allowUnixSockets` can expose powerful services such as the Docker socket; `enableWeakerNetworkIsolation` and `allowAppleEvents` open exceptions; weaker nested mode for Docker | `SECURITY.md` "Known limits": the shell runs as the app UID with the app's network access |
| Evidence | Runtime repository tests and CI (not reviewed in this pass) | Device gates (P5 to P10), 507 JVM tests, payload hashes. No gate tests a boundary, because there is none |

### 1.2 Where OpenCode-Android is stronger

- **Separation from other apps is enforced by the kernel.** On a stock phone the shell cannot read another app's private data and has no root. Claude Code's Linux sandbox runs on the user's own account, where the default read rules allow reads of files such as `~/.ssh` unless a deny rule is set.
- **No silent fail-open.** The shell cannot be turned off, so a missing dependency cannot leave commands unsandboxed. Claude Code runs unsandboxed when its sandbox is missing, unless `failIfUnavailable` is set.
- **Simpler approval story.** Every command is judged at the prompt. There is no mode that quietly widens approvals.
- **The server is authenticated and loopback-only** (`LoopbackGuard`, `LoopbackAudit`, Basic auth with a per-install password, Keystore-backed secrets). Claude Code has no comparable local server to protect.

### 1.3 Where OpenCode-Android is weaker

Ranked by severity for the stated threat model (untrusted model output, prompt injection from repository files and tool output, a stock phone):

1. **Unrestricted egress.** A prompt-injected or misbehaving command can send any file it can read to any host. Claude Code blocks this by default (the allowlist starts empty) and prompts for new hosts.
2. **Secrets readable by the model.** `auth.json` (provider keys) and `OPENCODE_SERVER_PASSWORD` (see 1.4) can be read from the shell. Claude Code can mask credentials, which is verified in the runtime README. Whether it scrubs the environment by default is unverified (see 1.1).
3. **No write boundary inside the app.** With All-files access, the shell can write anywhere on shared storage. Claude Code limits writes to the project and temp directories.
4. **Approval is the only control.** Once the user chooses "allow for session" for `bash`, the shell is unbounded for the rest of the session. Claude Code keeps its boundary in auto-allow mode.
5. **No protected paths.** Nothing stops a command or an `edit` from changing configuration that alters behaviour. Claude Code protects `.mcp.json`, `.git/hooks`, `.vscode` and shell rc files. See 1.6 for why this matters here.
6. **No violation reporting.** A blocked action is invisible to the model and the user. Claude Code reports violations (the macOS log is verified; the other platforms are not checked).
7. **Misleading vocabulary.** The UI calls the project folder "the sandbox" (`EmptyChatCopy`: "The sandbox is a real folder on your phone"; the preview panel is the "sandbox console"). Users will reasonably assume an OS boundary exists. None does.

### 1.4 Finding: the server password is in the shell's environment (code-verified; device check pending)

- `RuntimeEnv.kt` sets `OPENCODE_SERVER_PASSWORD` for the server process.
- Upstream `shell.ts` (pinned `05ea5073`) runs every command with `{...process.env, ...extra.env}`.
- So the model can run `env` or `printenv` and read the password. Other apps on the device can reach `127.0.0.1:4111`; the password is what stops them. `SECURITY.md` says the server is protected "as far as the password stays private". While the shell can read it, that is not true.
- Claude Code also inherits its environment by default (docs). A scrub option is mentioned in its docs and is unverified here.

**Proposed fix (not implemented).** Remove the variable from what children inherit. Options, to check against the upstream code before choosing:
(a) the launcher reads the variable at start, then deletes it from its own environment after the server has read it (needs proof that upstream reads it only once);
(b) pass the password through a file that the server reads once and then deletes, or through stdin;
(c) use upstream's `shell.env` plugin hook to scrub, if the hook can remove keys (it is documented to add them).

### 1.5 Finding: shared storage is `noexec` (device-verified fix)

The phone blocks `node_modules/.bin` shims on device (exit 126), and shell scripts under the project folder cannot be run directly. The payload patch in this branch (`noexec-shell.ts`, route A) runs such files through their interpreter. Device-verified for `./t.sh` and `node_modules/.bin/hello` (screenshot, 2026-10-31). This is a functional fix, not a security control.

### 1.6 Finding: configuration can grant code execution (design risk; to verify on the device)

- An `edit` to `opencode.json` that adds a local `mcp` entry makes upstream start that command when the instance starts. The refresh fix (`refreshMcp` disposes the idle instance) makes that start happen when the user presses Refresh.
- The approval shown for the `edit` is for text in a JSON file. The real effect is starting a program.
- The same may apply to a project `permission` block, if upstream lets project config override the app's `ask` defaults. **Unverified.** Test: with `bash` allowed for the session, ask the model to write `"permission": {"bash": "allow"}` into the project `opencode.json`, then check whether the policy changes.
- Claude Code protects `.mcp.json` from writes for this reason.

**Recommended:** treat writes to `opencode.json`, `.mcp.json` and upstream config files as a separate approval that cannot be session-allowed, and show the MCP command in the approval text.

### 1.7 What would close the gaps on Android

Claude Code's approach relies on Linux namespaces, which an unrooted app does not have. The Android options are:

- **Egress:** a `VpnService` that routes the app UID's traffic through a local filter with an allowlist. This enforces the rule at the network layer. A proxy that depends on proxy environment variables is bypassed by any program that ignores them (my analysis; not a documented limit of the runtime). Costs: the user must accept the VPN consent, and it conflicts with the user's own VPN.
- **Process and file isolation:** an `android:isolatedProcess` service runs under its own UID with no access to app data; it could host the shell with the project folder passed in. This is a large change. The isolated process cannot use the app's normal storage grants, so the file path needs a design. Not tested.
- **Path rules:** a path filter in the same payload patch that already rewrites commands. A text filter cannot see what a program does (`python -c`, `eval`, nested `sh -c`), so it is a guard rail, not a boundary. Label it that way.
- **Unix sockets and syscalls:** the existing seccomp shim (for Bun's compatibility) could also block `AF_UNIX` for the shell, as Claude Code's optional seccomp component does. Its syscall coverage needs study first (`phase4-seccomp-state.md`, not yet read).

### 1.8 Summary

Claude Code's sandbox is a per-command OS boundary with a network allowlist, protected paths and credential masking. It runs on desktop Linux, WSL2 and macOS. OpenCode-Android has no per-command boundary. It has stronger separation between apps, but inside the app everything is open after one approval. The three gaps to close first: egress, readable secrets (including the server password), and configuration that can grant code execution.

---

## Part 2 - Review of `docs/agent.md`

`docs/agent.md` ("OpenCode — Product Strategy & Roadmap", 979 lines) states its core vision as "Every Change Has Evidence." I read it in full. Below: what it asks for, what exists in this repository, and where it conflicts with the product as built.

### 2.1 Structure

1. Positioning and principles: reliability over autonomy; verified results over generated code; local execution; human control.
2. Core architecture: A Task Orchestrator; B Execution Engine (Git worktrees, rollback); C Verification Engine (independent of the agent); D Evidence Ledger; E Project Intelligence; F Model Adapter Layer; G Policy Engine.
3. Priority features: P0 (Mission Control, Proof of Work with explicit states, Safe Git Workspaces, Intelligent Model Routing, Trust and Permission Layer); P1 (Project Brain, Change Impact Simulator, Adversarial Code Review, Self-Healing with retry limits, Visual QA, Intent-to-Tests, Background Engineering); advanced (Personal Engineering Benchmark, One-Click Delivery, Skills Marketplace).
4. MVP "Change Assurance" with a worked example.
5. A five-phase, twelve-month roadmap.
6. A reliability benchmark suite (standard, large-codebase, failure-condition tests).
7. Success metrics, risks, open-source principles, technology strategy.

### 2.2 Status of each requirement in this repository

Status key: **Yes** = present and tested on the phone or in CI; **Partial** = some of it exists; **No** = not present.

| Section | Requirement | Status | Evidence / note |
|---|---|---|---|
| A | Task orchestrator with an explicit state machine | No | No task objects or states in the app; upstream sessions only |
| A | Acceptance criteria before implementation | No | Only the prompt text |
| B | Isolated Git worktrees per task | No | `worktree` appears in 2 app files, in metadata only. The Android Git build is local-only (no HTTPS, no SSH) |
| B | Keep user changes, detect conflicts, roll back | Partial | Upstream revert and unrevert (`POST /session/:id/revert`) exist. No conflict detection |
| B | Terminal commands and project scripts | Yes | Bash tool through `/system/bin/sh` |
| B | Permissions and limits on execution | Partial | Approval prompts and session allow exist. No filesystem or network enforcement and no per-command time or resource limits (see Part 1) |
| C | Independent verification (tests, lint, type checks, review) | No | The agent runs its own tests through the shell. No separate runner or reviewer |
| C | An agent never declares its own work verified | No | Nothing enforces it |
| D | Evidence ledger per task (commit, files, commands, results, risks) | No | Phase evidence (`docs/progress/phase*-evidence/`) covers the product's release gates, not user tasks |
| E | Project intelligence (index, dependencies, conventions) | Partial | Environment brief and skill loading. No index, no symbol tracking, no incremental indexing |
| F | Model adapter layer, fallback, local models | Partial | Model picker and provider keys work. No fallback policy found in the app |
| G | Policy engine independent of the agent | Partial | Ask defaults are enforced in the app. Configuration can change them (see 1.6) |
| P0-1 | Mission Control | No | |
| P0-2 | Proof of Work with explicit states | No | No state exists |
| P0-3 | Safe Git workspaces | No | Same as B |
| P0-4 | Model routing by task | No | Manual choice only |
| P0-5 | Trust and permission layer | Partial | See Part 1 |
| P1-6 | Project Brain | Partial | As E |
| P1-7 | Change impact simulator | No | |
| P1-8 | Adversarial code review (independent reviewer) | No | |
| P1-9 | Self-healing with retry limits | No | Runtime restarts exist (`RestartBackoff`), but they are a different thing |
| P1-10 | Visual QA (run, screenshot, console errors, responsive) | Partial | Preview works. The responsive width check passed on the phone (F2). No console-error capture found in the app |
| P1-11 | Intent to tests | No | |
| P1-12 | Background engineering | No | Background turns exist (the foreground service keeps the server running); no background task types |
| 13 | Personal engineering benchmark | No | `PROMPTS.md` (27 phone tests) is a start, not a benchmark |
| 14 | One-click delivery (requirement to PR) | Partial | GitHub connector and commit and push work from the phone (G1). No delivery workflow |
| 15 | Skills marketplace | Partial | Project skills load (C1). No marketplace, versioning or permission declarations |
| MVP | Change Assurance (end to end) | No | |
| 8 | Reliability benchmark suite | No | |

In short, what exists is mostly what upstream OpenCode already had (sessions, tools, revert, MCP, skills, permission prompts). Most of what `agent.md` adds on top of upstream is not built.

### 2.3 Where the document and this product disagree

1. **Missing platform.** The document never mentions Android, mobile or desktop. It assumes Git worktrees, "existing sandboxing mechanisms supported by the host operating system" (line 978), locally installed tools and browser automation. On the phone, bubblewrap does not exist (Part 1), shared-storage folders are `noexec` (found on the phone), and language servers and browser automation are not provided by the app (not checked). A platform section is needed before the plan is credible on the phone.
2. **Permissions can be granted by the model.** The document says: "The AI model must never be able to grant itself additional permissions" (line 245), and "All security decisions must be enforced outside the AI model" (line 399). Today's config writes (1.6) break both. This is the most important gap, because the document makes it central.
3. **The custom-runtime rule.** The document says: "Do not introduce a new database, orchestration framework, or custom execution runtime unless a measurable limitation justifies it" (line 979). The app already has a custom payload, a launcher and a patch to upstream's shell tool. Those may be justified, but the document does not record the measured limit that justifies them.
4. **The success metric needs a baseline that does not exist.** "10% relative improvement over baseline" (line 927) needs recorded results on fixed tasks. The repository has none yet. The phone prompt set and `phone-results.json` are the nearest thing.
5. **The evidence rule is not met for user work.** The document says an agent "must never be allowed to declare its own work successfully verified without independent evidence" (line 137). The product records evidence only for release gates, not for user tasks, so this rule does not hold for user work today.
6. **Scope without a stop rule.** The roadmap "should be adjusted based on implementation complexity and measured results" (line 857), but it names no criterion for dropping an item. Together, the MVP and roadmap list more than twenty features.

### 2.4 Strengths of the document

- It names the right failure modes: lost user changes, false "verified" claims, unbounded repair loops, and prompt injection from repository content. These match the findings in Part 1.
- It requires explicit states instead of trusting chat context. That is the right fix for "the agent says it is done".
- It keeps the core local, free and self-hostable, with no mandatory cloud service. The phone product already meets this.
- Its MVP is small and specific: one workflow with checkable output. That is the right first target for this app.

### 2.5 Recommended order (for this product, on Android)

1. **Policy first** (Part 1): scrub the server password from the shell environment; make configuration writes (`opencode.json`, `.mcp.json`) a separate approval that cannot be session-allowed; decide the egress approach (VpnService, or no network for the shell by default); add protected paths to the approval rules.
2. **Task record:** a small local task object with the explicit states from P0-2 (for example Planned, In Progress, Implemented, Verification Pending, Verified, Failed, Blocked), stored in app-private files. This is the smallest change that makes "never show unverified work as done" true.
3. **Evidence ledger per task:** start commit, changed files, commands run (from the tool transcript), test results, and what was not run. Store it as JSON next to the project.
4. **App-run verification:** after the agent reports done, the app runs the project's own test command and sets the task state from the exit code. The agent cannot set `Verified`.
5. **Worktree isolation on the phone:** git 2.48 supports worktrees; this needs a device test. Put the task branch in a worktree so the user's files stay untouched until review.
6. **Platform section** in `agent.md`: what Android can and cannot do, and which P1 items are not possible there.
7. **Benchmark:** turn `PROMPTS.md` into a fixed task set with expected results, recorded per run in `phone-results.json`. This gives the success metrics their baseline.

Items 2 to 4 are the core of the MVP. The rest can wait.

---

## Open questions for the owner

1. Is the server-password exposure (1.4) a release blocker? Recommended: yes, because the document's central promise is that the agent cannot reach what it should not.
2. Should the shell have network access by default (as now), or start with none and ask per host? The second option needs a `VpnService` on Android.
3. Should configuration writes (`opencode.json`, `.mcp.json`) always prompt, even when the user has allowed `bash` for the session?
4. Is the MVP in `agent.md` (task states, evidence ledger, app-run verification) the next phase after the current phone pass, or does it wait for the store release?
