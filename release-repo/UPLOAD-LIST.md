# Files for the new repository

Upload these to **noxifera-dev/Unofficial-Opencode-for-Android** (default branch `main`), keeping the same paths.
Every file here is a copy of a file in this repository, rewritten or checked for users.

| Path in the new repo | Source in this repo | Why |
|---|---|---|
| `README.md` | `release-repo/README.md` | front page for users |
| `PRIVACY.md` | `release-repo/PRIVACY.md` | privacy summary |
| `SECURITY.md` | `release-repo/SECURITY.md` | how to report a problem |
| `docs/PRIVACY-POLICY.md` | `release-repo/docs/PRIVACY-POLICY.md` | linked from the app (Settings → About) |
| `docs/SECURITY.md` | `release-repo/docs/SECURITY.md` | how keys and files are protected |
| `docs/THIRD-PARTY-NOTICES.md` | `release-repo/docs/THIRD-PARTY-NOTICES.md` | linked from the app (Settings → Open source) |
| `phase4/scripts/10-build-payload.sh` | copy | the Git build recipe (GPL-2.0 written offer) |
| `phase4/scripts/12-seal-payload.py` | copy | used by the same recipe |
| `phase4/payload/` (26 files) | copy | patches and sources used by the same recipe |
| `versions.lock` | copy | pinned versions the notices refer to |

Not uploaded yet: `LICENSE` (GPL). The owner adds it when the source is published.
Not uploaded: the release APK. Attach it to a GitHub Release as the README describes.
The in-app links point to `https://github.com/noxifera-dev/Unofficial-Opencode-for-Android/...`, so the paths
above must exist there for those links to work.

## Security source files (release-repo/security-source/)

These keep their original paths under `security-source/`. They are the parts the security
document describes: key storage, the loopback-only server, runtime file checks, the picture
download policy, and the permission and shell patches for OpenCode v2.2.5. They are excerpts:
they depend on other project files, so they will not build on their own.

| Path in the new repo | What it does |
|---|---|
| `security-source/app/src/main/java/ai/opencode/android/security/SecretStore.kt` | encrypts the server password and provider keys with the Android Keystore |
| `security-source/app/src/main/java/ai/opencode/android/security/ProviderKeyring.kt` | stores and removes provider keys |
| `security-source/app/src/main/java/ai/opencode/android/security/SecretNames.kt` | names of the stored secrets |
| `security-source/app/src/main/java/ai/opencode/android/security/GithubConnector.kt` | GitHub token handling |
| `security-source/app/src/main/java/ai/opencode/android/client/LoopbackGuard.kt` | refuses any server address that is not on the phone itself |
| `security-source/app/src/main/java/ai/opencode/android/runtime/LoopbackAudit.kt` | checks that the server listens only on loopback |
| `security-source/app/src/main/java/ai/opencode/android/runtime/PayloadExtractor.kt` | checks each runtime file's size and sha256 against the manifest before it is used |
| `security-source/app/src/main/java/ai/opencode/android/web/WebImagePolicy.kt` | the rule for downloading pictures: https only, and only to public addresses |
| `security-source/phase4/payload/v2-patches/permission-guard.ts` | keeps config-file writes behind a prompt, even when a session is allowed |
| `security-source/phase4/payload/v2-patches/noexec-shell.ts` | runs non-executable scripts through their interpreter, after the permission check |
