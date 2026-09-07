# Phase 7 - Workspace, memory and permissions: end-of-phase report

Branch: `arena/01a07dc2-opencode-app` (sources branched from `9ec40a3`)
Phase plan: this report is the phase's own plan-of-record (Phase 7 had no separate
plan doc; the task is carried in the phase prompt).
Date: 2026-09-07 (implementation + host-side checks complete; device evidence
pending the CI run described in section 5).

**Status: implementation complete, device evidence NOT YET PRODUCED.** Every
piece of Phase 7 is written and passes the host-side static checkers, but no CI
run has happened in this sandbox (there is no JDK, Gradle, Android SDK, `adb` or
KVM here), so nothing in this report is labelled TESTED on a device until the
`phase7` workflow has run. The report is written so the labels can be filled in
from `phase7/out/evidence/GATES_SUMMARY.txt` without rewriting the sections.

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
| Static checkers (ASCII, bracket/comment balance, UI strings, a11y, lazy lists, UI purity, workflow YAML) | **TESTED (host)** | `bash phase7/scripts/30-static-checks.sh` -> `rc=0`; counts in section 3 |
| Kotlin compilation + JVM unit tests | **NOT TESTED** | no JDK/Gradle in this sandbox; the first CI run compiles `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin` and runs `:app:testDebugUnitTest` |
| JVM unit tests (Phase 7 additions) | **NOT TESTED** | `ProjectIoTest`, `ProjectStoreLifecycleTest`, `ProjectMemoryTest`, `ProviderSetupTest` written, never executed here |
| Project lifecycle on the real filesystem (W1) | **NOT TESTED** | `WorkspaceIsolationGatesTest.w1_...` written, not run on a device |
| Workspace isolation through OpenCode's file layer (W2) | **NOT TESTED** | gate written; the server-side mechanism it asserts is pinned to upstream source in section 2 |
| Memory inspect/edit/remove on the device (W3) | **NOT TESTED** | gate written; `ProjectMemory` reads/writes the exact files upstream loads (section 2) |
| Permission bottom sheet (Phase 6 U3) | **TESTED (Phase 6)** | `P6_U3 PASS` in Phase 6 runs #6/#11; re-run as regression this phase |
| Settings permission-policy table + provider/memory sections | **NOT TESTED** | `SettingsScreen` additions compile-checked only; U7 (a11y) re-run covers them |
| Live tool call (L2, Phase 6 carry-over) | **NOT TESTED** | prompt strengthened this phase; still model-behaviour-dependent |
| Phase 5 regression tail | **TESTED (Phase 6), re-run pending** | Phase 6 froze 14 PASS / 1 FAIL (P5-G16 red-by-design); `40-fold-regression.sh` folds it as `P7-R5` |
| Workflow file | **PREPARED, NOT INSTALLED** | `phase7/workflow/phase7-workspace-memory.yml` written; the session token cannot write `.github/workflows/` (403), so a human must copy it (section 5) |

**What has actually run:** the three host-side UI checkers, the ASCII/comment/
balance checkers, and `bash -n` on every Phase 7 script - all green. Nothing else
has executed.

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
| `W2_WORKSPACE_ISOLATION` | `WorkspaceIsolationGatesTest` | model-free: listing project A shows only A's tree; the same relative read resolves inside each project's own directory; a read escaping the directory (`../../`) is refused by the server (HTTP 500, "Path escapes the location") while the file exists |
| `W3_MEMORY_INSPECTABLE_REMOVABLE` | `WorkspaceIsolationGatesTest` | the exact `AGENTS.md` files OpenCode loads exist at the project root and global config dir, round-trip, edit in place, and delete |
| `U3` (regression) | `ChatUiGatesTest` | the permission bottom sheet still accepts once/always/reject and the question ask still submits/skips |
| `U7` (regression) | `ChatUiGatesTest` | the interactive-element accessibility audit, now covering the Phase 7 Projects/Settings additions |
| `L1` (live) | `LiveChatUiGatesTest` | a real turn streams through the UI (unchanged from Phase 6) |
| `L2` (live, Phase 6 carry-over) | `LiveChatUiGatesTest` | a real shell tool call becomes an expandable card; prompt strengthened this phase to make tool use non-optional |
| `R5` (folded) | `40-fold-regression.sh` | the Phase 5 frozen tail (14 PASS / 1 FAIL, P5-G16 red-by-design) re-run on the same device |

W2 SKIPs (never silently passes) when the app-owned server is not answering;
L1/L2 SKIP with `P6_MODEL_AVAILABLE 0 :: reason` when no model can serve a turn.

---

## 5. CI: how a run starts, and the current (empty) log

The session bot token cannot create or modify files under `.github/workflows/`
(403 in Phases 2-5) and cannot POST a `workflow_dispatch`. The canonical workflow
template is committed at **`phase7/workflow/phase7-workspace-memory.yml`**. To
start a run:

1. Copy it to `.github/workflows/phase7-workspace-memory.yml` on branch
   `arena/01a07dc2-opencode-app` (GitHub web "Add file" or a local
   `cp` + `git add` + `git push`), then push.
2. Every push to the branch (ignoring `docs/progress/phase7-evidence/**`) then
   runs: static checks -> payload -> APKs -> fresh `-wipe-data -no-snapshot`
   emulator -> `WorkspaceIsolationGatesTest` -> `ChatUiGatesTest` ->
   `LiveChatUiGatesTest` -> Phase 5 regression tail -> evidence committed to
   `docs/progress/phase7-evidence/`.
3. Bring-up knob: `phase7/CI_GRADLE_ONLY` containing `1` makes a run compile both
   APKs and run the JVM unit tests only (no emulator) - the cheapest way to flush
   out compiler errors before the ~30-minute payload build. It MUST be `0`
   (it is) for any TESTED claim.

**Current CI log: none.** No Phase 7 run has happened. The first run is expected
to fail at compile (as every prior phase's first run did), and its
`compiler-errors.txt` is the artifact to read.

### Defects found and fixed before any CI run

| # | Where | What | Fix |
| --- | --- | --- | --- |
| 1 | `ProjectsScreen.kt` | the decorative `MoreVert` menu icon used `contentDescription = null` without clearing semantics; `check-ui-a11y` flagged it and gate U7 would too | `Modifier.size(24.dp).clearAndSetSemantics { }`, matching the existing `Chevron` convention |
| 2 | `AppRoot.kt` | unused `OpenCodeApi`/`ProviderSetup` imports after wiring | removed |

### Phase 6 carry-overs addressed this phase

* **L2 (no live tool call ever observed).** The prompt is now explicit and
  non-optional ("You must use the bash tool ... answering from memory is not
  allowed") and the command's output is unknowable to the model, so it cannot be
  faked. Still model-behaviour-dependent; W2 provides the phase's model-free
  isolation evidence regardless of whether L2 observes a tool call.
* **"No provider configured" as a first-class state.** `ProviderSetupClassifier`
  + the Settings `ProviderSection` make the key-free-default-provider uncertainty
  explicit (`NO_PROVIDER_CONFIGURED` / `PROVIDER_CONFIGURED` / `CONNECTED`).

---

## 6. Evidence locations (populated by the first CI run)

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

## 7. Next steps

1. Install the workflow (section 5) and run CI once in `CI_GRADLE_ONLY=1` to
   flush out compiler errors cheaply, then flip to `0` for the full device run.
2. Fill in the honesty table from `GATES_SUMMARY.txt` once W1/W2/W3 and the
   regression/live gates have device verdicts.
3. Remaining Phase 8 items (unchanged): secure-hardware key residency, `toybox
   tar` on a real API 29 device, non-emulator arm64 full gate suite, and
   stress/edge-case testing.
