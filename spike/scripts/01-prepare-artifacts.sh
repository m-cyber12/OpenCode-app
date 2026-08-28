#!/usr/bin/env bash
# 01-prepare-artifacts.sh — build/fetch everything the device chain needs.
# Runs on the GitHub Actions runner (full network). Outputs into spike/out/.
set -euo pipefail
SPIKE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SPIKE_DIR/out"
mkdir -p "$OUT/node_modules/jsonc-parser" "$OUT/node_modules/@lydell/node-pty" "$OUT/node_modules/bun-pty" "$OUT/opencode/dist/node"
export PATH="$HOME/.bun/bin:$PATH"

echo "=== [1/6] bun (host, for building) ==="
curl -sL -o /tmp/bun-host.tgz "https://registry.npmjs.org/@oven/bun-linux-x64/-/bun-linux-x64-1.3.14.tgz"
mkdir -p "$HOME/.bun/bin" /tmp/bun-host-x && tar xzf /tmp/bun-host.tgz -C /tmp/bun-host-x
mv /tmp/bun-host-x/package/bin/bun "$HOME/.bun/bin/bun" && chmod +x "$HOME/.bun/bin/bun"
bun --version

echo "=== [2/6] bun for Android (x86_64, bionic) ==="
curl -sL -o /tmp/bun-android.tgz "https://registry.npmjs.org/@oven/bun-linux-x64-android/-/bun-linux-x64-android-1.3.14.tgz"
mkdir -p /tmp/bun-android && tar xzf /tmp/bun-android.tgz -C /tmp/bun-android
cp /tmp/bun-android/package/bin/bun "$OUT/bun"
chmod +x "$OUT/bun"
sha256sum "$OUT/bun" > "$OUT/bun.sha256"
readelf -l "$OUT/bun" 2>/dev/null | grep -A1 INTERP || true   # expect /system/bin/linker64 (bionic)

echo "=== [3/6] OpenCode server bundle (bun-target build of upstream src/node.ts) ==="
rm -rf /tmp/opencode && git clone --depth 1 --branch dev https://github.com/anomalyco/opencode /tmp/opencode
(cd /tmp/opencode && git rev-parse HEAD > "$OUT/opencode/UPSTREAM_COMMIT.txt")
export PATH="$HOME/.bun/bin:$PATH"
if ! (cd /tmp/opencode && bun install | tail -2); then
  echo "bun install failed; patching out git-dep ghostty-web (web UI only, not in server bundle) and retrying"
  python3 - <<'EOF'
import json
p = "/tmp/opencode/packages/app/package.json"
d = json.load(open(p))
for sec in ("dependencies","optionalDependencies","devDependencies","peerDependencies"):
    if sec in d and "ghostty-web" in d[sec]:
        del d[sec]["ghostty-web"]; print("removed ghostty-web from", sec)
json.dump(d, open(p,"w"), indent=2); open(p,"a").write("\n")
EOF
  (cd /tmp/opencode && bun install --ignore-scripts | tail -2)
fi
(cd /tmp/opencode/packages/opencode && bun -e '
const generated = { modelsData: await (await fetch("https://models.dev/api.json")).text() };
await Bun.build({
  target: "bun",
  entrypoints: ["./src/node.ts"],
  outdir: "./dist/spike",
  format: "esm",
  sourcemap: "linked",
  external: ["jsonc-parser", "@lydell/node-pty", "bun-pty"],
  define: {
    OPENCODE_MODELS_DEV: generated.modelsData,
    OPENCODE_VERSION: `"1.18.23-spike"`,
    OPENCODE_CHANNEL: `"spike"`,
  },
  files: { "opencode-web-ui.gen.ts": "" },
});
console.log("bundle build complete");
')
cp /tmp/opencode/packages/opencode/dist/spike/node.js "$OUT/opencode/dist/node/"
cp /tmp/opencode/packages/opencode/dist/spike/*.wasm "$OUT/opencode/dist/node/"
ls -la "$OUT/opencode/dist/node/"

echo "=== [4/6] jsonc-parser + native-addon stubs ==="
JSONC=$(find /tmp/opencode/node_modules/.bun -maxdepth 2 -type d -name jsonc-parser | head -1)
if [ -z "$JSONC" ]; then JSONC=$(find /tmp/opencode/packages -maxdepth 3 -type d -name jsonc-parser | head -1); fi
echo "jsonc-parser at: $JSONC"
cp -r "$JSONC/." "$OUT/node_modules/jsonc-parser/"
cat > "$OUT/node_modules/@lydell/node-pty/package.json" <<'EOF'
{ "name": "@lydell/node-pty", "version": "0.0.0-stub", "main": "index.js", "type": "commonjs" }
EOF
cat > "$OUT/node_modules/@lydell/node-pty/index.js" <<'EOF'
// Stub: node-pty has no Android build. PTY/terminal degrades at use-time (documented).
module.exports = { spawn() { throw new Error("node-pty unavailable on Android (stub)"); } };
EOF
cat > "$OUT/node_modules/bun-pty/package.json" <<'EOF'
{ "name": "bun-pty", "version": "0.0.0-stub", "main": "index.js", "type": "commonjs" }
EOF
cat > "$OUT/node_modules/bun-pty/index.js" <<'EOF'
// Stub: bun-pty has no Android build. PTY/terminal degrades at use-time (documented).
module.exports = { spawn() { throw new Error("bun-pty unavailable on Android (stub)"); } };
EOF

echo "=== [5/6] ripgrep 15.1.0 (musl static) ==="
curl -sL -o /tmp/rg.tar.gz "https://github.com/BurntSushi/ripgrep/releases/download/15.1.0/ripgrep-15.1.0-x86_64-unknown-linux-musl.tar.gz"
tar xzf /tmp/rg.tar.gz -C /tmp
cp /tmp/ripgrep-15.1.0-x86_64-unknown-linux-musl/rg "$OUT/rg"
chmod +x "$OUT/rg"

echo "=== [6/6] static git (best-effort) ==="
if git clone --depth 1 https://github.com/git/git /tmp/git-src 2>/dev/null; then
  (cd /tmp/git-src \
    && make -j2 configure \
    && ./configure --prefix=/usr CC=gcc CFLAGS="-O2 -static" LDFLAGS="-static" \
         --without-iconv --without-tcltk --without-perl --without-python \
         --without-curl --without-openssl --without-expat --without-libpcre2 \
    && make -j2 all 2>&1 | tail -2) \
  && cp /tmp/git-src/git "$OUT/git" && chmod +x "$OUT/git" \
  || echo "GIT_BUILD_FAILED (spike continues without git)" > "$OUT/git.status"
else
  echo "GIT_CLONE_FAILED (spike continues without git)" > "$OUT/git.status"
fi
ls -la "$OUT/"
echo "ARTIFACTS_READY"
