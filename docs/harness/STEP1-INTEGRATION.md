# Step 1 — bringing the coding harness in, additively

Decision (owner, 2026-10-24): **integrate the terminal/harness and remove Bun**,
driven by **capabilities and the API 36 requirement**, distributed through
**Play**. Bugs first if they touch the runtime; they don't, so step 1 starts
now.

This document is the plan for step 1 only. Step 1 is deliberately additive: the
OpenCode server keeps running on Bun, no user-facing behaviour changes, and the
board stays green while the foundation is measured.

---

## 0. What step 1 is, and what it is not

**Is:** prove, in *this* app's tree, that a real userland can be executed on a
store-legal target, then bring the harness modules in behind that proof.

**Is not:** removing Bun. That is step 3+, and it is gated on a measurement
(the Node port of the server) that has not been made yet.

The reason for the order is attribution, not caution for its own sake: raising
`targetSdk` and swapping the agent's runtime in the same change means a failure
has two possible causes and the Play deadline (API 36; 31 Aug 2026, extension
to 1 Nov 2026) is the closer of the two clocks.

## 1. The precondition: the exec model, measured here

Android 10 (API 29) and later refuse to execute a file an app wrote into its own
data directory (W^X). The one place an app may still execute is the APK's
native library directory. A coding harness is a userland, so the whole idea
stands or falls on that line.

This app already lives on the legal side of it — `RuntimePaths` executes
`libbun.so`, `libgit.so` and `librg.so` out of `nativeLibraryDir` through
`libexecshim.so`, with `bin/` symlinks and a `libseccompshim.so` that turns
Android's SIGSYS answers into ENOSYS so Bun's fallbacks engage. But "our
runtime works" is not the same claim as "any executable we add will work", so
the rule is now measured rather than inherited:

**Slice A (done).** `phase4/payload/native/exec-probe.c` — a PIE
executable, packaged as `libocexecprobe.so` purely so the platform extracts it
— plus `HarnessExecModelGateTest` (gates X1-X3, stage D of the Phase 6
runner):

| Gate | Claim |
|---|---|
| X1 | `nativeLibraryDir` exec runs and prints its marker, at this app's targetSdk |
| X2 | the same bytes copied into app data and chmod +x are **refused** |
| X3 | a stable prefix path (`files/bin/probe`) symlinked into the APK's copy still runs |

X3 is the shape the harness actually uses: stable prefix paths for scripts,
`PATH` and shebangs, with the executable inode always coming from the APK.

**Result (2026-10-24, CI run 40102807738, targetSdk 34, API 34, abi x86_64) —
X1, X2 and X3 all pass, on the debug build and again on the release-shaped
smoke build:**

```
P6_X1 PASS :: nativeLibraryDir exec ran=true exit=0 marker=true targetSdk=34 api=34 abi=x86_64 :: OCEXECPROBE ok
P6_X2 PASS :: appData exec refused=true ran=false exit=-1 targetSdk=34 :: IOException: Cannot run program ".../files/probe-exec"
P6_X3 PASS :: prefix symlink exec linked=true ran=true exit=0 :: OCEXECPROBE ok
```

So, on this tree: the APK's native library directory executes, the same bytes
copied into app data and chmod +x are refused outright (the refusal is an
`IOException` from `execve`, not a permission error from the file), and a stable
prefix path symlinked into the APK runs. That is the predicate, measured.

An operational note that cost two boards of evidence: a phase-10 run commits
its evidence against the commit it checked out, so **pushing while a run is in
flight rejects that push and the run still reports success**. Both of the runs
that first carried X1-X3 (40095093459, 40095163720) lost their evidence that
way; the lines above are from the run that was left alone.

## 2. Slice B — vendor the harness modules

From `m-cyber12/t1` (`arena/f91bdedf-t1`): `harness-core` (bootstrap,
environment, provisioning, packs), `terminal-emulator` (PTY JNI, ANSI
emulator, `harness-exec` preload), `terminal-view` (renderer), `sealed-runtime`
(Android-free install/verify kernel).

The owner's position is that t1 is not a separate product — it was built for
this harness — so the modules are **vendored**, not pulled as a submodule:
one repository, one lockfile, one board.

Known friction, found before writing any code:

| t1 | this app | consequence |
|---|---|---|
| AGP 8.13.2, Gradle 9.2.1 | AGP 8.5.2, Kotlin 1.9.24, Gradle 8.9 | module build files must be adapted **down**; the alternative (upgrading this app's toolchain) drags Kotlin 1.9 → 2.x and the Compose compiler with it, which is its own project |
| `compileSdk 35`, `targetSdk 28` default | `compileSdk 34`, `targetSdk 34` | the vendored modules take this app's values; the targetSdk-28 apt shape is dropped on arrival, because Play is the target |
| NDK 27.2.12479018 | NDK 28.2.13676358 (pinned for the payload shims) | use ours; re-assert PIE/DYN on every produced executable (this is the failure mode that already bit once in the payload build) |
| `pack_buildtools` dynamic-feature module | single-module app | keep the *idea*: on-demand modules are how the 200 MB base-module cap is respected |

No app dependency is added until the modules compile and all existing gates are
still green. At that point the harness is present but inert.

**Slice B status (2026-10-24): the sealed kernel is vendored.** `sealed-runtime`
is the part that decides what may be executed and where it must live, and it is
Android-free by design, so it was ported into this app first, before anything
that needs a device:

| Class | Job |
|---|---|
| `ExecutableKind`, `ExecutableProbe` | ELF, script or data, from the first bytes |
| `RuntimeManifest` | sha256 + size per data file, verified against the bytes as they are written |
| `PayloadExtractor` | the data half of a payload into a prefix |
| `SealedLayout`, `SealedLinks` | every executable becomes a symlink into the APK's native library directory |
| `SealedInstaller`, `SealedUninstaller` | apply a payload, and reverse it exactly |
| `ExecAudit` | walk an installed prefix and say whether anything left in app data needs `execve()` |

It is Kotlin in `app/src/main/java/ai/opencode/android/harness/sealed/`, not a
new Gradle module: a module would have to be a Java 17 `java-library` (as it is
upstream) or an Android library at this app's AGP, and either way the toolchain
question reopens. In-package, it is the same code this app already compiles.

49 JVM unit tests came with it. 46 passed on the first board; the 3 that failed
were the tests asking the wrong question (a digest mismatch claimed as a length
change, a prefix directory that was never created, and an uninstall count that
forgot the second data file), not faults in the kernel.

## 2b. Slice C0 — the kernel, proven on a device (in progress)

Before any userland exists, the installer is exercised on a real Android
kernel with a payload the gate builds itself. `HarnessSealedInstallGateTest`
(stage E of the Phase 6 runner, gates S1-S4):

| Gate | Claim |
|---|---|
| S1 | a payload installs into a prefix: data extracted and verified, executable linked |
| S2 | the sealed executable **runs** from its prefix path, printing the probe's marker |
| S3 | `ExecAudit` says the prefix is publishable: nothing left in app data needs `execve()` |
| S4 | uninstall reclaims exactly the data file and the link, and leaves the APK's own copy alone |

It uses `libocexecprobe.so` — the same PIE, the same ABI list, no new payload —
because this is about the packaging, not the tool. It is the last cheap
falsification available: if S2 fails, everything in section 3 is sand.

**Result (2026-10-24, CI run 40107594196, API 34 / abi x86_64): S1-S4 pass, on
the debug build and again on the release-shaped smoke build.**

```
P6_S1 PASS :: sealed install ok=true files=1 verified=1 executables=1 problems=[]
P6_S2 PASS :: prefix exec symlink=true ran=true exit=0 targetSdk=34 api=34 :: OCEXECPROBE ok
P6_S3 PASS :: exec audit publishable=true shipped=1 outside=[] scanned=4 failure=null
P6_S4 PASS :: uninstall files=2 bytes=15 problems=[] probeStillInApk=true
```

S2 is the load-bearing one: the executable ran from its prefix path, installed
by this app's own code, on a real kernel — X3 again, but reached through the
installer rather than by hand, which is the path a userland will take. S3's
`outside=[]` is the audit re-deriving that from the filesystem rather than
believing the installer's counts. UI totals 27/27, JVM 477/477.

## 3. Slice C — the sealed payload

**C1 (done, 2026-10-24): the payload is sealed for the runtime we already
ship.** Before a userland exists, the split itself had to become real: a
generator, an asset, and an app that links from build output instead of from a
hard-coded table.

- `phase4/scripts/12-seal-payload.py` — turns a payload into the two halves:
  executables (`sealed-exec-<abi>.txt`, each a symlink into the APK) and data
  (`userland-<abi>.zip` + `sealed-manifest-<abi>.txt`, verified while written).
  **Its one rule is enforced, not remembered: a file that would have to be
  executed is refused as data.** It is checked with the same two tests the
  on-device classifier uses (ELF magic, `#!`), so a build that drops a binary
  into the data half fails in CI instead of working on an old device and dying
  on a new one. It runs its own `--self-test` at the top of every payload build.
- `HarnessSeal` + `SealedLayout` now create the app's `bin/` links
  (`bin/bun`, `bin/git`, `bin/rg`, git's two http helpers) from the shipped
  manifest. Same five links, same paths — but derived from build output,
  idempotent, and auditable. The old table is kept as a loudly-logged fallback,
  because a packaging bug must never present as "this device has no git".
- Gates T1-T3 (stage F): the manifest links every tool into the APK, `bin/rg`
  and `bin/git` actually run from their prefix paths, and `ExecAudit` says the
  runtime prefix is publishable.

This is the first change in step 1 that touches production code — the path that
creates the runtime's symlinks — and it is deliberately behaviour-preserving:
the server still starts on Bun, nothing the user sees changed.

**C2 (built, 2026-10-24): a shell and the coreutils.** The two cheapest real
answers to "full tools", and both are what Android itself ships:

| | What it is | Why this one |
|---|---|---|
| `libtoybox.so` | ~190 coreutils in **one** ELF that dispatches on `argv[0]` | toybox *is* AOSP's toolbox: maintained for bionic, and one binary costs one symlink per command instead of one binary |
| `libmksh.so` | the shell (`bin/sh`) | mksh was Android's own `/system/bin/sh` for years; toybox has no shell at all (its `sh` is unfinished, in `pending/`) |

Both cross-compile with the NDK we already pin, both are built from pinned
commits, both are PIE/DYN and asserted so, and both are sealed: every command
is a symlink into the APK. Total addition to the APK: well under 2 MB per ABI
for ~190 tools.

The command list is not a hand-written table: it is read out of the build's own
generated tables by `scripts/install.c`, so the manifest cannot disagree with
the binary that was built. The miniconfig is AOSP's `android_miniconfig` minus
nine symbols - libselinux, libcrypto and libz, none of which the NDK ships, so
toybox falls back to its own hashes and its own deflate.

**Still to do in slice C:** python, node-as-a-tool, curl/wget, jq, openssh - and
wiring any of it to the agent, which is slice D.

Play forbids downloading executable code, so the store shape is **sealed**:
every executable is a symlink into `nativeLibraryDir` (the inode comes from the
APK), read-only files (Python's stdlib, gems, the dpkg database) are data
assets unpacked on first run, and scripts are never `execve`-d — the preload
translates their interpreter.

t1's numbers, to be re-measured here: 535 executables → 252 JNI libraries,
13 858 data files, 1 173 data symlinks, `ExecAudit` zero, core tier 360-380 MB
on device, clang/cmake ~600 MB as an on-demand module, Play's base-module cap
200 MB (warning at 150 MB).

Expected first-run cost: extracting the data payload and provisioning the core
tier (python, pip, node, npm, perl, ruby, git, ripgrep, jq, curl, openssh);
the owner's own measurement is "about a minute", and the shell stays usable
with a retry if it fails.

## 4. Slice D — the surface

The app already has a Terminal tab (today: degraded, one-shot). It becomes a
real PTY: `HarnessSession` + the Termux emulator, rendered through
`AndroidView` inside our Compose screen so the app keeps its own design
language. Provisioning gets a real UI state (idle → resolving → installing →
verifying → ready, with the failure's own words, never a spinner that lies),
and the agent's environment brief stops saying "no python, no node, no curl".

## 5. Slice E — API 36

`compileSdk`/`targetSdk` → 36, an API 36 AVD lane in CI, and every gate
re-proved there — X1-X3 first, because they are the ones the platform could
break silently. Then the payload's own binaries (bun, git, ripgrep, the shims)
at 36, where the seccomp surprises have historically landed.

## 6. What would make me stop and say so

- X1 or X3 fails at 34: the harness cannot run here as designed, and the plan
  changes (or the target does).
- X2 "fails" by *succeeding*: the platform is not enforcing W^X on this
  device/ABI — which means our current runtime is fine by luck rather than by
  design, and the sealed shape becomes non-negotiable rather than preferred.
- The base module cannot hold the core tier: clang stays a feature module, and
  if the core itself does not fit, the core tier gets cut (Ruby and Perl are the
  first candidates — they are the least used by an agent, not the largest).

## 7. Not in scope for step 1

Removing Bun; porting the OpenCode server to Node; the Play upload itself;
anything that changes what the agent says or does. Those belong to step 3 and
to their own measurement.
