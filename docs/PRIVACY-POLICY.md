# Privacy policy

**App:** OpenCode for Android (`io.github.mcyber12.opencode`)
**Effective date:** 2026-09-18
**Contact:** the issue tracker at <https://github.com/m-cyber12/OpenCode-app/issues>

This policy describes what the app actually does on your device. It is written
from the implementation (the runtime host in `app/src/main/java`, the security
layer in `ai/opencode/android/security`, and the capability matrix in
`docs/CAPABILITY-MATRIX.md`), not from a template - and
`phase10/scripts/check-release-invariants.py` fails the build if this file stops
describing the shipped behaviour.

## The short version

* The **coding agent runs on your device**. There is no account, no sign-up, no
  server operated by the developer, and no telemetry.
* The app has **no analytics, no advertising and no crash-reporting SDK**. The
  developer receives no data about you or your usage, because there is no channel
  for it to arrive through.
* The only traffic the app initiates on its own is to **the model provider you
  configure**, plus whatever the agent fetches when it uses a tool you permitted.
* Provider API keys are stored **encrypted with the Android Keystore**; they are
  never shown again, never exported, and never included in the diagnostics bundle.

## What the app stores on your device

Everything below stays inside the app's own storage on your device unless you
explicitly export, publish or share it. Two locations are involved, and the
difference matters only for who can reach the files:

* **app-private storage** (`/data/data/…`) holds the runtime, the configurations,
  the logs and the encrypted keys - unreadable to other apps AND to a connected PC;
* **the app's own folder on shared storage**
  (`/storage/emulated/0/Android/data/<app>/files/workspaces/…`) holds your project
  folders. It is readable by `adb` / a connected computer on a stock, non-rooted
  phone (which is the point: the files are yours and should not be locked inside an
  app). On Android 11 and newer, no other *app* can browse it; on Android 10, an
  app that holds the legacy storage permission technically can, which is why this
  policy names the path rather than promising more than the platform does. The
  app's file browser, "Save a copy" and "Publish to a folder" all read from there.

| Data | Why | Where |
|---|---|---|
| Project folders and their files | the agent works in a folder you create or import | the app's own folder on shared storage (see above; on Android 11+ other apps cannot read it, on Android 10 an app holding the legacy storage permission can) |
| Conversations (messages, tool calls, results) | your history and session continuity | app-private storage |
| Agent instruction files (project and global "memory") | the agent reads them into every conversation | app-private storage |
| Settings (theme, permission policy, chosen model, MCP configuration) | to keep your choices | app-private storage |
| Provider API keys | to call the model provider you chose | encrypted files (see below) |
| Runtime logs (`runtime.log`, server log) | so a failure can be diagnosed locally | app-private storage |

Uninstalling the app deletes all of it, including the project folders. This is
worth reading twice: a project lives inside the app's storage, so removing the app
removes the files too - use **Export** or **Publish to a folder** for anything you
want to keep outside it. Android backups are
disabled (`allowBackup="false"`), so this data is not copied into a cloud backup
by the system. **Export is user-initiated**: when you export a project or share
diagnostics, the app hands the data to the Android system share/save sheet, and
you choose the destination.

## Provider API keys

* A key you enter is encrypted with an **AES-256-GCM key held in the Android
  Keystore** - the key material is generated inside the Keystore and cannot be
  read back out of it, on hardware-backed (TEE/StrongBox) or software-backed
  devices. The ciphertext is written to app-private storage with no plaintext
  fallback path. Which of the two your device actually provides depends on the
  hardware and the OS, and the app reports what it got rather than assuming:
  Settings -> Keys shows `Hardware-backed: true / false` for the key in use.
* The key is also handed to the embedded OpenCode runtime's own credential store
  so the agent can authenticate to that provider, in the same app-private storage.
* Keys are never displayed again after entry, never written to a log, and
  **excluded from the diagnostics bundle** the app can generate.
* Removing a key in Settings deletes the stored copy.

## What leaves your device, and when

| Destination | What is sent | When |
|---|---|---|
| The model provider you configured (for example OpenRouter, Anthropic, Google) | your prompts, the conversation context the agent sends, file contents/tool output the agent decides are relevant, and your API key | every turn you send |
| Web pages and URLs the agent fetches | the request itself, from your device | only when the agent uses a fetch/search tool and your permission policy allows it |
| The npm registry | a package install request | only if you configure a *new* plugin that is not already bundled; the plugin OpenCode needs is pre-installed |
| Local servers you add (MCP) | depends on that server; a local-process MCP server runs on the device, while a remote HTTP/SSE MCP server is not supported by this runtime | when you configure and connect one |

The embedded agent's own server listens only on **127.0.0.1** (the device's own
loopback address). It is not reachable from your network or the internet, and the
app's network security configuration permits cleartext traffic only to loopback.

**The model provider is a third party.** What it does with the prompts it receives
is governed by that provider's own privacy policy and terms, not by this one. If
you would rather nothing left the device at all, do not send a turn: the agent
runs locally, but generating a model response requires a model service.

## Permissions the app requests

| Permission | Why it is needed |
|---|---|
| `INTERNET` | loopback traffic to the app's own agent server, and the provider/tool traffic above. Android requires this permission even for loopback connections. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | the agent is a long-running local process (turns, shell commands, MCP servers). Without a foreground service Android would kill it mid-task. A low-priority, dismissible notification tells you it is running. |
| `POST_NOTIFICATIONS` | that notification. Optional: the app asks once and works if you decline. |

No storage, camera, microphone, location or contacts permission is requested.
Importing or exporting a project uses the system file picker, which grants access
only to the folder you pick.

## Children

The app is a developer tool, is not directed at children, and collects no personal
data from anyone. It has no accounts and no user profiles.

## Diagnostics you choose to share

Settings → Agent runtime → *Share diagnostics* produces a text bundle (supervisor
state, versions, device facts, log tails). It is generated **on your device** and
only leaves it through the share sheet you operate. It has no automatic upload.
The bundle excludes provider API keys and the Keystore-encrypted material; a log
tail can contain file paths and tool output from your sessions, so read it before
you post it publicly - the issue tracker is a public place.

## Crash reporting

There is none: no crash-reporting SDK is included, and the developer receives no
crash data automatically. When the agent process fails, the app writes the reason
to its local log and to the notification, and you can share that log yourself.

## Changes to this policy

The policy is versioned in the same repository as the app. A change that affects
what the app does is accompanied by a code change in the same commit, and the
effective date above changes with it. History:
<https://github.com/m-cyber12/OpenCode-app/commits/main/docs/PRIVACY-POLICY.md>.

## Who is responsible

The app is published by its individual developer (the `m-cyber12` GitHub account),
who does not operate any server that receives data from this app. The embedded
agent is the open-source OpenCode project (`anomalyco/opencode`, MIT licensed);
this app is an independent client of it, not affiliated with or endorsed by that
project - see `docs/BRANDING.md`.
