# OpenCode v2 full migration plan (approved 2026-11-05)

Owner decisions (2026-11-05):
- Replace v1 completely. Remove v1 and Bun from the app.
- Keep the 230 MiB APK cap. Size is acceptable once v1 and Bun are removed.
- Upgrade features for v2 too: native features (terminal, file watching, file search) are built for Android, not dropped.
- Change carefully, one phase at a time, each with a CI gate.
- Approved: change the Node pin (Node 26.4.0 replaces Node 24.22.1) and update the runtime payload.

## Current state (measured)

- Release APK: 200.8 MiB (includes `libbun.so` 89.8 MB, v1 server bundle 34.4 MB).
- Test APK with Node 26 + v2 bundle: about 184 MB zip (test-only, debug-signed).
- Node 26 `libnode26.so`: 93.8 MB (ICU full). Small-ICU not measured yet.
- v2 bundle: `opencode.mjs` 21.4 MB + assets (`app-archive.bin` about 25.9 MB).

Size estimate after removing Bun and v1 (not measured; to be checked in phase 5):
200.8 - 89.8 (bun) - 34.4 (v1 bundle) + 93.8 (node26) + 21.4 (v2 bundle) + 25.9 (assets) ≈ 217 MiB uncompressed, before compression. Re-measure with a real release build.

## Security invariants that must carry over (do not drop)

These exist in v1 today. Each one needs a v2 equivalent and a test.

1. Loopback-only bind. Refuse non-loopback. No mDNS. CORS none. Check: `SERVER_BOUND` log and loopback audit.
2. Server password: stdin path, not in the environment. Hidden from child processes (`process.env` view + spawn wrappers). Node-side equivalent needed in the launcher.
3. Basic auth on every route (user `opencode`, random password). Already verified on device (401 without auth).
4. Permission guard (`phase4/patches/permission-guard.ts`): applied to v1 source. Port to v2 permission model, then test.
5. No-exec shell (`noexec-shell.ts`): applied to v1 shell tool. Port to v2 `shell` tool, then test.
6. Config-write prompt and permission guard even when bash is session-allowed (owner 2026-10-31).
7. Shims: `wget`, `npm`, child shim. Re-check on Node 26 (route A verified on v1 only).
8. Seccomp preload (`seccomp-shim.c`): needed for Bun's syscalls. Re-test on Node 26 on device; drop only if the device shows no SIGSYS.
9. Exec shim (`exec-shim.c`): wraps `libbun.so`. Replace with the Node equivalent or remove if Node does not need it. Test before removing.
10. Keystore-backed password storage and no plaintext password file (already verified on device).
11. Storage: app-private paths only (Phase 5 policy); no network allowlist (owner: network access stays).

## Phases

### Phase 1: v2 runtime in the release build
- Release build ships `libnode26.so` (as jniLibs, already proven in test APK) and the v2 bundle zip.
- New v2 launcher (Node, no Bun APIs). Keep the invariants above in the Node form.
- Single swap (owner-approved 2026-11-08): v1 is not kept behind a switch. v1 and v2 cannot both ship under the 230 MiB cap, so v1 and Bun are removed in the same change that ships v2. Until then, the release gates stay red by design (option A).
- Gate: CI green; test-only probe passes on device (server, auth, models list, shell tool).

### Phase 2: security invariants on v2
- Port items 1–10 above. Each has a JVM or launcher test and a device check in the Diagnostics probe.
- Gate: launcher tests green; device Diagnostics shows each invariant as PASS.

### Phase 3: native features for Android (features upgrade)
- Terminal (`@lydell/node-pty`): build a Bionic `pty.node` with the NDK in CI (the current glibc prebuild cannot load on Android).
- File watching (`watcher`): build for Android, or use a polling fallback if the build is not reliable.
- File search (`fff`): build for Android.
- ffi-rs: build or remove (check which v2 tools need it).
- Each one gets a spike CI job and an ELF check (interpreter `/system/bin/linker64`, 16 KB alignment).
- Gate: each feature works on device (terminal runs a command, watcher sees a file change, search finds a file).

### Phase 4: app API port to v2
- Port `OpenCodeApi.kt` (about 30 v1 endpoints), `OpenCodeRepository.kt`, and the chat, Settings, MCP refresh, Preview, and P1 features (acceptance, background tasks) to the v2 protocol.
- Config: v2 key renames (`bash`→`shell`, `task`→`subagent`, `write`/`patch`→`edit`, `prompt`→`system`, `maxSteps`→`steps`), `tui.json`→`cli.json`, and migration of existing `opencode.json` files.
- Plugins: v1 plugins do not run in v2. Document and tell the user.
- Gate: JVM tests green; each P1 feature has a test on v2.

### Phase 5: remove v1 and Bun
- Remove `libbun.so`, the v1 bundle, the Bun-based launcher code, v1 patches, and Bun test harness (`launcher.test.mjs` runs under Bun 1.3.14 today).
- Update `phase4/scripts`, `phase10/scripts/40-release-verify.sh` (checks for `libbun`), `versions.lock`, `RuntimeVersion.kt`, and docs.
- Re-measure the release APK. Gate: under 230 MiB (`SIZE_CAP_MB=230`).

### Phase 6: verification
- CI green (static, unit, payload, release APK, Play size check).
- Owner phone run of `PROMPTS.md` on v2.
- Limit: free models are rate-limited on Zen for this device and network (429). A full model conversation needs a working quota window or a provider key. No paid keys without owner approval.

## Open items

- Node ICU mode (full vs small) not measured. Measure in phase 1 or 5.
- Zen quota (429) blocks full model tests on this network.
- Owner's pushes need `workflows` scope.

## Progress log

- 2026-11-05 Phase 1, slice 1 (`phase4/payload/v2/launcher-v2.mjs` + tests, 9/9 on Node 22.22.3): stdin password, loopback-only bind, `serve` argv. Not yet wired into the build or the APK.
- Plan change: the release APK cannot carry v1 and v2 side by side (cap 230 MiB). v1 is removed in the same change that ships v2 (phase 5 merged into the release switch-over). Intermediate commits use `[skip ci]` until the release is green.
- Finding (v2.2.5 source): the v2 CLI hides `OPENCODE_SERVER_PASSWORD` from child processes (`packages/cli/src/env.ts` `session()`). The Bun-era `Bun.spawn` wrappers are not needed for v2.
- 2026-11-05 Phase 1, slice 2 (Kotlin switch, transitional): `ServerEngine` (V1 / V2) selected only when the APK has both `libnode26.so` and `node26-v2.zip`. V2 path: `PayloadExtractor.ensureExtractedV2()` (zip to `runtime/v2`, marker `.complete`), `RuntimeProcess.start(engine, hostname)` runs `libnode26.so --experimental-ffi launcher-v2.mjs opencode.mjs --hostname H --port 4111`, health on `/api/info` (body `"version"`). The bundle zip now includes `launcher-v2.mjs`. Release APK has no Node 26, so release stays V1. Not yet compiled locally (no JDK in sandbox): CI is the compile check.
- Still to do before switch-over: release build ships Node 26 + v2 zip, remove `libbun.so` and the v1 payload, security invariants on v2 (permission guard, no-exec shell), release checks (`check-release-invariants.py`, size), JVM tests for v1 removal.
- 2026-11-05 device run (realme, test APK with Node 26, engine V2): status stuck in STARTING, 4 restarts, no server output at all, nothing listening on 127.0.0.1:4111. Cause (reproduced on host): the launcher detected "run as script" by comparing `import.meta.url` with the script path. Through a symlink Node reports the real path, the check failed, and the process exited 0 without loading the bundle. Fix: match by file name, log every launcher step to stderr, log process state when health fails, and add a symlink regression test.
- 2026-11-05 CI: run 42046282415 (commit 82bc5c28) was stopped by the runner with exit 143 after 59 minutes; no logs or artifacts. Re-run by the GitHub UI or a new change on this branch (empty commits and workflow_dispatch are not accepted for this token).
- 2026-11-07 Phase 4, slice 1 (client on v2, JVM-tested, not yet device-tested): `client/v2/V2Translator` turns v2 stream events into the v1 frames the transcript reads (text and reasoning streaming, turn status, errors). `client/v2/V2Mapping` rebuilds v1 shapes from v2 responses (sessions, providers from `/api/provider` + `/api/model` + `/api/model/default`, active status, messages with stable part ids). `OpenCodeApi` now calls v2 routes: connect (`/api/info`), sessions, prompt (model and agent synced to the session first), interrupt, history. `OpenCodeEventStream` reads `/api/event`; v2 has no heartbeat, so the silence budget is 120 s.
- Slice 1 gaps (throw "not on OpenCode v2 yet", status 501, no request sent): revert and unrevert, shell command, permission reply, question reply and reject, MCP add and connect, provider credentials, config write, file read and list. Lists that are not ported report empty (pending permissions and questions, todos, MCP). Consequence: a tool call that needs approval waits until the user taps Stop. Slice 2 must add the permission list (`GET /api/permission/request`) before tool use is tested on device.
- The v1 engine path is not kept in the client. The release build still selects v1 until the switch-over; the owner-approved single swap means v1 and v2 do not both run in the shipped app.
- 2026-11-07 CI for slice 1: run `42371022744` (commit `78965a22`). Compile and JVM unit tests pass (555, 0 failures). The device gates fail: `P9_PROVSEL_*` and `P10_WS_W4_WORKSPACE_VISIBLE` need a running server, and the release-shaped builds still start v1 while the client now speaks v2. This is the expected state of the single-swap plan until the release switch-over. The Node 26 test-only APK from the same run (`opencode-android-arm64-v8a-node26-test-only`, engine V2 with the new client) is the device build for slice 1.
- 2026-11-09 Phase 4, slice 2 (permissions and forms on v2, JVM-tested, not yet device-tested), commit `be6d37ee`. Permissions: `permission.asked` and `permission.replied` are translated; the pending list reads `GET /api/permission/request`; the reply posts to `POST /api/session/:sid/permission/:rid/reply`. Forms (the v2 name for questions): `GET /api/form` lists them, each visible field becomes one question (yes/no and option fields get labelled options; free text and numbers are custom answers). The reply reads the form, converts the chosen labels back to `Form.Answer` values and posts them; decline is `DELETE`. Forms with an `external` (web) field are not offered in the app. `Transcript` accepts `frm_` ids. Slice 2 gap: a form with a web step stays pending until Stop.
- 2026-11-09 CI for slice 2: run `42556994559` (commit `be6d37ee`). Compile and JVM unit tests pass (564, 0 failures). The gates that fail are the same as slice 1: `P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN` and `P10_WS_W4_WORKSPACE_VISIBLE`, which need a v1 server. The release-shaped smoke UI gates pass (38, 0 fail, 2 skip). Owner device check for slice 2: use the Node 26 test-only APK from this run (`opencode-android-arm64-v8a-node26-test-only`): a tool call that asks for permission, and a form question.
- 2026-11-09 CI for slice 3 (MCP and file routes): run `42636015396` (commit `34d916da`). Compile and JVM unit tests pass (569, 0 failures). Failing gates are the expected v1 ones: `P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN`, `P10_WS_W4_WORKSPACE_VISIBLE`. Smoke UI gates pass (38, 0 fail, 2 skip).
- 2026-11-12 CI for slice 4 (agent tool calls on v2): run `43185693836` (commit `83ce5dc2`) completed as failure. The failed gates are the expected v1 ones only: `P10_WS_W4_WORKSPACE_VISIBLE`, `P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN` (from `phase10-evidence/00-run-phase10.log`). Balance and comment checks pass.
- 2026-11-12 Phase 4, slice 5 (config write, owner option B), JVM-tested, not yet device-tested: the app edits `opencode.json`/`opencode.jsonc` in the global config folder itself. Comments outside the replaced `mcp` or `permissions` value are kept (`JsoncEdit`); the result must parse before the file is written (atomic rename); then `POST /api/location/reload`. MCP servers go to `mcp.servers`; permission policy goes to the v2 `permissions` rule list (v1 `bash` is `shell`). Accepted risk (owner, 2026-11-12): a bad edit can change the user's config, so the app refuses to write a file it cannot parse. Limit: comments inside a replaced `mcp` block are lost.
- 2026-11-12 CI for slice 5 (config write): run `43232347413` (`2696149e`) failed at compile (`V2Mapping` passed a Kotlin map to org.json); fixed in `feb7a23e`. Run `43252984198` (`feb7a23e`) compiled, but one new JVM test assumed key order (org.json has none); fixed in `51cba0a8`. Run `43260526294` (`51cba0a8`): JVM 584/0 pass; P10-DEVICE pass; failing gates are the expected v1 ones only (`P10_WS_W4_WORKSPACE_VISIBLE`, `P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN`). Still device-untested.
- 2026-11-12 Phase 4, slice 6 (shell command on v2), JVM-tested, not device-tested: `POST /api/session/:id/shell` replaces the stub; output comes from `session.shell.started|ended` as a `shell` tool part on its own message; history shell items map to the same part. CI `43300444618` (`16031712`): compile ok, JVM 589/0, device gate pass; failing gates are the expected v1 ones only (`P10_WS_W4_WORKSPACE_VISIBLE`, `P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN`).
- 2026-11-12 Phase 4, slice 7 (revert and redo on v2), JVM-tested, not device-tested: undo stages (`revert/stage`), redo clears (`DELETE revert`), forward redo stages a later message. The staged marker from `Session.Info.revert` is read into the session list. The server commits a staged revert when the next prompt runs, so the app refreshes the session list after a prompt sent while a revert was staged. Commit deletes the later messages, so redo is only possible before a prompt.
- 2026-11-13 CI for slice 7: run `43365569512` (commit `6e57c264`). Compile ok, JVM 592/0, device gate pass. Failing gates are the expected v1 ones only: `P10_WS_W4_WORKSPACE_VISIBLE`, `P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN`.
- 2026-11-13 Phase 4, slice 8 (provider credentials on v2), JVM-tested, not device-tested: the v2 provider is the integration with the same ID. Set key runs `POST /api/integration/:id/connect/key`, then removes the older key credentials (v1 replaced the one stored key). Remove deletes the key credentials. Secret values are never read (the integration route omits them). Open for device check: providers whose integration has no key method.
- 2026-11-13 CI for slice 8: run `43379769239` (commit `16e9e569`). Compile ok, JVM 595/0, device gate pass. Failing gates are the expected v1 ones only: `P10_WS_W4_WORKSPACE_VISIBLE`, `P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN`.
- 2026-11-13 Phase 4, slice 9 (compaction and remaining session parts on v2), JVM-tested, not device-tested: compact is the v2 route (already in slice 1, now tested). Completed compactions in history become summary messages. The todo poll is off on v2 because v2 has no todo route; the todo bar uses the latest `todowrite` tool part. Live compaction summaries are not mapped (no message id on `session.compaction.ended`). After this slice no client method sends a v1 route or throws "not on v2".
- 2026-11-13 slice 9 CI `43398652603` (`a6bf081a`): failure on expected v1 gates only (`P9_PROVSEL_REBUILT`, `P9_PROVSEL_TURN`, `P10_WS_W4_WORKSPACE_VISIBLE`). JVM 599/0. The skip list is the same as the previous evidence run. Device gate pass. Evidence commits `ac49c664`, `81389812` (bot).
