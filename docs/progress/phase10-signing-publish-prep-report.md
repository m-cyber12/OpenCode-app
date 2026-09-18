# Phase 10 report — signing, final verification, branding and publish prep

Status: **IMPLEMENTED, gated statically, and NOT YET EXECUTED on a device or in
CI.** Phase 10 has no verified device result to report: the authoring sandbox has
no JDK, no Gradle, no Android SDK, no emulator and no adb, and the Phase 10 CI
workflow cannot be installed by the automation account (measured: HTTP 403
"Resource not accessible by integration" — §1.4). Everything below that involves
building, installing or running anything is therefore labelled **NOT TESTED**, and
the machine steps that will produce those results are written, wired and
statically checked.

- Date: 2026-09-18
- Branch: `arena/01a0b15e-opencode-app` (from `main` @ `fbf3e5f`)
- Pins unchanged from Phase 9: OpenCode `05ea5073` v1.18.23, Bun 1.3.14,
  git v2.48.1, ripgrep 15.1.0, payload v7; app `1.18.23-phase10` (versionCode 8)
- Identity change (the one deliberate product change in this phase):
  applicationId `ai.opencode.android` -> **`io.github.mcyber12.opencode`**;
  display name **OpenCode**; icon = upstream's own; both documented in
  `docs/BRANDING.md` with a rename fallback that does not touch the ID.
- Nothing in this phase rebuilt app logic. The artifacts to sign are the ones
  Phase 9 gated (payload v7, unchanged).

## 0. Honesty table (read this first)

| # | Deliverable | Label | Where the proof is |
|---|---|---|---|
| 1 | Signing documented and reproducible **without a secret touching the repo, CI or chat** | **IMPLEMENTED + TESTED (statically)** — `phase10/scripts/check-release-invariants.py` `INVARIANTS PASS (0 findings, 2 notes)`; `docs/RELEASE.md` complete (keystore generation, resolution order, alignment, verification, Play App Signing, "what CI may never do") | §1, `phase10/out/evidence` in CI, `docs/progress/phase10-evidence/local/static-checks.txt` |
| 1b | A real release keystore exists | **NOT DONE, by design** — human-owned, offline, never seen by this project. `sign-release-local.sh` (the scriptable part of the human step) is written and syntax-checked; it has never signed anything | §1.2 |
| 1c | Signed APK + AAB produced from Phase 9's gated artifacts | **BLOCKED on the human keystore step.** Adds nothing to the app: sign-and-align only | §1.3 |
| 2 | Signed build verified on a real arm64 device (first run, runtime health, live model turn L2, no crashes) | **NOT TESTED** — script written (`90-real-device-signed.sh`, R1–R9), needs a phone, a signed APK and a window of the owner's time. Phase 9's last device evidence is x86_64-only, and was a *debug* build | §2 |
| 2b | R8 / obfuscation breakage | **VERIFIED BY INSPECTION (build configuration), sweep NOT TESTED**: `isMinifyEnabled = false` and no ProGuard/R8 config file is referenced, so the class/method names in the shipped APK are not rewritten and there is nothing for obfuscation to break. The runtime half (no `ClassNotFound`/`NoSuchMethod` in logcat) runs in CI (§2.3) | §2.3 |
| 3 | Final name / icon / branding decision + trademark handling | **DECIDED (owner instruction) + IMPLEMENTED**: name `OpenCode`, upstream's desktop icon, independence + trademark disclaimers in-app (Settings -> About) and in the listing; upstream permission request is an **OPEN item with the OpenCode project** (§3.4) | §3 |
| 4 | Play Store listing package | **SUBSTANTIALLY COMPLETE, 2 gaps**: all text fields, privacy policy, data-safety and content-rating answers, support contact, icon and feature graphic are done; **screenshots are missing** (must come from a device run) and the Console submission itself is obviously not done | §4 |
| 5 | Visual polish + Phase 6 gates re-run (F1–F4, U1–U8, a11y, lazy lists) | **POLISH IMPLEMENTED**; the **static** layer re-run is **TESTED green** (§5.3); the **runtime** F1–F4/U1–U8 gate re-run is **NOT TESTED** — it is wired to run twice per CI run (debug + release-shaped smoke) and any regression fails the job | §5 |
| 6 | Phase 9 carry-forward items | Two unchanged and correctly **not overstated**; the third (live tool call only ever verified on x86_64) now has a machine-readable closing path (§6) | §6 |
| 7 | Walkthrough of `11-FINAL-ACCEPTANCE.md` against the **signed** final build | **BLOCKED** — there is no signed build yet. §7 pre-checks every criterion that can be checked today and says which artifact each one needs | §7 |

## 1. Signing

### 1.1 What "signing cannot leak" means here, mechanically

Three independent things must be true at once, and each is checked by a program
rather than promised in prose:

1. **No key material in the tree.** `check-release-invariants.py` fails the run
   on a private-key header, on a literal password assigned in place (a bare
   identifier or an environment lookup is fine, a quoted literal is not),
   on a keystore file that is not gitignored, and requires `.gitignore` to cover
   `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pepk`. It also
   requires `build.gradle.kts` to read the keystore from *outside* the tree
   (`keystore.properties` / `System.getenv` / `RELEASE_KEYSTORE_FILE`).
2. **CI cannot receive it.** The Phase 10 workflow is checked for any expression
   that would *pull* a signing secret in (`secrets.*KEYSTORE*`,
   `secrets.*KEY_PASSWORD*`, `secrets.*KEY_ALIAS*`). Naming those variables in a
   comment or in the workflow's own "confirm CI has no signing material" guard
   step is required, and is not a failure — the earlier version of this check
   failed on its own documentation, which is a bad check, not a bad workflow.
3. **There is nothing to steal even if CI were compromised.** The release build
   config has no keystore configured, so Gradle produces `app-release-unsigned.apk`
   by construction, and the release stage asserts **unsigned** and fails if it
   ever finds a signature (`40-release-verify.sh`, stage 5).

### 1.1b Provider key vs signing material

The workflow does reference one repository secret, `OPENROUTER_API_KEY`, and that
distinction is deliberate rather than a loophole: it is a **provider credential for
the live-model gates**, not signing material. No keystore, alias or password may be
present in any form. It is optional - the live gates SKIP with the reason on record
when it is absent (the Phase 9 report records the owner deleting it on 2026-09-16, so
a first Phase 10 run will most likely show those gates SKIP and the L2 carry-forward
still open). The release build neither reads it nor needs it.

### 1.2 The human step, scripted (`phase10/scripts/sign-release-local.sh`)

* Refuses a keystore path inside the repository (it exists to be run by the owner
  on their own machine, not by an agent).
* `zipalign -p 4` **then** `apksigner sign` for the APK (in that order: signing
  before alignment yields an unaligned, or worse, invalidated artifact); `jarsigner`
  for the AAB, which is JAR-signed and must not be zipaligned.
* Then verifies what it just produced (`apksigner verify --print-certs --verbose`)
  and writes a report with the certificate SHA-256, both signed artifacts and
  their SHA-256 sums.
* New in this phase: the device script can assert that the artifact was signed with
  **your** key, not merely that it is signed — `--cert-sha256 <digest>` (or
  `P10D_CERT_SHA256=`, or `phase10/signing/expected-cert-sha256.txt`). A
  certificate fingerprint is public data (apksigner prints it; Play Console shows
  it), unlike the keystore, so recording it is safe and makes "wrong key" a FAIL
  instead of a silent upload.

`docs/RELEASE.md` is the operator-facing half: keystore requirements (RSA 4096,
validity 10000 days, `upload` alias, `keytool -genkeypair` command), where the file
may live, why a *half*-configured keystore is a build error rather than a silent
fallback to "unsigned", verification, Play App Signing, and the "never commit it,
and never put it in the repository" rule. Tested statically: the invariants checker
requires `RELEASE.md` to cover `keytool`, `apksigner`, `zipalign`, Play App Signing
and "never commit".

### 1.3 What is NOT TESTED

* No keytool/apksigner/jarsigner exists in this sandbox, so **no signing command in
  this phase has ever run**. `bash -n` passes; that is the strongest claim available.
* The Gradle signing configuration itself (property file first, then `RELEASE_*`
  env vars, then the Phase 9-era `P9_*` aliases kept so an existing local setup
  cannot silently produce an unsigned artifact) has never been resolved by Gradle
  here. It is exercised by the CI smoke build, which uses a *debug* key instead —
  not the same code path, and this report does not pretend otherwise.

### 1.4 The workflow installation blocker (measured)

`.github/workflows/` is the one path the automation account cannot write. Measured
this session with the contents API:

```
PUT /repos/m-cyber12/OpenCode-app/contents/.github/workflows/__arena_probe.yml
-> {"message":"Resource not accessible by integration", "status":403}
```

So `.github/workflows/phase10-release.yml` must be added once by the repository
owner through the GitHub web UI: open `phase10/workflow/phase10-release.yml` on the
branch, **Raw**, select all, copy, then *Add file -> Create new file* with the path
`.github/workflows/phase10-release.yml`, paste, commit to
`arena/01a0b15e-opencode-app`. The file's own header carries the same instructions
so it cannot get lost. Until that commit happens no Phase 10 CI verdict exists, and
the invariants checker says so as a NOTE rather than as a failure (it is expected
before the first install).

## 2. Real-device verification of the signed build

### 2.1 Why this is a separate script from every earlier phase

Phases 4–9 all verified **debug** builds, and Phase 10's CI verifies the
release-*shaped* smoke build. Neither is the artifact a user installs. Signing and
release packaging change exactly the things that break quietly:

| What changes | Why it can only be seen on the real artifact |
|---|---|
| non-debuggable | `run-as`, the test-only harness dir and the instrumented suites all disappear; every former gate shortcut is gone |
| different applicationId | the data directory, the stored secrets and the first-run path are all fresh |
| R8/ProGuard | off today, so the *absence* of breakage is what must be recorded (§2.3) |
| native lib extraction + payload validation | the first run does the expensive work that a debug install may already have done |

### 2.2 What `90-real-device-signed.sh` checks (R1–R9)

| ID | Check | Fails how |
|---|---|---|
| R1 | device facts: `ro.product.cpu.abi` must be arm64, Android version, free storage | non-arm64 device, insufficient space |
| R2 | artifact inspected (`check-apk.py --expect-signed --expect-not-debuggable --expect-icon --expect-payload --expect-package --expect-version-name --expect-version-code --expect-native-abi arm64-v8a --expect-min-sdk 29`) + `apksigner verify` + **certificate matches your key** | wrong key, wrong identity, unsigned, payload missing |
| R3 | clean install (`adb uninstall` first) of the signed APK | sign/ABI/minSdk mismatch |
| R4 | first-run flow through the UI, driven by the accessibility tree (no hardcoded coordinates) | a first-run screen that never appears |
| R5 | store-quality screenshots from the signed build (`70-device-screenshots.sh`) | fewer than 2 usable frames |
| R6 | a real turn typed into the composer, and the answer read back from the UI **plus a Shell-command tool card** — that is the L2 claim | "no provider error and no answer" is a FAIL, "no model served it" is a FAIL with the app's own reason |
| R7 | footprint: total PSS, app-private storage after payload extraction | — |
| R8 | packaging/obfuscation sweep over the session's logcat: `FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError`, `NoClassDefFoundError`, `UnsatisfiedLinkError`; then a crash count | any hit is a FAIL and the lines are in the bundle |
| R9 | verdict bundle: `SUMMARY.txt`, run log, redacted logcat, UI dumps, artifact report, screenshots; credentials filtered by a `sed` redactor before anything is written | — |

R6 deliberately requires **both** the answer text and a tool card: a model that
echoes the prompt without running the command would otherwise be recorded as a
passing live tool call. The run also appends the `P6_MODEL_AVAILABLE 0|1` marker
Phase 6 uses, so the x86_64-only carry-forward (§6.1) can be closed by reading a
file instead of trusting a sentence.

### 2.3 R8/ProGuard/minification: the actual finding

`app/build.gradle.kts` has `isMinifyEnabled = false` for the release build type and
references **no** ProGuard/R8 configuration file. Therefore:

* **IMPLEMENTED/TRUE TODAY:** class and method names in the shipped APK are not
  rewritten, unused code is not stripped, and reflection-driven code (Compose
  runtime, AndroidX, the app's own `RuntimeVersion` and JSON models) cannot be
  removed. There is no obfuscation-related failure mode in the current artifact.
* **Why it stays off for the first release:** 123.1 MB uncompressed of the 130.6 MB
  APK is four native binaries per ABI (Phase 9 evidence); the shrinkable part is the
  Kotlin dex, a few MB. R8 would buy a rounding error on a 130 MB download while
  adding a class of runtime failure that is invisible in debug builds. That is a
  deliberate trade recorded in `versions.lock`'s `app:` block, not an oversight, and
  it is reversible later with a proper keep-rule audit.
* **NOT TESTED:** the *runtime* half — that the release APK's classes actually load
  on a device — can only be observed by installing it. R8 does exactly that (plus
  R2's `--expect-not-debuggable`), and the smoke build gives the CI half. This
  report does not claim any of it has happened.

## 3. Branding and naming

### 3.1 The decision (owner instruction, and what it costs)

Name: **OpenCode**. Icon: **upstream's own desktop icon** (imported from the
project's repository, unmodified, 5 densities × `ic_launcher` / `ic_launcher_round`
/ `ic_launcher_foreground` plus two adaptive-icon XMLs). If no agreement is reached
with the OpenCode team, a **new name and icon will be adopted** — the fallback is
kept live and cheap (§3.3).

### 3.2 What the risk analysis found (`docs/BRANDING.md`)

* The upstream repository is **MIT** (verified via the GitHub license API), which
  covers the *code and the assets' copyright*, not the *name*: trademark and
  copyright are different rights.
* **No trademark/brand-usage policy exists at the pinned commit** — the README has
  no brand section (searched), and the brand assets ship with a README that
  documents how to regenerate them, not what may be called what.
* The Play listing must therefore **not imply endorsement**, and the app must say
  what it is. That is enforced in three places, not by a promise:
  1. `strings.xml`: `settings_about_independent` ("This is an independent client
     built on the open-source OpenCode project. It is not built, published or
     endorsed by the OpenCode project or its maintainers.") and
     `settings_about_trademark`, rendered in **Settings -> About** with links to the
     upstream project and its licence.
  2. `docs/STORE-LISTING.md`: both descriptions state independence explicitly, and
     the invariants checker *requires* that statement to be present in the listing
     file (it fails the run if the listing loses it).
  3. `docs/BRANDING.md`: the open-items checklist the owner takes to upstream.

### 3.3 The rename fallback is structural, not cosmetic

The application ID is `io.github.mcyber12.opencode` — a neutral token in the
developer's own GitHub namespace, deliberately **not** `ai.opencode.*` (upstream's
real domain, which reads as first-party and is an impersonation risk in review).
Consequences, all checked by `check-release-invariants.py`:

* A rename changes `app_name` in `strings.xml`, the icon files, the store assets and
  the docs. It cannot change the ID, because Play never accepts a change of
  applicationId for an existing listing, and does not need to.
* The invariants checker refuses `ai.opencode.*` as a published identity and
  requires `versions.lock`'s `app:` block to agree with `build.gradle.kts`.
* The Kotlin `namespace` stays `ai.opencode.android` on purpose: 93 source files and
  the R/BuildConfig package would move for zero user-visible benefit. It is recorded
  as internal in `README.md`, `docs/ARCHITECTURE.md`, `versions.lock` and
  `docs/BRANDING.md` so it cannot be mistaken for the published identity.

### 3.4 Open item (owner action)

**Request permission from the OpenCode project for the name and icon**, with the
fallback ready. This is a negotiation with a third party, not a task this project
can complete, and until it lands the listing and the app state independence rather
than permission. Nothing about it blocks the technical work.

## 4. Play Store listing package

Complete in this repository (`docs/STORE-LISTING.md` + `docs/PRIVACY-POLICY.md` +
`docs/store/`):

| Field | Value |
|---|---|
| App name | OpenCode (`strings.xml` `app_name`; 30-char Play limit respected) |
| Short description | present, `short_description:` block, checked <= 80 chars |
| Full description | present, `full_description: \|` block, checked <= 4000 chars, states independence, does not promise remote MCP, does not overstate keystore residency |
| Category / tags | Tools/Developer (as recorded in the listing file) |
| Contact / support | `https://github.com/m-cyber12/OpenCode-app/issues` (GitHub Issues, per the owner's instruction) |
| Privacy policy URL | `https://github.com/m-cyber12/OpenCode-app/blob/main/docs/PRIVACY-POLICY.md` — valid: **the repository is public** (verified via the GitHub API this session), so the URL is reachable without an account |
| Data safety answers | derived from the implementation, not from a template: no account, no analytics, no crash SDK, no advertising; provider keys encrypted with an AES-256-GCM key in the Android Keystore and never exported; everything else app-private; traffic only to the model provider you configure and whatever a tool the user permitted fetches (nothing else is initiated by the app; the network-security config permits cleartext only to loopback) |
| Content rating questionnaire | answers recorded in the listing file (developer tool, no user-generated content distribution, no ads, no in-app purchase) |
| Monetization | **completely free: no billing library, no IAP, no ads, no subscription** (owner instruction). `versions.lock` pins `monetization: free`; the invariants checker reads it |
| Icon (512×512) | `docs/store/icon-512.png` — upstream's `prod/icon.png`, unmodified |
| Feature graphic (1024×500) | `docs/store/feature-graphic-1024x500.png` — reproduced from upstream's own wordmark plus two lines of type, in this app's own theme colours, by two documented ImageMagick commands (`docs/store/README.md`) |
| Screenshots | **MISSING (0 files)** - and now a **gate**, not a checklist line: the pipeline runs the strict store-package check after the capture stage and records `P10_STORE_ASSETS` in `GATES_SUMMARY.txt`, so a run without a real image set cannot report a complete store package.  — `phase10/scripts/60-store-assets.sh` requires at least 2, >= 320 px per side and an aspect ratio no wider than 2:1, and refuses anything that was not produced by a device run. The Phase 6/8/9 evidence screenshots are 320×616 (ratio 0.5195) and are therefore **rejected by design** — verified this phase: the validator now reports `14 rejected` for them instead of silently skipping them (§5.4), because they are emulator gate evidence, not listing assets |

The listing text is written so that the three carry-forward limitations stay
honest: remote HTTP/SSE MCP is "an upstream limitation, documented in the app and in
the project's capability matrix, not a hidden failure"; keystore residency is
described as hardware- or software-backed with the app reporting which it got; and
nothing in the listing claims API 29 `toybox tar` behaviour either way.

Not done here, and not doable from a repository: creating the app in Play Console,
uploading the AAB, and Play review. The submission checklist at the end of
`docs/STORE-LISTING.md` is the owner's runbook for those.

## 5. Visual design polish

### 5.1 What changed, and why it is a polish pass and not a redesign

Scope was explicitly "polish the existing Phase 6 UI" — no new screens, no new
navigation, no change to any behaviour, state or data path. Every edit is
dimensional or presentational:

| File | Change |
|---|---|
| `ui/theme/Theme.kt` | shape scale 6/10/14/20/26 -> **8/12/16/22/28 dp** (softer, more generous surfaces without changing the palette or the type scale) |
| `ui/chat/ChatComponents.kt` | tool card on `shapes.large`; a 4 dp **status rail** drawn with `drawBehind` (state is carried by colour **and** position, never by colour alone); `animateContentSize` on expansion; a single chevron that rotates 180° with `animateFloatAsState(tween(180))` instead of swapping glyphs; part spacing 6 -> 10 dp; expanded-content padding 12/12/12 -> 12/12/12/14 dp |
| `ui/chat/ChatScreen.kt` | content padding 14 -> 16 dp, turn spacing 14 -> 18 dp |
| `ui/settings/SettingsScreen.kt` | 12 -> 14/28 dp padding, 12 -> 14 dp spacing |
| `ui/common/Widgets.kt` | section card padding 14 -> 16 dp |

The "assistant talking vs tool running" separation the phase asked for is the
existing one made legible: prose stays in the message column with full-width
markdown, tool calls stay in their own card with a monospace body, a state rail and
a state word (`Queued` / `Running` / `Done` / `Failed`), so the two are never the
same shape. No third-party app's icons, illustrations, colours or brand assets were
copied; the palette and type scale are the Phase 6 ones, and the only external
artwork in the listing is upstream's own wordmark/icon, which is the subject of §3.

### 5.2 The rule that kept this safe for the Compose gates

Every animation added is **finite** (`animateContentSize`, a 180 ms
`tween`). Nothing uses `rememberInfiniteTransition`, which would make
`waitForIdle()`-based Compose test gates hang forever and turn a visual change into
a test-infrastructure failure. `30-static-checks.sh` greps the UI layer for infinite
animations as a static gate so this cannot regress unnoticed.

### 5.3 Gate re-run status — the honest split

* **Static layer re-run: TESTED green** (local, this session, rc=0; log committed at
  `docs/progress/phase10-evidence/local/static-checks.txt`). This covers the checks
  that are code inspections and therefore meaningful without a device:
  UI accessibility (`contentDescription`/labels on every `Icon`/`Image`, accessible
  names on buttons, labels on text fields), lazy-list behaviour (no `.take(N)`
  caps, no eager `forEach` over server-owned collections, required screens on
  `LazyColumn` + `items`), UI strings/resources, UI purity, Kotlin balance and
  nested-comment scan, dex-safe test method names, `versions.lock` == shipped
  `versionName`, plus the Phase 9 and Phase 10 layers and the APK inspector's own
  self-test (17 checks). The Phase 6 layer also now downgrades *only* the
  session-pinned workflow trigger branch to a note (the treatment Phase 9's layer
  already had), so a Phase 6 re-run from this session cannot report a pre-existing
  false FAIL; every structural workflow check still fails the run.
* **Runtime layer (F1–F4 first-run, U1–U8 chat UI, L1/L2 live turn) re-run: NOT
  TESTED.** These need an emulator/device and a built APK. The Phase 10 pipeline
  runs them **twice** — once against the debug build (stage 4a) and once against the
  release-shaped smoke build (stage 4b) — precisely so that "the polish changed how a
  gate behaves" or "the release shape behaves differently" cannot be missed, and a
  failure in either run fails the job. This is the strongest available answer
  without a run, and it is not a result.

### 5.4 Defects found in this phase's own tooling (and fixed)

Writing a gate is not evidence that the gate works. Six defects were found and fixed
before any CI run, five of them of the "would have produced a wrong answer" kind:

1. **`60-store-assets.sh` could not read any image at all, silently.**
   `read -r W H < <(identify -format "%w %h" "$f")` fails, because `identify -format`
   prints **no trailing newline** and `read` reports failure at EOF; the `|| continue`
   then skipped every file. The script reported `0 usable, 0 rejected` - which reads
   like "no screenshots yet" while it would equally have reported that for a directory
   full of unusable ones. Fixed with a here-string; re-run, it now reports the real
   answer: `14 rejected` for the pre-Phase-10 screenshots (section 4).
2. **`60-store-assets.sh` hard-required ImageMagick.** The owner may not have it, and
   the check is a PNG header read. It now uses `identify` when present and a `python3`
   IHDR fallback otherwise - same rule, fewer ways to be unable to check.
3. **The store-asset scroll was CI-only.** `70-device-screenshots.sh` swiped at
   540,1600 -> 540,400, which is right for the 1080x1920 emulator and wrong on a phone.
   It is now derived from `wm size`.
4. **The live-turn prompt would have arrived mangled on the phone.**
   `adb shell input text "Run the shell command 'echo p10-live-ok' ..."` - `adb shell`
   re-quotes what it forwards and `input text` takes a *single* argument, in which `%s`
   is a space; the quotes and literal spaces would be re-parsed by the device shell. It
   now sends `Run%sthe%sshell%scommand%secho%sp10-live-ok%s...`, and taps the composer's
   real placeholder (`Message the agent`).
5. **`$PKG/.MainActivity` is not a valid component.** The Kotlin namespace
   (`ai.opencode.android`) is not the applicationId (`io.github.mcyber12.opencode`), so
   the shorthand resolved to `io.github.mcyber12.opencode.ai.opencode.android.MainActivity`
   and would have failed to launch the app in both device scripts. Both now use the
   explicit `$PKG/ai.opencode.android.MainActivity`, as the Phase 8/9 device suites
   already did.
6. **The orchestrator's verdict file could not see the new stages' verdicts.**
   `50-smoke-gates.sh` and `40-release-verify.sh` keep their own lines files, and the
   summary counted only `$FINAL` - so smoke/release verdicts were invisible to the job
   status. They are now folded in, along with the screenshots verdict (whose log path
   was wrong too).

Each of these is worth naming because a gate that silently passes is worse than no
gate: the first one is exactly the "an empty directory looks identical to a rejected
directory" failure this project has been bitten by before.

## 6. Phase 9 carry-forward items

### 6.1 Live tool call (L2) verified only on the x86_64 emulator, never re-confirmed on physical arm64

**Unchanged in substance: NOT TESTED on arm64.** Phase 8/9 evidence is x86_64, and
the live-model gates have historically SKIPPED when no funded key was available
(Phase 9 recorded L2 as blocked-no-credit). Two things changed in this phase:

* The signed-build device script runs the turn **through the composer on the owner's
  own phone**, reads the result from the accessibility tree, and requires a
  Shell-command tool card in addition to the expected output text (`p10-live-ok`) —
  so an echo-without-execution cannot be mistaken for a live tool call.
* It emits `P6_MODEL_AVAILABLE 0|1` into `p10d-model-lines.txt`, the same marker
  Phase 6 uses, so this item closes by file content rather than by narrative.

Until that run happens, the honest statement in the listing and the docs stays "live
model turns are verified on the emulator; not yet re-confirmed on a physical arm64
phone".

### 6.2 StrongBox/TEE keystore residency and `toybox tar` on real API 29 devices

**Unchanged, as instructed — deliberately not restated as anything better.** Phase 8
recorded that the device's keystore key was **software-backed** (the app reports the
residency it actually got: Settings -> Keys, `Hardware-backed: true/false`), and the
API 29 `toybox tar` behaviour was left as Phase 8 left it. Phase 10 touched neither
claim: the privacy policy states the *capability* (keys are generated inside the
Keystore and cannot be read back out, hardware- or software-backed) and adds the
disclosure that the app shows which one applies, and the listing says nothing about
StrongBox or TEE at all.

### 6.3 G16 remote HTTP/SSE MCP

**Unchanged upstream limitation**, documented in three places and not worked
around: the capability matrix, the in-app Settings text
(`settings_mcp_body`: "Remote HTTP or SSE servers are not supported by this
runtime, which is an upstream restriction rather than an app bug"), and the store
listing. Upstream issue: `anomalyco/opencode#47644`, with the local write-up in
`docs/progress/upstream-issue-47644-mcp-swallowed-error.md`.

## 7. Acceptance pre-check against `11-FINAL-ACCEPTANCE.md`

The checklist itself is part of the project brief and is not in this repository
(same situation Phase 9 recorded). Walking it needs the **signed** final build,
which does not exist yet, so it is **BLOCKED**. What *is* checked today, and what a
final walkthrough adds:

| Acceptance criterion | Today | Needs |
|---|---|---|
| One APK contains the real OpenCode runtime, no Termux/remote server/Kotlin reimplementation | TESTED in Phases 3–9 (x86_64 emulator + arm64 phone) | unchanged by Phase 10; re-asserted on the signed build by R2/R3/R6 |
| Pins match the shipped runtime (`versions.lock` == `RuntimeVersion.kt` == `versionName`) | TESTED statically this phase (`OK versions.lock == ... versionName`) | the same check runs in CI as `P9_LOCK` against the installed build |
| Release artifacts build, are gated, and are unsigned in CI | TESTED in Phase 9 (run 35268937790) | a Phase 10 run with the new invariants + `check-apk.py` inspection |
| The signed artifact is installable and behaves like the gated build | NOT TESTED (no signed artifact) | the owner's keystore + R1–R9 (§2) |
| Store listing is complete and accurate | 2 gaps: screenshots, Console submission | a device run for screenshots, then upload |
| Independence and trademark position are stated | IMPLEMENTED in-app and in the listing; permission negotiation **OPEN** | upstream's answer (§3.4) |
| Every claim in the docs is labelled | maintained through Phase 10 | a final read of README/CAPABILITY-MATRIX after the device run |
| Behavioural difference between signed and unsigned builds is investigated (Core Rule 4) | nothing observed yet (nothing has been run); the packaging sweep R8 is the instrument | the device run |

## 8. Owner action list (in order)

1. **Install the workflow**: GitHub web UI -> `phase10/workflow/phase10-release.yml`
   -> Raw -> copy -> Add file -> `.github/workflows/phase10-release.yml` on
   `arena/01a0b15e-opencode-app` -> commit (the bot token is refused here: §1.4).
   *That commit starts the run.*
2. **Watch the run** (`phases 10 pipeline`, ~2 h: payload build + emulator stages).
   The last line of `GATES_SUMMARY.txt` is the verdict; the artifacts are
   `opencode-android-release` (unsigned APK+AAB) and the evidence bundle.
3. **Generate the upload keystore** offline, on your own machine
   (`docs/RELEASE.md` §2), and store it outside the repository.
4. **Sign CI's artifacts** with `phase10/scripts/sign-release-local.sh` (never
   rebuild: the point is to sign exactly what the gates saw), record the certificate
   fingerprint.
5. **Run `90-real-device-signed.sh` on the phone** with a funded provider key for the
   live-turn gate; keep `p10d-out/`.
6. **Copy the screenshots** from the device run into `docs/store/screenshots/`, run
   `60-store-assets.sh`, and re-run `30-static-checks.sh` with
   `--require-store-assets` (the invariants checker then has no screenshot NOTE).
7. **Play Console**: create the app, paste the fields from `docs/STORE-LISTING.md`,
   upload the signed AAB, complete the data-safety and content-rating forms, submit.
8. Send the permission request from `docs/BRANDING.md`'s open-items checklist.

## 9. Evidence in this phase

| Path | What it is |
|---|---|
| `docs/progress/phase10-evidence/local/static-checks.txt` | the full local static run (rc=0), including the one expected phase-9 trigger-branch line and why it is not a failure |
| `docs/progress/phase10-evidence/` (CI) | written by `00-run-phase10.sh`: `GATES_SUMMARY.txt`, gate lines, artifact JSON from `check-apk.py`, device facts, Phase 6/9 evidence for the same device |
| `phase10/out/` | the orchestrator's working output in CI (gitignored) |
| `p10d-out/` | the signed-build device bundle: `SUMMARY.txt`, `run.log`, redacted `logcat.txt`, UI dumps, `artifact-report.txt`, `apksigner-verify.txt`, `screenshots/` |
| `phase10/scripts/test-check-apk.py` | the APK inspector's self-test (17 checks) — evidence that the verifier itself is not vacuous |

Reproduce the static layer in seconds on any machine with Python 3 and bash:

```bash
bash phase10/scripts/30-static-checks.sh      # rc=0 expected
python3 phase10/scripts/check-release-invariants.py .   # INVARIANTS PASS (0 findings, 2 notes)
python3 phase10/scripts/test-check-apk.py               # SELFTEST PASS (17 checks)
```

## 10. Change inventory for this phase

* **New**: `docs/RELEASE.md`, `docs/BRANDING.md`, `docs/PRIVACY-POLICY.md`,
  `docs/STORE-LISTING.md`, `docs/THIRD-PARTY-NOTICES.md`, `docs/store/` (icon,
  feature graphic, README), `phase10/` (12 scripts, workflow template, README),
  `README.md` doc index and identity rows, launcher icons in 5 densities + adaptive
  XMLs, About/Trademark strings, the `app:` block in `versions.lock`.
* **Changed**: `app/build.gradle.kts` (applicationId, versionCode/Name, signing
  resolution), `AndroidManifest.xml`, the five UI files of §5.1,
  `phase6/scripts/20-ui-gates.sh` (ASCII-only + `P6_PKG` override),
  `phase6/scripts/30-static-checks.sh` and `phase9/scripts/30-static-checks.sh`
  (session-aware workflow check: only the trigger-branch mismatch becomes a note),
  the Phase 4–9 harness scripts (debug applicationId change), `docs/ARCHITECTURE.md`,
  `docs/SECURITY.md`, `versions.lock`, `.gitignore`.
* **Not changed**: any runtime, client, protocol or packaging behaviour. Phase 10
  packages and verifies; it does not re-engineer.
