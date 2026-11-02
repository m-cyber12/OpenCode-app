# Node 26 on Android (spike)

Goal: check that Node 26.4.0 builds as an Android/Bionic arm64 executable with
the same Termux patches, zlib, NDK and configure flags as the shipping Node 24.22.1 recipe.

- Script: `build-node26-android.sh` (`--prepare-only` fetches and patches, no NDK needed).
- Workflow: `.github/workflows/node26-android-spike.yml` (CI artifact `node26-android-arm64`).
- Output stays outside the repo (`/tmp/node26-out` in CI). Nothing large is committed.
- Shipping Node 24 pin (`phase4/scripts/10-build-payload.sh`) is not changed by this spike.

## Status (2026-11-01)

Runs on this branch (manual by push, about 2 h each):

| Run | Result |
|---|---|
| 41341253895 | NDK 28.2 not on the runner image (image has 27.3 and 30.0) |
| 41341491340 | install step added; build failed at NDK check |
| 41341667566 | configure: `--without-corepack` is not a Node 26 flag |
| 41342281398 | libc++ of NDK 28.2 has no `std::atomic_ref` (V8 14 needs it); NDK probe added |
| 41342916704 | host `mksnapshot` link: `undefined symbol trap_handler::TryHandleSignal` |
| 41355013606 | host `mksnapshot` link: `undefined symbol v8_internal_simulator_ProbeMemory` |
| 41363662179 | V8 `mksnapshot` links; `node_mksnapshot` fails (log not readable from the sandbox) |

Fixes so far are in the script: NDK probe, V8 trap-handler configuration for
host tools (no trap handler, as the Android target already has), and a guard at the
posix trap entry point in `api.cc`.

Open: the `node_mksnapshot` failure. The next run annotates the first failing ninja
action so the cause is visible in the workflow log.

Not yet verified: the full build, the ICU check on the output, and running the binary on a device.

_Run requested 2026-11-02 (owner approved, option 1): next build with the ninja-action annotation from `83f2a1bc`._
