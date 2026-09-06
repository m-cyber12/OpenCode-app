#!/usr/bin/env python3
"""Phase 6 static check: CI-facing files this phase owns are ASCII-only.

Recurring operational note for this project: workflow files must be ASCII, and any
step `name:` containing ": " must be quoted. A YAML parse rejection already cost
this project a run in Phase 3, so new CI content is checked rather than
remembered. Files earlier phases committed are reported as notes only (phase2's
workflow carries a UTF-8 arrow in a comment and has run fine; rewriting another
phase's committed file is out of Phase 6 scope).

Usage: python3 phase6/scripts/check-ascii.py [repo-root]
"""
import os
import sys

SUFFIXES = (".yml", ".yaml", ".sh")
STRICT_DIRS = ("phase6",)
NOTE_DIRS = (".github", "phase5/workflow", "phase4/workflow", "phase3/workflow", "spike/workflow")
SKIP_DIRS = {"out", "build", ".git", "node_modules"}


def collect(root, bases):
    paths = []
    for base in bases:
        d = os.path.join(root, base)
        if not os.path.isdir(d):
            continue
        for dp, dn, fn in os.walk(d):
            dn[:] = [x for x in dn if x not in SKIP_DIRS]
            for f in fn:
                if f.endswith(SUFFIXES):
                    paths.append(os.path.join(dp, f))
    return sorted(paths)


def scan(p):
    data = open(p, "rb").read()
    bad = None
    for idx, byte in enumerate(data):
        if byte > 127:
            bad = data.count(b"\n", 0, idx) + 1
            break
    return bad, (b"\t" in data and p.endswith((".yml", ".yaml")))


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    strict = collect(root, STRICT_DIRS)
    notes = collect(root, NOTE_DIRS)
    bad = 0
    for p in strict:
        line, tab = scan(p)
        if line:
            print("FAIL non-ASCII byte in %s at line %d" % (os.path.relpath(p, root), line))
            bad = 1
        if tab:
            print("FAIL tab character in YAML %s (YAML forbids tabs for indentation)" % os.path.relpath(p, root))
            bad = 1
    for p in notes:
        line, _ = scan(p)
        if line:
            print("note: pre-existing non-ASCII byte in %s at line %d (not this phase's file)"
                  % (os.path.relpath(p, root), line))
    print("check-ascii: %d phase6 files (strict), %d pre-existing (note only), %d findings"
          % (len(strict), len(notes), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
