#!/usr/bin/env bash
# 01-prepare-artifacts.sh — build/fetch everything the Phase 3 gates need.
# Runs on the GitHub Actions runner (full network). Outputs into phase3/out/.
#
# Phase 2 carry-over fixes implemented here:
#   * static git: Phase 2's build failed at `./configure` with
#     "You cannot use git without perl" (git's configure rejects --without-perl
#     when perl is absent). Fix: skip configure entirely and drive the Makefile
#     directly with NO_PERL=YesPlease + static musl toolchain.
#   * deterministic downloads only (exact registry/github URLs, fail loudly).
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$DIR/out"
mkdir -p "$OUT/node_modules/jsonc-parser" "$OUT/node_modules/@lydell/node-pty" "$OUT/node_modules/bun-pty" \
         "$OUT/opencode/dist/node" "$OUT/mcp" "$OUT/bin"
export PATH="$HOME/.bun/bin:$PATH"

# Sandbox dry-run fallback: GATES_PREP_ALLOW_FALLBACK=1 lets steps that need
# github.com release assets substitute a host binary and mark it in
# $OUT/bin/ARTIFACT_SOURCE.txt. CI never sets this — download failures there
# are fatal (real artifacts only).
allow_fallback() { [ "${GATES_PREP_ALLOW_FALLBACK:-0}" = "1" ]; }

PINNED_COMMIT="05ea5073be967c779d326929b2de6228dda4159d"   # versions.lock (v1.18.23)
GIT_PIN="v2.48.1"                                           # versions.lock git.version
ZLIB_PIN="v1.3.1"
MCP_SDK_PIN="1.29.0"

echo "=== [1/7] bun (host, for building) ==="
curl -sL --max-time 180 -o /tmp/bun-host.tgz "https://registry.npmjs.org/@oven/bun-linux-x64/-/bun-linux-x64-1.3.14.tgz"
mkdir -p "$HOME/.bun/bin" /tmp/bun-host-x && tar xzf /tmp/bun-host.tgz -C /tmp/bun-host-x
mv /tmp/bun-host-x/package/bin/bun "$HOME/.bun/bin/bun" && chmod +x "$HOME/.bun/bin/bun"
bun --version

echo "=== [2/7] bun for Android (x86_64 for the emulator + aarch64 inventory for the product) ==="
curl -sL --max-time 180 -o /tmp/bun-android-x64.tgz "https://registry.npmjs.org/@oven/bun-linux-x64-android/-/bun-linux-x64-android-1.3.14.tgz"
mkdir -p /tmp/bun-android-x64 && tar xzf /tmp/bun-android-x64.tgz -C /tmp/bun-android-x64
cp /tmp/bun-android-x64/package/bin/bun "$OUT/bin/bun"
chmod +x "$OUT/bin/bun"
sha256sum "$OUT/bin/bun" > "$OUT/bun-x64.sha256"
readelf -l "$OUT/bin/bun" 2>/dev/null | grep -A1 INTERP > "$OUT/bun-x64.interp" || echo "(readelf unavailable)"
curl -sL --max-time 180 -o /tmp/bun-android-arm64.tgz "https://registry.npmjs.org/@oven/bun-linux-aarch64-android/-/bun-linux-aarch64-android-1.3.14.tgz"
mkdir -p /tmp/bun-android-arm64 && tar xzf /tmp/bun-android-arm64.tgz -C /tmp/bun-android-arm64
cp /tmp/bun-android-arm64/package/bin/bun "$OUT/bin/bun-arm64"
chmod +x "$OUT/bin/bun-arm64"
sha256sum "$OUT/bin/bun-arm64" > "$OUT/bun-arm64.sha256"
echo "bun x86_64 interp: $(cat "$OUT/bun-x64.interp" 2>/dev/null | tr '\n' ' ')"
echo "bun arm64 size: $(stat -c%s "$OUT/bin/bun-arm64") bytes"

echo "=== [3/7] OpenCode server bundle (bun-target build of upstream src/node.ts @ pinned commit) ==="
rm -rf /tmp/opencode && timeout 300 git clone -q --depth 1 --branch dev https://github.com/anomalyco/opencode /tmp/opencode
if ! (cd /tmp/opencode && git fetch -q --depth 1 origin "$PINNED_COMMIT" && git checkout -q FETCH_HEAD); then
  echo "FATAL: pinned commit $PINNED_COMMIT not found in upstream (report as blocker)"
  exit 1
fi
(cd /tmp/opencode && git rev-parse HEAD > "$OUT/opencode/UPSTREAM_COMMIT.txt")
echo "upstream commit: $(cat "$OUT/opencode/UPSTREAM_COMMIT.txt")"
curl -fsSL --max-time 90 -o "$OUT/models-dev.json" "https://models.dev/api.json" \
  && echo "models.dev snapshot: $(wc -c < "$OUT/models-dev.json") bytes" \
  || { echo "models.dev fetch FAILED; using empty snapshot"; echo '{}' > "$OUT/models-dev.json"; }
# NOTE: never pipe `bun install` through something that swallows its exit code
# (Phase 2 CI lesson) — the failure must reach the `if`.
if ! timeout 480 bash -c 'cd /tmp/opencode && bun install' > /tmp/bun-install-1.log 2>&1; then
  echo "bun install failed (tail):"; tail -5 /tmp/bun-install-1.log
  # Web-only packages are NEVER part of the server bundle (Core Rule 3: the
  # bundle is packages/opencode/src/node.ts + its server-path deps). The web
  # console pulls unreachable deps (ghostty-web git-dep, pkg.pr.new tarballs) —
  # drop those packages' dependency sections entirely so the install can
  # succeed, and neutralize the root catalog's URL entries they referenced.
  echo "patching out web-only packages' dependencies and URL catalog entries, then retrying"
  python3 - <<'EOF'
import json, os

WEB_ONLY = [
    "packages/app/package.json",
    "packages/console/app/package.json",
    "packages/console/support/package.json",
    "packages/stats/app/package.json",
    "packages/enterprise/package.json",
    "packages/storybook/package.json",
    "packages/session-ui/package.json",
    "packages/docs/package.json",
]
for rel in WEB_ONLY:
    p = "/tmp/opencode/" + rel
    if not os.path.exists(p):
        continue
    d = json.load(open(p))
    for sec in ("dependencies","optionalDependencies","devDependencies","peerDependencies"):
        if sec in d and d[sec]:
            print(f"dropping {rel} {sec}: {list(d[sec])[:6]}")
            d[sec] = {}
    json.dump(d, open(p,"w"), indent=2)
    open(p,"a").write("\n")

# neutralize non-registry catalog entries (pkg.pr.new URLs) in the root catalog
root = "/tmp/opencode/package.json"
d = json.load(open(root))
cat = d.get("catalog", {})
for k, v in list(cat.items()):
    if isinstance(v, str) and ("://" in v):
        print(f"dropping root catalog URL entry: {k} = {v}")
        del cat[k]
json.dump(d, open(root,"w"), indent=2)
open(root,"a").write("\n")
EOF
  # the old lockfile still pins ghostty-web; regenerate it so the retry is clean
  rm -f /tmp/opencode/bun.lock
  if ! timeout 480 bash -c 'cd /tmp/opencode && bun install --ignore-scripts' > /tmp/bun-install-2.log 2>&1; then
    echo "bun install retry failed (tail):"; tail -5 /tmp/bun-install-2.log
    exit 1
  fi
fi
(cd /tmp/opencode/packages/opencode && MODELS_DEV_API_JSON="$OUT/models-dev.json" timeout 300 bun -e '
const generated = { modelsData: await Bun.file(process.env.MODELS_DEV_API_JSON || "/dev/null").text() };
await Bun.build({
  target: "bun",
  entrypoints: ["./src/node.ts"],
  outdir: "./dist/spike",
  format: "esm",
  sourcemap: "linked",
  external: ["jsonc-parser", "@lydell/node-pty", "bun-pty"],
  define: {
    OPENCODE_MODELS_DEV: generated.modelsData,
    OPENCODE_VERSION: `"1.18.23-gates"`,
    OPENCODE_CHANNEL: `"gates"`,
  },
  files: { "opencode-web-ui.gen.ts": "" },
});
console.log("bundle build complete");
')
cp /tmp/opencode/packages/opencode/dist/spike/node.js "$OUT/opencode/dist/node/"
cp /tmp/opencode/packages/opencode/dist/spike/*.wasm "$OUT/opencode/dist/node/" 2>/dev/null || true
ls -la "$OUT/opencode/dist/node/"

echo "=== [4/7] jsonc-parser + native-addon stubs (same documented degradation as Phase 2) ==="
mkdir -p "$OUT/node_modules/jsonc-parser"
curl -fsSL --max-time 120 -o /tmp/jsonc-parser.tgz "https://registry.npmjs.org/jsonc-parser/-/jsonc-parser-3.3.1.tgz" \
  || { echo "FATAL: could not download jsonc-parser"; exit 1; }
mkdir -p /tmp/jsonc-parser-x && tar xzf /tmp/jsonc-parser.tgz -C /tmp/jsonc-parser-x --strip-components=1
cp -r /tmp/jsonc-parser-x/. "$OUT/node_modules/jsonc-parser/"
cat > "$OUT/node_modules/@lydell/node-pty/package.json" <<'EOF'
{ "name": "@lydell/node-pty", "version": "0.0.0-stub", "main": "index.js", "type": "commonjs" }
EOF
cat > "$OUT/node_modules/@lydell/node-pty/index.js" <<'EOF'
// Stub: node-pty has no Android build. PTY/terminal degrades at use-time (documented degradation).
// Phase 3 verified the bash tool needs NO PTY (tool/bash.ts spawns the shell with stdio pipes).
module.exports = { spawn() { throw new Error("node-pty unavailable on Android (stub)"); } };
EOF
cat > "$OUT/node_modules/bun-pty/package.json" <<'EOF'
{ "name": "bun-pty", "version": "0.0.0-stub", "main": "index.js", "type": "commonjs" }
EOF
cat > "$OUT/node_modules/bun-pty/index.js" <<'EOF'
// Stub: bun-pty has no Android build. PTY/terminal degrades at use-time (documented degradation).
module.exports = { spawn() { throw new Error("bun-pty unavailable on Android (stub)"); } };
EOF

echo "=== [5/7] ripgrep 15.1.0 (static musl; x86_64 for emulator + aarch64 inventory) ==="
# CI run #1 lesson: a single transient download failure aborted prep here and the
# gates then ran against missing artifacts. Retry downloads (3x), keep the
# emulator-required x86_64 rg FATAL, and treat aarch64 (inventory-only) as a
# warning after retries.
fetch_tar() {  # fetch_tar <url> <out.tgz> <expect-dir>
  local url="$1" out="$2" dir="$3"
  for attempt in 1 2 3; do
    if curl -fsSL --retry 2 --max-time 240 -o "$out" "$url" && tar xzf "$out" -C /tmp 2>/dev/null && [ -d "/tmp/$dir" ]; then
      return 0
    fi
    echo "download attempt $attempt failed: $url" | tee -a "$OUT/bin/ARTIFACT_SOURCE.txt"
    rm -f "$out"
    sleep 5
  done
  return 1
}
if fetch_tar "https://github.com/BurntSushi/ripgrep/releases/download/15.1.0/ripgrep-15.1.0-x86_64-unknown-linux-musl.tar.gz" /tmp/rg-x64.tar.gz ripgrep-15.1.0-x86_64-unknown-linux-musl; then
  cp /tmp/ripgrep-15.1.0-x86_64-unknown-linux-musl/rg "$OUT/bin/rg"
elif allow_fallback && [ -x /usr/bin/rg ]; then
  cp /usr/bin/rg "$OUT/bin/rg"
  echo "FALLBACK: rg = host /usr/bin/rg (sandbox cannot reach github release assets)" | tee -a "$OUT/bin/ARTIFACT_SOURCE.txt"
else
  echo "FATAL: ripgrep x86_64 download failed" | tee -a "$OUT/bin/ARTIFACT_SOURCE.txt"; exit 1
fi
chmod +x "$OUT/bin/rg"
if fetch_tar "https://github.com/BurntSushi/ripgrep/releases/download/15.1.0/ripgrep-15.1.0-aarch64-unknown-linux-musl.tar.gz" /tmp/rg-arm64.tar.gz ripgrep-15.1.0-aarch64-unknown-linux-musl; then
  cp /tmp/ripgrep-15.1.0-aarch64-unknown-linux-musl/rg "$OUT/bin/rg-arm64"
elif allow_fallback; then
  echo "FALLBACK: rg-arm64 omitted (no host substitute; aarch64 inventory not built in this sandbox)" | tee -a "$OUT/bin/ARTIFACT_SOURCE.txt"
else
  echo "WARNING: ripgrep aarch64 download failed after 3 attempts — aarch64 product inventory omitted (does not affect emulator gates)" | tee -a "$OUT/bin/ARTIFACT_SOURCE.txt"
fi
chmod +x "$OUT/bin/rg-arm64" 2>/dev/null || true
if [ -f "$OUT/bin/rg-arm64" ]; then
  sha256sum "$OUT/bin/rg" "$OUT/bin/rg-arm64" > "$OUT/rg.sha256"
  file "$OUT/bin/rg" "$OUT/bin/rg-arm64" > "$OUT/rg.file" || true
else
  sha256sum "$OUT/bin/rg" > "$OUT/rg.sha256"
fi

echo "=== [6/7] static git $GIT_PIN (PHASE 2 BLOCKER FIX) ==="
# Phase 2 root cause: git's ./configure errors out with "You cannot use git
# without perl" even with --without-perl when perl is absent. Fix: build the
# Makefile directly with NO_PERL=YesPlease and a static musl toolchain.
GIT_STATUS="$OUT/git.status"
: > "$GIT_STATUS"

sudo apt-get update -qq >/dev/null 2>&1 || true
sudo apt-get install -y -qq musl-tools >/dev/null 2>&1 \
  && echo "musl-tools installed" || echo "musl-tools install FAILED (will fall back to glibc -static)"

# zlib from source (deterministic GitHub mirror), built once per toolchain
curl -fsSL --max-time 180 -o /tmp/zlib.tgz "https://github.com/madler/zlib/archive/refs/tags/${ZLIB_PIN}.tar.gz"
rm -rf /tmp/zlib && mkdir -p /tmp/zlib && tar xzf /tmp/zlib.tgz -C /tmp/zlib --strip-components=1

rm -rf /tmp/git-src && timeout 240 git clone -q --depth 1 --branch "$GIT_PIN" https://github.com/git/git /tmp/git-src
(cd /tmp/git-src && git rev-parse HEAD > "$OUT/git.upstream.commit.txt")
echo "git source commit: $(cat "$OUT/git.upstream.commit.txt")"

build_git() {  # $1=label  $2=make-extra  $3=zlib-prefix
  local label="$1" extra="$2" zprefix="$3"
  local cc
  cc="$(echo "$extra" | grep -o 'CC=[^ ]*' | cut -d= -f2 || true)"
  [ -z "$cc" ] && cc="musl-gcc"
  echo "--- building static git ($label, CC=$cc) ---" | tee -a "$GIT_STATUS"
  ( cd /tmp/git-src && make clean >/dev/null 2>&1 || true
    if [ -n "$zprefix" ]; then
      ( cd /tmp/zlib && make clean >/dev/null 2>&1 || true
        CC="$cc" ./configure --static --prefix="$zprefix" >/dev/null \
          && make -j4 >/dev/null && make install >/dev/null ) \
        || { echo "zlib build FAILED for $label" | tee -a "$GIT_STATUS"; return 1; }
    fi
    timeout 900 make -j4 \
      $extra \
      CFLAGS="-O2 -I$zprefix/include" \
      LDFLAGS="-static -L$zprefix/lib" \
      ZLIB_PATH="$zprefix" \
      NO_PERL=YesPlease NO_PYTHON=YesPlease NO_TCLTK=YesPlease NO_GETTEXT=YesPlease \
      NO_ICONV=YesPlease NO_CURL=YesPlease NO_OPENSSL=YesPlease NO_EXPAT=YesPlease \
      NO_LIBPCRE2=YesPlease NO_INSTALL_HARDLINKS=YesPlease all >/dev/null 2>>"$GIT_STATUS" ) \
    || { echo "git build FAILED for $label (see git.status)" | tee -a "$GIT_STATUS"; return 1; }
  cp /tmp/git-src/git "$OUT/bin/$label-git"
  chmod +x "$OUT/bin/$label-git"
  sha256sum "$OUT/bin/$label-git" >> "$OUT/git.sha256"
  echo "$label build OK: $(file "$OUT/bin/$label-git" | cut -d: -f2)" | tee -a "$GIT_STATUS"
}

# x86_64 (emulator target) — musl-gcc if available, else gcc -static.
# CI run #1 lesson: the x86_64 build is REQUIRED for G2/G9; a failure here must
# abort prep (the final [ -x ] check below makes it fatal — no `|| true`).
if command -v musl-gcc >/dev/null 2>&1; then
  build_git "x86_64" "CC=musl-gcc" "/tmp/zlib-x86_64"
else
  echo "musl-gcc not available; using gcc -static (glibc) for x86_64" | tee -a "$GIT_STATUS"
  build_git "x86_64" "CC=gcc" "/tmp/zlib-x86_64"
fi

# aarch64 (product target inventory) — musl.cc cross toolchain, else glibc cross
if command -v aarch64-linux-musl-gcc >/dev/null 2>&1; then
  echo "aarch64 musl toolchain already present"
elif curl -fsSL --max-time 300 -o /tmp/musl-aarch64.tgz "https://musl.cc/aarch64-linux-musl-cross.tgz" 2>/dev/null; then
  tar xzf /tmp/musl-aarch64.tgz -C /opt
  export PATH="/opt/aarch64-linux-musl-cross/bin:$PATH"
  echo "musl.cc aarch64 toolchain: $(aarch64-linux-musl-gcc --version | head -1)" | tee -a "$GIT_STATUS"
else
  echo "musl.cc download failed; falling back to apt gcc-aarch64-linux-gnu (static glibc)" | tee -a "$GIT_STATUS"
  sudo apt-get install -y -qq gcc-aarch64-linux-gnu >/dev/null 2>&1 || true
fi
if command -v aarch64-linux-musl-gcc >/dev/null 2>&1; then
  build_git "aarch64" "CC=aarch64-linux-musl-gcc AR=aarch64-linux-musl-ar" "/opt/aarch64-linux-musl-cross/aarch64-linux-musl" || true
elif command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
  build_git "aarch64" "CC=aarch64-linux-gnu-gcc AR=aarch64-linux-gnu-ar" "/tmp/zlib-aarch64" || true
else
  echo "NO aarch64 cross toolchain available" | tee -a "$GIT_STATUS"
fi

if [ -x "$OUT/bin/x86_64-git" ]; then
  echo "x86_64 static git OK: $("$OUT/bin/x86_64-git" --version)" | tee -a "$GIT_STATUS"
  cp "$OUT/bin/x86_64-git" "$OUT/bin/git"
else
  echo "GIT_X86_64_BUILD_FAILED" | tee -a "$GIT_STATUS"
  echo "FATAL: x86_64 static git is required for G2/G9 on the emulator" | tee -a "$GIT_STATUS"
  exit 1
fi

echo "=== [7/7] MCP SDK $MCP_SDK_PIN + test server (real @modelcontextprotocol/sdk, stdio) ==="
mkdir -p "$OUT/mcp"
cat > "$OUT/mcp/package.json" <<EOF
{
  "name": "gates-mcp",
  "private": true,
  "type": "module",
  "dependencies": {
    "@modelcontextprotocol/sdk": "$MCP_SDK_PIN",
    "zod": "^3.25.0",
    "zod-to-json-schema": "^3.25.1"
  }
}
EOF
if ! (cd "$OUT/mcp" && bun install 2>&1 | tail -3); then
  echo "FATAL: MCP SDK install failed — G10 requires the real @modelcontextprotocol/sdk on device" | tee -a "$OUT/mcp/install.status"
  exit 1
fi
[ -d "$OUT/mcp/node_modules/@modelcontextprotocol/sdk" ] || { echo "FATAL: MCP SDK node_modules missing after install" | tee -a "$OUT/mcp/install.status"; exit 1; }
(cd "$OUT/mcp" && bun pm ls 2>/dev/null | head -20 > "$OUT/mcp/deps.txt" || true)
echo "MCP node_modules size: $(du -sh "$OUT/mcp/node_modules" 2>/dev/null | cut -f1)"

# real MCP stdio server (runs under bun on-device) + a client that round-trips it
cat > "$OUT/mcp/mcp-server.js" <<'EOF'
// gates-mcp — a real MCP server (stdio transport, @modelcontextprotocol/sdk)
// exercised by G10: OpenCode connects to it as a local MCP server, and a
// standalone SDK client round-trips tools/list + tools/call against it.
// NOTE: SDK 1.29 registerTool signature is (name, config, cb) with
// inputSchema INSIDE config (zod raw shape) — verified working locally.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { z } from "zod"

const server = new McpServer({ name: "gates-mcp", version: "1.0.0" })

server.registerTool(
  "echo",
  { title: "Echo", description: "Echo the message back with an echo: prefix", inputSchema: { message: z.string() } },
  async ({ message }) => ({ content: [{ type: "text", text: `echo:${message}` }] }),
)

server.registerTool(
  "write_marker",
  { title: "Write marker", description: "Write a text file on the host filesystem", inputSchema: { path: z.string(), content: z.string() } },
  async ({ path, content }) => {
    const fs = await import("node:fs")
    fs.writeFileSync(path, content)
    return { content: [{ type: "text", text: `wrote:${path}` }] }
  },
)

await server.connect(new StdioServerTransport())
EOF
cat > "$OUT/mcp/mcp-roundtrip.js" <<'EOF'
// gates-mcp-roundtrip — real SDK client driving the real server over stdio.
// Proves: child-process spawn + JSON-RPC over stdio on Android.
import { Client } from "@modelcontextprotocol/sdk/client/index.js"
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js"

const transport = new StdioClientTransport({
  command: "/data/local/tmp/gates/bin/bun",
  args: ["/data/local/tmp/gates/mcp/mcp-server.js"],
})
const client = new Client({ name: "gates-mcp-roundtrip", version: "1.0.0" })
await client.connect(transport)
const { tools } = await client.listTools()
console.log("TOOLS=" + JSON.stringify(tools.map((t) => t.name)))
const res = await client.callTool({ name: "echo", arguments: { message: "G10_MCP_ROUNDTRIP_OK" } })
console.log("CALL=" + JSON.stringify(res))
await client.close()
EOF
echo "mcp server/client scripts written"

ls -la "$OUT/" "$OUT/bin/"
echo "ARTIFACTS_READY"
