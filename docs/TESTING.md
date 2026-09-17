# Testing

Rule: a green compile is not evidence. Every capability claim is tied to a
gate that ran on a device (emulator or phone) and printed a verdict line, and
the verdict lines are committed as evidence. Unit tests exist, but they only
prove app-side logic.

## Layers

| Layer | Where | Runs | What it proves |
|---|---|---|---|
| Static checks | `phase9/scripts/30-static-checks.sh` (extends Phase 5-8 checkers) | seconds, every CI run, also locally without a JDK | script/YAML/Kotlin lexical sanity, ASCII-only CI files, UI rules (strings, a11y, lazy lists, purity), workflow triggers on this branch, **versions.lock == RuntimeVersion.kt == versionName**, release docs present, no `-android` version define |
| JVM unit tests | `app/src/test` (`./gradlew :app:testDebugUnitTest -PskipPayload`) | ~2 min | state machine, backoff, env, health wire contract, manifest/version pins, tar/extraction failure matrix, SSE accumulator, transcript reducer, error classifier, project store persistence, **`DefaultModelHintTest`** (Phase 9). 272 tests green through Phase 8; Phase 9 adds a handful - count printed as `P9-UNIT` |
| On-device instrumented | `app/src/androidTest` | in the app process, against the real embedded server | Phase 6/7 UI gates (`ChatUiGatesTest`, workspace/memory/permission tests), Phase 8 `StressRecoveryGatesTest` / `LiveToolCallGatesTest`, Phase 9 **`ProviderSelectionGatesTest`** |
| Host-driven device gates | `phase4..9/scripts/*.sh` + `scripts/device/*.js` (run *by the runtime's own Bun on the device*, via `run-as`) | adb against the emulator/phone | server boot, loopback audit, shell/git/rg through the real API, MCP fixtures, destructive gates (kill, corrupt, low storage, network loss, background), perf numbers |

## The pipeline (`phase9/workflow/phase9-release.yml` -> `phase9/scripts/00-run-phase9.sh`)

```
1 static checks ............................ P9-STATIC
2 compile + JVM tests ...................... P9-UNIT
3 embedded payload (pinned upstream) ....... P9-PAYLOAD (+ runtime-payload-engine.tar.gz, manifest as release artifacts)
4 fresh emulator + FULL Phase 8 suite ...... P8_* verdicts (+ P7REG / P5REG folds = Phase 7 and Phase 5 suites)
5 Phase 9 gates ............................ P9_PROVSEL_STALE / _REBUILT / _TURN / _CLEANUP, P9_VERSION, P9_PLUGIN, P9_LOCK
6 assembleRelease + bundleRelease .......... P9-RELEASE (APK + AAB, per-ABI native size)
7 GATES_SUMMARY.txt ........................ every line above; job fails if any P8/P9 gate FAILs or the release build fails
```

Evidence: `phase9/out/evidence/` (uploaded as the `phase9-evidence` artifact
and committed to `docs/progress/phase9-evidence/`), release files in the
`opencode-android-release` artifact. The last workflow step `cat`s the summary
so the verdicts are readable in the Actions log without downloading anything.

The workflow file must be installed by a repository owner (copy to
`.github/workflows/phase9-release.yml` on `arena/01a0a540-opencode-app`); the
automation account cannot write there.

### Reading a verdict

`P8_LARGE PASS :: createMs=14 listMs=163 ...` - `<gate> <PASS|FAIL|SKIP> :: <measurement>`.
SKIP always carries its reason (e.g. no model key, no registry egress) and is
never counted as a pass. Model-dependent gates (`P8_KEYPROBE`, `P8_TOOL`,
`P8_PERF` stream half, `P8_HIST`, `P9_PROVSEL_TURN`) need a **funded**
`OPENROUTER_API_KEY`; without it they SKIP (Phase 9) or FAIL with the
upstream error on record (Phase 8's stricter gates).

## Phase 9 gates in detail

| Gate | Method | Proves |
|---|---|---|
| `P9_PROVSEL_STALE` | provision a dummy `openrouter` key via `PUT /auth/openrouter` **without** dispose, read `GET /provider` | reproduces the carried defect (provider not connected / not visible on a stale instance) or records that this build no longer exhibits it |
| `P9_PROVSEL_REBUILT` | `POST /global/dispose` then `GET /provider` | after invalidation the provider is connected and its models are listed - the fix's mechanism |
| `P9_PROVSEL_TURN` | prompt with an explicit `openrouter/...` model; inspect server log for `llm.provider=` and `ProviderModelNotFoundError` | the turn is routed to the provisioned provider (auth failure with the dummy key is the *expected* outcome and still proves routing); real reply only with a real key |
| `P9_PROVSEL_CLEANUP` | `DELETE /auth/openrouter`, dispose, re-read | no credential left behind |
| `P9_VERSION` | `GET /global/health` `version` == `versions.lock` opencode version | the bare version define (plugin fix) shipped |
| `P9_PLUGIN` | seed `@opencode-ai/plugin@<lock>` present in `xdg/config/opencode/node_modules`; in a 150 s window +0 `dependency install failed` and +0 `code=159`; SIGSYS forensics (`type=1326`) captured if any 159 is seen | the pre-seeded plugin tree is used and the on-device npm path (which SIGSYS-crashed Bun in run 1) is not entered |
| `P9_LOCK` | `check-lock.py --require-manifest` | shipped manifest == versions.lock == app constants == versionName |
| `P9-RELEASE` | gradle release build; sizes; signing state | release artifacts exist; unsigned unless `P9_KEYSTORE_*` secrets are set |

Also folded in from Phase 8 (`LiveToolCallGatesTest` g01): the key-provision
step now disposes the instance and emits `P8_PROVSTATE` so the live-model
gates start from a rebuilt provider table.

## Coverage matrix (what has actually run where)

| | Emulator x86_64 API 34 (CI) | Phone arm64 API 35 (Realme RMX3830) | API 29-33 |
|---|---|---|---|
| Boot / health / loopback / auth | TESTED (every run since Phase 4) | TESTED (8 runs, Phase 8) | NOT TESTED |
| File/shell/git/rg through the API | TESTED | TESTED (subset R3/R6) | NOT TESTED |
| stdio MCP | TESTED | - | - |
| Remote MCP | FAIL by design (#47644) | - | - |
| Crash/corrupt/low-storage/large | TESTED | SERVERKILL, LIFECYCLELOG TESTED | - |
| Session persistence | TESTED (PASS since round 18) | - | - |
| Live model turn | BLOCKED (no credit / egress condition) | PASS once (run 1, free model), then no credit | - |
| Real tool card (L2) | never observed | never observed | - |
| Toybox tar harness staging | TESTED (34) | TESTED (35) | NOT TESTED |
| Phase 9 `P9_VERSION`, `P9_PLUGIN`, `P9_LOCK`, `P9-RELEASE` | **PASS** (runs 35201496822, 35238052238; zero exit-159) | NOT RUN | - |
| Phase 9 `P9_PROVSEL_STALE/REBUILT/TURN/CLEANUP` | **PASS** (run 35238052238: defect reproduced, dispose rebuilds table, turn reaches `openrouter`) | NOT RUN | - |

## Phase 8 numbers carried into this release

Cold start (emulator, fresh install incl. extraction) ~9.6 s; warm supervisor
window median ~2.5 s; phone first-launch 13-21.8 s, server start ~6.4 s.
Session create/list/status 14-19 / 5-6 / 8-15 ms; 2000-file list 163-171 ms;
shell op ~420-610 ms; git init+add+commit on 2000 files ~1 s. App PSS ~88 MB
(phone). Server-process memory and steady-state CPU: NOT MEASURED. Full table:
`docs/progress/phase8-hardening-testing-report.md` §6.

## Running locally

```
bash phase9/scripts/30-static-checks.sh              # no JDK needed
./gradlew :app:testDebugUnitTest -PskipPayload       # JVM
bash phase4/scripts/10-build-payload.sh              # payload (Linux, NDK 28.2)
bash phase4/scripts/02-boot-emulator.sh              # or attach a phone with USB debugging
bash phase8/scripts/00-run-phase8.sh                 # full Phase 8 suite (Phase 5/7 folded)
bash phase9/scripts/20-gates.sh                      # Phase 9 gates on the same device
bash phase9/scripts/00-run-phase9.sh                 # everything + release artifacts
```

Real-phone variant of the Phase 8 suite: `phase8/scripts/90-real-device-suite.sh`
(takes the model key interactively; never logs it).
