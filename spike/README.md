# PHASE 2 SPIKE — minimum runtime spike package

Everything needed to run the Phase 2 spike on a real Android emulator.

## What this proves

```
Android native host (emulator, Android 14 x86_64)
  → execution layer (Android native ELF loader / bionic — no proot, per Phase 1)
    → minimal Linux userspace (Android system + bundled static/bionic tools)
      → real shell (/system/bin/sh, mksh)
        → runtime (Bun 1.3.14 for Android, bionic x86_64, from @oven/bun-linux-x64-android)
          → OpenCode starts (real upstream OpenCode server bundle, commit 05ea5073, v1.18.23)
```

## How to run (one-time user step)

The GitHub App token used by this sandbox has no `workflows` permission, so
**only a credential with that permission can add the workflow file**. Push it
from a machine that has one (e.g. the fine-grained PAT you provided):

```bash
git clone https://github.com/m-cyber12/OpenCode-app.git
cd OpenCode-app
git checkout arena/01a044b6-opencode-app
mkdir -p .github/workflows
cp spike/workflow/phase2-spike.yml .github/workflows/phase2-spike.yml
git add .github/workflows/phase2-spike.yml
git commit -m "phase2: enable spike workflow"
git push origin arena/01a044b6-opencode-app
```

The push itself triggers the workflow (branch filter
`arena/01a044b6-opencode-app`). Track it at
https://github.com/m-cyber12/OpenCode-app/actions — the run installs the
Android SDK, boots an Android 14 x86_64 emulator with KVM, runs the chain,
and commits its logs to
`docs/progress/phase2-evidence/` on the same branch.

`workflow_dispatch:` is also enabled, so it can be re-run from the Actions UI
without another push.

## Layout

| Path | Purpose |
|------|---------|
| `workflow/phase2-spike.yml` | The GitHub Actions workflow (copy to `.github/workflows/`) |
| `scripts/01-prepare-artifacts.sh` | Runner-side: build OpenCode bundle, fetch bun-android, build static git, fetch musl ripgrep |
| `scripts/02-device-chain.sh` | Runner-side: adb driver that runs the whole chain on the emulator and captures logs |
| `scripts/device/device-chain.sh` | Device-side: the actual chain (shell → runtime → OpenCode server → health/session checks) |
| `scripts/device/launch-server.js` | OpenCode server launcher (mirrors `packages/desktop/src/main/sidecar.ts`) |
| `scripts/device/health-check.js` | On-device HTTP checks via bun fetch |
| `versions.spike.lock` | Pins for every component of the spike environment |

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
