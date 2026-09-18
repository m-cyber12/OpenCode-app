# Third-party notices

This app is a native Android client that runs other people's software on your
device. Those components are inside the APK, so their licences travel with it.
This file is the human-readable half; the in-app copy (Settings → *Open source*)
shows the same list to every user and links to the licences.

Everything is pinned in [`versions.lock`](../versions.lock) and verified against
the shipped artifact by `phase10/scripts/check-apk.py` and the device gate
`P9_LOCK`.

## Bundled and executed

| Component | Version | Licence | How it is shipped | Pinned source |
|---|---|---|---|---|
| **OpenCode** (the agent, its tools, its server) | 1.18.23, commit `05ea5073be967c779d326929b2de6228dda4159d` | **MIT** | bundled JavaScript, unmodified apart from the Android adaptation documented in `docs/ARCHITECTURE.md` | <https://github.com/anomalyco/opencode> @ `05ea5073` |
| **Bun** (the JavaScript runtime that executes it) | 1.3.14 | **MIT** | `arm64-v8a` and `x86_64` bionic-linked executables (from the official `@oven/bun-*-android` packages), packaged as `libbun.so` so Android will exec them | <https://github.com/oven-sh/bun> |
| **Git** (the agent's version-control tool) | v2.48.1 | **GPL-2.0-only** | statically built for Android from the official release tarball with the NDK toolchain, packaged as `libgit.so` | <https://github.com/git/git/releases/tag/v2.48.1> |
| **ripgrep** (the agent's search tool) | 15.1.0 | **MIT** (or the Unlicense, at your option) | built for Android with Cargo + NDK, packaged as `librg.so` | <https://github.com/BurntSushi/ripgrep/releases/tag/15.1.0> |
| **@opencode-ai/plugin** (+ its dependencies: `@opencode-ai/sdk`, `zod`, `effect`, `@ai-sdk/provider`) | 1.18.23 | MIT (and the respective licences of each package) | pre-installed plugin tree in the runtime payload (`plugin-seed/` → `xdg/config/opencode/node_modules`) | <https://registry.npmjs.org/@opencode-ai/plugin> |

## Linked into the app

| Component | Licence |
|---|---|
| AndroidX (core-ktx, activity-compose, lifecycle, documentfile) | Apache-2.0 |
| Jetpack Compose (UI, Material 3, Foundation, tooling) | Apache-2.0 |
| Kotlin standard library and coroutines | Apache-2.0 |

## GPL compliance for the bundled Git binary

Git is the only copyleft component, and it is shipped as a **separate program**,
executed as a child process; the rest of the app is not a derivative work of it
and is not placed under the GPL. The GPL-2.0 requires that the complete
corresponding source of a distributed binary be made available, so:

* the **exact source** is the upstream release tarball for `v2.48.1`
  (`https://mirrors.edge.kernel.org/pub/software/scm/git/git-2.48.1.tar.xz`,
  also mirrored at the GitHub release above);
* the **exact build recipe** used for this app - toolchain, configure flags,
  patches (there are none beyond a Bionic-compatibility configure) and the
  resulting binary's sha256 - is
  [`phase4/scripts/10-build-payload.sh`](../phase4/scripts/10-build-payload.sh)
  in this repository, and the sha256 of every shipped file is in the payload's
  `runtime-manifest.json`;
* this constitutes the **written offer** required by GPL-2.0 s3: the corresponding
  source is available from the locations above, for as long as this app is
  distributed. Requests can also be opened on the issue tracker.

Nothing in this app is statically linked against Git, and no Git headers or code
are compiled into the Kotlin application. `docs/RUNTIME.md` documents how the
binary is launched.

## Build-time only (not shipped to users)

| Component | Licence | Role |
|---|---|---|
| Android Gradle Plugin, Gradle | Apache-2.0 | build |
| Android NDK / SDK build-tools | Apache-2.0 / proprietary (SDK terms) | build |
| Cargo / Rust toolchain | MIT / Apache-2.0 | builds ripgrep for Android |
| Node/npm | MIT | installs the pre-seeded plugin tree at payload-build time |

## Attribution in the app

Settings → *About* states that this is an independent client of the open-source
OpenCode project, that it is not endorsed by that project, and who the name and
logo belong to. Settings → *Open source* lists the components above, the GPL note
for Git, and links to the upstream licence text and to this file.

## Reporting

If you believe something here is wrong - a missing attribution, an incorrect
licence, or a component you did not expect to find in the APK - open an issue at
<https://github.com/m-cyber12/OpenCode-app/issues>. `phase10/scripts/check-apk.py`
can list what is actually inside a build, and the payload manifest lists every
file with its hash.
