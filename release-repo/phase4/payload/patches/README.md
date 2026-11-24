# Payload patches (applied to the pinned upstream OpenCode checkout)

- `apply-noexec-shell.py` (run by `phase4/scripts/10-build-payload.sh` right after the pinned commit is checked out). It copies `noexec-shell.ts` into `packages/opencode/src/tool/` and makes two exact-text edits to `shell.ts`. It exits 1 if the anchors are not found exactly once, so the build stops instead of shipping the bug.
- `noexec-shell.ts`: before a bash-tool command runs, a path-like program that the kernel refuses to execute (`access(X_OK)` gives `EACCES`, typical for shared-storage folders on Android) is rewritten to run through its interpreter (`node`, `python3` or `sh`, from the shebang or the extension). Executable programs are never changed.
- `noexec-shell.test.mjs`: host test (12 cases) against the same tree-sitter parser the pinned upstream uses. Run it with `NOEXEC_TEST_DEPS=<dir with web-tree-sitter@0.25.10 and tree-sitter-bash@0.25.0> node --test phase4/payload/patches/noexec-shell.test.mjs` (Node 22.18 or newer).

Why: owner, 2026-10-31: "all shims should work in every path". The kernel refuses execve on noexec mounts, so the only fix is to avoid executing the file directly. The payload version is bumped (`PAYLOAD_VERSION=8`) so installed apps re-extract the patched bundle.

## Config guard (payload 9)

- `apply-permission-guard.py` (run by `10-build-payload.sh` after the noexec patch). It copies `permission-guard.ts` into `packages/opencode/src/permission/` and makes four exact-text edits to `index.ts`. It exits 1 if any anchor is not found exactly once.
- `permission-guard.ts`: upstream's session approval ("allow for session", reply `always`) is checked after the configured rules, so one approved `edit *` (what the edit tool sends) or one shell prefix such as `cat *` silently allowed later writes to `opencode.json`, `.mcp.json` and `.opencode/`. For requests naming those files (permissions `edit`, `bash`, `external_directory`), session approvals are ignored and `always` approves only that request. Textual limits are stated in the file.
- `permission-guard.test.ts`: behaviour test (8 cases) using upstream's own permission test harness. Run it in a checked-out upstream tree after the patch: copy it to `packages/opencode/test/permission/config-guard.test.ts` and run `bun test test/permission`. Verified on 2026-10-31 with Bun 1.3.14: 114 pass with the guard (the full upstream permission suite plus these 8). Without the guard, 6 of the 7 guard cases that protect config files fail. It is not run by CI.

## Launcher secret handling (payload 9)

- `../launcher.js`: the server password arrives on stdin (the host writes one line, then closes the pipe). An environment value is still accepted for the gate scripts. The launcher hides the password from the environment that its children inherit.
- `../launcher.test.mjs`: runs the real launcher under Bun 1.3.14 with a fake server bundle. Run it with `LAUNCHER_TEST_BUN=<bun> node --test phase4/payload/launcher.test.mjs`. It is not run by CI.
