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

**Slice A (in progress).** `phase4/payload/native/exec-probe.c` — a PIE
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

## 3. Slice C — the sealed payload

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
