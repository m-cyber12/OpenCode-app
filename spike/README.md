# PHASE 2 SPIKE — minimum runtime spike package

Everything needed to run the Phase 2 spike on a real Android emulator.

## What this proves

```
Android native host (emulator, Android x86_64 — API 31+ from the runner's preinstalled SDK)
  → execution layer (Android native ELF loader / bionic — no proot, per Phase 1)
    → minimal Linux userspace (Android system + bundled static/bionic tools)
      → real shell (/system/bin/sh, mksh)
        → runtime (Bun 1.3.14 for Android, bionic x86_64, from @oven/bun-linux-x64-android)
          → OpenCode starts (real upstream OpenCode server bundle, commit 05ea5073, v1.18.23)
```

## How to run (one-time user step)

The GitHub App token used by the sandbox has no `workflows` permission, so
only a credential with that permission can add the workflow file. Push it
from a machine that has one (a PAT with **Workflows: read & write** + **Contents: read & write**,
or any credential with workflow access):

```bash
git clone https://github.com/m-cyber12/OpenCode-app.git
cd OpenCode-app
git checkout arena/01a044b6-opencode-app
mkdir -p .github/workflows
cp spike/workflow/phase2-spike.yml .github/workflows/phase2-spike.yml
git add .github/workflows/phase2-spike.yml
git commit -m "phase2: enable spike workflow (v2)"
git push origin arena/01a044b6-opencode-app
```

The push triggers the workflow (branch filter `arena/01a044b6-opencode-app`).
It can also be re-run from the Actions UI (**Run workflow** → workflow_dispatch).

**Important:** the workflow re-runs on every push to this branch that touches
non-evidence files, so if a future spike fix is needed, just push the fix and
the workflow runs again automatically. The workflow's evidence commits do NOT
re-trigger it (`paths-ignore`).

## Layout

| Path | Purpose |
|------|---------|
| `workflow/phase2-spike.yml` | The GitHub Actions workflow (copy to `.github/workflows/`) |
| `scripts/00-run-all.sh` | Single orchestrator: probe → SDK → AVD → boot → artifacts → device chain → evidence |
| `scripts/01-prepare-artifacts.sh` | Runner-side: build OpenCode bundle, fetch bun-android, ripgrep, static git |
| `scripts/02-device-chain.sh` | Runner-side: adb driver that runs the chain on the emulator and captures logs |
| `scripts/50-install-sdk.sh` | Fallback: fresh SDK install (only if the preinstalled SDK is missing) |
| `scripts/51-manual-avd.sh` | avdmanager-free AVD creation fallback (writes config.ini directly) |
| `scripts/52-boot-emulator.sh` | Headless emulator boot + boot-completed wait + diagnostics |
| `scripts/53-evidence.sh` | Assemble evidence bundle (logs + provenance) |
| `scripts/device/device-chain.sh` | Device-side: the actual chain (shell → runtime → OpenCode server → health/session checks) |
| `scripts/device/launch-server.js` | OpenCode server launcher (mirrors `packages/desktop/src/main/sidecar.ts`) |
| `scripts/device/health-check.js` | On-device HTTP checks via bun fetch |
| `versions.spike.lock` | Pins for every component of the spike environment |

## Why v2 (what failed in v1, 2026-08-27 run 33113504275)

v1 installed the SDK from scratch inside the workflow (`sdkmanager` download).
On the runner that step silently did nothing (sdkmanager not on PATH; the
`| tail -3` pipe masked the `command not found` and exited 0), so "Create AVD"
failed instantly with no system image. v2 uses the **Android SDK preinstalled
on GitHub-hosted ubuntu runners** (`/usr/local/lib/android/sdk` — emulator,
platform-tools, cmdline-tools, system images, licenses), with the fresh-install
path demoted to a fallback, and every step now logs into `spike/out/` which is
committed as evidence even on failure.

## Notes

- The OpenCode bundle is the **bun-target** build of `packages/opencode/src/node.ts`
  (upstream's build-node.ts is node-target; bun 1.3.14 lacks `node:sqlite`, so the
  bundle is built with `target: "bun"` to resolve upstream's own `#db` bun condition
  → `db.bun.ts` → `bun:sqlite`). Same entry, same code, same defines; only the
  runtime-condition resolution differs. This is an adaptation, not a
  reimplementation (Core Rule 3).
- `@lydell/node-pty` and `bun-pty` have no Android builds; the server boots with
  stub modules and the terminal/PTY feature degrades at use-time (documented
  degradation, Core Rule 6). All other features (agent loop, sessions, SQLite,
  HTTP/WebSocket API, auth, config) come from real OpenCode code.
- The spike runs as `adb root` (normal for a system-image emulator). The
  Phase 1 W^X (API 29+) app-sandbox restriction is probed separately on-device;
  Phase 3 packaging will extract executables to `nativeLibraryDir` (exec-allowed).
