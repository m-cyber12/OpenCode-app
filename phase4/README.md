# Phase 4 — Embedded Runtime Host

Turns the Phase 2/3 proven spike into a **production Android app that owns the
runtime**. No Termux, no user installs, no remote server (Core Rule 2): the
real OpenCode server runs on-device from one APK.

## What's here

```
app/                                  Native Android app (Kotlin + Compose)
  src/main/java/ai/opencode/android/
    MainActivity.kt                   Status/diagnostics UI (chat UX is Phase 5)
    runtime/
      RuntimeVersion.kt               Pins (must match versions.lock)
      AbiGate.kt                      arm64-v8a/x86_64 gate, clear unsupported message
      RuntimePaths.kt                 app-private filesystem layout
      RuntimeManifest.kt              Parses the payload manifest (versions+sha256)
      PayloadExtractor.kt             First-run extract + sha256 validate + corruption recovery
      RuntimeEnv.kt                   HOME/XDG/PATH/SHELL/auth environment
      RuntimeProcess.kt               Launch/health-stop/SIGKILL/proc-sweep (no zombies/dupes)
      HealthChecker.kt                Polls /global/health (health, not "launched")
      RuntimeManager.kt               Supervisor: gate->extract->start->health->crash/backoff
      RuntimeService.kt               Foreground service (specialUse) that owns the supervisor
      RuntimeState.kt                 Observable lifecycle states (StateFlow)
      Diagnostics.kt                  Logs, crashes, versions, ABI, API level bundle
      Secrets.kt                      Random per-install server password (600), optional model key
      DebugControlReceiver.kt         adb-only (debug builds) stop/reset hooks for CI
  src/test/java/.../                  JVM unit tests (ABI, manifest, tar/extract, corruption)

phase4/
  payload/launcher.js                 bun entrypoint shipped in the payload (imports the
                                      real upstream Server.listen — adaptation glue only)
  scripts/
    00-run-phase4.sh                  Orchestrator: payload -> APK -> emulator install -> gates
    10-build-payload.sh               Build engine/ (jniLibs per ABI + assets payload+manifest)
    11-build-mcp.sh                   Real @modelcontextprotocol/sdk stdio server for G10
    20-device-gates.sh                H1..H8 host gates + G1..G12/G14/G15 over the app server
    02-boot-emulator.sh / 50-install-sdk.sh
    device/gate-*.js + gates-lib.js   Phase 3 gate drivers (real OpenCode HTTP API), parameterized
  workflow/phase4-runtime-host.yml    CI; COPY to .github/workflows/ (bot can't push there)
```

## How to build

On a networked Linux host (CI does this automatically):

```bash
bash phase4/scripts/10-build-payload.sh           # -> phase4/out/engine/
bash phase4/scripts/11-build-mcp.sh               # dev/test MCP server
./gradlew :app:assembleDebug                      # debug APK with payload embedded
./gradlew :app:testDebugUnitTest                  # JVM unit tests
```

The Gradle `verifyRuntimePayload` task fails fast if `phase4/out/engine/` is
missing, with instructions to run `10-build-payload.sh`.

## Runtime lifecycle (what the gates verify)

1. **ABI/device gate** — unsupported (32-bit/API<29) devices get a clear
   `UNSUPPORTED_DEVICE` state, never an opaque exec-format crash.
2. **Extraction** — `assets/runtime-payload.tar.gz` unpacks to a staging dir,
   every file's sha256/size is checked against `runtime-manifest.json`, then it
   atomically swaps into `filesDir/runtime/` and writes a versioned marker.
3. **Executables** — bun/git/rg ship as `lib*.so` (extracted to the
   exec-allowed `nativeLibraryDir` by Android); `filesDir/bin/{bun,git,rg}` are
   symlinks to them, so the agent's `which` lookups find the real tools while
   respecting W^X.
4. **Start** — the foreground service starts the supervisor, which launches
   bun from `nativeLibraryDir` and waits for a healthy `/global/health` (Basic
   auth, random per-install password) — not just a live process.
5. **Crash/restart** — unexpected exit is recorded to `filesDir/log/crashes/`
   and restarted with bounded exponential backoff (1s→30s, ≤8 attempts).
6. **Corruption** — on each start the manifest is re-verified; a mismatch wipes
   and re-extracts from the APK (the `DEBUG_RESET` hook and live-corruption
   paths are both gated).
7. **Stop** — SIGTERM (graceful server close) → SIGKILL → `/proc` sweep, so no
   zombies/duplicates remain; a duplicate start is a no-op (single service +
   pidfile + sweep + one port bind).
8. **Diagnostics** — host lifecycle log, OpenCode server log tail, crash
   reports, pinned/running versions, ABI and API level are collected and
   shareable.

## Gates

- **H1–H8** exercise the host above on a real emulator (x86_64, API 34).
- **G1–G12, G14, G15** are the Phase 3 OpenCode gates re-driven **against the
  app-owned server** (over `adb forward` + the app password) — real shell,
  read/write, git, MCP stdio, streaming, permissions, sessions, end-to-end.
  G13 (stop/restart) is covered by H6+G14; G4's runtime facts are checked in
  the app sandbox via `bin/` symlinks.
- Arm64-v8a is the **shipping** target: the payload is built for both ABIs;
  the emulator (x86_64) is the CI-executed target. Arm64 artifacts are
  produced and statically checked (ELF/interp/sha) — honest labeling applies.
