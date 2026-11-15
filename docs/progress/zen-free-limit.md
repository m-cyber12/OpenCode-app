# Zen free models: 429 "Free usage limit reached" (investigation)

Status: NOT FIXED. No code change is justified by the evidence so far.

## What the owner sees

- Ubuntu proot CLI: `opencode --version` → `v2.2.5`. Earlier `opencode run -m opencode/big-pickle` → `OK`, exit 0.
- App (v2.2.5 bundle on the phone): 429 and "Free usage limit reached" on `opencode/big-pickle`.

## What the code says (upstream v2.2.5, `anomalyco/opencode@v2.2.5`)

Request identity, CLI and app compared:

| Item | Official CLI | App (Node bundle) | Source |
|---|---|---|---|
| URL | `https://opencode.ai/inference/systemone/v1` | same | `ai/src/providers/opencode.ts` |
| Bearer | `public` (no key, no Console login) | `public` | `core/src/plugin/provider/opencode.ts` ~302–307 |
| `User-Agent` | `opencode/<channel>/<version>/<name>` | `opencode/latest/2.2.5/cli` (`OPENCODE_CLIENT=cli`, `OPENCODE_VERSION=2.2.5`, `OPENCODE_CHANNEL=latest` baked at build) | `core/src/session/model-request.ts` ~291–303; `app/.../runtime/RuntimeEnv.kt` 131; `spike/opencode-v2/build-v2-bundle.sh` ~61–64 |
| `x-opencode-client` | `app.name` (`cli`) | `cli` | `core/src/app.ts`; `cli/src/server-process.ts` ~103 |
| Extra headers | `x-opencode-session-id`, `x-opencode-project`, `x-opencode-session` | same kind (per-session values) | `core/src/session/model-request.ts` |

Error mapping (`core/src/session/to-session-error.ts`): a 429 whose body contains `FreeUsageLimitError` becomes `provider.free-limit` with the text "Free usage limit reached. Connect a provider or subscribe to OpenCode Go...". `ai/src/provider-error.ts` says Zen reports account caps as typed 429/402 errors, not throttles.

## What this means

- The app and the CLI send the same request shape, the same bearer, and (after the 11-05 build fix) the same User-Agent. The 426 "version too old" failure from device run 4 is not the current error.
- The current error is a Zen quota decision, not a client rejection. Zen identifies anonymous free use by values the client cannot change, so the client cannot clear it.
- The remaining client difference is the HTTP stack (Node 26 in the app, the Bun-compiled CLI). Whether Zen's quota depends on that is unknown.
- We have no evidence that a client change would help. Any change that imitates another client to get around a free quota is out of scope.

## Decisive test (owner, no code change)

Run both within the same minute, same prompt, and note the UTC time of each:

1. Ubuntu proot: `date -u; opencode run -m opencode/big-pickle "Reply with exactly: OK"; echo "exit=$?"`
2. App: one message to `opencode/big-pickle`, "Reply with exactly: OK". Note the time and paste the exact error text.

Reading the results:
- Both OK: the block has lifted. Pace requests one at a time.
- CLI OK, app 429: something on the app side differs. The next step is a test build that logs outgoing request headers (no secrets). Needs owner approval.
- Both 429: the quota is shared at this network or identity. Wait for the reset (community reports say 00:00 UTC; see opencode issue #58900), or use another route.

Result (owner, 2026-11-14 13:01:30 UTC): the CLI also returns `Error: Free usage limit reached...` with exit 1. This is the "both 429" case. Changing the phone's date does not affect the server-side quota; restore automatic date and time. The earlier CLI success is not evidence about the app. Upstream issue #58900 reports `Retry-After` to 00:00 UTC, but #58789 reports blocks that continue after the reset. Account-level checks go to help@anoma.ly per the upstream bot's reply on #58900. Long sessions may also count more (#58751).

## Options that need no paid key

- Wait for the free quota reset, then retry once.
- OpenRouter free models with the OpenRouter key the owner already holds. Free `:free` models do not charge, but they have their own daily limits. The v2 server reads `OPENROUTER_API_KEY` (`ai/src/providers/openrouter.ts`). The app path is not verified; it needs a check before it is offered as working.
- Keep Zen parked and test other v2 features with the remaining routes.

## Sources

- `core/src/plugin/provider/opencode.ts` (v2.2.5): free path, `OPENCODE_DISABLE_FREE_MODELS`.
- `core/src/session/model-request.ts` (v2.2.5): request headers.
- `core/src/session/to-session-error.ts` and `ai/src/provider-error.ts` (v2.2.5): FreeUsageLimitError mapping.
- `cli/vite.node.config.ts` (v2.2.5): `OPENCODE_ARTIFACT=cli-node`; the app overrides the name with `OPENCODE_CLIENT=cli`.
- GitHub issue anomalyco/opencode#58900 (429, reset to 00:00 UTC; community report, unverified).

## Result 2 (owner screenshot, phone 8:12 local, about 13:12 UTC if US time)

- The OpenCode TUI answered `ok` with `MiniMax M3.1 Flash Free` (4.6 s). The status bar shows a VPN icon.
- The bundled v2.2.5 model list has `minimax-m3.1-flash-free` (name "MiniMax M3.1 Flash Free", marked deprecated in the snapshot, but served now).
- `big-pickle` (the app's default free model, first in `PREFERRED_FREE`) failed on the CLI at 13:01 UTC. This suggests a per-model quota, not only per-network.
- The app is not failing at HTTP. Its error is Zen's own `FreeUsageLimitError` body. Transport failures map to `provider.transport` (`core/src/session/to-session-error.ts`), so the app's outbound path reaches Zen.
- Next test: select `minimax-m3.1-flash-free` in the app and send "Reply with exactly: OK". Record the result, and whether the VPN was on.

## Result 3 (owner screenshots, app, MiniMax selected, 8:20 local)

- The app's model is `opencode/minimax-m3...` and the reply is `provider.free-limit ... (status 429)`, a few seconds after send.
- The first screenshot shows an earlier completed reply in the same session (a "Cost $NaN · 4.68 seconds" line). If that reply was answered by Zen, the app reached Zen earlier. Owner to confirm.
- The `FreeUsageLimitError` text is produced only from an HTTP 429 response body (`ai/src/provider-error.ts`, `core/src/session/to-session-error.ts`). A fast reply is consistent with a gateway quota check, which rejects before inference.
- Upstream issue #57540 (user reports, not official docs): the free bucket is per IP or per API key on a daily cycle, shared across free models, and each request counts. This would explain CLI and app failing together at different times.
- Proof still needed: CLI log lines around 13:12–13:25 UTC (`/root/.local/share/opencode/log/opencode.log`), and an app-side request log (not available yet).

## Result 4 (owner writeup: "TLS fingerprint" diagnosis and Bun TLS proxy proposal)

- Proposal: route Zen requests from the v2 engine through a loopback proxy running under Bun, so Zen sees Bun's TLS fingerprint.
- Decision: not built. The proxy's purpose is to make Zen treat the app as the official client to get past a free-tier quota, which is working around the provider's access control.
- The diagnosis does not match the evidence: the Bun CLI itself returned `FreeUsageLimitError` at 13:01 UTC (exit 1). The error is the quota path, not a client rejection.
- Honest test, after the quota resets: same prompt, Bun CLI and a Node-based client, same minute. If only Node gets 429 with the quota message, report it upstream with the timestamps.

## Result 5 (owner, 2026-11-15 12:14 UTC)

- Ubuntu proot Bun CLI: `opencode run -m opencode/minimax-m3.1-flash-free "Reply with exactly: OK"` returned `OK`, exit 0. The quota that blocked the CLI at 13:01 UTC on 11-14 has lifted for the CLI.
- App: failed (owner report, exact text and send time not yet given).
- Proot `node -v` is v26.4.0, but `/root/.opencode/bin/opencode` is an ELF binary (Bun-compiled), so it is not a Node-based client. The Node comparison needs a Node-run opencode (Termux, if it runs the npm JS bundle).
- Next: one app message to MiniMax with the send time; Termux runtime check; one Termux run in the same minute as the app message.

## Decision 6 (owner: port the v2 engine to Bun so the app matches the official CLI stack)

- Not done. The aim is still to get past Zen's free-tier gate by matching the official client's stack, the same aim as the rejected proxy.
- Also conflicts with the owner-approved migration plan (remove Bun from the APK; agent on Node 26 + v2).
- Pending: the app result in the same minute as a working Bun CLI run, and the Termux runtime check.

## Decision 7 (owner, 2026-11-15): Zen "coming soon" in the app

- `ZenPolicy.COMING_SOON = true` (`app/.../client/ZenPolicy.kt`). Zen models are removed from the chat quick switch, the Models card and the default-model hint. Starred Zen models are filtered out.
- Zen stays in Settings → Providers with a gray "Coming soon" tag, one sentence on why, and no connect button.
- `OpenCodeApi.promptAsync` refuses a Zen model or a missing model before any request, so no turn reaches the quota gate by accident. The error appears in the chat.
- Set `COMING_SOON = false` to bring Zen back everywhere.
- Not device-tested. Unit tests updated (`DefaultModelHintTest`, `ModelPreferenceTest`, `OpenCodeApiHttpTest` new refusal test); not compiled here, CI is the compile check.
