#!/usr/bin/env python3
"""Phase 6 static check: accessibility is a requirement, not polish.

The Phase 5 UI audit found zero contentDescription / semantics anywhere in the
app, i.e. TalkBack had nothing to read. This check makes that unrepeatable
without a compiler:

  * every Icon( / Image( call must pass a contentDescription (and not null,
    unless the call also clears semantics - a genuinely decorative image),
  * every IconButton / Button / TextButton / OutlinedButton / FilledTonalButton
    must expose an accessible name: a Text( or Icon( child, or an explicit
    contentDescription,
  * every TextField / OutlinedTextField must have a label or a placeholder.

Usage: python3 phase6/scripts/check-ui-a11y.py [app/src/main/java/.../ui]
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ktscan  # noqa: E402

ICON_CALLS = ["Icon", "Image"]
BUTTON_CALLS = ["IconButton", "Button", "TextButton", "OutlinedButton", "FilledTonalButton", "ElevatedButton"]
FIELD_CALLS = ["TextField", "OutlinedTextField"]


def trailing_lambda(code, close_offset):
    """If a `)` is followed by a trailing lambda, return its body too."""
    j = close_offset
    while j < len(code) and code[j] in " \n\r\t":
        j += 1
    if j < len(code) and code[j] == "{":
        depth, k = 1, j + 1
        while k < len(code) and depth:
            if code[k] == "{":
                depth += 1
            elif code[k] == "}":
                depth -= 1
            k += 1
        return code[close_offset:k]
    return ""


def line_of(code, offset):
    return code.count("\n", 0, offset) + 1


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        "app", "src", "main", "java", "ai", "opencode", "android", "ui")
    files = ktscan.walk([root], (".kt",))
    fails = []
    counts = {"icon": 0, "button": 0, "field": 0}
    for path in files:
        src = open(path, encoding="utf-8", errors="replace").read()
        code = ktscan.code_text_of_source(src)
        for name in ICON_CALLS:
            for start, a0, a1 in ktscan.call_spans(code, name):
                counts["icon"] += 1
                args = code[a0:a1]
                line = line_of(code, start)
                if "contentDescription" not in args:
                    fails.append("%s:%d %s(...) has no contentDescription" % (path, line, name))
                elif re.search(r"contentDescription\s*=\s*null", args) and "clearAndSetSemantics" not in args:
                    fails.append("%s:%d %s(...) sets contentDescription = null without clearing semantics"
                                 % (path, line, name))
        for name in BUTTON_CALLS:
            for start, a0, a1 in ktscan.call_spans(code, name):
                counts["button"] += 1
                args = code[a0:a1] + trailing_lambda(code, a1)
                line = line_of(code, start)
                if not re.search(r"\bText\(|\bIcon\(|contentDescription", args):
                    fails.append("%s:%d %s(...) exposes no accessible name (no Text/Icon/contentDescription)"
                                 % (path, line, name))
        for name in FIELD_CALLS:
            for start, a0, a1 in ktscan.call_spans(code, name):
                counts["field"] += 1
                args = code[a0:a1] + trailing_lambda(code, a1)
                line = line_of(code, start)
                if not re.search(r"\blabel\s*=|\bplaceholder\s*=|contentDescription", args):
                    fails.append("%s:%d %s(...) has neither label nor placeholder" % (path, line, name))
    for f in fails:
        print("FAIL " + f)
    print("check-ui-a11y: %d files, %d icon/image, %d button, %d field call sites, %d findings"
          % (len(files), counts["icon"], counts["button"], counts["field"], len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
