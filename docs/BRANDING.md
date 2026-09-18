# Branding, naming and trademark position

Status of this document: it records what the app is called, what it looks like,
**why**, and what happens if the arrangement it depends on does not hold. It is a
release document, not legal advice: the trademark question below is the project
owner's decision, taken with the facts stated here.

## 1. The decision

| Thing | Value | Where it lives |
|---|---|---|
| Display name | **OpenCode** | `app/src/main/res/values/strings.xml` (`app_name`, `welcome_title`), `AndroidManifest.xml` `android:label` |
| Launcher icon | the **upstream OpenCode icon**, as shipped by the desktop app (the `prod` channel set) | `app/src/main/res/mipmap-*/` + `mipmap-anydpi-v26/` |
| Published package name | **`io.github.mcyber12.opencode`** | `app/build.gradle.kts` `applicationId` |
| Kotlin namespace | `ai.opencode.android` (internal, not user-visible) | `app/build.gradle.kts` `namespace` |

The icon files are copied from the upstream repository at the pinned commit, from
`packages/desktop/icons/prod/android/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`
(`ic_launcher.png`, `ic_launcher_round.png`, `ic_launcher_foreground.png`),
`packages/desktop/icons/prod/android/mipmap-anydpi-v26/ic_launcher.xml` and
`packages/desktop/icons/prod/android/values/ic_launcher_background.xml`. Upstream
ships no round adaptive-icon XML; the `ic_launcher_round.xml` in this repository
is the same foreground/background pair written out locally.

## 2. Why the name and icon are upstream's, and what that depends on

OpenCode's **code** is MIT licensed: using it, modifying it, and shipping it
commercially are permitted, with attribution (`LICENSE` in the upstream repository,
and `docs/THIRD-PARTY-NOTICES.md` here). A **name and a logo are not the code**.
Trademark rights are separate from copyright, and the upstream repository contains
no trademark or brand-usage policy file that grants or denies permission - so
using the name and logo is a request that has to be made, not a right that comes
with the licence.

The project owner's decision is:

1. ship with upstream's name and icon, because this app runs the genuine OpenCode
   agent unmodified on the device and its purpose is to be recognised as such;
2. **ask the OpenCode project for permission** to use the name and logo for this
   independent client, stating plainly that it is an independent build;
3. if permission is not granted (or no answer arrives in time for the release),
   **rename**: a new name and a new icon, with no change to the permanent package
   name.

That third step is why the application ID is **not** `ai.opencode.*`: it is
`io.github.mcyber12.opencode`, in the developer's own namespace, so a rename is a
change of visible strings and resources - not a new Play listing with zero
installs. **The package name can never change after the first publish** (Play does
not allow it), which is exactly why it was chosen to survive the rename.

## 3. Independence, stated in the app and in the listing

Using a name and a logo makes non-affiliation something that has to be said out
loud, not implied. It is said in three places:

* **In the app**, Settings → *About*: "This is an independent client built on the
  open-source OpenCode project. It is not built, published or endorsed by the
  OpenCode project or its maintainers", plus the same for the logo, plus two links
  (upstream project, this app's privacy policy).
* **In Settings → Open source**: what is bundled (OpenCode MIT; Bun MIT; Git
  GPL-2.0-only; ripgrep MIT/Unlicense), the written offer for the GPL component,
  and links to upstream's licence text and this repository's full notices.
* **In the store listing** (`docs/STORE-LISTING.md`): the first lines of the long
  description state that this is an independent client of the open-source OpenCode
  project and not affiliated with it, and the app title is followed by a
  "built on the open-source OpenCode project" clarifier wherever Play allows one.

## 4. If the rename happens

Everything the rename touches is in this list, and nothing else:

| Change | File(s) |
|---|---|
| Display name, notification text, welcome copy | `app/src/main/res/values/strings.xml` |
| Launcher icon, adaptive icon background | `app/src/main/res/mipmap-*/`, `values/ic_launcher_background.xml` |
| Listing copy, screenshots, feature graphic | `docs/STORE-LISTING.md`, `docs/store/` |

**Not** changed: `applicationId` (permanent), the Kotlin namespace, the harness
package references, `versions.lock`, and every statement of independence above
(which stay true, and keep the attribution that the MIT licence requires).

`phase10/scripts/check-release-invariants.py` is what keeps that claim honest
mechanically: it fails if `applicationId` is moved back under upstream's domain
(`ai.opencode.*`), if the icon resources disappear, or if the manifest stops
declaring the icon and round icon.

## 5. Open items before upload

- [ ] **Send the permission request** to the OpenCode project (issues/discussion)
      and record the answer next to this document. Until an answer exists, the
      app is published under a name and mark it does not own - the owner accepted
      this risk explicitly, on the basis that the rename path above is one commit
      and does not touch the package name.
- [ ] Confirm the shipped icon set matches the pinned upstream commit (the files
      are copies, so this is a diff, not a judgement call).
- [ ] If the rename path is taken, re-shoot the store screenshots: the launcher
      icon and the app name appear in the listing imagery.
