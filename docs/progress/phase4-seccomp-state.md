# Phase 4 runtime host — current debugging state (seccomp fix chain)

This note records where Phase 4 CI gate iteration stands as of the last on-device
CI run, so work can resume without re-discovering the root cause. All code below
is committed on branch `arena/01a04ca1-opencode-app`.

## TL;DR

The embedded OpenCode **server now runs real OpenCode code on-device**. The
remaining blocker was Android's per-app **seccomp** filter killing Bun with
`SIGSYS`. A native shim eliminated the fatal crashes; the last boot failure
(`mkdir ... ENOSYS`) is fixed by a local commit (`30c3016`) that emulates the
blocked legacy `mkdir`/`access` syscalls with the permitted `*at` variants.

**Action needed:** push `30c3016` (GitHub PAT in the session had expired, 401)
and let CI run `33xxxxx`. Then read `docs/progress/phase4-evidence/GATES_SUMMARY.txt`.

## Evidence-backed chain (on-device, x86_64 emulator, CI runs)

| Run / commit | Finding |
|---|---|
| logcat, run `33252919523` | binaries (`bun`,`git`,`rg`) execute via `nativeLibraryDir` symlinks; server not reachable |
| logcat, run `33276722389` | `Fatal signal 31 (SIGSYS) ... syscall 441` = **epoll_pwait2**; Android app seccomp denies with TRAP→SIGSYS (not ENOSYS), killing bun's event loop. CLI bun exits before blocking, so only the long-lived server crashed. |
| run `33279096146` (FFI shim) | 441 fixed, then `syscall 21` (`access`) trapped during bun's OWN native init (~43 ms) — before any JS / `bun:ffi` can install a handler. A JS-side shim is too late. |
| runs `33280230730`/`33300086811` | **PIE exec wrapper + LD_PRELOAD constructor** installed the SIGSYS handler before bun main(): **zero SIGSYS crashes since**. Server boots into OpenCode and emits `SERVER_BOOT_FAILED: ENOSYS: function not implemented, mkdir '.../xdg/data/opencode'` at `core/src/global.ts:35`. |
| commit `30c3016` (local, **not yet pushed**) | `mkdir` (raw x86-64 syscall 83) is seccomp-denied; bionic's own `mkdir()` uses the permitted `mkdirat` (257). The SIGSYS handler now reads trap args from saved registers (rdi/rsi/rdx) and emulates `mkdir→mkdirat(AT_FDCWD,…)` / `access→faccessat(AT_FDCWD,…)`, returning the real result in RAX. Verified on host with a BPF filter trapping 83+21: trapped `mkdir` actually creates the dir (stat confirms), `access` succeeds/returns ENOENT correctly. |

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

## Gate status from the last evidence run (`ffc2a33`, before `30c3016`)

H1 PASS, G01–G04 PASS (exec/userspace/shell/runtime), G05 PASS, H6 PASS
(graceful stop, no zombies), H7 PASS (logs/diagnostics), H8 PASS (ABI gate).
H2/H3, G06–G12, G15, G14, H4/H5 failed only because the server never reached
HEALTHY (the `mkdir` boot abort). **Model gates require `OPENROUTER_API_KEY`**
(absent in CI → skipped by design; not model-round-tripped this phase).

## Next steps after pushing `30c3016`

1. Push and let the Phase 4 workflow run; fetch evidence.
2. If H2 becomes HEALTHY: the G gates (shell/files/git/mcp/stream/permission/e2e)
   run against the real app server; triage any genuine failures.
3. Watch `runtime.log` for `[seccomp] trapped syscall N -> ENOSYS (no *at
   emulation)` — any new legacy syscall that needs an `*at`/fallback emulation
   gets a case in `emulate()` (seccomp-shim.c). Likely candidates: none expected
   beyond mkdir/access for boot; MCP/agent paths may reveal more.
4. After H1–H8 + core G gates are green, write
   `docs/progress/phase4-runtime-host-report.md` with IMPLEMENTED/TESTED labels.

## Operational note

Fine-grained PATs provided in-session expired within minutes (several times).
A longer-lived token (classic `repo`+`workflow`, or 90-day fine-grained with
Contents RW + Actions R) avoids mid-iteration lockouts.
