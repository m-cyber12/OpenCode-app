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
| Provider-selection fallback bug — root cause, fix in `OpenCodeRepository` + `DefaultModelHint`, gates `P9_PROVSEL_*` | **IMPLEMENTED, NOT TESTED** (awaits CI run) |
| `@opencode-ai/plugin` install bug — root cause, fix in payload build (bare `OPENCODE_VERSION`), gates `P9_VERSION`, `P9_PLUGIN` | **IMPLEMENTED, NOT TESTED** (`P9_PLUGIN` additionally needs registry egress from the emulator; SKIPs with reason otherwise) |
| Phase 9 CI pipeline (`phase9-release.yml` + `00-run-phase9.sh` + `20-gates.sh`) | IMPLEMENTED; static checks PASS locally; **NOT TESTED** end-to-end (install step is a user action) |
| Release APK/AAB + runtime artifacts | **NOT PRODUCED YET** — produced by stage 6 of the pipeline (`assembleRelease`/`bundleRelease`, `runtime-payload-engine.tar.gz`, manifest) and uploaded as `opencode-android-release`. Unsigned unless `P9_KEYSTORE_*` secrets are set. |
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

## 8. What the next run will tell us (and what to do)

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
