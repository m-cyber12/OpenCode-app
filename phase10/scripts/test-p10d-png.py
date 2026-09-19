#!/usr/bin/env python3
"""Self-test for p10d-png.py: the screenshot sanity check must not be vacuous.

The driver's whole point is that "the file exists" is not evidence. So the checker
itself gets tested with images whose answer is known: a black frame (an off or
locked screen) and a uniform white frame (an unpainted surface) must be REJECTED,
while a frame with a small amount of text-like content on either background must be
accepted. If this fails, the driver's screenshots mean nothing.

Usage: python3 phase10/scripts/test-p10d-png.py
"""
import os
import struct
import subprocess
import sys
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(HERE, "p10d-png.py")


def write_png(path, width, height, pixel_fn):
    """Minimal 8-bit RGBA PNG writer (no dependencies)."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter: none
        for x in range(width):
            r, g, b, a = pixel_fn(x, y)
            raw += bytes((r, g, b, a))
    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload +
                struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
                 chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


def run(path):
    out = subprocess.run([sys.executable, CHECKER, path, "--json"],
                         capture_output=True, text=True, check=True)
    import json
    return json.loads(out.stdout)


def main():
    checks = []
    with tempfile.TemporaryDirectory() as tmp:
        black = os.path.join(tmp, "black.png")
        white = os.path.join(tmp, "white.png")
        dark_ui = os.path.join(tmp, "dark-ui.png")
        light_ui = os.path.join(tmp, "light-ui.png")

        write_png(black, 240, 320, lambda x, y: (0, 0, 0, 255))
        write_png(white, 240, 320, lambda x, y: (255, 255, 255, 255))
        # a "dark theme screen": dark background, a bright row of text-ish pixels
        write_png(dark_ui, 240, 320,
                  lambda x, y: (240, 240, 240, 255) if (20 < y < 28 and 10 < x < 230) else (18, 18, 20, 255))
        # a "light theme screen": white background, dark text-ish pixels
        write_png(light_ui, 240, 320,
                  lambda x, y: (30, 30, 30, 255) if (20 < y < 28 and 10 < x < 230) else (252, 252, 252, 255))

        for name, path, expect in (
            ("a black frame is rejected", black, False),
            ("a uniform white frame is rejected", white, False),
            ("a dark-theme screen with text is accepted", dark_ui, True),
            ("a light-theme screen with text is accepted", light_ui, True),
        ):
            res = run(path)
            got = bool(res.get("looks_like_a_screen"))
            checks.append((name, got == expect, "got screen=%s (content=%.3f mean=%.1f)" % (
                got, res.get("content_fraction", -1), res.get("mean_brightness", -1))))

        # A truncated/garbage file must be reported as not decoded, never as fine.
        bad = os.path.join(tmp, "bad.png")
        with open(bad, "wb") as fh:
            fh.write(b"\x89PNG\r\n\x1a\nnot really a png")
        res = run(bad)
        checks.append(("a corrupt png is reported as undecoded",
                       res.get("decoded") is False, "decoded=%s" % res.get("decoded")))

    failed = [c for c in checks if not c[1]]
    for name, ok, detail in checks:
        print("%s %s (%s)" % ("PASS" if ok else "FAIL", name, detail))
    print("p10d-png selftest: %d checks, %d failed" % (len(checks), len(failed)))
    print("SELFTEST %s" % ("PASS" if not failed else "FAIL"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
