# Third-party notices

OpenCode for Android runs other people's software on your phone. Those components are inside
the app, so their licences come with it. Settings → *Open source* in the app shows the same list,
with links to the licence texts.

## Bundled and run by the app

| Component | Version | Licence | Upstream source |
|---|---|---|---|
| **OpenCode** (the agent, its tools and its server) | v2.2.5, commit `9bbb8ce18c80f099e0e448775f59d965ead40a21` | **MIT** | <https://github.com/anomalyco/opencode/releases/tag/v2.2.5> |
| **Node.js** (runs OpenCode on your phone) | 26.4.0 | **MIT**, plus the licences of Node's bundled dependencies (listed in its `LICENSE` file) | <https://github.com/nodejs/node/releases/tag/v26.4.0> |
| **Git** (the agent's version-control tool) | v2.48.1 | **GPL-2.0-only** (see the GPL section below) | <https://github.com/git/git/releases/tag/v2.48.1> |
| **ripgrep** (the agent's search tool) | 15.1.0 | **MIT** (or the Unlicense, at your option) | <https://github.com/BurntSushi/ripgrep/releases/tag/15.1.0> |
| **curl (libcurl)** (HTTP engine for Git's HTTPS remotes, and the wget-compatible downloader) | 8.10.1 | **curl licence** (MIT-like) | <https://github.com/curl/curl/releases/tag/curl-8_10_1> |
| **Mbed TLS** (TLS for libcurl) | 3.6.2 | dual **Apache-2.0 OR GPL-2.0-or-later**, used under the **GPL-2.0-or-later** option so the combined Git HTTPS helper is licence-consistent | <https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.2> |
| **OpenSSH portable** (SSH client tools; no SSH server) | 10.6p1 | BSD-style licences (see the upstream `LICENCE` file) | <https://github.com/openssh/openssh-portable/releases/tag/V_10_6_P1> |
| **OpenSSL libcrypto** (cryptography for OpenSSH) | 3.6.5 | **Apache-2.0** | <https://github.com/openssl/openssl/releases/tag/openssl-3.6.5> |
| **@opencode-ai/plugin** (with `@opencode-ai/sdk`, `zod`, `effect` and `@ai-sdk/provider`) | 1.18.23 | MIT, and the licence of each package | <https://registry.npmjs.org/@opencode-ai/plugin> |

## Linked into the app

| Component | Licence |
|---|---|
| AndroidX (core-ktx, activity-compose, lifecycle, documentfile) | Apache-2.0 |
| Jetpack Compose (UI, Material 3, Foundation, tooling) | Apache-2.0 |
| Kotlin standard library and coroutines | Apache-2.0 |
| Eclipse JGit 6.10.0 (the app's own GitHub clone and push code) and its `slf4j-api` dependency | EDL-1.0 (BSD-3-Clause); slf4j: MIT |

## GPL notice for the Git program

Git is the only component under a copyleft licence. It ships as a **separate program** that the app
starts as a child process. The rest of the app is not a derivative work of Git and is not placed
under the GPL.

The GPL-2.0 requires that the complete corresponding source of a distributed binary is available.
For this app:

* **The Git source** is the upstream release tarball for v2.48.1:
  `https://mirrors.edge.kernel.org/pub/software/scm/git/git-2.48.1.tar.xz`. It is also
  mirrored at the GitHub release linked above.
* **The build recipe** used for this app, with the toolchain, configure options and patches, is
  published in this repository at
  [`phase4/scripts/10-build-payload.sh`](https://github.com/noxifera-dev/Unofficial-Opencode-for-Android/blob/main/phase4/scripts/10-build-payload.sh).
  The SHA-256 of every file shipped in the runtime is listed in `runtime-manifest.json`, which is
  inside the APK.
* **This notice is the written offer** required by GPL-2.0. The corresponding source is available
  from the places above for as long as this app is distributed. You can also ask for it on the
  issue tracker.

Nothing in this app is statically linked against Git, and no Git code is compiled into the app.

## Used to build the app only (not shipped to your phone)

| Component | Licence | Role |
|---|---|---|
| Android Gradle Plugin and Gradle | Apache-2.0 | build |
| Android NDK and SDK build tools | Apache-2.0 / proprietary (SDK terms) | build |
| Cargo and the Rust toolchain | MIT / Apache-2.0 | builds ripgrep for Android |
| Node.js and npm | MIT | installs the pre-included plugin tree |
| Bun | MIT | bundles the OpenCode server at build time; not shipped |

## Attribution in the app

Settings → *About* says that this is an independent client of the open-source OpenCode project,
that it is not endorsed by that project, and who the name and logo belong to. Settings →
*Open source* lists the components above, the GPL note for Git, and links to the upstream licence
texts and to this file.

## Reporting

If you think something here is wrong, such as a missing attribution, an incorrect licence, or a
component you did not expect to find in the app, open an issue at
<https://github.com/noxifera-dev/Unofficial-Opencode-for-Android/issues>.
