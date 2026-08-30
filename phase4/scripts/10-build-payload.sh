#!/usr/bin/env bash
# 10-build-payload.sh — assemble the embedded runtime payload for the APK.
#
# Runs on a networked build host (CI ubuntu-latest). Outputs:
#   phase4/out/engine/jniLibs/<abi>/libbun.so  (Bun-for-Android, bionic)
#   phase4/out/engine/jniLibs/<abi>/libgit.so  (Git v2.48.1, Android/Bionic executable)
#   phase4/out/engine/jniLibs/<abi>/librg.so   (ripgrep 15.1.0, Android/Bionic executable)
#   phase4/out/engine/assets/runtime-payload.tar.gz  (server bundle, node_modules, launcher)
#   phase4/out/engine/assets/runtime-manifest.json   (versions + sha256 of every payload file)
#
# The .so names are the Android trick for shipping extra executables: the
# package manager extracts them to nativeLibraryDir (exec-allowed under W^X).
#
# Recipes reused from the Phase 3 gate suite (do NOT re-solve):
#   * bun-for-Android: official @oven/bun-*-android npm tarballs (bionic ELF)
#   * Git: build the Makefile directly with NO_PERL=YesPlease using the Android
#     NDK/Bionic toolchain. A Bionic-linked executable is intentional: Android's
#     zygote seccomp policy is designed for Bionic and kills static-musl children
#     on syscalls that cannot be intercepted from a wrapper after exec.
#   * ripgrep: build the real upstream source for each Android ABI with Cargo +
#     the NDK linker (not the glibc/musl desktop release binary).
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
NATIVE_SRC="$DIR/payload/native"
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
# Build both native helpers per ABI:
#   libseccompshim.so  shared lib; LD_PRELOADed into bun; its constructor
#                      installs the SIGSYS->ENOSYS handler before bun init.
#   libexecshim.so     PIE executable (the actual server entrypoint) that sets
#                      LD_PRELOAD and execv()s libbun.so. Shipped/exec'd from
#                      nativeLibraryDir (W^X allows execution there).
build_native() {  # $1=abi  $2=target-triple
  local abi="$1" triple="$2" outdir="$ENGINE/jniLibs/$abi"
  mkdir -p "$outdir"
  local cc="$NDK_BIN/${triple}29-clang"
  # preload handler library
  if ! "$cc" -O2 -fPIC -shared -Wl,-z,max-page-size=16384 \
        -o "$outdir/libseccompshim.so" "$NATIVE_SRC/seccomp-shim.c" 2>"$WORK/shim-$abi.log"; then
    note "FATAL: libseccompshim build failed for $abi; clang stderr:"; cat "$WORK/shim-$abi.log" | tee -a "$STATUS"; exit 1
  fi
  # PIE wrapper executable
  if ! "$cc" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
        -o "$outdir/libexecshim.so" "$NATIVE_SRC/exec-shim.c" 2>"$WORK/exec-$abi.log"; then
    note "FATAL: libexecshim build failed for $abi; clang stderr:"; cat "$WORK/exec-$abi.log" | tee -a "$STATUS"; exit 1
  fi
  # PIE child-tool wrapper (static musl git/rg seccomp filter + exec)
  if ! "$cc" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
        -o "$outdir/libchildshim.so" "$NATIVE_SRC/child-shim.c" 2>"$WORK/child-$abi.log"; then
    note "FATAL: libchildshim build failed for $abi; clang stderr:"; cat "$WORK/child-$abi.log" | tee -a "$STATUS"; exit 1
  fi
  chmod 755 "$outdir/libseccompshim.so" "$outdir/libexecshim.so" "$outdir/libchildshim.so"
  note "seccomp helpers $abi -> libseccompshim.so ($(stat -c%s "$outdir/libseccompshim.so") B)," \
       "libexecshim.so ($(stat -c%s "$outdir/libexecshim.so") B)," \
       "libchildshim.so ($(stat -c%s "$outdir/libchildshim.so") B)"
  "$NDK_BIN/llvm-nm" -D "$outdir/libseccompshim.so" 2>/dev/null | grep -q opencode_seccomp_init \
    || { note "FATAL: opencode_seccomp_init missing from libseccompshim.so ($abi)"; exit 1; }
  # The wrapper must be a PIE (DYN) executable so Android will exec it from
  # nativeLibraryDir (ET_EXEC fixed-address binaries are rejected). llvm-readelf
  # is bundled with the NDK; fall back to the host readelf if absent.
  READELF="$NDK_BIN/llvm-readelf"
  [ -x "$READELF" ] || READELF="$(command -v llvm-readelf readelf | head -1)"
  "$READELF" -h "$outdir/libexecshim.so" 2>/dev/null | tee -a "$STATUS" | grep -q 'DYN' \
    || { note "FATAL: libexecshim.so ($abi) is not a PIE/DYN executable"; exit 1; }
  "$READELF" -h "$outdir/libchildshim.so" 2>/dev/null | grep -q 'DYN' \
    || { note "FATAL: libchildshim.so ($abi) is not a PIE/DYN executable"; exit 1; }
}
for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_native "x86_64" "x86_64-linux-android" ;;
    arm64-v8a) build_native "arm64-v8a" "aarch64-linux-android" ;;
  esac
done

note "=== [3/6] Git $GIT_PIN per ABI (Android/Bionic, NO_PERL recipe) ==="
# A static musl executable cannot install a SIGSYS handler through LD_PRELOAD,
# and an Android app cannot use ptrace to repair the zygote policy after exec.
# Build the real Git source against Android Bionic instead. The NDK linker emits
# a normal /system/bin/linker64 (or linker) executable; Bionic's syscall wrappers
# stay inside the app policy and no external shell/package is required at run
# time. Local Git operations remain fully enabled; network/curl helpers remain
# disabled exactly as in the Phase 3 recipe.
build_git_android() {  # $1=abi $2=target triple $3=lib dir
  local abi="$1" triple="$2" outdir="$3"
  local cc="$NDK_BIN/${triple}29-clang"
  local ar="$NDK_BIN/llvm-ar" ranlib="$NDK_BIN/llvm-ranlib"
  local zprefix="$WORK/zlib-$abi-android"
  note "--- Android Git ($abi, CC=$cc) ---"

  ( cd "$WORK/zlib-src"
    make clean >/dev/null 2>&1 || true
    CC="$cc" AR="$ar" RANLIB="$ranlib" ./configure --static --prefix="$zprefix" >/dev/null
    make -j4 >/dev/null
    make install >/dev/null
  ) || { note "FATAL: Android zlib build failed for $abi"; return 1; }

  ( cd "$WORK/git-src"
    make clean >/dev/null 2>&1 || true
    timeout 1200 make -j4 \
      CC="$cc" AR="$ar" RANLIB="$ranlib" \
      CFLAGS="-O2 -I$zprefix/include" \
      LDFLAGS="-L$zprefix/lib -Wl,-z,max-page-size=16384" \
      ZLIB_PATH="$zprefix" \
      NO_REGEX=NeedsStartEnd \
      NO_PERL=YesPlease NO_PYTHON=YesPlease NO_TCLTK=YesPlease NO_GETTEXT=YesPlease \
      NO_ICONV=YesPlease NO_CURL=YesPlease NO_OPENSSL=YesPlease NO_EXPAT=YesPlease \
      NO_LIBPCRE2=YesPlease NO_INSTALL_HARDLINKS=YesPlease all
  ) || { note "FATAL: Android Git build failed for $abi"; return 1; }
  [ -x "$WORK/git-src/git" ] || { note "FATAL: Android Git binary missing for $abi"; return 1; }
  cp "$WORK/git-src/git" "$outdir/libgit.so"
  chmod 755 "$outdir/libgit.so"
  note "$abi Android Git OK: $("$outdir/libgit.so" --version 2>&1 | head -1 || echo 'cross binary')"
}

for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_git_android "x86_64" "x86_64-linux-android" "$ENGINE/jniLibs/x86_64" ;;
    arm64-v8a) build_git_android "arm64-v8a" "aarch64-linux-android" "$ENGINE/jniLibs/arm64-v8a" ;;
  esac
done

note "=== [4/6] ripgrep $RG_PIN per ABI (Android/Bionic source build) ==="
# The official desktop release artifacts are static musl/glibc binaries. They
# reproduce the SIGSYS failure when OpenCode spawns them from untrusted_app.
# Compile the real ripgrep source for Android so Rust's libc layer uses Bionic
# and the resulting executable uses the NDK's Android dynamic linker.
build_rg_android() {  # $1=abi $2=rust target $3=ndk triple
  local abi="$1" target="$2" triple="$3"
  local outdir="$ENGINE/jniLibs/$abi"
  local toolchain="$NDK_BIN"
  local cc="$toolchain/${triple}29-clang"
  note "--- Android ripgrep ($abi, target=$target) ---"
  if ! command -v cargo >/dev/null 2>&1 || ! command -v rustup >/dev/null 2>&1; then
    note "FATAL: cargo/rustup is required for Android ripgrep ($abi)"
    return 1
  fi
  rustup target add "$target" >/dev/null
  rm -rf "$WORK/rg-src"
  timeout 300 git clone -q --depth 1 --branch "$RG_PIN" https://github.com/BurntSushi/ripgrep "$WORK/rg-src"
  (
    cd "$WORK/rg-src"
    export CARGO_TARGET_$(echo "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER="$cc"
    export CC_$(echo "$target" | tr '[:upper:]-' '[:lower:]_')="$cc"
    export AR_$(echo "$target" | tr '[:upper:]-' '[:lower:]_')="$toolchain/llvm-ar"
    export CARGO_TARGET_$(echo "$target" | tr '[:lower:]-' '[:upper:]_')_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
    export CFLAGS="-O2 -D__ANDROID_API__=29"
    export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
    timeout 1200 cargo build --release --target "$target" --features pcre2
    cp "target/$target/release/rg" "$outdir/librg.so"
  ) || { note "FATAL: Android ripgrep build failed for $abi"; return 1; }
  chmod 755 "$outdir/librg.so"
  note "$abi Android ripgrep OK: $(file "$outdir/librg.so" | cut -d: -f2-)"
}

for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_rg_android "x86_64" "x86_64-linux-android" "x86_64-linux-android" ;;
    arm64-v8a) build_rg_android "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android" ;;
  esac
done

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
