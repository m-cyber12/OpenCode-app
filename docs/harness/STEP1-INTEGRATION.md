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

**D (wired, 2026-10-24): the agent actually uses it.** PATH already began with
`bin/` for bun/git/rg, so a shell and 190 coreutils appeared on the agent's PATH
for free the moment they were sealed. Two changes made that honest:

- `SHELL` now points at our `bin/sh` instead of `/system/bin/sh`. PATH and SHELL
  disagreeing is how a script finds the wrong `test`.
- the environment brief tells the model the prefix is *ours* - one pinned
  version, the same command set on every phone - and to prefer it to the
  device's `/system/bin`, whose toybox varies by model and Android version.

V3 gates the difference: it runs the pipeline through `RuntimeEnv.build` - the
same block the server is launched with - and names the winner of `command -v tr`,
because `/system/bin` has its own toybox and a PATH that merely *contains* `bin/`
would still hand the agent the device's tools.

**The size budget is now measured, not assumed** (new `P10_SIZE` gate). Play's
200 MB base-module cap is a wall, not a target: over it, tools must move to a
dynamic feature module, which is a different product rather than a bigger
number. Current reading: **132.7 MB, 67.3 MB of headroom**. The shell and 190
coreutils cost about 2 MB of that. Python (stdlib as payload data, compressed)
and a real `node` are the next questions, and both have to be measured against
that 67 MB before they are promised.

**E (2026-10-24): curl, for free.** Git's smart-HTTP transport is built against
a static curl, so the `curl` binary exists whether we ship it or not - shipping
it as `bin/curl` is one copy. It is built with NO baked-in CA bundle
deliberately, so `CURL_CA_PATH` now points it at Android's own CA store (the
same directory git gets); without that, every https request dies with a
certificate error that looks like the network being down. V4 asserts both: that
curl runs, and that the CA path points at a directory that exists.

**jq (2026-10-24): the first real cross-build here.** jq needs oniguruma for
its regex functions, and jq's repo carries oniguruma as a *submodule* - so a
clone has an empty `modules/oniguruma` and `--with-oniguruma=builtin` fails
there, while a release tarball may or may not bundle it. So oniguruma is built
separately and jq is pointed at it: the answer no longer depends on what any
tarball happens to ship. V5 calls `test("b")` on purpose - a jq built without
oniguruma still starts, still prints its version and still answers `.a`; it
only fails when something uses a regex function, which is the worst possible
place to find out.

**python (2026-10-24): measured before it was promised.** Two things make
CPython work here, and both were validated on a host build first:

- **every extension module is linked into the interpreter.** Android cannot
  `dlopen` from app data, so the usual `lib-dynload/` of shared modules would
  be dead weight that fails on first import. `Modules/Setup.local` names them
  with `*static*`, and modules needing libc functions Android lacks (syslog,
  getgrent) or libraries we don't ship (OpenSSL, libffi, sqlite) are dropped by
  name. Measured: **7.3 MB stripped**, all modules inside.
- **the stdlib is ONE source zip, read through zipimport** (~570 modules as a
  single **~4.3 MB** entry) rather than 570 files the extractor writes and
  checksums at first run. Do not flatten tagged `__pycache__` entries into a
  sourceless archive: zipimport looks for `module.pyc`, not
  `module.cpython-313.pyc`; board 40191275984 proved that mistake prevents
  Python from importing even `encodings` during startup.

The cross-build itself needs a build python of the same version, so the payload
builds a host CPython first rather than betting on what the CI image has.

**First board (40163562970) failed, and both failures were CPython mechanics,
not Android surprises** - recorded here so they are not rediscovered:

- `_ctypes` died on `ffi.h: file not found`. Configure finds the CI host's
  libffi (likewise sqlite/bzip2/lzma/readline) and enables the module; the
  cross compiler then has no such header. Filtering those names out of the
  Setup.stdlib copy appended to Setup.local was NOT enough, because makesetup
  also reads Setup.stdlib itself. makesetup is **first-rule-wins** and reads
  Setup.local first, so the fix is a `*disabled*` section at the END of
  Setup.local naming every unwanted module - that removes it from all files.
- `_PyMp_sem_unlink` was an undefined symbol at final link. bionic has no
  named POSIX semaphores, so `HAVE_MP_SEMAPHORE` is undefined and semaphore.c
  compiles to nothing, while multiprocessing.c references the symbol whenever
  `POSIX_SEMAPHORES_NOT_ENABLED` is also undefined. `_multiprocessing` is
  therefore not built: plain `Process` still works (it is fork + pipes), but
  `Lock`/`Queue` raise ModuleNotFoundError. Verified on the host build.
- The appended Setup.stdlib copy must contribute **module lines only**: the
  file starts with its own `*shared*` marker, and copying that marker turns
  everything appended back into shared objects (which `-pie` then refuses to
  link, wanting a `main()`). Stripped interpreter is now **8.0 MB** with the
  full static set (_decimal, CJK codecs, expat included), still zero `.so`.

**Second board (40191275984) proved the native build and found the zip error.**
Both ABIs built, all packaging/audit gates passed, and the APK was **141.1 MB**
with **58.9 MB headroom**. V6 alone failed at interpreter startup. The earlier
host "validation" of the compiled zip was false: that host still had an
installed stdlib at its configured prefix, so imports silently fell back to
it. Reproducing with an isolated `PYTHONHOME` gave the same fatal `encodings`
failure. The payload now keeps `.py` source (smaller anyway), and RuntimeEnv
sets both the sealed `PYTHONHOME=files/usr` and the exact zip in `PYTHONPATH`.

**Third board (40195652075) proved the zip still was not in the APK.** The
source archive itself was correct, but `runtime-payload.tar.gz` was packed
before `build_python_stdlib` created `STAGE/usr/lib/python3.13.zip`, while the
later seal invocation carried executable records only (no `--data-dir`). A
file existing in STAGE is not shipping evidence. Payload packing now happens
after every producer, `PayloadExtractor` promotes the verified `usr` root, and
the build directly asserts that both the tar and runtime manifest contain the
zip before Gradle is allowed to package the app.

**Fourth board (40200765125) proved Python itself works.** The APK grew to
**145.4 MB** (the 4.5 MB archive finally shipped), Python started, found its
source zip, and completed all V6 imports. The traceback was the test command:
the shell's outer single quotes consumed Python's inner quotes, turning
`{'ok': True}` into `{ok: True}` and `b'abc'` into `babc`; the resulting
`NameError` is reproduced byte-for-byte with any shell. V6 now wraps `-c` in
double quotes so those Python literals arrive intact. **Board 40205885278 is
green:** V6 passed on debug and release-shaped builds with JSON `{"ok": true}`
and CRC32 `891568578`; headroom is **54.6 MB**.

**Node/npm (build pending):** Node 24.22.1 is cross-built from source because
the tempting nodejs-mobile binaries are Node 18 shared libraries with no
`main()` and 4 KiB ELF alignment - both wrong for this CLI and API 36's 16 KiB
page requirement. Upstream labels Android unsupported, so the build applies
the exact Termux nodejs-lts patch set pinned in `versions.lock`, then strips a
PIE with bundled dependencies and no Intl/inspector/snapshot. npm 11.19.1 is
the package bundled by that Node tag. Its JS is verified payload data;
`bin/npm` and `bin/npx` point to a tiny native shim that execs sealed Node with
the CLI path, so neither launcher depends on executing a script from app data.
V7 creates and installs a local package (no network), then requires it and
expects 42 - version text alone is not package-manager evidence. First board
40211219623 reached the arm64 compile and found one upstream Android assumption:
Chromium's bundled zlib includes the NDK-removed `cpu-features.h`. The payload
already builds pinned zlib 1.3.1 per ABI for Git/Python; Node now links that
static archive through its external-zlib configure path instead of compiling a
second, incompatible zlib copy. Board 40214575682 then found cross-build path
leakage: the x86_64 host generator `node_js2c` was handed arm64 `libz.a`.
Termux strips target dependency paths from generated host Ninja rules; this
build uses Ninja, so it strips those paths from `obj.host/*.ninja` and asserts
none survive. Target rules still use the pinned Android archive; host generators
use the runner's host zlib. Board 40218036288 reached the old **90-minute**
payload timeout while Make was still compiling and racing duplicate host/target
outputs. Activating Termux's Ninja generator fix removed that race. Board
40227986986 then produced a **52,563,432-byte stripped arm64 Node**, proving the
build, but the four-hour payload guard expired just after x86_64 started. The
workflow has a nine-hour job cap; the payload now has a finite eight-hour guard,
leaving the measured final hour for device and release stages.

**Still to do after the Node size/run board:** wget and openssh.

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
