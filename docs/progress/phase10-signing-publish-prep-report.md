# Phase 10 report — signing, final verification, branding and publish prep

Status: **CI-green on the branch (run #8: 86 counted verdict lines, zero FAIL),
all fixes proven on the artifact.** This header supersedes the original one: the
workflow WAS installed and Phase 10 has now run in CI **eight times**: #1-#6 red
for diagnosed reasons, #7 gates-passed but the job was
GREEN-WHILE-A-GATE-FAILED (the verdict-fold defect, §0b - the scarcest kind of
red: the invisible one), #8 every gate PASS *and counted* on
`arena/01a0b557-opencode-app` (job 35381311437, evidence commits
`2f18da9`/`3bf2721`). Still outside CI and honestly pending: the human signing
step and the signed-build real-arm64 device pass (§1.3, §2) - CI cannot sign by
design. The authoring sandbox has no JDK, no Gradle, no Android SDK and no
emulator; every claim below cites either a static check run here or a
CI-evidence file committed under `docs/progress/phase10-evidence/`.

- Date: 2026-09-18
- Branch: `arena/01a0b15e-opencode-app` (from `main` @ `fbf3e5f`); runs #4-#6's
  fixes and the CI dispatch continue on `arena/01a0b557-opencode-app`
  (from `main` @ `4bac020`) - see §0b for why the work had to move.
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
| 1 | Signing documented and reproducible **without a secret touching the repo, CI or chat** | **IMPLEMENTED + TESTED (statically + CI runs #7/#8: `P10_UNSIGNED PASS`, scheme=none, CI guard step, zero secret pulls)** — `phase10/scripts/check-release-invariants.py` `INVARIANTS PASS (0 findings, 2 notes)`; `docs/RELEASE.md` complete (keystore generation, resolution order, alignment, verification, Play App Signing, "what CI may never do") | §1, `phase10/out/evidence` in CI, `docs/progress/phase10-evidence/local/static-checks.txt` |
| 1b | A real release keystore exists | **NOT DONE, by design** — human-owned, offline, never seen by this project. `sign-release-local.sh` (the scriptable part of the human step) is written and syntax-checked; it has never signed anything | §1.2 |
| 1c | Signed APK + AAB produced from Phase 9's gated artifacts | **BLOCKED on the human keystore step.** Adds nothing to the app: sign-and-align only | §1.3 |
| 2 | Signed build verified on a real arm64 device (first run, runtime health, live model turn L2, no crashes) | **NOT TESTED** — script written (`90-real-device-signed.sh`, R1–R9), needs a phone, a signed APK and a window of the owner's time. Phase 9's last device evidence is x86_64-only, and was a *debug* build | §2 |
| 2b | R8 / obfuscation breakage | **VERIFIED BY INSPECTION (build configuration), sweep NOT TESTED**: `isMinifyEnabled = false` and no ProGuard/R8 config file is referenced, so the class/method names in the shipped APK are not rewritten and there is nothing for obfuscation to break. The runtime half (no `ClassNotFound`/`NoSuchMethod` in logcat) runs in CI (§2.3) | §2.3 |
| 3 | Final name / icon / branding decision + trademark handling | **DECIDED (owner instruction) + IMPLEMENTED**: name `OpenCode`, upstream's desktop icon, independence + trademark disclaimers in-app (Settings -> About) and in the listing; upstream permission request is an **OPEN item with the OpenCode project** (§3.4) | §3 |
| 4 | Play Store listing package | **SUBSTANTIALLY COMPLETE, 2 gaps**: all text fields, privacy policy, data-safety and content-rating answers, support contact, icon and feature graphic are done; **screenshots are missing** (must come from a device run) and the Console submission itself is obviously not done | §4 |
| 5 | Visual polish + Phase 6 gates re-run (F1–F4, U1–U8, a11y, lazy lists) | **POLISH IMPLEMENTED**; the **static** layer re-run is **TESTED green** (§5.3); the **runtime** F1–F4/U1–U8 gate re-run is **TESTED GREEN IN CI** — run #8 executed all 14 gates twice (debug + release-shaped smoke = 28 PASS lines, 0 FAIL); on a signed build / real hardware it remains the human step (§2) | §5, `phase6/` + `smoke-ui/` evidence |
| 6 | Phase 9 carry-forward items | Two unchanged and correctly **not overstated**; the third (live tool call only ever verified on x86_64) now has a machine-readable closing path (§6) | §6 |
| 7 | Walkthrough of `11-FINAL-ACCEPTANCE.md` against the **signed** final build | **BLOCKED** — there is no signed build yet. §7 pre-checks every criterion that can be checked today and says which artifact each one needs | §7 |

## 0b. CI run ledger (runs #1-#6) and the branch recovery (this session)

Phase 10 has now consumed six red CI runs. Every one of them is accounted for
below with the artifact that records it; all are in
`docs/progress/phase10-evidence/` (the run #6 set is committed; earlier runs'
verdicts are recorded here from their session reports and the workflow history).

| run | symptom (from the evidence) | root cause | state |
|---|---|---|---|
| #1 | gradle compile error: `signingLine` "unresolved reference" at the one-line log call after `dependencies` | the `val` was defined inside the `android { }` block; the log line was moved out of scope | FIXED on main by hand-edit (moved `logger.lifecycle(signingLine)` inside the block, before `buildTypes`) |
| #2 | `smokeImplementation(...)` accessor unresolved in the `dependencies` block | type-safe accessors only exist for configurations present when the script starts; `smoke` is created in this same file | FIXED (deferred `configurations.configureEach` lookup; this session re-applied + documented it) |
| #3 | `BuildConfig` duplicate import in `SettingsScreen.kt` | hand-edit added a second import line | FIXED on main (import removed; this session's static layer now greps for duplicate imports via `check-gradle-script.py`) |
| #6 | `P10_SMOKE_APK FAIL`, `RELEASE_APK FAIL`, `UNSIGNED` misreport, `phase9_gate_fails=3` | four checker/packaging defects + one pipeline-sequencing defect, below | FIXED HERE (statically verified; the CI proof is the run this session dispatches) |

The run #6 evidence (`p10-release-apk-report.txt`, `GATES_SUMMARY.txt`,
`00-run-phase10.log`, `p9-provsel-instrument.log`) names all of these precisely:

1. **Label int ref.** Binary AXML stores `android:label="@string/app_name"` as a
   resource-ID int (measured: `label: 2131296266`), and `check-apk.py` asked
   whether `str(label)` starts with `@`. Every real release APK therefore read
   "label is a literal". Fixed: int resource ids are accepted, `label_raw`
   reports the encoding actually seen, and `test-check-apk.py` now encodes all
   three label shapes (string-@, int-ref, literal) and asserts each verdict.
2. **versionName placeholder.** Four scripts extracted the expected version with
   `grep -o 'versionName = "..."' -m1`, which matched the prose comment
   `// versionName = "<pinned OpenCode version>-phase10"` before the real
   assignment (measured: `expected '<pinned OpenCode version>-phase10'`).
   Fixed in all four (40, 50, 90, sign-release-local) with a line-anchored
   grep, plus an explicit FATAL when extraction yields nothing (an empty
   expectation silently passing `--expect-version-name ""` is the same class of
   bug with a different face). `check-gradle-script.py` now keeps the anchored
   form and the file's value in agreement.
3. **Payload staged but not packaged.** The Gradle log said "Runtime payload
   OK" (19,965,610-byte tarball staged), and the release/smoke APKs had no
   payload asset. `verifyAndStagePayload` hangs off `preBuild` only, so the
   merge/compress asset tasks had NO task edge to it, and run #6 also shows
   `compressSmokeAssets FROM-CACHE`: a build-cache entry can legitimately serve
   a merge/compress whose staged-input snapshot predates the payload. Fixed on
   three axes: explicit `dependsOn(verifyAndStagePayload)` for every
   `merge*/compress*/generate*Assets` task, `upToDateWhen { false }` on staging,
   and `outputs.cacheIf { false }` on the asset tasks (they are cheap; the
   correctness is not). Plus: staging now FAILS the build if
   `runtime-payload.tar.gz` is absent while the manifest is present (a
   manifest-only engine tree is a build bug), and prints `PAYLOAD_SOURCE` /
   `STAGED_ASSETS` lines so the next such question is answerable from one log.
   In the same family: `check-apk.py`'s payload survey accepted only
   `.tar.gz`/`.tgz`, but AAPT may store the asset decompressed as
   `runtime-payload.tar` - a fact this repo had already learned in Phase 4
   (both `PayloadExtractor` and the Phase 4 gate accept the two names). The
   survey now mirrors that, reports `PAYLOAD_ASSET name=... bytes=...`, and
   lists every `assets/` entry (`asset_list`) in the report.
4. **UNSIGNED misreport.** The signature-state check appended its full second
   report into the identity report (`>>`), so the file held two concatenated
   verdicts, and the UNSIGNED gate read findings that belonged to the other
   run. Fixed: separate file `p10-release-apk-unsigned-report.txt`, and the
   gate detail line now carries the actual `SIGNATURE scheme=...` so "PASS with
   scheme=none" is visible without opening anything.
5. **NEW this session (the 401 cascade):** run #6's `phase9_gate_fails=3` was
   not one of the four known fixes and would have kept CI red after them.
   `p9-provsel-instrument.log` + `p9-harness-export.log` show every P9 failure
   as `HTTP 401` / "exported credential did not authenticate the live server":
   the debug app's client was authenticating against a FOREIGN server. Stages
   6-7 leave the SMOKE app installed and running, and its runtime binds the
   same fixed loopback port 4111 (`RuntimeEnv.SERVER_PORT`) that the P9 stage's
   debug server must bind - the debug server cannot start, and every P9 HTTP
   call lands on the smoke server with the wrong (other Keystore) password.
   Fixed in `00-run-phase10.sh` stage 9: force-stop both identities and
   uninstall the smoke build (release applicationId; nothing published uses
   it) before the Phase 9 driver runs.

**Branch recovery, stated honestly.** The last session fixed items 1-4 on
`arena/01a0b15e-opencode-app` and committed locally (`b7454f7`, `55267a4`), but
the push failed when the PR merged, so those commits existed only in a sandbox
that no longer exists; `origin/main` advanced with only the partial hand-edits
described in runs #1-#3. This session therefore did not cherry-pick anything -
**the fixes were re-derived from the run #6 evidence and re-implemented here**
(the old objects are unrecoverable), which is also why they now carry regression
tests the old versions did not: `test-check-apk.py` grew from 17 asserted checks
(it hardcoded `10 + 7` while actually running 18 - a small monument to hardcoded
counts) to 26 COMPUTED ones, and a
new `phase10/scripts/check-gradle-script.py` is wired into the static layer to
hold the build-script shapes (including "the run #6 fix is still in the file")
that Phase 9's scans never covered.

### Run #7 (branch `arena/01a0b557`, job 35373257370, `17:35:47Z`): the fixes landed; the VERDICT PLUMBING did not

The owner dispatched gradle-only first (35372594071, 3m1s, **success**: static
+ 279 JVM tests green on the re-applied fixes) and then the full pipeline
(20m46s). The four re-derived fixes and the port-handover fix all proved out
**on the artifact**, from the committed evidence:

| must-show | measured in run #7 |
|---|---|
| label as int resource ref | `label_is_resource: true`, `label_raw: 0x7f09000a` (attr `2131296266`), `findings: []` |
| versionName real, not placeholder | `MANIFEST ... versionName=1.18.23-phase10` on both smoke and release, and the gates' expectation matched it |
| payload staged AND packaged | `STAGED_ASSETS n=2 runtime-manifest.json(187351B), runtime-payload.tar.gz(19965923B)` (Gradle log) -> `PAYLOAD_ASSET name=runtime-payload.tar bytes=108615680` in the APK: the run-#6 "missing payload" was **AAPT's decompress-and-rename** as much as the staging race - half of it the old extension check could never have seen; both mechanisms are now fixed and the two logs agree |
| UNSIGNED with its own report | `p10-release-apk-unsigned-report.txt` separate, `UNSIGNED PASS ... SIGNATURE scheme=none v1_files=0 v2_v3_block=False` |
| P9 handover | `phase9_gate_fails=0` - all five P9 gates PASS on the debug build right after the smoke stage (the 401 cascade is gone), including P9_VERSION `1.18.23` == versions.lock and the PROVSEL stale/rebuilt pair |

**But the job must not have been green, and it was** - two more findings, this
time in the gate plumbing itself (fixed in the commit this ledger lands in,
proven by simulating the orchestrator's fold+count against the new line
format):

6. **False-green verdict folding.** `40-release-verify.sh` wrote its verdicts as
   `RELEASE_APK PASS` / `RELEASE_AAB FAIL` - no `P10_` prefix - while the
   orchestrator folds and counts `^P10_[A-Z0-9_]+ (PASS|FAIL|SKIP)`. Every
   release-stage verdict was invisible: the AAB gate FAILED
   (`P10-RELEASE FAIL: see the log` printed as a human note) and
   `phase10_gate_fails=0` reported a clean run. This is the project's cardinal
   anti-pattern - a gate that cannot fail the build - found by reading the
   evidence instead of the badge. Fixed: the stage's `rec()` now emits
   `P10_<id>` lines (the human log keeps the bare names); the fold counts the
   stage's FAILs, and `P10_RELEASE_SUMMARY pass=N fail=N` cannot collide with
   the verdict pattern.
7. **The AAB expectation was unsatisfiable.** The bundle's protobuf manifest
   DOES carry `1.18.23-phase10` (found inside the printable run
   `versionName1.18.23-phase10...`) but the AAB branch of `check-apk.py`
   demanded an EXACT token match - a rule the file's own `protobuf_strings()`
   docstring says a printable-run scan cannot support, and one the package
   check on the same artifact already uses containment for. Fixed to
   containment for both, with a self-test that (a) reproduces the glued-run
   shape run #7 produced and (b) still FAILS a wrong version
   (`1.18.24-phase10` not found) - containment is not a rubber stamp.
   `test-check-apk.py` is now 27 computed checks.

Two smaller honest notes from the same run: the store-screenshot stage produced
**3** valid shots this time (01-welcome, 05-settings, 06-settings-open-source -
the projects capture was absent; the gate requires >=2, so PASS, and the R5
real-device set replaces the whole directory before submission anyway), and the
payload tarball's sha drifted (`8d97d871...` -> `39f8f3bd...`) between runs 6
and 7 on identical component versions and file counts - the tar embeds build
mtimes, which is why `P9_LOCK` pins the manifest's version fields, not its
digest; recorded here so nobody mistakes a rebuild for a payload change.

**Run #8 must come before any merge**: with the fold fixed, the same green
badge would now be produced only by a run whose release stage is genuinely
clean end to end (`P10_RELEASE_APK/UNSIGNED/RELEASE_AAB` all PASS *and counted*).

### Run #8 (job 35381311437, head `a8f8d33`, `18:59:11Z`): the first run where green MEANS green

The owner's trigger edit landed the recovery branch in `on.push.branches`; the
push itself started the pipeline - the run this phase has been trying to make
honest, now self-triggering. Verdicts, from `GATES_SUMMARY.txt` at the evidence
tip `3bf2721`:

* 86 `PASS` verdict lines, **zero** `FAIL`/`SKIP` lines; `phase6_ui_fails=0
  phase10_gate_fails=0 phase9_gate_fails=0`.
* The release stage's lines are now **visible and counted** (the fold fix):
  `P10_RELEASE_APK PASS`, `P10_RELEASE_APK_DETAIL PASS` (`payload=True
  manifest_asset=True`), `P10_UNSIGNED PASS ... scheme=none v1_files=0
  v2_v3_block=False`, `P10_RELEASE_AAB PASS` - the AAB containment fix holding
  on a real bundle (`PAYLOAD_ASSET name=runtime-payload.tar bytes=108615680`
  in the AAB too).
* Identity: `versionCode=8 versionName=1.18.23-phase10` on smoke AND release;
  `debuggable=False` (release) / `True` (smoke, by design); `P10_NO_DEBUG_ID
  PASS`; both ABIs on both artifacts.
* Staging chain again consistent: `PAYLOAD_SOURCE runtime-payload.tar.gz=19965703B`
  -> `STAGED_ASSETS n=2 ...` -> APK/AAB carry `runtime-payload.tar(108615680B)`.
  The payload sha (`023a2fd4...`) again differs from #6/#7 by the tar-mtime rule
  documented above; component versions and files=1062 are identical.
* All five P9 gates PASS for the second consecutive run (port-handover fix
  holds); the 14 Phase-6 UI gates PASS on BOTH the debug build and the
  release-shaped smoke build (28 lines, incl. L1/L2 live-model turns);
  `P10_STORE_ASSETS PASS` (icon + feature graphic + 3 screenshots, 0 rejected).
* `P10-STATIC PASS` covers INVARIANTS (0 findings) and the inspector self-test
  (27 checks) inside the CI static stage, as designed.

**What remains outside CI, unchanged by this:** the human signing step (§1.2/
§1.3) and the signed-build real-arm64 device pass (§2, R1-R9). CI has now
proven everything it can prove without the key.

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

1. ~~Install the workflow~~ **DONE** (web UI, §1.4) - and extended: `a8f8d33`
   added `arena/01a0b557-opencode-app` to `on.push.branches`, so every push to
   the recovery branch auto-runs the pipeline; the template copy is kept
   byte-identical to the installed one.
2. ~~Watch the run~~ **DONE for the CI-verdict purpose**: run #7 proved the
   artifact fixes, run #8 proved the counted verdicts (job 35381311437). The
   artifacts to sign are `opencode-android-unsigned-release` (unsigned APK+AAB
   + `release-sha256.txt`); the test-only pair is `opencode-android-smoke-test-only`
   - **never upload the smoke build** (docs/RELEASE.md §9).
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

---

# Appendix A — 2026-09-19: signed-build first-run diagnosis + workspace file visibility

Branch `arena/01a0b9d5-opencode-app` (base `0e6b222`, the merge of the Phase 10
session). Everything above is unchanged; this appendix is appended (the honesty
table in §0 is not rewritten, per the Phase 1 correction pattern).

Two problems were reported from manual testing of the signed build, and they are
different in kind:

* **A** — `90-real-device-signed.sh` (v1) reported `P10D_FIRST_RUN FAIL :: no working
  screen after 941s` on the signed release build, with 0 screenshots, 3
  packaging/runtime lines in logcat and no composer — while the owner then installed
  the same APK by hand and used it normally (real answers, a real tool call, the
  configured OpenRouter model selected). A gate that fails a build a human is using
  is worse than no gate: it teaches everyone to ignore red.
* **B** — a real product defect the human found while testing: the agent's files
  landed in `/data/data/<pkg>/files/workspaces/<project>`, i.e. app-private storage
  that no file manager, no MTP/USB browse and no non-root `adb shell` can read. The
  app was a sealed black box even though the agent really was writing files.

## A.0 Status at the end of this session

| Item | Status |
|---|---|
| A: v1 driver diagnosed, rewritten, and its helpers self-tested | **DONE** (§A.2, §A.3) |
| A: the rewritten driver run against the *signed* build on the owner's phone | **NOT TESTED** — needs the owner's phone + signed APK (§A.4); the script is ready and the exact command is in §A.4 |
| B: projects moved out of app-private storage into the app's own directory on shared storage | **IMPLEMENTED + TESTED** in CI on Android 14, from a non-root shell (§A.6) |
| B: in-app file browser (OpenCode's own file API) + SAF "Save a copy" / "Publish to a folder" | **IMPLEMENTED + TESTED** in CI (§A.6, gate `P6_U9`) |
| B: verified reachable, on a phone, by a file manager | **NOT TESTED, and partly impossible**: on Android 11+ no app may browse another app's `Android/data` (platform rule). The paths that *are* verified: `adb`/PC without root (CI, §A.6) and SAF publish into a folder the user picks. The owner's phone test must confirm both on real hardware (§A.4) |
| Phase 7 W1–W3 re-verified against the new root | **TESTED** in CI (§A.7) |
| Core Rule 5 report for the storage move | in §A.8 |

The CI evidence for this appendix is run **35449627090** (job `phase10-release`,
head `3777298`): **90 PASS verdict lines, 0 FAIL**, `phase6_ui_fails=0
phase10_gate_fails=0 phase9_gate_fails=0`, JVM tests 286/0/0/0. The run before it
(35448416605) is the one that caught the two gate-level bugs this appendix records
(§A.6 last paragraph).

## A.1 What this session changed, in one list

* **New**: `app/src/main/java/ai/opencode/android/ui/files/FilesScreen.kt` (the file
  browser), `phase10/scripts/p10d-ui.py` + `test-p10d-ui.py`,
  `phase10/scripts/p10d-png.py` + `test-p10d-png.py`,
  `phase10/scripts/92-workspace-visibility.sh`,
  `phase10/scripts/93-workspace-gates.sh`,
  `phase10/scripts/check-compose-icons.py`,
  `app/src/test/java/ai/opencode/android/projects/ProjectStoreMigrationTest.kt`
  (7 JVM tests), the `W4` gate in `WorkspaceIsolationGatesTest`, the `U9` gate in
  `ChatUiGatesTest`.
* **Rewritten**: `phase10/scripts/90-real-device-signed.sh` (v2 — same file name, so
  the owner's command does not change).
* **Changed**: `runtime/RuntimePaths.kt` (project root resolution + legacy root),
  `projects/ProjectStore.kt` (migration + new root), `projects/SafProjectTransfer.kt`
  (`publishTree`, `saveFileCopy`), `ui/AppRoot.kt` (files route, loaders, SAF
  launchers), `ui/chat/ChatScreen.kt` + `ui/projects/ProjectsScreen.kt` (entry
  points), `MainActivity.kt` (Compose test tags exposed as resource ids),
  `res/values/strings.xml`, `phase10/scripts/00-run-phase10.sh` (stage 5b + verdict
  fold), `phase10/scripts/30-static-checks.sh` (three new self-tests/checks),
  `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, `docs/CAPABILITY-MATRIX.md`,
  `docs/PRIVACY-POLICY.md`, `docs/STORE-LISTING.md`, `phase10/README.md`.
* **Deliberately untouched**: signing, the store listing package (except the one
  storage sentence that became false), branding, the emulator gate suite's
  semantics.

## A.2 Workstream A: why v1 failed a build that worked

### A.2.1 What v1 actually did

Re-reading v1 (`git show 0e6b222:phase10/scripts/90-real-device-signed.sh`) gives its
first-run verdict in eight lines:

```bash
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" ...
T0=$(date +%s)
HEALTH=0
for i in $(seq 1 60); do          # up to 5 minutes: first run extracts ~1 GB
  if uia_has "Ready" || uia_has "Continue" || uia_has "Start a conversation" || uia_has "Projects"; then HEALTH=1; break; fi
  sleep 5
done
T1=$(( $(date +%s) - T0 ))
... rd FIRST_RUN 1 "no working screen after ${T1}s (see p10d-ui.xml, run.log; ...)"
```

with `uia_dump()` as

```bash
adb shell uiautomator dump /sdcard/p10d-ui.xml >/dev/null 2>&1 || return 1
adb shell cat /sdcard/p10d-ui.xml 2>/dev/null > "$OUT/p10d-ui.xml"
```

That is the whole readiness test. Four defects follow directly from it, and only the
first is a matter of degree:

1. **`uiautomator dump` waits for the window to become idle, and this app's first-run
   screen never does.** The welcome screen shows an indeterminate
   `CircularProgressIndicator` while the payload is extracted and the server starts
   (`ui/welcome/WelcomeScreen.kt`, `StageIndicator`). Two facts combine:
   * v1 never disabled animations. CI's own driver has set
     `settings put global window_animation_scale 0` (and the transition/animator
     scales) since Phase 4 — `phase10/scripts/00-run-phase10.sh`, §4 "fresh emulator";
     v1 has no such line.
   * uiautomator's default `waitForIdleTimeout` is 10 s; a window that never goes idle
     makes the dump take that timeout and then fail with `ERROR: could not get idle
     state`, leaving `/sdcard/p10d-ui.xml` absent or stale.
   The arithmetic fits that reading exactly: 941 s over 60 iterations is **15.7 s per
   iteration**, versus the 5 s the loop intends (60 × 5 = 300 s). The extra ~10 s per
   iteration is the idle timeout. A dump that fails leaves nothing to grep, so all
   four needles miss, every iteration, and the loop can only ever print "no working
   screen".
2. **The loop threw away the one string that would have explained it.**
   `uia_dump` redirects uiautomator's stderr to `/dev/null`; the verdict therefore
   cannot distinguish "the app is broken" from "the dump never happened" from "the
   keyguard was in front".
3. **No preflight and no foreground check.** v1 never woke the device, never
   dismissed the keyguard, and never asked which window had focus. On a phone that
   had gone to sleep during the (long) install-and-launch step, every dump is a
   lock-screen dump with none of the four needles — indistinguishable from an app
   failure.
4. **No screenshots until stage R5.** The bundle's `screenshots/` directory is filled
   by `70-device-screenshots.sh`, which runs *after* the first-run verdict. So when
   R4 failed there were zero images of the failure — consistent with the reported
   "0 screenshots".

A fifth defect is separate, specific and certain (it can be read off the code, no
inference): **the live-turn gate could never have worked.** v1 typed the key into the
settings field but never filled the provider-id field:

```bash
if uia_tap "Settings" ...; then
    c=$(uia_center "Paste the key" ...); adb shell input text "$MODEL_KEY"; uia_tap "Save key"
```

while `SettingsScreen.kt` enables Save only when *both* are non-blank
(`enabled = providerId.isNotBlank() && apiKey.isNotBlank()`, `key_save`). Save stayed
disabled, no credential reached the server, and the live gate would have reported "no
model served the turn" — the reason the owner's manual test (key entered in the app)
succeeded where the script could not.

### A.2.2 The manual success is the corroboration

The owner's manual run of the same signed APK — open by hand, send messages, get real
answers, a real file-write tool card — is independent evidence that the artifact was
fine. Together with the mechanism above, the honest statement is:

* **Proven from code**: defects 2–5 (no dump diagnostics; no wake/unlock/foreground
  preflight; no screenshots before the verdict; the provider-id field never filled).
* **Leading cause for "no working screen after 941s", not yet proven line-by-line**:
  defect 1 (uiautomator never idle because animations were left on, with the ~15.7 s
  per-iteration timing as the supporting measurement). The decisive confirmation is
  the `run.log`/`p10d-ui.xml` from that v1 run. **The v1 bundle was requested and had
  not been supplied when this appendix was written** — if it arrives, §A.2.3 is where
  its lines go; if the dump turns out to have been *present* and simply lacked the
  needles, then the cause is defect 3 or 4 (a screen the driver did not recognise)
  and the v2 driver still covers it, because v2 does not depend on recognising a
  string: it reports what is on screen at every step.

### A.2.3 What the raw v1 bundle would settle (fill-in slot)

| Line to look for | What it proves |
|---|---|
| `ls -l p10d-ui.xml` → 0 bytes / missing | the dump never produced a tree → defect 1 (idle timeout) or a locked device |
| `p10d-ui.xml` present and containing a lock screen (`com.android.systemui`, "Swipe up") | defect 3: the phone was asleep/locked |
| `p10d-ui.xml` present with app text that v1 did not look for | defect 4: a real screen with the wrong needles |
| `screenshots.log`/`SUMMARY.txt` | confirms the "0 screenshots / composer never found" half |

## A.3 What the rewritten driver does differently

`phase10/scripts/90-real-device-signed.sh` v2 keeps the same interface (same flags,
same `p10d-out/` bundle, same `P10D_<GATE> PASS|FAIL|SKIP :: detail` verdict lines)
and replaces the readiness test with a state machine that can explain itself. Every
defect above maps to a change:

| v1 defect | v2 |
|---|---|
| animations left on → `uiautomator` never idle (A.2.1 #1) | **R0** switches the three animation scales off before anything else, with the reason written in the script; if the device still reports a locked screen after wake + `wm dismiss-keyguard`, the run says `P10D_DEVICE_AWAKE FAIL :: ... unlock the phone ... and re-run`, i.e. blames the right thing |
| dumps fail silently (#2) | `ui_dump` retries three times, keeps uiautomator's own message, and writes it to `DIAGNOSIS.txt`; `wait_for` on timeout prints the device screen state, the focused window, the node histogram and the visible text, and takes a screenshot named after the step (`screenshots/*-timeout-*.png`) |
| no preflight / no foreground check (#3) | R0 wakes, dismisses the keyguard, keeps the screen on; `foreground()` and `screen_state()` run on every wait tick; `handle_interruptions()` recognises the ANR dialog, the crash dialog and the notification-permission dialog and answers them like a user would (the notification dialog is the one a human dismisses without thinking — and the one that can cover the app on a fresh install) |
| no screenshots before the verdict (#4) | a screenshot is taken after **every** meaningful action (launch, welcome, each transition, prompt typed, answer, tool card, files screen), plus every 60 s during each wait; stage R10 requires ≥6 screenshots and zero of them blank |
| "the file exists" was the only screenshot check | `p10d-png.py` decodes each PNG and reports mean brightness, near-black fraction and content fraction; a uniformly-colored frame (black *or* white — an off screen, a locked screen, an unpainted surface) is rejected. Its self-test asserts exactly that: black and uniform-white frames rejected, light- and dark-theme screens with text accepted |
| `grep`-based taps matched disabled controls | `p10d-ui.py` refuses a non-clickable or disabled node and prints `FOUND_NOT_TAPPABLE enabled=false clickable=...`; matching is by Compose test tag exposed as an Android resource id (`welcome_continue`, `composer_send`, `files_publish`, …) with text/description as a fallback. Self-tested by `test-p10d-ui.py` (14 checks) |
| the provider-id field was never filled (#5) | R6 taps `key_provider`, types the provider (default `openrouter`, override with `P10D_PROVIDER`), then `key_value`, then `key_save`, and logs when Save never became tappable |
| the prompt could be mangled by the device shell | the prompt is typed with `%s` for spaces and contains **no** shell metacharacters (`>`, `&&`, `|`), because `adb shell input text` forwards the string through the device's shell |
| "no model served it" was indistinguishable from "it broke" | live-turn outcomes are split: PASS (answer + tool card), FAIL (answer but no card = the model echoed instead of running), SKIP with the app's own reason (`key was rejected` / `credit` / `unreachable` / `not running`) |
| the file-visibility question did not exist | **R7** runs `92-workspace-visibility.sh` against the project path the app itself displayed in **R5**, so the app's claim and an external observer's check are compared on the same string |

Why the tags exist at all: on a `debuggable=false` build there is no `run-as`, so the
only window onto the app from a desktop is uiautomator's view of the real window.
`MainActivity` now wraps the composition in
`Box(Modifier.semantics { testTagsAsResourceId = true })`, which exposes Compose test
tags as resource ids and changes nothing else — the U7 semantics audit still passes
(`interactive` counts unchanged for every screen, `unnamed=0`), because a resource id
is not read by TalkBack.

## A.4 Workstream A: running the rewritten driver (the owner's step)

```bash
# on your machine, phone plugged in, USB debugging on, phone UNLOCKED:
bash phase10/scripts/90-real-device-signed.sh --apk phase10/signing/<your-signed>.apk
```

The script auto-finds `phase10/signing/*.apk` when `--apk` is omitted. Useful extras:
`--cert-sha256 <fingerprint>` (proves it is your key), `--skip-live` (no model turn),
`--timeout-scale 2` (double every wait on a slow device).

The run prints the phone's screen state before it starts; if the keyguard cannot be
dismissed (a PIN lock), it says so explicitly instead of reporting an app failure.
Keep the whole `p10d-out/` directory — `SUMMARY.txt` is the verdict list,
`screenshots/` is the step-by-step image trail, `ui/` holds the accessibility dump of
every step, and `DIAGNOSIS.txt` is written whenever a step fails.

**Verification status of the rewritten driver itself** (Core Rule 4, honestly):

* its two helpers are tested in CI on every run (`test-p10d-png.py`: 5 checks;
  `test-p10d-ui.py`: 14 checks — both wired into `30-static-checks.sh`);
* the driver's *flow* is the same flow CI drives on the emulator every run (first run,
  project creation, composer, live turn, screenshots) — but that is a different script
  against a different build;
* the driver has **not** been run against the signed APK on a phone in this session
  (**NOT TESTED**). It cannot be: no device is attached to the authoring environment.
  Until it runs, the honest statement about the signed build stays what §2 of this
  report says — the owner has used it successfully by hand.

## A.5 Workstream B: the decision, and why

### A.5.1 What the platform actually allows (checked, not assumed)

| Location | Other apps | `adb` / PC, no root | POSIX runtime can `open()`/`write()` |
|---|---|---|---|
| `/data/data/<pkg>/files` (app-private; where projects *were*) | no | **no** (no `run-as` on a release build) | yes |
| `/storage/emulated/0/Android/data/<pkg>/files` (app-specific external; where projects *are* now) | **no on Android 11+** (platform blocks browsing any app's `Android/data`); on Android 10 an app with the legacy storage permission can | **yes** — verified in CI on Android 14: a non-root shell listed the directory and `cat`-ed a file the OpenCode server itself wrote | yes |
| A folder the user picks through SAF (`Documents/…`) | yes | yes | **no** — SAF gives URI access to the *framework*, not real paths to a bundled POSIX process. Writing there by path needs `MANAGE_EXTERNAL_STORAGE`, which Play restricts to a narrow set of app types (file managers, backup apps, …); this app does not qualify and shipping it would risk the submission |

Sources: the Android storage-behaviour changes for 10/11 (`Android/data` restrictions,
scoped storage) and Play's All-files-access policy. The claim used here is deliberately
the narrow one the CI evidence supports.

### A.5.2 The decision

**Both options were implemented, in the order that makes each one honest:**

1. **Move the live project root** to `<externalFilesDir>/workspaces/<project>`
   ("Option 2" in the task). This is the product fix: the agent's files are now in a
   real, `adb`-reachable location with no permission on any supported API level, and
   the app keeps working (falling back to the old internal root) on a device where
   external storage is not mounted.
2. **Add the in-app file browser + SAF publication** ("Option 1", plus one extra
   action). This is what makes the fix *actually* usable on a modern phone, because
   Android 11+ stops a file manager from opening `Android/data`. The browser reads
   through OpenCode's own `GET /file` / `GET /file/content` — the same layer the
   agent's tools use, so the user sees the agent's workspace and not a parallel
   copy — and "Publish to a folder" mirrors the project into a folder the user picks
   with the system picker, where **every** file manager can see it.

The rejected alternative is the third row of the table above: putting the live project
directly in a user-visible folder via SAF. It is the closest match to "feels like the
real terminal OpenCode", and it is not shippable in this architecture without
`MANAGE_EXTERNAL_STORAGE` (policy risk) or a full VFS layer inside the runtime (which
would be a re-engineering of OpenCode's file tools, i.e. a Core Rule 2/3 violation).

### A.5.3 What was implemented, precisely

* `RuntimePaths` now carries `filesDir`, the legacy internal root
  (`internalWorkspaces`) and the external root (`externalWorkspaces`), and resolves
  `workspaces` to the external one when it exists. `Context.getExternalFilesDir(null)`
  both creates the directory and returns null when the volume is not mounted — the
  availability check *is* the null check.
* `ProjectStore.get()` builds on that root and passes the legacy root as a migration
  source. `ProjectStore.ensureMigrated()` runs once per process start and:
  * does nothing when there is nothing to migrate (fresh install, no legacy root, or
    the legacy root *is* the root);
  * does nothing when the new root already holds a project — so a project the user
    deleted is never resurrected by a later start;
  * moves each project (rename first, copy+delete as the fallback, since the two
    locations are usually different filesystems), then removes the legacy directory
    only once it is empty;
  * leaves the originals in place if a move fails.
  7 JVM tests cover resolution, cross-filesystem moves, idempotence, the
  never-resurrect rule, the non-empty-target rule and preference history surviving the
  move (`ProjectStoreMigrationTest`, part of the 286 tests CI ran).
* `FilesScreen` (a pure function of the state it is handed, like every other screen —
  the Phase 6 purity check enforces it) shows: the exact on-device path, whether it is
  the external or the fallback location, the directory listing, an in-place file
  viewer with a size/truncation note, `Copy path`, `Save a copy` (SAF `CreateDocument`)
  and `Publish to a folder` (SAF `OpenDocumentTree`).
* `SafProjectTransfer.publishTree()` mirrors a project into the picked folder
  (overwrite-in-place, symlinks skipped, returns a file/byte count);
  `saveFileCopy()` writes one file. Published copies are exports, not syncs — a file
  deleted in the project is not deleted from an earlier publish, and the report says
  so rather than implying a two-way sync.
* Entry points: a files action in the chat top bar, and a `Files` item in each project
  row's menu (which selects that project first, so the browser cannot show one
  project's files under another project's name).

## A.6 Workstream B: verification (CI, Android 14, non-root)

Run **35449627090** (head `3777298`). The `P10_WS_*` names below are the harness's
fold of `docs/progress/phase10-evidence/workspace-gates.log` and `workspace-visibility.log`
(the two gate suites ran under their Phase 7 names `P7_W*` / `P10D_VISIBILITY_*`; the
fold renames them so they are Phase 10 verdicts). In that run the fold wrote them to
the wrong lines file (§A.6, last paragraph), so they are in those two logs and in the
job log but not in that run's `GATES_SUMMARY.txt`; the run after it fixed that. Either
way `phase10_gate_fails=0` in that run's summary already counted them:

```
P10_WS_W1_PROJECT_LIFECYCLE PASS   :: created/renamed/adopted/delete=true
P10_WS_W2_WORKSPACE_ISOLATION PASS :: listA=1 seesOwn=true seesOtherProject=false seesOutside=false
                                      readOwnA=true readOwnB=true escapeRefused=true
P10_WS_W3_MEMORY_INSPECTABLE_REMOVABLE PASS :: projectFile=<external root>/p7-memory-.../AGENTS.md
                                      globalFile=/data/user/0/<pkg>/files/xdg/config/opencode/AGENTS.md
P10_WS_W4_WORKSPACE_VISIBLE PASS   :: root=/storage/emulated/0/Android/data/io.github.mcyber12.opencode.debug/files/workspaces
                                      external=true underData=false legacyRoot=/data/user/0/<pkg>/files/workspaces
                                      serverWrote=true readBack=true marker=P10_VISIBLE_... shellStatus=200 dirOnDisk=true size=26
P10_WS_VISIBILITY_LOCATION PASS    :: the app-specific external root exists and holds 2 project(s) (Android 14)
P10_WS_VISIBILITY_SHELL_LIST PASS  :: a non-root shell lists the project directory (.../workspaces/w4-storage-...): 4 lines
P10_WS_VISIBILITY_SHELL_READ PASS  :: read p10-visible.txt from outside the app: 'P10_VISIBLE_...'
P10_WS_VISIBILITY_OLD_ROOT_EMPTY PASS :: the old app-private root is not readable from a shell at all:
                                      ls: /data/data/<pkg>/files/workspaces: Permission denied
P10_WS_VISIBILITY_SHELL_BASELINE PASS :: an unprivileged shell cannot read /data/data/<pkg> at all, so the PASSs above are real
P6_U9 PASS  :: listed=true pathShown=true locationCopy=true openedDir=true upShown=true upWorks=true
               openedFile=true viewer=true body=true saveCopy=true/true closed=true publish=true/true
               copyPath=true empty=true
P10_SMOKE_UI_U9 PASS :: (the same gate against the release-shaped build)
P10-UNIT PASS :: JVM tests=286 failures=0 errors=0 skipped=0
phase6_ui_fails=0 phase10_gate_fails=0 phase9_gate_fails=0
```

Notes on what these actually prove, and what they do not:

* `SHELL_LIST`/`SHELL_READ` are executed by the **host** against the path the app
  printed; the shell user reaches the directory through the `ext_data_rw` group on the
  app's own external directory (`ls -la` output is in
  `docs/progress/phase10-evidence/workspace-visibility.log`). This is the same vantage
  point `adb pull` and a desktop file browser use. The `SHELL_BASELINE` check exists so
  a PASS cannot be an artifact of a device where *everything* is readable: the same
  shell is refused `/data/data/<pkg>`.
* `W4` is the app's own claim (root resolved outside `/data/data`, and a shell command
  run through OpenCode's `/session/:id/shell` wrote and read back a marker file);
  `V*` is the external observer. Neither is sufficient alone — that is why both exist.
* The emulator is **Android 14**, the same major version as the owner's phone, but an
  emulator is still an emulator: the phone run is the remaining evidence (§A.4).
* The two gate failures this stage caught in its first CI run, both now fixed and
  pinned by a test: (1) the folded verdict lines were written to `p10-lines.txt`, which
  the *smoke* stage truncates a stage later, so real PASSes never reached
  `GATES_SUMMARY.txt` — they now go to their own `p10-workspace-lines.txt`; (2) the U9
  gate compared the screen against a fixture path with a different applicationId than
  the surface under test was rendering — the screen was right, the assertion was wrong.

## A.7 Phase 7's W1–W3 against the new storage location (the requested confirmation)

**They still pass, unmodified, on the new root.** The W2 mechanism is unchanged and
was re-checked rather than assumed: the server still scopes `/file` by the instance
directory (`?directory=`) and still refuses an escaping read through upstream's own
`FSUtil.contains` guard (`escapeRefused=true`, upstream's generic error body in the
detail line). W3 shows the project `AGENTS.md` now living at
`/storage/emulated/0/Android/data/<pkg>/files/workspaces/<project>/AGENTS.md` while the
*global* `AGENTS.md` stays in app-private XDG config — the same split as before, with
only the project half moved. W1 (create → rename → adopt → delete) passes on the new
filesystem, including the rename path that now crosses a filesystem boundary on some
devices (the JVM migration test covers the copy+delete fallback).

Isolation is therefore a property of the *resolved project root*, not of the old
location: nothing in the isolation logic keyed off `filesDir`, and the W4 gate adds the
missing assertion that the root is deliberately outside the sandbox.

## A.8 Core Rule 5 report (architecture-change rule) for the storage move

1. **Which requirement changes**: not a core requirement — the core requirement
   (real OpenCode running locally in one APK, no Termux/PC/server) is untouched. What
   changes is a *storage-location* decision that Phase 7 had recorded as "app-private
   is the point" (`docs/SECURITY.md` before this session). The product requirement it
   conflicted with is the one the owner hit: the user must be able to see and take
   their files.
2. **Why**: `/data/data` is unreadable from every vantage point a non-root user has
   (no file manager, no MTP, no non-root `adb`), so the agent's output was invisible
   outside the app.
3. **Evidence**: the CI verdict lines in §A.6 (`SHELL_LIST`, `SHELL_READ`) plus the
   baseline that the same shell cannot read `/data/data/<pkg>`; the app-side W4 gate;
   and the owner's original observation from manual testing (this appendix's premise).
4. **Alternatives investigated**: (a) in-app browser only — rejected as insufficient
   alone, because it leaves the files unreachable from outside the app *and* from a
   file manager; kept as a complement; (b) SAF-chosen project folder as the live root —
   rejected, `MANAGE_EXTERNAL_STORAGE`/VFS implications (§A.5.1/.2); (c) do nothing and
   document the limitation — rejected by the owner's own product judgement.
5. **What is lost**: the guarantee that project files are unreadable to *any* other
   process on the device. On Android 11+ they remain unreadable by other apps; on
   Android 10 an app holding the legacy storage permission can read them, and a
   connected PC with debugging enabled can read them. That trade is stated in
   `docs/PRIVACY-POLICY.md` (with the exact path, the Android-10 caveat, and the
   "uninstalling deletes your projects — export first" warning) and in
   `docs/SECURITY.md`. Runtime internals, credentials, XDG state and logs stay in
   app-private storage.

## A.9 Honesty table for this appendix

| Claim | Label | Evidence |
|---|---|---|
| v1's readiness loop could not distinguish "no dump" from "no app", never woke or unlocked the device, took no screenshot before the verdict, and could never fill the provider-id field | **PROVEN FROM CODE** | §A.2.1; the v1 script at `0e6b222` and `SettingsScreen.kt`'s `key_save` enablement rule |
| the leading cause of "no working screen after 941s" is uiautomator's idle-wait against a never-idle welcome screen (animations left on) | **INFERRED, strongly supported** — timing arithmetic (941 s / 60 ≈ 15.7 s vs 5 s intended), the spinner on the first-run screen, and CI disabling animations where v1 did not | §A.2.1; needs the raw v1 bundle to close (§A.2.3) |
| the rewritten driver's helpers work as specified (black/blank screens rejected, disabled controls refused, ANR dialogs detected) | **TESTED** (CI + local) | `test-p10d-png.py` 5 checks, `test-p10d-ui.py` 14 checks, wired into `30-static-checks.sh` on every run |
| the rewritten driver runs end-to-end against the signed build on a phone | **NOT TESTED** | needs the owner's phone (§A.4) |
| projects now live under the app-specific external directory and are migrated once from the old root | **IMPLEMENTED + TESTED** (CI Android 14; JVM) | `P10_WS_W4_WORKSPACE_VISIBLE`, `ProjectStoreMigrationTest` (7 tests) |
| a non-root shell can list and read what the OpenCode server wrote there, while `/data/data/<pkg>` stays unreadable | **TESTED** (CI Android 14) | `P10_WS_VISIBILITY_SHELL_LIST/SHELL_READ/OLD_ROOT_EMPTY/SHELL_BASELINE` |
| the in-app file browser lists, opens, offers Save-a-copy/Publish/Copy-path and has an accessible name for every control | **TESTED** (CI, debug + release-shaped builds) | `P6_U9 PASS`, `P10_SMOKE_UI_U9 PASS`, `P6_U7` (`files=6`, `unnamed=0`) |
| a *file manager* on a modern phone can browse the project directory | **NOT POSSIBLE on Android 11+ by platform rule**; the verified substitutes are `adb`/PC (CI) and SAF publish | §A.5.1; `files_location_external` string states this in the app itself |
| Phase 7 W1–W3 still hold on the new root | **TESTED** (CI) | §A.7 |
| the device-side visibility check works against the signed build | **NOT TESTED** | runs as R7 of the rewritten driver (§A.4) |

## A.10 Reproduce the CI layer in seconds

```bash
bash phase10/scripts/30-static-checks.sh          # rc=0; now also runs the two helpers' self-tests
python3 phase10/scripts/test-p10d-ui.py           # 14 checks
python3 phase10/scripts/test-p10d-png.py          # 5 checks
python3 phase10/scripts/check-compose-icons.py    # 0 not-in-core (advisory)
python3 phase6/scripts/30-static-checks.sh        # UI purity/a11y/strings/lists, 0 findings
```

Full pipeline: `bash phase10/scripts/00-run-phase10.sh` (CI: static → unit → payload →
emulator → debug UI gates → **new: workspace location + visibility** → smoke gates →
screenshots → release APK/AAB inspection → Phase 9 gates → `GATES_SUMMARY.txt`).

On the owner's machine, the two new scripts are useful on their own — the second one is
the fastest way to answer "can anything else read my files?":

```bash
bash phase10/scripts/92-workspace-visibility.sh --pkg io.github.mcyber12.opencode
```

## A.11 Owner action list (replaces §8 items 5–6; the rest still stand)

1. **Re-run the device script** on the signed build (the command in §A.4). Expect the
   first run to take a few minutes of extraction; the script will say what it is
   waiting for the whole time, and will leave a screenshot of every step.
2. Send back `p10d-out/` — `SUMMARY.txt`, `screenshots/`, `ui/`, `DIAGNOSIS.txt` and
   `visibility.log`. That is what turns §A.4 and §A.9's last rows from NOT TESTED into
   TESTED (or into a diagnosis that can be trusted).
3. If §A.2.3's bundle from the v1 run still exists, send it too (it closes the last
   inferred line in this appendix).
4. **Re-sign CI's newer artifacts before installing**: the fix in §A.5 lives in the
   app code, so the APK from before this appendix does not contain it. Order:
   CI uploads `opencode-android-unsigned-release` → `sign-release-local.sh` →
   `90-real-device-signed.sh`.
5. Then the store screenshots (from a phone, with the file browser in the set) and the
   Play Console submission per §8 steps 6–8.

## A.12 Follow-up: the two red runs, and what they turned out to be

Runs #12 and #13 (`35450781510` on `414a275`, `35450712913` on `346695c`) both came back
red. They were not two independent findings, and neither was an app defect.

**What CI does not give you when two runs overlap.** Run #13 ran 23 minutes and failed,
and left no usable evidence: its `docs/progress/phase10-evidence` commit lost a race with
run #12's (both rewrite the same generated files on the same branch, the rebase
conflicted, the push was rejected and the script treated that as non-fatal), and its
uploaded artifact and job log could not be downloaded from this sandbox either
(`gh run view --log`, `gh api .../actions/runs/:id/logs` and `gh run download` all ended in
`EOF` after repeated attempts with 60-second backoff). Its code state differs from run
#12's only by documentation commits, and run #12's evidence names exactly one failing
gate, so the honest statement is: **run #13 is attributed, not directly evidenced.**

**Run #12's failure was the gate, not the app.** `P6_L1` / `P10_SMOKE_UI_L1` failed with
the server holding the reply (`reply='Blue.'`, 5 chars) while the conversation surface had
no message rows on it. The mechanism is in the gate's own poll loop: it asks the server
every third iteration, so it sees the reply within a few seconds of the server having it,
then asserted on the screen at that same instant — before the SSE frame arrived and the
transcript repainted. `newScreenLines=2` is the socket indicator and the round dot, i.e.
chrome. L2 in the same run, 30 seconds later and waiting for `tool_card_*` to appear
before asserting anything, saw the full transcript (screenshot in
`docs/progress/phase10-evidence/phase6/screenshots/32-live-tool-card-expanded.png`).

**The worse find was the evidence, not the verdict.** `30-live-chat-reply.png` from the
failing run is an empty conversation — and it is byte-identical, 22406 bytes,
sha256 `4bb5dc9eac27c702…`, to the file a *passing* run produced at the June base commit.
The gate's own screenshots were never the screen the gate was describing:
`captureToImage()` reads the last *presented* frame, so a capture taken as a
recomposition lands can be the frame from before the content. A verdict can be argued
with; a picture is what a reader trusts, so this had to be fixed first.

**The screenshot fix needed a second pass, and run #14 is why.** With L1 waiting for
the screen, run #14 (`35455909445`) passed every gate (103 PASS, 0 FAIL) and reported
the catch-up values it should: `messageRows=4 emptyConversation=false`. But its
`30-live-chat-reply.png` was *still* byte-identical to the June base file
(`sha256 4bb5dc9eac27c702…`, ink 0.023) — the Compose capture for this screen is not
just stale by a frame, it is deterministic and carries no conversation at all. So the
threshold that decides "this capture is too empty to be evidence" was calibrated against
the run's own files (empty conversation 0.023-0.034; every screen with content
0.109-0.60) and now sits at 0.06, with a real device screenshot as the fallback.

**What changed** (commits `phase6 gates: L1 waits for the screen instead of the server…`
and `phase6 gates: calibrate the ink floor…`):

* L1 now waits, with a named expectation and a 120-second budget, for the prompt text and
  a word from the reply to be **on screen**, then re-reads the screen and only then
  judges. Its verdict line carries `messageRows=`, `emptyConversation=` and
  `screenCatchUpMs=`, so a screen that never catches up is reported as exactly that.
* Every screenshot now reports `bytes=` **and** `ink=` (the share of non-background
  pixels) and the path it was taken by: a Compose capture, a retake after a settle, or a
  real device screenshot through `UiAutomation` when the Compose frame stays blank. The
  ink measurement is wrapped so that a failure to measure can only cost the number,
  never the picture.
* The harness's evidence push retries and resolves a rebase conflict in favour of the
  copy already on the branch, instead of dropping the run's evidence on the floor.
* `phase10/workflow/phase10-release.yml` (the template) carries a one-run-per-branch
  `concurrency:` group. **It is not installed**: the automation's token cannot write
  `.github/workflows/**`, so until the owner copies the template over the installed file
  (the file header documents that step), overlapping runs are still possible and the
  retry above is what keeps their evidence.

**Verdict.** Run #14 (`35455909445` on `3a2621d`) is green on the fix for the failure
itself: 103 verdict lines, 0 FAIL, `phase6_ui_fails=0 phase10_gate_fails=0
phase9_gate_fails=0`, JVM tests 286/0/0/0, and L1 on both the debug and the
release-shaped smoke build passed with the screen (not the server) deciding:

```
P6_L1 PASS :: promptSent=true userPromptShown=true serverReplyChars=5 replyShownInUi=true(needle='Blue')
              newScreenLines=12 messageRows=4 emptyConversation=false screenCatchUpMs=120000
              busyIndicator=true streamingDots=true errorBanner=false asksAnswered=0
              composerUsableAgain=true screenshot[bytes=22406 ink=0.023 via=compose] reply='Blue.'
P6_L2 PASS :: tool=bash status=completed partId=prt_0ba9bcec4001R97YeSrlZOgVyO cardShown=true
              collapsedBeforeTap=true headline=true expandedByTap=true outputRendered=true
              markerInServerOutput=true markerOnScreen=true marker=P6LIVE164107
              screenshots[bytes=71917 ink=0.076 via=compose | bytes=92858 ink=0.235 via=compose]
```

The `ink=0.023 via=compose` on the L1 shot is the finding above: the verdict is sound,
the picture was not.

### A.12.1 Run #15 — green, and one honest correction about CI's pictures

Run #15 (`35457139209` on `e4f77db`) is green: **103 verdict lines, 0 FAIL**,
`phase6_ui_fails=0 phase10_gate_fails=0 phase9_gate_fails=0`, and L1 now decides on the
screen:

```
P6_L1 PASS :: ... newScreenLines=9 messageRows=4 emptyConversation=false
              screenCatchUpMs=120000 ... screenshot[bytes=62924 ink=0.103 via=device] reply='Blue.'
P6_L2 PASS :: ... screenshots[bytes=73112 ink=0.090 via=compose | bytes=84872 ink=0.235 via=compose]
```

But `via=device` on that L1 line was **my mistake, and it is now reverted**: with the ink
floor at 0.06 the step replaced the Compose capture with a device photograph, and that
photograph is the **launcher with the keyboard open**, not the app. Writing a picture of
another screen over app-shaped evidence is worse than an empty-but-flagged one, so
`shot()` no longer substitutes:

* the main file always stays the Compose capture (app-shaped, deterministic);
* if its ink is below the floor it is retaken after a settle, and if it is *still* below
  the floor the device frame is written beside it (`<name>-device.png`) and the verdict
  line says so explicitly: `via=compose lowInk=true deviceShot=… deviceBytes=… deviceInk=…`;
* the ink floor (0.06, calibrated in §A.12) therefore only ever *labels* a picture.

Two facts worth keeping, because they bound what CI evidence can claim here:

1. The renderer's capture path for this one step is unreliable **in this emulator** and
   was so before this session: the June base commit's `30-live-chat-reply.png` is the
   same 22406 bytes as run #12's and run #14's, i.e. a PASSING run shipped the same
   empty picture. It is not an app defect — the same run's L2 captures show the
   conversation correctly, and the app's own semantics tree reports the rows.
2. Therefore the trustworthy screenshots of the app come from the **device script on a
   real phone** (§A.4), not from CI's per-step pictures. CI's verdicts (the gate lines)
   are the evidence; CI's PNGs for this step are indicative only, and the line now says
   which kind a reader is looking at.

### A.12.2 Run #16 — the gate found a real app bug, and it is now fixed

Run #16 (`35460196586` on `6481ab4`) came back red on the same gate, but with a
completely different and much more useful signature — the same one in both the debug and
the release-shaped smoke build:

```
P6_L1 FAIL :: ... userPromptShown=false replyShownInUi=false newScreenLines=2
              messageRows=0 emptyConversation=true screenCatchUpMs=120000
              busyIndicator=true streamingDots=false errorBanner=false composerUsableAgain=true
              screenshot[bytes=22406 ink=0.023 via=compose lowInk=true
                         deviceShot=30-live-chat-reply-device.png deviceBytes=63289 deviceInk=0.103]
              reply='Blue.'
```

The screen was waited on for the full 120 seconds and the conversation **never** got a
single row, while the server held the reply. That is not the gate being early any more
(the fix in §A.12 covers that case, and it is why this run's failure is legible) — it is
the app: `L2`, which ran seconds later with a 420-second budget, saw the whole transcript.

Root cause, from the repository's own code: the transcript is built from live event
frames, and messages were re-read from the server **only when the selected session
changed** (`refresh()` → `if (keep.isNotEmpty() && keep != sel)`). A frame emitted before
this client subscribed is lost with no repair path, and both gates drive the UI fast
enough to make that likely (project created, prompt sent, all within a second or two of
the app starting). Symptom the user would see: type a message right after opening the
app and the conversation can stay blank even though the agent is answering.

Fix (`OpenCodeRepository`):

* `refreshMessages(status, force)`: re-read the selected session's messages from the
  server, single-flight, silent on failure (the stream stays the primary path).
* A turn **ending** (`session.idle`) is a repair point with `force = true` — the server
  holds the final state regardless of which frames this client was subscribed for. One
  request per turn, bounded by construction.
* A `session.status` frame arriving for a session whose transcript has **no** messages
  is the "frames were missed" signal → one repair fetch.
* After a prompt is accepted, the transcript is seeded from the server instead of waiting
  for a frame that may never arrive.

This is a real, user-visible robustness fix, found by the improved gate and verified by
CI (run #17). It is the second thing this session fixed that the old driver could not
have seen: the old L1 asserted on the screen the instant the *server* answered, so a
missing transcript was indistinguishable from a slow one.
