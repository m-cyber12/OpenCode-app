# Node 26 on Android (spike)

Goal: check that Node 26.4.0 builds as an Android/Bionic arm64 executable with
the same Termux patches, zlib, NDK and configure flags as the shipping Node 24.22.1 recipe.

- Script: `build-node26-android.sh` (`--prepare-only` fetches and patches, no NDK needed).
- Workflow: `.github/workflows/node26-android-spike.yml` (CI artifact `node26-android-arm64`).
- Output stays outside the repo (`/tmp/node26-out` in CI). Nothing large is committed.
- Shipping Node 24 pin (`phase4/scripts/10-build-payload.sh`) is not changed by this spike.
- Not yet verified: the NDK build, the ICU check on the output, and running the binary on a device.
