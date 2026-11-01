#!/usr/bin/env bash
# Spike: Node 26 as a REAL Android/Bionic arm64 executable.
#
# This is a separate spike. It does NOT change the shipping Node 24.22.1 pin in
# phase4/scripts/10-build-payload.sh (owner rule, 2026-10-30). Outputs go to
# NODE26_OUT (default /tmp/node26-out), outside the repository, and CI uploads
# them as an artifact. Nothing large is committed to Git.
#
# Usage:
#   build-node26-android.sh               full build (CI, needs the Android NDK)
#   build-node26-android.sh --prepare-only   fetch + apply patches, no NDK needed
set -Eeuo pipefail
# Any unplanned failure is also reported as an annotation with the command that failed.
trap 'echo "::error title=node26-spike::unplanned failure at line $LINENO: $BASH_COMMAND (exit $?)"' ERR

NODE_VERSION="26.4.0"
TERMUX_PIN="2d6b9d566c2e933c50e675198a0d7847a96deb61"   # same pin as the Node 24 recipe
ZLIB_PIN="v1.3.1"                                       # same pin as the Node 24 recipe
NDK_PIN="28.2.13676358"                                 # same pin as the Node 24 recipe
TRIPLE="aarch64-linux-android"
PREPARE_ONLY="${1:-}"

OUT="${NODE26_OUT:-/tmp/node26-out}"
WORK="$OUT/work"
REPORT="$OUT/report.txt"
ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
NDK_HOME="$ANDROID_HOME/ndk/$NDK_PIN"
NDK_BIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

mkdir -p "$WORK"
: > "$REPORT"
note() { echo "$(date -u +%FT%TZ) $*" | tee -a "$REPORT"; }
# GitHub shows ::error:: lines as check-run annotations, which can be read through
# the API when the job log cannot be downloaded from the sandbox.
fatal() { note "FATAL: $*"; echo "::error title=node26-spike::$*"; exit 1; }

note "node26 spike: node v$NODE_VERSION, termux $TERMUX_PIN, zlib $ZLIB_PIN, ndk $NDK_PIN"

# 1. Node source (codeload: tag archives are reachable in CI; release assets are not).
curl -fsSL --retry 3 --max-time 600 -o "$WORK/node.tgz" \
  "https://codeload.github.com/nodejs/node/tar.gz/refs/tags/v${NODE_VERSION}"
rm -rf "$WORK/node-src" && mkdir -p "$WORK/node-src"
tar xzf "$WORK/node.tgz" -C "$WORK/node-src" --strip-components=1
grep -q "define NODE_MAJOR_VERSION 26" "$WORK/node-src/src/node_version.h" \
  || fatal "source is not Node 26"
note "source: $(grep -E 'define NODE_(MAJOR|MINOR|PATCH)_VERSION' "$WORK/node-src/src/node_version.h" | awk '{print $3}' | paste -sd. -)"

# 2. Termux Android patch set for Node (nodejs-lts recipe at the pinned commit).
curl -fsSL --retry 3 --max-time 300 -o "$WORK/termux.tgz" \
  "https://codeload.github.com/termux/termux-packages/tar.gz/${TERMUX_PIN}"
rm -rf "$WORK/termux-patches" && mkdir -p "$WORK/termux-patches"
tar xzf "$WORK/termux.tgz" -C "$WORK/termux-patches" --strip-components=3 \
  "termux-packages-${TERMUX_PIN}/packages/nodejs-lts"
[ -f "$WORK/termux-patches/node.gyp.patch" ] || fatal "termux patch set did not extract"

# 3. Apply the same build/runtime patch list the Node 24 recipe applies, minus the
#    V8 trap-handler patch. That patch deletes the whole trap-handler support block
#    for the v24 header. Node 26's header already excludes Android in every support
#    condition and has `#error` if the trap handler is enabled on Android, so the
#    build itself checks the guard. Any other patch failing stops the build.
PATCHES="avoid-ficlone-ioctl.patch common.gypi.patch
deps-uv-src-unix-core.c.patch deps-uv-src-unix-process.c.patch deps-uv-uv.gyp.patch
deps-uvwasi-src-uvwasi.c.patch deps-v8-src-flags-flag-definitions.h.patch
fix_multiple_definitions.patch lib-child_process.js.patch lib-os.js.patch node.gyp.patch
src-debug_utils.cc.patch src-getaddrinfo.patch src-node_internals.h.patch src-node_report.cc.patch
tools-gyp-pylib-gyp-generator-ninja.py.patch tools-v8_gypfiles-toolchain.gypi.patch
tools-v8_gypfiles-v8.gyp.patch"
for p in $PATCHES; do
  patch -p1 -s -d "$WORK/node-src" < "$WORK/termux-patches/$p" || fatal "patch failed: $p"
done
note "patches applied: $(echo $PATCHES | wc -w) (skipped: deps-v8-src-trap-handler-trap-handler.h.patch, see script comment)"

( cd "$WORK/node-src"
  # Same runtime rewrites as the Node 24 recipe: the app's data dir replaces the
  # Termux prefix, and child_process honours the sealed SHELL at runtime.
  grep -rl '@TERMUX_PREFIX@' deps lib src tools 2>/dev/null \
    | xargs -r sed -i 's|@TERMUX_PREFIX@|/data/local/tmp/opencode|g'
  sed -i "s@file = '/data/local/tmp/opencode/bin/sh';@file = process.env.SHELL || '/system/bin/sh';@" lib/child_process.js
) || fatal "runtime path rewrite failed"

if [ "$PREPARE_ONLY" = "--prepare-only" ]; then
  note "prepare-only: stopping before the NDK build"
  exit 0
fi

# 4. Toolchain checks (CI image provides the NDK and Ninja).
# Same lookup as the Node 24 recipe: pinned NDK, and Ninja from PATH or the SDK tree.
note "installed NDKs: $(ls "$ANDROID_HOME/ndk" 2>/dev/null | paste -sd' ' - || true)"
[ -x "$NDK_BIN/${TRIPLE}29-clang" ] || fatal "pinned NDK $NDK_PIN not found at $NDK_HOME (installed: $(ls "$ANDROID_HOME/ndk" 2>/dev/null | paste -sd' ' - || true))"
NINJA="$(command -v ninja || true)"
if [ -z "$NINJA" ]; then
  NINJA="$(find "$(dirname "$ANDROID_HOME")" "$ANDROID_HOME" -type f -name ninja -perm -111 2>/dev/null | sort -r | head -1 || true)"
fi
[ -n "$NINJA" ] && [ -x "$NINJA" ] || fatal "ninja not found on PATH or under the Android SDK"
note "ninja: $NINJA"
note "toolchain: $("$NDK_BIN/${TRIPLE}29-clang" --version | head -1)"

# 5. zlib for Android (Node links it with --shared-zlib).
curl -fsSL --retry 3 --max-time 300 -o "$WORK/zlib.tgz" \
  "https://github.com/madler/zlib/archive/refs/tags/${ZLIB_PIN}.tar.gz"
rm -rf "$WORK/zlib-src" && mkdir -p "$WORK/zlib-src"
tar xzf "$WORK/zlib.tgz" -C "$WORK/zlib-src" --strip-components=1
ZPREFIX="$WORK/zlib-android"
( cd "$WORK/zlib-src"
  CC="$NDK_BIN/${TRIPLE}29-clang" AR="$NDK_BIN/llvm-ar" RANLIB="$NDK_BIN/llvm-ranlib" \
    ./configure --static --prefix="$ZPREFIX" >/dev/null
  make -j4 >/dev/null
  make install >/dev/null ) || fatal "zlib build failed"
note "zlib built: $(stat -c%s "$ZPREFIX/lib/libz.a") bytes"

# 6. Node itself. Same flags as the Node 24 recipe: full ICU, no inspector,
#    no snapshot, no code cache, no bundled npm/corepack (the app ships its own).
NDKHOME_ABS="$(cd "$NDK_HOME" && pwd)"
export PATH="$NDK_BIN:$PATH"
export CC="$NDK_BIN/${TRIPLE}29-clang" CXX="$NDK_BIN/${TRIPLE}29-clang++" LINK="$NDK_BIN/${TRIPLE}29-clang++"
export CC_host="$(command -v gcc)" CXX_host="$(command -v g++)" LINK_host="$(command -v g++)"
export CFLAGS="-O2 -fPIE -D__TERMUX__" CXXFLAGS="-O2 -fPIE -D__TERMUX__"
export LDFLAGS="-pie -Wl,-z,max-page-size=16384"
export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=linux OS=android android_ndk_path=$NDKHOME_ABS"
cd "$WORK/node-src"
/usr/bin/python3 configure.py \
  --dest-cpu=arm64 --dest-os=android --cross-compiling \
  --openssl-no-asm --with-intl=full-icu --without-inspector \
  --without-node-snapshot --without-node-code-cache \
  --shared-zlib --shared-zlib-includes="$ZPREFIX/include" --shared-zlib-libpath="$ZPREFIX/lib" \
  --without-npm --ninja > "$OUT/node-configure.log" 2>&1 \
  || fatal "configure.py failed: $(tail -n 4 "$OUT/node-configure.log" | tr '\n' ' ')"
# Node 26 made corepack opt-in (--with-corepack), so Node 24's --without-corepack
# is no longer a valid flag here; leaving it out means corepack is not installed.
# Cross-configure leaks target paths into host tools (same fix as the Node 24 recipe).
find out/Release/obj.host -name '*.ninja' -type f -exec \
  sed -i -e "s|-I$ZPREFIX/include||g" -e "s|-L$ZPREFIX/lib||g" {} +
if find out/Release/obj.host -name '*.ninja' -type f -exec grep -lF "$ZPREFIX" {} + | grep -q .; then
  fatal "target zlib path survived in a host Ninja rule"
fi
note "ninja start $(date -u +%FT%TZ)"
timeout 14400 "$NINJA" -C out/Release -j4 > "$OUT/node-ninja.log" 2>&1 \
  || { tail -n 80 "$OUT/node-ninja.log" | tee -a "$REPORT"
       fatal "node build failed: $(grep -m3 -E 'FAILED:|error:|Error ' "$OUT/node-ninja.log" | tr '\n' ' ' | cut -c1-900)"; }
note "ninja finished $(date -u +%FT%TZ)"

# 7. Verify the output the same way the Node 24 recipe does.
BUILT="$WORK/node-src/out/Release/node"
[ -f "$BUILT" ] || fatal "node output missing"
"$NDK_BIN/llvm-nm" "$BUILT" > "$WORK/nm.txt" 2>/dev/null || true
grep -Eq 'icudt[0-9]+[lb]?_dat' "$WORK/nm.txt" || grep -aEq 'icudt[0-9]+[lb]' "$BUILT" \
  || fatal "no bundled ICU data (full-icu not built)"
note "ICU data symbol: present"

DEST="$OUT/node26-arm64"
mkdir -p "$DEST/bin"
cp "$BUILT" "$DEST/bin/node"
"$NDK_BIN/llvm-strip" "$DEST/bin/node"
chmod 755 "$DEST/bin/node"
{
  echo "--- ELF header (machine, type)";  "$NDK_BIN/llvm-readelf" -h "$DEST/bin/node" | grep -E 'Machine|Type'
  echo "--- interpreter (must be Bionic linker)"; "$NDK_BIN/llvm-readelf" -l "$DEST/bin/node" | grep -i 'interpreter' || echo "(none)"
  echo "--- LOAD segment alignment (16 KiB pages = 0x4000)"; "$NDK_BIN/llvm-readelf" -lW "$DEST/bin/node" | awk '/LOAD/ {print $NF}' | sort | uniq -c
  echo "--- NEEDED libraries"; "$NDK_BIN/llvm-readelf" -d "$DEST/bin/node" | grep NEEDED || true
} | tee -a "$REPORT"
if "$NDK_BIN/llvm-readelf" -d "$DEST/bin/node" | grep -q 'libc++_shared.so'; then
  cp "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE/libc++_shared.so" "$DEST/bin/"
  note "copied libc++_shared.so beside node (needed at run time)"
fi
sha256sum "$DEST/bin/node" | tee -a "$REPORT"
ls -la "$DEST/bin/node" | tee -a "$REPORT"
note "done: $DEST/bin/node (not run here: this host is x86_64 Linux, the binary targets Android)"
