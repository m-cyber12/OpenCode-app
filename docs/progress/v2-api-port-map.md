# v1 → v2 API port map (Phase 4 working document)

Source: OpenCode v2.2.5 (`packages/protocol/src/groups/*.ts`, `packages/schema/src/event*.ts`). Each row is a call the app makes today (`client/OpenCodeApi.kt`), with its v2 equivalent. "TBD" = no direct equivalent found yet; decide before porting.

## Status (2026-11-09; the first bullet was written 2026-11-07)

- Slice 1 done in code (`client/v2/`, `OpenCodeApi.kt`, `OpenCodeEventStream.kt`): connect, sessions, prompt, interrupt, history, models, active status, text and reasoning stream, errors. JVM tests in `OpenCodeApiHttpTest` and `v2/V2TranslatorTest`.
- Slice 2 done in code (2026-11-09): permissions (list and reply), forms as questions (list, reply, reject).
- Slice 3 done in code (2026-11-09): MCP list (`GET /api/mcp`), add (`PUT /api/experimental/mcp/:server`, v1 `enabled` becomes v2 `disabled`), connect and disconnect; file list (`GET /api/fs/list`) and file read (`GET /api/fs/read/*`, text). JVM tests in `OpenCodeApiHttpTest`.
- Slice 4 done in code (2026-11-09): agent tool calls, live and in history. `session.tool.input.started|ended`, `session.tool.called|progress|success|failed` become one v1 tool part per call (id `messageID:tool:callID`); an assistant `tool` item in history maps to the same part. A later frame with no name keeps the name (`Transcript`).
- Slice 5 done in code (2026-11-12, option B): config write. The app edits `opencode.json` (or `opencode.jsonc`) in the global config folder itself, keeping comments (`JsoncEdit`), checks the result parses, writes atomically, then `POST /api/location/reload`. `mcp` writes go to `mcp.servers`; `permission` writes go to the v2 `permissions` rule list (v1 `bash` is `shell`; last rule wins). `globalConfig()` reads the rule list back as the v1 policy map. Only `mcp` and `permission` keys are written; others return 501. Limit: a whole `mcp` value is replaced, so comments inside that block are lost. JVM tests: `JsoncEditTest` and the config tests in `OpenCodeApiHttpTest`.
- Not ported (throw status 501): revert, unrevert, shell command, provider credentials. Empty for now: todos.
- Config write: v2 `PATCH /api/experimental/config` accepts only `{shell}` (`Config.Patch`). Decided 2026-11-12 (owner, option B): comment-preserving edit of the file by the app, then reload. See slice 5 above.
- Shell: v2 `POST /api/session/:id/shell` returns 204; output arrives as `session.shell.started|ended` (durable) and as `shell` items in history. Map to a tool part named `shell`. Not yet done.
- Revert: v2 is two steps (`revert/stage` with `messageID`, `files`; then `revert/commit`); clear is `DELETE /api/session/:id/revert`. Redo depends on the staged marker, so the semantics need a test before porting. Not yet done.
- Event stream: `/api/event` (no `directory` scope); v2 sends no heartbeat, so the silence budget is 120 s.

## Status of the runtime (done)

- Server starts on Node 26 with the v2 bundle; health on `GET /api/info` (body has `"version"`). Verified on device 2026-11-06 (`version 2.2.5`, healthy in ~4 s after start).
- The UI still shows "Offline" and "Event stream: reconnecting" because the app calls v1 routes. Expected until this port lands.

## Calls

| App call (v1) | v2 equivalent | Notes |
|---|---|---|
| `GET /global/health` | `GET /api/info` | Done in the runtime (`ServerEngine.V2`). |
| `GET /global/event` (SSE) | `GET /api/event` (SSE, optional `types` filter) | Event envelope differs (`id`, `created`, `type`, `data`, `location`). Parser rewrite. |
| `GET /global/config` | `GET /api/config` | |
| `PATCH` config | `PATCH /api/experimental/config` | Experimental. |
| `POST /global/dispose` | TBD | v2 emits `global.disposed`; no route found. |
| `GET /provider` | `GET /api/provider`, `GET /api/provider/:providerID` | |
| `GET /api/model` (used by sweep) | `GET /api/model`, `GET /api/model/default` | Returns `id`, `providerID`, `enabled`, `status`. |
| `GET /session` | `GET /api/session` | |
| `POST /session` | `POST /api/session` | Body `model: {id, providerID}`; returns `data.id`. |
| `GET/PATCH/DELETE /session/:id` | `GET /api/session/:id`, `PATCH /api/session/:id`, `DELETE /api/session/:id` | |
| `GET /session/status` | `GET /api/session/active` | Check shape. |
| `GET /session/:id/message` | `GET /api/session/:id/message` | Returns `{data, cursor}`. Message shape differs (see below). |
| `POST /session/:id/prompt_async` | `POST /api/session/:id/prompt` | Body `{text}`. Wait with `POST /api/experimental/session/:id/wait` (204). |
| `POST /session/:id/abort` | `POST /api/session/:id/interrupt` | |
| `POST /session/:id/shell` | `POST /api/session/:id/shell` | Shell tool is `shell` in v2 (was `bash`). |
| `POST /session/:id/revert` | `POST /api/session/:id/revert/stage` then `/revert/commit` | Two steps in v2. |
| `POST /session/:id/unrevert` | `DELETE /api/session/:id/revert` | "clear". |
| `POST /session/:id/summarize` | `POST /api/session/:id/compact` | |
| `GET /session/:id/todo` | TBD | No todo group found in v2 protocol. Slice 1: empty list. |
| `GET /permission` | `GET /api/permission/request` | |
| `POST /permission/:id/reply` | `POST /api/session/:id/permission/:requestID/reply` | Event `permission.asked` / `permission.replied`. |
| `GET /question` | `GET /api/session/:id/form` | v2 "form" replaces question (`form.created`, `form.replied`, `form.cancelled`). |
| `POST /question/:id/reply` / `reject` | `POST /api/session/:id/form/:formID/reply` / `DELETE .../form/:formID` | |
| `PUT /auth/:providerID` | `POST /api/credential` (+ `/activate`) | Keystore-held credentials stay in the app. |
| `GET /mcp` | `GET /api/mcp` | |
| `POST /mcp/:name/connect` | `POST /api/experimental/mcp/:server/connect` | |
| `POST /mcp/:name/disconnect` | `POST /api/experimental/mcp/:server/disconnect` | |
| (MCP add, Settings) | `PUT /api/experimental/mcp/:server` | Add or update a server. |
| `GET /file` | `GET /api/fs/list` | |
| `GET /file/content` | `GET /api/fs/read/*` | |
| (find) | `GET /api/fs/find` | |

## Events the chat depends on (v2 names)

- Text: `session.text.started`, `session.text.delta`, `session.text.ended`
- Reasoning: `session.reasoning.started|delta|ended`
- Tools: `session.tool.input.started|delta|ended`, `session.tool.called`, `session.tool.failed`
- Steps: `session.step.started|streamed|ended|failed`
- Turn state: `session.execution.started|succeeded|failed|interrupted`, `session.idle`, `session.status`
- Messages: `session.message.content.updated`
- Permissions and forms: `permission.asked|replied`, `form.created|replied|cancelled`
- Errors and retries: `session.execution.failed`, `session.retry.scheduled`
- MCP: `mcp.status.changed`, `mcp.tools.changed`, `mcp.resources.changed`

## Message parts (v2 `session-message.ts`, part types)

`text`, `reasoning`, `tool`, `file`, `patch`, `step-start`, `step-finish`, `subtask`, `agent`, `compaction`, `retry`. The chat screen must map these to its existing render types. TBD: confirm each field name against `packages/schema/src/session-message.ts` before writing the parser.

## Not in v2 (decide per feature)

- v1 plugins: not supported (accepted 2026-11-05).
- v1 `/mcp` config write path: the live server uses `PUT /api/experimental/mcp/:server`; the persisted copy is written to `mcp.servers` in the config file (slice 5).
- Acceptance and background-task features (P1-11, P1-12): they read files and run prompts, so they depend only on prompt and events.

## Next steps

1. Read `session-message.ts` and the `session.*` event payloads; write the Kotlin data classes.
2. Replace the event parser (`/global/event`) with `/api/event`; keep the reconnect loop.
3. Port `OpenCodeApi.kt` calls in the order: info, config, model, session list/create, prompt, message, events, permissions, forms, MCP, files.
4. JVM tests with recorded v2 JSON samples; device check via Diagnostics.
