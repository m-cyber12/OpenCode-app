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

**Status: IMPLEMENTED, PARTIALLY TESTED on the CI emulator.** Run #14 delivered the
behavioural half of this section exactly as designed (`p5-17-loopback.txt`):

```
probe_loopback_connect=OK
probe_external_address=10.0.2.16
probe_external_connect=REFUSED(ECONNREFUSED)
mdns_sockets_owned_by_uid=0
server_version=1.18.23-android
```

with the supervisor's own `runtime.log` carrying
`SERVER_BOUND url=http://127.0.0.1:4111/ hostname=127.0.0.1 port=4111 mdns=disabled cors=none auth=basic user=opencode`
and `BIND_AUDIT audit tcp=unreadable(EACCES) audit tcp6=unreadable(EACCES)`.

What did *not* go as planned is the table half, and the reason matters: the host's
`/proc/net/tcp` view reported `any_listen_on_4111=0` while the app was provably bound and
reachable, so shell/adbd do not share the app's view of the listener table on this image.
G17 now records `table=conclusive|inconclusive(...)` and, when inconclusive, lets the
native-socket probe carry the verdict instead of either passing vacuously on an empty table
or failing on a read it was never permitted to make. In run #15 the table came back
**conclusive and clean** (`table=conclusive listens=1 wildcard=0 mdns_sockets=0`), yet the
gate still failed — because its violation rule counted *any* app-uid row whose local address
did not start with `0100007F:`. All five counted rows were legitimate: two outbound HTTPS
sessions to `:443` (provider traffic, which the brief expects) and three loopback→loopback
client sockets in the IPv4-mapped IPv6 form `::ffff:127.0.0.1`. A bind policy constrains
*listening* sockets, so run #16 restricts the rule to state `0A`, recognises the mapped form,
and prints the outbound rows beside it as the record of what the app talks to. The same
mistake in the in-app gate `K7` (an unreadable `/proc/net/tcp` reported as `wildcard_listeners=-1`
*while the same line said* `external_accepted=0`) is now treated as inconclusive, with the
behavioural refusal carrying the verdict — the honest reading the section's rule already states.
Run #16 (`ed0264f`) closed both gate halves on the emulator: `P5-G17 PASS` with
`table=conclusive wildcard=0 nonloopback_rows=0 listens=1 mdns_sockets=0 bound_lines=5
publish_lines=0 table_ok=1` (and `device global ipv4: '10.0.2.15'` recorded beside it), and the
in-app `P5_LOOPBACK PASS` — so the listener table and the behavioural matrix now agree, and the
only caveat left in this section is the one that has always applied: *real-device* (arm64)
execution of all of it stays NOT TESTED.

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

Run #15 promoted the **local/stdio row to device-confirmed**: the `gates-mcp` child is spawned by the
app's own server from the Keystore-provisioned config and its tools reach the agent — `P5_G10_MCP PASS ::
status={gates-mcp=connected} …` in the app process, and `P5-R-10 PASS` from the *unmodified* phase-4 driver
run on-device against the same server. The **network rows are still NOT TESTED on device**, for two harness
defects found by reading #15's evidence rather than by guessing: (i) `gate-16-mcp-remote.js` imported the shared
helper by repo-relative path, which resolves nowhere inside the staged on-device directory (`Cannot find module
'../../../phase4/scripts/device/gates-lib.js'` from `/data/data/…/files/tmp/p5js/…`) — the driver now resolves
`./gates-lib.js` first and falls back to phase 4's file in place, so the same source works in both layouts; and
(ii) the fixture binds the *host's* loopback while the device must reach it through the emulator's NAT gateway
`10.0.2.2`, which is a different interface — a `127.0.0.1` bind is simply unreachable from the guest, so the
fixture now defaults to `0.0.0.0` (ephemeral CI runner, credential-free, purpose-built peer; the app's own
binding policy is untouched and is what K7/G17 assert). Verdict pending run #16.

Run #17 narrowed the network rows decisively. The driver measured both candidate paths before
asserting anything, and **both answered**: `nat-gateway@http://10.0.2.2:4551=reachable(HTTP 200
{"healthy":true,"marker":"P5_REMOTE_MCP","mcp":0,"sse":0,…})` and `adb-reverse@http://127.0.0.1:4551=reachable(…)`,
with `chosen=nat-gateway` printed into the gate label. The same attempt's `POST /mcp` still produced
`{"p5-remote-http":{"status":"failed","error":"Failed to get tools"}}`. So: the guest *can* dial the host, plain HTTP
works, and what fails lives inside the MCP exchange — which the label alone cannot describe, for a reason that
belongs to pinned upstream rather than to this app. `mcp/index.ts:390-393` converts any failed tool list into
`new Error("Failed to get tools")`, and `McpCatalog.defs()` (`catalog.ts:38-39`) wraps the call in
`Effect.catch(() => Effect.void)`, so the cause is dropped before the status is stored and is never logged — #17's
`opencode-server.log` holds no `p5-remote-http` line at all, which is how I know it is not even logged at WARN
(only `server unavailable`, which fires on the *other* branch, appears, and for `gates-mcp` at that).
`catalog.ts:145-160` shows the single automatic escape: the SDK's strict `listTools` runs first and only an
*output-schema-validation* error is retried with the tolerant schema, so a server whose `tools/list` trips any other
parse or transport error is reported to the client as exactly this string. I tested the obvious suspect here against
the pinned SDK 1.29.0 — the fixture's tool objects carry `$schema` inside `inputSchema`, and they parse fine under
both `ListToolsResultSchema` and `ToolSchema`, so that is not the cause.

No upstream change is proposed. Instead #18 will name the failure from both ends at once, with instruments that are
already in this branch: the driver replays the same conversation itself in raw JSON-RPC (`initialize` →
`notifications/initialized` → `tools/list` → `DELETE`, printing status, `content-type`, whether a session id came
back, elapsed time and the first bytes of each body), and the fixture logs every request it serves
(`[p5-mcp] POST /mcp -> 200 ct=text/event-stream bytes=652 sessions=1 +5ms`, plus a line when a session initializes
and when a response is `CLOSED without ending`). Both were exercised on this host against the live fixture, where the
probe reports `raw_mcp OK … tools/list: HTTP 200 … "tools":[{"name":"remote_echo"…` — so the probe is sound, and on
device it will either reproduce upstream's failure and name it, or show a clean exchange, which would localise the
difference to how upstream's transport uses the platform rather than to what the platform can do. Until that run
publishes, §2's remote rows stay NOT TESTED on device; the `stdio` row stays confirmed by two runs of both halves.

Run #18 (`b67ee5d`) closed the ambiguity, and the answer is neither the network nor the payload. Both ends of the
exchange were recorded in the same run:

```
driver  : raw_mcp OK initialize: HTTP 200 ct=text/event-stream session=yes 17ms … tools/list: HTTP 200 …
fixture : [p5-mcp] streamable session initialized id=f869d6df… (requests=1)
          [p5-mcp] POST /mcp -> 200 bytes=191 | POST /mcp -> 202 | POST /mcp -> 200 bytes=681 | DELETE -> 200
          [p5-mcp] streamable session initialized id=11162817… (requests=5)
          [p5-mcp] POST /mcp -> 200 bytes=191 | POST /mcp -> 202
fixture : after the attempt -> {"mcp":1,"sse":0,"requests":6,"inits":2}
```

Read together: the driver's own raw JSON-RPC conversation completed **on the device, under Bun for Android, over the
SSE-framed response mode** — `initialize`, the `initialized` notification, and a `tools/list` that returned 681
bytes of tool definitions. So the platform, the route and the wire format are all demonstrably fine. The second
session in the same log is OpenCode's: its `initialize` arrived and was answered, its `notifications/initialized`
arrived and was accepted, and **then its client never sent `tools/list`** — `requests` went 5 → 6, and the only
sixth request is the health snapshot. Upstream therefore fails *between* a completed handshake and the tool-list
request, at a point where it throws before writing anything to the socket; `McpCatalog.defs()`'s
`Effect.catch(() => Effect.void)` then discards the error and `mcp/index.ts:393` reports the one string
`"Failed to get tools"`, which is why no log line, no status field and no `GET /mcp` response can say more. That is
a property of the pinned upstream's error handling, not of this app: the same source built for Linux registers
`tools_registered=4` against this very fixture (the host rehearsal), so the difference is in how upstream's client
behaves on this runtime, and it is not reachable from the client side without changing OpenCode — which Phase 5's
rules put out of scope.

Run #19 (`5f1c6f9`) then removed the two remaining candidate explanations in one pass, and narrowed the failure
to a single line of upstream's client:

- the `POST /mcp` that registers the remote server returned in **996 ms**, so nothing is being consumed by
  `McpCatalog`'s `DEFAULT_TIMEOUT` of 30 s (`catalog.ts:11`) — this is an immediate throw, not a stall;
- registering the *same* fixture in **JSON-response mode** fails identically
  (`DIAGNOSTIC json_mode_status=not-connected(status=failed error=Failed to get tools)`), with the fixture logging a
  third session (`requests=8`, three `streamable session initialized` lines, and still no `tools/list` from either
  OpenCode session — two requests each) — so the SSE-vs-JSON response framing is not the cause either;
- the guard at `index.ts:391` must have *passed* (had `getServerCapabilities()?.tools` been falsy, `listed` would be
  `[]`, `!listed` is false for an empty array, and the status would read `connected` with zero tools rather than this
  error) — so upstream reached `client.listTools()` and threw inside it, in about a second, before a byte of that
  request was written to the socket, and the SDK's own error text is destroyed by `defs()`'s
  `Effect.catch(() => Effect.void)`.

That is the boundary of what can be said from outside OpenCode without changing it: on this runtime the client's
state between a completed handshake and a tool-list request is inconsistent, deterministically and in both response
modes; the raw exchange from the same process, same runtime and same route succeeds, which keeps the payload, the
bind, the route and the fixture out of it. Root-causing further means instrumenting upstream's MCP client, which
Phase 5's rules exclude, so it is recorded in §5 as the open item it is. What this does and does not license as a claim: **§2's remote rows are NOT TESTED on device and remain so**, with the
failure bounded to "tool discovery after a successful handshake on-device, with the cause discarded upstream". It
does *not* mean remote MCP is broken on Android in general — this app's client code path for remote MCP is upstream's
own, and no app-side MCP layer exists to blame. The one experiment that would separate "off-device peer" from
"any peer at all" is registered in §5 as the next step: run the same fixture as a **second process on the device**
and point OpenCode at `127.0.0.1`, which needs no app or upstream change and is not yet built. Two diagnostics from
#18 stay in the harness regardless: the elapsed time printed beside each `POST /mcp` (to distinguish a ~30 s
`DEFAULT_TIMEOUT` consumption from an immediate throw — `catalog.ts:11`), and a JSON-response-mode registration
that is *reported, never asserted*, so a response-mode asymmetry would be visible rather than inferred.


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
                                                IV generated by the Keystore provider,
                                                slot name bound inside the ciphertext,
                                                blob = filesDir/secrets/<name>.enc,
                                                header OCS2|ver|ivLen|iv|ct, file mode 0600)
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

### Platform constraint found by the first device run (documented, fixed)

Run #13 was the first ever execution of `SecretStore` on Android, and it failed closed in a
way no JVM test could show (the JDK's own Keystore provider has no such rule):

```
java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted
    at android.security.keystore2.KeyStoreCryptoOperationUtils.getExceptionForCipherInit
    at ai.opencode.android.security.SecretStore.put(SecretStore.kt:114)
    at ai.opencode.android.runtime.RuntimeManager.supervise(RuntimeManager.kt:195)
2026-08-31T15:35:24.874Z [host] state -> FATAL (supervisor error: Caller-provided IV not permitted)
```

Two AndroidKeyStore properties were violated by the original design; both are now honoured in
the code and pinned by gate K6:

- A key created with `setRandomizedEncryptionRequired(true)` **must** get its GCM IV from the
  provider. `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(…, myIv))` throws, so `put` inits
  without a spec and reads the generated IV back off the cipher (the pattern androidx
  security-crypto uses); decryption then passes that stored IV explicitly, which is allowed.
- GCM **AAD is not relied on**: AAD support for Keystore-held keys is not guaranteed across
  platform versions, and it would have to be fed through the same `Cipher.init` call that must
  stay IV-free. The name binding therefore moved *inside* the ciphertext (`binding\nvalue`,
  binding = `ai.opencode.android/secret/<name>`); a blob copied or renamed into another slot
  still refuses to open, now asserted by reading that frame instead of trusting AAD support.

The blast radius is the honest part of this story: the password is derived *before* extraction,
so the exception reached the supervisor, the state went `FATAL`, the payload never started, and
**every** device gate in run #13 cascaded off it (§6). Two resilience changes follow, neither of
which weakens a claim: `Secrets.serverPassword` treats a *failed write* as "ephemeral password for
this run" (still nothing plaintext on disk, loud `WARN` in `runtime.log`), and `Secrets.providerKey`
returns null for an unreadable blob rather than throwing into the supervisor. The gates keep the
strict version — `P5-04`, `P5-KEYSTORE` and `P5-G18` fail if the Keystore path is not genuinely
working — so this resilience can never be mistaken for a pass.

### Proofs the gates will produce

| Claim | Mechanism | Gate |
| --- | --- | --- |
| ciphertext-only at rest | every `filesDir/secrets/*.enc` must begin with the `OCS2` header (a plaintext blob fails the gate); `ls -la` of the dir | `P5-G18` |
| no plaintext password file | `files/secrets/server-password` and `files/secrets/openrouter-api-key` must **not exist** | `P5-G18` |
| password not readable anywhere else | device-side `grep -rl "$PASSWD" files/` must yield nothing outside the test-only `harness/` dir | `P5-G18` |
| OpenCode's own store keeps upstream mode | `stat` of `xdg/data/opencode/auth.json` must be `600`, and must not retain the K8 canary after revoke | `P5-G18` |
| key is non-exportable / hardware-backed | instrumented **K6**: `AndroidKeyStore.getKey(…).encoded == null`, `KeyInfo` introspection (`isStrongboxAvailable`/`isSoftwareAttested` reporting), full round-trip through `SecretStore`, **renaming the blob breaks decryption** (the slot name is bound inside the ciphertext) | `P5-K` |
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

**Status: IMPLEMENTED, TESTED on the CI emulator** — run #15 executed the whole set against the running server:
`P5_KEYSTORE PASS` (K6: round trip, cross-name binding refusal, overwrite, delete, 48-char server credential),
`P5_CREDENTIALS PASS` (K8: `PUT /auth` → provider `connected` → `DELETE` → cleared, with the durable plaintext copy
`chmod 600`), `P5-G18 PASS` (the exported password authenticated a real `GET /global/health` → 200) and
`P5-G19 PASS` (nothing credential-shaped in the APK or payload). `keystore_hw=false` on that image, so *secure-hardware
key residency* stays NOT TESTED (and K6 labels it, without letting it change the verdict). Run #16 repeated the whole
set (`kotlin_gate_pass=10 fail=0`, `P5-G18 PASS` with `enc_blobs=1 plaintext_blobs=0 legacy_absent=1 keyfile_absent=1
auth_mode=600 auth_canary_absent=1 auth_verdict=clean leaks_outside_harness=0`, `P5-G19 PASS` with all three counts at
0). P5-04's definition was corrected twice — see §6.

---

## 4. Re-running the Phase 3 gates against the real integration

Per instruction, G6, G7, G10, G11 and G12 are re-run **against this integration**, in two independent ways.

**(a) Unmodified drivers.** `phase4/scripts/device/gate-{06,07,10,11,12}-*.js` are executed *as shipped* (no edits; the file is not even copied — only *staged*, i.e. copied byte-for-byte into `files/tmp/p5js` on the device) against `OPENCODE_BASE=http://127.0.0.1:4111`, **run by the payload's own bun inside the app's process namespace as the app uid** over `adb forward`, with `OPENCODE_DIRECTORY` = the app's fixture workspace and the password from the harness export. Recorded as `P5-R-06/07/10/11/12`. `model_available` is decided by a **live pre-flight probe** (`phase5/scripts/p5-model-probe.py`: one tiny `POST /session/:id/prompt_async` turn, then look for an assistant text part) rather than by assuming a key exists — this phase requires **no** `OPENROUTER_API_KEY`, and the pinned build's key-free `opencode/big-pickle` default is what those halves exercise. Run #13's earlier rule ("the `/provider` response mentions `default`") was dropped as meaningless once the source was read: upstream fills `default` from the models.dev catalog for *every* provider (`Provider.defaultModelIDs`, `packages/opencode/src/provider/provider.ts:1132`), so it is non-empty even when nothing can serve a turn. Note the asymmetry this preserves honestly: Phase 4 gated the same assertions on `OPENROUTER_API_KEY` being present and therefore **skipped** them in key-free CI — so if the probe passes, Phase 5's `R-10/11/12` are stronger evidence than Phase 4's `G10/11/12` were, and if it fails the drivers still exit 0 with the model halves skipped and `model_probe=` in `GATES_SUMMARY.txt` says exactly what happened.
**Run #15 result: all five PASS on the device** (`P5-R-06 PASS :: health=200 healthy=true version=1.18.23`,
`P5-R-07 PASS :: … reply=P5_G7_SHELL_OK tool=completed parts=message.part.updated`, `P5-R-10`,
`P5-R-11 PASS :: … frames_seen=19 session=ses_f88e849b8ffe…`, `P5-R-12`), **with the model actually available**
(`model_available=1`, `PROBE ok :: model=big-pickle exact-token reply`) — so the parts phase 4 could only skip were
asserted here. Each driver's own `GATES_RESULT.md` verdict lines are written back to the evidence bundle.
Run #16 repeated it with `P5-K: PASS kotlin-client-gates-on-device (K1..K9, pass=10 fail=0 skip=0)` — every
Kotlin half green, including `P5_LOOPBACK` — alongside the same five `P5-R-*` PASS lines.

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

**Status: TESTED on the CI emulator (x86_64, API 34) — not on a physical device.** In run #15 both halves executed
against the app's own on-device server: the five unmodified phase-4 drivers passed (`P5-R-06/07/10/11/12`, listed above),
and the Kotlin half that run #15 completed at 9 of 10 (`P5_G6_HEALTH`, `P5_G7_SHELL`, `P5_G10_MCP`,
`P5_G11_STREAM`, `P5_G12_PERMISSION`, `P5_CREDENTIALS`, `P5_KEYSTORE`, `P5_K9_DURABLE_CONFIG_PATCH`,
`P5_HARNESS_EXPORT` = PASS; the one FAIL, `P5_LOOPBACK`, was my gate rule treating an *unreadable*
`/proc/net/tcp` as a violation while the same line reported `external_accepted=0`). Run #16 re-ran both halves
and the Kotlin side came back **all ten green, none skipped** — `P5-K: PASS kotlin-client-gates-on-device
(K1..K9, pass=10 fail=0 skip=0)`, `P5_LOOPBACK PASS` included — with the same five unmodified drivers passing
again (`P5-R-06/07/10/11/12`) and `model_available=1` both times, so the model-dependent halves were asserted
rather than skipped. `kotlin_gate_skipped=0` in both runs, so nothing was quietly skipped. What that does **not** cover: real arm64 hardware (NOT TESTED, carried from phase 4), and
`GATES_SUMMARY.txt` for the run is the only source of these numbers.

---

## 5. Honesty labels, losses, and what is blocked

- **IMPLEMENTED** (code paths written, self-reviewed, no execution): the loopback policy stack, `LoopbackAudit` + fail-closed stop, `SecretStore`/`Secrets`, the whole `client/` package, `RuntimeIntegration` credential re-push, the four UI tabs (Chat / Approvals / MCP / Credentials / Runtime), `payload_version` 5, and the Phase 5 CI wiring (`phase5/scripts/00-run-phase5.sh`, `11-build-remote-mcp.sh`, `20-integration-gates.sh`, `device/gate-16-mcp-remote.js`, `phase5/workflow/phase5-integration.yml`, plus the Phase 4 tail hook that stages Phase 5 on the same emulator).
- **TESTED** (executed with evidence, on *this host*): the MCP fixture's two network transports against real SDK clients (§2).
- **EXECUTED IN CI (JVM; supporting evidence only, never runtime evidence)**: the 55 unit tests across `client/`, `runtime/`, `security/` — `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, `:app:compileDebugUnitTestKotlin` and `:app:testDebugUnitTest` all **green** in run #11 (`8dfc883`, verdict committed as `050374e`, "BUILD SUCCESSFUL in 1m 47s"). This proves the code compiles for app + instrumentation + tests and that the frame-semantics/reducer/audit/ABI/manifest/secret-name logic behaves as specified on a JVM. It proves **nothing** about the device: no Keystore, no init-ABI gate against real payload bytes, no server, no `/proc`, no permissions round-trip. Twelve of these tests failed on their first execution and the fixes (see §6) are what makes this line worth having.
- **EXECUTED IN CI (device, full standalone mode) — §3's storage claim is now tested, the
  API-integration claims are not.** Run #13 (`b6e87a4`) reached the device for the first
  time (2 PASS / 12 FAIL, all twelve cascading off one startup fatal: the Keystore IV defect
  above). Run #14 (`3792690`, 5 PASS / 9 FAIL) fixed that and went further than any Phase-5
  run had: `P5-01` PASS with `app_uid=10192` and a live `pidof`, `P5-02` PASS on the
  `payloadVersion:5` marker, `P5-03` PASS, **`P5-KEYSTORE` (instrumented K6) PASS** — Keystore
  round trip, `encoded == null` on the master key, ciphertext-only blob with the OCS2 header
  and a 12-byte provider-generated IV, legacy plaintext file gone, renamed blob refused — and
  **`P5-G18` PASS** (`plaintext_blobs=0 legacy_absent=1 keyfile_absent=1 auth_mode=absent
  auth_verdict=clean`), plus `P5-G19` PASS (`secret_hits=0 bundled_secret_entries=0
  external_url_literals=0`). The runtime reached `state -> HEALTHY` (`healthy=true http=200`,
  version `1.18.23-android`) and wrote the loopback audit quoted in §1.
  Two readings must stay narrow: `keystore_hw=false` in the app's own integration line is the
  **emulator's software keymaster**, so *ciphertext at rest* is TESTED while *residency of the
  master key in secure hardware* is NOT TESTED (real device only; StrongBox stays
  device-dependent). And the run established a harness fact worth keeping permanently:
  `am instrument` replaces the app process, and the OpenCode server is a child of that process
  — which is why `P5-04`, all five `R-*` drivers, `G16` and `G17` failed together while the
  supervisor was logging `HEALTHY`. §6 carries the diagnosis and the changes it forced; none
  of those verdicts is an integration result.
- **EXECUTED IN CI (device, full standalone mode) — run #15 (`da4447b`), the first Phase-5 run whose
  integration gates actually asserted something: 11 PASS / 4 FAIL, `gates_skip=0`,
  `kotlin_gate_pass=9 kotlin_gate_fail=1 kotlin_gate_skipped=0`.** Executed on the device, as the app uid,
  against the app's own server: the five **unmodified phase-4 drivers** (`P5-R-06/07/10/11/12` — staged into
  `files/tmp/p5js` and run by the payload's bun in the app's namespace, because host `adb forward` proved to be
  the wrong vantage), the **model pre-flight** (`PROBE ok :: model=big-pickle exact-token reply`, so no model half
  was skipped — the one failure was `P5_LOOPBACK FAIL :: … external_accepted=0 …`, i.e. the gate rule, not the
  runtime, and K7's `wildcard_listeners=-1` was likewise the rule treating "cannot read /proc/net" as a violation),
  **K1–K6, K8, K9** (`P5_G6_HEALTH`, `P5_G7_SHELL`, `P5_G10_MCP`, `P5_G11_STREAM`, `P5_G12_PERMISSION`,
  `P5_CREDENTIALS`, `P5_KEYSTORE`, `P5_K9_DURABLE_CONFIG_PATCH`), `P5-01/02/03`, `P5-G18`, `P5-G19`, and the first
  real `P5-05` relaunch check (`health=OK host-forward adb forward tcp:4111 -> http 200`). The four failures were
  all mine: G16's import path, G17's row rule, K7's table rule, and P5-04 racing the instrumented process for a
  health answer; each is described with its fix in §6. `P5-04`'s new definition is stricter-in-spirit and
  race-free: the Keystore password must be ≥20 chars *and* the export gate that authenticates the live server with
  it must have passed.
- **EXECUTED IN CI (device, full standalone mode) — run #17 (`f9c569b`): 14 PASS / 1 FAIL, `gates_skip=0`,
  `kotlin_gate_pass=10 fail=0 skipped=0`, `model_available=1`.** Every gate in Phase 5's scope is now green on the
  emulator except `P5-G16`: `P5-01`…`P5-05` PASS (including `P5-04 keystore-password-authenticated-live-server`,
  which had never passed before), `P5-K PASS` with all of K1–K9, `P5-G17/G18/G19` PASS, and `P5-R-06/07/10/11/12`
  PASS for a third consecutive run with the live model pre-flight. `G16` remains FAIL with the *cause* narrowed to
  the MCP exchange (§2), and the instruments that will name it are in place.
- **EXECUTED IN CI (device, full standalone mode) — run #16 (`ed0264f`): 13 PASS / 2 FAIL, `gates_skip=0`,
  `kotlin_gate_pass=10 kotlin_gate_fail=0 kotlin_gate_skipped=0`.** This is the run in which the loopback claim, the
  credential contract and the Phase-3 re-runs all became *gate-green on a device* at the same time: `P5-G17 PASS`
  (`table=conclusive wildcard=0 nonloopback_rows=0 listens=1 mdns_sockets=0 table_ok=1`), `P5_LOOPBACK PASS` in-app,
  `P5_KEYSTORE`/`P5_CREDENTIALS`/`P5-G18`/`P5-G19` PASS, `P5-K PASS` with all of K1–K9, the five unmodified phase-4
  drivers PASS again, `P5-05` PASS, and the model pre-flight live (`PROBE ok :: model=big-pickle exact-token reply`).
  Two gates stayed open and both were about my instruments rather than the app: `P5-G16` (whose `Failed to get
  tools` #17 then proved is *not* a routing problem — both routes reachable — but an MCP-exchange problem whose cause
  upstream discards, §2) and `P5-04` (the verdict token I read did not exist in that capture; #17 reads the
  `OK (1 test)` trailer and came back **PASS**).
- **NOT TESTED:** OpenCode's remote MCP transports on device past the handshake. #18 bounded it precisely (§2):
  the route works over both candidate paths, the driver's own raw JSON-RPC exchange completes on-device in SSE mode,
  and OpenCode's session completes `initialize` + `initialized` and then never sends `tools/list`, with the cause
  discarded by `McpCatalog.defs()` upstream — so what is unproven is *tool discovery through upstream's client on
  this runtime*, not the network, not the payload, not the fixture. #19 excludes a slow timeout (the registration
  returns in 996 ms) and a response-mode asymmetry (JSON mode fails with the identical string), leaving a
  client-side throw inside `client.listTools()` before any request byte is written; closing that needs instrumentation
  inside upstream's MCP client, which this phase may not do. The experiment that would separate "off-device
  peer" from "any peer at all" is to run the same fixture as a second on-device process and point OpenCode at
  `127.0.0.1`, which needs no app or upstream change and is **not yet built** (it also needs the MCP SDK importable
  from the payload's `node_modules`, which is unverified); it belongs with the other device-side follow-ups in
  Phase 8. Also still open: the full-suite execution on real arm64 hardware, carried forward from Phase 4. A quick manual real-device pass (install the Phase 5 debug APK, open the Credentials tab, add a key, send one prompt, approve one permission) is worth doing before Phase 8; if CI stays the only full-suite host, that gap moves to Phase 8 explicitly.
- **BLOCKED (needs the user, not me):** (1) *editing or dispatching* the workflow. `phase5-integration.yml` now
  exists on GitHub (id `346561927`, created by the user from `phase5/workflow/`), so **triggering runs is no longer
  blocked** — any push to this branch starts a full device run, docs included, and there is no `concurrency:` block,
  so no push is dropped. Still blocked for this session's identity: creating/editing `.github/workflows/*` and
  `POST .../dispatches`. Re-probed on 2026-08-31 with the reconnected session token: `PUT .github/workflows/phase5-integration.yml` → **403 Resource not accessible by integration**, and `POST .../actions/workflows/<id>/dispatches` → **403** too. So the restriction is not only "cannot write workflow files" but also "cannot start a run"; `git push` itself works, and the branch is published (`8305d47`). Two user-side unblocks, either is enough: click **Run workflow** on `phase4-runtime-host` selecting `arena/01a05713-opencode-app` (its tail stage then runs the Phase 5 suite on the same emulator, ~15 min of Phase 4 work first), or create `.github/workflows/phase5-integration.yml` in the browser from the content of `phase5/workflow/phase5-integration.yml` — after that commit, any push to this branch triggers Phase 5 on its own. (2) running the suite on the Realme RMX3830 (needs adb from the user's machine). (3) repo secrets: none is needed for this phase, `gh secret list` is 403 for this token, and the suite is written so that no gate depends on one. A user-supplied PAT was offered mid-session and deliberately **not** used: this sandbox pins `GH_TOKEN` to the session identity (an inline override returned the identical 403 for `gh api user`), so no external credential can be exercised here — and since it was pasted into chat it should be revoked.
- **Losses introduced by *this* phase: none in OpenCode functionality.** Two behavioural notes: (i) the pre-Phase-5 plaintext key-file bootstrap in `launcher.js` is no longer fed by app code (`Secrets.readApiKey`, `RuntimeEnv`'s `OPENCODE_API_KEY_FILE`, and the `apiKey` env parameter were deleted) — the launcher still honours an operator-provided `OPENROUTER_API_KEY`/`files/secrets/openrouter-api-key` purely as a CI convenience, and Phase 5 asserts that file is absent during a Phase 5 run; (ii) an app-side "export credentials" affordance does not exist, deliberately: exporting Keystore material is the one thing this design must not do. All Phase 1–4 losses (PTY stubs, no `@parcel/watcher`, `NO_CURL`/`NO_OPENSSL` Git, no `bun:ffi` dlopen, degraded mDNS, no 32-bit ABIs) are unchanged and documented in the Phase 4 report.
- **No silent substitutions.** Anything a gate could not verify is recorded as `SKIP` with a reason by `p5()` (e.g. `P5-G16 SKIP` if the fixture could not be built), never as a pass; `GATES_SUMMARY.txt` counts skips separately from passes.

---

## 6. CI run log (append each run here; empty = nothing has been claimed yet)

| Run | Ref | Result | Notes |
| --- | --- | --- | --- |
| host rehearsal (this session) | local | `REHEARSAL_PASS` | MCP fixture + `P5-G16` driver green on one host against a fake-OpenCode MCP surface (§2). Not device evidence; does not close any gate. |
| 65 (`33385975679`) | `arena/01a05713-opencode-app` | **FAILED, no gate verdicts** | Dispatched manually by the user at 11:13Z against the Phase 4 workflow; died at step 5/10 ("Run the Phase 4 suite") after ~9 min, *before* the Phase 5 tail stage could run (no Phase 5 evidence commit exists, and Phase 5 publishes on every exit path including a failed build). Steps 1-4 (checkout, KVM, JDK) were green; steps 6/7 (evidence copy, artifacts) ran because they are `if: always()`. **Which Phase 4 sub-step failed is an inference from the timing, not a fact:** step 5 covers the payload build, the Gradle compile, the emulator boot and the device gates; ~9 min with no boot log activity points at the Gradle compile of the ~2,600 new Kotlin lines, but the log is unreachable from here (§next) so it is unconfirmed. Nothing is claimed from this run. |

### Run 65 diagnosis and fix (resolved, first compile attempt)

Root cause of all nine errors, in one character of prose: **Kotlin block comments
nest** (unlike Java's), and a KDoc line in `LoopbackAudit.kt` said
`/proc/net/* prints IPv4 as…`. That `/*` opened a second comment, so the outer one
closed on the wrong `*/` and the rest of the file - `formatAddress`, `audit`,
`rawFor`, and the object's closing brace - was silently commented out. Every
`Unresolved reference` in `RuntimeIntegration.kt` (`audit`, `ok`, `listeners`,
`detail`, `mdnsSockets`, `rawFor`) was that one swallowed object, plus two real
syntax errors (`Missing '}'`, `Unclosed comment`). No API mismatch, no behavioural
design problem: the fix is prose (`proc-net tables`), applied to `LoopbackAudit.kt`
and to the same pattern in `LoopbackAuditTest.kt`.

Because the compiler reports every error in a module, the log also told us something
good: the remaining ~2,590 new Kotlin lines in `:app:compileDebugKotlin` were clean
(`:app:testDebugUnitTest`, the androidTest APK and the device gates were never
reached, so they stay unverified).

To keep this class of failure off the CI queue, `phase5/scripts/check-kotlin-comments.py`
now lexes every Kotlin file in the repo for unbalanced/nested block comments (it
catches the trap in both source sets; run it before pushing). Brace/paren/bracket
balance after comment-and-string stripping is checked the same way by hand.

Also from that log: `Unable to strip the following libraries, packaging them as
they are: libbun.so, libchildshim.so, libexecshim.so, libgit.so, librg.so,
libseccompshim.so` is a **warning**, not a failure - these are prebuilt musl/static
artifacts from the payload, and AGP has no matching NDK strip tool for them. They are
packaged unstripped (they already are, by build design); the APK size cost is known
and already reflected in the phase-4 numbers.

### What runs 1-3 of `phase5-integration` showed (and one flaw they exposed)

Installing the workflow made the loop work as designed - a push to this branch now
starts a run by itself (run #1 `b4ccc93`, #2 `fb46557`, #3 `53221c6`, all
auto-triggered) - and it also showed that **my "fast" mode could not have worked as
first written**: `preBuild` `dependsOn(verifyAndStagePayload)`, so *every* Gradle
task in this app - including a pure compile - fails on a missing
`phase4/out/engine/assets/runtime-manifest.json`, and the fast mode deliberately
skipped the payload build. Runs #1/#2 therefore sat far longer than a compile should
and produced no evidence commit that this side could see. Two changes, both local
until the connection is back:

- `app/build.gradle.kts`: `verifyAndStagePayload` honours an opt-in `-PskipPayload`
  that skips staging with a loud warning, documented as compile/test-only (never
  passed with `assemble*`/`package*`), because the packaging path must keep failing
  closed on a missing payload.
- the fast mode now runs `:app:compileDebugKotlin`,
  `:app:compileDebugAndroidTestKotlin`, `:app:testDebugUnitTest` with that flag - a
  real compile check that needs no runtime, no APK, no emulator. It is labelled as
  such in its own summary line and in the report; it closes no gate.
- `00-run-phase5.sh` publishes a `PROGRESS.txt` heartbeat into
  `docs/progress/phase5-evidence/` every four minutes (ignored path, so it cannot
  re-trigger the workflow; a run started from a heartbeat commit disables its own
  heartbeats, bounding any cascade to one), which is how a long or hung step becomes
  observable from the branch instead of the unreachable console.

### Why runs #1-#3 produced nothing: a hang in my own CI step wrapper (reproduced locally, fixed)

Runs #1 (`b4ccc93`), #2 (`fb46557`) and #3 (`53221c6`) of `phase5-integration` were
still `in_progress` after ~2 hours with `updated_at == created_at` and no evidence
commit. Cause: both orchestrators wrapped every step as
`timeout N bash -c "..." 2>&1 | tee -a "$MAINLOG"`. When `timeout` kills the build,
the build's surviving JVM (Kotlin compile daemon / Gradle worker, which inherits the
pipe and ignores SIGTERM) keeps the pipe's write end open, so `tee` never sees EOF -
the step stops *forever* instead of failing, `record_fatal`/`push_evidence` never
run, and the run burns its whole 150-minute allowance without publishing anything.

Reproduced in this sandbox, old vs new, with a TERM-ignoring grandchild:

    NEW (redirect to file, `timeout -k 30`):  FILE_DONE elapsed=2s  rc=124
    OLD (`| tee`):                            still blocked at the 30s probe -> exit 124

Fix: both `run_c` helpers now redirect the step to a file and `cat` it (kill is
decisive), pass `-k 30` (children that ignore SIGTERM get SIGKILL), and label
`rc=124/137` as `STEP_TIMEOUT rc=… after Ns (killed)` so a timeout is never mistaken
for a build error. A repo-wide sweep (`grep -En "timeout [0-9]+ .*\|"` over
phase4/phase5 scripts) finds no other killable-piped step: the remaining 69 `| tee`
uses are on commands nothing kills.

**What this means for the record:** the silence of runs #1-#3 is *not* evidence about
Phase 5 - it is a tooling defect of mine, and it also means I could not yet confirm
which step was timing out (gradle-only mode should have been a few minutes; the
`verifyAndStagePayload` flaw above is the leading candidate, now fixed). The next run
either publishes a compile verdict or publishes a `STEP_TIMEOUT` line naming the step.

### Runs #7-#9: the fast loop in operation, and what the unit tests found

With the step-hang fixed, a push now yields a verdict in ~6 minutes, published as an
evidence commit on this branch. Sequence and findings:

- **#7/#8** (`246484f`, `e97b009`): `-PskipPayload` alone did *not* reach the task
  (Gradle's property handling differed from my assumption), so the compile never
  started; fixed by also passing `SKIP_PAYLOAD=1` and printing what the task saw.
  #8 then proved the pipeline works end to end: `:app:compileDebugKotlin`
  **SUCCEEDED** - the whole Phase 5 client/runtime/UI/security surface type-checks -
  and both test source sets failed with 9 errors, all listed in the pushed log.
- **#9** (`c83740c`): after those 9 fixes, **all three compile tasks are green**
  (`compileDebugKotlin`, `compileDebugAndroidTestKotlin`, `compileDebugUnitTestKotlin`)
  and `:app:testDebugUnitTest` executed for the first time: **43 of 55 tests pass,
  12 fail**. The JVM unit tests are supporting evidence, not runtime evidence, but
  these failures were worth fixing before any device run, and they split into four
  distinct causes:

  1. *My test fake, not the client* (4 failures): the `ServerSocket` stand-in parsed
     headers with a `BufferedReader`, which prefetches the request body into the
     reader's buffer, so the raw stream then blocked until the client's read timeout.
     Every body-carrying request timed out; GETs and the zero-length-body DELETE (no
     body to steal) passed - which is how it was diagnosable at all. Now parsed a byte
     at a time until the blank line.
  2. *Fixtures that were nicer than reality* (5 failures): `message.part.updated`
     frames in the tests carried `messageID` only on the envelope, while upstream's
     `Part` object carries its own `messageID`; the reducer (correctly) requires it and
     dropped the frames. Fixtures now match the real frame shape, and the reducer
     gained the same `props` fallback it already used for `sessionID`, so a frame that
     names the message nowhere still cannot mint a phantom message.
  3. *Two genuine client bugs* (3 failures + one latent): a `session.status`/
     `session.idle` frame and a `permission.asked` frame did not create the session row
     they attach to, so a busy spinner or a pending approval arriving before any part
     frame was **invisible in the UI** - exactly the kind of thing that would have
     looked fine on a happy-path device run. `setBusy` and the ask handler now `touch`
     the session. `message.part.removed` also set `dirty` on a no-op path.
  4. *A stale expectation*: `ManifestTest`'s fixture still said `payloadVersion: 4`
     after the Phase 5 bump to 5, so it failed on the *correct* mismatch; fixture now
     says 5 (the mismatch case remains covered in `PayloadExtractorTest`).

- **#10** (`6f977b8`): 54 of 55 green; the one survivor (`toolPartLifecycleKeepsLatest
  Status`) was diagnosed against the pinned upstream rather than by tweaking the
  assertion - `session/processor.ts:171-180` rebuilds the tool state on completion and
  **carries `input` forward**, so the reducer's replace-the-part behaviour is the
  faithful mirror and my *fixture* was the thing that lied. Fixture corrected (and the
  test now also asserts the command survives completion, which is the UI-visible
  consequence); reducer untouched. (The sandbox reset also deleted the pinned upstream
  checkout under `~/upstream`; it is re-obtainable in seconds with
  `git fetch --depth 1 --filter=blob:none origin 05ea5073… ` + sparse-checkout of
  `packages/opencode/src/{session,bus,permission}`, and that is what these
  verifications are read from.)

  A finding worth keeping in its own words: **`PATCH /global/config` cannot be tested
  on the JVM at all** - `java.net.HttpURLConnection` throws
  `ProtocolException: Invalid HTTP method: PATCH`, while Android's OkHttp-backed
  implementation accepts it. Production code uses that route to persist MCP entries and
  permission modes across restarts, so it had zero coverage from unit tests and none
  from the shell-side gates either. Added instrumented gate **K9
  (`P5_K9_DURABLE_CONFIG_PATCH`)**: PATCH a *disabled* MCP probe, read the global
  config back, assert it persisted and that the fixture's `gates-mcp` entry survived the
  deep-merge. The JVM test was rewritten to pin the JDK limitation itself
  (`patchMethodIsAndroidOnly`) so the gap stays visible in code rather than as a test
  that silently proves nothing.

- **#11** (`8dfc883`): **BUILD SUCCESSFUL in 1m 47s** - all three compile tasks green
  and `:app:testDebugUnitTest` passed 55/55. First executed evidence for any Phase 5
  code (JVM only; supporting, not runtime). Commit `050374e` carries the log, the
  summary and all 10 JUnit XML reports.
- The marker `phase5/CI_GRADLE_ONLY` is now `0`, so the next push runs the **full
  standalone suite**: payload build -> both APKs -> emulator boot + install ->
  `20-integration-gates.sh` (P5-01..04 prerequisites, K1..K9 in-app gates, G16 remote
  MCP, G17 loopback/no-mDNS, G18 credentials at rest, G19 secret scan, and the Phase 3
  re-runs G6/G7/G10/G11/G12 through the phase-4 drivers verbatim). Heartbeats make
  that ~40-minute run observable per step.

- **#12** (`d095a94`, first full-mode attempt): died in ~2 minutes with `no usable x86_64
  system image under …/system-images`. Root cause was a wrong assumption in my standalone
  path, not the CI image: the runner ships SDK *tools* but no `system-images/` directory, so
  probing for an emulator can never succeed — it has to be installed. Standalone mode now
  calls Phase 4's `50-install-sdk.sh` under the same `HAS_EMU/HAS_IMG` test and points the
  fatal at its log (`64d420c`, re-played onto the tip as `b6e87a4` after a sandbox reconnect
  reset; the reconnect also taught a durable lesson — `docs/progress/phase5-evidence/**` came
  back *stale* in the snapshot, so `git diff --cached` + `git restore --source=HEAD` on that
  directory must precede any commit, or published evidence gets silently reverted).
- **#13** (`b6e87a4`): **the first run that reached the device.** Everything the previous
  eleven runs were about — install, boot, build, stage, launch, gates, evidence push —
  worked, and the run produced real verdicts for the first time:

  | Gate | Verdict | What it actually says |
  | --- | --- | --- |
  | `P5-01` | FAIL | app installed and version-correct, but judged on an `app_uid` that came back empty — a **gate bug** (see below) |
  | `P5-02` | FAIL `payload-v5-manifest-marker` | marker read *before* the app was even launched — a **gate bug**, though extraction genuinely had not happened yet |
  | `P5-03` | **PASS** | fixture + harness marker written through `run-as` ⇒ install and debuggability are proven |
  | `P5-04` | FAIL `keystore-password-and-healthy-server` | the Keystore crash above: `password: … (0 chars)`, then the supervisor tail |
  | `P5-K` | FAIL (`pass=0 fail=0`) | all 8 server-dependent Kotlin gates hit `Assume.assumeTrue` and were **invisible** — JUnit reported `Tests run: 11, Failures: 3`, and K6's failure *is* the IV exception |
  | `P5-R-06/07/10/11/12` | FAIL | the phase-4 drivers never ran: standalone CI has no host bun (Phase 4 downloads one inside its own gate script) — a **harness gap**, not a driver result |
  | `P5-G16` | FAIL | host fixture listener came up (`up=1`), device half needs a server |
  | `P5-G17` | FAIL | needs the app uid + a live server; `live_launcher_processes` was a wall of `/proc/<pid>/cmdline: Permission denied` — shell uid 2000 cannot walk `/proc` on Android |
  | `P5-G18` | FAIL | `enc_blobs=0` — consistent with `put()` always throwing; `legacy_absent=1 keyfile_absent=1` are real |
  | `P5-G19` | **PASS** | `secret_hits=0 bundled_secret_entries=0 external_url_literals=0` |
  | `model_available` | 0 | `/provider` was never queried against a live server (no key was needed for this, contrary to how it logged) |

  So one **product bug** (the Keystore IV/AAD contract, above) and four **harness bugs** in
  `20-integration-gates.sh`, each fixed in the same commit rather than papered over:

  1. `app_uid` came back empty from `dumpsys package` parsing ⇒ P5-01's own condition. Now
     `run-as <pkg> id -u` with `pm list packages -U` as fallback.
  2. `count_launchers()` walked `/proc/*/cmdline` as uid 2000, which Android denies, so the
     diagnostic was pure noise. Now `ps -A | grep -c '[l]auncher.js'`.
  3. P5-01/P5-02 read the extraction marker once, *before* `am start`, on a cold install where
     extraction takes seconds — a guaranteed false FAIL. The launch is now followed by a 60 s
     poll, and P5-01 additionally requires a live `pidof`.
  4. The host runner for the drivers did not exist in standalone mode, and the script logged
     `FATAL` without a fallback, then let `run_driver` report driver failures. It now fetches
     the same pinned `@oven/bun-linux-x64@1.3.14` Phase 4 uses (node with
     `--experimental-detect-module` only as a fallback, since the drivers are ESM with
     top-level await), and a missing runner is recorded as **skip**, not FAIL.
  Observability fixes rode along, because "0 pass / 0 fail" was itself a diagnosis failure:
  every skipped Kotlin gate now prints `P5_<ID> SKIPPED` so skips are countable in
  `GATES_SUMMARY.txt` (`kotlin_gate_skipped=`), the K verdicts are read from the union of the
  instrumentation stream and logcat (`p5-k-lines.txt`, deduplicated — logcat alone hid them),
  `adb logcat -s` now includes the `OpenCode/gate` tag (the old filter matched only `OpenCode`
  and silently excluded every verdict line), P5-04 surfaces the export gate's own line, and
  `P5-K` requires all 9 gates to pass rather than 6.

- **#14 → #15 (the run-#14 diagnosis, fixed in the push that triggers #15).** Both halves of
  the failure were about *how the gates attach*, not about the client:

  1. **`am instrument` replaces the app process, and the OpenCode server is a child of that
     process.** The instrumented process has no server unless the app's own start path runs
     inside it (only `MainActivity`/`RuntimeService` call it), and every stage after an
     instrument run was talking to a process that had already been replaced. Fixed on both
     sides: the test class gained `@Before startRuntimeInThisProcess()` —
     `RuntimeManager.get(ctx).start()`, idempotent, the production supervisor path minus the
     foreground-service wrapper (which exists only to keep the process alive), then a poll
     for health up to 150 s — and the gate script does `force-stop` → relaunch →
     `wait_healthy` before the device-side stages, recorded as a new gate **`P5-05
     runtime-healthy-after-instrumentation`** so a relaunch failure can never be mistaken for
     a gate failure downstream of it.
  2. **A host `adb forward` is not a route into the app's namespace.** Every host-side request
     in run #14 died with `ECONNRESET` (the drivers) or `TypeError: fetch failed` (undici, for
     G16) while `probe_loopback_connect=OK` inside the app said otherwise, and
     `any_listen_on_4111=0` in the host's table read matches "different view", not "nothing is
     listening". The five phase-4 drivers and `gate-16` therefore now execute **on-device with
     the payload's own bun, as the app uid** — driver files unmodified, same env contract,
     each wrapped in `timeout 300` — which also removes the ~90 MB host-bun fetch phase 4
     needed and makes gate-10's MCP stdio child spawn *on the device* (phase 4 spawned it on
     the host). The host keeps exactly one job: the remote-MCP fixture listener, which has to
     live outside the device for G16 to mean anything, and node is enough for that.
     `wait_healthy` tries the forward first, then the in-namespace probe, and prints which
     path answered (`HEALTH_TRANSPORT=host-forward|on-device`); G17 records the host-forward
     result as a diagnostic instead of a dependency.
  3. The model pre-flight moved to `phase5/scripts/device/p5-model-probe.js` for the same
     reason — it was a host-side HTTP call — and the python version is deleted.
  4. The export gate no longer swallows its failure: it prints `lastErr=<Class>: <message>`
     where run #14 had a bare `health=null`, which is what cost this diagnosis cycle.
  5. The gates step in `00-run-phase5.sh` stopped being a `| tee -a` pipe — the exact shape
     that hung runs #1–#4 — and goes through `run_c 3600`.

G18's two legibility fixes ride in the same push: the leak scan no longer reports 20 phantom
matches when no password was exported (an empty `grep -rl ''` pattern matches every file, which
is exactly what `leaks_outside_harness=20` in run #13 was), and the durable-auth flag is renamed
`AUTH_BAD` because it carried 0 = clean polarity under a name saying the opposite, against every
other check in the file — both conditions were already correct, just unreadable.

**Run #15 (`da4447b`) — the integration gate ran, and four of my own rules failed.** `GATES_SUMMARY.txt`
came back `gates_pass=11 gates_fail=4 gates_skip=0`, `kotlin_gate_pass=9 kotlin_gate_fail=1
kotlin_gate_skipped=0`, `model_available=1`. The pass set is what Phase 5 was for: `P5-01` (uid 10192, live
`pidof`), `P5-02` (`payloadVersion:5`), `P5-03`, **all five unmodified phase-4 drivers, executed on the device**
(`P5-R-06/07/10/11/12`; `P5-R-11` streamed 19 frames and matched `session.idle` against a live keyless
`big-pickle` turn), `P5-05` (server healthy again after instrumentation, via `host-forward` — and the transport is
now written to `$OUT/health.transport`, because the old value was computed inside a `$(…)` subshell and so printed
`none`), `P5-G18`, `P5-G19`, and in-app K1–K6/K8/K9. The four failures were all in the harness, and reading them is
the useful part of this section:

- `P5-G16 FAIL` was one line: `Cannot find module '../../../phase4/scripts/device/gates-lib.js' from
  '/data/data/ai.opencode.android.debug/files/tmp/p5js/gate-16-mcp-remote.js'` — a repo-relative import inside a file
  the staging step flattens onto the device. Behind it waited a second defect the run never reached: the fixture
  answered on the *host's* loopback while the device was told to dial `10.0.2.2`, the emulator's NAT gateway — a
  different interface, so a connect would have been refused and reported as `failed`, which is precisely the shape a
  real defect looks like from outside. The driver now resolves `./gates-lib.js` first and falls back to phase 4's file
  in place; the fixture now defaults to `0.0.0.0`, because `10.0.2.2` is the emulator's NAT
*gateway interface* on the host, not its loopback, and a `127.0.0.1` bind is unreachable from the
guest. Run #16 then produced the first real on-device remote-MCP measurement, and it is precise
about where the boundary lies: the driver ran, the pre-state was clean, `POST /mcp {type:"remote",
url:"http://10.0.2.2:4551/mcp"}` was **accepted (200)**, and upstream's MCP client reported
`{"p5-remote-http":{"status":"failed","error":"Failed to get tools"}}` — while the fixture logged
`mcp:0 sse:0`, i.e. not one session reached it. The same run also confirmed the *negative* case for
free: an unreachable remote reports `failed` with a real error string rather than being swallowed,
which is exactly the evidence that MCP was not crippled to make loopback work. What is therefore
NOT TESTED is only the wire: whether the guest can open a TCP session to the test host at all. #17
measures that instead of guessing — the driver probes `GET /health` over both candidate routes
(NAT gateway, and an `adb reverse` tunnel to the same host port), uses whichever answers, prints
`chosen=<route>` into the gate label so the verdict names its own path, and exits 7 (SKIP) if
neither answers, because "the harness could not reach its own fixture" must never be booked as a
result about OpenCode's transports. The local/stdio half of this section is confirmed on device
(#15 and #16 both: `P5_G10_MCP PASS` in-app, `P5-R-10 PASS` from the unmodified driver).
- `P5-G17 FAIL` and `P5_LOOPBACK FAIL` were one mistake in two places: an absence of view treated as a violation.
  G17's row rule counted non-LISTEN sockets (two outbound `:443 provider sessions and three `::ffff:127.0.0.1`
  loopback client rows) while K7's counted an unreadable `/proc/net/tcp` — and both files recorded the thing that
  matters: `wildcard=0`, `external_accepted=0`, `mdns_sockets=0`, `probe_external_connect=REFUSED(ECONNREFUSED)`,
  `table=conclusive listens=1`. The rules now ask the question the claim actually poses: LISTEN rows only, mapped
  loopback recognised as loopback, unreadable table inconclusive with the behavioural refusal carrying the verdict,
  and outbound provider/network-MCP sessions listed as evidence rather than counted as offences.
- `P5-04 FAIL` was a race I created, and its own evidence contradicts itself in a way worth recording: the export
  gate inside the app printed `P5_HARNESS_EXPORT PASS :: bytes=48 waited=0s health={"healthy":true…}` — the Keystore
  password authenticated the live server — while P5-04's host-side check timed out 30 seconds later, because that
  server is a child of the instrumented process, which had by then exited. `P5-04` is now defined as "Keystore-held
  credential of ≥20 chars **and** the export gate that authenticated a live server with it passed", with
  outside-reachability owned by `P5-05` after the relaunch. One of my own diagnostics contributed: `wait_healthy`
  printed its timeout line to stdout, which the caller captures and compares against `HEALTH_OK`; that line now goes
  only to the log file.
- Also corrected while the evidence was open: `P5-05`'s `HEALTH_TRANSPORT` reported `none` (subshell scope, fixed
  above), and the staging step's `tar` is **toybox** on the device rather than the GNU `tar` the host rehearsal used,
  so `-C` plus a member path is unverified on API 29 — the on-device `ls` after extraction is the guard for now, and
  a real-device pass should look at it directly. Separately, `phase4-runtime-host` run #66 on this branch failed at
  step 5 and committed **no** evidence, so the Phase 4 G1–G14 suite is not verified here either; its verdicts exist
  only on the phase-4 branch.

Run #16 therefore has one job: turn `P5-G16`/`P5-G17`/`P5_LOOPBACK` green on the same device with the same model
pre-flight, so §1 and §2 can drop "pending" and read TESTED on the CI emulator end to end. What #15 already settled —
executed, device-side, skip-free — is the phase's core question: the Android client drives the on-device OpenCode
server as a real client, with streaming, permissions, stdio MCP and Keystore credentials, and no upstream change.
Nothing in this report may be upgraded on the strength of #13's or #14's build succeeding, of #11's 55/55 unit tests,
or of a `state -> HEALTHY` line that no gate has yet asserted anything about.

**Run #16 (`ed0264f`) — the corrected rules held, and the two open gates say something specific.**
`gates_pass=13 gates_fail=2 gates_skip=0`, `kotlin_gate_pass=10 kotlin_gate_fail=0 kotlin_gate_skipped=0`,
`model_available=1`. Concretely: `P5-K PASS` with **all** of K1–K9 green (`K7`'s loopback matrix now reads
`table=… / external_accepted=0` and passes on what it measures, not on what the app is permitted to see),
`P5-G17 PASS` with `table=conclusive wildcard=0 nonloopback_rows=0 listens=1 mdns_sockets=0 bound_lines=5
publish_lines=0 table_ok=1`, `P5-G18 PASS` with `enc_blobs=1 plaintext_blobs=0 legacy_absent=1 keyfile_absent=1
auth_mode=600 auth_canary_absent=1 auth_verdict=clean leaks_outside_harness=0`, `P5-G19 PASS` with every count at
zero, and the five unmodified phase-4 drivers green a second time. So §1, §3 and §4 rest on executed device
evidence from here on, on that emulator image.

The two failures are the interesting half, because #16 is the first run whose failures are *about the system under
test* rather than about my quoting:

- `P5-G16` got past both #15 defects and produced a real measurement: the driver reached its assertions, the
  pre-state was clean (`gates-mcp=connected`, 12 tool ids, no remote tools — the driver's own "absent before"
  assertion passed), `POST /mcp` with `{type:"remote", url:"http://10.0.2.2:4551/mcp"}` was **accepted with 200**,
  and OpenCode's MCP client reported `{"status":"failed","error":"Failed to get tools"}`. The fixture's own log for
  the same window says `P5_MCP_LISTENING http://0.0.0.0:4551/mcp` and its health endpoint says `mcp:0 sse:0`: it
  never received a session. The app's config path, status surface and error reporting are therefore *working as
  upstream defines*, and the unresolved variable is the network path between guest and host — which a gate must
  not answer by inference. #17 measures it: the driver `GET /health`s each candidate route (`nat-gateway` on
  `10.0.2.2`, `adb-reverse` through a forwarded device port to the same host port), drives the fixture URLs from
  whichever answers, prints `chosen=<route>` into the gate's summary label, and exits **7** when neither answers so
  the run records a SKIP with the probe text instead of a verdict about MCP. Verified locally in both directions
  (live listener → `chosen=adb-reverse` and it proceeds; both dead → `GATE16 NO_ROUTE`, rc 7).
- `P5-04` failed while *passing*. Its export run printed `OK (1 test)` after 44 s — the JUnit trailer, which this
  project treats as the authority — yet my new verdict grep looked for the `P5_HARNESS_EXPORT PASS` token in that
  capture, where it does not exist: an instrumented test's `println` goes to logcat (which is exactly why the K
  stage reads logcat too), and the file held only the trailer. `export_verdict=none` then failed the gate. #17
  reads the trailer and the absence of a failure marker, which is the same claim from a complete source. This is the
  second time in two runs that P5-04 has been the gate that exposed a harness assumption rather than an app one;
  §3's credential verdict has never depended on it, since K6/K8/G18 measure the same contract directly.

Nothing in this report may be upgraded on the strength of #13's or #14's build succeeding, of #11's 55/55 unit
tests, or of a `state -> HEALTHY` line that no gate has yet asserted anything about; and the reverse also holds —
#16's ten green Kotlin gates are evidence about *that emulator image*, with arm64 hardware, secure-element key
residency and the remote-MCP wire still explicitly open.

**Run #17 (`f9c569b`) — 14 PASS / 1 FAIL / 0 SKIP: Phase 5's own scope is green on the emulator.**
`P5-01`…`P5-05` all PASS — `P5-04 keystore-password-authenticated-live-server` among them, for the first time, now
that the verdict is read from the export run's `OK (1 test)` trailer instead of a stdout token that the instrumented
runner never writes — `P5-K: PASS kotlin-client-gates-on-device (K1..K9, pass=10 fail=0 skip=0)`, `P5-G17 PASS`,
`P5-G18 PASS`, `P5-G19 PASS`, and `P5-R-06/07/10/11/12 PASS` for a third consecutive run with `model_available=1`.
That is the phase's stop condition met on this image: the Android client drives the on-device OpenCode server as a
real client — sessions, streaming events, a permission round trip, stdio MCP, Keystore-held credentials — with no
upstream API change and nothing skipped to get there.

`P5-G16` is the single gate still open, and #17 is what turned it from "unexplained" into "narrowly located". The
route probe I added for this run answered on *both* candidate paths (`nat-gateway@http://10.0.2.2:4551=reachable`,
`adb-reverse@…=reachable`), so the guest reaches the host over HTTP; `POST /mcp` is accepted with 200; and
upstream's remote client still reports `{"status":"failed","error":"Failed to get tools"}`. Reading that string in
the pinned source is what explained the silence: `mcp/index.ts:390-393` invents the label and
`McpCatalog.defs()`'s `Effect.catch(() => Effect.void)` discards the cause, so `GET /mcp`, the server log and this
report can only ever see the same three words — `#17`'s `opencode-server.log` contains no `p5-remote-http` line at
all. I ruled out the first suspect locally (the fixture's `inputSchema` carries `$schema`; SDK 1.29.0's
`ListToolsResultSchema` and `ToolSchema` both accept it, and upstream's tolerant retry only fires for
*output-schema* validation errors, `catalog.ts:145-160`). #18 therefore carries two new instruments, both verified
against the live fixture on this host: the driver replays the exchange itself in raw JSON-RPC and prints each
response's status, content-type, session presence, timing and first bytes; the fixture logs every request it serves
and every session it opens or closes. If the device-side probe prints `raw_mcp OK` while OpenCode still reports
`failed`, the finding is about how upstream's transport drives fetch on this platform, not about the payload, the
bind, or the fixture — and §2 says exactly that, with the labels to match, whichever way it lands.


**Run #18 (`b67ee5d`) — same verdicts, new instruments, and the last unknown became a bounded finding.**
`gates_pass=14 gates_fail=1 gates_skip=0`, `kotlin_gate_pass=10 fail=0 skipped=0`, `model_available=1`: nothing that
was green in #17 moved, which is the point — the run added the raw JSON-RPC probe on the driver and the per-request
log on the fixture, and both produced the evidence §2 now rests on (the device-side probe completing the whole
`initialize → initialized → tools/list` exchange in SSE mode, OpenCode's own session reaching the same fixture and
then never sending `tools/list`: `requests 5 → 6`, `inits=2`, `mcp:1`). No gate's definition changed, so #17's
fourteen green gates still stand as the phase's evidence; #18's contribution is that `P5-G16`'s failure is now
described by two measured facts instead of one swallowed string. The next run adds the elapsed time beside each
`POST /mcp` (so a ~30 s `DEFAULT_TIMEOUT` consumption can be told apart from an immediate throw) and a
*reported-not-asserted* JSON-response-mode registration; both were exercised locally first, which is also how a bug
in my own instrumentation was caught before it could reach CI (the fixture handler referenced `url` out of scope and
answered every MCP request with `{"error":"url is not defined"}` — a green-looking G16 with that bug would have been
worse than a red one).

**Run #19 (`5f1c6f9`) — 14 PASS / 1 FAIL / 0 SKIP again, with `P5-G16` now fully bounded** (its summary label carries
the route it used: `route=nat-gateway`). The two diagnostics added for this run closed the last two hypotheses.
`POST /mcp` for a remote registration returns in **996 ms**, so upstream's 30 s tool-list timeout
(`catalog.ts:11`) is not being consumed — an immediate throw, not a stall. And the same fixture registered in
**JSON-response mode** fails with the *identical* `Failed to get tools`
(`DIAGNOSTIC json_mode_status=not-connected(status=failed error=Failed to get tools)`), with the fixture logging a
third initialized session (`requests=8`, three `streamable session initialized` lines) and still no `tools/list` from
either OpenCode session — two requests each. §2 carries what that leaves: upstream throwing inside
`client.listTools()` after a completed handshake and before writing a byte, with the cause destroyed by `defs()`'s
`Effect.catch(() => Effect.void)`; and the harness's own instruments are now demonstrated good on the same device,
same runtime, same route and same fixture. Fourteen of Phase 5's fifteen gates are green on the CI emulator with
nothing skipped, and the fifteenth is an upstream-client question, filed as such in §5 rather than papered over.

### Reading CI output from this sandbox (why run 65 has no log here)

The Actions console is not reachable from the sandbox this work is driven from: `api.github.com`
works, but every log/artifact body is served through `results-receiver.actions.githubusercontent.com`
and `productionresultssa*.blob.core.windows.net`, and both time out here. Verified on run 65 with
`gh run view --log-failed`, `gh api actions/jobs/<id>/logs` and `gh api actions/artifacts/9755821552/zip`
(all three failed to fetch the blob; the artifact itself, 111 MB, exists and is downloadable from a
browser). The check-run annotations API answers only `Process completed with exit code 1`.

So the repo is the log channel (commit `a132418`):

- `phase4/scripts/00-run-phase4.sh` now publishes a failure digest - the error-grep plus a 500-line log
  tail - as `docs/progress/ci-failure-digest/` on the branch under test, on any failing step, only inside
  Actions and only on failure.
- `phase5/scripts/00-run-phase5.sh` already committed its evidence on every exit path, including a failed
  build; `phase5/CI_GRADLE_ONLY` = `1` additionally selects a fast standalone mode (compile the app +
  androidTest APK, run the JVM unit tests, no payload build / emulator / gates) so a compiler error comes
  back in ~10 min per push instead of a full run. **This marker is a bring-up knob, not a verdict: a run
  in that mode produces no device evidence and says so in its summary.** It must be `0` for any Phase 5
  gate claim in this report to have evidence behind it.

User-side steps, current state: the `phase5-integration` workflow **is registered** (id
`346561927`) after the earlier directory-name mistake was fixed, and every push to this branch
triggers a full run - runs #11-#13 were all triggered that way, so the trigger itself no longer
needs the user. Still open: (a) the `phase4-runtime-host` dispatch (option 2, chosen for its
proven emulator plumbing plus a full Phase 4 regression on the same device) cannot be started
from here - `POST .../workflows/phase4-runtime-host/dispatches` returns **403 Resource not
accessible by integration**, re-verified this session - so it needs a browser click; and (b) the
hung runs #1-#4 are still listed as in-progress and want cancelling, which is also a user-side
action. Both are convenience, not blockers: the push trigger works.

