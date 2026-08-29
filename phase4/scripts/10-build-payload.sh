#!/usr/bin/env bash
# 10-build-payload.sh — assemble the embedded runtime payload for the APK.
#
# Runs on a networked build host (CI ubuntu-latest). Outputs:
#   phase4/out/engine/jniLibs/<abi>/libbun.so  (Bun-for-Android, bionic)
#   phase4/out/engine/jniLibs/<abi>/libgit.so  (static git v2.48.1)
#   phase4/out/engine/jniLibs/<abi>/librg.so   (ripgrep 15.1.0)
#   phase4/out/engine/assets/runtime-payload.tar.gz  (server bundle, node_modules, launcher)
#   phase4/out/engine/assets/runtime-manifest.json   (versions + sha256 of every payload file)
#
# The .so names are the Android trick for shipping extra executables: the
# package manager extracts them to nativeLibraryDir (exec-allowed under W^X).
#
# Recipes reused from the Phase 3 gate suite (do NOT re-solve):
#   * bun-for-Android: official @oven/bun-*-android npm tarballs (bionic ELF)
#   * static git: build the Makefile directly with NO_PERL=YesPlease
#     (git's ./configure refuses to run without perl), static toolchain,
#     NO_REGEX=NeedsStartEnd for musl; NO_CURL/OPENSSL/EXPAT -> local git only
#   * OpenCode bundle: bun build of upstream packages/opencode/src/node.ts
#     at the PINNED commit (fail loudly if the commit can't be checked out)
#
# Usage:  bash phase4/scripts/10-build-payload.sh [x86_64|arm64 ...]
# Default ABIs: arm64-v8a x86_64.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="$DIR/out/engine"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$ENGINE/jniLibs/arm64-v8a" "$ENGINE/jniLibs/x86_64" "$ENGINE/assets"

if [ "$#" -gt 0 ]; then ABIS=("$@"); else ABIS=(arm64-v8a x86_64); fi

PINNED_COMMIT="05ea5073be967c779d326929b2de6228dda4159d"
GIT_PIN="v2.48.1"
ZLIB_PIN="v1.3.1"
BUN_PIN="1.3.14"
RG_PIN="15.1.0"
PAYLOAD_VERSION=4
STATUS="$ENGINE/build.status"
: > "$STATUS"
note() { echo "$*" | tee -a "$STATUS"; }

export PATH="$HOME/.bun/bin:$PATH"

note "=== [1/6] bun host (for building the bundle) ==="
if ! command -v bun >/dev/null 2>&1; then
  curl -fsSL --retry 3 --max-time 180 -o "$WORK/bun-host.tgz" \
    "https://registry.npmjs.org/@oven/bun-linux-x64/-/bun-linux-x64-${BUN_PIN}.tgz"
  mkdir -p "$HOME/.bun/bin" "$WORK/bh" && tar xzf "$WORK/bun-host.tgz" -C "$WORK/bh"
  mv "$WORK/bh/package/bin/bun" "$HOME/.bun/bin/bun" && chmod +x "$HOME/.bun/bin/bun"
fi
bun --version | tee -a "$STATUS"

note "=== [2/6] Bun-for-Android (bionic) per ABI ==="
fetch_bun_android() {  # $1=npm-arch  $2=dest-so
  local arch="$1" dest="$2"
  curl -fsSL --retry 3 --max-time 240 -o "$WORK/bun-$arch.tgz" \
    "https://registry.npmjs.org/@oven/bun-linux-${arch}-android/-/bun-linux-${arch}-android-${BUN_PIN}.tgz"
  mkdir -p "$WORK/bun-$arch" && tar xzf "$WORK/bun-$arch.tgz" -C "$WORK/bun-$arch"
  cp "$WORK/bun-$arch/package/bin/bun" "$dest"
  chmod 755 "$dest"
  note "bun $arch -> $dest ($(stat -c%s "$dest") bytes)"
}
for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)   fetch_bun_android "x64" "$ENGINE/jniLibs/x86_64/libbun.so" ;;
    arm64-v8a) fetch_bun_android "aarch64" "$ENGINE/jniLibs/arm64-v8a/libbun.so" ;;
  esac
done

note "=== [2b/6] seccomp compatibility shim per ABI (NDK clang) ==="
# Android's per-app seccomp filter kills newer syscalls with SIGSYS instead of
# ENOSYS (observed x86_64 epoll_pwait2=441). The shim installs a SIGSYS
# handler mapping them to ENOSYS so Bun's fallbacks engage. Built from
# phase4/payload/native/seccomp-shim.c into jniLibs as libseccompshim.so; the
# launcher dlopens it (OPENCODE_SECCOMP_SHIM -> nativeLibraryDir).
SHIM_SRC="$DIR/payload/native/seccomp-shim.c"
NDK_ROOT="${ANDROID_HOME:-/usr/local/lib/android/sdk}/ndk"
# Find the toolchain bin dir that actually contains a target clang wrapper.
# Match the <triple>29-clang wrappers (not the NDK's python3/bin, which has
# no clang). Take the newest NDK if several are installed.
NDK_BIN=""
for c in $(find "$NDK_ROOT" -type f -name 'x86_64-linux-android29-clang' 2>/dev/null | sort -r); do
  d="$(dirname "$c")"
  if [ -x "$d/clang" ]; then NDK_BIN="$d"; break; fi
done
note "NDK toolchain candidates:"
find "$NDK_ROOT" -type f -name 'x86_64-linux-android*-clang' 2>/dev/null | sort | sed 's/^/  /' | tee -a "$STATUS"
if [ -z "$NDK_BIN" ] || [ ! -x "$NDK_BIN/clang" ]; then
  note "FATAL: Android NDK clang not found under $NDK_ROOT"
  exit 1
fi
note "NDK clang dir: $NDK_BIN"
if [ -z "$NDK_BIN" ] || [ ! -x "$NDK_BIN/clang" ]; then
  note "FATAL: NDK clang not found (looked for .../toolchains/llvm/prebuilt/linux-x86_64/bin/clang)"
  exit 1
fi
build_shim() {  # $1=abi  $2=target-triple
  local abi="$1" triple="$2" out="$ENGINE/jniLibs/$abi/libseccompshim.so"
  mkdir -p "$(dirname "$out")"
  if ! "$NDK_BIN/${triple}29-clang" -O2 -fPIC -shared \
        -Wl,-z,max-page-size=16384 \
        -o "$out" "$SHIM_SRC" 2>"$WORK/shim-$abi.cc.log"; then
    note "FATAL: shim build failed for $abi; clang stderr:"
    cat "$WORK/shim-$abi.cc.log" | tee -a "$STATUS"
    exit 1
  fi
  chmod 755 "$out"
  note "seccomp-shim $abi -> $out ($(stat -c%s "$out") bytes)"
  # Fail-loud sanity: the exported symbol must be present.
  "$NDK_BIN/llvm-nm" -D "$out" 2>/dev/null | grep -q opencode_seccomp_init \
    || { note "FATAL: opencode_seccomp_init missing from $out"; exit 1; }
}
for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_shim "x86_64" "x86_64-linux-android" ;;
    arm64-v8a) build_shim "arm64-v8a" "aarch64-linux-android" ;;
  esac
done

note "=== [3/6] static git $GIT_PIN per ABI (NO_PERL recipe, Phase 3-proven) ==="
curl -fsSL --retry 3 --max-time 180 -o "$WORK/zlib.tgz" \
  "https://github.com/madler/zlib/archive/refs/tags/${ZLIB_PIN}.tar.gz"
rm -rf "$WORK/zlib-src" && mkdir -p "$WORK/zlib-src"
tar xzf "$WORK/zlib.tgz" -C "$WORK/zlib-src" --strip-components=1
rm -rf "$WORK/git-src"
timeout 300 git clone -q --depth 1 --branch "$GIT_PIN" https://github.com/git/git "$WORK/git-src"
( cd "$WORK/git-src" && git rev-parse HEAD > "$ENGINE/git.upstream.commit.txt" )
note "git source: $(cat "$ENGINE/git.upstream.commit.txt")"

# musl.cc cross toolchains (fully static, no NSS/dlopen — ideal for Android)
ensure_musl_cc() {  # $1=triple
  local triple="$1"
  if command -v "$triple-gcc" >/dev/null 2>&1; then return 0; fi
  note "fetching musl.cc toolchain $triple ..."
  if curl -fsSL --retry 2 --max-time 300 -o "$WORK/$triple.tgz" "https://musl.cc/$triple-cross.tgz"; then
    mkdir -p /opt/muslcc && tar xzf "$WORK/$triple.tgz" -C /opt/muslcc
    export PATH="/opt/muslcc/$triple-cross/bin:$PATH"
  fi
  command -v "$triple-gcc" >/dev/null 2>&1
}

build_git_static() {  # $1=label $2=CC $3=AR(optional) $4=zlib-prefix
  local label="$1" cc="$2" ar="$3" zprefix="$4"
  note "--- static git ($label, CC=$cc) ---"
  ( cd "$WORK/zlib-src" && make clean >/dev/null 2>&1 || true
    CC="$cc" ./configure --static --prefix="$zprefix" >/dev/null 2>&1
    make -j4 >/dev/null 2>&1 && make install >/dev/null 2>&1 ) \
    || { note "zlib build FAILED for $label"; return 1; }
  ( cd "$WORK/git-src" && make clean >/dev/null 2>&1 || true
    timeout 1200 make -j4 ${ar:+AR="$ar"} \
      CC="$cc" \
      CFLAGS="-O2 -I$zprefix/include" \
      LDFLAGS="-static -L$zprefix/lib" \
      ZLIB_PATH="$zprefix" \
      NO_REGEX=NeedsStartEnd \
      NO_PERL=YesPlease NO_PYTHON=YesPlease NO_TCLTK=YesPlease NO_GETTEXT=YesPlease \
      NO_ICONV=YesPlease NO_CURL=YesPlease NO_OPENSSL=YesPlease NO_EXPAT=YesPlease \
      NO_LIBPCRE2=YesPlease NO_INSTALL_HARDLINKS=YesPlease all 2>&1 | tail -20 ) \
    | tee -a "$STATUS"
  [ -x "$WORK/git-src/git" ] || { note "git build FAILED for $label"; return 1; }
  note "$label git OK: $("$WORK/git-src/git" --version 2>&1 | head -1 || echo 'cannot run (cross)')"
}

# x86_64: musl static (emulator target, TESTED in Phase 3)
if printf '%s\n' "${ABIS[@]}" | grep -q x86_64; then
  sudo apt-get update -qq >/dev/null 2>&1 || true
  sudo apt-get install -y -qq musl-tools >/dev/null 2>&1 || note "musl-tools apt install failed"
  if command -v musl-gcc >/dev/null 2>&1; then
    build_git_static "x86_64" "musl-gcc" "" "$WORK/zlib-x86_64" \
      && cp "$WORK/git-src/git" "$ENGINE/jniLibs/x86_64/libgit.so" \
      || { note "FATAL: x86_64 static git (musl) failed"; exit 1; }
  else
    note "musl-gcc unavailable; falling back to gcc -static (glibc) for x86_64"
    build_git_static "x86_64" "gcc" "" "$WORK/zlib-x86_64" \
      && cp "$WORK/git-src/git" "$ENGINE/jniLibs/x86_64/libgit.so" \
      || { note "FATAL: x86_64 static git failed"; exit 1; }
  fi
  chmod 755 "$ENGINE/jniLibs/x86_64/libgit.so"
fi

# arm64: musl.cc aarch64 cross (product target). musl-static is fully static
# and runs under Android's seccomp/bionic (same class as the tested x86_64).
if printf '%s\n' "${ABIS[@]}" | grep -q arm64; then
  GIT_ARM_OK=0
  if ensure_musl_cc "aarch64-linux-musl"; then
    if build_git_static "arm64" "aarch64-linux-musl-gcc" "aarch64-linux-musl-ar" \
         "/opt/muslcc/aarch64-linux-musl-cross/aarch64-linux-musl"; then
      cp "$WORK/git-src/git" "$ENGINE/jniLibs/arm64-v8a/libgit.so"
      GIT_ARM_OK=1
    fi
  fi
  if [ "$GIT_ARM_OK" != 1 ]; then
    note "WARNING: arm64 static git (musl.cc) failed; see git status lines above. arm64 libgit.so NOT produced."
    note "         x86_64 libgit.so remains validated; arm64 packaging is inventory-checked by Gradle."
  else
    chmod 755 "$ENGINE/jniLibs/arm64-v8a/libgit.so"
  fi
fi

note "=== [4/6] ripgrep $RG_PIN per ABI ==="
fetch_rg_musl() {  # $1=rust-triple dir $2=dest-so
  local triple="$1" dest="$2"
  local url="https://github.com/BurntSushi/ripgrep/releases/download/${RG_PIN}/ripgrep-${RG_PIN}-${triple}.tar.gz"
  if curl -fsSL --retry 2 --max-time 240 -o "$WORK/rg.tgz" "$url"; then
    mkdir -p "$WORK/rg-x" && tar xzf "$WORK/rg.tgz" -C "$WORK/rg-x"
    local bin
    bin="$(find "$WORK/rg-x" -name rg -type f | head -1)"
    [ -n "$bin" ] && cp "$bin" "$dest" && chmod 755 "$dest" && return 0
  fi
  return 1
}
if printf '%s\n' "${ABIS[@]}" | grep -q x86_64; then
  if fetch_rg_musl "x86_64-unknown-linux-musl" "$ENGINE/jniLibs/x86_64/librg.so"; then
    note "x86_64 rg (musl-static) OK"
  else
    note "FATAL: x86_64 ripgrep download failed"; exit 1
  fi
fi
# arm64: upstream publishes aarch64-unknown-linux-gnu (glibc, NOT Android-safe).
# Prefer a real NDK/bionic build when an Android NDK + rust target are present;
# otherwise record the gap honestly (x86_64 rg is the CI-validated binary).
if printf '%s\n' "${ABIS[@]}" | grep -q arm64; then
  RG_ARM_OK=0
  NDK_HOME_CANDIDATE="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
  if [ -z "$NDK_HOME_CANDIDATE" ]; then
    NDK_HOME_CANDIDATE="$(ls -d ${ANDROID_HOME:-/usr/local/lib/android/sdk}/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
  if [ -n "$NDK_HOME_CANDIDATE" ] && command -v cargo >/dev/null 2>&1 \
     && rustup target list --installed 2>/dev/null | grep -q aarch64-linux-android; then
    note "building ripgrep aarch64 with NDK at $NDK_HOME_CANDIDATE"
    TOOLCHAIN="$NDK_HOME_CANDIDATE/toolchains/llvm/prebuilt/linux-x86_64"
    ( rm -rf "$WORK/rg-src"
      timeout 300 git clone -q --depth 1 --branch "$RG_PIN" https://github.com/BurntSushi/ripgrep "$WORK/rg-src"
      cd "$WORK/rg-src"
      export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android29-clang"
      export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android29-clang"
      export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
      cargo build --release --target aarch64-linux-android 2>&1 | tail -15
      cp "target/aarch64-linux-android/release/rg" "$ENGINE/jniLibs/arm64-v8a/librg.so" ) \
      && { chmod 755 "$ENGINE/jniLibs/arm64-v8a/librg.so"; RG_ARM_OK=1; note "arm64 rg (NDK bionic) OK"; } \
      || note "arm64 NDK ripgrep build failed"
  fi
  [ "$RG_ARM_OK" = 1 ] || note "WARNING: arm64 librg.so not produced (no NDK/rust aarch64 target on this host)."
fi

note "=== [5/6] OpenCode server bundle @ $PINNED_COMMIT (pinned, fail-loud) ==="
rm -rf "$WORK/opencode"
timeout 300 git clone -q --depth 1 --branch dev https://github.com/anomalyco/opencode "$WORK/opencode"
if ! ( cd "$WORK/opencode" && git fetch -q --depth 1 origin "$PINNED_COMMIT" && git checkout -q FETCH_HEAD ); then
  note "FATAL: pinned commit $PINNED_COMMIT not found upstream (report as blocker)"; exit 1
fi
GOT_COMMIT="$(cd "$WORK/opencode" && git rev-parse HEAD)"
note "upstream commit: $GOT_COMMIT"
[ "$GOT_COMMIT" = "$PINNED_COMMIT" ] || { note "FATAL: checked out $GOT_COMMIT != pin $PINNED_COMMIT"; exit 1; }
curl -fsSL --max-time 90 -o "$WORK/models-dev.json" "https://models.dev/api.json" \
  || echo '{}' > "$WORK/models-dev.json"
if ! ( cd "$WORK/opencode" && timeout 600 bun install >/tmp/p4-bun-install.log 2>&1 ); then
  note "bun install failed; dropping web-only package deps (Phase 3 recipe) and retrying"
  python3 - "$WORK/opencode" <<'EOF'
import json, os, sys
root = sys.argv[1]
WEB_ONLY = [
 "packages/app/package.json","packages/console/app/package.json",
 "packages/console/support/package.json","packages/stats/app/package.json",
 "packages/enterprise/package.json","packages/storybook/package.json",
 "packages/session-ui/package.json","packages/docs/package.json",
]
for rel in WEB_ONLY:
    p = os.path.join(root, rel)
    if not os.path.exists(p): continue
    d = json.load(open(p))
    for sec in ("dependencies","optionalDependencies","devDependencies","peerDependencies"):
        d.pop(sec, None)
    json.dump(d, open(p,"w"), indent=2); open(p,"a").write("\n")
d = json.load(open(os.path.join(root,"package.json")))
for k,v in list(d.get("catalog",{}).items()):
    if isinstance(v,str) and "://" in v: del d["catalog"][k]
json.dump(d, open(os.path.join(root,"package.json"),"w"), indent=2)
open(os.path.join(root,"package.json"),"a").write("\n")
EOF
  rm -f "$WORK/opencode/bun.lock"
  ( cd "$WORK/opencode" && timeout 600 bun install --ignore-scripts >>/tmp/p4-bun-install.log 2>&1 ) \
    || { note "FATAL: bun install retry failed"; tail -5 /tmp/p4-bun-install.log | tee -a "$STATUS"; exit 1; }
fi
( cd "$WORK/opencode/packages/opencode" && MODELS_DEV_API_JSON="$WORK/models-dev.json" timeout 300 bun -e '
const generated = { modelsData: await Bun.file(process.env.MODELS_DEV_API_JSON || "/dev/null").text() };
await Bun.build({
  target: "bun",
  entrypoints: ["./src/node.ts"],
  outdir: "./dist/p4",
  format: "esm",
  sourcemap: "linked",
  external: ["jsonc-parser", "@lydell/node-pty", "bun-pty"],
  define: {
    OPENCODE_MODELS_DEV: generated.modelsData,
    OPENCODE_VERSION: `"1.18.23-android"`,
    OPENCODE_CHANNEL: `"android"`,
  },
  files: { "opencode-web-ui.gen.ts": "" },
});
console.log("bundle build complete");
' ) | tee -a "$STATUS"

note "=== [6/6] stage payload, compute manifest ==="
STAGE="$WORK/payload"
mkdir -p "$STAGE/opencode/dist/node" "$STAGE/node_modules/jsonc-parser" \
         "$STAGE/node_modules/@lydell/node-pty" "$STAGE/node_modules/bun-pty"
cp "$WORK/opencode/packages/opencode/dist/p4/node.js" "$STAGE/opencode/dist/node/node.js"
cp "$WORK/opencode/packages/opencode/dist/p4/"*.wasm "$STAGE/opencode/dist/node/" 2>/dev/null || true
cp "$WORK/opencode/packages/opencode/dist/p4/"*.map "$STAGE/opencode/dist/node/" 2>/dev/null || true
# jsonc-parser (externalized dep) + PTY stubs (documented degradation: bash tool
# needs no PTY — Phase 3 verified tool/bash.ts uses stdio pipes).
curl -fsSL --retry 3 --max-time 120 -o "$WORK/jc.tgz" \
  "https://registry.npmjs.org/jsonc-parser/-/jsonc-parser-3.3.1.tgz"
mkdir -p "$WORK/jc" && tar xzf "$WORK/jc.tgz" -C "$WORK/jc" --strip-components=1
cp -r "$WORK/jc/." "$STAGE/node_modules/jsonc-parser/"
cat > "$STAGE/node_modules/@lydell/node-pty/package.json" <<'EOF'
{ "name": "@lydell/node-pty", "version": "0.0.0-stub", "main": "index.js", "type": "commonjs" }
EOF
cat > "$STAGE/node_modules/@lydell/node-pty/index.js" <<'EOF'
// Stub: node-pty has no Android build. Interactive terminal degrades at use-time.
// The bash tool spawns the shell with stdio pipes (no PTY) — Phase 3 verified.
module.exports = { spawn() { throw new Error("node-pty unavailable on Android (stub)"); } };
EOF
cat > "$STAGE/node_modules/bun-pty/package.json" <<'EOF'
{ "name": "bun-pty", "version": "0.0.0-stub", "main": "index.js", "type": "commonjs" }
EOF
cat > "$STAGE/node_modules/bun-pty/index.js" <<'EOF'
module.exports = { spawn() { throw new Error("bun-pty unavailable on Android (stub)"); } };
EOF
cp "$DIR/payload/launcher.js" "$STAGE/launcher.js"

# Deterministic tar (sorted, no owner/mtime noise) then gzip.
PAYLOAD_TGZ="$ENGINE/assets/runtime-payload.tar.gz"
( cd "$STAGE" && find . -type f | sed 's|^\./||' | sort \
    | tar --owner=0 --group=0 --numeric-owner --no-recursion -T - -cf - \
    | gzip -9n > "$PAYLOAD_TGZ" )
PAYLOAD_SHA="$(sha256sum "$PAYLOAD_TGZ" | cut -d' ' -f1)"
note "payload tarball: $(stat -c%s "$PAYLOAD_TGZ") bytes sha256=$PAYLOAD_SHA"

# Manifest: per-file sha256 + size, paths relative to the runtime root (runtime/).
python3 - "$STAGE" "$ENGINE/assets/runtime-manifest.json" "$PAYLOAD_SHA" "$GOT_COMMIT" <<'EOF'
import hashlib, json, os, sys
stage, out, payload_sha, commit = sys.argv[1:5]
files = {}
for dirpath, _, names in os.walk(stage):
    for n in names:
        p = os.path.join(dirpath, n)
        rel = os.path.relpath(p, stage)
        data = open(p, "rb").read()
        files[rel] = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
manifest = {
    "payloadVersion": int(os.environ.get("PAYLOAD_VERSION", "4")),
    "opencodeCommit": commit,
    "opencodeVersion": "1.18.23",
    "bunVersion": "1.3.14",
    "gitVersion": "v2.48.1",
    "rgVersion": "15.1.0",
    "payloadSha256": payload_sha,
    "files": dict(sorted(files.items())),
}
json.dump(manifest, open(out, "w"), indent=2)
print(f"manifest: {len(files)} files -> {out}")
EOF

note "=== engine layout ==="
( cd "$ENGINE" && find . -type f -exec ls -la {} \; | awk '{print $5, $9}' )
note "PAYLOAD_READY"
