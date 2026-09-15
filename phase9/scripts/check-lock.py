#!/usr/bin/env python3
"""Phase 9: "shipped == pinned" check.

Compares versions.lock (the single source of truth for pins) against
  - app/src/main/java/ai/opencode/android/runtime/RuntimeVersion.kt (what the
    app asserts at extraction time),
  - app/build.gradle.kts versionName (must start with the pinned OpenCode version),
  - phase4/out/engine/assets/runtime-manifest.json (what the payload build
    actually produced) when it exists; --require-manifest makes its absence a
    failure (the device gate), otherwise it is only reported.

Prints one line starting with OK or BAD; exit 0/1.
Usage: check-lock.py <repo-root> [--require-manifest]
"""
import json, os, re, sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
require_mf = "--require-manifest" in sys.argv
L = open(os.path.join(root, "versions.lock"), encoding="utf-8").read()
K = open(os.path.join(root, "app/src/main/java/ai/opencode/android/runtime/RuntimeVersion.kt"), encoding="utf-8").read()
G = open(os.path.join(root, "app/build.gradle.kts"), encoding="utf-8").read()
mf = os.path.join(root, "phase4/out/engine/assets/runtime-manifest.json")

def lk(pat):
    m = re.search(pat, L, re.M); return m.group(1) if m else ""
def kk(pat):
    m = re.search(pat, K); return m.group(1) if m else ""

want = {
  "opencodeCommit": lk(r"^  commit: ([0-9a-f]{40})"),
  "opencodeVersion": lk(r"^  version: ([0-9.]+)"),
  "bunVersion": lk(r"^  bun_android_arm64: ([0-9.]+)"),
  "gitVersion": lk(r"^    version: (v[0-9.]+)"),
  "rgVersion": lk(r"^    version: (15\.[0-9.]+)"),
  "payloadVersion": int(lk(r"^  payload_version: ([0-9]+)") or 0),
}
have_kt = {
  "opencodeCommit": kk(r'OPENCODE_COMMIT = "([0-9a-f]+)"'),
  "opencodeVersion": kk(r'OPENCODE_VERSION = "([0-9.]+)"'),
  "bunVersion": kk(r'BUN_VERSION = "([0-9.]+)"'),
  "gitVersion": kk(r'GIT_VERSION = "(v[0-9.]+)"'),
  "rgVersion": kk(r'RIPGREP_VERSION = "([0-9.]+)"'),
  "payloadVersion": int(kk(r'PAYLOAD_VERSION = ([0-9]+)') or 0),
}
bad = []
for k, v in want.items():
    if not v: bad.append(f"versions.lock has no {k}")
    if have_kt[k] != v: bad.append(f"RuntimeVersion.{k}={have_kt[k]}!=lock {v}")
sha = ""
if os.path.exists(mf):
    try:
        M = json.load(open(mf))
        for k, v in want.items():
            if M.get(k) != v: bad.append(f"manifest.{k}={M.get(k)}!=lock {v}")
        sha = M.get("payloadSha256", "")
    except Exception as e:
        bad.append(f"manifest unreadable: {e}")
elif require_mf:
    bad.append("runtime-manifest.json missing (payload not built)")
m = re.search(r'^\s*versionName = "([^"]+)"', G, re.M)
vn = m.group(1) if m else ""
if not vn.startswith(want["opencodeVersion"] + "-"): bad.append(f"versionName={vn} does not start with {want['opencodeVersion']}-")
print(("OK" if not bad else "BAD") + f" lock={want} versionName={vn} manifestSha={sha[:16] or 'n/a'} " + "; ".join(bad))
sys.exit(1 if bad else 0)
