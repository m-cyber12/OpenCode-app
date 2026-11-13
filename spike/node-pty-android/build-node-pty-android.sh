#!/usr/bin/env bash
# TEST ONLY. Phase 3 spike (terminal): builds the native addon of @lydell/node-pty for
# Android arm64 (Bionic) with the pinned NDK, so the OpenCode v2 terminal can run on the
# phone. The glibc prebuild in @lydell/node-pty-linux-arm64 cannot load on Android.
#
# Output (under $NODE_PTY_OUT, default /tmp/node-pty-android-out):
#   report.txt                            every check and its result
#   pty.node                              the Bionic build
#   node-pty-linux-arm64-android.tgz      the platform package layout with the Bionic pty.node
#
# Checks (any failure stops the build with exit 1):
#   - sources and headers match the pinned sha512 / sha256
#   - the addon is AArch64 and every LOAD segment is 16 KiB aligned
#   - NEEDED libraries are only Android system libraries
#   - every undefined symbol is defined by a NEEDED system library at API 29, or is a
#     Node-API symbol supplied by the host node process
# Not checked here: the addon loads and runs. That needs the phone (the owner's device run).
set -euo pipefail

NDK_VERSION=28.2.13676358
NODE_VERSION=26.4.0
NODE_PTY_VERSION=1.2.0-beta.12
# Registry integrity of node-pty@1.2.0-beta.12 (the native source) and of
# @lydell/node-pty-linux-arm64@1.2.0-beta.12 (the glibc platform package this reuses for lib/).
NODE_PTY_INTEGRITY="sha512-uExTCG/4VmSJa4+TjxFwPXv8BfacmfFEBL6JpxCMDghcwqzvD0yTcGmZ1fKOK6HY33tp0CelLblqTECJizc+Yw=="
PLATFORM_INTEGRITY="sha512-Sx+A71x5BDGHt9ansfrtGxwq2VFVDWvJUAdlUL0Hv0qeiJUfts+hgopx+CgT4PSwahKjdEgtu0+FAfY9rICKRw=="
API=29

OUT="${NODE_PTY_OUT:-/tmp/node-pty-android-out}"
WORK="$OUT/work"
ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TC="$PREBUILT/bin"
SYSROOT="$PREBUILT/sysroot"
LIBDIR="$SYSROOT/usr/lib/aarch64-linux-android/$API"
REPORT="$OUT/report.txt"

rm -rf "$OUT"
mkdir -p "$WORK"
: > "$REPORT"

log() { echo "$*" | tee -a "$REPORT"; }
fail() {
  echo "::error title=node-pty-android::$*"
  log "FAIL: $*"
  exit 1
}

log "node-pty Android spike: node-pty $NODE_PTY_VERSION, node $NODE_VERSION headers, NDK $NDK_VERSION, API $API"

# 1. Toolchain
[ -x "$TC/aarch64-linux-android${API}-clang++" ] || fail "NDK clang not found: $TC/aarch64-linux-android${API}-clang++"
[ -d "$LIBDIR" ] || fail "NDK API $API system libraries not found: $LIBDIR"
log "toolchain: ok ($NDK)"

# 2. Sources (sha512 pinned)
curl -fsSL -o "$WORK/node-pty.tgz" "https://registry.npmjs.org/node-pty/-/node-pty-$NODE_PTY_VERSION.tgz"
got="sha512-$(openssl dgst -sha512 -binary "$WORK/node-pty.tgz" | base64 -w0)"
[ "$got" = "$NODE_PTY_INTEGRITY" ] || fail "node-pty integrity mismatch (got $got)"
tar -xzf "$WORK/node-pty.tgz" -C "$WORK"
log "node-pty source: integrity ok"

curl -fsSL -o "$WORK/platform.tgz" "https://registry.npmjs.org/@lydell/node-pty-linux-arm64/-/node-pty-linux-arm64-$NODE_PTY_VERSION.tgz"
got="sha512-$(openssl dgst -sha512 -binary "$WORK/platform.tgz" | base64 -w0)"
[ "$got" = "$PLATFORM_INTEGRITY" ] || fail "platform package integrity mismatch (got $got)"
mkdir -p "$WORK/platform"
tar -xzf "$WORK/platform.tgz" -C "$WORK/platform"
log "platform package (lib/ layout): integrity ok"

# 3. Node headers (sha256 from the release SHASUMS256.txt)
HDR_BASE="https://nodejs.org/dist/v$NODE_VERSION"
curl -fsSL -o "$WORK/node-headers.tar.gz" "$HDR_BASE/node-v$NODE_VERSION-headers.tar.gz"
curl -fsSL -o "$WORK/SHASUMS256.txt" "$HDR_BASE/SHASUMS256.txt"
expected="$(grep " node-v$NODE_VERSION-headers.tar.gz\$" "$WORK/SHASUMS256.txt" | cut -d' ' -f1 || true)"
got="$(sha256sum "$WORK/node-headers.tar.gz" | cut -d' ' -f1)"
[ -n "$expected" ] && [ "$got" = "$expected" ] || fail "node headers sha256 mismatch (expected '$expected', got '$got')"
tar -xzf "$WORK/node-headers.tar.gz" -C "$WORK"
[ -f "$WORK/node-v$NODE_VERSION/include/node/node_api.h" ] || fail "node headers missing include/node/node_api.h"
log "node $NODE_VERSION headers: sha256 ok"

# 4. Bionic has no separate libutil at every API level; forkpty/openpty must come from a
#    library the NDK ships for API $API. Remove -lutil only when the NDK has no libutil.
PKG="$WORK/package"
UTIL_LINK="removed (NDK API $API has no libutil.so)"
if [ -f "$LIBDIR/libutil.so" ]; then
  UTIL_LINK="kept (libutil.so present)"
else
  sed -i "/'-lutil'/d" "$PKG/binding.gyp"
  grep -q "'-lutil'" "$PKG/binding.gyp" && fail "could not remove -lutil from binding.gyp"
fi
log "libutil: $UTIL_LINK"

# Bionic declares forkpty/openpty in <pty.h> from API 23. This is only a record of what
# the NDK API $API stubs export; the authoritative check is the undefined-symbol check
# after the build. (A stricter check here failed on a false negative in CI, run 43488039590.)
for lib in libc.so libutil.so; do
  [ -f "$LIBDIR/$lib" ] || { log "stub $lib: absent at API $API"; continue; }
  hits="$("$TC/llvm-nm" -D --defined-only "$LIBDIR/$lib" | awk '{print $NF}' | grep -i 'pty' || true)"
  log "stub $lib pty symbols: $(echo $hits)"
  echo "::notice title=node-pty-android::stub $lib pty symbols: $(echo $hits)"
done

# 5. Build with NDK clang (node-gyp cross-compile: CC/CXX/AR/LINK point at the NDK)
cd "$PKG"
npm install --no-save --ignore-scripts "node-addon-api@^7.1.0" > "$WORK/npm-addon.log" 2>&1 \
  || { tail -n 40 "$WORK/npm-addon.log"; fail "npm install node-addon-api failed"; }

export CC="$TC/aarch64-linux-android${API}-clang"
export CXX="$TC/aarch64-linux-android${API}-clang++"
export AR="$TC/llvm-ar"
export LINK="$CXX"

npx --yes node-gyp@11 configure --nodedir="$WORK/node-v$NODE_VERSION" --arch=arm64 > "$WORK/gyp-configure.log" 2>&1 \
  || { tail -n 60 "$WORK/gyp-configure.log"; fail "node-gyp configure failed"; }
npx --yes node-gyp@11 build --nodedir="$WORK/node-v$NODE_VERSION" -j 4 > "$WORK/gyp-build.log" 2>&1 \
  || { tail -n 80 "$WORK/gyp-build.log"; fail "node-gyp build failed"; }

SO="$PKG/build/Release/pty.node"
[ -f "$SO" ] || fail "build did not produce build/Release/pty.node"
cp "$SO" "$OUT/pty.node"
log "build: ok ($(stat -c %s "$SO") bytes)"

# 6. ELF checks
READELF="$TC/llvm-readelf"
NM="$TC/llvm-nm"

machine="$("$READELF" -h "$SO" | awk -F: '/Machine/{print $2}')"
log "machine:$machine"
case "$machine" in *AArch64*) ;; *) fail "machine is not AArch64:$machine";; esac

ALLOWED_NEEDED="libc.so libm.so libdl.so liblog.so libutil.so"
needed="$("$READELF" -d "$SO" | awk '/NEEDED/{gsub(/[\[\]]/,"",$NF); print $NF}')"
log "NEEDED: $(echo $needed)"
for n in $needed; do
  case " $ALLOWED_NEEDED " in *" $n "*) ;; *) fail "NEEDED library $n is not an Android system library";; esac
done

bad_align=""
while read -r align; do
  [ $((align)) -ge 16384 ] || bad_align="$bad_align $align"
done < <("$READELF" -lW "$SO" | awk '$1=="LOAD"{print $NF}')
[ -z "$bad_align" ] || fail "LOAD segments not 16 KiB aligned:$bad_align"
log "LOAD alignment: 16 KiB or more"

defined="$WORK/defined-system.txt"
: > "$defined"
for n in $needed; do
  "$NM" -D --defined-only "$LIBDIR/$n" | awk '{print $NF}' >> "$defined"
done
missing=""
while read -r sym; do
  base="${sym%%@*}"
  case "$base" in napi_*|node_api_*) continue;; esac
  grep -qx "$base" "$defined" || missing="$missing $base"
done < <("$NM" -D --undefined-only "$SO" | awk '{print $NF}')
[ -z "$missing" ] || fail "undefined symbols not provided by the NEEDED system libraries:$missing"
log "undefined symbols: all provided (Node-API from the host node process, the rest by NEEDED libraries)"

# 7. Package in the platform layout (lib/ from the registry, the Bionic pty.node replaces the glibc one)
DEST="$OUT/node_modules/@lydell/node-pty-linux-arm64"
mkdir -p "$DEST" "$DEST/prebuilds/linux-arm64"
cp -r "$WORK/platform/package/lib" "$WORK/platform/package/package.json" "$DEST/"
cp "$SO" "$DEST/prebuilds/linux-arm64/pty.node"
tar -czf "$OUT/node-pty-linux-arm64-android.tgz" -C "$OUT/node_modules/@lydell" node-pty-linux-arm64
log "package: $OUT/node-pty-linux-arm64-android.tgz"
log "sha256 pty.node: $(sha256sum "$SO" | cut -d' ' -f1)"
log "result: BUILT (not yet loaded on a device)"
