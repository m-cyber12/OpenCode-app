# Phase 4 — production embedded runtime host report

**Date:** 2026-08-31
**Branch:** `arena/01a04ca1-opencode-app`
**Scope:** APK-owned extraction, process lifecycle, recovery, diagnostics, ABI gating, and in-APK runtime packaging.
**Pinned OpenCode:** `05ea5073be967c779d326929b2de6228dda4159d` (v1.18.23), unchanged from Phase 3.
**Current validation state:** Phase 4's production implementation, both-ABI packaging, Gradle build/tests, and the Android x86_64 emulator suite are validated. The arm64-v8a startup patch has now also been exercised on the real Realme RMX3830 (Android 15/API 35, arm64-v8a): the app reports `HEALTHY` on `127.0.0.1:4111` with no crash records. Full G1–G14 lifecycle execution on arm64 was not supplied; the x86_64 emulator remains the complete G/H gate run.

## 1. Executive result and evidence

The production host is implemented in the Android app; it is not a Kotlin replacement for OpenCode. `RuntimeManager` starts the pinned, bundled OpenCode server through Bun, and the server's real shell, file, Git, ripgrep, MCP, HTTP, SSE, and agent paths are what the gates exercise.

The decisive completed Android run was GitHub Actions **33330083624**, whose CI evidence commit is **dca0a3a**:

- API 34 `x86_64` emulator: **TESTED**.
- H1–H8: **8/8 PASS**.
- G01–G15, including explicit G13 stop/restart and G14 reconnect: **15/15 PASS**.
- Device gates exit code: `0`.
- Gradle APK build and JVM tests: **TESTED in CI**, `BUILD SUCCESSFUL`, 46 actionable tasks, all listed tests passed.
- APK: 143,833,367 bytes; the APK listing contains complete `arm64-v8a` and `x86_64` runtime libraries plus the runtime asset.
- Payload build: `PAYLOAD_READY`, 2 complete ABIs; Git 2.48.1, ripgrep 15.1.0, Bun 1.3.14, and the pinned OpenCode bundle.
- No `OPENROUTER_API_KEY` was supplied (`model_available=0`). Model-dependent content assertions are therefore not claimed as a real external-model round trip; see §4.

A preceding both-ABI attempt (**33329943774**) exposed an arm64 portability defect: `seccomp-shim.c` referenced x86_64-only `__NR_access`. Commit `424162f` added the architecture guard. The follow-up **33330083624** built both ABIs, packaged both into the APK, and passed the complete x86_64 Android suite. The arm64 BPF-skip patch initially exposed a compile-scope error in **33335804059**; `b744714` corrected that, and CI runs **33335963268**, **33336625302**, and **33341713633** completed successfully. The latter produced committed post-patch evidence (`b3c2b04`) with both ABI helpers packaged, strict-version/lifecycle changes built, and H1–H8/G01–G15 green on x86_64. Arm64 binary packaging is **TESTED** in CI, and the arm64 startup/health path is now **TESTED** on the RMX3830 using user-provided on-device diagnostics evidence.

Relevant committed evidence is under `docs/progress/phase4-evidence/`, especially:

- `GATES_SUMMARY.txt`
- `00-run-phase4.log`
- `payload-build.status`
- `runtime.log`
- `g09.log`, `g08.log`, `g10.log`, `g11.log`, `g12.log`, `g15.log`
- `apk-contents.txt`, `app-dependencies.txt`

## 2. Implementation by requested scope item

### 2.1 APK extraction, validation, and versioning — IMPLEMENTED; x86_64 TESTED, arm64 packaged

- `PayloadExtractor.kt` reads the APK's `runtime-manifest.json` and validates every extracted file by size and SHA-256.
- Extraction is staged and promoted only after validation; the marker is written only after a complete verified extraction. Path traversal is rejected by the self-contained tar reader.
- The marker includes `payloadVersion`; it must match `RuntimeVersion.PAYLOAD_VERSION` (`4`). A missing, stale, incomplete, or corrupt extraction is re-extracted from the APK rather than silently accepted.
- The OpenCode payload is flat in app-private storage (`filesDir/launcher.js`, `filesDir/node_modules`, and `filesDir/opencode/...`) so Bun's normal module resolution is preserved. Host metadata remains under `filesDir/runtime/`.
- H1 on the API-34 emulator validated first-run extraction, the marker, the flat payload, executable access, and pinned versions.
- The app now rejects an APK manifest whose payload, OpenCode, Bun, Git, or ripgrep versions/commit differ from the pinned `RuntimeVersion`/`versions.lock` contract; it fails clearly instead of re-extracting an incompatible payload forever.
- The payload builder validates requested ABIs and clears its generated engine directory before each build, preventing a partial/previous invocation from silently supplying an ABI artifact that was not rebuilt.

### 2.2 Real lifecycle: start, health, stop, crash, restart, duplicate prevention — IMPLEMENTED; x86_64 TESTED

- `RuntimeManager.kt` is the single supervisor and exposes state to the app UI.
- Startup order is ABI gate → directories/secrets → extraction validation → executable links → process start → authenticated `/global/health` polling. A launched PID alone is never considered healthy.
- `RuntimeProcess.kt` launches the real `libbun.so` from Android `nativeLibraryDir`, drains stdout/stderr, records the PID, sends SIGTERM for graceful shutdown, escalates to SIGKILL after the bounded wait, and sweeps launcher processes to prevent zombies.
- Start is generation/idempotence guarded; stale launcher processes are removed before a new start and the server's single port is also a final duplicate guard.
- Unexpected death is recorded and restarted with bounded exponential backoff. H2, H3, H4, H6, and the explicit G13 result all passed on the emulator.
- Supervisor generations are checked after launch, health, backoff, and process exit; the process wrapper serializes start/stop and does not let an old waiter clear a newer generation's PID slot. A stop request also sweeps stale processes when the supervisor flag is already false.
- `RuntimeService.kt` is a `specialUse` foreground service. The service is needed because an on-device agent may have long-running server, shell, Git, MCP, or model work that must survive app backgrounding/doze. The manifest contains the service property and rationale; stopping it tears down the process cleanly.

### 2.3 Corruption detection and recovery — IMPLEMENTED; x86_64 TESTED

- Any manifest mismatch, missing payload file, bad size/hash, stale marker, or interrupted staging state causes a complete re-extraction from the APK.
- H5 appended a corruption marker to an extracted payload file, invoked the debug reset/recovery path, waited for a healthy server, and verified that the marker was gone.
- Recovery is fail-loud in logs and does not silently continue with an unverified payload.

### 2.4 Logs and diagnostics — IMPLEMENTED; x86_64 TESTED

- `RuntimeLogger.kt` writes bounded/rotated host logs and one crash record per unexpected server death.
- OpenCode logs remain in the app-private XDG state directory.
- `Diagnostics.kt` gathers host/server log tails, crash information, pinned and installed runtime versions, ABI, Android API level, layout/exec checks, restart state, and model-key presence without logging the secret. The UI can share the resulting diagnostic bundle through the app `FileProvider`.
- H7 found the expected runtime log and diagnostic building blocks on the emulator.

### 2.5 ABI and device gating — IMPLEMENTED; x86_64 and arm64 startup/health TESTED

- `AbiGate.kt` accepts `arm64-v8a` and `x86_64` on API 29+ and reports a clear `UNSUPPORTED_DEVICE` state for 32-bit ABIs or an older API rather than allowing an opaque exec-format/runtime crash.
- JVM unit tests cover arm64/x86_64 acceptance, 32-bit rejection, API rejection, empty-ABI fallback, and ABI precedence; the tests passed in CI.
- H8 passed on the API-34 x86_64 emulator.
- The final payload build and APK contain arm64-v8a Bun/Git/ripgrep/helper binaries.
- The real RMX3830 Android 15/API 35 arm64-v8a device is now **TESTED for startup/health**: user-provided diagnostics show `nativeLibraryDir=.../lib/arm64`, executable Bun/Git/ripgrep/helper files, `status=HEALTHY`, `detail=healthy on 127.0.0.1:4111`, `restart_count=0`, and `Crashes (none)`.
- The screenshot does not include the requested `adb shell getconf PAGE_SIZE` output; page-size evidence is **NOT RECORDED**, although the server health result itself is device execution evidence.

### 2.6 Packaging — IMPLEMENTED; both-ABI APK TESTED in CI

- Bun-for-Android, Git, ripgrep, and native compatibility helpers are packaged as JNI libraries under `lib/<abi>/` and execute from Android's `nativeLibraryDir`, which is the W^X-compatible executable location.
- `filesDir/bin/bun`, `bin/git`, and `bin/rg` are symlinks to the corresponding native-library executables. The production Git/ripgrep path points directly to `libgit.so`/`librg.so`; the retained `libchildshim.so` is compatibility/diagnostic packaging, not the production tool path.
- The JavaScript OpenCode bundle, launcher, and node modules are one compressed `assets/runtime-payload.tar.gz` payload. Android AAPT may list it as a raw `runtime-payload.tar`; extraction handles that form.
- The final APK evidence shows `libbun.so`, `libgit.so`, `librg.so`, the three helper libraries under both `arm64-v8a` and `x86_64`, and `runtime-manifest.json` inside the APK. The APK is self-contained; no user-installed shell, Git, Bun, Node, OpenCode, Termux, or remote server is required.
- Run 33330083624 built both ABIs and produced the 143,833,367-byte APK. The x86_64 emulator executed the x86_64 slice; the RMX3830 subsequently executed the arm64 slice through the installed APK and reached health.

## 3. G1–G14 and host-gate results

The result below is from committed device evidence in run 33330083624 (evidence commit dca0a3a). “TESTED” means executed against the installed real APK on the Android emulator, not merely compiled. The emulator was x86_64; arm64 rows are package/build evidence unless explicitly stated otherwise.

| Gate | Result | Evidence / meaning |
|---|---|---|
| G01 execution layer | **TESTED — PASS** | Bun through app `bin/` → `nativeLibraryDir`, Android platform/ABI |
| G02 userspace | **TESTED — PASS** | Bundled Android/Bionic Git and ripgrep versions execute |
| G03 real shell | **TESTED — PASS** | `/system/bin/sh` variable, pipe, and exit semantics |
| G04 runtime | **TESTED — PASS** | Bun child process, shell, and SQLite path |
| G05 OpenCode start | **TESTED — PASS** | Real server starts from the app-owned host |
| G06 health | **TESTED — PASS** | Authenticated health endpoint returns HTTP 200/healthy |
| G07 shell endpoint | **TESTED — PASS** | Real OpenCode session shell and streamed tool lifecycle |
| G08 files | **TESTED — PASS** | File list/content/find and server-side write; agent write subtest skipped without key |
| G09 Git | **TESTED — PASS** | Server-context `command -v git` resolves app `bin/git`; real Git status/branches/log work, including `feature/g9` |
| G10 MCP | **TESTED — PASS** | OpenCode MCP connected and real SDK stdio child round trip; agent-MCP subtest skipped without key |
| G11 SSE/streaming | **TESTED — PASS** | Real event stream and tool lifecycle; model text-delta subtest skipped without key |
| G12 permissions | **TESTED — PASS** | Real bash command executed. Effective policy auto-allowed it; no ask event was required |
| G13 stop/restart | **TESTED — PASS** | Graceful stop/no zombies followed by cold relaunch and health; explicitly recorded by the Phase 4 runner |
| G14 reconnect | **TESTED — PASS** | Sessions persisted across force-stop/cold relaunch |

The Phase 4 extension G15 also passed: the real OpenCode agent/tool path inspected the fixture and produced a substantive project explanation. Since `model_available=0`, this is not claimed as an external real-model/provider round trip.

Host lifecycle gates were all green on the same emulator:

| Gate | Result | Evidence / meaning |
|---|---|---|
| H1 extraction/version/checksums | **TESTED — PASS** | 30 payload files extracted and validated |
| H2 health-gated start | **TESTED — PASS** | `/global/health` returned `healthy=true`, version `1.18.23-android` |
| H3 duplicate prevention | **TESTED — PASS** | Repeated starts left exactly one launcher and healthy port |
| H4 crash/backoff | **TESTED — PASS** | SIGKILL produced crash record/backoff and automatic healthy restart |
| H5 corruption recovery | **TESTED — PASS** | Corrupt extracted file was replaced from APK payload |
| H6 graceful stop/no zombies | **TESTED — PASS** | SIGTERM path, escalation guard, zero launchers, port down |
| H7 logs/diagnostics | **TESTED — PASS** | Runtime/OpenCode/crash diagnostic building blocks present |
| H8 ABI/device gate | **TESTED — PASS** | API 34 x86_64 accepted |

Evidence summary from the green run:

```text
h_total=8 h_pass=8 h_fail=0
g_total=15 g_pass=15 g_fail=0
model_available=0
device_abi=x86_64 sdk=34
device gates rc=0
```

## 4. Model credential boundary

Phase 4 is primarily a runtime/process phase and does not need model credentials. The successful run intentionally had `model_available=0`:

- G08's agent-write subtest was skipped.
- G10's agent-MCP-tool subtest was skipped.
- G11's model text-delta assertion was skipped.
- The external-provider/model round-trip aspect of G15 is **NOT TESTED** and is not required to validate the runtime host.
- G12's deterministic permission command executed through the real permission path and passed in auto-allow mode.

No model credential was stored in the repository or emitted in evidence. A future model-enabled run is required only if acceptance expands to external-provider behavior.

## 5. New failure modes versus the Phase 2/3 spike and handling

1. **App-private storage is no-exec under modern Android W^X.** The spike used a more permissive temporary/root path. Production packages executables as JNI libs and resolves them through `nativeLibraryDir`; H1/G01/G02/G09 exercised this x86_64 path.
2. **Android zygote seccomp differs for app-started children.** The server starts through the NDK-built `libexecshim.so`, which installs the tested BPF errno compatibility filter before exec. Git and ripgrep are now Android/Bionic builds, not the earlier static-musl production path. The old child shim remains only for compatibility and is not silently selected.
3. **Bun FFI is unavailable in the selected Android Bun build.** Runtime logs report `bun:ffi dlopen() is not available in this build (TinyCC is disabled)`. The server still reached `SERVER_READY` and all non-model gates passed using the exec-shim BPF path, but arbitrary Bun FFI/native-addon use is a known capability limitation and is not represented as full parity.
4. **Payload layout/module resolution is stricter than the spike.** Flat extraction at the app-files root preserves Bun's normal `node_modules` walk-up; staging and checksum validation prevent partial payloads from becoming active.
5. **Background execution can outlive an Activity.** A `specialUse` foreground service and explicit shutdown path handle this; H4/H6/G13 tested the process lifecycle.
6. **Git network/crypto helpers are deliberately not in this minimal Android build.** Git local repository operations are TESTED. The build uses `NO_CURL`, `NO_OPENSSL`, `NO_EXPAT`, and related local-build options, so remote Git transport/authentication and HTTPS Git operations are capability loss, not silently claimed support.
7. **PTY packages are stubs.** `node-pty`/`bun-pty` cannot be built into this payload yet; their spawn path throws a clear “unavailable on Android” error. Non-interactive `/system/bin/sh` execution is TESTED; interactive PTY parity is NOT TESTED/BLOCKED by that missing native port.
8. **ABI coverage requires separate device evidence.** x86_64 emulator execution does not prove arm64 execution. Both ABI slices are build- and APK-packaging-tested, and the RMX3830 supplied arm64 startup/health execution evidence. The full G1–G14/H lifecycle suite was not re-run on arm64.

These are explicit capability losses; the app does not silently fall back to Termux, a desktop binary, a remote OpenCode server, or a fake agent implementation.

## 6. Validation/CI history and current stop condition

| Run / commit | Result | Consequence |
|---|---|---|
| 33328418669 | FAIL | Auxiliary Git targets linked unavailable Android `-lrt`; narrowed build to `git` |
| 33328621351 | FAIL | Direct Git target still inherited `-lrt`; passed `NEEDS_LIBRT=` |
| 33328803576 / evidence 2cffa2f | FAIL | Android Git and APK host path worked, but G09 fixture was not a Git repo; fixed provisioning to invoke packaged Git directly and create `feature/g9` |
| 33329413542 / evidence 2e7d21d | **SUCCESS** | x86_64 APK, Gradle tests, H1–H8, G01–G15 all passed on API-34 emulator |
| 33329943774 / evidence f0c11ec | FAIL | Both-ABI build exposed arm64-only compile failure: `__NR_access` is not defined on arm64 |
| 33330083624 / evidence dca0a3a | **SUCCESS** | `#ifdef __NR_access` fix worked; both ABI slices built, Gradle/APK passed, and x86_64 emulator H/G suite was fully green |
| 33335804059 / evidence b02a3f7 | FAIL | The first arm64 BPF-skip patch did not compile: `arch` was scoped out by the architecture guard; no APK/device result was produced |
| 33335963268 | **SUCCESS** | Re-run after the compile-scope correction in `b744714`; the Android build/package and existing x86_64 gates completed successfully. This still does not replace the real RMX3830 retest |
| 33336625302 / evidence 14cdaeb | **SUCCESS** | Post-patch APK build/package and x86_64 emulator H/G suite; arm64 helper compilation/package passed, but this is not arm64 device execution |
| 33341713633 / evidence b3c2b04 | **SUCCESS** | Version/lifecycle hardening plus clean payload rebuild; both ABI helpers packaged and x86_64 H1–H8/G01–G15 remained green |

The Phase 4 implementation, required x86_64 Android validation, and the arm64 startup/health acceptance requirement are complete. The arm64-v8a artifacts are included in the final APK and pass build/package evidence. The full G1–G14/H suite remains x86_64-tested only; that limitation is recorded explicitly and is separate from the now-proven arm64 startup fix.

## 7. Real-device arm64 Android 15 incident and follow-up

**Status: RESOLVED for embedded-server startup/health; the patch is IMPLEMENTED and TESTED on the phone.**

The reported device is a Realme `RMX3830`, Android 15/API 35, ABI `arm64-v8a`. The prior APK's runtime evidence showed the exec wrapper's `starting` line and then no `execv`, Bun, `SERVER_READY`, or health evidence. The failure therefore occurred before the embedded Bun server became observable.

The patched APK was installed and exercised on that same phone. User-provided diagnostics show `nativeLibraryDir=.../lib/arm64`, executable Bun/Git/ripgrep/helper files, `status=HEALTHY`, `detail=healthy on 127.0.0.1:4111`, `restart_count=0`, and `Crashes (none)`. This is direct arm64 device execution evidence that the embedded server now reaches authenticated health. A committed transcription of the supplied screenshot is in `docs/progress/phase4-evidence/rmx3830-arm64-smoke.txt`.

The follow-up patch is intentionally narrow:

- `exec-shim.c` now detects `__aarch64__` at compile time, logs `[exec-shim] arm64: skipping child BPF filter; using LD_PRELOAD seccomp handler`, and avoids installing the wrapper's second `PR_SET_SECCOMP` filter on arm64. x86_64 retains the previously tested exec-surviving BPF filter.
- `seccomp-shim.c` now logs whether its preload constructor and SIGSYS handler installation succeed or fail.
- `launcher.js` now logs entry, bundle import, and listener binding milestones before `SERVER_READY`.
- `RuntimeProcess.kt` records early process exit codes, including exits observed during stop, to make an arm64 failure diagnosable instead of only reporting an unsuccessful health poll.

The rationale is a leading hypothesis, not a proven root cause: on the affected arm64 Android 15 path, the inherited application seccomp policy may trap the wrapper's own `PR_SET_NO_NEW_PRIVS`/`PR_SET_SECCOMP` setup or another early wrapper syscall. Skipping the child filter lets the dynamic-linker preload constructor install the compatibility handler before Bun initialization. The arm64 Bun artifact was also inspected: it is an AArch64 PIE/DYN using `/system/bin/linker64`, with 16-KB-compatible load alignment and Android bionic dependencies, so ELF packaging alone is not currently the leading explanation.

Validation received:

1. The rebuilt patched APK was installed on the RMX3830 and its installed manifest reports OpenCode `1.18.23`, Bun `1.3.14`, Git `v2.48.1`, ripgrep `15.1.0`, and 30 validated payload files.
2. The user confirmed Android 15/API 35 and arm64; the diagnostics show the actual `.../lib/arm64` native-library directory and executable native payloads.
3. The diagnostics report `status=HEALTHY` and `healthy on 127.0.0.1:4111`, with `restart_count=0` and `Crashes (none)`. This proves the arm64 embedded server passed the required startup/health acceptance.
4. The screenshot does not include the raw `adb shell getconf PAGE_SIZE` output, APK SHA-256, or the complete raw logcat milestones. Those are diagnostic-completeness gaps, not a blocker to the already demonstrated server-health result.
5. The complete x86_64 G1–G14/H suite was rerun after the patch and remains green in CI. The full G1–G14/H suite was not rerun on arm64 and is therefore labeled **NOT TESTED** for that ABI.

## 8. Local verification performed in this checkout

- Confirmed branch is `arena/01a04ca1-opencode-app`.
- `bash -n` passed for the Phase 4 orchestration, payload, and device-gate scripts.
- `node --check phase4/payload/launcher.js` passed.
- Host GCC syntax checks passed for both native shim sources; this sandbox has no Android arm64 cross compiler, Android SDK/JDK/KVM, or direct adb connection, so the RMX3830 result is recorded from the user's supplied device diagnostics.
- `git diff --check` passed for the changes.
- CI runs 33341713633/33342304137 supplied the post-patch Gradle/JVM test, both-ABI APK, and x86_64 Android emulator evidence; the user's RMX3830 diagnostics supply the arm64 startup/health evidence.
