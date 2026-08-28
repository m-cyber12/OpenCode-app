# PHASE 2 — MINIMUM RUNTIME SPIKE REPORT

**Phase:** 2 (minimum runtime spike — executed on a real Android emulator)
**Date:** 2026-08-28
**Branch:** `arena/01a044b6-opencode-app`
**Upstream:** `https://github.com/anomalyco/opencode` @ `dev`

---

## 0. Executive summary

**The candidate architecture from Phase 1 boots on a real Android emulator.**
The full chain executed on-device on an Android 14 (API 34) x86_64 Google
emulator:

```
Android native host (emulator, Android 14 / API 34 / x86_64, KVM-accelerated)
  → execution layer (bionic native ELF loading — no proot, no chroot)
    → minimal Linux userspace (Android system + bundled static/musl tools)
      → real shell (/system/bin/sh — MIRBSD KSH R59 "Android")
        → runtime (Bun 1.3.14 for Android, bionic x86_64 — @oven/bun-linux-x64-android)
          → OpenCode starts (real upstream OpenCode server bundle v1.18.23/25)
            → /global/health 200 {"healthy":true}
            → /session POST 200 (real session created)
            → /session GET 200, /config GET 200
            → child-process spawn from the server works (SPAWN_OK, exit 0)
            → SQLite storage + JSONC config written on-device
```

This is **TESTED evidence** (honesty protocol): every link above actually
executed on the emulator, with logs committed at
`docs/progress/phase2-evidence/` (evidence commit `d161ccd`).

---

## 1. Deliverable 1 — exact steps taken and exact commands/config used

### 1.1 Environment (the only feasible execution venue)

The project sandbox has a locked-down egress (only `github.com`, `registry.npmjs.org`,
`pypi.org` reachable; `dl.google.com`, `nodejs.org`, `sourceforge.net`, Debian etc. all
blocked) and no KVM. The spike therefore ran on a **GitHub Actions runner**
(`ubuntu-latest`), which has full network + KVM + a preinstalled Android SDK.
The workflow lives at `spike/workflow/phase2-spike.yml` (copied to
`.github/workflows/` by the user — the sandbox's GitHub App token lacks the
`workflows` permission, so only a user credential could add that file).

### 1.2 The chain and the exact commands

| Link | What ran | Evidence |
|------|----------|----------|
| Emulator boot | `emulator -avd spike -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -cores 2 -accel auto` | `emulator.log` (version 37.1.11, KVM) |
| Android facts | `adb shell getprop ro.build.version.{release,sdk}` / `ro.product.cpu.abi` | device log: `release=14 sdk=34 abi=x86_64`, `Linux ... 6.1.23-android14-... x86_64 Toybox` |
| Shell | `/system/bin/sh -c 'echo SHELL_OK ...'` | `SHELL_OK name=sh`; `@(#)MIRBSD KSH R59 2020/10/31 Android` |
| Runtime | `bun --version` + `bun -e 'console.log(process.platform, process.arch)'` | `1.3.14`; `bun platform=android arch=x64` |
| Bundled tools | `/data/local/tmp/spike/bin/rg --version` | `ripgrep 15.1.0 (rev af60c2de9d)` |
| OpenCode server | `setsid bun /data/local/tmp/spike/launch-server.js &` (env: `OPENCODE_SERVER_PASSWORD`, `XDG_*` to `/data/local/tmp/spike/*`) | `SERVER_READY http://127.0.0.1:4111/` |
| API checks | `bun /data/local/tmp/spike/health-check.js` (bun fetch on-device) | `HTTP 200 /global/health -> {"healthy":true,"version":"1.18.23-spike"}`; session created; `HEALTH_CHECK_PASS` |
| Spawn | `spawn("/system/bin/sh", ["-c", ...])` from the server's JS context | `SPAWN_OK shell=5225`, `SPAWN exit code: 0` |
| Storage | — | `opencode-spike.db` (+wal/shm) under `XDG_DATA_HOME/opencode/`; `opencode.jsonc` under `XDG_CONFIG_HOME/opencode/` |

### 1.3 Artifact build (runner-side, `spike/scripts/01-prepare-artifacts.sh`)

- `@oven/bun-linux-x64-android@1.3.14` tarball from `registry.npmjs.org`
  (sha256 `291f09c6…` in `bun.sha256`; ELF interp `/system/bin/linker64`).
- OpenCode bundle: `git clone --branch dev …/anomalyco/opencode`,
  `bun install`, then `Bun.build({ target: "bun", entrypoints: ["./src/node.ts"],
  format: "esm", external: ["jsonc-parser", "@lydell/node-pty", "bun-pty"], … })`.
- `ripgrep 15.1.0` x86_64-unknown-linux-musl from GitHub releases.
- Static git: build failed on the runner (best-effort step) — see §3.2.
- `jsonc-parser` 3.3.1 from `registry.npmjs.org` (external dep of the bundle).
- Stub modules for `@lydell/node-pty` and `bun-pty` (documented degradation, §3.3).

### 1.4 Device-side chain (`spike/scripts/device/device-chain.sh`, `launch-server.js`, `health-check.js`)

Runs as `adb root` on the emulator; sets `PATH=/data/local/tmp/spike/bin:/system/bin:…`,
`HOME/XDG_*/TMPDIR` under `/data/local/tmp/spike/`, launches the server with
`OPENCODE_SERVER_USERNAME=opencode`, `OPENCODE_SERVER_PASSWORD=spike-password`,
`OPENCODE_CLIENT=android-spike`, then performs the HTTP + spawn checks on-device.

---

## 2. Deliverable 2 — real logs/output from the emulator

Committed at `docs/progress/phase2-evidence/` (evidence commit `d161ccd`,
run 33160309729, all job steps `success`):

| File | Content |
|------|---------|
| `device-chain.device.log` | The on-device transcript (chain [1]→[9]), quoted below |
| `device-chain.log` | Runner-side driver log (pushes, pulls, timing) |
| `00-run-all.log` | Full orchestrator log (probe → SDK → AVD → boot → artifacts → chain → evidence) |
| `emulator.log` | Emulator boot log (version 37.1.11.0, KVM, boot completed) |
| `server.log` | `SERVER_READY http://127.0.0.1:4111/` (pulled back from device) |
| `bun.sha256` | sha256 of the Android bun binary |
| `UPSTREAM_COMMIT.txt` | `755ebdb94ee755a9d5691e47af2c16f56696996e` (dev HEAD at build time; see §3.4) |
| `inventory.txt` | Evidence bundle inventory |

Key excerpts (from `device-chain.device.log`):

```
=== [1] ANDROID NATIVE HOST / EXECUTION LAYER ===
uid=0(root) … context=u:r:su:s0
Linux localhost 6.1.23-android14-4-00257-g7e35917775b8-ab9964412 #1 SMP PREEMPT … x86_64 Toybox
release=14 sdk=34 abi=x86_64
sh=/system/bin/sh
INTERP  0x000200 … 0x00015 … [Requesting program interpreter: /system/bin/linker64]

=== [2] REAL SHELL ===
SHELL_OK name=sh
shell version: @(#)MIRBSD KSH R59 2020/10/31 Android

=== [3] RUNTIME (Bun 1.3.14 for Android, bionic x86_64) ===
1.3.14
bun platform=android arch=x64

=== [4] USERSAPCE TOOLS ===
ripgrep 15.1.0 (rev af60c2de9d)
git not bundled (build failed on runner)

=== [5] OPENCODE SERVER START ===
server pid=4909; waiting for boot...
SERVER_READY http://127.0.0.1:4111/

=== [6] OPENCODE HEALTH / SESSION / CONFIG (on-device, via bun fetch) ===
HTTP 200 /global/health -> {"healthy":true,"version":"1.18.23-spike"}
HTTP 200 /session -> {"id":"ses_fb83f1934ffedXuYljGQlPHQZt","slug":"quick-planet","projectID":"global",
  "directory":"/data/local/tmp/spike", …}
HTTP 200 /session -> [{"id":"ses_fb83f1934ffedXuYljGQlPHQZt", …}]
HTTP 200 /config -> {"$schema":"https://opencode.ai/config.json","command":{},"plugin":[],"username":"root",…}
SPAWN stdout: SPAWN_OK shell=5225
SPAWN stdout: /data/local/tmp/spike/project
SPAWN exit code: 0
HEALTH_CHECK_PASS

=== [7] STORAGE EVIDENCE (sqlite + config on Android) ===
… opencode-spike.db, opencode-spike.db-shm, opencode-spike.db-wal …   (XDG_DATA_HOME/opencode/)
… opencode.jsonc, .gitignore …                                        (XDG_CONFIG_HOME/opencode/)

DEVICE_CHAIN_DONE
```

---

## 3. Deliverable 3 — where it succeeded, where it broke, root causes

### 3.1 Succeeded (all TESTED on-device)

- Android native ELF execution of a bionic-linked runtime from a writable dir.
- Real shell, real runtime, real OpenCode server, real HTTP API, real SQLite
  persistence, real child-process spawn from the server, real ripgrep execution.

### 3.2 Broke / degraded (with root cause)

| Item | Status | Root cause |
|------|--------|------------|
| Static `git` | **BLOCKED (build-time)** | The runner-side static-git build failed (musl-tools unavailable / build issues). Not a runtime blocker for the spike: binary execution is already proven by bun + rg + spawn. Phase 3 must produce a working static git (musl toolchain or NDK). |
| `@lydell/node-pty`, `bun-pty` | **BLOCKED (documented degradation)** | No Android builds exist upstream. The server boots with stub modules; the PTY/terminal feature throws at use-time. Documented per Core Rule 6 — Phase 3 either builds these for Android or ships without a terminal. |
| `@parcel/watcher` | **NOT TESTED (graceful fallback)** | No Android binding; upstream's own fallback (watcher disabled) was exercised. |
| W^X probe (step [8]) | **NOT TESTED** | `su 2000 -c` isn't available on the emulator (`su: failed to exec -c`); the probe printed a false positive (`WX_PROBE_EXEC_ALLOWED` despite `su` failing). The Phase 1 W^X (API 29+) restriction is a *known Android behavior*; Phase 3 packaging will place executables in `nativeLibraryDir` (exec-allowed), and must verify on a real app sandbox. |

### 3.3 Process-model notes (from the run)

- The server bundle is the **bun-target** build of `packages/opencode/src/node.ts`
  (upstream `build-node.ts` is node-target; bun 1.3.14 lacks `node:sqlite`, so the
  `#db` bun condition → `db.bun.ts` → `bun:sqlite` is resolved instead). Same entry,
  same code, same defines — an adaptation, not a reimplementation (Core Rule 3).
- The device chain ran as `adb root` (system-image emulator). A production app
  runs in the app sandbox with W^X constraints — Phase 3's job, not this spike's.

### 3.4 Version pin drift (honesty note, Core Rule 7)

The first successful run built the bundle from `dev` **HEAD `755ebdb9` (v1.18.25)**,
not the pinned `05ea5073` (v1.18.23): the artifact script cloned `dev` without
checking out the pinned commit. The chain result is still real upstream code (one
minor release newer) and remains valid evidence, but it does not match the pin.
**Fixed** in commit `0019d2d` (script now fetches and checks out the exact pinned
commit and fails loudly if it cannot); `versions.lock` updated with the note.

---

## 4. Deliverable 4 — recommendation

**Proceed to Phase 3 gates with this architecture.** The Phase 1 candidate
(Node-or-Bun-on-Android) is empirically confirmed: real OpenCode runs on-device
under a real Android runtime with a real shell, real API, and real storage.
The Bun-for-Android runtime path (official npm package, bionic-linked, pinned
1.3.14) is the pragmatic choice over building Node for Android from source.

Phase 3 gate agenda (from this phase's findings):
- **G(exec):** app-sandbox W^X / `nativeLibraryDir` extraction (the W^X probe
  must be redone properly inside a real APK sandbox, not `adb root`).
- **G(tools):** static `git` for arm64 (musl or NDK) — the one tool that failed
  to build on the runner; plus static musl `rg` for arm64 (spike used x86_64).
- **G(runtime):** arm64-v8a bun (`@oven/bun-linux-aarch64-android`), same chain.
- **G(ptys):** PTY/terminal decision (node-pty/bun-pty Android builds or
  documented degradation).
- **G(model):** a real model round-trip (needs credentials; out of spike scope).

---

## 5. Honesty-protocol labeling

| Claim | Label |
|-------|-------|
| Emulator (Android 14, API 34, x86_64) boots on the GH runner with KVM | **TESTED** (`emulator.log`, run 33160309729) |
| Android native ELF execution (bionic interp `/system/bin/linker64`) | **TESTED** (readelf on-device + `bun` ran) |
| Real shell executes (`/system/bin/sh`, MIRBSD KSH R59) | **TESTED** (`SHELL_OK`, version string) |
| Bun 1.3.14 for Android runs on-device (`platform=android arch=x64`) | **TESTED** (`bun --version`, `bun -e`) |
| ripgrep 15.1.0 runs on-device | **TESTED** (`rg --version`) |
| Real OpenCode server bundle boots on-device | **TESTED** (`SERVER_READY` on the device) |
| `/global/health` 200 `{"healthy":true}` on-device | **TESTED** (HTTP 200 on-device) |
| Session create/list + config endpoints on-device | **TESTED** (HTTP 200, real `ses_…` id) |
| Child-process spawn from the server on-device (process model of the bash tool) | **TESTED** (`SPAWN_OK`, exit 0) |
| SQLite + JSONC storage written on-device | **TESTED** (db + config files on device) |
| Static git bundled and runnable | **BLOCKED** (build failed on runner; not runtime-tested) |
| PTY/terminal via node-pty/bun-pty on Android | **BLOCKED** (no Android builds; stubbed, documented degradation) |
| W^X exec-restriction behavior in a real app sandbox | **NOT TESTED** (probe invalid under `adb root`; deferred to Phase 3) |
| Bundle built at the pinned commit `05ea5073` | **IMPLEMENTED** (script fixed in `0019d2d`; re-run pending) |
| First run bundle provenance (dev HEAD `755ebdb9`, v1.18.25) | **TESTED** (`UPSTREAM_COMMIT.txt`; drift documented in `versions.lock`) |
| This report + spike package + evidence | **IMPLEMENTED** |

---

## 6. Stop condition

Met: the chain boots with real evidence (deliverables 1–4 above). The full
G1–G15 gate suite was **not** run — that is Phase 3.
