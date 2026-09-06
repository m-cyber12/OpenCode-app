<!--
Filed on 2026-09-06 as https://github.com/anomalyco/opencode/issues/47644 (title: 'MCP: every tool-list failure
collapses to "Failed to get tools" - the underlying error is discarded'). This is the verbatim body, kept in the
repo so the Phase 5 report's §2 box and the CI evidence bundle stay readable without network access, and so the
restriction can be retired against a release note rather than against a memory.

Why a copy is worth keeping: P5-G16 is currently FAIL-by-design on this branch because of this upstream defect.
When #47644 is fixed (or when upstream at least logs the cause), re-run phase5-integration on this branch: the gate
needs no rework - it will either pass, or print an actionable cause - and the disposition box in
docs/progress/phase5-opencode-integration-report.md section 2 gets replaced by that outcome.

Note for the record: this identity can create issues upstream but cannot edit or close them, so the throwaway
probe issue #47643 ('probe', created while testing whether issue creation was permitted at all) still needs closing
by a human with triage rights on anomalyco/opencode.
-->

### Summary

When OpenCode's MCP client cannot obtain a server's tool list, the user-visible status collapses to the single string `"Failed to get tools"`, and the error that actually caused it is **discarded** — not logged anywhere, not attached to the status. This makes an entire class of MCP failures undiagnosable from the API, the CLI, or logs.

Pinned commit: `05ea5073be967c779d326929b2de6228dda4159d` (`dev`).

### Where

`packages/opencode/src/mcp/catalog.ts:38-40`

```ts
export function defs(client: Client, timeout?: number) {
  return listTools(client, timeout ?? DEFAULT_TIMEOUT).pipe(Effect.catch(() => Effect.void))
}
```

`Effect.catch(() => Effect.void)` turns *any* failure into `undefined`, with no log line.

`packages/opencode/src/mcp/index.ts:390-394`

```ts
const listed = mcpClient.getServerCapabilities()?.tools ? yield* McpCatalog.defs(mcpClient, mcp.timeout) : []
if (!listed) {
  return yield* Effect.fail(new Error("Failed to get tools"))
}
```

The thrown value is a fresh `Error` with a constant message — the original error is unreachable by then. `index.ts:890` (`authenticate`) reports the same constant. The only thing `GET /mcp` can ever show is `{"status":"failed","error":"Failed to get tools"}`.

Two related details in the same area:

- `catalog.ts:145-160` retries with `TolerantListToolsResultSchema` only when `isOutputSchemaValidationError(error)` matches (`catalog.ts:164-166`, a regex over the message). Every other shape — transport closed, capability assertion, a server 500, a body that fails to parse — skips the retry and is then swallowed by `defs()`.
- `if (!listed)` can never fire for the "server advertises no tools" branch, because that branch yields `[]`, and `![]` is `false`. So "server has no tools" is silently treated as success-with-zero-tools, while "tool list threw" is indistinguishable from "server unreachable at list time".

### Why it matters (real integration, not hypothetical)

We run OpenCode as an on-device server on Android (Bun for Android, x86_64 emulator + arm64 hardware) with a loopback-only bind, and `POST /mcp` with `{"type":"remote","url":"http://10.0.2.2:4551/mcp"}` against a spec-legal StreamableHTTP MCP server (MCP SDK 1.29.0, both response modes) behaves like this:

```
fixture (the MCP server), same run:
  [p5-mcp] streamable session initialized id=42921813-…     <- OpenCode's session
  [p5-mcp] POST /mcp -> 200 bytes=191   (initialize)
  [p5-mcp] POST /mcp -> 202            (notifications/initialized)
  ...nothing else. No tools/list request ever reaches the server.

OpenCode's API:
  POST /mcp p5-remote-http … -> 200 {"p5-remote-http":{"status":"failed","error":"Failed to get tools"}} [996ms]
```

Established by measurement, from the same device, same runtime, same route, same server:

- it is **not** a timeout: the registration returns in ~1 s, not at `DEFAULT_TIMEOUT = 30_000`;
- it is **not** response framing: `enableJsonResponse` (single `application/json` body) fails with the identical string;
- it is **not** the network or the server: a raw JSON-RPC client in the *same process* completes `initialize` → `notifications/initialized` → `tools/list` (681 bytes of tool definitions) against that same listener;
- the server-side handshake succeeded, so `connect()` resolved; the throw happens inside `client.listTools()` **before a single request byte is written**.

What we can say is exactly: *upstream's client threw between a completed handshake and issuing `tools/list`.* What we cannot say — because of the two snippets above — is **what it threw**. No OpenCode log line accompanies it (`opencode-server.log` for that run contains no entry for this MCP name at all), so from outside the project this is unreproducible-by-report: we can only say OpenCode returned a constant string.

### Proposed fix (small, no behaviour change for healthy servers)

Preserve the cause. Either of:

```ts
// catalog.ts — keep the fallback semantics, log the reason
return listTools(client, timeout ?? DEFAULT_TIMEOUT).pipe(
  Effect.tapError((error) => Effect.logWarning("mcp tools/list failed", { error: String(error) })),
  Effect.catch(() => Effect.void),
)

// index.ts — keep the message, add the cause
if (!listed) {
  return yield* Effect.fail(new Error(`Failed to get tools (see server log for the underlying MCP error)`))
}
```

Better still: store the underlying message in the `Status` (`{status:"failed", error}` already exists — put the real message there, and keep `Failed to get tools` as a prefix if some consumer depends on it). Same for `index.ts:890`. And since `Status.error` is what every UI, `opencode mcp list`, and third-party client shows, this is the difference between "our MCP server is broken, here's why" and a string that could mean five different things.

### Acceptance

`GET /mcp` (or the CLI) for a server whose `tools/list` fails must contain text that distinguishes at least: transport not connected / closed, method not supported by the server, response failed schema validation, server error response, and timeout — and a matching log line must exist with the original error (ideally `error.cause` too).

### Note

This is blocking a downstream integration gate for us (we must not modify OpenCode's server API from our side, so we can only document it as a platform restriction). Happy to test a patch on the Android/Bun-for-Android configuration and report whether the message becomes actionable. If an upstream fix lands, we drop our workaround and link the release here.
