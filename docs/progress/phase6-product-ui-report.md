# Phase 6 - Product UI: end-of-phase report

Branch: `arena/01a077b3-opencode-app` (sources `e1104ee`; workflow installed by
hand as `f8eca14`; CI evidence `8d3a62b`/`cb7d8fc`/`cbbbc59`)
Phase plan: `docs/progress/phase6-ui-polish-plan.md`
Date: 2026-09-06
Scope discipline: Phase 6 is **presentation only**. No agent loop, no tool, no
server API and no OpenCode behaviour was reimplemented in Kotlin; every visible
fact is read from `OpenCodeApi`, `OpenCodeEventStream`, `Transcript` or
`RuntimeManager.diagnostics()`.

---

## 0. Honesty table (read this first)

| Item | Label | Evidence |
| --- | --- | --- |
| Static checkers (ASCII, bracket/comment balance, UI strings, a11y, lazy lists, UI purity, workflow YAML) | **TESTED (host)** | `bash phase6/scripts/30-static-checks.sh` -> `rc=0`; see section 3 for the exact counts |
| Product UI sources (17 files, ~5,800 lines) | **IMPLEMENTED** | compiled by CI, not yet by a device run |
| JVM unit tests (6 new test files) | **IMPLEMENTED, NOT TESTED** | no JDK/Gradle in this sandbox; they run in CI as `:app:testDebugUnitTest` |
| Instrumented gate harness (3 classes, 14 gates) | **IMPLEMENTED, NOT TESTED** | needs an emulator |
| Kotlin compile of main + androidTest sources | **FAILED on CI run #1, defects fixed, re-run pending** | `docs/progress/phase6-evidence/compiler-errors.txt` from run 34096781049; see section 5 |
| First-run flow on a fresh emulator (F1-F4) | **NOT TESTED** | this is the phase's stop condition; the suite never reached the emulator on run #1 |
| Deterministic chat/tool-card/permission/degraded-state gates (U1-U8) | **NOT TESTED** | same |
| Live turn through the UI (L1-L2) | **NOT TESTED** | also needs the pinned key-free default model to serve a turn; the class reports `SKIP` (never `PASS`) when no model can answer |
| Screenshots | **NOT CAPTURED YET** | the harness writes PNGs into the app's own `filesDir/screenshots`; `phase6/scripts/20-ui-gates.sh` pulls them with `run-as` + base64 (adb pull cannot read `/data/data`) |

**What has actually run:** the GitHub credential this session uses expired
mid-phase (`gh api` -> `HTTP 401: Bad credentials`) and was reconnected; the
Phase 6 workflow was then installed by hand as `.github/workflows/phase6-ui.yml`
(commit `f8eca14`, "Add Phase 6 UI gates workflow") because the session token
gets `403` on any write under `.github/workflows/`. Run **34096781049** executed
the static checks (green) and then failed at step 2/8, `:app:compileDebugKotlin`,
in 1m37s - three real defects, listed with their fixes in section 5. It never
reached the payload build, the emulator or any gate.

Everything labelled IMPLEMENTED is source-complete and static-checked. Nothing is
claimed to work on Android yet: no device verdict and no screenshot exists.

---

## 1. Screens and flows implemented

Routing lives in one place, `ui/AppRoot.kt`
(`welcome -> projects -> chat`, plus `sessions` and `settings` panels). It is the
only file in `ui/` allowed to touch singletons - `check-ui-purity` enforces that.

| Screen | File | What it shows | Where the facts come from |
| --- | --- | --- | --- |
| Welcome / first run | `ui/welcome/WelcomeScreen.kt` | Stage title + body for each of the 7 supervisor states (EXTRACTING, STARTING, HEALTHY, CRASHED_RESTARTING, STOPPED, FATAL, UNSUPPORTED_DEVICE), a progress bar while the payload is extracted, "Continue" (enabled only when ready), "Settings & diagnostics" | `RuntimeSummary` (status/detail/restartCount/availability/opencodeVersion/payloadVersion/abi/device/androidVersion/audit) |
| Projects | `ui/projects/ProjectsScreen.kt` | Existing workspaces (name + path), empty state, create-a-project field with live sanitisation, open, runtime line (version only) | `projects/ProjectStore` + `RuntimeSummary.opencodeVersion` |
| Chat | `ui/chat/ChatScreen.kt`, `ui/chat/ChatComponents.kt` | Lazy transcript, streaming indicator, stop, composer with attachments, status area, permission/question asks pinned above the composer, jump-to-latest, retry / undo / redo, new session, open sessions, open projects, open settings | `OpenCodeRepository.UiState` (transcript, streaming, streamStatus, sessionStatus, busy, error/errorKind, serverReachable, notice, attachments, draft) |
| Sessions | `ui/chat/SessionPanel.kt` | Lazy session list with title/updated/pending/busy markers, select, new, rename, delete, revert note | `UiState.sessions` (`OpenCodeApi.SessionInfo`), `UiState.selectedSession`, `Transcript` |
| Settings & diagnostics | `ui/settings/SettingsScreen.kt` | Runtime restart, Phase 4/5 diagnostics (refresh/copy/share), model keys per provider (masked entry, save, revoke), model picker, MCP list + add/connect/disconnect, bash permission policy, theme (dynamic/dark/light/system) | `RuntimeManager.diagnostics()`, `OpenCodeApi.providers()/mcpEntries()/patchGlobalConfig()` |
| Markdown + code | `ui/markdown/Markdown.kt`, `MarkdownText.kt`, `CodeHighlight.kt` | Headings, lists, emphasis, inline code, links, fenced code blocks with per-language token colours, copy button, horizontal rules | `Transcript.Part` text, rendered client-side from the server's own text |
| Attachments | `ui/Attachments.kt` | `content:` URIs copied into `filesDir/attachments` and handed to the server as upstream `FilePartInput` (`file:` URL); chips with size + remove | `OpenCodeApi.Attachment` |
| Shared widgets | `ui/common/Widgets.kt`, `RuntimeSummary.kt`, `UiPrefs.kt` | Availability banner, buttons, fields, cards, section headers | `client/UiError.AgentAvailability` |
| Theme | `ui/theme/{Color,Type,Theme}.kt` | Own palette, type scale, dynamic-colour support | - |

**First-run promise (implemented, awaiting device proof):** install the APK ->
open it -> welcome screen explains what is happening in human language -> the
runtime extracts and starts by itself in the background -> "Continue" enables ->
create or open a project -> conversation screen with a usable composer. There is
no terminal, no URL, no port, no Termux, no SSH and no `adb` anywhere in the
user-visible surface, and `F1`/`F2`/`U4` assert that against a forbidden-token
list built from the app's own runtime constants (not a hand-written list).

---

## 2. Agentic behaviour is surfaced, not collapsed

The UI deliberately does **not** reduce OpenCode to "send prompt -> receive
text":

* **Tool calls are first-class chat rows.** Every `tool` part becomes an
  expandable card tagged `tool_card_<partId>` with a header
  (`tool_header_<partId>`) whose headline is "Shell command . bash"-style text
  derived from `client/ToolMeta.kt`, and an output body
  (`tool_output_<partId>`). Cards default to expanded only when the tool's state
  is `error`, so failures are never hidden behind a tap.
* **Permission asks keep OpenCode's own model**: once / always / reject
  (`permission_ask_<id>` + `permission_once|always|reject`), rendered as
  touch-sized buttons above the composer, because upstream blocks the turn until
  they are answered.
* **Question asks** (`question_ask_<id>` + `question_submit|skip`) render
  upstream's option lists; submit is enabled only when every item has an answer.
* **Reasoning parts** (`reasoning_<partId>`) are shown separately from answer
  text.
* **Streaming, stop, retry, undo, redo**: `streaming_indicator`, `composer_stop`
  (abort), `message_retry`/`message_undo` (upstream's `revert`), plus a redo path
  (`unrevert`). Retry is revert-then-re-prompt, exactly as the TUI does it;
  nothing is invented client-side.
* **Session status** (`busy`, `retry` with attempt count and next retry time) is
  read from `sessionStatus()` and shown as the busy bar / retry banner rather
  than guessed from a spinner.
* **Runtime status and Phase 4 logs** are one tap away in Settings -> diagnostics
  (refresh / copy / share), so the embedded runtime is observable without being
  exposed as a terminal.

---

## 3. Rule compliance (with the checker output that backs it)

`bash phase6/scripts/30-static-checks.sh` -> **rc=0**:

| Rule | Checker | Result |
| --- | --- | --- |
| No hardcoded copy, no URL literals in UI, every `R.string.*` exists | `check-ui-strings.py` + `.sh` | 49 kotlin files, 0 hardcoded-copy findings, 0 URL findings; strings.xml: 263 defined, 236 referenced, **0 missing** |
| Every meaningful interactive element has a real accessible name | `check-ui-a11y.py` | 17 files, 16 icon/image + 40 button + 8 field call sites, **0 findings** (39 `contentDescription` sites in `ui/`); gate `U7` re-checks this at runtime against the semantics tree |
| Unbounded lists use real lazy rendering | `check-ui-lists.py` | 17 files, 4 using `LazyColumn`, **0 findings** (no `take(N)`, no eager `forEach` over messages/sessions/pending); gates `U1`/`U8` count composed rows at both ends of a 602-message transcript |
| Screens are pure functions of state (thin client) | `check-ui-purity.py` | 17 ui files, **0 findings** - only `AppRoot` touches singletons, no `java.net`/`okhttp` in `ui/` |
| Kotlin bracket / nested-block-comment hazards (Phase 5 lesson) | `check-kotlin-balance.py`, `ktscan.py` | 68 files, 0 findings, 0 block-comment anomalies |
| Workflow file is ASCII and triggers on this branch | `check-ascii.py`, `check-workflow-yaml.py` | 6 phase6 files strict-ASCII, 0 findings; `phase6/workflow/phase6-ui.yml` triggers on `arena/01a077b3-opencode-app` |
| No ChatGPT/Claude branding copied | manual + `check-ui-strings` | Own palette, type scale and wording; no third-party product names in `strings.xml` |
| No model API key needed for the UI work | gate design | `U1-U8` and `F1-F4` are key-free. **Flagged:** `L1`/`L2` need a model to serve a turn; they use the pinned build's key-free default model (`opencode/big-pickle`) and print `P6_MODEL_AVAILABLE 0 :: reason` + `SKIP` when it cannot, so a missing key can never masquerade as a pass |

**Offline / degraded states are distinguishable** (`client/UiError.kt`,
`AgentAvailability`): `RUNTIME_UNAVAILABLE` (the embedded runtime is down),
`SERVER_UNREACHABLE` (runtime up, HTTP server not answering),
`SERVER_AUTH`, `PROVIDER_UNREACHABLE` (network to the model provider),
`PROVIDER_AUTH` (bad/absent key), `REQUEST_REJECTED`, `ABORTED`,
`RUNTIME_STARTING`, `PROVIDER_OTHER`, `UNKNOWN` - each with its own title+body
string, and the composer is disabled only for the four states where sending
cannot possibly work. Gate `U4` renders all of them (plus all 7 welcome
supervisor states) and asserts distinct headlines, correct bodies and **zero
leaked connection tokens** (host, port, URL, `Termux`, `SSH`, `adb`, paths).

---

## 4. Gate inventory (14 gates, all in `app/src/androidTest/java/ai/opencode/android/ui/`)

Every gate prints `P6_<id> PASS|FAIL|SKIP :: <detail>` to stdout **and** logcat
(`Log.i("OpenCode/gate", ...)`); `20-ui-gates.sh` folds the deduplicated union of
both channels into `GATES_SUMMARY.txt`, so a gate that never ran cannot be
counted as passed (absent = FAIL).

| Gate | Class | Asserts |
| --- | --- | --- |
| `U1` | `ChatUiGatesTest` | A 602-message transcript renders lazily (row count composed at top and at bottom stays small), newest and oldest are reachable, jump-to-latest works |
| `U2` | `ChatUiGatesTest` | Tool cards read naturally in chat, are collapsed before a tap, expand to the real output text, and error-state cards start expanded |
| `U3` | `ChatUiGatesTest` | Permission asks accept all three replies; question asks submit answers and can be skipped |
| `U4` | `ChatUiGatesTest` | Every `AgentAvailability` state and all 7 supervisor states render their own copy, no leaked connection tokens, distinct chat headlines |
| `U5` | `ChatUiGatesTest` | Streaming indicator, stop button (send hidden while busy), retry banner, undo and redo are all visible and wired |
| `U6` | `ChatUiGatesTest` | Markdown renders (headings/emphasis/lists/links) and code blocks carry syntax-highlight spans with a copy action |
| `U7` | `ChatUiGatesTest` | Every interactive node in the chat and settings trees has a real accessible name (no empty/`null`/tag-only names) |
| `U8` | `ChatUiGatesTest` | Session list is lazy and switching sessions replaces the transcript |
| `F1` | `FirstRunUiGatesTest` | First screen is the welcome screen, all its copy is present, no host/port/terminal/Termux/SSH token is ever rendered, screenshot captured |
| `F2` | `FirstRunUiGatesTest` | The runtime brings itself up to `HEALTHY` with no `start()` call from the test, and the app advances to projects/chat by itself |
| `F3` | `FirstRunUiGatesTest` | Typing a project name in the UI, creating it, landing in a conversation with an enabled composer and the sanitized name shown |
| `F4` | `FirstRunUiGatesTest` | Every stage left a usable screenshot in `filesDir/screenshots` |
| `L1` | `LiveChatUiGatesTest` | A real prompt typed into the composer produces a real assistant reply **as reported by the server** (the app's own `OpenCodeApi` over loopback is the authority; novelty is decided by message id, not by a clock), and that reply's distinctive word is visible in the UI |
| `L2` | `LiveChatUiGatesTest` | A shell request produces a real `tool` part server-side and an expandable card tagged with that part's own id, collapsed before the tap, showing the marker the shell command printed |

Expected screenshots once the suite runs (names are fixed so the report can be
diffed run-to-run): `01-first-run-welcome`, `02-first-run-ready`,
`03-first-run-projects`, `04-first-run-chat`, `10-chat-long-transcript`,
`11-chat-tool-cards`, `12-chat-asks`, `13-chat-runtime-down`,
`14-chat-provider-auth`, `15-chat-streaming`, `16-chat-markdown`,
`17-chat-a11y`, `18-settings-a11y`, `19-sessions`, `19b-welcome-states`,
`30-live-chat-reply`, `31-live-tool-card-collapsed`,
`32-live-tool-card-expanded`, `99-final-host-screen` - all under
`docs/progress/phase6-evidence/screenshots/`.

---

## 5. CI run log, and what run #1 found

Environment: `phase6/CI_GRADLE_ONLY` is `0`, so a run does the whole thing
(static checks -> payload -> both APKs -> fresh emulator -> gates A/B/C ->
evidence). Step 2/8 is the fast compile + JVM-unit-test stage, which fails the
job before the ~30-minute payload build; that is what caught the defects below.

### Run 34096781049 (commit `f8eca14`) - FAILED at compile, main sources

`P6-BUILD FAIL kotlin compile or JVM unit tests failed`. Three defects, all in
Phase 6 main sources, all fixed in the commit that follows this report:

| # | Error (verbatim from `compiler-errors.txt`) | Cause | Fix |
| --- | --- | --- | --- |
| 1 | `UiError.kt:108:29 Expecting '->'` and `108:17 Type mismatch: inferred type is AgentAvailability but Boolean was expected` | the fall-through arm of a **condition-less** `when { }` was written as a bare expression `hints(lower)` instead of `else -> hints(lower)`; Kotlin parses a bare expression there as the branch condition, hence "expecting `->`" and the Boolean mismatch | `else -> hints(lower)` |
| 2 | `ChatComponents.kt` lines 141/301/396/412/471/656/732: `Unresolved reference: TAG_MESSAGE_`, `TAG_REASONING_`, `TAG_TOOL_CARD_`, `TAG_TOOL_HEADER_`, `TAG_TOOL_OUTPUT_`, `TAG_PERMISSION_ASK_`, `TAG_QUESTION_ASK_` | composite test tags were written as `"$TAG_TOOL_CARD_${part.id}"`. A `$name` template greedily consumes identifier characters - including the trailing `_` - so the compiler looked for a constant called `TAG_TOOL_CARD_` | all seven rewritten with braces: `"${TAG_TOOL_CARD}_${part.id}"`. The gate expectations (`tool_card_<partId>` etc.) are unchanged, and the instrumented tests already used the braced form or `"$TAG_TOOL_OUTPUT" + "_<id>"`, so no test edit was needed |
| 3 | `SettingsScreen.kt:900:9 Unresolved reference: Box` plus two cascading `@Composable invocations can only happen from the context of a @Composable function` | the theme/policy radio chip uses `Box(contentAlignment = ...)` but `androidx.compose.foundation.layout.Box` was never imported; with `Box` unresolved the trailing lambda is not seen as composable content, which is what produced the two cascading errors | import added |

Nothing else in the compile stage was reached (`:app:compileDebugAndroidTestKotlin`
and `:app:testDebugUnitTest` did not run), so the instrumented harness and the JVM
unit tests are still unproven - they are next in line on the re-run.

### Run 34097714114 (commit `e2f14a2`) - FAILED at compile, main sources now green

`:app:compileDebugKotlin` passed, so the three fixes above are confirmed by the
compiler. The failures moved to the test sources - five more defects, all fixed in
the commit that carries this paragraph:

| # | Error | Cause | Fix |
| --- | --- | --- | --- |
| 4 | `UiGateSupport.kt` 121-124/155/200/209 and `ChatUiGatesTest.kt` 423/433-436/450/456/466/1018: `Unresolved reference` with only `kotlin.collections.getOrNull` offered as candidates | `SemanticsConfiguration.getOrNull` is **not a member** - the reference page lists it under *Extension functions*, so it needs `import androidx.compose.ui.semantics.getOrNull`. Without the import only the collection extensions were in scope, hence the "receiver type mismatch" candidate list and the cascading `Unresolved reference: it` | import added to both files |
| 5 | `UiGateSupport.kt:209:47` and `ChatUiGatesTest.kt:450:89`: `Unresolved reference: Enabled` | Compose has **no** `SemanticsProperties.Enabled`. A disabled component carries `SemanticsProperties.Disabled = true` - which is what the framework's own `assertIsEnabled()`/`assertIsNotEnabled()` matchers read | `isEnabledNode` and `sendEnabled` now test `getOrNull(SemanticsProperties.Disabled) != true` |
| 6 | `ChatUiGatesTest.kt:318:16 Type mismatch: inferred type is Pair<SnapshotStateList<Transcript.Message>, MutableState<Boolean>> but Unit was expected`, plus `498:25 Destructuring declaration initializer of type Unit must have a component1()/component2()` | `renderLiveChat` has a block body with **no declared return type**, so Kotlin fixed it to `Unit` and the `return live to liveBusy` was rejected; the destructuring at the call site was the cascade | return type declared as `Pair<SnapshotStateList<Transcript.Message>, MutableState<Boolean>>` (two `androidx.compose.runtime` imports added) |
| 7 | `ChatUiGatesTest.kt:586:20 No value passed for parameter 'availability'` | one of the thirteen `renderChat` call sites (U2, the tool-card gate) omitted the required `availability` argument | `availability = AgentAvailability.READY` passed explicitly |
| 8 | `CodeHighlightTest.kt:108:51 Unresolved reference: PATH` | the shell fixture `"echo \"$PATH\" | grep x"` was read as a Kotlin string template referring to a variable named `PATH` | escaped to `\"\$PATH\"` - the same trap as the `"$TAG_..."` templates in defect 2, in a different disguise |

`:app:compileDebugAndroidTestKotlin` and `:app:testDebugUnitTest` had not been
reached before, so this run is the first that will exercise the JVM unit tests and
the instrumented harness compilation end to end.

### Offline cross-checks done while CI was blocked

These are host-side checks, not device evidence:

* Compose test API verified against the official reference rather than memory:
  `onNode`/`onAllNodes` are **members** of `SemanticsNodeInteractionsProvider`
  (inherited by `ComposeTestRule`, so importing them is an error), `onRoot` **is**
  an extension in `androidx.compose.ui.test`, `SemanticsMatcher`'s companion has
  no `Any` (so the harness builds its own matcher through the public
  constructor), and screenshots go through the documented
  `captureToImage().asAndroidBitmap()`.
* Every `ai.opencode.android.*` import in the 4 androidTest files and all 14 JVM
  test files resolves to a real declaration; no JVM test touches `R.string`.
* Every screen call site in `ChatUiGatesTest` matches the real signature
  (ChatScreen 20 required args, SettingsScreen 26, SessionPanel 8,
  ProjectsScreen 6, WelcomeScreen 3), and the fabricated fixtures match the real
  `RuntimeSummary`, `UiState`, `SessionInfo`, `Attachment` and `MessageInfo`
  shapes.
* Gate ids (`U1-U8`, `F1-F4`, `L1-L2`), class FQCNs, `@get:Rule`,
  `@FixMethodOrder(NAME_ASCENDING)` and method ordering all match what
  `20-ui-gates.sh` runs and folds into `GATES_SUMMARY.txt`.
* A whole-tree scan for unresolved capitalised names and bare function calls
  found no further missing imports after the `Box` fix.

### Next

1. Push the fixes; the workflow re-runs on push (runs so far: #1 three defects in
   main sources, #2 five defects in the test sources, all fixed).
2. If step 2/8 goes green, the same run continues into the payload build, the
   fresh emulator and gates A/B/C, and commits verdicts + screenshots to
   `docs/progress/phase6-evidence/`.
3. Only then can F1-F4, U1-U8 and L1-L2 be labelled TESTED, and only for the
   device that ran them (x86_64 emulator, API level recorded in
   `GATES_SUMMARY.txt`). Real arm64 device coverage stays a Phase 8 gap, and
   P5-G16 stays red-by-design (upstream restriction
   anomalyco/opencode#47644 for remote HTTP/SSE MCP; no client-side proxy
   workaround was built).

Phase 5 stays frozen: `phase5/scripts/20-integration-gates.sh`, the R-* drivers
and P5-K are untouched in this phase, and both `CI_GRADLE_ONLY` markers are `0`.
Expected Phase 5 steady state remains 14 PASS / 1 FAIL.

## 6. OpenCode capabilities not yet exposed in the UI (flags for Phase 7/8)

The client (`client/OpenCodeApi.kt`) already speaks to these upstream endpoints;
the UI does not surface them yet. Deliberate - Phase 6 is presentation, and
projects/workspace management, memory and permission depth are Phase 7:

| Capability | Client status | UI status | Phase |
| --- | --- | --- | --- |
| Agent / mode selection (`build`, `plan`, custom agents) | `promptAsync(agent = ...)` supported, repository passes none | not exposed | 7 |
| Project file browser + file viewer | `fileList(path)`, `fileContent(path)` implemented | no UI at all | 7 (workspace management) |
| Global config editing beyond MCP + bash policy | `globalConfig()` (unused), `patchGlobalConfig()` (used for MCP/policy only) | fixed sections only | 7 |
| Memory / `AGENTS.md` management | not in the client | not exposed | 7 |
| Permission policy depth (per-tool, per-path, always-allow rules beyond bash) | bash policy only | one dropdown | 7 |
| Structured diff viewer for `write`/`edit` tools | tool output text is shown verbatim in the card | no side-by-side diff | 7/8 |
| Session sharing / export | not in the client | not exposed | 8 |
| Per-session cost & token accounting | `SessionInfo.cost` fetched; `formatCost` rendered per message | partial (no session-level totals screen) | 7 |
| Server-side LSP / diagnostic output | not in the client | not exposed | 8 |
| MCP over remote HTTP/SSE (G16) | upstream restriction, shown verbatim including `failed / "Failed to get tools"` | honest status text, no workaround | stays documented |
| Secure-hardware key residency | software keystore only | - | 8 (device coverage) |
| toybox `tar` on a real API 29 device | unverified | - | 8 (device coverage) |

---

## 7. Evidence locations

| Path | Contents |
| --- | --- |
| `docs/progress/phase6-evidence/GATES_SUMMARY.txt` | `P6_SUMMARY`, pass/fail/skip counts, per-class instrument rc, screenshot count, `model_available`, device abi + sdk (written by CI, not present yet) |
| `docs/progress/phase6-evidence/p6-ui-lines.txt` | every `P6_*` verdict line, deduplicated across stdout + logcat |
| `docs/progress/phase6-evidence/p6-model-lines.txt` | `P6_MODEL_AVAILABLE 0|1 :: reason` marker lines |
| `docs/progress/phase6-evidence/screenshots/` | the PNGs listed in section 4 |
| `docs/progress/phase6-evidence/p6-{chat-ui,first-run,live-chat}-instrument.log` | raw `am instrument` output per class |
| `docs/progress/phase6-evidence/{runtime.log,opencode-server.log,logcat-OpenCode.txt}` | the app's own runtime log, the embedded server's log, filtered logcat |
| `docs/progress/phase6-evidence/jvm-unit-tests/` | `:app:testDebugUnitTest` XML |
| `docs/progress/phase6-evidence/static-checks.log` | the checker output quoted in section 3 |
