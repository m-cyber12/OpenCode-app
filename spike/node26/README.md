# Node 26 on Android (spike)

Goal: check that Node 26.4.0 builds as an Android/Bionic arm64 executable with
the same Termux patches, zlib, NDK and configure flags as the shipping Node 24.22.1 recipe.

- Script: `build-node26-android.sh` (`--prepare-only` fetches and patches, no NDK needed).
- Workflow: `.github/workflows/node26-android-spike.yml` (CI artifact `node26-android-arm64`).
- Upstream patch: `patches/nodejs-pr65774-v8-android-posix-trap.patch` (nodejs/node PR #65774,
  open, head `98da35b1`; issue #65771). Applied after the Termux patches.
- Output stays outside the repo (`/tmp/node26-out` in CI). Nothing large is committed.
- Shipping Node 24 pin (`phase4/scripts/10-build-payload.sh`) is not changed by this spike.

## Status

| Run | Result |
|---|---|
| 41341253895 | NDK 28.2 not on the runner image (image has 27.3 and 30.0) |
| 41341491340 | install step added; build failed at NDK check |
| 41341667566 | configure: `--without-corepack` is not a Node 26 flag |
| 41342281398 | libc++ of NDK 28.2 has no `std::atomic_ref` (V8 14 needs it); NDK probe added |
| 41342916704 | host `mksnapshot` link: `undefined symbol trap_handler::TryHandleSignal` |
| 41355013606 | host `mksnapshot` link: `undefined symbol v8_internal_simulator_ProbeMemory` |
| 41363662179 | V8 `mksnapshot` links; `node_mksnapshot` fails (log not readable from the sandbox) |
| 41383009551 | failed at ninja line 61263 with NDK 30.0; first failing action not readable (only annotations are) |
| 41436063697 | **success**: Node 26.4.0 arm64 built for Android with NDK 30.0.16248370 and PR 65774; ICU data check passed (the script fails otherwise). ELF and alignment report not readable from the sandbox (artifact blob download blocked). Not run on a device. |

## Verification after 41436063697 (2026-11-02)

- The script now checks the output and fails the job if the interpreter is not
  `/system/bin/linker64`, the binary is not AArch64, or any LOAD segment is aligned below 16 KiB.
  The result is also written as a `node26-elf` notice annotation, because the artifact cannot be
  downloaded from the sandbox.
- Tested the check against a mock good output (passes) and host `/bin/ls` (fails for the right reasons).

## Changes after 41383009551 (2026-11-02)

- Replaced the local trap-handler workarounds (api.cc `#if 0` guard, global
  `V8_TRAP_HANDLER_SUPPORTED=false`) with upstream nodejs/node PR #65774. The PR
  compiles the Android POSIX trap-handler sources and keeps Android trap-handler
  activation off. The old workarounds hid missing symbols rather than fixing source selection.
- Added `--v8-disable-temporal-support` (Node 26 Temporal is Rust; the cross-build cannot link it
  with a host-only rustup toolchain). Also used by the community Android build for Node 26
  (`andiasriefail/node-android-build`, `patches/26/custom/configure-args`).
- The first failing ninja action is now written to an `::error::` annotation (newlines escaped,
  12 KB cap), so the cause can be read through the API when the log download fails.

Verified: the CI build completes and the ICU data symbol is present (run 41436063697).
Not yet verified: the ELF checks on the real binary (the next run asserts them), and running the binary on a device.
