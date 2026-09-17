# Phase 9 report — CI, documentation, release

Status: **IMPLEMENTED; device re-verification PENDING on the Phase 9 CI run**,
which the repository owner must install (the automation account cannot write
under `.github/workflows/`). Nothing in this report claims a device result
that has not been observed; the two carried bugs are root-caused with upstream
source references and fixed, and the gates that will prove the fixes are
written, wired and statically checked — but they have **not run yet**.

- Date: 2026-09-15
- Branch: `arena/01a0a540-opencode-app` (from `main` @ `dac18a90`)
- Sandbox facts that shape this phase: no JDK/Android SDK/emulator/Bun in the
  authoring environment (JDK download failed; not retried). Static checks ran
  locally; compile, unit tests and every device gate run only in CI.
- Pins (unchanged upstream, new payload): OpenCode `05ea5073` v1.18.23,
  Bun 1.3.14, git v2.48.1, ripgrep 15.1.0, **payload v6**, app
  `1.18.23-phase9` (versionCode 7).

## 0. Honesty table (read this first)

| Item | Label |
|---|---|
| Provider-selection fallback bug — root cause, fix in `OpenCodeRepository` + `DefaultModelHint`, gates `P9_PROVSEL_*` | **IMPLEMENTED, NOT TESTED** — runs 1 and 2 never reached a verdict (run 2: the gate class did not start the runtime in its own instrumentation process, the Phase 5 lesson; fixed for run 3, §2c) |
| `@opencode-ai/plugin` install bug — bare `OPENCODE_VERSION` (**TESTED**: `P9_VERSION PASS`, runs 1+2) + pre-seeded plugin tree in payload v7 (**TESTED, run 2**: `P9_PLUGIN PASS`, seed present at 1.18.23, 0 install failures, **0 exit-159 in the whole run** vs 113 in run 1 - §2c) |
| Phase 9 CI pipeline (`phase9-release.yml` + `00-run-phase9.sh` + `20-gates.sh`) | **TESTED** end-to-end (run 35132822991 reached all 7 stages and printed every gate) |
| Release APK/AAB + runtime artifacts | **PRODUCED (run 2, payload v7)**: `app-release-unsigned.apk` 130 557 869 B (130.6 MB), `app-release.aab` 118 782 367 B (118.8 MB), payload tar + manifest, artifact `opencode-android-release`. Unsigned (no keystore secret). |
| Capability matrix, README, ARCHITECTURE, RUNTIME, SECURITY, TESTING | IMPLEMENTED (written from executed Phase 4–8 evidence; every row labelled) |
| `versions.lock` accurate | IMPLEMENTED + TESTED (mechanically: `check-lock.py` OK locally against `RuntimeVersion.kt` and `versionName`; the manifest half runs in CI as `P9_LOCK`) |
| Phase 8 temporary secrets removed | **DONE (owner-confirmed 2026-09-16)**: `OPENROUTER_API_KEY` repo secret deleted and the Gemini key from commit `52e7c4d` revoked by the repository owner; the repository itself cannot verify either, so this rests on the owner's statement |
| Permanent limitations documented (G16 remote MCP, toybox on API 29, keystore residency) | IMPLEMENTED (README, RUNTIME, SECURITY, CAPABILITY-MATRIX) |
| Check against `11-FINAL-ACCEPTANCE.md` | **PENDING (joint review)**: the checklist is part of the project brief, not of the repository, and is reviewed together with the project owner after Phase 9 completes; §7 pre-checks the criteria stated in the Phase 9 brief |
| L2 real tool card | still **BLOCKED-NO-CREDIT** (unchanged from Phase 8; needs a funded key — see §3) |

## 1. Carried bug 1 — provider selection falls back to the bundled `opencode` provider

### Diagnosis (upstream source, pinned commit, read this session)

- `packages/opencode/src/provider/provider.ts` builds the provider table
  **once per instance** (`Instance.state`, ~1395–1725) from `auth.json`,
  config and env. `provider.ts:185–200`: the bundled `opencode` provider
  without a key keeps only zero-cost models — that is why the fallback lands
  on `opencode/big-pickle`.
- `server/routes/instance/httpapi/handlers/control.ts:13–26`: `PUT /auth/:id`
  and `DELETE /auth/:id` **only write `auth.json`**; no cache invalidation.
- The upstream desktop app calls `client.global.dispose()` right after
  `auth.set()` (`packages/app/src/components/settings-providers.tsx:134`,
  `settings-v2/providers.tsx:131`; `server-compat.ts:400–405`). The Android
  client (`OpenCodeRepository.provisionProvider`) never did.
- Consequences: a freshly added provider is invisible until restart; a turn
  with an explicit model for it fails with `ProviderModelNotFoundError`
  published only on `session.error` (no assistant message → the "silent empty
  turn"); a turn without a model uses `Provider.defaultModel()`
  (`provider.ts:2003–2036`: config → `model.json` recent → first provider) →
  `opencode/big-pickle`.
- Second defect in the same area: the app's default model was the first
  entry of `GET /provider`'s `default` map — an arbitrary catalogue provider
  (e.g. `subconscious`), not a connected one.
- Why Phase 8 round 19 saw `provider=openrouter`: the key had been provisioned
  *before* a reboot in that run, so the table was rebuilt with it. Consistent
  with this diagnosis; no retraction needed.

### Fix (IMPLEMENTED)

- `OpenCodeRepository.applyCredentialChange`: after `PUT`/`DELETE /auth/:id`
  → `POST /global/dispose` → `GET /provider` → default model recomputed.
- `client/DefaultModelHint.kt` (+ JVM `DefaultModelHintTest`): the default
  model is taken from a **connected** provider (`default` map entry for a
  connected id; explicit preference kept when still valid), never from the
  first catalogue entry.
- `OpenCodeApi.ProviderEntry.source` exposed (upstream's `source: "api" |
  "config" | "env" | "custom"`), which is what the gates use to distinguish a
  credential-built instance from a stale catalogue entry.
- Phase 8 harness aligned: `p8-keymanage.js provision` now disposes and reads
  the provider back (`P8KEYPROV ok=1 disposed=1 instanceSource=api`);
  `LiveToolCallGatesTest.g01` disposes and emits `P8_PROVSTATE`.

### Verification (written, NOT TESTED)

`app/src/androidTest/.../client/ProviderSelectionGatesTest.kt`, driven by
`phase9/scripts/20-gates.sh`, dummy `openrouter` key (never a real one):

| Gate | Expected on 05ea5073 |
|---|---|
| `P9_PROVSEL_STALE` | PASS with `reproduced=true instanceSource=models.dev` — the defect observed |
| `P9_PROVSEL_REBUILT` | PASS `instanceSource=api models>0` after dispose |
| `P9_PROVSEL_TURN` | PASS: user message stored with `openrouter/<model>`, no `ProviderModelNotFoundError`; the dummy key's 401 at stream time is the expected outcome |
| `P9_PROVSEL_CLEANUP` | PASS |

## 2. Carried bug 2 — `@opencode-ai/plugin` install fails (`NpmInstallFailedError`)

### Diagnosis

`packages/opencode/src/config/config.ts:438–445` installs
`@opencode-ai/plugin@${InstallationVersion}` unless the channel is `local`;
`InstallationVersion` is the `OPENCODE_VERSION` build define
(`packages/core/src/installation/version.ts`). Our build defined it as
`"1.18.23-android"`; npm has `1.18.23` (and 1.18.2, 1.18.20–1.18.31) but no
`-android` version → every install fails (observed on device in Phase 8).

### Fix (IMPLEMENTED)

`phase4/scripts/10-build-payload.sh`: define `OPENCODE_VERSION: "1.18.23"`
(bare), `OPENCODE_CHANNEL` stays `android`; `PAYLOAD_VERSION=6` →
`RuntimeVersion.PAYLOAD_VERSION = 6`, manifest default, unit tests
(`ManifestTest`, `PayloadExtractionValidationTest`, `OpenCodeApiHttpTest`),
`phase4/scripts/20-device-gates.sh` H1 regex, `versions.lock`.

### Verification (written, NOT TESTED)

`P9_VERSION` (health `version` == lock) and `P9_PLUGIN` (configure the
plugin, restart the instance, wait ≤ 420 s for `node_modules/@opencode-ai/
plugin` or a failure line in the server log; SKIP with the probe result if
the emulator has no registry egress — Phase 8 showed the server process's
egress on the CI emulator is unreliable, so a SKIP there is possible and is
reported as such, not as a pass).

## 2b. CI run 1 (35132822991, 2026-09-16) - what it proved and what it found

Every stage was reached: P9-STATIC, P9-UNIT (278 JVM tests), P9-PAYLOAD (v6),
APKs, fresh emulator, full Phase 8 suite, Phase 9 gates, **release build
(`app-release-unsigned.apk` 127 100 669 B, `app-release.aab` 115 325 497 B;
native libs arm64-v8a 123.1 MB / x86_64 126.7 MB uncompressed)**.

| Gate | Verdict | Meaning |
|---|---|---|
| `P9_VERSION` | **PASS** | `/global/health` reports `1.18.23` - bug 2's version half is fixed and TESTED |
| `P9_LOCK` | **PASS** | shipped manifest == versions.lock == app constants == versionName |
| `P9-RELEASE` | **PASS** | first release artifacts of the project (unsigned; no keystore secret) |
| Phase 8 SERVERKILL, CRASH, CORRUPT, LIFECYCLELOG, LARGE, STORAGE, SESSIONPERSIST, TOYBOX, CLEANUP, PERF(op half) | PASS | hardening baseline holds on payload v6 |
| `P9_PLUGIN`, all `P9_PROVSEL_*`, `P8_P7REG` (P6_L1), P5-R-07/10/11/12, P5-G17, `P8_HIST`, `P8_NETLOSS`, `P8_BGFG` | FAIL | **one cause, below** |

**Finding (new, serious, now fixed in code): the plugin-version fix made the
server crash-loop.** With the bare version, upstream's background
`npm install @opencode-ai/plugin@1.18.23` runs for real on every instance
load. Arborist begins writing `~/.npm/_cacache` and ~5 s later Bun dies with
**exit 159 = SIGSYS** (a syscall the app seccomp filter traps and our shims
do not emulate). The supervisor restarts it, the install retries, it dies
again: **113 exit-159 events in this run** (Phase 8 evidence on v5: 0),
roughly one every 8-10 s. Every gate that needs the server to stay up for a
turn failed with "connection refused"; `P9_PLUGIN` never saw an outcome
because each restart found the previous attempt's lock
(`LockCompromisedError`). On v5 the install 404'd instantly, so the crashing
code path was never reached - the fix exposed a latent crash rather than
introducing the syscall problem, but the effect was an unusable app.

Fix (payload **v7**): the payload ships the *installed* plugin tree
(`plugin-seed/` -> `xdg/config/opencode/{node_modules,package.json,package-lock.json}`,
built on the host with `npm install --ignore-scripts`, types/maps/bin stripped,
~3.5 MB compressed). Upstream's `Npm.install` sees `node_modules` and a lockfile
covering the package and returns without calling Arborist. The plugin API
therefore works, offline, without the crashing path. `P9_PLUGIN` now asserts:
seed present at the pinned version, zero new `dependency install failed`
lines and zero new exit-159 in a 150 s window; when any 159 is seen it also
captures the kernel's `type=1326 ... syscall=N` audit lines
(`p9-sigsys-forensics.txt`) so the shim table can be extended.

**Residual limitation (documented, BLOCKED until the syscall is identified):
a plugin the user adds beyond the seeded package triggers the on-device
Arborist path and can crash the server** (supervised restart, bounded). Also
fixed from this run: P5-02 accepted only `payloadVersion 5`;
`P9_PROVSEL_CLEANUP` reported a mid-restart readback as failure.

## 2c. CI run 2 (35201496822, 2026-09-17) - crash loop gone, one new defect found

Payload v7 did what it was built for. Whole-run numbers, from
`docs/progress/phase9-evidence/`:

| Gate | Verdict | Evidence |
|---|---|---|
| `P9_PLUGIN` | **PASS** | seed `@opencode-ai/plugin@1.18.23` present in `xdg/config/opencode/node_modules`; +0 `dependency install failed`; +0 `code=159` in the 150 s window; **zero `code=159` in every `runtime.log` of the run** (run 1: 113) |
| `P9_VERSION`, `P9_LOCK` (payloadVersion 7), `P9-RELEASE` | **PASS** | as run 1, now on v7 |
| Phase 8 fold | pass=11 fail=4 skip=4 | PASS: CLEANUP, CRASH, **HIST (was FAIL in run 1)**, LARGE, LIFECYCLELOG, P5REG (K1-K9, R-06/07/10/11/12, G17 conclusive), **P7REG (L1 green again)**, SERVERKILL, SESSIONPERSIST, STORAGE, TOYBOX. FAIL: CORRUPT (below), NETLOSS / BGFG / PERF-stream (key-free, egress-dependent, unchanged since Phase 8). SKIP: the five key-dependent gates. |
| `P9_PROVSEL_TURN`, `_REBUILT` | FAIL | `Failed to connect to 127.0.0.1:4111` on the first POST |
| `P9_PROVSEL_STALE`, `_CLEANUP` | SKIP | "server not healthy" |

**Defect found (IMPLEMENTED fix, NOT TESTED until run 3): the runtime
re-extracted the whole payload on every launch.** `verifyExtraction` checks
each manifest entry at `filesDir/<path>`, but v7 promotes `plugin-seed/*` to
`xdg/config/opencode/*`, so every start logged
`extraction invalid (missing plugin-seed/node_modules/.package-lock.json) -> (re)extracting`
and spent 4-10 s re-extracting 1062 files (the server itself came up fine
afterwards, which is why most gates still passed). Fix: `installedLocation()`
maps seed entries to their promoted path; unit test
`pluginSeedEntriesAreVerifiedAtTheirPromotedLocation` covers both the clean
verify and a tampered promoted file.

Consequences of that defect in run 2, and what else changed for run 3:

* `P8_CORRUPT FAIL (healthy=no reextract_lines=20 marker=1)`: the device
  re-extracted and its own log shows `HEALTHY` at 09:16:45, but neither the
  host-forward nor the on-device probe got a 200 inside the window while the
  runtime.log filled with re-extraction lines. Not fully explained;
  `wait_healthy` now writes forensics on timeout (app pid, `adb forward
  --list`, the raw device-side fetch error, last three states) so run 3 tells
  us instead of us guessing.
* `P9_PROVSEL_*`: the test class never called `RuntimeManager.start()` in its
  own process. `am instrument` replaces the app process and the server is a
  child of it - the exact lesson recorded in `OpenCodeClientGatesTest`
  (Phase 5). A `@Before` now starts the runtime and waits up to 240 s for
  health, as the Phase 5 gates do.
* Plugin bug 2 is now **TESTED closed for the seeded package**; the residual
  limitation (user-added plugins -> on-device Arborist -> possible SIGSYS)
  remains BLOCKED-UPSTREAM-SYSCALL and is documented in RUNTIME.md and the
  capability matrix.

## 3. Permanent limitations (final wording)

| Limitation | Where documented | Label |
|---|---|---|
| Remote HTTP/SSE MCP — upstream #47644 swallows the transport error; `P5-G16` red by design | CAPABILITY-MATRIX, RUNTIME, `upstream-issue-47644-mcp-swallowed-error.md` | BLOCKED (upstream) |
| Toybox `tar` staging on API 29 — the harness path is proven on API 34/35 only; the product's extractor does not use toybox | RUNTIME §Android versions, TESTING coverage matrix | NOT TESTED |
| Keystore residency — software keystore on the CI emulator and on the real phone; hardware requested, result reported | SECURITY §Known limits | TESTED (measurement), accepted |
| No PTY, no git network transport, no file watcher, OAuth provider flows not exposed in the UI | CAPABILITY-MATRIX | DEGRADED / NOT IMPLEMENTED (UI) |
| L2 real tool card — needs a funded model key; the whole path up to the provider's "insufficient credits" is verified | TESTING, README | BLOCKED-NO-CREDIT |
| Server-process memory / steady-state CPU | RUNTIME §Footprint | NOT MEASURED (no profiling pass ran this phase) |

## 4. CI / release pipeline

`phase9/workflow/phase9-release.yml` → `phase9/scripts/00-run-phase9.sh`:

1. `30-static-checks.sh` (Phase 8 checker + phase9 tree + lock check + docs
   present + no `-android` define) — **PASS locally**, all items.
2. compile + JVM unit tests (`-PskipPayload`) — P9-UNIT.
3. payload build (`10-build-payload.sh`) — P9-PAYLOAD; payload tar + manifest
   copied to `phase9/out/release/` as release artifacts.
4. **full Phase 8 suite** via its own orchestrator (boots a fresh emulator,
   builds APKs, Phase 7 + Phase 5 regressions folded, stress/live/destructive
   gates, perf) — every `P8_*` line copied into the release summary.
5. `20-gates.sh` on the same device — `P9_PROVSEL_*`, `P9_VERSION`,
   `P9_PLUGIN`, `P9_LOCK` (re-exports the harness password through the
   Phase 5 debug-only mechanism since the Phase 8 suite deletes it).
6. `assembleRelease` + `bundleRelease` — P9-RELEASE with sizes and signing
   state (unsigned without `P9_KEYSTORE_B64` & co.; `build.gradle.kts` reads
   the keystore only from env, nothing in git).
7. `GATES_SUMMARY.txt` — `cat` as the last workflow step; job exit = any
   Phase 8 FAIL, any Phase 9 FAIL, or release build failure. Evidence
   committed to `docs/progress/phase9-evidence/` and uploaded
   (`phase9-evidence`, `opencode-android-release`).

Knobs: `phase9/CI_GRADLE_ONLY` / dispatch input `gradle_only` (compile only,
clearly labelled in the summary), `P9_SKIP_P8=1` (iteration only).

**To start the first run**: copy `phase9/workflow/phase9-release.yml` to
`.github/workflows/phase9-release.yml` on this branch via the GitHub UI. The
Phase 8 workflow, if still installed, triggers on the Phase 8 branch only and
is unaffected.

## 5. Documentation delivered

`README.md` (rewritten from the one-line stub), `docs/ARCHITECTURE.md`,
`docs/RUNTIME.md` (build, startup, shims, Android versions/ABI, footprint,
troubleshooting), `docs/SECURITY.md` (controls with gates, known limits,
incident/clean-up status), `docs/TESTING.md` (layers, pipeline, how to read a
verdict, Phase 9 gate table, coverage matrix, Phase 8 numbers, local
commands), `docs/CAPABILITY-MATRIX.md` (all 16 requested capability rows plus
Android-specific ones, each with Desktop / Android / Status), `versions.lock`
header updated from "DRAFT, Phase 1" to release status with the Phase 9 notes.

Numbers quoted (APK 143.8 MB both-ABI debug; filesDir ≈ 208 MB; cold start
9.6 s emulator / 13–21.8 s phone; op latencies) are Phase 8 measurements with
their source cited; per-ABI release sizes will come from the pipeline.

## 6. Secrets

- `OPENROUTER_API_KEY` repository secret: **deleted by the owner (2026-09-16)**.
  The pipeline does not need it; model gates SKIP.
- Gemini key in commit `52e7c4d`: **revoked by the owner (2026-09-16)**. It
  remains in Git history as a dead credential; no history rewrite needed.
- Phase 9 gates use a dummy key and remove it (`P9_PROVSEL_CLEANUP`).

## 7. Acceptance pre-check (against the Phase 9 brief; the `11-FINAL-ACCEPTANCE.md` checklist is reviewed with the project owner separately)

| Criterion | Result |
|---|---|
| Real OpenCode in one APK; no Termux/Node/Bun install; no remote server; no Kotlin reimplementation | Held throughout; verified by architecture (ARCHITECTURE.md) and gates P5-G17/G18 |
| Two carried bugs fixed with re-verification evidence or Rule-5 handling | Root-caused + fixed + gates written; **evidence pending the CI run** (labelled NOT TESTED, not claimed) |
| Permanent limitations documented | Done (§3) |
| CI builds app/native/runtime/rootfs, runs emulator gates, prints per-gate PASS/FAIL | Pipeline written; last step prints every gate; **not yet executed** |
| Capability matrix with required columns and rows | Done |
| README/ARCHITECTURE/RUNTIME/SECURITY/TESTING incl. Android versions/ABI, limitations, APK size, perf, troubleshooting | Done |
| `versions.lock` accurate | Done + mechanically checked |
| Release APK/AAB + runtime artifacts + final test report | Artifacts: produced by the pipeline (**not yet**); test report: this document + `GATES_SUMMARY.txt` when the run completes |
| Phase 8 temp secrets removed | Done (owner-confirmed) |
| Honesty labels on every claim | Applied |

## 8. What the next run (run 3) will tell us (and what to do)

0. `runtime.log` must show `extraction complete` at most once per fresh
   install and no `missing plugin-seed/...` line - the re-extraction defect
   is closed; `P8_CORRUPT` should return to PASS as in Phase 8.

1. If `P9_PROVSEL_STALE reproduced=true` and `P9_PROVSEL_REBUILT PASS`: bug 1
   diagnosis confirmed and fixed → relabel TESTED in the docs.
2. If `P9_PLUGIN PASS`: bug 2 closed. If SKIP (no egress): the version fix is
   still proven by `P9_VERSION`; re-run on a real phone with
   `phase9/scripts/20-gates.sh` for the install half.
3. If `P9_PROVSEL_TURN` FAILs with `modelNotFound=true`: the dispose did not
   take effect for the session's instance (per-directory instance) — check
   that the dispose call uses the same `?directory=` as the session.
4. Any Kotlin compile error: the run stops at P9-UNIT with
   `compiler-errors.txt` in the evidence; fix and push (paths-ignore prevents
   evidence commits from re-triggering).
