# Play Store listing package

Everything the Play Console asks for, in one place, so submission is copy-paste -
and so the claims in the listing are the claims in the code. Play's limits are
enforced by `phase10/scripts/check-release-invariants.py`, which reads this file.

**Listing is currently: FREE. No in-app purchases. No ads.**
If that ever changes, a listing that has *ever* declared in-app purchases cannot
remove that declaration, so this section must be updated only when the purchase
actually ships (see "Monetization" below).

---

short_description: OpenCode coding agent runs on your device. Real shell, files, Git, no PC.

full_description: |
  OpenCode for Android runs the real OpenCode coding agent on your phone. Not a
  remote control for a server, not a chat window to somebody's cloud: the agent,
  its shell, its filesystem and its Git are inside this app, on this device.

  This is an independent client built on the open-source OpenCode project. It is
  not built, published or endorsed by the OpenCode project or its maintainers.

  WHAT IT DOES
  - Install, open, type. There is no account, no sign-up and no terminal to
    configure. The agent unpacks itself on first launch and starts working.
  - The agent is OpenCode, unmodified: the same agent loop, the same tools, the
    same session format, running from the upstream code pinned in versions.lock.
  - It edits files, runs shell commands, uses Git, reads and writes your project
    folder, and asks your permission before the things that matter.

  HOW IT LOOKS
  - A clean, conversation-first chat: your turns, the agent's turns, and every
    step it takes shown as it happens.
  - Tool calls are not buried in prose. Each one appears as a terminal-style card
    with its real command, real output, exit status, and diffs where files
    changed, so you can see exactly what is being done to your project.
  - Code is rendered with syntax highlighting, in a monospace block you can copy
    with one tap.
  - Light and dark themes, large touch targets, screen-reader labels throughout.

  YOUR PROJECTS, YOUR DEVICE
  - Create a project folder in the app, or import one from your device.
  - Everything stays in the app's private storage until you export or share it.
  - Choose what the agent may do without asking: shell commands, file edits,
    reads, page fetches, working outside the project folder.

  MODELS
  - Bring your own provider key (OpenRouter, Anthropic, Google and the other
    providers OpenCode supports). The key is stored encrypted with the Android
    Keystore and is never shown again, exported, or written to a log.
  - Or use the built-in default model where it is available.

  MCP
  - Local-process MCP servers work. Remote HTTP/SSE MCP servers are not
    supported by this runtime - an upstream limitation, documented in the app and
    in the project's capability matrix, not a hidden failure.

  PRIVACY
  - No account, no analytics, no advertising, no crash-reporting SDK.
  - The agent's server listens only on the device's own loopback address.
  - Prompts go to the model provider you configure, and only when you send them.
  - Full policy: see the privacy policy link on this listing.

  OPEN SOURCE
  - The app is built on open-source software: OpenCode (MIT), Bun (MIT), Git
    (GPL-2.0-only), ripgrep (MIT or Unlicense). In-app, under Settings, the app
    names each component and links the licences.

  REQUIREMENTS
  - Android 10 or newer, a 64-bit ARM device (arm64-v8a), and a few hundred MB of
    free storage for the bundled runtime.

  A NOTE ON WHAT THIS IS NOT
  - It is not the official OpenCode app, and it does not speak for that project.
  - It is not a code editor. It is an agent that works in your files while you
    talk to it, and shows you everything it does.

---

## Store listing fields

| Field | Value |
|---|---|
| App name (30 char limit) | `OpenCode` |
| Short description (80 char limit) | the `short_description:` line above |
| Full description (4000 char limit) | the `full_description:` block above |
| App category | Developer Tools (`Tools` is the fallback if the category is unavailable in a market) |
| Tags | coding, developer tools, ai, git, terminal |
| Contact email | the address registered with the Play Console developer account (required field; the public support channel is the issue tracker) |
| Support / website URL | `https://github.com/m-cyber12/OpenCode-app` |
| Support channel | `https://github.com/m-cyber12/OpenCode-app/issues` |
| Privacy policy URL | `https://github.com/m-cyber12/OpenCode-app/blob/main/docs/PRIVACY-POLICY.md` |
| Contains ads | No |
| In-app purchases | No (see Monetization) |
| Government app | No |
| News app | No |
| Target audience | 18+ (developer tool; no child-directed content) |
| Monetization | Free, no ads, no in-app purchases |

## Monetization (decision, and what it commits us to)

**This release is free with no in-app purchases.** The previous instruction set
considered a one-time "supporter unlock"; it is deliberately *not* in this build,
because there is no billing code in the app and shipping a listing that declares
in-app purchases without a tested purchase flow would be a promise the app cannot
keep. The ordering also matters in Play: a listing that has never declared IAP can
add it later (with a new version that implements it), while the reverse is
permanent. So the free listing is the reversible choice, taken now.

If the unlock ships later, it needs all of: a Play Billing dependency, a product
ID, a real purchase/restore flow, a verification path against Play, and a listing
update - and `docs/RELEASE.md`'s rule still applies (no entitlement material in
CI). Nothing about the app's core function is designed to be gated.

## Content rating questionnaire (answers to give)

| Question | Answer | Why |
|---|---|---|
| Violence, blood, sexuality, nudity, language, drugs, gambling, horror | No to all | the app has no such content |
| User-generated or user-to-user content shared with others | No | conversations and projects stay on the device; there is no social surface and no server operated by the developer |
| Users can interact / share location | No | no accounts, no social features, no location permission |
| Digital purchases | No | no billing code in this build |
| Unrestricted internet access / web browsing | **Yes** | the agent can fetch pages when the user allows it and calls the model provider over the network; the app also contains a browser-launch for its own information links |
| Miscellaneous: "Shares user's current location", "Allows users to communicate" | No | neither exists |

Expected outcome: **Rated for 3+ / Everyone** with the "unrestricted internet"
disclosure. Reviewer notes should state: all network use is user-configured model
access, user-initiated page fetches, or loopback traffic to the app's own agent
server.

## Data safety form (answers to give)

| Section | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** - the developer receives nothing; there is no developer-operated backend, no analytics and no crash reporting |
| Data collected / shared | None by the developer. (Prompts are transmitted to the model provider **the user configured**, under that provider's policy; Play's form asks about collection by the developer, and this app's developer collects nothing.) |
| Is data encrypted in transit? | Yes - provider traffic is HTTPS to the provider's endpoint; the app's own server is loopback-only |
| Can users request data deletion? | Yes - uninstalling the app or "Clear data" deletes all locally stored data; there is no server-side copy to delete |
| Data types to declare as *not* collected | Location, personal info, financial info, health, messages, photos/videos, audio, files/docs, calendar, contacts, app activity, web browsing, app info and performance, device or other IDs |

**Re-verify before submitting**: Play's form is binding, and this table must match
the build that is uploaded. The prompt text the user types *is* sent to a
third-party model provider by the user's own configuration - the honest place to
say so is the listing description and the privacy policy, both of which do.

## Screenshots and graphics

Play requirements: a 512x512 icon, a 1024x500 feature graphic, at least 2 phone
screenshots (16:9 or 9:16, 320-3840 px per side).

| Asset | File | Source |
|---|---|---|
| App icon (512x512) | `docs/store/icon-512.png` | upstream OpenCode icon, `packages/desktop/icons/prod/icon.png` |
| Feature graphic (1024x500) | `docs/store/feature-graphic-1024x500.png` | composed locally from the upstream icon + wordmark (see the generation note in `docs/store/README.md`) |
| Phone screenshots | `docs/store/screenshots/*.png` | captured **on device**, from the release-shaped build, by the Phase 10 gate run (`phase6/scripts/20-ui-gates.sh` with `P6_PKG=io.github.mcyber12.opencode`) - the same screens the F1-F4/U1-U8 gates assert on |

Screenshots must show the real UI. Do not stage a screen the gates cannot produce:
every file in `docs/store/screenshots/` comes from a device run whose verdicts are
in `docs/progress/phase10-evidence/`.

## Release notes for the first submission (<= 500 chars)

```
First release. The real OpenCode agent runs on your device: shell, files, Git and
MCP, with no PC and no account. Conversation-first UI, terminal-style tool cards
with real command output and diffs, syntax-highlighted code, permission controls,
and provider keys stored in the Android Keystore. Independent client of the
open-source OpenCode project.
```

## Submission checklist

- [ ] `phase10/scripts/check-release-invariants.py . --require-store-assets` passes
      (the Phase 10 pipeline runs exactly this after the screenshot stage and records
      it as the `P10_STORE_ASSETS` verdict, so the checklist item is machine-checked
      rather than remembered)
- [ ] Signed AAB produced from the Phase 10 artifacts (docs/RELEASE.md s4) and verified (s5)
- [ ] `phase10/scripts/90-real-device-signed.sh` run against the signed APK on a real arm64 phone
- [ ] Privacy policy URL is public and matches `docs/PRIVACY-POLICY.md` (the link above is on the `main` branch: merge before submitting, or point the field at the branch's blob URL)
- [ ] Support URL and contact email filled in
- [ ] Content rating questionnaire answered as above; the internet-access disclosure is present
- [ ] Data safety answers as above, re-checked against the uploaded build
- [ ] Screenshots are from the final build (re-shoot after any UI change)
- [ ] Open items from `docs/BRANDING.md` s5 resolved or consciously accepted
