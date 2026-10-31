#!/usr/bin/env python3
"""Apply the config-guard patch to a pinned upstream OpenCode checkout.

Usage: apply-permission-guard.py <upstream-root>

Copies permission-guard.ts next to upstream's permission service and wires it in
with four exact-text edits to packages/opencode/src/permission/index.ts. Every
anchor must occur exactly once; otherwise the script exits 1 and the payload build
stops (fail loud: a silent miss would leave config writes unguarded).

See permission-guard.ts for what is guarded and the limits.
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PERMISSION_DIR = os.path.join("packages", "opencode", "src", "permission")

IMPORT_ANCHOR = 'import { EventV2Bridge } from "@/event-v2-bridge"\n'
IMPORT_LINE = IMPORT_ANCHOR + 'import { isProtectedPattern, isProtectedRequest } from "./permission-guard"\n'

# 1. The live evaluation: a guarded pattern ignores session approvals.
EVAL_ANCHOR = "const rule = evaluate(request.permission, pattern, ruleset, approved)"
EVAL_LINE = (
    "const rule = evaluate(request.permission, pattern, ruleset, "
    "isProtectedPattern(request.permission, pattern) ? [] : approved)"
)

# 2. Pending requests that a new approval unblocks: a guarded pattern never
#    unblocks on a session approval.
RESOLVE_ANCHOR = '(pattern) => evaluate(item.info.permission, pattern, approved).action === "allow",'
RESOLVE_LINE = (
    '(pattern) => !isProtectedPattern(item.info.permission, pattern) && '
    'evaluate(item.info.permission, pattern, approved).action === "allow",'
)

# 3. "always" on a guarded request approves nothing beyond that request.
ALWAYS_ANCHOR = "for (const pattern of existing.info.always) {"
ALWAYS_LINE = "for (const pattern of isProtectedRequest(existing.info) ? [] : existing.info.always) {"

EDITS = [
    ("import anchor", IMPORT_ANCHOR, IMPORT_LINE),
    ("evaluation anchor", EVAL_ANCHOR, EVAL_LINE),
    ("resolve anchor", RESOLVE_ANCHOR, RESOLVE_LINE),
    ("always anchor", ALWAYS_ANCHOR, ALWAYS_LINE),
]


def fail(msg: str) -> None:
    print(f"FATAL permission guard patch: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply-permission-guard.py <upstream-root>")
    root = sys.argv[1]
    index_path = os.path.join(root, PERMISSION_DIR, "index.ts")
    if not os.path.isfile(index_path):
        fail(f"missing {index_path}")
    with open(index_path, encoding="utf-8") as fh:
        text = fh.read()

    for label, anchor, _ in EDITS:
        count = text.count(anchor)
        if count != 1:
            fail(f"{label} found {count} times in permission/index.ts (need exactly 1); upstream moved")

    for _, anchor, line in EDITS:
        text = text.replace(anchor, line, 1)
    with open(index_path, "w", encoding="utf-8") as fh:
        fh.write(text)

    shutil.copyfile(
        os.path.join(HERE, "permission-guard.ts"),
        os.path.join(root, PERMISSION_DIR, "permission-guard.ts"),
    )
    print("permission guard patch applied to", index_path)


if __name__ == "__main__":
    main()
