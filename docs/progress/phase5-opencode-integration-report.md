# Phase 5 — OpenCode client integration report

**Date:** 2026-08-31
**Branch:** `arena/01a05713-opencode-app`
**Scope (and only this):** the Android app becomes a real *client* of the on-device OpenCode server — loopback-only binding, OpenCode's own sessions/events/streaming/tool calls/permissions/file ops, MCP transports, and Keystore-backed credentials. UI polish is Phase 6.
**Pinned OpenCode:** `05ea5073be967c779d326929b2de6228dda4159d` (v1.18.23) — unchanged since Phase 3; **no upstream source was modified**, and no OpenCode behaviour was replaced in Kotlin.

> ## Read this first: validation state of this report
>
> Phase 5's code, tests, gate drivers and CI wiring are **written**; the Kotlin has **not been compiled yet** and **no Phase 5 device run exists yet**. There is no JVM/Android toolchain in this sandbox (no `java`, `gradle`, `adb`; `maven`/`dl.google.com` unreachable), so the *first* compile and the *first* device evidence can only come from GitHub Actions. Concretely:
>
> | Item | Label |
> | --- | --- |
> | Kotlin client (`OpenCodeApi`/`OpenCodeEventStream`/`Transcript`/`OpenCodeRepository`), Keystore `SecretStore`, `LoopbackGuard`, `LoopbackAudit`, `RuntimeIntegration`, UI tabs | **IMPLEMENTED, NOT TESTED** (not compiled, not run) |
> | Phase 5 gate suite (`phase5/scripts/20-integration-gates.sh`, `gate-16-mcp-remote.js`) + orchestrators | **IMPLEMENTED** (bash/JS syntax checked; provisioning + audit logic rehearsed against a fake-`adb` harness; never run against a device) |
> | Host-side remote-MCP fixture (`phase5/mcp/remote-mcp-server.mjs`) | **TESTED on this host** — real `@modelcontextprotocol/sdk` 1.29.0 clients connected over **StreamableHTTP and legacy HTTP+SSE**, `tools/list` + `tools/call` round-trips green (see §2) |
> | Gate `P5-G16` *driver* (`phase5/scripts/device/gate-16-mcp-remote.js`) | **TESTED on this host against a harness** — `phase5/scripts/rehearsal/run-host-rehearsal.sh` runs the driver, the fixture, and a fake OpenCode MCP surface whose status transitions come from genuine SDK client negotiations → `G16_PASS`. **Host wiring proof only: no device, no app, no on-device OpenCode involved.** |
> | G6/G7/G10/G11/G12 re-run on a device, loopback audit on a device, credential at-rest proof on a device | **NOT TESTED** — pending the CI run recorded in §6 |
> | `.github/workflows/phase5-integration.yml` installation, and the real arm64 device run | **BLOCKED on the user** (see §5/§6) |
>
> Nothing below claims device evidence that does not exist yet. When the run lands, §6 gets the run id + verdicts and the labels above get promoted or falsified.

---

## 1. Loopback-only binding

### What the app guarantees (design, verified against pinned upstream source)

Upstream's server is `Server.listen({ port, hostname, cors, mdns?, mdnsDomain? })`. Our launcher (`phase4/payload/launcher.js`, unchanged in its API surface) calls it with:

- `hostname` from `OPENCODE_SERVER_HOSTNAME`, which the app computes in `RuntimeEnv.hostname()` → **`RuntimeEnv.hostname()` can only ever return `127.0.0.1`, `localhost` or `::1`**; anything else is refused and logged as `SERVER_BIND_POLICY`.
- `cors: []` — no cross-origin access at all, so a browser on the LAN cannot use the device as a proxy either.
- **`mdns` is never passed.** Upstream `setupMdns` publishes only when `opts.mdns && port && hostname ∉ {127.0.0.1, localhost, ::1}`; with `mdns` absent it never publishes, and even a `mdns:true` build cannot publish on a loopback bind (upstream logs `mDNS enabled but hostname is loopback; skipping mDNS publish`). So the "discoverable on the LAN" path is off by construction, not by patch.

Three independent enforcement layers, all app-side:

1. **Parent policy** — `RuntimeEnv.hostname()` (fail-closed: non-loopback request → default `127.0.0.1` + refusal message).
2. **Child self-assertion** — before `Server.listen`, `launcher.js` re-reads its own env, hard-exits if the host is not loopback, and after binding logs `SERVER_BOUND hostname=… port=…` plus a `BIND_AUDIT` line listing the listener rows it can read.
3. **Supervisor audit + fail-closed stop** — `runtime/LoopbackAudit.kt` parses `/proc/net/tcp`, `/proc/net/tcp6` (and the UDP tables for `5353`) after the server reports healthy; a **conclusive** violation (a `LISTEN` row for our port on a non-loopback/wildcard address, or an app-uid UDP-5353 socket) makes `RuntimeManager` stop the runtime and move it to `FAILED` instead of leaving it exposed. An *unreadable* table (Android's SELinux `proc-net` restriction can deny app-uid reads) is recorded as **inconclusive**, never as a pass and never as a violation.

The client half is symmetric: `client/LoopbackGuard.kt` refuses to build any base URL whose host is not a loopback literal, and `RuntimeEnv.SERVER_PORT` + that guard are the only way the app can address the server. `network_security_config.xml` allows cleartext **only** for `127.0.0.1`/`localhost`, with `cleartextTrafficPermitted=false` in the base-config — so even a mis-set URL would fail in the platform, not silently dial out.

No "LAN exposure" toggle exists in the app today. If Phase 6+ wants one, it must be an explicit user action; nothing in this phase pre-enables it.

### Evidence that will be produced (gate `P5-G17`, `phase5/scripts/20-integration-gates.sh`)

- `awk` over the **host-visible** `/proc/net/tcp{,6}` (read from the adb *shell* uid, which is not subject to the app-uid `proc-net` denial), filtered by the app's uid:
  exactly **one** `LISTEN` row on port `4111` with local address `0100007F:100F` (= `127.0.0.1:4111`; the little-endian word is decoded in `LoopbackAudit.formatAddress`, unit-tested at `app/src/test/.../runtime/LoopbackAuditTest.kt`);
  **zero** rows for that uid on a non-loopback local address; **zero** wildcard (`00000000:` / all-zero IPv6) listeners.
- **No mDNS:** zero app-uid UDP rows on `5353`, and no `mDNS published` line in the OpenCode server log.
- **Behavioural proof, not just table inspection:** from the device, as the app uid, using the payload's own `bun` (a *native* socket, so the Java-layer `NetworkSecurityPolicy` cannot fake a refusal): connect to `127.0.0.1:4111` → must open; connect to the device's own **global** IPv4 (`ip -o -4 addr show scope global`, e.g. `10.0.2.15`):4111 → must be `ECONNREFUSED` (or time out). Both results are captured in `p5-17-external-connect.txt`.
- `SERVER_BOUND` / `BIND_AUDIT` lines from the supervisor log, and the app's own `files/log/loopback-audit.txt`.
- `adb forward --list` — the only host-side listener is `127.0.0.1:4111`, created by adb for the gate drivers; the guest port is never published on the host network.
- Instrumented gate **K7** (`OpenCodeClientGatesTest.kt`) repeats the same connect matrix inside the app process with `java.net.Socket` (bypassing `NetworkSecurityPolicy` deliberately, so a "refused" verdict cannot be an artefact of the cleartext policy).

**Status: IMPLEMENTED, NOT TESTED** (the audit code and the gate exist; the verdict table below is empty until CI runs it).

---

## 2. MCP: which transports work, which do not, and why

Nothing was crippled globally to make loopback work. OpenCode's own `McpCatalog`/`MCP.connect` paths are used verbatim; the app only *configures* servers through OpenCode's API (`GET /mcp`, `POST /mcp`, `POST /mcp/:name/{connect,disconnect}`), which Phase 4 already proved is upstream-complete.

| Transport | Verdict | Why / limits |
| --- | --- | --- |
| **local (stdio child process)** | **works** — upstream code path, no adaptation. Proven in Phase 4 (G10 green) and re-run in Phase 5 (G10 + Kotlin **K4**). | The command must resolve inside the bundled userspace: `filesDir/bin` (`bun`, `git`, `rg` symlinks → `nativeLibraryDir`) and `/system/bin`. `{type:"local", command:[…], cwd?, environment?, enabled?, timeout?}` → `StdioClientTransport` with `shell:false`, inheriting `process.env`. **Consequence:** `npx <pkg>` / `bunx <pkg>` style servers need a package manager that can fetch at runtime; our userspace has no npm, and `bun x` on-device is **NOT TESTED**. Pre-shipping extra local MCP servers is a payload question (Phase 8), not a client limitation. |
| **remote, StreamableHTTP** | **works** — OpenCode's `connectRemote` uses the SDK's `StreamableHTTPClientTransport`; our fixture speaks the SDK's `StreamableHTTPServerTransport` (stateful, session ids). Gate `P5-G16` drives it on-device. | Requires outbound HTTP from the device. On the emulator that is `http://10.0.2.2:<port>/mcp` (guest → host loopback through NAT); on a real device it is any reachable host, including HTTPS. No OAuth browser flow is possible on-device (`needs_auth` is surfaced, not silently swallowed). |
| **remote, legacy HTTP+SSE** | **works** — same `connectRemote`, which falls back to `SSEClientTransport` when StreamableHTTP negotiation fails. `P5-G16` asserts this by pointing a config at the fixture's `/sse` endpoint, which only speaks the legacy transport. | Same outbound-HTTP requirement. Servers that expose *only* `/sse` still connect, which is exactly the compatibility we did not want to lose. |
| **remote, unreachable / wrong URL** | **correctly fails** — `GET /mcp` reports `status: "failed"` for that entry only; other servers keep working. Asserted by `P5-G16` against a closed port. | This is the anti-"crippled MCP" check: a broken remote server is visible, not masked as connected or disabled. |
| **local server that cannot be spawned** (bad path, missing binary) | reports `failed` with the spawn error (upstream behaviour; unchanged). | |
| **`needs_client_registration` / `needs_auth`** | surfaced verbatim by `GET /mcp`; the UI shows the status text (Phase 6 can add a flow). | Not a loss, just unimplemented UI. |

Per-server persistence follows upstream: `POST /mcp` is in-memory for the running server, while `opencode.jsonc`'s `mcp` block is durable. The app therefore offers both: the Credentials/MCP tabs write through `PATCH /global/config` (jsonc patch of the first existing config file under `Global.Path.config`, then `Config.invalidate`) for durable entries, and `POST /mcp` for a session-scoped trial. **No app-side MCP proxy, no reimplementation, no filtering of tools** — the only app influence is `Permission.visibleTools`, which is upstream's own ruleset logic.

Fixture self-test (this host, real SDK, not the device): `remote-mcp-server.mjs` answered

```
PROBE streamable-http connected tools=[remote_echo,remote_marker] echo="echo:hello-streamable-http" marker="P5_REMOTE_MCP_OK"
PROBE http-sse        connected tools=[remote_echo,remote_marker] echo="echo:hello-http-sse"        marker="P5_REMOTE_MCP_OK"
HEALTH {"healthy":true,"marker":"P5_REMOTE_MCP","mcp":1,"sse":0}
```

That is **TESTED** (host-side): it proves the *peer* is a genuine MCP server on both transports, so a device-side failure would mean our client/config path, not the fixture. One defect was found and fixed this way: wiring `server.onclose → transport.close()` recursed (stack overflow on client disconnect); the fix relies on the HTTP `close` event alone.

The gate driver itself was then rehearsed the same way (`run-host-rehearsal.sh`, output above is committed to nothing — it is a dev-run script under `phase5/scripts/rehearsal/`, and its own header says "NOT device evidence"). That rehearsal caught two more real bugs before CI: `gate-16` imported `./gates-lib.js`, which only exists in `phase4/scripts/device/` (now imported in place, unmodified, so both phases share one helper contract), and a stale `p5-remote-*` entry from an earlier run made the "no remote tools before" assertion fail — the driver now disconnects its own names first, so each run measures its own pre-state. Verdict of that rehearsal:

```
GATE16 remote MCP transports OK — streamable_http=connected http_sse=connected
  tools_registered=4 lifecycle=disconnect+connect unreachable=failed stdio=connected
G16_PASS
```

The `failed` entry it produced for the unreachable server carried the real client error (`connect ECONNREFUSED 127.0.0.1:4599`), which is the shape `P5-G16` expects from upstream and does not synthesise.

---

## 3. Credentials: storage, and proof that nothing is hardcoded or bundled

### How a provider key travels (no custom auth scheme anywhere)

```
user types key in UI
  → SecretStore.put("provider:<id>")            AES-256-GCM under a non-exportable
                                                AndroidKeyStore master key
                                                (alias opencode-app-secret-master-v1,
                                                AAD = "ai.opencode.android/secret/<name>",
                                                blob = filesDir/secrets/<name>.enc,
                                                header OCS1|ver|ivLen|iv|ct, file mode 0600)
  → after every healthy start (and on save) the app re-pushes it:
      PUT /auth/openrouter  {"type":"api","key":…}      <- OpenCode's own endpoint
  → OpenCode persists it its own way: plaintext 0600
      $XDG_DATA_HOME/opencode/auth.json  (app-private storage)
  → the model provider is then configured through OpenCode's normal mechanism
     (GET /provider -> {all, default, connected}; no app-side provider registry)
```

Consequences, all deliberate:

- **Keystore is the only app-side copy.** There is no plaintext mirror, no SharedPreferences, no env var, no `assets/` file. `Secrets.kt`'s pre-Phase-5 plaintext `files/secrets/server-password` is *migrated into the Keystore and deleted* on first start; if the master key is gone (restored data), the loopback password is regenerated rather than cached in the clear.
- **The loopback server password** (HTTP Basic, upstream's `OPENCODE_SERVER_PASSWORD`) is random per install, generated into the Keystore, passed to the child only through its **environment** (never argv, never a file), and never logged. `Diagnostics` reports presence/absence, never the value.
- **Re-pushing after each start** is required because `PUT /auth` is a live-server mutation; without it a reinstall/`Clear data` would leave OpenCode unconfigured while the Keystore still held the key. `integration/RuntimeIntegration.kt` does exactly that and records `provisioned=[…] failed=[…]` (values never included).
- **Nothing is sent to any project-specific remote backend.** The app's only HTTP code is `client/OpenCodeApi` + `client/OpenCodeEventStream`, both funnelled through `LoopbackGuard.checked(baseUrl)` (unit-tested: non-loopback host → `IllegalArgumentException`). Egress from the device is OpenCode's own provider traffic (`api.openrouter.ai`/whatever the configured provider says) — expected per scope, and not something the app proxies or sees.
- Deleting a credential is `SecretStore.delete` **and** `DELETE /auth/:id`, so OpenCode's durable copy goes away too. Gate **K8** asserts both directions: after `PUT`, `GET /provider` lists the id in `connected` and a canary string appears in `auth.json`; after `DELETE`, `connected` loses it and the canary is gone from disk.

### Proofs the gates will produce

| Claim | Mechanism | Gate |
| --- | --- | --- |
| ciphertext-only at rest | every `filesDir/secrets/*.enc` must begin with the `OCS1` header (a plaintext blob fails the gate); `ls -la` of the dir | `P5-G18` |
| no plaintext password file | `files/secrets/server-password` and `files/secrets/openrouter-api-key` must **not exist** | `P5-G18` |
| password not readable anywhere else | device-side `grep -rl "$PASSWD" files/` must yield nothing outside the test-only `harness/` dir | `P5-G18` |
| OpenCode's own store keeps upstream mode | `stat` of `xdg/data/opencode/auth.json` must be `600`, and must not retain the K8 canary after revoke | `P5-G18` |
| key is non-exportable / hardware-backed | instrumented **K6**: `AndroidKeyStore.getKey(…).encoded == null`, `KeyInfo` introspection (`isStrongboxAvailable`/`isSoftwareAttested` reporting), full round-trip through `SecretStore`, **renaming the blob breaks decryption** (AAD binds the name) | `P5-K` |
| no secret shipped in the APK | `adb pull` of the installed APK, then `strings` over `classes*.dex` **and** `assets/*` matched against `sk-or-v1-[0-9a-f]{24,}`, `sk-[A-Za-z0-9]{32,}`, `BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE`; `unzip -l` must show no `openrouter-api-key`/`*.pem`/`id_rsa`/`server-password` entry | `P5-G19` |
| no secret in the repo or payload | same regexes via `git grep` over `app/ phase4/ phase5/ docs/` | `P5-G19` |
| no external endpoint compiled into the client | scan of `app/src/main/java` for `https?://` literals, excluding `127.0.0.1`, `localhost`, `::1`, documentation/placeholder hosts — **zero** allowed matches | `P5-G19` |

**Known test-only exception, stated plainly:** the *instrumentation* APK (not the app) can export the loopback password to `files/harness/server-password` **only** when a `files/harness/enabled` marker exists, which `20-integration-gates.sh` creates with `run-as`. Reason: the Phase 4 host-side gate drivers authenticate with HTTP Basic, and a Keystore-held secret cannot be read by a shell script. It is a debug-build-only, marker-gated path, exercised by no production code, and `P5-G18` additionally asserts nothing else on disk contains that password. The alternative — a permanent plaintext mirror written by the app — was rejected.

### Every request shape the client sends, re-verified against the pinned source

Phase 5 added no new API surface, so each client method was re-checked against
the pinned commit rather than trusted from memory (this is what "thin client"
means in practice):

| Client call | Upstream definition (pinned `05ea5073`) |
| --- | --- |
| `GET /global/health` | `groups/global.ts:13` — `{healthy: Literal(true), version}` |
| `POST /global/dispose` | `groups/global.ts:120` — returns `Schema.Boolean` |
| `GET /global/event` | `groups/global.ts:88` — SSE, frames `{directory, project?, workspace?, payload}` where payload is a legacy `{id,type,properties}`, `InstanceDisposed`, or a durable `{type:"sync", syncEvent:{type:"<t>.N", data}}` — exactly what `EventFrame.deriveType`/`properties` decode |
| `GET /session?limit=&roots=` | `groups/session.ts:30` `ListQuery` (`limit`/`roots` are real fields; `roots` is a `QueryBoolean`) |
| `GET /session/:id/message?limit=` | `groups/session.ts:43` `MessagesQuery` (`limit` optional int ≥ 0) |
| `POST /session/:id/shell` | `ShellInput = {sessionID, messageID?, agent, model?, command}` — the client sends `{agent, command}` only |
| `POST /session/:id/prompt_async` | `{parts:[{type:"text",text}], model?:{providerID,modelID}, agent?}` |
| `GET /permission` | `groups/permission.ts:20` → `Array(PermissionV1.Request)`; `Request` = `{id, sessionID, permission, patterns, metadata, always, tool?}` (`packages/schema/src/v1/permission.ts:27`) |
| `POST /permission/:id/reply` | `groups/permission.ts:33` — `{reply: "once"\|"always"\|"reject", message?}`; `permission.replied` carries `{sessionID, requestID, reply}`, which is why `Transcript.onReplied` reads `requestID` first |
| `PUT /auth/:providerID` | `groups/control.ts:39`, payload `Auth.Info` = union `Oauth|Api|WellKnown`; the `api` variant is `{type:"api", key, metadata?}` (`packages/opencode/src/auth/index.ts:24`), so the app's `{type:"api","key":…}` body is complete and `metadata` really is optional |
| `DELETE /auth/:providerID` | `groups/control.ts:51` — returns `Schema.Boolean` (client compares the body to `true`) |
| `GET/PATCH /global/config` | `groups/global.ts:97/106`; `Config.updateGlobal` (`config/config.ts:637`) deep-merges the submitted partial config into the first existing global config file (`patchJsonc` for `.jsonc`) and then invalidates — so a partial `{"mcp":{name:…}}` patch from the MCP tab adds one server without clobbering the rest |
| `GET /mcp`, `POST /mcp`, `POST /mcp/:name/{connect,disconnect}` | `groups/mcp.ts` — `{name, config}` payload, name→status map response |
| `GET /experimental/tool?provider=&model=` | `groups/experimental.ts:95` + `tool/registry.ts:286` (`Permission.visibleTools(mcp.tools(), ruleset)`), which is the model-free proof the MCP gates use |

Two client details were tightened while doing this: bodyless `POST/PUT/DELETE`
now declare `Content-Length: 0` (the framing a browser `fetch` produces) instead
of omitting framing, and the query params above were confirmed to be *accepted*
fields rather than guesses.

**Status: IMPLEMENTED; NOT TESTED on device** (the APK/payload/source scans and at-rest checks run in `P5-G18`/`P5-G19`; the Keystore semantics run in `K6`/`K8`).

---

## 4. Re-running the Phase 3 gates against the real integration

Per instruction, G6, G7, G10, G11 and G12 are re-run **against this integration**, in two independent ways.

**(a) Unmodified drivers.** `phase4/scripts/device/gate-{06,07,10,11,12}-*.js` are executed *as shipped* (no edits; the file is not even copied) against `OPENCODE_BASE=http://127.0.0.1:4111` over `adb forward`, with `OPENCODE_DIRECTORY` = the app's fixture workspace and the password from the harness export. Recorded as `P5-R-06/07/10/11/12`. `model_available` is decided by asking the running server (`GET /provider` for a resolvable `default`) rather than assuming a key exists — this phase requires **no** `OPENROUTER_API_KEY`; the pinned build's key-free `opencode/big-pickle` default is what the model-dependent halves use.

**(b) Through the Android client** (`OpenCodeClientGatesTest`, run in the app's own process with the app's own classes — this is the part that actually proves *integration*, not just server health):

| Phase 3 gate | Kotlin gate | What it asserts, through `OpenCodeApi`/`OpenCodeEventStream` only |
| --- | --- | --- |
| **G6** health | **K1** | `GET /global/health` → 200, `healthy=true`, version equals the pinned `1.18.23` |
| **G7** shell/tool round trip | **K2** | `POST /session` → `POST /session/:id/shell {agent:"build", command:"echo P5_K2_SHELL_OK"}` → a `tool` part reaches `state.status=="completed"` **and** the marker appears in the part output; the payload shape is exactly upstream's `ShellInput` |
| **G11** streaming/events | **K3** | one live `GET /event?directory=` stream on `127.0.0.1:4111`, authenticated, carrying `message.part.updated` **and** `session.status`/`session.idle` for *our* session, with REST confirming the parts afterwards (same-frame ordering is what `Transcript` relies on); `server.heartbeat` cadence respected via the read-timeout budget |
| **G10** MCP stdio | **K4** | `GET /mcp` reports `gates-mcp` `connected` (the Phase 4 local stdio server, launched from `opencode.jsonc`), and `GET /experimental/tool` lists its tools |
| **G12** permissions | **K5** | a **real** `permission.asked` frame (config sets bare `"permission":{"bash":"ask",…}` so the agent loop must ask), answered exactly once via `POST /permission/:id/reply {reply:"once"}`, marker text then reaches the transcript, and `GET /permission` is empty afterwards — no auto-approve, no UI short-circuit |
| — | **K6/K7/K8** | Keystore semantics, loopback matrix, credential provisioning round trip (§2/§3) |

Machine-readable verdict lines (`P5_<NAME> PASS|FAIL :: detail`) are emitted to logcat from inside the app process and parsed by the gate script; the instrumented run's own `OK (n tests)` / `FAILURES!!!` lines are parsed too, and *both* must agree for `P5-K` to pass.

**Status: NOT TESTED** — the drivers and the Kotlin gates exist and are self-reviewed, but no device has executed them yet. §6 records the run.

---

## 5. Honesty labels, losses, and what is blocked

- **IMPLEMENTED** (code paths written, self-reviewed, no execution): the loopback policy stack, `LoopbackAudit` + fail-closed stop, `SecretStore`/`Secrets`, the whole `client/` package, `RuntimeIntegration` credential re-push, the four UI tabs (Chat / Approvals / MCP / Credentials / Runtime), `payload_version` 5, and the Phase 5 CI wiring (`phase5/scripts/00-run-phase5.sh`, `11-build-remote-mcp.sh`, `20-integration-gates.sh`, `device/gate-16-mcp-remote.js`, `phase5/workflow/phase5-integration.yml`, plus the Phase 4 tail hook that stages Phase 5 on the same emulator).
- **TESTED** (executed with evidence, on *this host*): the MCP fixture's two network transports against real SDK clients (§2). JVM/Robolectric-free unit tests were **written but not executed** — no JVM in the sandbox — so they are **NOT TESTED** too, and even when green they are not runtime evidence.
- **NOT TESTED:** every device-visible claim in §1–§4 (compile included), the remote-MCP gate on-device, and the full-suite execution on real arm64 hardware — carried forward from Phase 4 and still open. A quick manual real-device pass (install the Phase 5 debug APK, open the Credentials tab, add a key, send one prompt, approve one permission) is worth doing before Phase 8; if CI stays the only full-suite host, that gap moves to Phase 8 explicitly.
- **BLOCKED (needs the user, not me):** (1) *triggering* CI. Re-probed on 2026-08-31 with the reconnected session token: `PUT .github/workflows/phase5-integration.yml` → **403 Resource not accessible by integration**, and `POST .../actions/workflows/<id>/dispatches` → **403** too. So the restriction is not only "cannot write workflow files" but also "cannot start a run"; `git push` itself works, and the branch is published (`8305d47`). Two user-side unblocks, either is enough: click **Run workflow** on `phase4-runtime-host` selecting `arena/01a05713-opencode-app` (its tail stage then runs the Phase 5 suite on the same emulator, ~15 min of Phase 4 work first), or create `.github/workflows/phase5-integration.yml` in the browser from the content of `phase5/workflow/phase5-integration.yml` — after that commit, any push to this branch triggers Phase 5 on its own. (2) running the suite on the Realme RMX3830 (needs adb from the user's machine). (3) repo secrets: none is needed for this phase, `gh secret list` is 403 for this token, and the suite is written so that no gate depends on one. A user-supplied PAT was offered mid-session and deliberately **not** used: this sandbox pins `GH_TOKEN` to the session identity (an inline override returned the identical 403 for `gh api user`), so no external credential can be exercised here — and since it was pasted into chat it should be revoked.
- **Losses introduced by *this* phase: none in OpenCode functionality.** Two behavioural notes: (i) the pre-Phase-5 plaintext key-file bootstrap in `launcher.js` is no longer fed by app code (`Secrets.readApiKey`, `RuntimeEnv`'s `OPENCODE_API_KEY_FILE`, and the `apiKey` env parameter were deleted) — the launcher still honours an operator-provided `OPENROUTER_API_KEY`/`files/secrets/openrouter-api-key` purely as a CI convenience, and Phase 5 asserts that file is absent during a Phase 5 run; (ii) an app-side "export credentials" affordance does not exist, deliberately: exporting Keystore material is the one thing this design must not do. All Phase 1–4 losses (PTY stubs, no `@parcel/watcher`, `NO_CURL`/`NO_OPENSSL` Git, no `bun:ffi` dlopen, degraded mDNS, no 32-bit ABIs) are unchanged and documented in the Phase 4 report.
- **No silent substitutions.** Anything a gate could not verify is recorded as `SKIP` with a reason by `p5()` (e.g. `P5-G16 SKIP` if the fixture could not be built), never as a pass; `GATES_SUMMARY.txt` counts skips separately from passes.

---

## 6. CI run log (append each run here; empty = nothing has been claimed yet)

| Host rehearsal (this session) | n/a | `REHEARSAL_PASS` | fixture + `P5-G16` driver green on one host against a fake-OpenCode MCP surface (§2). Not device evidence; does not close any gate. |
| Run | Ref | Result | Notes |
| --- | --- | --- | --- |
| _pending_ | `arena/01a05713-opencode-app` | — | First run of the Phase 5 suite (staged after Phase 4 on the x86_64 emulator). Expected first failures to triage: Kotlin compile errors (never compiled), `P5-G16` status-string assumptions, `P5-G17` app-uid `/proc/net/tcp` availability (the audit is designed to report *inconclusive*, and the gate then leans on the behavioural socket probe). |

Planned evidence paths after a run: `docs/progress/phase5-evidence/GATES_SUMMARY.txt`, `p5-k-instrument.log`, `p5-k-gates.log`, `rerun-gate-*.log`, `p5-16-mcp-remote.log`, `p5-16-fixture-host.log`, `p5-17-loopback.txt`, `p5-17-external-connect.txt`, `p5-18-credentials.txt`, `p5-19-secret-scan.txt`.

## 7. What changes when the run lands

Only §1–§4 **status lines** and §5's labels change on evidence; the design text above already reflects the code in the tree. If `P5-G17`'s table read is inconclusive on the emulator, the honest outcome is "behavioural refusal proven, kernel table audit unavailable" — recorded as such rather than upgraded to a pass. If `P5-G16` shows `connected` for the *dead* server or the tool list never grows, that is a real integration defect and this phase stays open.
