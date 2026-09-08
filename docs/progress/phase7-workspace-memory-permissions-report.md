# Phase 7 - Workspace, memory and permissions: end-of-phase report

Branch: `arena/01a07dc2-opencode-app` (sources branched from `9ec40a3`; workflow
installed by hand as `.github/workflows/phase7-workspace-memory.yml` in commit
`809dff1`)
Phase plan: this report is the phase's own plan-of-record (Phase 7 had no separate
plan doc; the task is carried in the phase prompt).
Date: 2026-09-08 (device evidence through commit `8cf7a71`)
Device evidence: five CI runs; the confirming run is **34171137692** - workflow
conclusion **success**, `ui_gates_pass=7 ui_gates_fail=0 ui_gates_skip=1` on a
fresh `-wipe-data -no-snapshot` `sdk_gphone64_x86_64` emulator, Android 14 /
API 34 / x86_64, with the pinned key-free default model serving a real turn
through the UI.

**Stop condition met.** Workspace isolation (W2) is proven at OpenCode's own file
layer on the device - not a UI-level filter - and the memory layer (W3) is proven
inspectable, editable and removable on the device. Project lifecycle (W1), the
permission bottom sheet (U3), accessibility across the new screens (U7), a live
turn (L1) and the Phase 5 regression tail (R5) are all green. The single SKIP
(L2) is the Phase 6 carry-over live-tool-call observation, still
model-behaviour-dependent and reported as SKIP by design.

Scope discipline: Phase 7 is **user-facing experience on top of the real OpenCode
runtime** - projects/workspace management, the permission model surfaced
mobile-first, OpenCode's own `AGENTS.md` memory made inspectable/editable/
removable, and Settings. No agent loop, no tool, no server API and no permission
policy is reimplemented in Kotlin; workspace isolation is proven through OpenCode's
own file layer, not by a UI-level filter the app invented.

---

## 0. Honesty table (read this first)

| Item | Label | Evidence |
| --- | --- | --- |
| Static checkers (ASCII, bracket/comment balance, UI strings, a11y, lazy lists, UI purity, workflow YAML) | **TESTED (host + CI)** | `bash phase7/scripts/30-static-checks.sh` -> `rc=0` in this sandbox and on every CI run; counts in section 3 |
| Kotlin compilation + JVM unit tests | **TESTED (CI)** | `:app:compileDebugKotlin` + `:app:compileDebugAndroidTestKotlin` + `:app:testDebugUnitTest` green since run #3; **187 JVM tests, 0 failures** |
| JVM unit tests (Phase 7 additions) | **TESTED (CI)** | `ProjectIoTest`, `ProjectStoreLifecycleTest`, `ProjectMemoryTest`, `ProviderSetupTest` all green (one test-side bug and one product bug found and fixed in runs #1/#2, section 5) |
| Project lifecycle on the real filesystem (W1) | **TESTED on device** | `P7_W1_PROJECT_LIFECYCLE PASS` (runs #4 and #5): create -> rename -> adopt -> delete on the real app filesystem |
| Workspace isolation through OpenCode's file layer (W2) | **TESTED on device** | `P7_W2_WORKSPACE_ISOLATION PASS`: `seesOtherProject=false seesOutside=false readOwnA=true readOwnB=true escapeRefused=true` - the escaping read was refused by the server while the file existed |
| Memory inspect/edit/remove on the device (W3) | **TESTED on device** | `P7_W3_MEMORY_INSPECTABLE_REMOVABLE PASS`: both `AGENTS.md` files (project root + global config dir) written, round-tripped, edited in place, and deleted |
| Permission bottom sheet (U3) | **TESTED on device** | `P6_U3 PASS` (`answered=true skipped=true scrolledIntoView=true`) - the asks now render in a bottom sheet (Phase 7 change); run #4 caught the gate/sheet incompatibility, fixed in run #5 |
| Settings permission-policy table + provider/memory sections (U7) | **TESTED on device** | `P6_U7 PASS`: `interactive={chat=23, sessions=11, projects=6, welcome=2, welcome-unsupported=2, settings=5} total=49 unnamed=0` |
| Live turn through the UI (L1) | **TESTED on device** | `P6_L1 PASS`: `replyShownInUi=true(needle='Blue')`, verified against the server's own messages |
| Live tool call (L2, Phase 6 carry-over) | **NOT TESTED - SKIP by design** | the model answered without calling a tool within 420s even with the strengthened prompt; the class reports SKIP, never PASS |
| Phase 5 regression tail | **TESTED on device** | `P7-R5: PASS` - `phase5=14pass/1fail/0skip kotlin=10pass/0fail`, only the documented upstream restriction P5-G16 red |
| Workflow file | **INSTALLED** | copied to `.github/workflows/phase7-workspace-memory.yml` by hand (the session token gets 403 there); canonical copy at `phase7/workflow/` |

**What has actually run:** five CI runs (#1 compile fail, #2 JVM test fail, #3
gradle-only green, #4 full run with only U3 red, #5 full run green). The
confirming run #5 produced the verdicts above on a fresh emulator.

---

## 1. What Phase 7 implements

### 1.1 Projects / workspace management

`projects/ProjectStore.kt` grows from Phase 6's list/create/open into the full
lifecycle, all on top of app-private storage (`files/workspaces/<name>`):

* **create** (Phase 6) - sanitised name, collision suffix, becomes the OpenCode
  instance directory (`OpenCodeApi.directory`).
* **rename** - `ProjectIo.renameDir` moves the directory, the preference history
  and (only when it pointed at the project) the active pointer. Sessions are
  deliberately NOT rewritten: OpenCode stores a session's directory at creation
  time and this app has no endpoint to move them (and must not invent one).
* **delete** - server-side session cleanup first
  (`repository.deleteSessionsForDirectory`, which lists `GET /session?directory=`
  and DELETEs each id), then `ProjectIo.deleteTree` and the preference history.
* **adopt** - registers a directory an import copied into place without
  re-suffixing its name.
* **import/export** - `projects/SafProjectTransfer.kt` uses the Storage Access
  Framework (`OpenDocumentTree` in, `CreateDocument` zip out). Import is capped
  at 512 MiB, refuses an already-existing target, and delegates capped writes to
  `ProjectIo.writeStream`; export writes a temp zip and streams it to the picker
  target. SAF is used for transfer only - the agent never sees the whole Android
  filesystem, only the controlled workspace.
* **history** - session history was already Phase 6 (the server's session list,
  per-directory); the Projects screen now shows a per-project session count from
  the server's own `listSessions` grouped by directory.

`projects/ProjectIo.kt` is the pure filesystem layer (Android-free, JVM-tested):
`renameDir`, `deleteTree`, `sizeOf`, `copyTree` with a byte cap + symlink skip +
`TooLarge`, `zipTree`, `writeStream`.

### 1.2 Workspace isolation (the phase's acceptance criterion)

The deliverable is not "the UI hides other projects" but "the agent's file access
is restricted to the project directory". The mechanism is OpenCode's own:

* `GET /file?path=&directory=<dir>` lists only `<dir>`'s tree. The handler
  (`packages/opencode/src/server/routes/instance/httpapi/groups/file.ts`, pinned
  commit `05ea5073`) scopes `fs.list` to the instance directory and resolves
  entries with `path.resolve(location.directory, item.path)`.
* `GET /file/content?path=<rel>&directory=<dir>` computes
  `path.resolve(directory, path)` and then
  `if (!FSUtil.contains(directory, file)) return Effect.die(new Error("Path escapes the location"))`
  - a read that would escape the project directory is refused by the server
  (HTTP 500), even when the target file exists and is OS-readable.
* `FSUtil.contains(parent, child)` is `relative(parent, child)` not absolute and
  not starting with `..` (`packages/core/src/fs-util.ts`).
* The `directory` value is how the server selects the instance: the
  `WorkspaceRoutingMiddleware` local branch uses
  `url.searchParams.get("directory") || request.headers["x-opencode-directory"] || process.cwd()`.
  The app sends `?directory=` on every instance route (the same mechanism Phase 5
  and 6 already use for sessions).

Gate W2 proves this on device without betting on a model: two project directories
are created, and the gate asserts through the app's own `OpenCodeApi` that (a)
listing A shows A's file and neither B's nor an outside file, (b) the same
relative read resolves inside each project's own tree, and (c) a
`../../<outside file>` read is refused with status 500 while the file exists.

### 1.3 Memory (OpenCode's own mechanism, not a new one)

Upstream has no bespoke "memory tool" API - memory *is* rules files
(`AGENTS.md`/`CLAUDE.md`), loaded into every session's context by
`packages/opencode/src/session/instruction.ts`. `memory/ProjectMemory.kt` reads
and writes exactly those files, nothing else:

* project scope: `files/workspaces/<name>/AGENTS.md` (the project root);
* global scope: `$XDG_CONFIG_HOME/opencode/AGENTS.md`
  (`RuntimePaths.xdgConfigOpencode`).

Both live in app-private storage as plain markdown, so they are inspectable
(shown verbatim in Settings), editable (the editor rewrites in place), scoped
(one file per project plus one global file - exactly OpenCode's two scopes),
removable (blank save or Remove deletes the file), and privacy-conscious (nothing
is sent anywhere; OpenCode only reads these into the model's context on this
device). The `syncMemoryText` repository method was deliberately removed rather
than refined: memory access belongs in `ProjectMemory`/`AppContainer.memory()`,
not in the repository.

### 1.4 Permissions (bottom sheet kept; standing policy added)

Phase 6 already rendered OpenCode's permission asks as touch-sized
once/always/reject cards above the composer (`PermissionAsk`, gate U3 TESTED) -
the mobile-friendly bottom sheet, keeping OpenCode's model intact. Phase 7 adds
the standing policy to Settings: `permission.<key> = ask|allow|deny` for
`bash`/`edit`/`read`/`webfetch`/`external_directory`, patched through OpenCode's
own `PATCH /global/config` (`repository.setPermissionPolicy`) and re-read so the
table reflects what the server actually stored. An absent key is upstream's own
"ask" default and is not invented. The per-prompt bottom sheet remains the
authority for what a running turn may do.

### 1.5 Settings

`ui/settings/SettingsScreen.kt` adds three sections to the Phase 6 screen:

* **ProviderSection** - "is a model actually configured" as a first-class
  labelled state, from the pure `ProviderSetupClassifier`
  (`NO_PROVIDER_CONFIGURED` / `PROVIDER_CONFIGURED` / `CONNECTED` / `UNKNOWN`),
  derived only from `GET /provider` connected list + Keystore-stored ids. This is
  the Phase 6 carry-over "key-free default provider isn't always connected" made
  explicit rather than an empty field.
* **PermissionsSection** - the policy table above.
* **MemorySection** - the `AGENTS.md` editors above.

Provider/credential config (Phase 5) and runtime diagnostics (Phase 4) remain
surfaced exactly as before.

---

## 2. Upstream grounding (pinned commit `05ea5073`, verified this session)

* `packages/opencode/src/server/routes/instance/httpapi/groups/file.ts` - the
  `FileQuery` spreads `WorkspaceRoutingQueryFields` (so `path` and `directory` are
  both query params), and the `content` handler refuses escapes with
  `FSUtil.contains` -> `"Path escapes the location"`.
* `packages/core/src/fs-util.ts` - `contains(parent, child) =
  relative(parent, child) === "" || (!isAbsolute && !== ".." && !startsWith(".."+sep))`.
* `packages/opencode/src/server/routes/instance/httpapi/middleware/workspace-routing.ts`
  - local-workspace directory resolution order (query param, header, cwd).
* `packages/core/src/location.ts` + `project.ts` - `Location` resolves the
  *project* via `git.repo.discover` and falls back to `ID.global` / filesystem
  root when there is no git repo; the *instance* directory (`location.directory`)
  is what scopes `fs.list`. The app does not `git init` projects (local-only git,
  `NO_CURL`/`NO_OPENSSL`, a Phase 5 characteristic); the file-layer boundary in
  W2 depends on the instance directory, not on git.
* `packages/opencode/src/session/instruction.ts` - global rules path
  `path.join(global.config, "AGENTS.md")`; project rules `globUp` for `AGENTS.md`
  then `CLAUDE.md`. `ProjectMemory` writes `AGENTS.md` at exactly these paths.

---

## 3. Rule compliance (host-side checker output)

`bash phase7/scripts/30-static-checks.sh` -> **rc=0** (run in this sandbox):

| Rule | Checker | Result |
| --- | --- | --- |
| No hardcoded copy, no URL literals in UI, every `R.string.*` exists | `check-ui-strings.py` + `.sh` | 53 kotlin files, 0 hardcoded-copy findings, 0 URL findings; all new Phase 7 strings present in `strings.xml` |
| Every meaningful interactive element has a real accessible name | `check-ui-a11y.py` | 17 files, 17 icon/image + 50 button + 10 field call sites, **0 findings** (one defect found and fixed this phase: the decorative `MoreVert` menu icon in `ProjectsScreen` now `clearAndSetSemantics`) |
| Unbounded lists use real lazy rendering | `check-ui-lists.py` | 17 files, 4 using `LazyColumn`, **0 findings** |
| Screens are pure functions of state | `check-ui-purity.py` | 17 ui files, **0 findings** - only `AppRoot` touches singletons |
| Kotlin bracket / nested-block-comment hazards | `check-kotlin-balance.py`, `check-kotlin-comments.py` | 77 files, 0 findings, 0 block-comment anomalies |
| CI-facing files ASCII; workflow triggers on this branch | `check-ascii.py`, manual | 0 findings; `phase7/workflow/phase7-workspace-memory.yml` ASCII-clean, no unquoted `: ` step names, triggers on `arena/01a07dc2-opencode-app` |
| `bash -n` on every Phase 7 script | manual | 4/4 OK |

---

## 4. Gate inventory (Phase 7 gates + regression + live carry-over)

All gates are in `app/src/androidTest/`. Verdict discipline is Phase 5's: each
gate prints `P7_<id> PASS|FAIL|SKIP :: detail` (or the existing `P6_` ids for the
re-run classes) to logcat, stdout **and** an on-device verdict file; the collector
folds the deduplicated union, so an absent gate counts as FAIL.

| Gate | Class | Asserts |
| --- | --- | --- |
| `W1_PROJECT_LIFECYCLE` | `WorkspaceIsolationGatesTest` | create -> rename (dir moves, old gone) -> adopt (kept file survives) -> delete (dir gone) on the real filesystem |
| `W2_WORKSPACE_ISOLATION` | `WorkspaceIsolationGatesTest` | model-free: listing project A shows only A's tree; the same relative read resolves inside each project's own directory; a read escaping the directory (`../../`) is refused by the server (HTTP 500, upstream's generic "UnknownError / Unexpected server error" wrapper around the `FSUtil.contains` guard) while the file exists |
| `W3_MEMORY_INSPECTABLE_REMOVABLE` | `WorkspaceIsolationGatesTest` | the exact `AGENTS.md` files OpenCode loads exist at the project root and global config dir, round-trip, edit in place, and delete |
| `U3` (regression) | `ChatUiGatesTest` | the permission bottom sheet still accepts once/always/reject and the question ask still submits/skips |
| `U7` (regression) | `ChatUiGatesTest` | the interactive-element accessibility audit, now covering the Phase 7 Projects/Settings additions |
| `L1` (live) | `LiveChatUiGatesTest` | a real turn streams through the UI (unchanged from Phase 6) |
| `L2` (live, Phase 6 carry-over) | `LiveChatUiGatesTest` | a real shell tool call becomes an expandable card; prompt strengthened this phase to make tool use non-optional |
| `R5` (folded) | `40-fold-regression.sh` | the Phase 5 frozen tail (14 PASS / 1 FAIL, P5-G16 red-by-design) re-run on the same device |

W2 SKIPs (never silently passes) when the app-owned server is not answering;
L1/L2 SKIP with `P6_MODEL_AVAILABLE 0 :: reason` when no model can serve a turn.

---

## 5. CI run log (five runs, two defects, one gate/sheet incompatibility)

The workflow is installed at `.github/workflows/phase7-workspace-memory.yml`
(commit `809dff1`, added by hand - the session token gets 403 on writes under
`.github/workflows/`). Every push to the branch runs the whole suite; evidence
lands in `docs/progress/phase7-evidence/` on every exit path.

| # | Run | Commit | Outcome |
| --- | --- | --- | --- |
| 1 | 34168504135 | workflow file install | **FAIL at compile** (4 errors, all Phase 7 code) |
| 2 | 34169005955 | compile fixes + gradle-only | **FAIL at JVM tests** (1 product bug, 1 test bug) |
| 3 | 34169371879 | JVM fixes + gradle-only | **GREEN**: compile + 187 JVM tests, 0 failures |
| 4 | 34169687565 | full run (CI_GRADLE_ONLY=0) | 6 PASS / 1 FAIL (U3) / 1 SKIP (L2) - everything green except the U3 gate vs the new bottom sheet |
| 5 | 34171137692 | U3 fix | **GREEN**: 7 PASS / 0 FAIL / 1 SKIP. Stop condition met |

### Run #1 - compile errors

`P7-BUILD FAIL kotlin compile or JVM unit tests failed`. Four errors, all in
Phase 7 code I wrote, all fixed in the next commit:

| # | Error | Cause | Fix |
| --- | --- | --- | --- |
| 1 | `SafProjectTransfer.kt:62 Unresolved reference: openInputStream` | `DocumentFile` has no `openInputStream` - that is a `ContentResolver` method | `context.contentResolver.openInputStream(child.uri)` |
| 2 | `AppRoot.kt:165 Unresolved reference: value` | `exportTarget` is a `by`-delegated property; `.value` on it does not exist | drop `.value` |
| 3,4 | `AppRoot.kt:382/402 'if' must have both main and 'else' branches if used as an expression` | the memory write/remove `if/else if` chains are the **last expression** of a `withContext { }` lambda, so Kotlin parses them as expressions | an explicit `else -> Unit` arm |

### Run #2 - JVM unit-test failures

`P7-BUILD FAIL gradle compile/unit tests failed` - the sources now compiled, and
`testDebugUnitTest` ran. Two failures in `ProjectIoTest`, one product, one test:

| # | Failure | Verdict | Fix |
| --- | --- | --- | --- |
| 5 | `copyTreeEnforcesTheByteCapAndLeavesNothingPartial`: `partial target must not exist` | **product bug.** `copyTree`/`writeStream` threw `TooLarge` but left the partial/empty destination file behind - a failed import would leave a phantom half-copied workspace that `ProjectStore.projects()` discovers | both now delete the partial destination before `TooLarge` propagates, and `SafProjectTransfer.importTree` deletes the destination workspace on any failure; new test `writeStreamRemovesThePartialFileWhenTheCapIsBreached` pins it |
| 6 | `zipTreeRoundTripsFiles`: `FileNotFoundException: .../empty (Is a directory)` | **test bug.** the fixture wrote `writeText` into a path that had just been `mkdirs()`ed | the empty directory is now created directly, without the write |

### Run #3 - gradle-only green

`P7_BUILD pass` (`compileDebugKotlin` + `compileDebugAndroidTestKotlin` +
`testDebugUnitTest`) with **187 JVM tests, 0 failures**; `P7_STATIC pass`.

### Run #4 - full run: everything green except U3

`ui_gates_pass=6 ui_gates_fail=1 ui_gates_skip=1`. W1, W2, W3, U7, L1 all PASS
and `P7-R5` PASS (Phase 5 at `14pass/1fail`, only P5-G16) on the first full run -
the phase's core acceptance evidence held. U3 FAILED with
`Semantic Node has no parent layout with a Scroll SemanticsAction` at
`ChatUiGatesTest.kt:820`.

That is the one real Phase 7 consequence this report has to be honest about: the
permission/question asks were moved into a `ModalBottomSheet` (the phase's
"mobile-friendly bottom sheet" requirement), and the gate still did
`performScrollTo()` - a Phase 6 fix for the asks being *below the transcript's
viewport*. A bottom sheet's content column is not scrollable, so the call threw.
It also exposed a genuine product gap: with a permission ask and a question
stacked, a non-scrollable sheet would clip the second ask. Fixed in run #5 by
restoring `verticalScroll` to the sheet content (Phase 6 behaviour) and guarding
the gate's `performScrollTo` so it reports instead of aborting.

### Run #5 - GREEN: 7 PASS / 0 FAIL / 1 SKIP

`ui_gates_pass=7 ui_gates_fail=0 ui_gates_skip=1`, `screenshots=11`,
`model_available=1`, `device_abi=x86_64`, `android_sdk=34`, all three instrument
classes `rc=0`, `P7-R5: PASS` at Phase 5's frozen steady state. The confirming
verdicts:

| Gate | Verdict | Detail |
| --- | --- | --- |
| `W1_PROJECT_LIFECYCLE` | PASS | `created/renamed/adopted/delete=true` on the real filesystem |
| `W2_WORKSPACE_ISOLATION` | PASS | `listA=1 seesOwn=true seesOtherProject=false seesOutside=false readOwnA=true readOwnB=true escapeRefused=true` - the escape read returned upstream's own 500 error body while the file existed |
| `W3_MEMORY_INSPECTABLE_REMOVABLE` | PASS | project `AGENTS.md` at `files/workspaces/.../AGENTS.md` and global `AGENTS.md` at `files/xdg/config/opencode/AGENTS.md`, both round-tripped and removed |
| `U3` | PASS | `answered=true skipped=true answersSeen=[(que_gate1, [[staging]])] submitClicks=1 scrolledIntoView=true` |
| `U7` | PASS | `interactive={...} total=49 unnamed=0` |
| `L1` | PASS | `replyShownInUi=true(needle='Blue')` - a real turn through the UI |
| `L2` | SKIP | the model answered without calling a tool within 420s (by design; see below) |
| `R5` | PASS | `phase5=14pass/1fail/0skip` - only the documented upstream restriction P5-G16 red |

### Phase 6 carry-overs, final state this phase

* **L2 (no live tool call ever observed).** The prompt was strengthened to make
  tool use explicit and non-optional, and run #5's model still answered without
  calling one. It stays SKIP by design. Deterministic tool-card coverage is U2
  (Phase 6, still green); observing a *live* tool call is carried to Phase 8 as a
  prompt/model-selection task, not a UI task.
* **"No provider configured" as a first-class state.** Implemented
  (`ProviderSetupClassifier` + Settings `ProviderSection`); the state is a pure
  classifier pinned by `ProviderSetupTest`, and its UI is covered by U7's a11y
  audit (named, so the empty state is never a blank field).

---

## 6. Evidence locations (populated by runs #1-#5)

| Path | Contents |
| --- | --- |
| `docs/progress/phase7-evidence/GATES_SUMMARY.txt` | `P7_SUMMARY`, pass/fail/skip counts, per-class instrument rc, screenshots, `model_available`, device abi + sdk |
| `docs/progress/phase7-evidence/p7-ui-lines.txt` | every `P6_`/`P7_` verdict line, deduplicated |
| `docs/progress/phase7-evidence/p7-{isolation,chat-ui,live-chat}-instrument.log` | raw `am instrument` output per class |
| `docs/progress/phase7-evidence/p7-{isolation,chat-ui,live-chat}-verdicts.txt` | the on-device verdict files read back with `run-as` |
| `docs/progress/phase7-evidence/phase5-regression/` | the whole Phase 5 evidence bundle from the regression tail |
| `docs/progress/phase7-evidence/{runtime.log,opencode-server.log,logcat-OpenCode.txt}` | the app's runtime log, the embedded server's log, filtered logcat |
| `docs/progress/phase7-evidence/jvm-unit-tests/` | `:app:testDebugUnitTest` XML |
| `docs/progress/phase7-evidence/p7-static-checks.log` | the checker output quoted in section 3 |
| `docs/progress/phase7-evidence/screenshots/` | PNGs captured on device |

---

## 7. Next steps (Phase 8)

Phase 7 is complete and TESTED on one device (x86_64 emulator, API 34). What
carries forward, all out of Phase 7 scope:

1. **L2 live tool call** - never observed (model behaviour, not UI). Try a prompt
   that reliably provokes a tool call, or a model that uses tools more eagerly.
2. **Device coverage** - secure-hardware key residency, `toybox tar` on a real
   API 29 device, and a non-emulator arm64 full gate suite remain unverified.
3. **Stress / edge cases** - Phase 8 scope: concurrent import/rename/delete races,
   very large trees, malformed zips on import, permission-policy edge values.
4. **Remote HTTP/SSE MCP** - stays documented-red (upstream `anomalyco/opencode#47644`),
   surfaced verbatim in Settings.

A repeat full run (e.g. to reconfirm on a second boot) is a single push to the
branch; the workflow is installed and triggers automatically.
