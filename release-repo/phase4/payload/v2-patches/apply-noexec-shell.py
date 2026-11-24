#!/usr/bin/env python3
"""Apply the no-exec shell patch to a pinned OpenCode v2 checkout.

Usage: apply-noexec-shell.py <v2-root>

Copies noexec-shell.ts next to the v2 shell tool and makes two exact-text edits to
packages/core/src/tool/plugin/shell.ts: an import, and one call placed after the
permission check and before the command is spawned. Every anchor must occur exactly
once; otherwise the script exits 1 and the bundle build stops.

See noexec-shell.ts for what is rewritten and the limits.
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL_DIR = os.path.join("packages", "core", "src", "tool", "plugin")
SHELL_FILE = os.path.join(TOOL_DIR, "shell.ts")

IMPORT_ANCHOR = 'import { ShellResult } from "../../shell/result.js"\n'
IMPORT_LINE = (
    IMPORT_ANCHOR
    + 'import { ShellScan } from "../../shell/scan.js"\n'
    + 'import { rewriteNoexec } from "./noexec-shell.js"\n'
)

# Runs in prepare(), after permission.assert and before the spawn reads invocation.command.
SPAWN_ANCHOR = "// Approval can outlive the directory, so validate immediately before spawning."
SPAWN_LINE = (
    'invocation.command = rewriteNoexec(invocation.command, invocation.cwd, (c) => ShellScan.scan(c, "posix"))\n'
    "      " + SPAWN_ANCHOR
)

EDITS = [
    ("import anchor", IMPORT_ANCHOR, IMPORT_LINE),
    ("spawn anchor", SPAWN_ANCHOR, SPAWN_LINE),
]


def fail(msg: str) -> None:
    print(f"FATAL v2 noexec patch: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply-noexec-shell.py <v2-root>")
    root = sys.argv[1]
    target = os.path.join(root, SHELL_FILE)
    if not os.path.isfile(target):
        fail(f"missing {target}")
    with open(target, encoding="utf-8") as fh:
        text = fh.read()

    # The spawn anchor survives the edit (the new line ends with it), so a second run would
    # match again. Refuse an already-patched tree explicitly.
    if "rewriteNoexec" in text:
        fail("shell.ts already has the no-exec patch; apply it to a fresh checkout")
    for label, anchor, _ in EDITS:
        count = text.count(anchor)
        if count != 1:
            fail(f"{label} found {count} times in shell.ts (need exactly 1); upstream moved")

    for _, anchor, line in EDITS:
        text = text.replace(anchor, line, 1)
    with open(target, "w", encoding="utf-8") as fh:
        fh.write(text)

    shutil.copyfile(os.path.join(HERE, "noexec-shell.ts"), os.path.join(root, TOOL_DIR, "noexec-shell.ts"))
    print("v2 noexec shell patch applied to", target)


if __name__ == "__main__":
    main()
