# Privacy

OpenCode for Android runs the OpenCode coding agent on your phone. This page is a short
summary. The full policy, written from the app's code, is in
[docs/PRIVACY-POLICY.md](docs/PRIVACY-POLICY.md).

## The short version

- **No account, no analytics, no advertising, no crash reporting.** The developer receives
  no data about you or your use of the app.
- **The agent runs on your phone.** Its server listens only on the phone's own loopback
  address (`127.0.0.1`), so other devices can't reach it.
- **The network traffic is limited to:**
  - the model provider you configure, which gets your prompts, the context the agent sends,
    and your API key;
  - web pages the agent fetches, when you allow it;
  - pictures at web addresses that a reply contains. Only `https` addresses are used. The
    phone downloads the picture in the background with a plain request, with no cookies,
    no credentials and no app headers. The picture's host sees your phone's network address;
  - GitHub, if you connect it and use a GitHub project.
- **Your files stay on your phone.** Project folders are in `Documents/OpenCode` on shared
  storage, so file managers can open them. Conversations, settings and logs are in the app's
  private storage.
- **Keys are encrypted.** Provider API keys and your GitHub token are encrypted with a key
  held in the Android Keystore. Keys are never shown again, and they are left out of the
  diagnostics you copy.
- **Diagnostics go only where you put them.** Settings → Diagnostics → *Copy diagnostics*
  copies a text report to the clipboard. The report can contain file paths and tool output, so
  read it before you post it anywhere public.
- **Backups are off.** The app's data isn't copied by Android's cloud backup.

## Permissions

The app asks for internet access, a foreground service (so the agent keeps running during a
task), and notifications. Shared-storage access ("All files access") is requested only when
you want project folders visible in file managers. The app doesn't ask for camera,
microphone, location or contacts.

## Model providers and GitHub are third parties

What a model provider or GitHub does with the data it receives is governed by its own
privacy policy and terms.

## Questions

Ask in this repository's issue tracker. For security problems, see [SECURITY.md](SECURITY.md).
