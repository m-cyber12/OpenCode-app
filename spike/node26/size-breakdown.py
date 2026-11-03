#!/usr/bin/env python3
"""Size breakdown of an unstripped Node binary, by symbol group.

Usage: size-breakdown.py <llvm-nm --print-size --defined-only output> <label>

Prints one line per group (bytes, MiB, share) and a total, as plain text.
Group rules are name-prefix heuristics, so 'other' is the honest remainder.
"""
import sys
from collections import defaultdict

GROUPS = [
    ("ICU data (icudt*)", lambda n: n.startswith("icudt")),
    ("ICU code", lambda n: "_ZN3icu" in n or n.startswith("icu_") or n.startswith("ucal_") or n.startswith("unum_")),
    ("V8", lambda n: "_ZN2v8" in n or n.startswith("v8") or "_ZN4v8" in n),
    ("OpenSSL (crypto/ssl)", lambda n: n.startswith(("OPENSSL", "ossl_", "BN_", "EC_", "SSL_", "EVP_", "X509", "RSA_", "ASN1", "PEM_", "CRYPTO_", "DH_", "ENGINE_", "OSSL_"))),
    ("Node (node::)", lambda n: "_ZN4node" in n or n.startswith("node_")),
    ("libuv", lambda n: n.startswith("uv_") or n.startswith("uv__")),
    ("nghttp2/llhttp/cares/zlib", lambda n: n.startswith(("nghttp2", "llhttp", "ares_", "uv_", "inflate", "deflate", "crc32", "adler32", "zlib"))),
]


def classify(name):
    for label, pred in GROUPS:
        if pred(name):
            return label
    return "other"


def main():
    nm_path, label = sys.argv[1], sys.argv[2]
    totals = defaultdict(int)
    grand = 0
    with open(nm_path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            parts = line.split()
            if len(parts) < 4:
                continue
            try:
                size = int(parts[1], 16)
            except ValueError:
                continue
            name = parts[3]
            totals[classify(name)] += size
            grand += size
    print(f"size breakdown ({label}): symbols total {grand} bytes = {grand / 2**20:.1f} MiB")
    for group, size in sorted(totals.items(), key=lambda kv: -kv[1]):
        share = 100.0 * size / grand if grand else 0.0
        print(f"  {group:<28} {size:>12} B  {size / 2**20:7.2f} MiB  {share:5.1f}%")


if __name__ == "__main__":
    main()
