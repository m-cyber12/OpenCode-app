#!/usr/bin/env python3
"""Phase 6 static check: the UI layer stays a thin, pure client of the server.

The Phase 6 plan of record fences the work: "The UI stays a thin client of the
on-device server... No new state model in the UI layer... and no reimplementation
of anything OpenCode does." Two properties make that enforceable rather than
aspirational:

  1. Only ui/AppRoot.kt (and MainActivity) may touch process-level singletons
     (RuntimeManager / RuntimeService / AppContainer / RuntimePaths / SecretStore)
     or construct a repository. Every screen below it is a function of the state
     it is handed - which is also what makes the Compose UI gates deterministic:
     they render fabricated server state with no runtime, no model and no key.
  2. The markdown parser and the syntax highlighter are pure Kotlin (no
     androidx.*, no java.net), so they are covered by JVM unit tests with real
     fixtures instead of only being exercised on a device.

Usage: python3 phase6/scripts/check-ui-purity.py [app/src/main/java]
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ktscan  # noqa: E402

SINGLETONS = ["RuntimeManager", "RuntimeService", "AppContainer", "RuntimePaths", "SecretStore",
              "OpenCodeRepository(", "Diagnostics.collect"]
ALLOWED_SINGLETON_FILES = ["ui/AppRoot.kt"]

PURE_FILES = ["ui/markdown/Markdown.kt", "ui/markdown/CodeHighlight.kt"]
PURE_FORBIDDEN = re.compile(r"^\s*import\s+(androidx\.|android\.|java\.net|okhttp)", re.MULTILINE)

NETWORK_IN_UI = re.compile(r"^\s*import\s+(java\.net|okhttp|javax\.net)", re.MULTILINE)


def norm(path):
    return path.replace(os.sep, "/")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join("app", "src", "main", "java")
    files = [p for p in ktscan.walk([root], (".kt",)) if "/ui/" in norm(p)]
    fails = []
    for path in files:
        src = open(path, encoding="utf-8", errors="replace").read()
        n = norm(path)
        allowed = any(n.endswith(a) for a in ALLOWED_SINGLETON_FILES)
        if not allowed:
            for token in SINGLETONS:
                for m in re.finditer(re.escape(token), src):
                    line = src.count("\n", 0, m.start()) + 1
                    fails.append("%s:%d UI screen reaches for %s (only AppRoot may)" % (path, line, token.strip("(")))
        if NETWORK_IN_UI.search(src):
            fails.append("%s: the UI layer must not open sockets - go through the repository" % path)
        for pure in PURE_FILES:
            if n.endswith(pure):
                m = PURE_FORBIDDEN.search(src)
                if m:
                    fails.append("%s:%d must stay pure Kotlin (JVM unit-testable): %s"
                                 % (path, src.count("\n", 0, m.start()) + 1, m.group(0).strip()))
    missing = [p for p in PURE_FILES if not any(norm(f).endswith(p) for f in files)]
    for p in missing:
        fails.append("missing required pure file: %s" % p)
    for f in fails:
        print("FAIL " + f)
    print("check-ui-purity: %d ui files scanned, %d findings" % (len(files), len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
