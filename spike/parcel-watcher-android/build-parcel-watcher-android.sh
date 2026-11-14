#!/usr/bin/env bash
# TEST ONLY. Phase 3 spike (file watching): builds the native binding of @parcel/watcher
# for Android arm64 (Bionic) with the pinned NDK. The OpenCode v2 core loads it from
# OPENCODE_PARCEL_WATCHER_PATH. Without it the watcher logs "watcher backend not supported"
# and watches nothing (packages/core/src/filesystem/watcher.ts).
#
# Output (under $PARCEL_OUT, default /tmp/parcel-watcher-android-out):
#   report.txt   every check and its result
#   watcher.node the Bionic binding (inotify backend, the Linux branch of binding.gyp)
#
# Checks (any failure stops the build with exit 1):
#   - source and headers match the pinned sha512 / sha256
#   - the binding is AArch64, every LOAD segment is 16 KiB aligned
#   - NEEDED libraries are only Android system libraries
#   - every undefined symbol is provided by a NEEDED system library at API 29, or is a
#     Node-API symbol supplied by the host node process
# Not checked here: the binding loads and watches a file. That needs the phone.
set -euo pipefail

NDK_VERSION=28.2.13676358
NODE_VERSION=26.4.0
PW_VERSION=2.5.1
# Registry integrity of @parcel/watcher@2.5.1 (checked in the sandbox against the registry).
PW_INTEGRITY="sha512-dfUnCxiN9H4ap84DvD2ubjw+3vUNpstxa0TneY/Paat8a3R4uQZDLSvWjmznAY/DoahqTHl9V46HF/Zs3F29pg=="
API=29

OUT="${PARCEL_OUT:-/tmp/parcel-watcher-android-out}"
WORK="$OUT/work"
ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TC="$PREBUILT/bin"
LIBDIR="$PREBUILT/sysroot/usr/lib/aarch64-linux-android/$API"
REPORT="$OUT/report.txt"

rm -rf "$OUT"
mkdir -p "$WORK"
: > "$REPORT"

log() { echo "$*" | tee -a "$REPORT"; }
fail() {
  echo "::error title=parcel-watcher-android::$*"
  log "FAIL: $*"
  exit 1
}

log "@parcel/watcher Android spike: $PW_VERSION, node $NODE_VERSION headers, NDK $NDK_VERSION, API $API"

# 1. Toolchain
[ -x "$TC/aarch64-linux-android${API}-clang++" ] || fail "NDK clang not found: $TC/aarch64-linux-android${API}-clang++"
[ -d "$LIBDIR" ] || fail "NDK API $API system libraries not found: $LIBDIR"
log "toolchain: ok ($NDK)"

# 2. Source (sha512 pinned)
curl -fsSL -o "$WORK/watcher.tgz" "https://registry.npmjs.org/@parcel/watcher/-/watcher-$PW_VERSION.tgz"
got="sha512-$(openssl dgst -sha512 -binary "$WORK/watcher.tgz" | base64 -w0)"
[ "$got" = "$PW_INTEGRITY" ] || fail "@parcel/watcher integrity mismatch (got $got)"
tar -xzf "$WORK/watcher.tgz" -C "$WORK"
log "@parcel/watcher source: integrity ok"

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

PKG="$WORK/package"
cd "$PKG"

# 4. Static libc++ (a shared libc++_shared.so would be a NEEDED entry the app would have to ship).
#    No -lutil or other extra libraries are needed on the Linux branch of binding.gyp.
STATIC_CXX="'-Wl,--trace-symbol=memfd_create', '-nostdlib++', '-Wl,-Bstatic', '-lc++_static', '-lc++abi', '-lunwind', '-Wl,-Bdynamic'"
python3 - "$PKG/binding.gyp" "$STATIC_CXX" <<'PY'
import sys
path, flags = sys.argv[1], sys.argv[2]
text = open(path).read()
anchor = '"src/binding.cc",'
assert text.count(anchor) == 1, "binding.cc source anchor not found once"
# Weak memfd_create fallback (see the node-pty spike): compiler-rt references it; API 29 has none.
text = text.replace(anchor, anchor + ' "src/memfd_weak.c",', 1)
text = text.replace('"defines": [ "NAPI_DISABLE_CPP_EXCEPTIONS" ],',
                    '"defines": [ "NAPI_DISABLE_CPP_EXCEPTIONS" ],\n      "libraries": [ ' + flags + ' ],', 1)
assert '-lc++_static' in text, "static libc++ flags were not added"
assert 'memfd_weak.c' in text, "memfd fallback source was not added"
open(path, "w").write(text)
PY
cat > "$PKG/src/memfd_weak.c" <<'CSRC'
/* Phase 3 spike (TEST ONLY): weak fallback for memfd_create on Bionic API 29. */
#include <errno.h>
#include <sys/types.h>
__attribute__((weak)) int memfd_create(const char *name, unsigned int flags) {
  (void)name;
  (void)flags;
  errno = ENOSYS;
  return -1;
}
CSRC
log "link: static libc++; memfd_create: weak fallback compiled in (API 29 has none; API 30+ libc wins)"

# 5. Build with NDK clang (node-gyp cross-compile: CC/CXX/AR/LINK point at the NDK)
npm install --no-save --ignore-scripts "node-addon-api@^7.0.0" > "$WORK/npm-addon.log" 2>&1 \
  || { tail -n 40 "$WORK/npm-addon.log"; fail "npm install node-addon-api failed"; }

export CC="$TC/aarch64-linux-android${API}-clang"
export CXX="$TC/aarch64-linux-android${API}-clang++"
export AR="$TC/llvm-ar"
export LINK="$CXX"

npx --yes node-gyp@11 configure --nodedir="$WORK/node-v$NODE_VERSION" --arch=arm64 > "$WORK/gyp-configure.log" 2>&1 \
  || { tail -n 60 "$WORK/gyp-configure.log"; fail "node-gyp configure failed"; }
npx --yes node-gyp@11 build --nodedir="$WORK/node-v$NODE_VERSION" -j 4 > "$WORK/gyp-build.log" 2>&1 \
  || { tail -n 80 "$WORK/gyp-build.log"; fail "node-gyp build failed"; }

SO="$PKG/build/Release/watcher.node"
[ -f "$SO" ] || fail "build did not produce build/Release/watcher.node"
cp "$SO" "$OUT/watcher.node"
log "build: ok ($(stat -c %s "$SO") bytes)"

# 6. ELF checks
READELF="$TC/llvm-readelf"
NM="$TC/llvm-nm"

machine="$("$READELF" -h "$SO" | awk -F: '/Machine/{print $2}')"
log "machine:$machine"
case "$machine" in *AArch64*) ;; *) fail "machine is not AArch64:$machine";; esac

ALLOWED_NEEDED="libc.so libm.so libdl.so liblog.so"
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
  "$NM" -D --defined-only "$LIBDIR/$n" | awk '{print $NF}' | sed 's/@.*//' >> "$defined"
done
missing=""
while read -r sym; do
  base="${sym%%@*}"
  case "$base" in napi_*|node_api_*) continue;; esac
  grep -qx "$base" "$defined" || missing="$missing $base"
done < <("$NM" -D --undefined-only "$SO" | awk '{print $NF}')
[ -z "$missing" ] || fail "undefined symbols not provided by the NEEDED system libraries:$missing"
log "undefined symbols: all provided (Node-API from the host node process, the rest by NEEDED libraries)"

log "result: BUILT (not yet loaded on a device)"
log "sha256 watcher.node: $(sha256sum "$SO" | cut -d' ' -f1)"
