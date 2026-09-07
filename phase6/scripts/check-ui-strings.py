#!/usr/bin/env python3
"""Phase 6 static check: user-visible copy comes from resources, not literals.

Two rules, both cheap to get wrong by hand and both explicitly required by the
Phase 6 brief:

  1. No hardcoded user-visible text in the UI layer. Every `Text("...")`,
     `contentDescription = "..."`, `label`/`placeholder` literal under
     app/src/main/java/ai/opencode/android/ui must come from strings.xml via
     stringResource(...). Literals that are protocol values ("text", "tool",
     "once"), test tags or map keys stay in code - they are not copy.
  2. No non-loopback URL literal anywhere in app/src/main/java. This is the same
     property Phase 5's P5-G19 gate asserts on the built APK; checking the source
     locally means a Phase 6 screen cannot regress it (the app talks only to the
     OpenCode server it runs on 127.0.0.1).

Usage: python3 phase6/scripts/check-ui-strings.py [app/src/main/java]
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ktscan  # noqa: E402

UI_SUBDIR = os.path.join("ai", "opencode", "android", "ui")

# Contexts in which a string literal IS user-visible copy.
COPY_CONTEXTS = [
    re.compile(r"\bText\(\s*$"),
    re.compile(r"\bText\(\s*text\s*=\s*$"),
    re.compile(r"\bcontentDescription\s*=\s*$"),
    re.compile(r"\bplaceholder\s*=\s*\{?\s*Text\(\s*$"),
    re.compile(r"\blabel\s*=\s*\{?\s*Text\(\s*$"),
    re.compile(r"\bsetText\(\s*$"),
    re.compile(r"\bheadlined?\s*=\s*$"),
]
# Contexts where a literal is fine (not copy).
ALLOWED_CONTEXTS = [
    re.compile(r"\btestTag\(\s*$"),
    re.compile(r"\btag\s*=\s*$"),
    re.compile(r"\bLog\.[vdiew]\([^)]*$"),
    re.compile(r"\bprintln\(\s*$"),
    re.compile(r"\brequire\([^)]*$"),
]

URL_RE = re.compile(r"https?://[A-Za-z0-9._\-]+")
URL_ALLOW = re.compile(r"127\.0\.0\.1|localhost|::1|example\.|opencode\.ai/config|schemas|://host")


def context_before(code, offset, width=80):
    return code[max(0, offset - width):offset].rstrip()


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join("app", "src", "main", "java")
    files = ktscan.walk([root], (".kt",))
    copy_fails, url_fails = [], []
    for path in files:
        src = open(path, encoding="utf-8", errors="replace").read()
        code = ktscan.code_text_of_source(src)
        is_ui = UI_SUBDIR in path.replace(os.sep, "/")
        for offset, line, kind, value in ktscan.string_literals(src):
            if kind == ktscan.CHAR:
                continue
            ctx = context_before(code, offset)
            if URL_RE.search(value) and not URL_ALLOW.search(value):
                url_fails.append("%s:%d url literal %r" % (path, line, value[:80]))
            if not is_ui:
                continue
            if any(r.search(ctx) for r in ALLOWED_CONTEXTS):
                continue
            if any(r.search(ctx) for r in COPY_CONTEXTS):
                copy_fails.append("%s:%d hardcoded copy %r (use stringResource)" % (path, line, value[:70]))
    for f in copy_fails:
        print("FAIL " + f)
    for f in url_fails:
        print("FAIL " + f)
    print("check-ui-strings: %d kotlin files, %d hardcoded-copy findings, %d url findings"
          % (len(files), len(copy_fails), len(url_fails)))
    return 1 if (copy_fails or url_fails) else 0


if __name__ == "__main__":
    sys.exit(main())
