# OpenCode v2 on Node 26 (TEST ONLY, spike)

Goal: check that the OpenCode v2 server runs on the phone under the Node 26 binary
(`libnode26.so`, see `spike/node26/`), with no Bun. This is the first slice of
removing Bun from the app. The release APK, its 230 MiB cap and the Node 24.22.1 pin
do not change.

## What CI builds

`build-v2-bundle.sh` (called from phase10, `continue-on-error`):

- clones `anomalyco/opencode` at tag `v2.2.5` (Bun 1.4.4 pin),
- `bun install --frozen-lockfile`, then
  `OPENCODE_TARGET=linux-arm64 bun run script/build-node.ts --bundle-only`,
  which gives `opencode.mjs` and `assets/` (the arm64 asset set, the app archive
  and the native pieces the bundle loads),
- zips them as `node26-v2.zip`.

`.github/scripts/node26-inject-test-apk.sh` stores that zip as
`assets/node26-v2.zip` in the TEST-ONLY APK copy, next to `libnode26.so`.

## What the phone runs

Settings > Diagnostics > "OpenCode v2 on Node 26 probe (test build only)":

1. extracts the zip to `filesDir/node26-v2/`,
2. runs `libnode26.so --experimental-ffi --use-system-ca
   --disable-warning=ExperimentalWarning opencode.mjs serve --hostname 127.0.0.1 --port 4797`
   with a random `OPENCODE_SERVER_PASSWORD` and HOME/XDG under `filesDir`,
3. polls `GET /api/config` with and without Basic auth (user `opencode`),
4. stops the server and prints the log tail with the password redacted.

## Pass criteria for this slice

- The server stays alive and answers `GET /api/config` with HTTP 200 under Basic auth.
- Without auth the answer is 401. Anything else is a finding; report it as is.
- Any `Error`/`Cannot find module` lines in the log name the next native piece to fix.

Not covered yet: model calls, tools, sessions, pty, file watching, and the app's own
client, which still talks to the v1 API. Those come after this slice passes.

## Known gaps

- The bundle is built for linux-arm64 glibc natives. Android is Bionic, so the native
  modules (node-pty, parcel watcher, fff, ffi-rs) may fail to load. That shows up in the
  log and decides the next step.
- The probe runs only in the test APK. The release APK never gets `node26-v2.zip`.
