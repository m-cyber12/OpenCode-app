#!/usr/bin/env python3
"""Phase 6 static check: lists that can grow unbounded are really lazy.

The Phase 5 audit found the concrete bug this guards against: `LazyColumn` was
imported and never used, every list rendered eagerly inside a `verticalScroll`
Column, and the session list was capped with `.take(8)` - which silently hides
sessions instead of scrolling them. Three rules:

  1. No hardcoded item cap before a render loop (`.take(N).forEach`).
  2. No eager `forEach` over a server-owned collection that can grow
     (messages / sessions / pending permissions / projects / MCP entries).
  3. The screens named in REQUIRED_LAZY must use LazyColumn + items(...), and no
     single function may put a LazyColumn inside a verticalScroll (that is the
     "infinity maximum height constraints" crash, or one silently measured item).

Usage: python3 phase6/scripts/check-ui-lists.py [app/src/main/java/.../ui]
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ktscan  # noqa: E402

REQUIRED_LAZY = [
    "ui/chat/ChatScreen.kt",
    "ui/chat/SessionPanel.kt",
    "ui/projects/ProjectsScreen.kt",
    "ui/settings/SettingsScreen.kt",
]

CAP_RE = re.compile(r"\.take\(\s*\d+\s*\)\s*\.\s*(forEach|map)\b")
EAGER_RE = re.compile(
    r"\b(messages|sessions|pending|prompts|projects|entries|servers|providers|logs|diagnostics)"
    r"\s*\.\s*forEach\s*\{")


def norm(path):
    return path.replace(os.sep, "/")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        "app", "src", "main", "java", "ai", "opencode", "android", "ui")
    files = ktscan.walk([root], (".kt",))
    fails = []
    lazy_files = 0
    for path in files:
        src = open(path, encoding="utf-8", errors="replace").read()
        code = ktscan.code_text_of_source(src)
        n = norm(path)
        for m in CAP_RE.finditer(code):
            fails.append("%s:%d hardcoded item cap before a render loop: %s"
                         % (path, code.count("\n", 0, m.start()) + 1, src[m.start():m.start() + 40].strip()))
        for m in EAGER_RE.finditer(code):
            fails.append("%s:%d eager forEach over an unbounded collection (%s) - use items() in a LazyColumn"
                         % (path, code.count("\n", 0, m.start()) + 1, m.group(1)))
        if "LazyColumn" in code:
            lazy_files += 1
            if "items(" not in code and "itemsIndexed(" not in code:
                fails.append("%s: LazyColumn without items(...) renders nothing" % path)
        for name, b0, b1 in ktscan.functions(code):
            body = code[b0:b1]
            if "LazyColumn" in body and "verticalScroll" in body:
                fails.append("%s: function %s puts a LazyColumn inside a verticalScroll "
                             "(infinite-height constraint)" % (path, name))
        for req in REQUIRED_LAZY:
            if n.endswith(req) and "LazyColumn" not in code:
                fails.append("%s: required lazy list screen has no LazyColumn" % path)
    missing = [r for r in REQUIRED_LAZY if not any(norm(p).endswith(r) for p in files)]
    for r in missing:
        fails.append("missing required screen: %s" % r)
    for f in fails:
        print("FAIL " + f)
    print("check-ui-lists: %d files, %d using LazyColumn, %d findings" % (len(files), lazy_files, len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
