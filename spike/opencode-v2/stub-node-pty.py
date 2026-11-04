#!/usr/bin/env python3
"""TEST ONLY. Stubs the node-pty native loader in the OpenCode v2 bundle's assets.

The npm package ships only a glibc prebuild (prebuilds/linux-arm64/pty.node). It needs
libc.so.6 and ld-linux-aarch64.so.1, which Android does not provide, so the module
fails at import and the v2 server never starts. This replaces only the failing
throw in lib/utils.js: the module loads, and any call into the terminal throws a
clear "not available in the Node 26 Android test build" error. Terminal sessions do
not work in this test build. Nothing else changes.

Usage: stub-node-pty.py <path to @lydell/node-pty-linux-arm64/lib/utils.js>
"""
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()

old = '    throw new Error("Failed to load native module: " + name + ".node, checked: " + dirs.join(\', \') + ": " + lastError);'
new = (
    "    // node26 Android TEST stub (spike/opencode-v2/stub-node-pty.py): no Android build yet.\n"
    "    return { dir: \"node26-test-stub\", module: new Proxy({}, { get: function (_t, key) {\n"
    "        if (typeof key === \"symbol\" || key === \"then\") return undefined;\n"
    "        return function () { throw new Error(\"node26 Android test build: native \" + name + \".\" + String(key) + \" is not available\"); };\n"
    "    } }) };"
)

count = src.count(old)
if count != 1:
    sys.exit(f"expected exactly one loader throw in {path}, found {count}")
open(path, "w", encoding="utf-8").write(src.replace(old, new))
print(f"stubbed native loader in {path}")
