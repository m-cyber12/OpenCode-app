# Payload patches (applied to the pinned upstream OpenCode checkout)

- `apply-noexec-shell.py` (run by `phase4/scripts/10-build-payload.sh` right after the pinned commit is checked out). It copies `noexec-shell.ts` into `packages/opencode/src/tool/` and makes two exact-text edits to `shell.ts`. It exits 1 if the anchors are not found exactly once, so the build stops instead of shipping the bug.
- `noexec-shell.ts`: before a bash-tool command runs, a path-like program that the kernel refuses to execute (`access(X_OK)` gives `EACCES`, typical for shared-storage folders on Android) is rewritten to run through its interpreter (`node`, `python3` or `sh`, from the shebang or the extension). Executable programs are never changed.
- `noexec-shell.test.mjs`: host test (12 cases) against the same tree-sitter parser the pinned upstream uses. Run it with `NOEXEC_TEST_DEPS=<dir with web-tree-sitter@0.25.10 and tree-sitter-bash@0.25.0> node --test phase4/payload/patches/noexec-shell.test.mjs` (Node 22.18 or newer).

Why: owner, 2026-10-31: "all shims should work in every path". The kernel refuses execve on noexec mounts, so the only fix is to avoid executing the file directly. The payload version is bumped (`PAYLOAD_VERSION=8`) so installed apps re-extract the patched bundle.
