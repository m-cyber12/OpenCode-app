#!/usr/bin/env python3
"""Guard against the one Kotlin lexical trap a bracket-balance review misses:
Kotlin block comments NEST, so prose like "the /proc/net/* tables" inside a KDoc
opens a second comment and silently swallows the rest of the file (this cost a
full CI cycle in phase 5: 9 unresolved-reference errors from one stray `/*`).

Usage: python3 phase5/scripts/check-kotlin-comments.py [root ...]
Exits 1 if any scanned .kt/.kts file ends with an open comment, and lists every
nested `/*` it saw inside a comment body so it can be reworded (e.g. "proc-net").
"""
import os
import sys

EXCLUDE = {"out", "build", "node_modules", ".gradle", ".git"}


def scan(path):
    src = open(path, encoding="utf-8", errors="replace").read()
    i, n, line, depth = 0, len(src), 1, 0
    state, nested = None, []
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
        if state == "line":
            if c == "\n":
                state = None
        elif state == "block":
            if src.startswith("*/", i):
                depth -= 1
                state = "block" if depth else None
                i += 2
                continue
            if src.startswith("/*", i):
                depth += 1
                nested.append((line, "nested /* inside a block comment"))
                i += 2
                continue
        elif state == "tdq":
            if src.startswith('"""', i):
                state = None
                i += 3
                continue
        elif state == "dq":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = None
            elif c == "\n":
                state = None  # unterminated string literal is the compiler's problem
        elif state == "sq":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = None
        else:
            if src.startswith("//", i):
                state = "line"
                i += 2
                continue
            if src.startswith("/*", i):
                depth, state = 1, "block"
                i += 2
                continue
            if src.startswith('"""', i):
                state = "tdq"
                i += 3
                continue
            if c == '"':
                state = "dq"
            elif c == "'":
                state = "sq"
        i += 1
    return depth, nested


def main():
    roots = sys.argv[1:] or ["app/src"]
    files = []
    for r in roots:
        for dp, dn, fn in os.walk(r):
            dn[:] = [d for d in dn if d not in EXCLUDE]
            files += [os.path.join(dp, f) for f in fn if f.endswith((".kt", ".kts"))]
    bad = 0
    for p in sorted(files):
        depth, nested = scan(p)
        if depth or nested:
            bad += 1
            where = ", ".join(f"line {l} ({m})" for l, m in nested[:4]) or "unclosed /*"
            print(f"FAIL {p}: block-comment depth {depth} at EOF; {where}")
    print(f"checked {len(files)} kotlin files, {bad} with block-comment anomalies")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
