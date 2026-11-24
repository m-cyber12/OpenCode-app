# Files for the new repository

Upload these to **noxifera-dev/Opencode-android** (default branch `main`), keeping the same paths.
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

Not uploaded: the release APK. Attach it to a GitHub Release as the README describes.
The in-app links point to `https://github.com/noxifera-dev/Opencode-android/...`, so the paths
above must exist there for those links to work.
