# Privacy policy

**App:** OpenCode for Android (`io.github.NoxiferaDev.opencode`)
**Effective date:** 2026-11-24
**Contact:** the [issue tracker](https://github.com/noxifera-dev/Unofficial-Opencode-for-Android/issues). For private matters, see [SECURITY.md](../SECURITY.md).

This policy describes what the app does on your phone.

## The short version

* **The coding agent runs on your phone.** There is no account, no sign-up, no server run by
  the developer, and no telemetry.
* **The app has no analytics, no advertising and no crash-reporting SDK.** The developer
  gets no data about you or how you use the app.
* **The app contacts only:** the model provider you set up; web pages the agent fetches when
  you allow it; pictures at web addresses in a reply (`https` only); and GitHub, if you connect
  it. See "What leaves your phone".
* **Provider API keys are encrypted** with the Android Keystore. They are never shown again,
  never exported, and never included in the diagnostics you copy.

## What the app stores on your phone

Everything below stays on your phone. It is sent nowhere unless you export or share it.
There are two places:

* **App-private storage** holds the runtime, settings, conversations, logs and encrypted
  keys. Other apps can't read it, and neither can a connected computer.
* **`Documents/OpenCode/` on shared storage** holds your project folders. This is deliberate:
  your work is an ordinary folder, so the Files app, any file manager, a USB connection and
  `adb` can open it while the agent works.

On Android 11 and newer, writing to `Documents/OpenCode` needs the **"All files access"**
permission (`MANAGE_EXTERNAL_STORAGE`). You grant it in system settings. The app asks for it from
its storage panel and explains why. The app uses it to read, write, rename and delete inside
`Documents/OpenCode` and inside the folder you pick. It does not scan or read anything else on
your storage, and nothing leaves your phone because of it.

If you decline it, projects stay in the app's own `Android/data/<app>/files/workspaces` folder.
That works, but on Android 11 and newer file managers can't open that folder, and the app says so
on the screen where it shows the path. The in-app file browser and **Export a copy...** read
from wherever your projects are.

| Data | Why | Where |
|---|---|---|
| Project folders and their files | the agent works in a folder you create or import | `Documents/OpenCode/` on shared storage (readable by file managers and a connected computer, by design), or the app's own `Android/data` folder if you declined All files access |
| Conversations (messages, tool calls, results) | your history and session continuity | app-private storage |
| Agent instruction files (project and global "memory") | the agent reads them into every conversation | app-private storage |
| Settings (theme, permission policy, chosen model, MCP configuration) | to keep your choices | app-private storage |
| Provider API keys | to call the model provider you chose | encrypted files (see below) |
| Runtime logs (`runtime.log`, server log) | so a failure can be diagnosed on your phone | app-private storage |

**Uninstalling the app** deletes its app-private storage: conversations, settings, logs, keys
and the runtime. Your project folders are different. They live in `Documents/OpenCode`, which is
yours, so uninstalling does **not** delete them. Open that folder with any file manager to see
them. If your projects are in the app's `Android/data` folder (because All files access was
declined), Android removes that folder when the app is uninstalled. Use **Export a copy...** first
for anything you want to keep.

Android backups are disabled for the app, so the system does not copy this data to a cloud
backup. **Exporting is always your choice.** When you export a project, the app hands the data
to the Android share or save sheet, and you pick the destination.

## Provider API keys

* A key you enter is encrypted with an **AES-256-GCM key held in the Android Keystore**. The key
  material is generated inside the Keystore and cannot be read back out of it. Depending on
  your phone, it is hardware-backed (TEE or StrongBox) or software-backed. The app reports which
  one your phone gave: **Settings → Keys** shows `Hardware-backed: true` or `false` for the key in use.
  The encrypted data is written to app-private storage, with no plain-text fallback.
* The key is also given to the agent's own credential store, so the agent can authenticate to
  that provider. That store is in the same app-private storage.
* Keys are never shown again after you enter them, never written to a log, and left out of
  the diagnostics the app can generate.
* Removing a key in Settings deletes the stored copy.

## What leaves your phone, and when

| Destination | What is sent | When |
|---|---|---|
| The model provider you set up (for example OpenRouter, Anthropic or Google) | your prompts, the conversation context the agent sends, file contents and tool output the agent decides are relevant, and your API key | every turn you send |
| Web pages and addresses the agent fetches | the request itself, from your phone | only when the agent uses a fetch or search tool and your permission settings allow it |
| Pictures at web addresses in a reply | a plain request for the picture file: no cookies, no credentials and no app headers. The picture's host sees your phone's network address | when a reply contains a picture link. Only `https` addresses are used, and the phone downloads the picture in the background |
| The npm registry | a package install request | only if you add a *new* plugin that is not already included. The plugin OpenCode needs is pre-installed |
| Local servers you add (MCP) | depends on that server. A local MCP server runs on your phone. Remote HTTP and SSE MCP servers are not supported by this runtime | when you set one up and connect it |

The agent's own server listens only on **127.0.0.1** (your phone's own loopback address). It
is not reachable from your network or the internet. The app's network security settings allow
unencrypted traffic only to loopback.

**The model provider is a third party.** What it does with the prompts it receives is governed
by its own privacy policy and terms, not by this one. If you would rather nothing leaves your
phone, don't send a message. The agent runs locally, but generating a reply requires a model
service.

## Permissions the app requests

| Permission | Why it is needed |
|---|---|
| `INTERNET` | traffic to the app's own agent server on your phone, and the provider and tool traffic above. Android requires this permission even for loopback connections. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | the agent is a long-running process on your phone (turns, shell commands, MCP servers). Without a foreground service, Android could stop it in the middle of a task. A low-priority notification you can dismiss shows that it is running. |
| `POST_NOTIFICATIONS` | that notification. The app asks once, and it works if you decline. |

Apart from All files access (described above), the app requests no storage, camera,
microphone, location or contacts permission. Importing or exporting a project uses the system
file picker, which gives access only to the folder you pick.

## Children

The app is a developer tool. It is not directed at children, and it collects no personal data
from anyone. It has no accounts and no user profiles.

## Diagnostics you choose to copy

Settings → Diagnostics → *Copy diagnostics* produces a text report: the agent's state, versions,
device facts and log excerpts. It is generated **on your phone**, and it goes to the clipboard
only when you copy it. The app never uploads it. The report leaves out provider API keys and the
Keystore-encrypted data. A log excerpt can still contain file paths and tool output from your
sessions, so read it before you post it anywhere public. The issue tracker is public.

## Crash reporting

There is none. No crash-reporting SDK is included, and the developer receives no crash data
automatically. When the agent process fails, the app writes the reason to its local log and to
the notification. You can share that log yourself.

## Changes to this policy

This policy is kept alongside the app's source code. When a change affects what the app does
with your data, the code change and the update to this policy are made together, and the
effective date above is updated.

## Who is responsible

The app is published by its individual developer, who does not operate any server that receives
data from this app. The agent is the open-source OpenCode project (`anomalyco/opencode`, MIT
licensed). This app is an independent client of OpenCode. It is not affiliated with or endorsed by
that project. Settings → About explains this.
