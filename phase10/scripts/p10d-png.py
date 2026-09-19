#!/usr/bin/env python3
"""Screenshot sanity for the real-device driver: is this PNG actually a screen?

Why this exists: the first signed-build run produced ZERO usable screenshots and
the failure was reported as "no working screen after 941s" - with no image to look
at. A screencap of a locked, off, or all-black screen is still a valid PNG, so
"the file exists" is not evidence that the app was on screen. This decodes the PNG
(standard library only - a phone-connected laptop may have neither Pillow nor
ImageMagick) and reports three numbers that separate a real UI from a black or
off screen:

  * mean brightness (0-255) of the sampled pixels,
  * the fraction of near-black pixels,
  * the number of distinct colors among the sampled pixels (a black frame has 1).

Usage:
  python3 p10d-png.py shot.png [--rows N] [--json]
Exit code 0 always (this is a diagnostic, not a gate); the driver decides.
"""
import json
import struct
import sys
import zlib


def read_png(path, max_rows=0):
    """Return (width, height, bpp, pixel_bytes, rows_decoded) or None if unsupported."""
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    pos = 8
    idat = b""
    width = height = depth = color = None
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            width, height, depth, color = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break
        pos += 12 + length
    if width is None or depth != 8 or color not in (0, 2, 4, 6):
        return None
    bpp = {0: 1, 2: 3, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = width * bpp
    rows = height if not max_rows or max_rows >= height else max_rows
    out = bytearray()
    prev = bytearray(stride)
    p = 0
    for _ in range(rows):
        if p + 1 + stride > len(raw):
            break
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if filt == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filt == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filt == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif filt == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pa, pb, pc = abs(a + b - c - a), abs(a + b - c - b), abs(a + b - c - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out += line
        prev = line
    return width, height, bpp, bytes(out), rows


def analyze(path, max_rows=400):
    loaded = read_png(path, max_rows=max_rows)
    if loaded is None:
        return {"file": path, "decoded": False}
    width, height, bpp, pixels, rows = loaded
    total = 0
    dark = 0
    histogram = [0] * 32
    count = 0
    step = bpp * 7  # sample every 7th pixel: enough for a brightness verdict, cheap
    for i in range(0, len(pixels) - bpp, step):
        r, g, b = pixels[i], pixels[i + 1], pixels[i + 2]
        lum = (r * 299 + g * 587 + b * 114) // 1000
        total += lum
        if lum < 12:
            dark += 1
        histogram[min(31, lum >> 3)] += 1
        count += 1
    if not count:
        return {"file": path, "decoded": False}
    mean = total / count
    modal = max(histogram)
    modal_bucket = histogram.index(modal)
    # "content" = everything that is not the dominant background tone. A screen
    # that is switched off, all black, or an unpainted surface has ~no content,
    # whatever its background color is; that is the property this check needs, and
    # it is theme-agnostic (the app is light in one theme and dark in the other).
    content = 1.0 - (modal / count)
    return {
        "file": path,
        "decoded": True,
        "width": width,
        "height": height,
        "rows_sampled": rows,
        "mean_brightness": round(mean, 1),
        "near_black_fraction": round(dark / count, 3),
        "content_fraction": round(content, 4),
        "modal_luminance_bucket": modal_bucket,
        "looks_like_a_screen": content > 0.005 and not (mean < 3 and content < 0.05),
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    rows = 400
    if "--rows" in sys.argv:
        rows = int(sys.argv[sys.argv.index("--rows") + 1])
    if not args:
        print("usage: p10d-png.py shot.png [--rows N] [--json]")
        return 2
    results = [analyze(p, max_rows=rows) for p in args]
    if "--json" in sys.argv:
        print(json.dumps(results if len(results) > 1 else results[0]))
    else:
        for r in results:
            if not r.get("decoded"):
                print("%s: NOT DECODED (unsupported PNG shape) - inspect by hand" % r["file"])
            else:
                # The FULL path, never a shortened one: this line is copied into the
                # device bundle's screenshots.log, where a trimmed path names a file
                # that does not exist ([...][-48:] produced exactly that on any path
                # longer than 48 characters).
                print("%s: %dx%d mean=%.1f dark=%.3f content=%.3f screen=%s" % (
                    r["file"], r["width"], r["height"], r["mean_brightness"],
                    r["near_black_fraction"], r["content_fraction"],
                    "yes" if r["looks_like_a_screen"] else "NO (blank/off/locked?)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
