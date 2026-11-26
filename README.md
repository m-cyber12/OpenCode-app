# OpenCode for Android

OpenCode for Android runs the [OpenCode](https://github.com/anomalyco/opencode) coding
agent on your phone. The agent, its shell, its files and its Git all run inside the app,
on your device. You don't need a computer or a server, and you don't need to install Node,
Bun or Termux.

> **Status: beta.** This is a pre-release build. Core features work, but some things are
> still rough or missing. The [known limitations](#known-limitations) section lists them.

OpenCode for Android is an independent project. It is not affiliated with or endorsed by the
OpenCode project.

## What you need

- An Android phone with **64-bit ARM** processor (arm64-v8a). Most phones from the last
  several years qualify.
- **Android 10 (API 29) or newer.**
- Free storage space for the app and the agent's files. The app is large because it
  includes the agent's runtime.
- **An API key from a model provider** (for example Anthropic, OpenAI, OpenRouter or Google),
  or a provider you run yourself. The agent needs a model to think with.

## Install

1. Download the latest APK from the project's **Releases** page on GitHub.
2. Open the file on your phone. If Android asks, allow installs from this source for your
   browser or file manager.
3. Open **OpenCode**.

Updates install over the old version, and your data is kept, as long as each release is
signed with the same key. Release notes list the signing key's fingerprint so you can check it.

## Get started

1. **Add a provider.** Open **Settings → Providers**, pick a provider, and enter your API key.
   A provider shows **Ready** once the agent can use at least one of its models.
2. **Create a project.** Each project is its own folder with its own agent session. Projects
   are stored in `Documents/OpenCode/` on shared storage. You can open them from any file
   manager, over USB, or with `adb`.
3. **Start a session.** Type a task in the chat. The agent reads and edits files, runs commands
   and reports back. Pick a model from the model menu in the header.

## Features

**Chat**
- Streaming replies, with Markdown and syntax-highlighted code.
- Comparisons appear as real tables, with rows and columns.
- Tool cards show each step: shell commands with their output and exit code, file reads and
  edits, searches, and file diffs.
- While the agent writes a file or runs a command, its output appears as it arrives.
- A task bar above the message box shows the agent's checklist for the current task.
- If the model service reports a temporary error in the middle of a reply, the reply keeps
  going. The app shows the turn as running until the agent has finished.
- Press and hold a message you sent for one second to copy its text.
- Tap **Pause** before the model has received your message to take it back. The message is
  removed from the chat, and its text returns to the box.
- For models that offer it, choose the thinking level from the model menu.
- Copy a reply, mark it good or bad, retry, undo or redo the latest turn.
- Each reply shows its token count and cost.

**Planning and questions**
- Switch between **Build** mode (the agent makes changes) and **Plan** mode (it proposes a
  plan first).
- When the agent has a question, it appears inline in the chat. Answer one step at a time.
- Approve a plan in the chat to switch to Build and start the work.
- When the agent wants to run a sensitive command, a permission sheet asks you to allow it
  once, always, or reject it.

**Projects and files**
- Several projects, each isolated in its own directory.
- A **Sandbox** tab shows the project's files. If the agent starts a web server on the phone,
  the tab lists its port, and you can tap it to open the page in the app. The server listens
  only on the phone's own loopback address (`127.0.0.1`).
- A **Diff** tab shows the changes the agent has made.
- Import a copy of a folder, or export a whole project.
- Long sessions are summarized automatically, so you can keep working past the model's
  context limit.

**Agent instructions, skills and tools**
- **Agent memory** (Settings): project instructions apply to one project; global instructions
  apply to every project. The agent reads both in every conversation.
- **Skills** (Settings): a skill is a folder with a `SKILL.md` file. The agent sees each skill's
  short description and loads the full instructions only when it needs them.
- **MCP servers** (Settings): local (stdio) servers work. Remote HTTP and SSE servers do not
  (see Known limitations).

**Terminal**
- The **Terminal** tab is a read-only console. It shows every shell command the agent ran in
  the current conversation, with its output and exit code.
- You can't type commands into it. To run commands, ask the agent.

**Toolchain inside the app**

The agent's tools are built into the app. You don't install them.

- **Node.js 26.4.0 with npm 11.17.0.** The agent's server runs on Node, and npm is included.
- **Git 2.48.1.** Version control for your projects: status, diff, log and commit.
- **ripgrep 15.1.0.** Fast search through project files.
- **Python 3.13.1.**
- **jq 1.7.1.** Reads and edits JSON.
- **curl and wget.** Download files and call web APIs.
- **OpenSSH client.** `ssh`, `scp`, `sftp`, `ssh-keygen`, `ssh-keyscan`, `ssh-add` and `ssh-agent`.
  The app does not run an SSH server.
- **OpenSSL.** Shared cryptography for the tools above.
- **toybox** and the **mksh** shell, which is the phone's own `/system/bin/sh`. These provide
  the everyday command-line tools.

**GitHub projects**
- Create a project from one of your GitHub repositories. The app clones it and puts you on its
  own branch, `opencode/<project>`.
- After the first clone, the app pushes that branch to GitHub so it exists on the remote.
  The agent works only on that branch, never on the default branch.
- Before every message you send, the app refreshes the repository, so the agent always starts
  from the latest code.
- An empty repository gets a starter README first, so it can be cloned.
- To connect, create a GitHub personal access token with the `repo` and `workflow` scopes, and
  paste it into the app.
- Your token is only sent to GitHub projects. Other projects can't see it.

**Welcome and beta notice**
- The first welcome page says that this version is a beta, and that you should not use it for
  important projects.

## Providers and keys

- Keys are saved in **Settings → Providers**. You can save several keys for one provider.
  If one key hits a usage limit, the app can fail over to the next key. You can also choose
  a key yourself.
- Keys are encrypted with an AES-256 key held in the Android Keystore. The key cannot be exported.
  On the devices tested so far the Keystore key is software-backed. See
  [docs/SECURITY.md](docs/SECURITY.md) for the details.
- To remove a key, open the provider and delete it. The provider changes to **Not Ready** once
  it has no working key.
- To use your own OpenAI-compatible endpoint, use **Add a custom provider** in Settings.

**Free Zen models are not offered in this beta.** The OpenCode project's free-model service
currently returns usage-limit errors for this app. The app hides those models until the
service is working again.

## Privacy and security

- The app has no account, no analytics and no advertising.
- Your code, files and chat stay on your phone. The network traffic goes to the model
  provider you configured, to GitHub if you connect it.
- The agent's server listens only on the phone's loopback address (`127.0.0.1`). It is
  protected by a random password that is created on first run and stored encrypted.
- Full details are in [PRIVACY.md](PRIVACY.md) (a summary), [docs/PRIVACY-POLICY.md](docs/PRIVACY-POLICY.md),
  [SECURITY.md](SECURITY.md) and [docs/SECURITY.md](docs/SECURITY.md).
- To report a security problem, email **noxifera.dev@gmail.com** (private), or open an issue.

## Known limitations

- **No interactive terminal.** The Terminal tab is read-only, and full-screen or interactive
  programs can't run on the phone. The agent's shell commands work normally.
- **Git over HTTPS only.** SSH remotes are not supported.
- **Remote MCP servers over HTTP or SSE** can fail because of an upstream bug.
  Local (stdio) MCP servers work.
- **Some Android versions are less tested.** The file-staging path is verified on Android 14 and 15
  only. Please report problems on other versions.
- **64-bit ARM only.** 32-bit phones and x86 emulators aren't supported.

The full capability table, with what is tested and what is not, is in
[docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md).

## Reporting problems

Open an issue on this repository. Include your phone model, Android version, the app
version (in **Settings → About**), and the steps that caused the problem. Describe what you
expected to happen. Don't include API keys or GitHub tokens in an issue.

## Documentation

| Document | What it covers |
|---|---|
| [docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md) | What works on Android, what doesn't, and what has been tested |
| [PRIVACY.md](PRIVACY.md) | Summary: what the app does with your data |
| [SECURITY.md](SECURITY.md) | How to report a security problem |
| [docs/SECURITY.md](docs/SECURITY.md) | How keys and tokens are stored, and the threat model |
| [docs/PRIVACY-POLICY.md](docs/PRIVACY-POLICY.md) | What the app does with your data |
| [docs/RUNTIME.md](docs/RUNTIME.md) | How the embedded runtime works, and troubleshooting |
| [docs/THIRD-PARTY-NOTICES.md](docs/THIRD-PARTY-NOTICES.md) | Open-source components and their licenses |

## Building from source

Developers can build the app themselves. The steps are in
[docs/TESTING.md](docs/TESTING.md), and signing for releases is in
[docs/RELEASE.md](docs/RELEASE.md).

```
bash phase4/scripts/10-build-payload.sh   # embedded runtime (Linux, needs NDK and Node 26)
./gradlew :app:assembleDebug              # debug APK
```

## License

The OpenCode agent is MIT-licensed by its authors. This app's license and the licenses of
bundled components are listed in [docs/THIRD-PARTY-NOTICES.md](docs/THIRD-PARTY-NOTICES.md).
