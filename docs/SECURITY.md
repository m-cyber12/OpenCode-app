# Security

## Model

The threat model is a normal, non-rooted Android phone running untrusted apps
next to this one. The assets are: the user's model-provider keys, the project
files, and the loopback server that can run a shell in the app's sandbox.

| Control | Implementation | Verified (gate) |
|---|---|---|
| Everything app-private | runtime, workspaces, XDG dirs, logs, secrets under `/data/data/io.github.mcyber12.opencode/files` (mode 0700; no external storage, no `MANAGE_EXTERNAL_STORAGE`) | P5-G18, P7-W2, device `files-layout.txt` (Phase 8 §4 #1) |
| Loopback-only server | `opencode serve --hostname 127.0.0.1 --port 4111`; `LoopbackGuard` refuses any non-loopback base URL in the client; `LoopbackAudit` records `/proc/net/tcp` binds at start | P5-G17 (proc-net audit + external-interface refusal), device `SERVER_BOUND url=http://127.0.0.1:4111/` |
| Server authentication | HTTP Basic, user `opencode`, per-install random password generated on first run; every app request and every health poll carries it; a wrong password gets 401 (JVM `HealthCheckerTest`) | P5-04, P5-05 |
| Secrets at rest | `SecretStore`: AES-256-GCM, key = non-exportable `AndroidKeyStore` AES key (`OCS2` blob format, per-secret random IV) in `files/secrets/<name>.enc`; holds the server password and provider keys the user enters; the pre-Phase-5 plaintext password file is migrated and deleted | P5-G18 (blobs, no plaintext, `auth.json` 0600), P8-CLEANUP (revoked key provably gone from Keystore + server) |
| Provider keys in the server | forwarded to upstream via `PUT /auth/:id` (upstream writes `xdg/data/opencode/auth.json`, 0600) - the same mechanism the desktop app uses; revoke = `DELETE /auth/:id` + blob deletion + instance dispose | P8-CLEANUP PASS (CI + device) |
| No hardcoded secrets | source/APK/payload scan gate; the CI key travels base64-over-stdin, never in argv or logs | P5-G19 every run |
| Payload integrity | manifest sha256 per file + tarball; extraction refused on mismatch; tar reader rejects traversal, absolute paths, truncation | JVM `PayloadExtractionValidationTest`; P8-CORRUPT PASS |
| No root, no special permissions | needs `INTERNET`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS` (13+); SAF for import/export | stock phone runs (Phase 8 device suite) |
| Permissions for agent actions | upstream's permission requests surface as a sheet; standing policy (ask / allow-for-session) stored per project; nothing auto-approved by default | P6-U3, P7 permission gates |
| Workspace boundaries | one OpenCode instance per workspace directory (`?directory=`); tools operate relative to it | P7-W2 |
| Log hygiene | supervisor/crash logs redact env; the Phase 8 harness redacts provider error URLs (a Google error message embeds the key in the URL - see incident below) | Phase 8 §3.1.8 |

## Known limits (permanent or unverified)

- **Keystore master key is software-backed** on every tested device
  (`insideSecureHardware=false`, StrongBox unavailable): CI emulator *and* the
  real Realme RMX3830 (P8-KEYRESIDENCY, measured). The key is still
  non-exportable through the Keystore API, but resistance is software-keystore
  level, not TEE/StrongBox level. The app requests hardware backing and
  reports what it got; it does not refuse to run without it.
- The shell tool runs as the app UID with the app's network access. Anything
  the model is allowed to execute (after the permission prompt) can read the
  app's own files, including `auth.json` - identical to desktop OpenCode's
  model, contained here by the app sandbox.
- Basic auth on loopback protects against other apps on the device only as far
  as the password stays private; it is stored encrypted and never logged, and
  the test harness's export of it is gated behind a debug-only marker file.
- Backups: `android:allowBackup` is off; nothing of the runtime is exported.

## Incidents and clean-up (Phase 8 -> 9)

1. **Leaked Gemini API key** - commit `52e7c4d` added `p8d-out/key-preflight.txt`
   containing a verbatim Bun connection error whose URL carried
   `?key=<live Gemini key>`. The working tree is redacted and the harness now
   redacts all captured errors, but the key remains in Git history and **must
   be treated as compromised. Revocation at <https://aistudio.google.com/apikey>
   is the required action and is the repository owner's to perform**; no
   history rewrite is needed once it is revoked. Status: **revoked by the repository owner on 2026-09-16** (owner's
   statement; not verifiable from the repository).
2. **`OPENROUTER_API_KEY` repository secret** (Phase 8 temporary key for
   model-dependent CI gates). Nothing in the pipeline requires it - without it
   the model gates SKIP with the reason on record. The automation account
   cannot manage repository secrets (403); **removed by the repository owner on 2026-09-16**. If a future run needs a live model, add a fresh short-lived key
   for that run and remove it afterwards.
3. Every phase suite removes injected credentials from the device at its end
   (P8-CLEANUP) and the Phase 9 provider gate removes its dummy key
   (P9_PROVSEL_CLEANUP).

## Reporting

Security issues in the Android shell: open an issue in this repository.
Issues in the agent/server itself belong upstream (`anomalyco/opencode`); the
bundle here is the unmodified pinned commit.
