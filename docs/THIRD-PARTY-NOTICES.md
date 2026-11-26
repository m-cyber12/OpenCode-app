# Third-party notices

OpenCode for Android runs other people's software on your phone. Those components are inside
the app, so their licences come with it. Settings → *Open source* in the app names the main
components; this page is the complete list, with the licence of each one.

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
| **Python** (the agent's scripting interpreter) | 3.13.1 | **PSF-2.0** | <https://www.python.org/downloads/release/python-3131/> |
| **jq** (the agent's JSON tool) | 1.7.1 | **MIT** | <https://github.com/jqlang/jq/releases/tag/jq-1.7.1> |
| **toybox** (the everyday command-line tools) | built from a pinned commit | **0BSD** | <https://landley.net/toybox/> |
| **mksh** (the `sh` the agent's shell tool runs) | built from a pinned commit | **MirOS licence** | <http://www.mirbsd.org/mksh.htm> |
| **tree-sitter** (`web-tree-sitter` 0.25.10, `tree-sitter-bash` 0.25.0) | 0.25.x | **MIT** | <https://github.com/tree-sitter/tree-sitter> |

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

The GPL-2.0 requires that the complete corresponding source of a distributed binary is made
available to whoever receives it. A written offer to supply that source satisfies the licence.
For this app:

* **The Git source** is the upstream release tarball for v2.48.1:
  `https://mirrors.edge.kernel.org/pub/software/scm/git/git-2.48.1.tar.xz`. It is also
  mirrored at the GitHub release linked above.
* **The build recipe and the patches** applied to the pinned upstream sources are **not
  published**, and are no longer needed to identify the source: every source is the upstream
  release named above, at the version named above, built with the Android NDK (see
  `runtime-manifest.json` inside the APK for the SHA-256 of every file shipped).
* **This notice is the written offer** required by GPL-2.0 §3(b): for three years from the day
  this app is last distributed, the complete corresponding source of the Git build — the pinned
  upstream tree plus the exact configure options, toolchain settings and patches used — is
  available on request by email to **noxifera.dev@gmail.com**. Write "Git source" in the subject
  line. Nothing is charged beyond the cost of the medium.

Nothing in this app is statically linked against Git, and no Git code is compiled into the app.

## Used to build the app only (not shipped to your phone)

| Component | Licence | Role |
|---|---|---|
| Android Gradle Plugin and Gradle | Apache-2.0 | build |
| Android NDK and SDK build tools | Apache-2.0 / proprietary (SDK terms) | build |
| Cargo and the Rust toolchain | MIT / Apache-2.0 | builds ripgrep for Android |
| Node.js and npm | MIT | toolchain for building the server bundle |
| Bun | MIT | bundles the OpenCode server at build time; not shipped |

## Attribution in the app

Settings → *About* says that this is an independent client of the open-source OpenCode project,
that it is not endorsed by that project, and who the name and logo belong to. Settings →
*Open source* lists the components above, the GPL note for Git, and links to the upstream licence
texts and to this file.

## Reporting

If you think something here is wrong, such as a missing attribution, an incorrect licence, or a
component you did not expect to find in the app, open an issue in this repository.
