# Phase 8 report — Hardening & Testing

Status: **COMPLETE, with two gaps explicitly carried** (L2's tool card and the
provider-selection defect behind it). Everything Phase 8 owns — hardening,
security, performance, failure states, the test matrix — is delivered and
labelled. The carried items are named precisely, with the next diagnostic step
already implemented rather than left as an intention.

- Date: 2026-09-08 → 2026-09-12
- Branch: `arena/01a07f92-opencode-app` (from Phase 7 merge `afbf052`)
- Pinned runtime (unchanged from `versions.lock`): OpenCode `05ea5073`
  (v1.18.23), Bun 1.3.14, git 2.48.1, ripgrep 15.1.0, payload v5.
- CI: workflow `phase8-hardening.yml` (user-installed, branch-triggered,
  480 min job cap). CI run history: §12.
- Real device: Realme RMX3830, Android 15 / API 35, arm64-v8a — **eight** full
  suite runs executed by the user (2026-09-08 → 2026-09-12), bundles uploaded
  to this branch as `p8d-out/`.

**Correction log (read this first).** This report was wrong in public more than
once, and each retraction is kept rather than quietly edited away:

| Round | Claim made | Verdict |
|---|---|---|
| 13 | the typed Gemini key was invalid (wrong length/prefix) | **RETRACTED** — Google also issues `AQ.`-prefixed keys (§3.1.3) |
| 14 | preflight "no output" = provider/egress problem | **RETRACTED** — `run-as` has no `AID_INET`; our probe had no network (§3.1.7) |
| 15 | raw-transcript capture is safe | **INCIDENT** — leaked a live API key into a public repo (§3.1.8) |
| 17 | Gemini is geo-blocked for this device → L2 `BLOCKED-GEO` | **RETRACTED** — run 8: `key=OK models=40 modelUsable=true` (§3.1.11b) |
| 18 | provider SDKs are missing from the payload | **WITHDRAWN as overstated** — they are upstream deps and should be inlined; unverified (§3.1.11b) |
| 3/5/7 | COLDSTART wall times (320s/326s/319s) | **RETRACTED** — instrument overhead; supervisor windows (13–21.8 s) stand (§3.1.12) |

The common failure was concluding from a single observation without taking the
measurement that would have settled it. The user's own check of the Google
usage dashboard — one request, successful — did in seconds what four rounds of
harness work had not.

---

## 1. Executive summary

Phase 8 had five obligations: close (or explicitly document) the carried gaps,
re-verify the security checklist with evidence, MEASURE performance, make
failure states distinguishable, and build the full test matrix. Outcome:

- **Production hardening** (state machine, backoff, illegal-transition hook,
  health-check wire contract, extraction validation): all
  `IMPLEMENTED + TESTED` (272 JVM tests green in every CI run since round 4,
  including the pre-existing 187).
- **L2 real tool call** (carried since Phase 6): **NOT CLOSED — carried into
  Phase 9 as `BLOCKED-PROVIDER-SELECTION`.** A real model round-trip was
  observed once on the real device (run 1, P8-KEYPROBE PASS,
  `reply='P8PROBEOK'`), but it has never reproduced across runs 2–8, so this
  report does not lean on it. The tool card was never observed. The cause is
  now narrowed to a specific, actionable defect rather than an environment
  excuse: **every `llm runtime selected` line in all captured evidence names
  `llm.provider=opencode`** (the bundled provider), never the provisioned
  `google`/`openrouter` — and the user's provider dashboard confirms the turns
  send zero requests. Credentials, network and model id are all verified good
  (`key=OK models=40 modelUsable=true`). Round 19 ships the diagnostic that
  will name the reason on the next run (§3.1.11b).
- **Secure-hardware key residency** (gap b): `TESTED` — measured on BOTH
  devices: the key lives in the **software** keystore on the real arm64 phone
  (`insideSecureHardware=false`, strongBox probe null), as designed. Documented
  as an accepted limitation with the precise consequence (§3.2).
- **Toybox tar** (gap c): `TESTED` on API 34 (CI) and API 35 (device); the
  API-29 half remains `NOT TESTED` — no API-29 device exists for this project;
  documented with the blast-radius analysis (§3.3).
- **Real arm64 suite** (gap d): the real-device suite ran R1–R6
  (`TESTED` for the gates it contains); the destructive host gates stay
  emulator-only by design (§3.4).
- **P5-G16**: unchanged, documented-red (upstream `anomalyco/opencode#47644`)
  (§3.5).
- **Security checklist**: all six original items re-verified green (Phase 5/7
  regression suites pass in every CI round) + the per-device residency
  measurement (§4).
- **Performance**: cold start, supervisor windows, op latencies, large-project
  ops and app memory MEASURED on both machines (§6); two items are explicitly
  `NOT MEASURED` (steady-state CPU, server-process memory) rather than guessed.
- **Failure states**: the classifier is `IMPLEMENTED + TESTED` (JVM) and the
  live gates surfaced a real upstream behaviour — failed provider turns can
  complete *silently* (empty message, no recorded error), which the phase
  documents instead of papering over (§7, §13).
- **Test matrix**: full status table with per-gate results (§8).

---

## 2. Production hardening changes (this phase)

All under `app/src/main/java/ai/opencode/android/runtime/` unless noted.

| Change | File | Why | Status |
|---|---|---|---|
| Legal-transition table: 7 states, 17 edges; `isLegal`, `expectedFrom` | `RuntimeStateMachine.kt` (new) | The supervisor's state behaviour was implicit; a defect could only be found by watching a device. The table makes the contract pure and testable. | IMPLEMENTED + TESTED (JVM `RuntimeStateMachineTest`) |
| Restart backoff: `delayMs(attempt, jitter) = min(cap, base·2^(attempt-1))·(0.5+jitter)`, clamped [500ms, 30s]; give up after 8 | `RestartBackoff.kt` (new) | Backoff was inline in the supervisor with no tests; the formula is now pinned (worst-case 7-sleep budget = 106 495 ms). | IMPLEMENTED + TESTED (JVM `RestartBackoffTest`) |
| Supervisor consults the table on every publish; illegal transitions logged (`ILLEGAL_STATE_TRANSITION` + expected set), behaviour-neutral | `RuntimeManager.kt` | Makes a state-machine defect loud in `runtime.log` and assertable. | IMPLEMENTED + TESTED (JVM; on-device P8-LIFECYCLELOG PASS on CI AND device: 0 illegal transitions, 9/4 → 4/3 transitions observed) |
| Backoff delegated to `RestartBackoff` | `RuntimeManager.kt` | Single source of truth. | IMPLEMENTED + TESTED (JVM) |
| Health check uses `java.util.Base64` (identical to `android.util.Base64` on API 26+; minSdk 29) | `HealthCheckerTest` target | JVM tests can run the real wire contract against a real localhost `ServerSocket`. | IMPLEMENTED + TESTED (JVM) |
| `RuntimePaths` constructor File-based + `forTesting` | `RuntimePaths.kt` | Pure construction for unit tests without a Context. | IMPLEMENTED + TESTED (JVM `RuntimeEnvTest`) |
| `OpenCodeApi.shellOutput` (body-returning sibling of `shell`) | `OpenCodeApi.kt` | Diagnostics (server-context egress probe, §3.1.1). | IMPLEMENTED + TESTED (compiles in all rounds; exercised by g00) |

## 3. Carried gaps — resolutions

### 3.1 L2 "a real tool call" (carried since Phase 6)

Mechanism (unchanged from the phase plan): a short-lived `OPENROUTER_API_KEY`
(user-provided) flows through the **product's own credential path**
(Keystore-backed `Secrets.putProviderKey` + OpenCode's `PUT /auth/openrouter`),
a probe turn (`P8-KEYPROBE`) proves the key serves a real round-trip, and the
UI gate (`P8-TOOL`) sends a prompt whose only correct answer is a bash tool
call echoing an unknown marker; the gate asserts on the server part AND the
tool card tagged with that part's id.

**Result — split by machine, and the split is the finding:**

- **Real device (RMX3830, user's fresh key, model
  `nvidia/nemotron-3-ultra-550b-a55b:free`): P8-KEYPROBE PASS** —
  `tokenSeen=true reply='P8PROBEOK'`. `TESTED`: the server on real hardware
  reached OpenRouter with the provisioned key and got a real answer.
  P8-CLEANUP PASS (key removed from the phone afterwards).
- **P8-TOOL: NOT YET OBSERVED** — the first device run used the free tier,
  where a 1-line answer took ~4 min wall; a tool-call turn needs TWO
  inferences and the 480 s budget expired with `replyChars=0` (the turn never
  completed — the model did not refuse the tool; it was still queued/generating).
  OpenRouter's model page confirms this exact free endpoint **does** support
  tools and tool_choice, so capability is not the blocker — throughput is.
  Round 11 raised the budgets (KEYPROBE 300 s/240 s, TOOL 900 s).
  **[SUPERSEDED] The throughput explanation did not survive: runs 2–8 showed
  the turns send no provider request at all (§3.1.11b). Final status is
  `BLOCKED-PROVIDER-SELECTION`, carried to Phase 9.**
- **CI emulator: BLOCKED** — see §3.1.1.

- **Device runs 2 and 3 (round-11 APK, 900 s TOOL budget): still no tool card,
  and the reason is now measured, not guessed.**
  - Run 2 added the in-process egress probe and caught
    `P8NETPROBE_UI openrouter http=403 ms=924` while `opencode http=200` and
    `control http=200` — **OpenRouter's edge refused the phone's IP even on the
    public, keyless `/api/v1/models` endpoint.** Every model turn in that window
    was swallowed into a silent empty completion (§13). The round-9 provider-auth
    probe fix was verified in the same run
    (`serverSawError=true serverErrorName=APIError classified=PROVIDER_OTHER`).
  - Run 3 (user changed IP): `P8NETPROBE_UI openrouter http=200 ms=1648` — the
    IP block was gone — yet P8-KEYPROBE still failed **silently**
    (`tokenSeen=false lastError=''`, `P8_MODEL_AVAILABLE 0`) and P8-TOOL SKIPped.
    Public endpoint reachable + authenticated model calls silently empty is the
    signature of an **account-level free-tier restriction**, not a product
    defect: the account has never purchased credits, and OpenRouter caps
    unfunded accounts at **50 requests/day, 20/min**, serving `:free` endpoints
    at lowest priority. Run 1's single successful answer, then zero across runs
    2–3, fits that cap.
  - Run 3's cold start (326 s wall) is **POLLUTED** (the phone was handled
    mid-run); the supervisor window from the same run,
    19:20:10.645 → 19:20:32.415 = **21.8 s**, is the number of record.

#### 3.1.2 Round 13 — the provider is now a run-time choice (Gemini free tier)

The blocker for P8-TOOL is provider quota/throughput, not the app. Round 13
makes the provider a parameter of the live gates instead of a hard-coded
`openrouter` string:

- `files/harness/provider` (written by the device suite base64-over-stdin, like
  the key) selects the OpenCode provider id. It defaults to `openrouter`, so
  every earlier round keeps its exact meaning.
- `P8ServerProbe.provisionedProvider` feeds all five call sites:
  `Secrets.putProviderKey`, `PUT /auth/:providerID`, `ModelRef(provider, model)`,
  the repository's `setModel`, and the CLEANUP assertions — so a Gemini run also
  proves the Keystore cleanup path for a non-OpenRouter provider.
- The device suite prompts for the provider (default **google**), defaults the
  model to `gemini-2.5-flash`, sanity-checks the key shape (`AIza…` vs `sk-or-…`)
  before spending 20 minutes on a run, and deletes `harness/provider` with the
  key at the end.
- The in-process egress probe also probes
  `https://generativelanguage.googleapis.com/`, so a Gemini run yields the same
  reachability evidence line.

Rationale (recorded because it changes the L2 evidence provider): Google AI
Studio's free tier is **request-limited, not credit-limited** — Flash-class
models publish roughly 10–15 RPM and hundreds-to-1,500 RPD at 250 K+ TPM, no
card — whereas the unfunded OpenRouter tier is 50 requests/day at lowest
priority. A tool-call turn needs at least two inferences; the Gemini free tier
can serve that repeatedly, the unfunded OpenRouter tier demonstrably could not.
**Status: IMPLEMENTED, TESTED on device (device run 4) — the provider switch
works; the run failed on the key, not on the code.**

#### 3.1.3 Device run 4 (2026-09-09, round-13 APK, provider `google`)

The Gemini path executed end to end: `live gates provider=google
model=gemini-2.5-flash`, the provider id reached every call site, and
**P8D-CLEANUP PASS** now proves the Keystore/auth-store cleanup path for a
**non-OpenRouter provider** (`storedProviderIds=` empty afterwards) — that is
new coverage. Model-free gates were unchanged: TOYBOX PASS, COLDSTART PASS
(**20 s** wall, fresh install incl. first extraction), SERVERKILL PASS
(`killedPid=30202 → newPid=30650 finalStatus=HEALTHY`), LIFECYCLELOG PASS
(`illegalTransitions=0`), KEYRESIDENCY FAIL-as-measurement (unchanged software
keystore result).

**P8D-KEYPROBE FAIL / P8D-TOOL SKIP — cause NOT yet established.** An earlier
revision of this section claimed the typed key was invalid because it was 53
characters with no `AIza` prefix. **That claim was wrong and is retracted**:
Google AI Studio began issuing keys with an `AQ.` prefix (e.g.
`AQ.Ab8RN6…`, ~52 characters) alongside the classic `AIzaSy…` (39 characters)
in August 2026, and the user confirmed from the AI Studio key page that this is
exactly what they typed. The suite's shape check knew only the old format and
cried wolf on a valid key. Recorded here because the harness's own diagnostic
misled the analysis — the honesty rule cuts both ways.

What the run does establish:

- Egress was healthy in the gate window:
  `P8NETPROBE_UI openrouter http=200 ms=693 | gemini http=404 ms=574 |
  opencode http=200 ms=809 | control http=200 ms=529`. The `404` is the
  expected reply to a bare `GET` on the Gemini API root (no method path) — the
  host resolved and Google's front end answered, so the network is not the
  blocker.
- The turn failed the way §13 predicts: `tokenSeen=false lastError=''` — a
  silent empty completion, which upstream produces for provider auth *and*
  model-id errors alike. **The observable evidence cannot distinguish "key
  rejected" from "model id not served to this key"**, and one further candidate
  is now known: the `AQ.` key rollout is reported to `404` on several older
  model ids, which would make `gemini-2.5-flash` itself the fault rather than
  the credential.

Status: **P8D-TOOL remains BLOCKED, cause under investigation.** Round 14 exists
to make the next run answer this in two seconds instead of ten minutes.

#### 3.1.4 Round 14 — key preflight (fail in 2 s, not 10 min)

Because OpenCode cannot be relied on to report provider-auth failures, the
device suite asks the provider **directly from the phone, before the gates
run**, and prints the provider's own words as `P8KEYPREFLIGHT` (saved to
`p8d-out/key-preflight.txt`). It asks two questions — *is the key accepted?*
(`GET /v1beta/models?key=…`) and *does this model exist for this key?* (the same
response filtered to `generateContent`-capable models) — auto-switching to a
model the key itself reports when the configured id is absent. The shape check
now accepts `AIza…`, `AQ.…` and `sk-or-…` and is a **warning only**.

#### 3.1.5 Device run 5 — the preflight fired, and caught OUR bug, not Google's

Run 5 (round-14 APK) returned `P8KEYPREFLIGHT no output` **in one second**, and
that single fact overturns the whole line of investigation. A network probe
against Google cannot fail in one second with no output, no status, and no
error text — a timeout would have taken 20 s, a rejection would have carried a
status. Something killed the process before it ran any JavaScript.

The same run contains the contrast that identifies it:

| Context (device run 5) | outcome |
|---|---|
| server's **child** process (OpenCode `/shell` → bun) | `openrouter http=200`, `gemini http=404` (expected for a bare root GET), `opencode http=200`, `control http=200` |
| server's **own** process (model calls) | `APIError serverStatus=0`, empty message — silent |
| **run-as child** (`$FILES/bin/bun script.js`, the preflight) | **no output at all, ~1 s** |

**Root cause: the harness was launching Bun the wrong way.** The app never
executes `bin/bun` directly. `RuntimeProcess.start()` execs
`libexecshim.so` with `OPENCODE_BUN_EXEC` and `OPENCODE_SECCOMP_SHIM` set, so
that `libseccompshim.so` is `LD_PRELOAD`ed and its constructor installs a
`SIGSYS` handler **before Bun's native init**. That handler exists precisely
because Android's per-app seccomp filter turns unknown syscalls into a **fatal
`SIGSYS`** rather than `ENOSYS` — the code comments record bun dying this way
during its own startup. Every ad-hoc `'$FILES/bin/bun' …` in the device suite
skipped that path entirely, so those probes were killed by a signal and printed
nothing.

Consequences, stated plainly:

- The round-14 preflight **never contacted Google**. Its "no output" was our
  launcher failing, and the suite then logged the misleading
  `preflight produced no output (bun/egress problem)` — it discarded the raw
  transcript by piping straight into `grep … | head -1`, destroying the only
  evidence.
- The **cold-start probe used the same raw-bun call**. Its 150-iteration wait
  loop read "no output" as "server not up yet", which is the most likely
  explanation for the absurd `320 s` (run 5) and `326 s` (run 3) COLDSTART wall
  numbers sitting next to supervisor windows of **17.6 s** and **21.8 s**. The
  supervisor windows are the numbers of record; **the wall figures from runs 3
  and 5 are retracted as instrument error.**
- This does **not** explain the server's own silent model turns (the server
  process is launched correctly, through the shim). That remains open — but it
  was never the same failure as the preflight's, and conflating them sent runs
  4 and 5 chasing the API.

#### 3.1.6 Round 15 — fix the instrument, then re-measure

1. **`dbun()`** — one helper that runs a device-side Bun script exactly as the
   app does: `libexecshim.so` with `OPENCODE_BUN_EXEC` + `OPENCODE_SECCOMP_SHIM`,
   `HOME`/`TMPDIR` set, and `echo dbun_rc=$?` appended. `nativeLibraryDir` is
   resolved from `pm path` after install. The preflight and the cold-start
   health probe both use it now.
2. **Stop destroying evidence.** `key-preflight.txt` keeps the full raw
   transcript plus the exit code. When Bun is killed by a signal the suite says
   so explicitly — *"bun terminated by signal 31 (rc=159) — this is a
   RUNTIME/seccomp launch failure on the device, NOT a provider problem"* —
   instead of blaming the network.
3. **New gate `P8D-BUNLAUNCH`** runs the same trivial script both ways (raw vs
   exec-shim) and records each exit code, so the launch path is a measured
   verdict in `SUMMARY.txt` rather than an assumption. Exit `159` = `128+31`
   (`SIGSYS`) is called out by name.

All parsing branches (signal / success / rejected / auto-switch / no-output)
were exercised against captured output shapes on the host before shipping.
**Status: IMPLEMENTED, NOT YET TESTED on device.**

#### 3.1.7 Device run 6 — the instrument was fixed, and it named the real fault

Round 15 shipped `P8D-BUNLAUNCH` and kept raw transcripts. Both paid off
immediately.

**Confirmed fixes.** `P8D-BUNLAUNCH PASS`: exec-shim Bun runs
(`P8BUNSELFTEST alive=1 v=1.3.14`, `rc=0`) while raw `bin/bun` gives
`rc=127 inaccessible or not found`. `P8D-COLDSTART` fell from the retracted
320 s to **13 s wall** — confirming §3.1.5: the old wall figures were the
broken probe, not the product. Gate count improved to **6 PASS / 3 FAIL /
1 SKIP**.

**The preflight's raw transcript — the thing round 14 threw away — named the
fault outright:**

```
error: Unable to connect. Is the computer able to access the url?
  path: "https://generativelanguage.googleapis.com/v1beta/models?key=<redacted>"
  code: "ConnectionRefused"
```

**Root cause: `run-as` shells have no network, by kernel design.** Android
builds with `CONFIG_ANDROID_PARANOID_NETWORK`, which permits socket creation
only to processes in the `inet` group (**AID_INET, GID 3003**). The framework
puts an app's own UID in that group when it holds `INTERNET`, but an `adb
run-as` shell does not inherit it — so every socket it opens is refused. The
host-side preflight could never have reached Google from that context, no
matter how correct the key was. This also explains the whole misleading
pattern: the server's own child processes reach the network
(`openrouter http=200`, `control http=200`) because they inherit the app UID's
groups, while everything we probed from `run-as` looked "dead".

Two rounds of API-hunting were therefore chasing a harness artefact. **The key
has still never actually been tested.**

#### 3.1.8 SECURITY INCIDENT — live API key committed to a public repository

Bun's connection-error message prints the **full request URL**, and a Gemini
key travels in the query string (`?key=…`). Round 15's "keep the raw
transcript" fix wrote that transcript verbatim to
`p8d-out/key-preflight.txt`, which was uploaded to this **public** repository
in commit `52e7c4d`. The key was exposed in plaintext.

- **Severity:** high — a valid, unrevoked Google AI Studio credential, publicly
  readable and indexable.
- **Immediate action:** the value in the working tree is redacted, but **the
  key remains in Git history at `52e7c4d` and must be treated as compromised.
  Revocation by the user is the only real remedy** (see §10).
- **Prevention (round 16):** every artifact the device suite writes now passes
  through a `redact()` filter (`AQ.…`, `AIza…`, `sk-or-…`, `Bearer …`), and the
  on-device preflight never prints a key or a URL containing one.
- **Lesson recorded:** "capture more evidence" and "handle secrets" are in
  direct tension. The round-15 fix improved diagnostics and simultaneously
  created a credential leak, because the transcript was treated as opaque text.
  Any raw-output capture must be redacted *at the point of capture*.

#### 3.1.9 Round 16 — preflight moved into a network-capable context

The preflight now runs **inside the instrumented gate**, in the probe that is a
child of the server (the context proven to have egress), and is reported as its
own gate, `P8_KEYPREFLIGHT`, ahead of KEYPROBE/TOOL. It answers *is the key
accepted?* and *is this model available to it?* without ever emitting the
credential. The host-side preflight is deleted. **Status: IMPLEMENTED, NOT YET
TESTED.**

#### 3.1.11 Device run 7 — ⚠️ RETRACTED CONCLUSION (kept for the record)

> **This subsection's conclusion is WRONG and was overturned by run 8
> (§3.1.11b).** It is preserved unedited because the reasoning error — treating
> one transient `403` as a standing regional block — is the point. Read
> §3.1.11b for the corrected finding.

The round-16 on-device preflight worked exactly as intended and ended the
investigation with one line:

```
P8NETPROBE_UI openrouter http=200 ms=874
P8NETPROBE_UI gemini     http=403 ms=704
P8NETPROBE_UI opencode   http=200 ms=963
P8NETPROBE_UI control    http=200 ms=558
P8KEYPREFLIGHT provider=google key=REJECTED http=403
```

**The decisive detail is that the `403` also appears on the KEYLESS probe.**
`P8NETPROBE_UI gemini` is a bare `GET` of the API root carrying no credential
at all, and Google refused it too — while openrouter, opencode and the control
host all returned `200` in the same window from the same process. A rejection
that does not depend on the key cannot be caused by the key.

**Conclusion: `generativelanguage.googleapis.com` is geo-blocked for this
device's network.** Google restricts the Gemini API by request-origin IP
(documented unsupported regions include Iran, where this device is operating),
returning `403`/`FAILED_PRECONDITION "User location is not supported"`. Earlier
runs' `404` on the same probe was the *normal* answer for a bare root GET, i.e.
the endpoint was reachable then; the `403` now is a refusal at the edge.

This also finally explains the **silent empty turns** that consumed rounds
13–16: OpenCode's provider call was being refused at the network edge, and
upstream's silent-completion behaviour (§13) converted that refusal into an
empty assistant message with no `info.error`. Two independent faults —
`run-as` having no `AID_INET` (§3.1.7) and this geo-block — produced the same
"no output" signature, which is why each fix revealed another layer.

**[RETRACTED — see §3.1.11b] Status of L2: `BLOCKED-GEO`, not "failing".** Honest statement of what is and
is not proven:

- **PROVEN on real hardware:** a real model round-trip through the product's own
  credential path (device run 1, `P8D-KEYPROBE PASS`, `reply='P8PROBEOK'`).
- **NOT OBSERVED:** the tool card (`P8D-TOOL`). It is blocked by provider
  reachability from this device's network, not by app code, and no amount of
  harness work can close it from here.
- Routes that would close it (all outside the current constraints, listed for
  Phase 9, none silently assumed): a network whose egress IP is in a supported
  region; a provider reachable from this region that OpenCode supports and that
  serves tool calls; or attaching billing, which lifts the regional restriction
  for some regions.

`P8_KEYPREFLIGHT` is retained as a permanent gate: any future run now states in
one line whether the provider is reachable and the credential usable, *before*
spending twenty minutes discovering it indirectly.

#### 3.1.11b Device run 8 — the geo-block call was WRONG, and the user's dashboard found the real cause

**Retraction.** §3.1.11 concluded that Gemini was geo-blocked for this device and
closed L2 as `BLOCKED-GEO`. **That was wrong**, and run 8 disproves it from two
directions:

```
P8KEYPREFLIGHT provider=google key=OK http=200 models=40 model=gemini-2.5-flash modelUsable=true
P8NETPROBE_UI gemini http=404 ms=709      (bare root GET — the normal answer)
```

The key authenticates, Google lists **40 models**, and the configured model is
present. The `403` in run 7 was a **transient** edge response, not a standing
regional block — I generalised one sample into a permanent conclusion and
closed a gap on it. That was premature, and the closure is withdrawn.

**The decisive evidence came from the user, not the harness.** They checked the
Google AI Studio usage dashboard and reported **exactly one request, successful**.
That single fact is what identifies the real fault:

- One request = the round-16 **preflight** (which returns `key=OK`).
- The KEYPROBE and TOOL turns that followed produced **zero requests**.

So the server is not being refused by Google, and it is not timing out against
Google. **It never sends a request at all.** Every "silent empty turn" since
round 6 is consistent with this: no request, no response, no error — upstream
completes the message empty (§13).

**Leading hypothesis (stated as a hypothesis, not a conclusion): the turn never
selects the provisioned provider.**

A correction to an earlier draft of this section, which asserted that the
provider SDK is "not shipped and cannot be fetched": that was **too strong and
is withdrawn**. Upstream's `packages/opencode/package.json` at the pinned commit
lists `@ai-sdk/google` and `@openrouter/ai-sdk-provider` as **direct
dependencies**, and `provider.ts` imports them through a static
`BUNDLED_PROVIDERS` map. The payload build marks only `jsonc-parser`,
`@lydell/node-pty` and `bun-pty` as `external`, so the bundler should inline
every provider SDK into `node.js`. Whether that actually happened in the shipped
payload is **not yet verified**, and asserting it without checking would repeat
the exact mistake of rounds 13-17.

What *is* established, from the server's own structured log in the CI bundle:

```
llm runtime selected  llm.runtime=ai-sdk llm.provider=opencode llm.model=big-pickle   (x7, every occurrence)
background dependency install failed ... NpmInstallFailedError
  (cause: @opencode-ai/plugin: No matching version found for @opencode-ai/plugin@1.18.23-android)
```

**Every single "llm runtime selected" line across all captured evidence names
`provider=opencode`** — the one provider compiled into the bundle — and there
is not one line naming `google` or `openrouter`. Combined with the user's
dashboard (one request, from the preflight; zero from the turns), the evidence
says the model turn is not reaching the provisioned provider at all. *Why* it
falls back is the open question: provider construction failing, auth not being
resolved, or the model reference not being applied.

The `NpmInstallFailedError` is a second, independent finding: the runtime does
attempt npm work on device and fails, because the payload reports version
`1.18.23-android` while `@opencode-ai/plugin` has no such published version.
That is a real Android-specific defect in its own right (plugins can never
install), logged as an open item.

**The corroborating detail nobody had connected:** every model turn that has
*ever* succeeded on this device used `providerID:"opencode"`,
`modelID:"big-pickle"` — the one provider whose SDK is compiled into the
bundle. Visible in run 8's own netprobe turn.

**What about device run 1's `openrouter` PASS?** It is real evidence
(`reply='P8PROBEOK'`) and is not withdrawn, but it is now the **anomaly** to
explain rather than the baseline, since the same provider produced nothing in
runs 2–8. Until that is explained, §3.1's L2 claim rests on a single
unreproduced observation, and this report should not lean on it harder than
that.

**Corrected status of L2:** not `BLOCKED-GEO`, and not a credential problem —
**`BLOCKED-PAYLOAD`: the provider SDK for any third-party provider is absent
from the runtime payload and cannot be installed at runtime.** This is a real,
actionable product gap, it is ours, and it is fixable — by bundling the
provider SDKs into the payload build (the same way `jsonc-parser` is vendored)
rather than by any further device runs, key rotations, or network changes.

**Process note, recorded deliberately.** Rounds 13–17 produced four
increasingly confident wrong diagnoses — bad key format, account restriction,
geo-block — each built on one observation, while the decisive measurement
(*does a request reach the provider at all?*) was never taken. The user's
dashboard check took seconds and settled it. The harness should have asked
that question directly; `P8_KEYPREFLIGHT` now proves reachability, but nothing
yet proves the **turn** reaches the provider, which is why the gap was missed.

#### 3.1.12 Round 17 — the cold-start number was still measuring the harness

Run 7 reported `319 s wall` against a supervisor window of **17.6 s**
(09:50:46.443 → 09:51:04.055). The round-15 fix made the health probe *work*
but left it inside a 150-iteration host loop that, on every single iteration,
re-wrote the probe file and paid a full `adb` + `run-as` + exec-shim + Bun
cold start. The loop was timing itself.

Round 17 writes the probe once and polls it with a bounded loop **inside a
single device shell** (`dbun_poll`), so one `adb` round-trip covers the whole
wait. Both branches (early success, timeout) were exercised on the host before
shipping. The supervisor-window figures (**13–21.8 s**) remain the numbers of
record; **every `wall` figure from runs 3, 5 and 7 is retracted as instrument
overhead.**

### 3.1.10 Why the CI workflow is red (and why device runs still ran)

A fair challenge from the user: *why run device tests while CI is not green?*

The honest answer is that **"red" here is not one thing**, and the report has
not made that distinction visible enough. Round 15's CI run is
**10 PASS / 7 FAIL / 2 SKIP**, and the seven failures split cleanly:

| CI gate | Red because | Blocks device work? |
|---|---|---|
| KEYPROBE, PERF, HIST | emulator server-process egress (§3.1.1) — model turns get `status=0` | No — the device is the authority for model gates |
| PROVAUTH | classifier sees `APIError status=0`, not a 401, for the same reason | No |
| NETLOSS, BGFG | verdict lines missing / turn-shaped assertions that depend on a model turn | Partly — real defects in the *verifier*, not proven product bugs |
| SESSIONPERSIST | `sessionFound=true userMessageFound=false` — a genuine open defect | **No, and this one should have been fixed first** |

So: five of seven are downstream of the one documented emulator condition, and
the device suite exists precisely because that condition cannot be fixed in CI.
Running on-device was the right call for *those*.

**But the challenge lands on SESSIONPERSIST.** That is a real, reproducible,
model-independent failure that has been carried as an "open item" across
rounds 8–15 while effort went into chasing model turns. It should have been
fixed before spending six device runs on the tool card. That is a
prioritisation error, and it is recorded as one — not as an environment
problem. It is now the top item in the hand-off (§11).

### 3.1.13 Round 18 — P8-SESSIONPERSIST: the ambiguity removed

Per §3.1.10 this was promoted above L2. Investigation first, fix second.

**What the failure actually said:** `P8SESVRFY ok=0 sessionsSeen=7
sessionFound=true userMessageFound=false` — the session itself survived the
process death; only the user message was missing.

**Two hypotheses, opposite fixes.** Either (a) the store loses the message
across a restart — a real product defect — or (b) the message was never stored
before the kill, making the whole verdict a harness race.

**Parsing was ruled out first.** The verifier's matcher
(`(m.info?.role ?? m.role) === "user"` + a text part containing the marker)
was replayed against the real wire shapes: wrapped `{info,parts}`, flat
`{role,parts}`, assistant-only, user-with-empty-parts, and empty list. It
returns the correct answer in all five cases, so the verifier is not
mis-reading a message that is present.

**The race is real and was structural.** `POST /session/:id/prompt_async`
returns **204 the instant the turn is QUEUED**, not when the user message is
durably written. The gate called `force-stop` immediately after that 204, with
no wait — so the app was killed inside the window where the message may only
exist in memory. On the emulator, where model turns are refused outright
(§3.1.1), that window is exactly where every run landed. **The gate could never
distinguish (a) from (b)** — which is why the verdict sat unexplained for eight
rounds.

**The fix is to make the precondition explicit rather than assumed:**

1. `create` now **polls the server until it reports the user message** (30 s
   budget) and publishes the result as `stored=0|1` on `P8SESCREATE`. If the
   message is not readable before the kill, it logs a warning saying a later
   FAIL is the harness's own race.
2. `verify` gives the same grace on the way back (15 s), because the store is
   opened lazily after a cold start — without it a slow reopen reads as
   "data lost".
3. The gate now emits a **diagnosis instead of a bare FAIL**:

| `stored` before kill | verify | verdict |
|---|---|---|
| 1 | ok=1 | PASS — data was there, survived |
| **1** | **ok=0** | **REAL PERSISTENCE DEFECT** — proven product bug |
| 0 | ok=0 | **INCONCLUSIVE (harness race)** — nothing was there to persist |

All three branches were exercised on the host before shipping, as was the
matcher.

**Honest status:** the *ambiguity* is fixed, and that was the actual blocker —
the old gate produced a verdict that could not be acted on. Whether a genuine
persistence defect exists underneath is **still unknown**, and the next CI run
will now say which of the two it is in its own verdict line rather than leaving
it to interpretation. **Status: IMPLEMENTED, NOT YET TESTED.** No claim is made
that the product bug is fixed — none has yet been proven to exist.

### 3.2 Secure-hardware key residency

The app's master key (AES-256-GCM, non-exportable, AndroidKeyStore) is
probed **in-process on every device** that runs the suite:
`KeyInfo.isInsideSecureHardware` (public API) + `isStrongBoxBacked()`
(a `@SystemApi`, probed by reflection — an unreachable method is recorded,
not swallowed). Gate: P8-KEYRESIDENCY (asserts secure hardware, so it reads
FAIL when the hardware is absent — by design it is a *measurement*).

- **CI emulator (x86_64, API 34): `insideSecureHardware=false`,
  `strongBoxBacked=probe-returned-null`, `secretStoreIsHardwareBacked=false`**
  — software keystore, as expected for the software-keymaster emulator.
- **Real device (RMX3830, arm64, API 35): identical result** —
  `insideSecureHardware=false`, `strongBoxBacked=probe-returned-null`,
  `secretStoreIsHardwareBacked=false`.

**Resolution: `TESTED` — and the honest answer is that the keys are NOT in
secure hardware on the real phone.** The app deliberately requests a
software-backed KeyStore key (strongBox was not requested: this device class
does not expose it through the public path, and key attestation was out of
scope). Consequence, stated plainly: **ciphertext-at-rest holds** (keys are
KeyStore-backed, non-exportable, no plaintext on disk — Phase 5 G18 evidence),
**key-extraction resistance is software-keystore level**, not hardware-level.
This is an accepted, documented limitation of the current design; upgrading to
strongBox (where available) is a Phase 9+ hardening option, and the gate will
keep measuring per-device so any such change is immediately visible.

### 3.3 Toybox tar on real API 29

Scope, stated precisely: toybox (`base64 -d | tar xz -C dir`) serves the
**gate-harness staging** on device. The product's own extraction path does NOT
use toybox (Kotlin reads the APK assets; `PayloadExtractor` is JVM-tested).
Blast radius is CI/harness tooling, not the product.

- API-34 half: P8-TOYBOX **PASS** on the CI emulator (every round 4+).
- API-35 half: P8D-TOYBOX **PASS** on the real device (RMX3830).
- **API-29 half: NOT TESTED** — no API-29 device is available to this project.
  Documented as an open limitation. Mitigation: the harness path uses only
  `echo | base64 -d | tar xz`, the oldest stable toybox subset, and the app
  itself never depends on it.

### 3.4 Full G1–G14/H + gate suite on real non-emulator arm64

- Phase 5 (K1–K9, G6–G12, G16 documented-red, G17–G19) and Phase 7 (W/U/L)
  suites re-ran on the CI x86_64 emulator as regression: **P8-P5REG PASS and
  P8-P7REG PASS in every round 4+** (only G16 red, exactly as Phase 5 froze it).
- The arm64 half ran on the real device: R1 facts, R2 toybox, R3 install +
  cold start, R4 `StressRecoveryGatesTest` (KEYRESIDENCY/PROVAUTH/SERVERKILL/
  LIFECYCLELOG all executed on arm64 with the LD_PRELOAD seccomp path — note
  the device log: `arm64: skipping child BPF filter; using LD_PRELOAD seccomp
  handler`, a code path the x86_64 emulator never exercises), R5
  `LiveToolCallGatesTest`, R6 footprint. **TESTED** (subset, by design — the
  destructive host gates do not run on a personal device).

### 3.5 P5-G16 remote HTTP/SSE MCP

**Unchanged: documented-red.** Upstream `anomalyco/opencode#47644` (swallowed
error in the remote MCP transport). No client-side workaround is attempted;
the Phase 5 regression fold treats "only G16 red" as the steady state and
anything else as a regression. See
`docs/progress/upstream-issue-47644-mcp-swallowed-error.md`.

## 4. Security checklist (re-verified this phase)

| # | Item | Evidence channel | Status |
|---|---|---|---|
| 1 | App-private runtime/project storage | Phase 5 G18 + Phase 7 W2 re-run in every CI round (P8-P5REG/P8-P7REG PASS); device `files-layout.txt` | TESTED |
| 2 | Loopback-only OpenCode server | Phase 5 G17 re-run verbatim (proc-net audit + `loopback-audit.txt` + external-interface refusal); device `runtime.log`: `SERVER_BOUND url=http://127.0.0.1:4111/` + `BIND_AUDIT tcp=unreadable(EACCES)` on the phone | TESTED |
| 3 | Keystore-backed credentials, no hardcoded secrets | G18 (OCS2 blobs, no plaintext, 0600 auth.json) + G19 (APK/payload/source secret scan) re-run; **P8-CLEANUP PASS on CI and device** (the injected key is provably gone from Keystore + server + harness dir) | TESTED |
| 4 | No root requirement | Every host action uses `adb shell` + `run-as` (debuggable CI build); the product requires nothing beyond a normal install (the real-device run proves it on a stock phone) | TESTED |
| 5 | Extracted artifacts checksum-validated before use | JVM `PayloadExtractionValidationTest` (full failure matrix + tar reader); on-device **P8-CORRUPT PASS** (truncated launcher + sha-mangled bundle + wiped marker → re-extracted → healthy, CI rounds 6–9) | TESTED (JVM + on-device) |
| 6 | Workspace boundaries + permission flows | Phase 7 W2 + Phase 6 U3 re-run (P7 regression, every round) | TESTED |
| 7 | (Phase 8 addition) Key residency measured per device | P8-KEYRESIDENCY on CI emulator AND real device — measured, both software keystore (§3.2) | TESTED (measurement) |

## 5. Real-device suite (user's machine)

**Executed** by the user on 2026-09-08 (bundle `p8d-out/`, commit `59276c3`):

| Gate | Result | Note |
|---|---|---|
| P8D-TOYBOX | PASS | API 35 half closed |
| P8D-COLDSTART | PASS | launch → loopback HTTP in **20 s wall** (fresh install incl. first extraction); supervisor window 15.19:40.497 → 15.19:55.947 (15.45 s EXTRACTING→HEALTHY on real silicon) |
| P8D-KEYRESIDENCY | FAIL (measurement) | software keystore on real hardware — the intended finding, §3.2 |
| P8D-PROVAUTH | FAIL | invalid key → silent empty turn (no server-recorded error, no UI error text) — the upstream shape of §7 |
| P8D-SERVERKILL | PASS | SIGKILL → supervised restart, healthy again (arm64, LD_PRELOAD seccomp path) |
| P8D-LIFECYCLELOG | PASS | 0 illegal transitions, 4 starting / 3 healthy transitions |
| P8D-KEYPROBE | **PASS** | real OpenRouter round-trip on real hardware (reply `P8PROBEOK`, model `nvidia/nemotron-3-ultra-550b-a55b:free`) |
| P8D-TOOL | SKIP | 480 s budget, `replyChars=0` — turn never completed (free-tier throughput); re-run pending with 900 s budget (§3.1) |
| P8D-CLEANUP | PASS | key removed from the phone |

Suite defects found in this run and fixed (round 10): the summary's
verdict-parsing lost verdicts that were plainly present in its own files
(environment-dependent grep behaviour on the user's Windows shell) — replaced
with a single-awk parser + logcat as a third verdict source + a diagnostic
dump on miss; the device verdict file is now cleared per instrument run.
(One more capture gap: `storage.txt` came back empty because `du` was run as
the `shell` user, which cannot read `/data/data/…` — the emulator's
`du`-via-`run-as` number is reported in §6 instead.)

## 6. Performance (measured, not guessed)

Channels: the supervisor's own timestamped `runtime.log` (EXTRACTING→HEALTHY
windows), `p8-perf.js` (op latencies + SSE turn), `dumpsys meminfo`, `du`
footprints — under P8-PERF / stage E / device R3+R6.

| Metric | Emulator (x86_64, CI) | Real device (arm64) |
|---|---|---|
| Cold start (fresh install, incl. first extraction) | first-launch supervisor window ≈ **9.6 s** (round 7/8 `runtime.log`) | **20 s wall** launch→loopback (incl. install); supervisor window **15.45 s** |
| Warm supervisor window (EXTRACTING→HEALTHY, validated, no re-extraction) | median **≈2.5 s** (11 observations, 2.4–7.7 s) | 15.45 s (first launch only; warm windows not separately isolated) |
| OpenCode server startup (STARTING→HEALTHY) | included in the windows above (log: `STARTING (starting OpenCode server (attempt 1))` → `HEALTHY`) | 15:34:38.150 → 15:34:44.537 ≈ **6.4 s** (device `runtime.log`, second launch) |
| Warm restart (supervisor stop→start→HEALTHY) | **22 000 ms** (round 8, after the HOME-background fix; round 6 pre-fix: 260 000 ms) | NOT MEASURED (not in the device suite's destructive set by design) |
| Session create / list / status (API) | **18 / 5 / 8 ms** | NOT MEASURED (device suite scope) |
| File list (small) / content | **146 / 32 ms** | NOT MEASURED |
| File list / content — 2000 files / 52.5 MB project (P8-LARGE) | list **171 ms** (2000 entries), content **252 ms** | NOT MEASURED |
| Git init+add+commit on the 2000-file project | **1 000 ms** (round 8; round 9: 0 ms on the pre-built tree) | NOT MEASURED |
| Shell op (OpenCode's own `/shell` endpoint) | **613 ms** (`shellOk=1`) | indirectly: the server-context egress probe round-tripped a shell turn in ~0.9 s (round 9) |
| Streaming TTFT / full turn (provisioned model) | **BLOCKED-CI** (`turnMs=240 002 streamTimedOut=1` — the emulator egress condition of §3.1.1) | NOT MEASURED as TTFT; wall evidence: KEYPROBE 1-line round-trip ~4 min (free tier), model round-trip itself `TESTED` |
| Memory — app process (PSS) | in `p8-meminfo-full.txt` evidence | **90 252 KB ≈ 88 MB** (Java 10.4 + Native 18.2 MB; server runs as a separate process) |
| Memory — server process | NOT MEASURED (separate process; gap recorded, not estimated) | NOT MEASURED (same gap) |
| CPU profile (steady state) | NOT MEASURED — no steady-state CPU sampling was run; startup windows above are the timing evidence | NOT MEASURED (same) |
| Storage — `filesDir` footprint | **212 536 KB ≈ 208 MB** (opencode 89.5 MB, xdg 9.5 MB, …) | `du` capture failed on the device (shell-user permission; §5); file layout recorded in `p8d-out/files-layout.txt` |

Note on "proot/compat-layer overhead": this runtime uses **no proot and no
compat layer** — Bun is the official Android (bionic-linked) build, git and
ripgrep are NDK-built natives, and the W^X seccomp policy is an LD_PRELOAD
handler (BPF child filter on x86_64, preload on arm64 — both observed in the
device logs). The measured numbers ARE the native-stack numbers; there is no
proot tax to attribute, and this report says so rather than estimating a fake
number. The two NOT-MEASURED rows are open items for Phase 9's profiling pass,
not gaps hidden inside a number.

## 7. Offline / failure-state distinguishability

Mechanism (Phase 6, re-verified): `UiError` classifies from the SERVER's own
words (name → status → retryable → hints, AUTH before NETWORK); the UI renders
per-kind human-readable copy ("The model key was rejected" vs "The model
service is unreachable") with the raw upstream text in the disclosure.
`IMPLEMENTED + TESTED` (JVM: `UiErrorOfflineTest` pins the full order; the
status=0+empty-message shape classifies to PROVIDER_OTHER by design).

Live proofs attempted — and the honest result:

- **P8-PROVAUTH (real rejected key, real UI, real provider): FAIL in every
  round (CI 6–11, device).** Round 11, with the round-9 probe fix
  (synthetic part for zero-part messages), finally saw what the server
  recorded: `serverSawError=true serverErrorName=APIError
  serverErrorMessage='' serverStatus=0 classified=PROVIDER_OTHER` — the
  probe fix works, and the classifier is doing exactly what the JVM tests
  pin for that shape (status=0 + empty message is NOT an auth shape, so
  PROVIDER_OTHER is the correct, honest label). But on the CI emulator the
  emulated-egress condition masks the provider's real 401 (the request never
  arrives, so no 401 comes back), and on the first device run the probe was
  the blind round-8 build. The clean 401 → `PROVIDER_AUTH` → "key rejected"
  live proof is therefore expected from the pending device re-run (working
  egress + fixed probe). Until then: `IMPLEMENTED + TESTED` (JVM) for the
  classifier, **live proof PENDING** — not claimed.
- **P8-NETLOSS (network pulled mid-task): FAIL (rounds 6–10).** The cut WAS
  effective (the turn failed — `status=error`), but the error text was empty
  (same silent shape) and recovery was not demonstrated. `BLOCKED` by the same
  upstream surface behaviour.
- **P8-BGFG (90 s in background with a turn in flight): FAIL** — no verdict
  line surfaced (the mid-background health WAS 200, i.e. the server kept
  working backgrounded; the gate's completion assertion never fired).
  `BLOCKED` — open item (§13).
- The *runtime-unavailable vs provider-unreachable vs provider-auth*
  distinction is therefore: `IMPLEMENTED + TESTED` at the classifier level,
  `TESTED` for runtime-unavailable (stage A/B run on a not-yet-healthy server
  every round), and **not live-proven** for the two provider shapes because of
  the upstream silent-completion behaviour above.

## 8. Test matrix — final status

### Unit (JVM, `app/src/test`) — 272 tests, GREEN in every CI round 4+

| Area | File | Status |
|---|---|---|
| State machine (17 edges, closure, re-entry, dead ends) | `RuntimeStateMachineTest` | TESTED |
| Restart backoff (schedule, jitter band, floor/cap, give-up, worst-case budget) | `RestartBackoffTest` | TESTED |
| Env construction (app-private XDG/HOME/TMPDIR, PATH order, fail-closed loopback hostname, server user/port pins) | `RuntimeEnvTest` | TESTED |
| Health-check wire contract (localhost socket, Basic auth, 2xx+"healthy" rule, 401/500/refused, bounded wait) | `HealthCheckerTest` | TESTED |
| Manifest/version pins (six pins, safe-path rules incl. backslash normalization, fromJson refusals, round-trip) | `RuntimeVersionTest` | TESTED |
| Extraction validation + tar reader (failure matrix, ustar fixture, traversal/truncation refusal, sha256) | `PayloadExtractionValidationTest` | TESTED |
| Offline error classifier (name→status→retryable→hints order, AUTH before NETWORK, per-name fallbacks) | `UiErrorOfflineTest` | TESTED |
| Project-store persistence (restart = new instance over same storage, stale pointer fallback, legacy discovery) | `ProjectStorePersistenceTest` | TESTED |
| Pre-existing 187 tests (Phase 0–7) | — | TESTED (no regression in any round) |

### Integration / on-device (host-driven + instrumented)

| Gate | What it proves | CI (x86_64) | Device (arm64) |
|---|---|---|---|
| P8-P7REG | Phase 7 suite (W/U/L) | **PASS** (rounds 4+) | — (not in device scope) |
| P8-P5REG | Phase 5 suite (K1–K9, G6–G12, G16 red, G17–G19) | **PASS** (rounds 4+) | — |
| P8-KEYRESIDENCY | where the master key lives | FAIL-as-measurement: software keystore | FAIL-as-measurement: software keystore (§3.2) |
| P8-PROVAUTH | rejected key → auth failure (UI + classifier agree) | FAIL (silent upstream shape, §7) | FAIL (same shape) |
| P8-SERVERKILL | SIGKILLed server → supervised restart, bounded | **PASS** | **PASS** |
| P8-LIFECYCLELOG | zero illegal transitions in a clean run | **PASS** (0 illegal, 9 transitions) | **PASS** (0 illegal, 4 transitions) |
| P8-KEYPROBE | the provided key serves a model round-trip | FAIL — BLOCKED-CI egress (§3.1.1) | **PASS once (run 1, `P8PROBEOK`), FAIL runs 2–8** — not reproduced; see §3.1.11b |
| P8-TOOL | **the real tool call (L2 close)** | never reached (key probe first) | **SKIP in all 8 runs — CARRIED to Phase 9** as `BLOCKED-PROVIDER-SELECTION` (§3.1.11b) |
| P8-CLEANUP | injected key revoked from server + Keystore | **PASS** | **PASS** |
| P8-TOYBOX | harness staging path on this API | **PASS** (API 34) | **PASS** (API 35) |
| P8-CORRUPT | corrupted payload → re-extract → healthy | **PASS** (rounds 6–9) | — |
| P8-CRASH | force-stopped process → relaunch → healthy | **PASS** | — |
| P8-SESSIONPERSIST | session + user message survive process death | **FAIL, now DIAGNOSABLE** — round 18 proves whether the message was stored before the kill and reports `REAL PERSISTENCE DEFECT` vs `INCONCLUSIVE (harness race)` (§3.1.13) | — |
| P8-NETLOSS | network loss mid-task → network-shaped error → recovery | FAIL (effective cut, silent error text, §7) | — |
| P8-BGFG | background 90 s with turn in flight → completes cleanly | FAIL (health 200 in background; verdict never fired, §7) | — |
| P8-LARGE | 2000 files + 50 MB: API + git stay responsive | **PASS** (list 171 ms, content 252 ms, git 1 000 ms) | — |
| P8-HIST | 40 sequential turns in one session; tail latency | **FAIL (round 11, strict)**: `turns=5 textTurns=0 failedTurns=5 bail=5` — turns hang (120.5 s avg, no completion, no error) under the §3.1.1 condition; the strict driver works as designed (round 9's false-positive version is superseded) | — |
| P8-STORAGE | low storage: honest behaviour, then cleanup | **PASS** (rounds 8, 11: free 4.37 GB → 2.53 GB under pressure → 4.37 GB after cleanup) | — |
| P8-PERF | the measured numbers of §6 | numbers captured (rounds 8 + 11); model-stream half BLOCKED-CI (`turnMs=240 002 streamTimedOut=1`) | subset (R3/R6) |
| P8D-BUNLAUNCH | the app's Bun launch path works; a raw one does not | — | **PASS** (shim `rc=0`, raw `rc=127`) — added round 15 (§3.1.7) |
| P8-KEYPREFLIGHT | provider reachable AND credential/model usable, before the model gates | — | **PASS** (run 8: `key=OK http=200 models=40 modelUsable=true`) — added round 16 |
| P8D-COLDSTART | launch → server answering loopback | — | **PASS** — supervisor window **13–21.8 s**; `wall` figures retracted (§3.1.12) |

## 9. Regressions and defects found and fixed during this phase

1. **Compile round 1** (run 34202030317): six error classes in the new tests
   (enum-as-alias, missing import, `Thread.stop()` collision, nullable
   `.trim()`, compose-rule type resolution on 1.6.8, `@SystemApi`
   `isStrongBoxBacked` → reflection). Fixed in `d0f729f`.
2. **D8 dexing** (round 5, run 34206564273): androidTest dex limit — fixed by
   the build change in round 6.
3. **Kotlin `ByteArray.copyInto`**: 3rd positional arg is
   `originalStartIndex`, not length — fixed where used.
4. **HIST false positive** (round 8 → fixed rounds 9/10): 40 empty-part
   assistant completions counted as 40 successes; the driver now requires the
   turn's own marker text, surfaces `lastInfoError`, and bails after 5
   consecutive non-answering turns.
5. **Probe blind spot** (round 9 fix): `P8GateSupport.serverParts()` dropped
   `info.error` on zero-part messages — now synthesizes one part so
   `errorInNew` can see it.
6. **Key starvation ordering** (rounds 6–8 bug, fixed round 9): PROVAUTH's
   destructive cleanup ran before KEYPROBE/TOOL and deleted the run's key;
   stage reorganization (model gates in the post-reboot window, per-method
   stress runs, re-provision after PROVAUTH).
7. **g00 probe ran before server ready** (round 9 → fixed round 10):
   `P8NETPROBE_UI no session` — now 30×1.5 s createSession retries.
8. **Device-suite verdict parsing** (device run → fixed round 10): summary
   dropped verdicts present in its own files; single-awk parser + logcat
   third source + diagnostic dump.
9. **Per-run runtime.log pull used the wrong path** (round 10 → fixed):
   `log/runtime.log` instead of `files/log/runtime.log` (silent behind
   `|| true`; every captured tail was empty). Fixed in the round-12 tree.
10. **CI-emulator model egress** (§3.1.1): not a code defect — an environment
   finding, isolated by the probe matrix, documented as BLOCKED-CI.

## 10. Model key handling (deliverable)

- A fresh, short-lived `OPENROUTER_API_KEY` was set as a repo secret by the
  user for the phase's model-dependent testing. **The user rotated their
  account key mid-phase** (the device run used a fresh key typed at the
  terminal; the repo secret still holds the first key). The round-10/11
  `P8NETPROBE_AUTH` probe resolved its state: **the repo key is live
  (`/auth/key` → http=200 from both probe contexts)** — so no CI model result
  in this phase can be blamed on the credentials.
- Usage confined to: P8-KEYPROBE, P8-TOOL, P8-PERF streaming, device R5, and
  the diagnostic probes (never in logs — the key travels base64-over-stdin).
- Hygiene proven: device harness dir cleaned by the suite (R5 tail) AND by
  P8-CLEANUP (Keystore + auth store, PASS on CI and device); CI host removes
  its copy at run end.
- **🔴 ACTION REQUIRED — a live Gemini API key was leaked to this public
  repository.** Round 15's raw-transcript capture wrote Bun's connection error
  verbatim, and that error contains the full request URL including
  `?key=<the Gemini key>`; the file was uploaded in commit `52e7c4d`
  (`p8d-out/key-preflight.txt`). The working-tree value is now redacted and the
  suite redacts all artifacts (§3.1.8), **but the key is still readable in Git
  history and must be considered compromised.** The user must:
  1. **Revoke/delete that key now** at <https://aistudio.google.com/apikey>
     (deleting the key is sufficient; rewriting history is not required and is
     not a substitute for revocation).
  2. Create a replacement key for any further runs.
  The agent cannot revoke Google credentials; this is the user's action.
- **Removal from the repository secrets: PENDING (user action).** The bot token
  cannot manage repository secrets (403), so the user must delete
  `OPENROUTER_API_KEY` under *Settings → Secrets and variables → Actions*.
  Phase 8 no longer needs it: the CI model gates are BLOCKED-CI by design
  (§3.1.1) and every model verdict now comes from the device suite, which takes
  its key interactively at the terminal. **Nothing in Phase 8 is waiting on this
  secret; it should simply be removed.**

### 10.1 Secret-handling defect found and fixed in this phase

Round 15 leaked a live Gemini key into this public repository (§3.1.8). The
mechanism is worth recording because it generalises: **an error string is not
opaque text.** Bun includes the full request URL in connection errors, and
Google's API carries the credential in the query string, so a verbatim
"capture the raw transcript for better diagnostics" change published a secret.

Controls now in place:

- `redact()` in the device suite filters `AQ.…`, `AIza…`, `sk-or-…` and
  `Bearer …` out of **every** artifact it writes, applied at the point of
  capture rather than before upload.
- The on-device preflight never prints the key, and never prints a URL
  containing it.
- The key still travels base64-over-stdin and is deleted from the device
  (`harness/model-key`, `harness/provider`) at the end of every run, proven by
  `P8D-CLEANUP PASS` in all eight runs.

Residual risk: the leaked key remains in Git history at `52e7c4d`. Redaction of
the working tree does not remediate that — **only revocation does**, which is
the user's action.

## 11. Hand-off to Phase 9

Phase 9 (release packaging/docs) starts from: this report's final state, the
evidence bundles in `docs/progress/phase8-evidence/` (CI) and `p8d-out/`
(device), and these explicit open items:

0. **🔴 REVOKE THE LEAKED GEMINI KEY** (§3.1.8, §10) — highest priority, and it
   is a user action the agent cannot perform.
1. **P8-SESSIONPERSIST — ambiguity resolved in round 18 (§3.1.13), verdict
   pending.** The gate now proves the user message was stored *before* the kill
   and reports `REAL PERSISTENCE DEFECT` vs `INCONCLUSIVE (harness race)`
   explicitly. Read that line in the next CI run: if it says the former, there
   is a genuine store bug to fix in Phase 9; if the latter, the eight rounds of
   red were the harness and the gate is now sound.
2. **L2 — `BLOCKED-PROVIDER-SELECTION` (§3.1.11b). The `BLOCKED-GEO` closure is
   RETRACTED, and so is the follow-up "SDK missing from payload" claim.** Run 8
   showed `key=OK models=40 modelUsable=true`; the user's dashboard showed one
   request (the preflight) and none from the turns; and every
   `llm runtime selected` line ever captured says `llm.provider=opencode`,
   never `google`/`openrouter`. So the turn silently runs on the bundled
   provider instead of the provisioned one. **Next step is diagnostic, not
   another blind run:** round 19 makes the device bundle capture OpenCode's own
   server log (`opencode-server.log` + `provider-used.txt`) and puts the
   server's own words into the KEYPROBE verdict. One run now answers *why* the
   provider is not selected.
3. **`NpmInstallFailedError` on device** — the runtime reports version
   `1.18.23-android`, for which `@opencode-ai/plugin` has no published version,
   so background dependency install always fails. Plugins cannot install on
   Android. Independent defect, newly identified, not yet fixed.
2. **Upstream silent-completion behaviour** (§7/§13): provider failures (bad
   key, network loss) complete as empty turns without a recorded error —
   classify for upstream / work around in the UI layer in Phase 9+.
3. **P8-SESSIONPERSIST FAIL**: session survives process death but the user
   message is not found by the verifier — product bug or verifier bug must be
   decided (open investigation).
4. **API-29 toybox half**: documented untested (§3.3).
5. **Secret removal confirmation** (§10).
6. **NOT-MEASURED perf rows** (§6): server-process memory, steady-state CPU —
   Phase 9 profiling pass.

## 12. CI run history (for the record)

| Run | ID | Outcome |
|---|---|---|
| 1 | 34202030317 | compile failures (6 classes, §9.1) |
| 2 | 34202618656 | compile fixes iteration |
| 3 | 34204522727 | iteration |
| 4 | 34206077181 | **SUCCESS** (first green: gates + 272 JVM) |
| 5 | 34206564273 | D8 dex fail (fixed §9.2) |
| 6 | 34207634094 | 8 PASS / 10 FAIL / 1 SKIP, `model_available=0` |
| 7 | (39 m) | 9 PASS / 8 FAIL / 2 SKIP, `model_available=0` |
| 8 | 34217406687 | 11 PASS / 6 FAIL / 2 SKIP, `model_available=0` (HIST false positive) |
| 9 | 34239793862 | **cancelled by user** mid-HIST after ~2 h (HIST hung on per-turn timeouts; round-10 bail added). Evidence pulled from the branch auto-commit: model-free gates green; `P8NETPROBE_SERVER openrouter http=200 ms=404` in stage D (the decisive probe); `P8_NETPROBE_UI no session` (g00 bug) |
| 10 | 34253707754 | completed (failure = gate FAILs, by design) — first `P8NETPROBE_AUTH` data: **repo key live (http=200)**; per-run `runtime.log` capture (path bug in the pull, fixed in round 12), g00 readiness, HIST bail |
| 11 | 34254826074 | **final CI evidence** (50 min, all stages completed): 10 PASS / 7 FAIL / 2 SKIP — PASS: P5REG, P7REG, TOYBOX, SERVERKILL, LIFECYCLELOG, CLEANUP, CORRUPT, CRASH, LARGE, STORAGE; FAIL: KEYPROBE, PROVAUTH, HIST (strict bail), NETLOSS, BGFG, SESSIONPERSIST, PERF (model-stream half); SKIP: KEYRESIDENCY (measurement), TOOL (key probe first). PROVAUTH fully characterized (§7). Free-tier budgets (300/240 s, 900 s TOOL) live in its APK — **the artifact the device re-run installs** |
| 12 | (triggered by the round-11 report push) | tree-only delta: the per-run `runtime.log` pull path fix (§9.9) — the one addition is the server's own stderr around the model-call failures; APK identical to round 11 |

## 13. Upstream observations (file-worthy, not worked around)

1. **Silent turn completion**: a provider-level failure (invalid key, dead
   network) can leave the assistant message with `parts:[]`, no `info.error`,
   and `time.completed` set — i.e. the turn "succeeds" empty. Observed on the
   pinned `05ea5073` server on both machines, rounds 6–11. A client-side
   error UI therefore cannot rely on the message payload alone; the app's
   classifier handles every shape it receives, but the *shape* is missing
   upstream. (Candidate upstream issue; none filed — phase scope.)
2. **No turn timeout upstream**: an in-flight model turn has no server-side
   timeout; a stalled provider leaves the turn pending indefinitely (rounds
   6–9 CI windows; the 480 s device TOOL window). The harness budgets are the
   only bound today.
3. **Seccomp shim warning** (device `runtime.log`): `[seccomp] failed to load
   shim: bun:ffi dlopen() is not available in this build (TinyCC is
   disabled)` — non-fatal (the LD_PRELOAD path continues), recorded for the
   record.
