# Phase 6 — UI polish: scope, findings, plan

**Date:** 2026-09-06 · **Branch:** `arena/01a05713-opencode-app` · **Prior phase:** closed at 14 PASS / 1 FAIL (§2's documented upstream restriction, upstream [#47644](https://github.com/anomalyco/opencode/issues/47644))

## Scope and fences

Phase 6 is *presentation only*, per the standing rule that Phase 6 = UI polish. Concretely:

- **The UI stays a thin client of the on-device server.** Every visible string, status and transcript entry must come
  from `OpenCodeApi` / `OpenCodeEventStream` / `Transcript` / `RuntimeManager.diagnostics()`. No new state model in
  the UI layer, no caching of server facts in view models beyond what `UiState` already holds, and **no
  reimplementation of anything OpenCode does** — if polish seems to need a client-side substitute, that is a Phase 5
  architecture question and goes to §"Surfacing" below, not into a composable.
- **Upstream status text is shown verbatim**, including the ugly parts. `GET /mcp` reporting
  `failed / "Failed to get tools"` is displayed as-is, with the explanatory line the Phase 5 report §2 authorises
  (remote HTTP/SSE MCP is not usable on this runtime; stdio is). Do not translate, soften, or retry-loop it.
- **Phase 5's gates are not touched.** `phase5/scripts/20-integration-gates.sh`, the `R-*` drivers and `P5-K` stay
  byte-identical; `P5-G16` stays red-by-design. A polish phase may not lower a bar to pass it.
- Credentials: still Keystore-only, still never rendered back (masked entry, no "show value", no export), still
  never in logcat or diagnostics output.

## What the UI is today (audited, not assumed)

`app/src/main/java/ai/opencode/android/ui/OpenCodeScreen.kt` — **one file, 471 lines, five tabs**
(`CHAT / PERMISSIONS / MCP / CREDENTIALS / RUNTIME`) under `OpenCodeRoot`, plus `MainActivity.kt`. Measured facts:

| Finding | Evidence | Consequence |
| --- | --- | --- |
| Every list renders eagerly inside a scrolling `Column` | `Scrollable()` = `Column(Modifier.verticalScroll(...))`; transcript is `messages.forEach { MessageCard(it) }`, sessions are `state.sessions.take(8).forEach { … }` | Long transcripts compose and lay out in full: jank and memory growth on a real phone. `take(8)` is a *silence* mechanism, not a fix — it hides sessions rather than scrolling them |
| `androidx.compose.foundation.lazy.LazyColumn` is imported and never used | `OpenCodeScreen.kt:10` | A half-finished conversion; the import is the only trace. Converting lists to lazy *requires* removing the outer `verticalScroll` (a lazy list with infinite max height either crashes or silently measures one item) |
| 4 strings in `res/values/strings.xml`; all UI copy hardcoded | `grep -c "<string"` → 4 | No localisation, no RTL review, and text like `"No messages yet. What you type goes to POST /session/:id/prompt_async…"` (an API detail) is user-facing prose |
| No `contentDescription`, no semantics, no `testTag` anywhere | `grep` across `app/src/main` → none | Screen readers get nothing; there is no anchor for Compose UI tests, so "the UI works" cannot be gated at all |
| No Compose UI-test dependencies | `app/build.gradle.kts` androidTest: `androidx.test.ext:junit`, `runner`, `core` only | **Prerequisite P6-00.** Until `ui-test-junit4` (+ `debugImplementation ui-test-manifest`) exist, every Phase 6 claim would be "it compiles", which this project does not accept as evidence |
| Input masking already correct | `OpenCodeScreen.kt:412` `PasswordVisualTransformation` | Not a task; keep, and extend to the MCP header/token fields if those gain inputs |
| `UiState` already carries `streaming`, `streamStatus`, `busy`, `error`, `notice`, `mcp: Map<String,String>`, `providers`, `model` | `client/OpenCodeRepository.kt:33-46` | Most polish needs no new plumbing — but `mcp: Map<String,String>` **drops upstream's `#error` text**, which is exactly the field the Phase 5 restriction tells the user about; widening it to a small value type is the one repository change in scope |

## Plan (each item lands with its own CI run; labels start at NOT TESTED)

0. **P6-00 Test surface** *(prerequisite)* — add `androidx.compose.ui:ui-test-junit4` (from the Compose BOM) and
   `debugImplementation("androidx.compose.ui:ui-test-manifest")`; create
   `app/src/androidTest/.../ui/UiSmokeTest.kt` with `createComposeRule()`, and put `testTag(...)` on every
   interactive element. *Accept:* smoke test runs in the existing emulator job; the CI gate counts it
   (`p6 UI smoke pass/fail` lines in `GATES_SUMMARY.txt`, same trailer-is-truth discipline as `P5-K`).
1. **P6-01 Lazy, bounded, scrollable** — transcript, session list, permission list and MCP list into `LazyColumn` /
   `items(...)`; delete the outer `verticalScroll` from those tabs (keep it where a genuinely short block scrolls);
   remove `take(8)` in favour of real scrolling; drop the unused import or use it.
   *Accept:* a UI test that renders a 300-message transcript and scrolls to the last item; no
   "infinity maximum height constraints" crash; frame timing not asserted on the emulator (device-only claim, so
   it stays NOT TESTED unless measured on hardware).
2. **P6-02 Streaming legibility** — pin-to-bottom while new parts arrive (un-pin when the user scrolls up, with a
   "N new" affordance), per-message streaming state from `Transcript`/`streamStatus`, and `Interrupt` visibly
   enabled only when `busy` (already so — keep, and disable input while a turn is in flight instead of queueing).
   Tool-call parts render as collapsible blocks with upstream's own status (`running/completed/error`) — no
   client-side interpretation of what a tool "should" have done.
3. **P6-03 Errors and notices as first-class** — an error banner bound to `UiState.error` (dismissible, with the raw
   server text in an expandable section), and **MCP per-server `#error` text** surfaced on the MCP tab: widen
   `UiState.mcp` to `Map<String, McpEntry(name, status, error?)>` populated straight from `GET /mcp`. Includes the
   remote-MCP restriction line (§2 of the Phase 5 report is its source of truth) so the failure is explained once, in
   plain words, instead of looking like our own bug.
4. **P6-04 Permissions UX** — tab label gains a pending count; each ask shows `permission`, `patterns`, `metadata`
   (expandable) and the three upstream replies (`once` / `always` / `reject`), with an optional message on reject.
   No auto-reply, no client-side allowlist, no "remember" beyond upstream's `always`.
5. **P6-05 Sessions** — title or id (never a truncated id alone as the primary label), relative time, explicit
   empty state, and selection that survives tab switches; "New session" keeps using `POST /session`.
6. **P6-06 Credentials & providers** — provider list from `GET /provider` with `connected` state shown honestly
   (and the Phase 5 rule restated in code comment form: `connected` is *not* proof a turn can run — that is why the
   gate probes a real prompt; the UI must not imply otherwise); key entry masked, save = `PUT /auth/:id`,
   clear = `DELETE /auth/:id`, no read-back of stored values; Keystore-failure degradation (`Secrets`' ephemeral
   password) gets a visible warning in the Runtime tab because it changes durability.
7. **P6-07 Theme, a11y, i18n** — every string to `strings.xml` (and user-visible prose loses API jargon), dark theme
   + `DynamicColors` on Android 12+, semantics (`selectableGroup`/`Role.Tab` for the tab row, state descriptions for
   pending asks), 200 % font-scale pass, 48 dp targets, RTL check. *Accept:* a static script
   `phase6/scripts/check-ui-strings.sh` failing on `Text("` literals outside an allowlist, wired into the CI job; the
   smoke test asserting semantics on one tab and one ask card.
8. **P6-08 Rotation and process death** — `rememberSaveable` for selected tab and input draft; transcript scroll
   position best-effort; confirm the runtime is *not* restarted by rotation (`RuntimeManager.start()` is idempotent;
   `RuntimeService` owns the FGS) with a UI test that rotates and re-reads `state.serverVersion`.
9. **P6-09 Diagnostics panel** — Runtime tab shows `manager.diagnostics()` as labelled sections (state, restart
   count, bind audit, loopback verdict, payload version from `RuntimeVersion`/`versions.lock`) instead of a text
   dump, plus the existing share action; every field must be one the app already computes — no new collection.

## Surfacing, not substituting

Two tempting shortcuts are explicitly out of scope and get reported instead of built: a client-side MCP proxy or
retry to make remote servers "work" (that would be a reimplementation of the transport, and it hides upstream
#47644), and a UI-side tool-output rewriter. If Phase 6 polish needs either to look correct, that is a finding to
write into the phase report.

## Evidence rules carried forward

Emulator-green is not device-green: **real arm64 execution of everything — Phase 5's suite included — remains NOT
TESTED**, and Phase 6 adds UI claims that matter more on hardware (frame pacing, font scale, IME behaviour, rotation
under memory pressure). The manual Realme RMX3830 checklist from Phase 4/5 still applies and is now *longer*: after
P6-01/P6-02 a human should scroll a long streamed reply and approve one permission on real hardware. Each push to
this branch still runs `phase5-integration` (docs included); the expected steady state is **14 PASS / 1 FAIL** with
`P5-G16` the documented restriction — a Phase 6 change that moves any other gate is a regression, not an improvement.

**Status: IMPLEMENTED = nothing yet; every item above is NOT TESTED.** This file is the plan of record; results go
to `docs/progress/phase6-ui-polish-report.md` when the phase closes.
