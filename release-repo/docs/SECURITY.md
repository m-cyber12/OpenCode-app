# Security

This page explains how OpenCode for Android protects your keys, your files and the agent. It
also lists what the app cannot protect against.

## Who the app protects you from

The app assumes a normal, non-rooted Android phone with other apps installed. What matters most is:

* your model-provider API keys and GitHub token,
* your project files,
* the agent's local server, which can run commands inside the app.

## What the app protects

| Protection | How it works |
|---|---|
| **Private app files** | The runtime, settings, conversations, logs and secrets are in the app's private storage. Other apps cannot read them. |
| **Project files you can see** | Projects are ordinary folders in `Documents/OpenCode` by default, so any file manager can open them. Writing there needs the **All files access** permission on Android 11 and newer. You grant it, and the app works without it, in a folder that `adb` can still see. |
| **Local server only on your phone** | The agent's server listens only on `127.0.0.1`. Other devices on your network cannot reach it. |
| **Server password** | Every request to the server needs a password. It is random, created on first run, and stored encrypted. A wrong password is refused. |
| **Encrypted secrets** | Provider keys, the GitHub token and the server password are encrypted with AES-256-GCM. The key is held in the Android Keystore and cannot be exported. The old plain-text password file is migrated and deleted. |
| **Removing a key** | Deleting a key in the app also deletes it from the agent and removes the encrypted copy. |
| **Logs** | Logs redact environment variables. |
| **Runtime integrity** | Every runtime file has a checksum. The app refuses a file that does not match, and it rejects archives that try to write outside their folder or use absolute paths. |
| **No root, and only disclosed special access** | The app needs the internet, a foreground service, notifications and, only if you grant it, All files access. It does not use root, storage permissions for general file access, or location. |
| **Approvals for agent actions** | When the agent wants to do something that needs approval, a sheet asks you to allow it once, allow it for the session, or reject it. Nothing is approved automatically by default. Your permission settings are kept per project. |
| **Config files stay protected** | An "allow for session" approval never covers changes to `opencode.json`, `.mcp.json` or `.opencode/`, nor shell or external-folder requests that name them. Each such request asks you again. |
| **Preview tap and type** | The agent can tap, type and scroll only inside the preview page that is open. It has no shell and no file access there. The first batch of each preview session asks you to Allow or Deny. Each run is limited to 20 steps and 5 seconds per wait. |
| **Background tasks** | A background task has a time limit of 1 to 60 minutes and uses the same approval rules as chat. It runs only while the app is open. |
| **Separate workspaces** | Each project has its own agent instance, and its tools work relative to that project. |
| **Backups off** | Android backups are disabled, so the app's data is not copied to a cloud backup. |

## The runtime

* The agent is OpenCode v2.2.5, running on Node.js 26.4.0 on your phone. Bun is not included in
  the app or on your phone. It is used only when the app is built.
* The app ships for 64-bit ARM (arm64-v8a) phones only. Other processor types are refused at start.
* Some of the runtime's hardening checks are still being verified on real phones.

## Known limits

* **Key storage on the phones tested so far is software-backed.** The Keystore key cannot be
  exported, but its protection is at software-keystore level, not hardware level (TEE or
  StrongBox). The app asks for hardware backing and tells you what it got. It does not refuse
  to run without it.
* **The agent's shell runs with the app's own permissions.** Anything the model is allowed to
  run (after your approval) can read the app's own files, including the saved provider keys. The
  app's sandbox contains this, but it is not a hard wall.
* **The server password protects only while it stays private.** It is handed to the server on
  standard input, not through the environment, and the server hides it from every process it
  starts. The shell can still read the app's own files, which include the provider key file.
* **The config-file guard is a text check.** It looks at shell commands as text, so an unusual
  command could get past it.
* **Background tasks have a time limit but no token or cost budget.** They use the permissions you
  have allowed for the session. There is no separate list of allowed actions for unattended work.
* **Watch and tap acts on the page you are looking at.** It can press any button on that page,
  including a payment or delete button. Your Allow is the only check, and the app does not check what a
  button does.
* **All files access is wider than the project folder.** The permission lets the app write
  anywhere on shared storage, not only in your project folder. The app limits itself: agent
  tools run in the project folder, the OpenCode file guard refuses paths that escape it, and
  actions outside the project go through the approval sheet. Android cannot stop a shell command
  that is pointed at `/storage/emulated/0`. Grant All files access only if you accept this.

## Reporting a security problem

Report security problems in the app by email to **noxifera.dev@gmail.com** (private), or by opening an
issue on this repository. Use email for anything that could put users at risk before a fix is out.
Do not send API keys or GitHub tokens. Problems in the OpenCode agent itself belong to the
upstream project, [anomalyco/opencode](https://github.com/anomalyco/opencode). The short
policy is in [SECURITY.md](../SECURITY.md).
