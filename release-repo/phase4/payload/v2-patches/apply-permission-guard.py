#!/usr/bin/env python3
"""Apply the config guard to a pinned OpenCode v2 checkout.

Usage: apply-permission-guard.py <v2-root>

Copies permission-guard.ts next to the v2 permission service and wires it in with
three exact-text edits to packages/core/src/permission.ts. Every anchor must occur
exactly once; otherwise the script exits 1 and the bundle build stops (fail loud:
a silent miss would leave config writes unguarded).

See permission-guard.ts for what is guarded and the limits.
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CORE_DIR = os.path.join("packages", "core", "src")
PERMISSION_FILE = os.path.join(CORE_DIR, "permission.ts")
GUARD_FILE = os.path.join(CORE_DIR, "permission-guard.ts")

IMPORT_ANCHOR = 'import { PermissionSaved } from "./permission/saved.js"\n'
IMPORT_LINE = IMPORT_ANCHOR + 'import { isProtectedRequest } from "./permission-guard.js"\n'

# 1. The live evaluation: a protected request ignores saved approvals.
EVAL_ANCHOR = "const all = [...rules, ...(yield* savedRules())]"
EVAL_LINE = "const all = [...rules, ...(isProtectedRequest(input) ? [] : yield* savedRules())]"

# 2. "always" on a protected request approves that request only; nothing is saved.
ALWAYS_ANCHOR = 'if (input.reply === "always" && existing.request.save?.length) {'
ALWAYS_LINE = (
    'if (input.reply === "always" && existing.request.save?.length && '
    "!isProtectedRequest(existing.request)) {"
)

EDITS = [
    ("import anchor", IMPORT_ANCHOR, IMPORT_LINE),
    ("evaluation anchor", EVAL_ANCHOR, EVAL_LINE),
    ("always anchor", ALWAYS_ANCHOR, ALWAYS_LINE),
]


def fail(msg: str) -> None:
    print(f"FATAL v2 permission guard patch: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply-permission-guard.py <v2-root>")
    root = sys.argv[1]
    target = os.path.join(root, PERMISSION_FILE)
    if not os.path.isfile(target):
        fail(f"missing {target}")
    with open(target, encoding="utf-8") as fh:
        text = fh.read()

    if "isProtectedRequest" in text:
        fail("permission.ts already has the config guard; apply it to a fresh checkout")
    for label, anchor, _ in EDITS:
        count = text.count(anchor)
        if count != 1:
            fail(f"{label} found {count} times in permission.ts (need exactly 1); upstream moved")

    for _, anchor, line in EDITS:
        text = text.replace(anchor, line, 1)
    with open(target, "w", encoding="utf-8") as fh:
        fh.write(text)

    shutil.copyfile(os.path.join(HERE, "permission-guard.ts"), os.path.join(root, GUARD_FILE))
    print("v2 permission guard patch applied to", target)


if __name__ == "__main__":
    main()
