#!/usr/bin/env python3
"""Apply the noexec shell patch to a pinned upstream OpenCode checkout.

Usage: apply-noexec-shell.py <upstream-root>

Copies noexec-shell.ts next to upstream's bash tool and wires it in with two
exact-text edits to packages/opencode/src/tool/shell.ts. Every anchor must occur
exactly once; otherwise the script exits 1 and the payload build stops (fail
loud: a silent miss would ship the bug again).
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL_DIR = os.path.join("packages", "opencode", "src", "tool")

IMPORT_ANCHOR = 'import { ShellID } from "./shell/id"\n'
IMPORT_LINE = IMPORT_ANCHOR + 'import { rewriteNoexec } from "./noexec-shell"\n'

SPAWN_ANCHOR = "const handle = yield* spawner.spawn(cmd(input.shell, input.command, input.cwd, input.env))"
SPAWN_LINE = (
    "const handle = yield* spawner.spawn(cmd(input.shell, "
    "yield* Effect.promise(() => rewriteNoexec(input.command, input.cwd, "
    "(c) => parser().then((p) => p.bash.parse(c)))), input.cwd, input.env))"
)


def fail(msg: str) -> None:
    print(f"FATAL noexec patch: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply-noexec-shell.py <upstream-root>")
    root = sys.argv[1]
    shell_path = os.path.join(root, TOOL_DIR, "shell.ts")
    if not os.path.isfile(shell_path):
        fail(f"missing {shell_path}")
    with open(shell_path, encoding="utf-8") as fh:
        text = fh.read()

    for label, anchor in (("import anchor", IMPORT_ANCHOR), ("spawn anchor", SPAWN_ANCHOR)):
        count = text.count(anchor)
        if count != 1:
            fail(f"{label} found {count} times in shell.ts (need exactly 1); upstream moved")

    text = text.replace(IMPORT_ANCHOR, IMPORT_LINE, 1)
    text = text.replace(SPAWN_ANCHOR, SPAWN_LINE, 1)
    with open(shell_path, "w", encoding="utf-8") as fh:
        fh.write(text)

    shutil.copyfile(os.path.join(HERE, "noexec-shell.ts"), os.path.join(root, TOOL_DIR, "noexec-shell.ts"))
    print("noexec shell patch applied to", shell_path)


if __name__ == "__main__":
    main()
