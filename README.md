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
- Tool cards show each step: shell commands with their output and exit code, file edits with
  diffs, and more.
- Copy, like or dislike a reply. Retry, undo or redo the latest turn.
- Per-turn time, token and cost summary.

**Planning and questions**
- Switch between **Build** mode (the agent makes changes) and **Plan** mode (it proposes a
  plan first).
- When the agent has a question, it appears inline in the chat. Answer one step at a time.
- Approve a plan in the chat to switch to Build and start the work.
- When the agent wants to run a sensitive command, a permission sheet asks you to allow it
  once, always, or reject it.

**Projects and files**
- Several projects, each isolated in its own directory.
- A file browser, a Changes view, and a terminal tab.
- Import and export whole projects.
- Long sessions are summarized automatically, so you can keep working past the model's
  context limit.

**GitHub projects**
- Create a project from one of your GitHub repositories. The app clones it onto its own branch
  (`opencode/<project>`).
- To connect, create a GitHub personal access token with the `repo` and `workflow` scopes, and
  paste it into the app.
- The agent can commit and push to that branch. The app never pushes on its own.
- Your token is only sent to GitHub projects. Other projects can't see it.

**Web preview**
- A local preview server shows your web project in the app, the way a deployed site would look.
- It listens only on the phone's own loopback address. Other devices on your network can't
  reach it.

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
- Your code, files and chat stay on your phone. The only network traffic goes to the model
  provider you configured, and to GitHub if you connect it.
- The agent's server listens only on the phone's loopback address (`127.0.0.1`). It is
  protected by a random password that is created on first run and stored encrypted.
- Full details are in [docs/PRIVACY-POLICY.md](docs/PRIVACY-POLICY.md) and
  [docs/SECURITY.md](docs/SECURITY.md).

## Known limitations

- **Interactive terminal.** The terminal tab doesn't run full-screen or interactive programs.
  The agent's shell commands work normally.
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
