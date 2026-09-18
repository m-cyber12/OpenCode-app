# Release & signing

This is the whole path from the CI-built artifacts to a Play submission, and the
rules that keep the signing key out of every automated system. It is written for
the **human owner** of the app: signing is an offline, human-controlled step, and
nothing in this repository, in CI, or in any chat can do it for you.

Contents: [what CI produces](#1-what-ci-produces) - [generate the keystore](#2-generate-the-upload-keystore-once) -
[where it lives](#3-where-the-keystore-lives) - [sign the Phase 10 artifacts](#4-sign-the-artifacts-ci-already-built) -
[verify a signature](#5-verify-what-you-are-about-to-upload) - [Play App Signing](#6-play-app-signing-first-upload) -
[releases after the first](#7-every-release-after-the-first) - [what CI may never do](#8-what-ci-may-never-do) -
[the debug-key smoke build](#9-the-debug-key-smoke-build-never-upload-it).

---

## 1. What CI produces

`phase10/workflow/phase10-release.yml` (installed by hand under
`.github/workflows/`, see the workflow header) builds and publishes:

| Artifact | What it is | Signed? |
|---|---|---|
| `app-release-unsigned.apk` | the release APK, exactly the bytes Phase 10 gated | **no** |
| `app-release.aab` | the App Bundle for Play | **no** |
| `opencode-runtime-engine.tar.gz` + `runtime-manifest.json` | the pinned embedded runtime (Bun/git/ripgrep + OpenCode bundle) with sha256s | n/a |
| `phase10-evidence` | gate verdicts, APK manifest/signature report, screenshots | n/a |
| `TEST-ONLY-debugkey-*.apk` | the release-shaped **smoke** build (section 9) | **debug key, never upload** |

CI cannot sign the real artifacts: no keystore, no password, no Play credentials
exist in the workflow, by design and enforced by
`phase10/scripts/check-release-invariants.py` (it fails if a workflow mentions a
signing secret).

## 2. Generate the upload keystore (once)

On a machine you control, with a JDK installed (`keytool` ships with it):

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

* `-validity 10000` (about 27 years): Play requires the key to outlive the app's
  update history; a key that expires cannot sign updates.
* Use a **strong, unique** store password and keep it in a password manager.
* PKCS12 (what `keytool` writes by default since Java 9) uses **one** password for
  store and key. That is what the Gradle block assumes when `keyPassword` is
  absent - it falls back to the store password rather than guessing.
* Do not use the Android debug keystore (`~/.android/debug.keystore`) for a
  published app: it is public, its password is `android`, and every other
  developer has a key with the same identity.

Record, outside the repository, where this file is and what the passwords are. If
you lose the keystore after publishing with Play App Signing, you can still
recover by resetting the upload key with Google; if you have *not* enabled App
Signing, losing it means you cannot update the app under the same listing.

## 3. Where the keystore lives

**Never commit it, and never put it in the repository.** The `.gitignore` covers `keystore.properties`,
`*.jks`, `*.keystore`, `*.p12`, `*.pepk`, and the Phase 10 checks fail if any of
them is tracked.

`app/build.gradle.kts` resolves the signing configuration from outside the tree,
first hit wins:

1. `~/.config/opencode-app/keystore.properties` (recommended: outside the repo entirely)
2. `<repo>/keystore.properties` (gitignored, for convenience on a build machine)
3. environment variables - `RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (the Phase 9 names
   `P9_KEYSTORE_FILE`/`P9_KEYSTORE_PASSWORD`/`P9_KEY_ALIAS`/`P9_KEY_PASSWORD`
   are still honoured, so an existing setup keeps working)

`keystore.properties` looks like this - and note that this file is the *only*
place a password is written down:

```
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Behaviour that matters:

* **No keystore configured -> the build is UNSIGNED**, and says so in the log
  (`RELEASE SIGNING: not configured -> release artifacts are UNSIGNED`). That is
  the CI case, and it is the only way an unsigned artifact can happen.
* **Half-configured -> the build FAILS.** A missing `storeFile`, a `storeFile`
  that does not exist, or a missing store password raises a Gradle error instead
  of quietly producing something unsigned. Shipping an unsigned APK because a
  password was missing is the failure this project refuses to have.
* Every build prints one identity line, e.g.
  `RELEASE SIGNING: enabled store=upload-keystore.jks alias=upload storeSha256=...`.
  The keystore **file name** and the SHA-256 of the keystore file are not secrets;
  they are what tells you which key signed an artifact.

## 4. Sign the artifacts CI already built

Phase 10's rule: sign the bytes Phase 10 gated. Do not rebuild for signing unless
you have to (and if you do, re-run the gates - see section 7).

```bash
# 0) get the artifacts from the Phase 10 run (Actions -> phase10-release -> Artifacts)
#    and verify they are the ones the run hashed:
sha256sum -c release-sha256.txt          # from the phase10-evidence artifact

# 1) APK: zipalign FIRST, then sign (signing after alignment keeps it aligned;
#    signing before alignment would invalidate the signature)
zipalign -p -f 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign \
  --ks ~/keys/upload-keystore.jks --ks-key-alias upload \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out app-release-signed.apk app-release-aligned.apk

# 2) AAB: bundles are JAR-signed; no zipalign step
jarsigner -keystore ~/keys/upload-keystore.jks -sigalg SHA256withRSA \
  -digestalg SHA-256 app-release.aab upload

# 3) sanity: what did we just produce?
apksigner verify --print-certs --verbose app-release-signed.apk
sha256sum app-release-signed.apk app-release.aab
```

`apksigner` and `zipalign` come from Android SDK build-tools
(`$ANDROID_HOME/build-tools/<version>/`). Android Studio users can do the same
thing with **Build -> Generate Signed Bundle / APK**, which runs exactly these
tools; choose "APK" for a sideloadable file and "Android App Bundle" for Play.

Phase 10 also ships a helper that runs the same steps and refuses to continue if
a fingerprint does not match what you expect:

```bash
bash phase10/scripts/sign-release-local.sh \
  --apk  path/to/app-release-unsigned.apk \
  --aab  path/to/app-release.aab \
  --keystore ~/keys/upload-keystore.jks \
  --alias upload \
  --out  phase10/signing
```

It prompts for passwords (never on the command line, never in a log), verifies
the result, prints the certificate SHA-256, and writes both signed artifacts plus
a `signing-report.txt` into `--out`. It never copies the keystore anywhere.

## 5. Verify what you are about to upload

`apksigner verify --print-certs` is the authoritative check. Two things to look
for:

* `Signer #1 certificate SHA-256 digest:` - must be the **upload key**, and the
  same value across releases;
* `Verified using v2 scheme (APK Signature Scheme v2): true` - Android 11+
  requires v2 or newer for apps with `targetSdkVersion` 30+; a v1-only signature
  installs nowhere modern.

Then confirm the artifact's identity and contents:

```bash
python3 phase10/scripts/check-apk.py app-release-signed.apk \
  --expect-package io.github.mcyber12.opencode \
  --expect-version-name 1.18.23-phase10 \
  --expect-version-code 8 \
  --expect-not-debuggable --expect-signed --expect-icon --expect-payload \
  --expect-min-sdk 29 --expect-target-sdk 34 \
  --expect-native-abi arm64-v8a

python3 phase10/scripts/check-apk.py app-release.aab \
  --expect-package io.github.mcyber12.opencode \
  --expect-version-name 1.18.23-phase10 \
  --expect-native-abi arm64-v8a --expect-payload
```

Both must print `VERDICT PASS`. On the phone, the same APK can be installed
directly (`adb install app-release-signed.apk`) - which is what
`phase10/scripts/90-real-device-signed.sh` walks through.

That device script checks the signature **against your key**, not merely that a
signature exists: `apksigner verify --print-certs` prints the signer's SHA-256
fingerprint, and the run fails if it is not the one you expect. Write yours down
once (Play Console shows it later as the "upload certificate"):

```bash
bash phase10/scripts/90-real-device-signed.sh --apk phase10/signing/app-release-signed.apk \
     --cert-sha256 <the digest apksigner printed>      # or: P10D_CERT_SHA256=<digest> ...
```

A fingerprint is public data - unlike the keystore, it is safe to write down, and
putting it in `phase10/signing/expected-cert-sha256.txt` (gitignored) makes every
later device run answer "was this really signed with my key?" by itself.

## 6. Play App Signing (first upload)

* Upload the **signed AAB**. Play re-signs the delivered APKs with the app
  signing key it manages, and keeps the upload key only as proof that updates
  come from you.
* Keep "Play App Signing" ON (it is the default for new apps). It is what makes
  a lost upload key recoverable.
* The package name `io.github.mcyber12.opencode` is **permanent** once published:
  Play will never accept a change of application ID for an existing listing, and
  a new ID starts from zero installs. That is why the ID is deliberate and
  independent of the display name (see `docs/BRANDING.md`).

## 7. Every release after the first

1. Bump `versionCode` (must strictly increase; `versionName` is cosmetic) in
   `app/build.gradle.kts` **and** in the `app:` block of `versions.lock` -
   `check-release-invariants.py` fails if they disagree.
2. Push to the working branch; Phase 10 CI builds unsigned artifacts and runs
   every gate, including the release-shaped smoke build.
3. Sign the artifacts from that run (section 4), verify (section 5).
4. Upload the signed AAB, fill in the release notes, roll out.

If you ever build the release variant locally instead of signing CI's output,
re-run the device gates for that exact build
(`phase10/scripts/40-release-verify.sh` plus `50-smoke-gates.sh`) before
uploading: a locally rebuilt artifact has not been through Phase 10's gates.

## 8. What CI may never do

* Hold, receive, decode or print the release keystore or its passwords.
* Sign a release artifact with the real upload key.
* Print anything that could reconstruct a secret (the Gradle block logs only the
  keystore file name and its SHA-256).

`phase10/scripts/check-release-invariants.py` enforces the first rule mechanically:
it fails the build if `phase10/workflow/phase10-release.yml` (or the installed
copy) mentions `P9_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEYSTORE_FILE` or any `P10_KEYSTORE*` name. The Phase 9 workflow's
optional signing secrets are retired for Phase 10 and must not be restored.

## 9. The debug-key smoke build (never upload it)

To answer "does the *release-shaped* app behave differently from the debug build
every earlier phase tested?" without putting a release key in CI, Phase 10 adds a
`smoke` build type:

* same `applicationId` as release (`io.github.mcyber12.opencode`),
* same code shape - `isMinifyEnabled = false` for release, so no R8 stripping,
* `debuggable = true` and signed with the **public Android debug key**, which is
  what lets `am instrument` and `run-as` see into it,
* its APK name starts with `TEST-ONLY-debugkey-`, its evidence is labelled
  `SMOKE`, and it is uploaded as an explicitly test-only artifact.

It is the closest thing to "the release build, on a device" that a CI system
without the signing key can run. It is **not** a release artifact: Play would
reject it (wrong signing identity, debuggable) and installing it on a phone that
has the real app is a downgrade. Delete it when you are done with it.
