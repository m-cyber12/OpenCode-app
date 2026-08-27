# PHASE 1 CORRECTION — STALE UPSTREAM REFERENCE

**Phase:** 1 correction (verification only — no product code written)
**Date:** 2026-08-27
**Timestamp:** 2026-08-27T19:13Z
**Branch:** `arena/01a044a0-opencode-app`
**Corrects:** `docs/progress/phase1-runtime-investigation-report.md` + `versions.lock`

Scope: confirm the canonical upstream repo/org/branch, re-verify the pinned
commit and the five cited file paths, update `versions.lock`, and append a
correction note to the Phase 1 report. Nothing else.

---

## 1. Canonical repo / org / branch — what it actually is now

**The canonical repo is `github.com/anomalyco/opencode`, default branch `dev`.**

Evidence (all fetched live 2026-08-27):

- `curl -sI https://github.com/sst/opencode` → **`301 Moved Permanently`**, with
  `Location: https://github.com/anomalyco/opencode`. `sst/opencode` is a stale
  redirecting name, not a live canonical repo.
- `gh api repos/sst/opencode` and `gh api repos/anomalyco/opencode` both return
  **repository id `975734319`** — the *same* repository object. This is a
  **rename** (GitHub moves the repo and leaves a redirect), **not a fork**. A
  fork would have a different id and its own `fork: true` flag; here
  `"fork": false`, `"archived": false`.
- `gh api repos/anomalyco/opencode` → `full_name: "anomalyco/opencode"`,
  `default_branch: "dev"`, `description: "The open source coding agent."`.
- `git ls-remote https://github.com/anomalyco/opencode refs/heads/dev refs/heads/main refs/heads/master`
  returns **only `refs/heads/dev`** (`05ea5073…`). There is no `main` or `master`
  branch. A fresh `git clone --branch dev` reports `HEAD branch: dev`.

**Correction vs Phase 1:** Phase 1 wrote "HEAD of `main`". Under
`anomalyco/opencode` the default branch is **`dev`**. The commit SHA is
unchanged (still HEAD of `dev`), so only the branch name note was wrong.

| Question | Result | Label |
|----------|--------|-------|
| Does `sst/opencode` redirect to `anomalyco/opencode`? | Yes, HTTP 301 | **TESTED** |
| Is `anomalyco/opencode` a fork or a rename? | Rename (same repo id 975734319, `fork: false`) | **TESTED** |
| Current default branch | `dev` (no `main`/`master`) | **TESTED** |

## 2. Pinned commit re-verification

**The pinned commit is valid and unchanged — no re-pin needed.**

- `gh api repos/anomalyco/opencode/commits/05ea5073be967c779d326929b2de6228dda4159d`
  returns the commit: `"fix(console): improve Go comparison chart on mobile (#45044)"`,
  authored `2026-08-27T17:21:00Z`.
- `git ls-remote https://github.com/anomalyco/opencode refs/heads/dev` →
  `05ea5073be967c779d326929b2de6228dda4159d` — the pinned commit **is** the
  current HEAD of the default branch `dev`.
- `packages/opencode/package.json` @ that commit → `"version": "1.18.23"`.
- `packages/core/package.json` @ that commit → `"version": "1.18.23"`.

The org move did **not** rewrite history: the SHA resolves under the new org
and still corresponds to version `1.18.23`. `versions.lock` keeps the same
commit; only the upstream URL (and the branch-name note) changed.

| Question | Result | Label |
|----------|--------|-------|
| Commit exists under `anomalyco/opencode`? | Yes | **TESTED** |
| Commit still HEAD of the default branch? | Yes (`dev`) | **TESTED** |
| Commit still corresponds to version 1.18.23? | Yes (both package.json) | **TESTED** |

## 3. Cited file paths — re-verified status

All five minimum-required paths were checked in a fresh shallow clone of
`anomalyco/opencode` @ `dev` (HEAD = `05ea5073…`). All exist and match the
Phase 1 descriptions.

| Path | Phase 1 claim | Status @ `05ea5073` | Label |
|------|---------------|---------------------|-------|
| `packages/opencode/script/build.ts` | Bun CLI compile | Exists. `Bun.build({ … compile: { … }, outfile: "dist/<name>/bin/opencode" })`, target built from `name.replace(pkg.name, "bun")`. Matches. | **TESTED** |
| `packages/opencode/script/build-node.ts` | Node server bundle | Exists. `target: "node"`, `entrypoints: ["./src/node.ts"]`, `outdir: "./dist/node"`, `format: "esm"`, `external: ["jsonc-parser", "@lydell/node-pty"]`. Matches. | **TESTED** |
| `packages/opencode/src/node.ts` | Node build entrypoint | Exists. Exports `Config` (from `@/config/config`), `Server` (from `./server/server`), `bootstrap` (from `./cli/bootstrap`), `Database` (from `@opencode-ai/core/database/database`). Matches. | **TESTED** |
| `packages/desktop/src/main/server.ts` | Sidecar spawn / health check | Exists. `utilityProcess.fork(join(…, "sidecar.js"))`, `SIDECAR_START_STALL_TIMEOUT`, health check against `/api/health` and `/global/health`, `Sidecar exited before health check passed`. Matches. | **TESTED** |
| `packages/core/src/database/sqlite.node.ts` | `node:sqlite` usage | Exists. `import { DatabaseSync, type SQLInputValue } from "node:sqlite"`, `new DatabaseSync(config.filename, …)`, `drizzle-orm/node-sqlite`. Matches. | **TESTED** |

Secondary confirmations supporting the headline "Node server path" finding
(no change):

- `packages/desktop/electron.vite.config.ts` resolves `virtual:opencode-server`
  to `${OPENCODE_SERVER_DIST}/node.js` (the Node build output).
- `packages/desktop/scripts/prebuild.ts` runs `cd ../opencode && bun script/build-node.ts`.
- Root `package.json` `packageManager: bun@1.3.14`; catalog `@types/bun: 1.3.13`,
  `@types/node: 24.12.2` — all as pinned in `versions.lock`.
- `packages/core/src/ripgrep/binary.ts`: `VERSION = "15.1.0"`,
  `arm64-linux → aarch64-unknown-linux-gnu` — as Phase 1 reported.
- `packages/desktop/package.json`: `electron 42.3.3`, node-pty `1.2.0-beta.12`,
  `@parcel/watcher 2.5.1` — as pinned.
- `packages/core/package.json` imports `#sqlite` / `#pty` / `#fff` map to
  `*.bun.ts` / `*.node.ts`; `packages/opencode/package.json` import `#db` maps to
  `./src/storage/db.bun.ts` / `./src/storage/db.node.ts` — as Phase 1 described.

**No Phase 1 conclusion changed.** In particular, the Node-server-path finding
(the desktop app runs the real OpenCode server under plain Node/V8 via a
sidecar) still holds under `anomalyco/opencode` @ `dev`. This is a URL/org fix,
not an architecture-relevant change, so no stop-and-surface is required.

## 4. `versions.lock` — updated

- `opencode.upstream` → `https://github.com/anomalyco/opencode` (was `…/sst/opencode`).
- `opencode.commit` → **unchanged** (`05ea5073be967c779d326929b2de6228dda4159d`),
  re-annotated `# HEAD of `dev` (default branch)`.
- Header now carries a dated note explaining the SST → Anomaly rename, the 301
  redirect, the shared repo id (`975734319`), and the `dev` default branch.

| Item | Result | Label |
|------|--------|-------|
| `versions.lock` edited | Yes | **IMPLEMENTED** |

## 5. Phase 1 report — correction note appended

A `## 8. Correction (appended 2026-08-27) — stale upstream org reference`
section was appended to `docs/progress/phase1-runtime-investigation-report.md`.
The original body was **not** rewritten (honesty protocol: past reports are a
record of what was true at the time). The note records the org rename, the
`dev` default branch, the unchanged commit pin, the re-verified file paths, and
the `versions.lock` update.

| Item | Result | Label |
|------|--------|-------|
| Correction note appended to Phase 1 report | Yes | **IMPLEMENTED** |

---

## 6. Honesty-protocol labeling (summary)

| Claim | Label |
|-------|-------|
| `sst/opencode` → `anomalyco/opencode` is a rename (same repo id) with a 301 redirect | **TESTED** (live `curl` + GitHub API) |
| Default branch is `dev` (no `main`/`master`) | **TESTED** (API + `git ls-remote` + clone) |
| Pinned commit `05ea5073…` exists and is HEAD of `dev` @ version 1.18.23 | **TESTED** (API + clone + manifest reads) |
| Five cited file paths exist and match Phase 1 descriptions | **TESTED** (source inspection of the clone) |
| Secondary pins (Bun 1.3.14, Electron 42.3.3, node-pty 1.2.0-beta.12, @parcel/watcher 2.5.1, ripgrep 15.1.0, @types/node 24.12.2, @types/bun 1.3.13) | **TESTED** (source inspection of the clone) |
| `versions.lock` updated | **IMPLEMENTED** |
| Correction note appended to Phase 1 report | **IMPLEMENTED** |
| Anything executed on an Android emulator/device | **NOT TESTED** (out of scope for this correction; deferred to Phase 2) |

## 7. Stop condition

Correction complete: `docs/progress/phase1-correction-report.md` is written,
`versions.lock` is updated, and the Phase 1 report carries an appended
correction note. Phase 2 (the runtime spike, `03-phase2-minimum-spike.md`) is
**not** started in this run, per the task's stop condition.
