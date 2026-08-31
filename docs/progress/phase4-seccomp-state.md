# Phase 4 runtime host — current debugging state (seccomp fix chain)

This note records where Phase 4 CI gate iteration stands as of the last on-device
CI run, so work can resume without re-discovering the root cause. All code below
is committed on branch `arena/01a04ca1-opencode-app`.

## TL;DR

The embedded OpenCode **server runs real OpenCode code on the Android x86_64
emulator**. The x86_64 seccomp chain is validated: the PIE exec wrapper loads
the preload handler before Bun initialization, the server reaches `SERVER_READY`
and authenticated health, and the full non-model Phase 4 gate set is green.

A real Android 15 arm64 device (`RMX3830`) exposed an earlier startup failure
before `execv`/Bun output. The follow-up arm64 patch is committed, both-ABI
compilation/APK packaging is green in CI, and the rebuilt APK has now run on
that phone: user-provided diagnostics show arm64 native libraries and
`HEALTHY` on `127.0.0.1:4111` with no crashes. The arm64 startup/health blocker
is resolved; the full G1–G14/H suite remains tested on x86_64 only.

## Evidence-backed chain (on-device, x86_64 emulator, CI runs)

| Run / commit | Finding |
|---|---|
| logcat, run `33252919523` | binaries (`bun`,`git`,`rg`) execute via `nativeLibraryDir` symlinks; server not reachable |
| logcat, run `33276722389` | `Fatal signal 31 (SIGSYS) ... syscall 441` = **epoll_pwait2**; Android app seccomp denies with TRAP→SIGSYS (not ENOSYS), killing bun's event loop. CLI bun exits before blocking, so only the long-lived server crashed. |
| run `33279096146` (FFI shim) | 441 fixed, then `syscall 21` (`access`) trapped during bun's OWN native init (~43 ms) — before any JS / `bun:ffi` can install a handler. A JS-side shim is too late. |
| runs `33280230730`/`33300086811` | **PIE exec wrapper + LD_PRELOAD constructor** installed the SIGSYS handler before bun main(): **zero SIGSYS crashes since**. Server boots into OpenCode and emits `SERVER_BOOT_FAILED: ENOSYS: function not implemented, mkdir '.../xdg/data/opencode'` at `core/src/global.ts:35`. |
| commit `30c3016` (now included in the branch history) | `mkdir` (raw x86-64 syscall 83) is seccomp-denied; bionic's own `mkdir()` uses the permitted `mkdirat` (257). The SIGSYS handler reads trap args from saved registers (rdi/rsi/rdx) and emulates `mkdir→mkdirat(AT_FDCWD,…)` / `access→faccessat(AT_FDCWD,…)`, returning the real result in RAX. Verified on host with a BPF filter trapping 83+21: trapped `mkdir` actually creates the dir (stat confirms), `access` succeeds/returns ENOENT correctly. Subsequent CI run 33330083624 and later post-patch runs reached health on x86_64. |

## The fix architecture (all in repo)

- `phase4/payload/native/seccomp-shim.c` — `libseccompshim.so` (NDK clang,
  links bionic). Installs a SIGSYS handler via its `__attribute__((constructor))`
  (and exports `opencode_seccomp_init()` for a bun:ffi backstop in launcher.js).
  Handler maps new-syscall traps (epoll_pwait2, close_range, preadv2/pwritev2,
  clone3, faccessat2, statx) to -ENOSYS so Bun/Zig take old-kernel fallbacks,
  and EMULATES legacy mkdir(83)/access(21) via mkdirat/faccessat on x86-64.
  x86-64 trap RIP is already advanced past `syscall` (only set RAX); arm64 PC
  points at `svc #0` (advance +4, set x0). arm64 has no legacy mkdir/access.
- `phase4/payload/native/exec-shim.c` — `libexecshim.so`, a PIE executable in
  jniLibs. The supervisor launches this instead of `libbun.so`; it sets
  `LD_PRELOAD=<nativeLibraryDir>/libseccompshim.so` (from
  `OPENCODE_SECCOMP_SHIM`) and `execv()`s the bun in `OPENCODE_BUN_EXEC`,
  forwarding argv. This makes the handler load during dynamic linking.
- `app/.../runtime/RuntimeProcess.kt` — launches the wrapper (falls back to
  direct bun if absent), passes `OPENCODE_BUN_EXEC`/`OPENCODE_SECCOMP_SHIM`.
- `app/.../runtime/RuntimePaths.kt` — `execShimBinary()`; pre-creates the
  OpenCode XDG subtree (`xdg/{data,config,state,cache}/opencode`,
  `state/opencode/log`) in `ensureDirs()`.
- `app/.../runtime/RuntimeEnv.kt` — exports `OPENCODE_SECCOMP_SHIM`.
- `phase4/scripts/10-build-payload.sh` — step [2b] builds both native helpers
  per ABI with the runner NDK clang (`.../toolchains/llvm/prebuilt/linux-x86_64/bin`,
  target `<triple>29-clang`), PIE+shared; Gradle `verifyAndStagePayload`
  requires `libseccompshim.so` and `libexecshim.so` in each complete ABI.
- `app/src/main/AndroidManifest.xml` — declares `INTERNET` (loopback needs it;
  without it netd denies sockets → earlier EPERM), plus loopback-only cleartext.

## Gate status from post-fix evidence

CI run `33341713633` (evidence commit `b3c2b04`) completed successfully with
both ABI native helpers compiled and packaged after the version/lifecycle
hardening changes. On the API-34 x86_64 emulator: H1–H8 PASS, G01–G15 PASS,
and device-gates exit code 0. Run `33342304137` also completed successfully
after the report update. After a nondeterministic G15 false negative where the
real agent delegated through `task`, run `33371704423` (evidence commit
`9bf818a`) passed after G15 was corrected to count completed child-session
real-tool parts streamed over SSE. All runs had no `OPENROUTER_API_KEY`, so
model-dependent assertions were skipped and no real external-model round trip
is claimed. User-provided RMX3830 diagnostics separately prove arm64 startup
and authenticated health.

## Follow-up evidence gaps (non-blocking for the demonstrated startup fix)

1. The supplied diagnostics screenshot does not include raw `adb shell getconf PAGE_SIZE`, APK SHA-256, or the complete native/launcher logcat milestones; those remain **NOT RECORDED**.
2. The full G1–G14/H lifecycle suite was rerun on x86_64 and is green. It was not rerun on the arm64 phone, so arm64 full-suite rows remain **NOT TESTED**.

The required arm64 embedded-server startup/health acceptance is nevertheless
now **TESTED** on the RMX3830. The complete acceptance status and honesty labels
are maintained in `docs/progress/phase4-runtime-host-report.md`.
