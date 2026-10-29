#!/usr/bin/env python3
"""scrub-evidence.py - hide the application id from evidence text before it is
committed (owner, 2026-10-29: hide the id from scripts and their output).

The id is read from app/build.gradle.kts, the one source of truth, and every
occurrence in a text file under the given directories is replaced with <app>.

Skipped on purpose:
  * binary files (any NUL byte in the first 8 KiB) and archive chunks, so a
    compressed Node cache is never edited;
  * node-cache/ directories (those are binary);
  * the honesty reports under docs/progress/*.md, which are history and are
    appended to, never rewritten.

Usage:  scrub-evidence.py ROOT_DIR [DIR ...]
Exit 0 always after reporting; a missing id in build.gradle.kts is an error.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
GRADLE = os.path.join(REPO, "app", "build.gradle.kts")
PLACEHOLDER = "<app>"


def app_id():
    with open(GRADLE, encoding="utf-8") as f:
        m = re.search(r'applicationId = "([^"]+)"', f.read())
    if not m:
        raise SystemExit("scrub-evidence: applicationId not found in app/build.gradle.kts")
    return m.group(1)


def is_text(path):
    with open(path, "rb") as f:
        head = f.read(8192)
    return b"\x00" not in head


def scrub_tree(root, needle):
    changed = files = 0
    for dirpath, dirnames, filenames in os.walk(root):
        if "node-cache" in dirpath.split(os.sep):
            continue
        for name in filenames:
            path = os.path.join(dirpath, name)
            if name.endswith((".tgz", ".gz", ".zip", ".apk", ".aab", ".png", ".jpg")):
                continue
            if not is_text(path):
                continue
            with open(path, encoding="utf-8", errors="surrogateescape") as f:
                text = f.read()
            if needle not in text:
                continue
            files += 1
            with open(path, "w", encoding="utf-8", errors="surrogateescape", newline="") as f:
                f.write(text.replace(needle, PLACEHOLDER))
            changed += 1
    return files, changed


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    needle = app_id()
    for root in argv[1:]:
        if not os.path.isdir(root):
            continue
        files, changed = scrub_tree(root, needle)
        print("scrub-evidence: %s: %d file(s) scrubbed" % (root, changed))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
