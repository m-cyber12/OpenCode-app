# Security policy

## Reporting a vulnerability

You can report a security problem in the Android app in either of two ways:

- **Email (private):** send it to **noxifera.dev@gmail.com**. Use this for anything that
  could put users at risk before a fix is out.
- **Issue:** open an issue on this repository.

Include:

- what the problem is and what an attacker could do with it;
- the steps to reproduce it;
- the app version (**Settings → About**), your phone model and your Android version.

Don't send API keys or GitHub tokens. If a report needs one, replace it with a placeholder.

Problems in the OpenCode agent itself belong to the upstream project,
[anomalyco/opencode](https://github.com/anomalyco/opencode). This repository publishes the
Android app's documentation and a small set of security-relevant excerpts; the app's source
code and its build recipe are not published.

## What the app protects

The full security model, with the controls, the tests that check them and the known limits,
is in [docs/SECURITY.md](docs/SECURITY.md). In short:

- The agent's server listens only on the phone's loopback address, and it requires a random
  password that is created on the first run.
- Provider keys and the GitHub token are encrypted with a non-exportable key in the Android
  Keystore. On the phones tested so far, that key is software-backed.
- The agent's shell runs with the app's own permissions. Anything it runs can read the app's
  own files. Actions that need your approval show a permission sheet first.
- "All files access" lets the app write to shared storage. The app uses it only for the
  project folder and a folder you choose, but a command the agent runs could reach other
  shared-storage folders. Grant it only if you accept that.
