#!/usr/bin/env python3
"""TEST ONLY. Writes the test launcher: the release launcher plus test-launcher-block.mjs.

Usage: test-launcher-insert.py <launcher-v2.mjs> <test-launcher-block.mjs> <out.mjs>

Two exact-text anchors, each required to occur once; otherwise exit 1 (fail loud, as the
patch scripts do). The block goes before the entry-point check, and its call goes just
before the OpenCode bundle import, so the env and the stub are ready before the server loads.
"""
import sys

ENTRY_ANCHOR = "const isMain = "
CALL_ANCHOR = '  log("importing OpenCode bundle")\n'
CALL_LINE = "  await startTestStub()\n"


def fail(msg: str) -> None:
    print(f"FATAL test launcher: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 4:
        fail("usage: test-launcher-insert.py <launcher> <block> <out>")
    launcher_path, block_path, out_path = sys.argv[1:4]
    with open(launcher_path, encoding="utf-8") as fh:
        text = fh.read()
    with open(block_path, encoding="utf-8") as fh:
        block = fh.read()
    if "startTestStub" in text:
        fail("launcher already has the test block")
    for label, anchor in (("entry anchor", ENTRY_ANCHOR), ("call anchor", CALL_ANCHOR)):
        count = text.count(anchor)
        if count != 1:
            fail(f"{label} found {count} times in {launcher_path} (need exactly 1); upstream moved")
    text = text.replace(ENTRY_ANCHOR, block + "\n" + ENTRY_ANCHOR, 1)
    text = text.replace(CALL_ANCHOR, CALL_ANCHOR + CALL_LINE, 1)
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write(text)
    print(f"test launcher written to {out_path}")


if __name__ == "__main__":
    main()
