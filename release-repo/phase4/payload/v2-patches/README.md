# OpenCode v2 patches (migration plan, Phase 2)

Patches applied to the pinned OpenCode v2 source (`v2.2.5`) by `spike/opencode-v2/build-v2-bundle.sh`
before the Node bundle is built. They port the v1 invariants in `phase4/payload/patches/` to v2.

| Plan item | Patch | Applied to (v2 source) |
|---|---|---|
| 4. Permission guard | `apply-permission-guard.py` + `permission-guard.ts` | `packages/core/src/permission.ts` (3 exact-text edits) |
| 5. No-exec shell | `apply-noexec-shell.py` + `noexec-shell.ts` | `packages/core/src/tool/plugin/shell.ts` (2 exact-text edits) |
| 6. Config writes prompt even when shell is session-allowed | covered by the guard (`shell` with a config token is protected) | same |
| 7. Tool-argument stream | `apply-tool-input-delta.py` | `packages/core/src/session/runner/publish-llm-event.ts` (1 exact-text insertion) |

## What each one does

- **Permission guard.** For `edit`, `shell` and `external_directory` requests whose resource names a config file
  (`opencode.json`, `opencode.jsonc`, `.mcp.json`, `.opencode`, `config/opencode`), saved "always" approvals are not
  used, and an "always" approves only that request. v2 evaluates saved allow rules after the configured rules, so
  without this one approval of `edit *` or `cat *` would allow later writes to those files.
- **No-exec shell.** Before a command is spawned (after the permission check), a path-like program that the kernel
  refuses to execute (`access(X_OK)` gives `EACCES`) is run through its interpreter: `./x.mjs` becomes
  `node ./x.mjs`. The command is found with the v2 scanner (`core/src/shell/scan.ts`). v1 used tree-sitter; v2 has no
  bash grammar in its bundle.

- **Tool-argument stream.** Upstream declares `session.tool.input.delta` but never publishes it (v2.2.5 and
  v2.3.2), so a Write or Edit reached the client only when it had finished. The patch gives the runner's existing
  argument buffer a `delta` callback, so each batch streams as `Tool.Input.Delta` (ephemeral). `Tool.Input.Ended`
  still carries the full text.

## Limits (same as v1, plus one)

- The shell check is textual. A command that writes a config file without naming it is not caught.
- A symlink to a config file is not caught by the edit check.
- The no-exec rewrite leaves a command unchanged when the scanner cannot read it (heredocs, opaque constructs).
  Permission still applies to the original command.

## Tests

- `test/permission-guard.test.ts`: pure, no dependencies.
- `test/noexec-shell.test.ts`: real files (mode 0644 and a shebang) and the real v2 scanner. The scanner file
  starts with a self re-export that Node cannot load, so the build passes a copy without that line
  (`V2_SHELL_SCAN`).

Run them on Node 22+: `node --test --experimental-strip-types phase4/payload/v2-patches/test/*.test.ts`.
The build script runs them after patching. Both apply scripts were checked on 2026-11-13 against the real
`v2.2.5` `permission.ts` and `shell.ts` (each anchor found once).

## Checked for the tool-argument stream (2026-11-21)

- Baseline, unpatched `v2.2.5` server (run from source, scripted model streaming one `write` call's arguments
  in 16-character pieces, 120 ms apart): `session.tool.input.started` at 1.5 s, then no tool event until
  `session.tool.input.ended` at 11.5 s. Zero `session.tool.input.delta`.
- Same server with this patch: 83 `session.tool.input.delta` events between 5.3 s and 11.7 s, in order, then
  `ended` with the full 1316-character text, `called`, `success`. The file the tool wrote has all 30 lines.
- The app's partial-JSON reader (`ToolInputPreview`, ported to Python) gives a true prefix of the content for
  every one of the 1320 prefixes of that argument text, and never shrinks.
- Not run: the bundle build (`build-node.ts --bundle-only` was killed at 3.9 GB RAM here) and the phone.

## Not yet proven

- Neither patch has been run on a device. The owner's device check is the Diagnostics probe for each invariant
  (plan Phase 2 gate).
- The permission flow itself (ask, reply, saved rules) is not covered by a test here. v1 had an upstream-harness test
  (`phase4/payload/patches/permission-guard.test.ts`). A v2 equivalent needs the v2 test harness, which is not wired yet.
