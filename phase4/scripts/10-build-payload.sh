#!/usr/bin/env bash
# 10-build-payload.sh — assemble the embedded runtime payload for the APK.
#
# Runs on a networked build host (CI ubuntu-latest). Outputs:
#   phase4/out/engine/jniLibs/<abi>/libbun.so  (Bun-for-Android, bionic)
#   phase4/out/engine/jniLibs/<abi>/libgit.so  (Git v2.48.1, Android/Bionic executable)
#   phase4/out/engine/jniLibs/<abi>/librg.so   (ripgrep 15.1.0, Android/Bionic executable)
#   phase4/out/engine/jniLibs/<abi>/libnode.so (Node 24, Android/Bionic executable)
#   phase4/out/engine/jniLibs/<abi>/libwget.so (sealed curl-backed wget launcher)
#   phase4/out/engine/jniLibs/<abi>/libssh*.so (OpenSSH 10 client suite)
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

if [ "$#" -gt 0 ]; then ABIS=("$@"); else ABIS=(arm64-v8a x86_64); fi
for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a|x86_64) ;;
    *) echo "FATAL: unsupported build ABI '$abi' (expected arm64-v8a or x86_64)" >&2; exit 2 ;;
  esac
done
# Never let a partial or previous invocation silently supply an ABI artifact
# that this invocation did not build.
rm -rf "$ENGINE"
mkdir -p "$ENGINE/jniLibs/arm64-v8a" "$ENGINE/jniLibs/x86_64" "$ENGINE/assets"


PINNED_COMMIT="05ea5073be967c779d326929b2de6228dda4159d"
GIT_PIN="v2.48.1"
ZLIB_PIN="v1.3.1"
# v9.28 (owner: "start the parked item") - HTTPS transport for the bundled
# git. curl is the only http backend git supports; Mbed TLS is the TLS
# library BECAUSE OF LICENSING: git is GPL-2.0-only, OpenSSL 3 is
# Apache-2.0 (GPLv2-incompatible), while Mbed TLS is dual Apache-2.0 /
# GPL-2.0-or-later - we use its GPL option, which composes cleanly.
CURL_PIN="8.10.1"
MBEDTLS_PIN="3.6.2"
JQ_PIN="1.7.1"
PYTHON_PIN="3.13.1"
# Node 24 is the active LTS line and satisfies upstream OpenCode's node:sqlite
# floor (>=22.5). npm is the version bundled in this exact Node release.
NODE_PIN="24.22.1"
NPM_PIN="11.19.1"
# Android fixes from the exact Termux nodejs-lts recipe current for NODE_PIN.
# Upstream calls Android unsupported; pinning these patches is what turns its
# best-effort android-configure into a reproducible CLI build.
NODE_TERMUX_PATCH_PIN="2d6b9d566c2e933c50e675198a0d7847a96deb61"
# The final core-tier network tool. OpenSSH uses the exact portable release and
# Android patch lineage from this same Termux commit; one OpenSSL libcrypto is
# packaged beside the clients in the APK native directory, never as writable
# payload data.
OPENSSH_PIN="10.6p1"
OPENSSH_TAG="V_10_6_P1"
OPENSSL_PIN="3.6.5"
ONIGURUMA_PIN="6.9.10"
BUN_PIN="1.3.14"
RG_PIN="15.1.0"
PAYLOAD_VERSION=7   # 7 = Phase 9: bare OPENCODE_VERSION + pre-seeded @opencode-ai/plugin tree (plugin-seed/); 6 = bare version only; 5 = Phase 5 launcher/Keystore layout
STATUS="$ENGINE/build.status"
: > "$STATUS"
note() { echo "$*" | tee -a "$STATUS"; }

# v9.42 (step 1, slice C): the sealed-payload generator is build-side code that
# decides which half of a payload may be executed. It is checked before anything
# expensive runs, because its one rule - an executable may never ship as data -
# is the rule the whole store-legal shape rests on.
SEAL_PY="$DIR/scripts/12-seal-payload.py"
python3 "$SEAL_PY" --self-test | tee -a "$STATUS" || { note "FATAL: 12-seal-payload.py self-test failed"; exit 1; }

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
NDK_PIN="${P4_NDK_VERSION:-28.2.13676358}"
# Pin the NDK rather than taking "the newest installed", and keep the search for a
# *usable* clang wrapper as the fallback. Reason, recorded because it bit mid-phase:
# on 2026-09-06 the CI image gained NDK r29.0.14206865, the newest-wins glob below
# silently switched compilers with no repo change at all, and r29 links
# x86_64 child-shim.c as ET_EXEC - which our own PIE assertion (correctly) refuses,
# because Android cannot exec a fixed-address binary out of nativeLibraryDir. A
# build script that follows the runner's mood is not a build script; pin, log the
# pin, and let the assertion print the ELF header if a pinned toolchain ever drifts.
NDK_BIN=""
if [ -x "$NDK_ROOT/$NDK_PIN/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ] \
   && [ -e "$NDK_ROOT/$NDK_PIN/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android29-clang" ]; then
  NDK_BIN="$NDK_ROOT/$NDK_PIN/toolchains/llvm/prebuilt/linux-x86_64/bin"
  note "NDK pinned to $NDK_PIN (override with P4_NDK_VERSION)"
else
  note "pinned NDK $NDK_PIN not installed; falling back to the newest wrapper found - expect drift"
fi
for c in $(find "$NDK_ROOT" -type f -name 'x86_64-linux-android29-clang' 2>/dev/null | sort -r); do
  [ -n "$NDK_BIN" ] && break
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
  # v9.42 (step 1, slice A): the harness exec-model probe. A PIE executable
  # shipped under a lib*.so name so the platform extracts it into
  # nativeLibraryDir; the on-device gate (X1-X3) executes it from there,
  # through a prefix symlink, and from a COPY in app data - and requires the
  # first two to work and the third to be refused. Without this measurement the
  # whole harness (a real userland at targetSdk 29+) is an assumption.
  if ! "$cc" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
        -o "$outdir/libocexecprobe.so" "$NATIVE_SRC/exec-probe.c" 2>"$WORK/probe-$abi.log"; then
    note "FATAL: libocexecprobe build failed for $abi; clang stderr:"; cat "$WORK/probe-$abi.log" | tee -a "$STATUS"; exit 1
  fi
  chmod 755 "$outdir/libseccompshim.so" "$outdir/libexecshim.so" "$outdir/libchildshim.so" "$outdir/libocexecprobe.so"
  note "seccomp helpers $abi -> libseccompshim.so ($(stat -c%s "$outdir/libseccompshim.so") B)," \
       "libexecshim.so ($(stat -c%s "$outdir/libexecshim.so") B)," \
       "libchildshim.so ($(stat -c%s "$outdir/libchildshim.so") B)," \
       "libocexecprobe.so ($(stat -c%s "$outdir/libocexecprobe.so") B)"
  "$NDK_BIN/llvm-nm" -D "$outdir/libseccompshim.so" 2>/dev/null | grep -q opencode_seccomp_init \
    || { note "FATAL: opencode_seccomp_init missing from libseccompshim.so ($abi)"; exit 1; }
  # The wrapper must be a PIE (DYN) executable so Android will exec it from
  # nativeLibraryDir (ET_EXEC fixed-address binaries are rejected). llvm-readelf
  # is bundled with the NDK; fall back to the host readelf if absent.
  READELF="$NDK_BIN/llvm-readelf"
  [ -x "$READELF" ] || READELF="$(command -v llvm-readelf readelf | head -1)"
  # Keep the ELF header in the log for BOTH files, and name the toolchain that
  # produced them: run #21 failed this assertion with no way to see whether the
  # binary was EXEC or readelf had simply misbehaved. Cheap to record, decisive
  # to read, and the alternative (relaxing the grep) would let a non-executable
  # shim ship to devices.
  for so in libexecshim.so libchildshim.so libocexecprobe.so; do
    "$READELF" -h "$outdir/$so" 2>&1 | tee -a "$STATUS" | grep -q 'DYN' \
      || { note "FATAL: $so ($abi) is not a PIE/DYN executable (ELF header above) - NDK: $NDK_BIN"; exit 1; }
  done
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
curl -fsSL --retry 3 --max-time 180 -o "$WORK/zlib.tgz" \
  "https://github.com/madler/zlib/archive/refs/tags/${ZLIB_PIN}.tar.gz"
rm -rf "$WORK/zlib-src" && mkdir -p "$WORK/zlib-src"
tar xzf "$WORK/zlib.tgz" -C "$WORK/zlib-src" --strip-components=1
rm -rf "$WORK/git-src"
timeout 300 git clone -q --depth 1 --branch "$GIT_PIN" https://github.com/git/git "$WORK/git-src"
(cd "$WORK/git-src" && git rev-parse HEAD > "$ENGINE/git.upstream.commit.txt")
note "git source: $(cat "$ENGINE/git.upstream.commit.txt")"

# v9.28: TLS + http sources for git's remote-https helper (release tarballs,
# self-contained - the mbedTLS git tree needs submodules, its tarball does not).
curl -fsSL --retry 3 --max-time 180 -o "$WORK/mbedtls.tar.bz2" \
  "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_PIN}/mbedtls-${MBEDTLS_PIN}.tar.bz2"
rm -rf "$WORK/mbedtls-src" && mkdir -p "$WORK/mbedtls-src"
tar xjf "$WORK/mbedtls.tar.bz2" -C "$WORK/mbedtls-src" --strip-components=1
curl -fsSL --retry 3 --max-time 180 -o "$WORK/curl.tgz" \
  "https://github.com/curl/curl/releases/download/curl-${CURL_PIN//./_}/curl-${CURL_PIN}.tar.gz"
rm -rf "$WORK/curl-src" && mkdir -p "$WORK/curl-src"
tar xzf "$WORK/curl.tgz" -C "$WORK/curl-src" --strip-components=1

# v9.42 step 1 (slice E): jq, and the regex engine it links. Release tarballs
# for both, and oniguruma built SEPARATELY rather than as jq's "builtin":
# jq's repo carries oniguruma as a git SUBMODULE, so a clone has an empty
# modules/oniguruma and --with-oniguruma=builtin fails there, while the
# release tarball may or may not bundle it depending on how it was rolled.
# Pointing jq at a prefix we built ourselves makes the answer independent of
# that, and keeps regex support (test/match/sub) instead of shipping a jq
# that errors on the regex functions.
curl -fsSL --retry 3 --max-time 180 -o "$WORK/jq.tgz" \
  "https://github.com/jqlang/jq/releases/download/jq-${JQ_PIN}/jq-${JQ_PIN}.tar.gz"
rm -rf "$WORK/jq-src" && mkdir -p "$WORK/jq-src"
tar xzf "$WORK/jq.tgz" -C "$WORK/jq-src" --strip-components=1
curl -fsSL --retry 3 --max-time 180 -o "$WORK/oniguruma.tgz" \
  "https://github.com/kkos/oniguruma/releases/download/v${ONIGURUMA_PIN}/onig-${ONIGURUMA_PIN}.tar.gz"
rm -rf "$WORK/oniguruma-src" && mkdir -p "$WORK/oniguruma-src"
tar xzf "$WORK/oniguruma.tgz" -C "$WORK/oniguruma-src" --strip-components=1

# v9.42 step 1: CPython. The repo tarball, not a release asset: cpython
# commits its generated `configure`, so no autotools are needed on the host,
# and the same URL is verifiable outside CI.
curl -fsSL --retry 3 --max-time 300 -o "$WORK/cpython.tgz" \
  "https://codeload.github.com/python/cpython/tar.gz/refs/tags/v${PYTHON_PIN}"
rm -rf "$WORK/cpython-src" && mkdir -p "$WORK/cpython-src"
tar xzf "$WORK/cpython.tgz" -C "$WORK/cpython-src" --strip-components=1

# Node 24 + the Android patch set maintained by Termux. codeload is deliberate:
# GitHub release-asset hosts are not reachable in the reproducible build
# environment, while tag/commit archives are. The Node archive is kept packed
# and extracted fresh per ABI so two V8 output trees do not exhaust runner disk.
curl -fsSL --retry 3 --max-time 600 -o "$WORK/node.tgz" \
  "https://codeload.github.com/nodejs/node/tar.gz/refs/tags/v${NODE_PIN}"
curl -fsSL --retry 3 --max-time 300 -o "$WORK/termux-node-patches.tgz" \
  "https://codeload.github.com/termux/termux-packages/tar.gz/${NODE_TERMUX_PATCH_PIN}"
rm -rf "$WORK/termux-node-patches" && mkdir -p "$WORK/termux-node-patches"
tar xzf "$WORK/termux-node-patches.tgz" -C "$WORK/termux-node-patches" \
  --strip-components=3 \
  "termux-packages-${NODE_TERMUX_PATCH_PIN}/packages/nodejs-lts"
[ -f "$WORK/termux-node-patches/node.gyp.patch" ] \
  || { note "FATAL: pinned Termux Node patch set did not extract"; exit 1; }

# OpenSSH's portable source and the crypto library its complete algorithm set
# needs. Both archives come from pinned GitHub tags (not mutable package repos).
curl -fsSL --retry 3 --max-time 300 -o "$WORK/openssl.tgz" \
  "https://codeload.github.com/openssl/openssl/tar.gz/refs/tags/openssl-${OPENSSL_PIN}"
rm -rf "$WORK/openssl-src" && mkdir -p "$WORK/openssl-src"
tar xzf "$WORK/openssl.tgz" -C "$WORK/openssl-src" --strip-components=1
curl -fsSL --retry 3 --max-time 300 -o "$WORK/openssh.tgz" \
  "https://codeload.github.com/openssh/openssh-portable/tar.gz/refs/tags/${OPENSSH_TAG}"
rm -rf "$WORK/openssh-src" && mkdir -p "$WORK/openssh-src"
tar xzf "$WORK/openssh.tgz" -C "$WORK/openssh-src" --strip-components=1
rm -rf "$WORK/termux-openssh-patches" && mkdir -p "$WORK/termux-openssh-patches"
tar xzf "$WORK/termux-node-patches.tgz" -C "$WORK/termux-openssh-patches" \
  --strip-components=3 \
  "termux-packages-${NODE_TERMUX_PATCH_PIN}/packages/openssh"
[ -f "$WORK/termux-openssh-patches/ssh-keygen.c.patch" ] \
  || { note "FATAL: pinned Termux OpenSSH patch set did not extract"; exit 1; }

# Keep only the Termux fixes the client suite needs. Server/auth patches pull
# Termux-only services into the link and do not belong in this app. These seven
# patches cover Android's no-hardlink cases, mux sockets, explicit memory wipe,
# passwd iteration, config ownership, key replacement and PTY permissions.
for p in hostfile.c.patch mux.c.patch openbsd-compat_explicit_bzero.c.patch \
         openbsd-compat_xcrypt.c.patch readconf.c.patch ssh-keygen.c.patch sshpty.c.patch; do
  ( cd "$WORK/openssh-src" && patch -p1 -s < "$WORK/termux-openssh-patches/$p" ) \
    || { note "FATAL: pinned OpenSSH patch failed: $p"; exit 1; }
done
# Android's synthetic passwd record does not point at this app's HOME, and the
# upstream scp/sftp default bakes /usr/bin/ssh. Make both client-only boundaries
# dynamic without baking the debug or release application id into an ELF.
python3 - "$WORK/openssh-src" <<'EOF'
from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root / "ssh.c"
s = p.read_text()
needle = "\t/* Take a copy of the returned structure. */\n\tpw = pwcopy(pw);\n"
replacement = needle + """\t/* Android synthesizes a passwd home outside the app sandbox. */
\tif ((cp = getenv("HOME")) != NULL && cp[0] == '/') {
\t\tfree(pw->pw_dir);
\t\tpw->pw_dir = xstrdup(cp);
\t}
"""
if s.count(needle) != 1:
    raise SystemExit("OpenSSH ssh.c home patch point drifted")
p.write_text(s.replace(needle, replacement))

p = root / "ssh-agent.c"
s = p.read_text()
needle = """\t/* drop */
\t(void)setegid(getgid());
\t(void)setgid(getgid());
"""
replacement = """\t/* Android apps start at their final fixed uid/gid. API 34 seccomp kills
\t * the otherwise redundant set[e]gid-to-self syscalls. */
#ifndef __ANDROID__
\t(void)setegid(getgid());
\t(void)setgid(getgid());
#endif
"""
if s.count(needle) != 1:
    raise SystemExit("OpenSSH ssh-agent gid-drop patch point drifted")
p.write_text(s.replace(needle, replacement))

p = root / "misc.c"
s = p.read_text()
needle = """\tdir = odir = xstrdup(target);

\tif (*dir == '/' &&
\t    (fd = open("/", O_RDONLY|O_DIRECTORY)) == -1) {
\t\terror_f("open(\\\"/\\\"): %s", strerror(errno));
\t\tfree(odir);
\t\treturn -1;
\t}
"""
replacement = """\tdir = odir = xstrdup(target);

#ifdef __ANDROID__
\t/* SELinux denies opening the filesystem root, but the app may safely
\t * traverse below its already-openable HOME directory. */
\t{
\t\tconst char *home = getenv("HOME");
\t\tsize_t hlen = home == NULL ? 0 : strlen(home);

\t\tif (*dir == '/' && hlen > 1 && strncmp(dir, home, hlen) == 0 &&
\t\t    (dir[hlen] == '/' || dir[hlen] == '\\0')) {
\t\t\tif ((fd = open(home, O_RDONLY|O_DIRECTORY)) == -1) {
\t\t\t\terror_f("open HOME: %s", strerror(errno));
\t\t\t\tfree(odir);
\t\t\t\treturn -1;
\t\t\t}
\t\t\tdir += hlen;
\t\t\twhile (*dir == '/')
\t\t\t\tdir++;
\t\t}
\t}
#endif
\tif (*dir == '/' &&
\t    (fd = open("/", O_RDONLY|O_DIRECTORY)) == -1) {
\t\terror_f("open(\\\"/\\\"): %s", strerror(errno));
\t\tfree(odir);
\t\treturn -1;
\t}
"""
if s.count(needle) != 1:
    raise SystemExit("OpenSSH mkdir_path HOME patch point drifted")
p.write_text(s.replace(needle, replacement))

p = root / "pathnames.h"
s = p.read_text()
needle = '#define _PATH_SSH_PROGRAM\t\t"/usr/bin/ssh"'
if s.count(needle) != 1:
    raise SystemExit("OpenSSH _PATH_SSH_PROGRAM patch point drifted")
p.write_text(s.replace(needle, '#define _PATH_SSH_PROGRAM\t\t"ssh"'))

# Bionic deliberately keeps struct __res_state opaque. Keep OpenSSH's bundled
# SSHFP parser and public res_query() path, but initialize through res_init()
# instead of dereferencing glibc/BSD resolver internals.
p = root / "openbsd-compat" / "getrrsetbyname.c"
s = p.read_text()
needle = """#ifndef HAVE__RES_EXTERN
struct __res_state _res;
#endif
"""
replacement = """#if !defined(HAVE__RES_EXTERN) && !defined(__ANDROID__)
struct __res_state _res;
#endif
"""
if s.count(needle) != 1:
    raise SystemExit("OpenSSH resolver global state patch point drifted")
s = s.replace(needle, replacement)
needle = "\tstruct __res_state *_resp = _THREAD_PRIVATE(_res, _res, &_res);\n"
replacement = """#ifndef __ANDROID__
\tstruct __res_state *_resp = _THREAD_PRIVATE(_res, _res, &_res);
#endif
"""
if s.count(needle) != 1:
    raise SystemExit("OpenSSH resolver state declaration patch point drifted")
s = s.replace(needle, replacement)
needle = """\t/* initialize resolver */
\tif ((_resp->options & RES_INIT) == 0 && res_init() == -1) {
\t\tresult = ERRSET_FAIL;
\t\tgoto fail;
\t}

#ifdef DEBUG
\t_resp->options |= RES_DEBUG;
#endif /* DEBUG */

#ifdef RES_USE_DNSSEC
\t/* turn on DNSSEC if EDNS0 is configured */
\tif (_resp->options & RES_USE_EDNS0)
\t\t_resp->options |= RES_USE_DNSSEC;
#endif /* RES_USE_DNSEC */
"""
replacement = """\t/* initialize resolver */
#ifdef __ANDROID__
\t/* Bionic exposes the query API but intentionally hides resolver state. */
\tif (res_init() == -1) {
\t\tresult = ERRSET_FAIL;
\t\tgoto fail;
\t}
#else
\tif ((_resp->options & RES_INIT) == 0 && res_init() == -1) {
\t\tresult = ERRSET_FAIL;
\t\tgoto fail;
\t}

# ifdef DEBUG
\t_resp->options |= RES_DEBUG;
# endif /* DEBUG */

# ifdef RES_USE_DNSSEC
\t/* turn on DNSSEC if EDNS0 is configured */
\tif (_resp->options & RES_USE_EDNS0)
\t\t_resp->options |= RES_USE_DNSSEC;
# endif /* RES_USE_DNSEC */
#endif /* __ANDROID__ */
"""
if s.count(needle) != 1:
    raise SystemExit("OpenSSH resolver initialization patch point drifted")
p.write_text(s.replace(needle, replacement))
EOF

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

  # ---- v9.28: Mbed TLS (static libs only; GPL-2.0-or-later option) --------
  local mprefix="$WORK/mbedtls-$abi-android"
  ( cd "$WORK/mbedtls-src"
    make clean >/dev/null 2>&1 || true
    timeout 900 make -j4 lib CC="$cc" AR="$ar" CFLAGS="-O2 -fPIC" >/dev/null
    rm -rf "$mprefix" && mkdir -p "$mprefix/lib" "$mprefix/include"
    cp library/libmbedtls.a library/libmbedx509.a library/libmbedcrypto.a "$mprefix/lib/"
    cp -r include/mbedtls include/psa "$mprefix/include/"
  ) || { note "FATAL: Android Mbed TLS build failed for $abi"; return 1; }

  # ---- v9.28: curl, static, http(s)-only, TLS = Mbed TLS ------------------
  # No baked-in CA defaults: the runtime env points git at Android's own CA
  # store (GIT_SSL_CAPATH), so cert trust follows the DEVICE, not our build.
  local cprefix="$WORK/curl-$abi-android"
  ( cd "$WORK/curl-src"
    make distclean >/dev/null 2>&1 || true
    ./configure --host="$triple" --prefix="$cprefix" \
      --disable-shared --enable-static \
      --with-mbedtls="$mprefix" --with-zlib="$zprefix" \
      --without-ca-bundle --without-ca-path \
      --without-brotli --without-zstd --without-libpsl --without-libidn2 \
      --without-nghttp2 --disable-ldap --disable-ldaps --disable-ftp \
      --disable-file --disable-telnet --disable-tftp --disable-pop3 \
      --disable-imap --disable-smb --disable-smtp --disable-gopher \
      --disable-mqtt --disable-rtsp --disable-dict --disable-manual \
      --disable-docs --disable-threaded-resolver \
      CC="$cc" AR="$ar" RANLIB="$ranlib" >/dev/null
    timeout 900 make -j4 >/dev/null
    make install >/dev/null
  ) || { note "FATAL: Android curl build failed for $abi"; return 1; }

  ( cd "$WORK/git-src"
    make clean >/dev/null 2>&1 || true
    # v9.28: NO_CURL is GONE - git builds its smart-HTTP transport
    # (git-remote-http) against the static curl above. NO_OPENSSL stays:
    # git itself never links a TLS library; only curl does (Mbed TLS).
    # NO_EXPAT stays: it only disables dumb DAV push; GitHub is smart HTTP.
    timeout 1200 make -j4 \
      CC="$cc" AR="$ar" RANLIB="$ranlib" NEEDS_LIBRT= \
      CFLAGS="-O2 -I$zprefix/include" \
      LDFLAGS="-L$zprefix/lib -Wl,-z,max-page-size=16384" \
      ZLIB_PATH="$zprefix" \
      CURL_CFLAGS="-I$cprefix/include" \
      CURL_LDFLAGS="-L$cprefix/lib -lcurl -L$mprefix/lib -lmbedtls -lmbedx509 -lmbedcrypto -lz" \
      NO_REGEX=NeedsStartEnd \
      NO_PERL=YesPlease NO_PYTHON=YesPlease NO_TCLTK=YesPlease NO_GETTEXT=YesPlease \
      NO_ICONV=YesPlease NO_OPENSSL=YesPlease NO_EXPAT=YesPlease \
      NO_LIBPCRE2=YesPlease NO_PTHREADS=YesPlease NO_INSTALL_HARDLINKS=YesPlease \
      git git-remote-http
  ) || { note "FATAL: Android Git build failed for $abi"; return 1; }
  [ -x "$WORK/git-src/git" ] || { note "FATAL: Android Git binary missing for $abi"; return 1; }
  [ -x "$WORK/git-src/git-remote-http" ] || { note "FATAL: git-remote-http missing for $abi"; return 1; }
  cp "$WORK/git-src/git" "$outdir/libgit.so"
  chmod 755 "$outdir/libgit.so"
  # One helper binary serves both schemes; the app symlinks
  # bin/git-remote-https AND bin/git-remote-http at it (upstream ships the
  # https name as a copy/hardlink of the same executable).
  cp "$WORK/git-src/git-remote-http" "$outdir/libgitremotehttp.so"
  chmod 755 "$outdir/libgitremotehttp.so"
  # ---- v9.42 step 1 (slice E): the curl CLI, from the build git needs ----
  # Git's smart-HTTP transport links this exact static curl, so the command
  # line tool is FREE: the binary is built whether we ship it or not, and
  # shipping it is one copy. It carries NO baked-in CA path (see the
  # configure line above) on purpose - trust comes from CURL_CA_PATH at
  # runtime, pointing at Android's own CA store, the same directory git gets
  # through GIT_SSL_CAPATH. A baked-in bundle would trust yesterday's CAs.
  [ -x "$cprefix/bin/curl" ] || { note "FATAL: curl CLI missing for $abi"; return 1; }
  cp "$cprefix/bin/curl" "$outdir/libcurl.so"
  chmod 755 "$outdir/libcurl.so"
  note "$abi curl CLI: $(stat -c%s "$outdir/libcurl.so") bytes"

  # wget is a native argument translator over this exact curl executable, not
  # a second TLS stack. It preserves the familiar non-recursive downloader
  # interface while HTTPS continues to use Mbed TLS + Android's live CA store.
  # Unsupported Wget-only crawl options fail loudly instead of changing meaning.
  if ! "$cc" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
        -o "$outdir/libwget.so" "$NATIVE_SRC/wget-shim.c" \
        2>"$WORK/wget-$abi.log"; then
    note "FATAL: wget compatibility launcher build failed for $abi; clang stderr:"
    cat "$WORK/wget-$abi.log" | tee -a "$STATUS"
    return 1
  fi
  chmod 755 "$outdir/libwget.so"
  note "$abi wget launcher: $(stat -c%s "$outdir/libwget.so") bytes"
  if git_version=$("$outdir/libgit.so" --version 2>&1 | head -1); then
    note "$abi Android Git host execution: $git_version"
  else
    # The build host is x86_64; arm64 and Android dynamic-linker binaries are
    # intentionally not executed here. Device gates execute the exact binary
    # through the installed APK instead.
    note "$abi Android Git built (cross-ABI host execution skipped)"
  fi
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
  # Cargo's release profile still left the full Rust/DWARF symbol payload in
  # both shipped binaries (~35 MiB arm64, ~36 MiB x86_64). Those symbols are
  # never read by Android or rg and `file` proved the old APK said
  # "with debug_info, not stripped". Every other large source-built runtime is
  # stripped already. Strip rg with the pinned NDK tool too, then fail rather
  # than silently paying that download cost again if the tool does not work.
  "$toolchain/llvm-strip" "$outdir/librg.so" \
    || { note "FATAL: llvm-strip failed for Android ripgrep ($abi)"; return 1; }
  chmod 755 "$outdir/librg.so"
  local rg_file
  rg_file="$(file "$outdir/librg.so" | cut -d: -f2-)"
  case "$rg_file" in
    *"not stripped"*|*"with debug_info"*)
      note "FATAL: Android ripgrep still contains debug symbols after llvm-strip ($abi): $rg_file"; return 1 ;;
  esac
  note "$abi Android ripgrep OK (stripped): $rg_file bytes=$(stat -c%s "$outdir/librg.so")"
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
  // Do not ship a production source map. The previous linked map was the
  // single largest data-payload file: 54,934,336 bytes versus 34,386,968 for
  // the executable JS bundle itself. It is developer-only diagnostic input;
  // neither Bun nor OpenCode needs it to run, and all runtime/device gates
  // exercise this exact no-map bundle before release.
  sourcemap: "none",
  external: ["jsonc-parser", "@lydell/node-pty", "bun-pty"],
  define: {
    OPENCODE_MODELS_DEV: generated.modelsData,
    // PHASE 9 FIX (carried bug): the runtime reports InstallationVersion verbatim
    // and upstream config.ts installs `@opencode-ai/plugin@<InstallationVersion>`
    // into every config dir whenever the channel is not "local". npm publishes
    // 1.18.23 but no "1.18.23-android", so every plugin install failed
    // (NpmInstallFailedError, Phase 8 §11). The version string must be the bare
    // upstream semver; the Android provenance lives in OPENCODE_CHANNEL (which
    // upstream only uses for isPreview()/user-agent - it is not part of the
    // npm spec) and in the runtime manifest.
    OPENCODE_VERSION: `"1.18.23"`,
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

# Phase 9: pre-seed @opencode-ai/plugin@<pinned> for the global config dir.
# Upstream (config.ts) forks `npm install @opencode-ai/plugin@<OPENCODE_VERSION>`
# into every config dir on instance load unless node_modules already exists and
# package-lock.json covers the package (core/src/npm.ts Npm.install). With the
# bare version define the install now runs on-device - and Arborist trips a
# syscall Android's app seccomp filter kills (SIGSYS, exit 159; Phase 9 CI run
# 35132822991 saw 113 crash/restart cycles). Shipping the installed tree means
# the on-device npm path is never entered for the default plugin set, and the
# plugin API works offline. The extractor promotes plugin-seed/ into
# xdg/config/opencode/ (never overwriting a user's opencode.json).
note "=== plugin seed: @opencode-ai/plugin@${OPENCODE_VERSION_BARE:-1.18.23} ==="
SEED="$WORK/plugin-seed"
rm -rf "$SEED" && mkdir -p "$SEED"
( cd "$SEED" \
  && printf '{ "name": "opencode-android-config", "private": true, "version": "0.0.0" }\n' > package.json \
  && npm install --ignore-scripts --no-audit --no-fund --no-bin-links --save-exact \
       "@opencode-ai/plugin@${OPENCODE_VERSION_BARE:-1.18.23}" >> "$STATUS" 2>&1 \
  && find node_modules \( -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' -o -name '*.map' \
        -o -name '*.md' -o -name 'LICENSE*' -o -name '*.ts' -o -name '*.mts' -o -name '*.cts' \) -type f -delete \
  && rm -rf node_modules/.bin node_modules/@msgpackr-extract \
  && find node_modules -type l -delete \
  && find node_modules -type d -empty -delete )
[ -f "$SEED/node_modules/@opencode-ai/plugin/package.json" ] || { note "plugin seed FAILED"; exit 1; }
mkdir -p "$STAGE/plugin-seed"
cp -r "$SEED/node_modules" "$SEED/package.json" "$SEED/package-lock.json" "$STAGE/plugin-seed/"
note "plugin seed: $(find "$STAGE/plugin-seed" -type f | wc -l) files, $(du -sk "$STAGE/plugin-seed" | cut -f1) KB, plugin $(python3 -c 'import json;print(json.load(open("'"$STAGE"'/plugin-seed/node_modules/@opencode-ai/plugin/package.json"))["version"])')"

# Pack the data payload only after EVERY producer has populated STAGE. This is
# a function, rather than work done here, because Python's stdlib is added much
# later (after the cross-build functions below run). Boards 40191275984 and
# 40195652075 built STAGE/usr/lib/python3.13.zip after the old tar command had
# already run; the interpreter shipped but its stdlib never entered the APK.
pack_runtime_payload() {
  local payload_tgz="$ENGINE/assets/runtime-payload.tar.gz" payload_sha=""
  # Deterministic POSIX ustar (sorted, no owner/mtime noise) then gzip. The
  # format is not cosmetic: our small on-device reader understands ustar's
  # prefix field, while GNU long-name extension records are deliberately
  # skipped. npm adds paths over 100 bytes; those entries were absent after
  # extraction on board 40285201790 even though the manifest had the full name.
  ( cd "$STAGE" && find . -type f | sed 's|^\./||' | sort \
      | tar --format=ustar --owner=0 --group=0 --numeric-owner --no-recursion -T - -cf - \
      | gzip -9n > "$payload_tgz" )
  payload_sha="$(sha256sum "$payload_tgz" | cut -d' ' -f1)"
  note "payload tarball: $(stat -c%s "$payload_tgz") bytes sha256=$payload_sha"

  # Manifest: per-file sha256 + size, paths relative to filesDir.
  python3 - "$STAGE" "$ENGINE/assets/runtime-manifest.json" "$payload_sha" "$GOT_COMMIT" <<'EOF'
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
    "payloadVersion": int(os.environ.get("PAYLOAD_VERSION", "7")),
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
}

# ---------------------------------------------------------------------------
# The sealed layout for the runtime this payload ships. Every executable here
# is already a lib*.so in jniLibs; the manifest says which prefix path it must
# appear at, and the app links them at startup with the vendored
# harness.sealed.SealedLayout instead of hand-rolling symlinks. Same links as
# before, now derived from a shipped, auditable asset - and checked by
# ExecAudit on the device (gate T3).
# ---------------------------------------------------------------------------
note "=== [6.5/6] sealed userland core: a shell and the coreutils (v9.42 slice C2) ==="
# Why these two, and why they are the cheapest honest answer to "full tools".
# The agent has no shell and no coreutils today: the runtime is bun, git and
# ripgrep, and the environment brief tells the model so. toybox is AOSP's own
# toolbox - maintained FOR bionic, every command in one ELF that dispatches on
# argv[0], so ~190 tools cost one binary and one symlink each - and mksh was
# Android's /system/bin/sh for years. Both cross-compile with the NDK, both
# ship as lib*.so, and every command becomes a symlink into the APK, which is
# the only shape an app may execute at API 29+.
TOYBOX_PIN="6a0c0ef5eeb2c43e4feef9781398c1718b8bddcb"
MKSH_PIN="341752823184238dc9b2b0a22b48da984f25442d"
SEALED_EXEC_RECORDS="$WORK/sealed-exec-records.txt"

checkout_pinned() {  # $1=dir $2=url $3=sha
  rm -rf "$1" && mkdir -p "$1"
  ( cd "$1" && git init -q . && git remote add origin "$2" \
      && git fetch -q --depth 1 origin "$3" && git checkout -q FETCH_HEAD )
}

build_mksh() {  # $1=abi $2=triple
  local abi="$1" triple="$2" outdir="$ENGINE/jniLibs/$abi"
  local cc="$NDK_BIN/${triple}29-clang" src="$WORK/mksh-$abi"
  checkout_pinned "$src" https://github.com/MirBSD/mksh.git "$MKSH_PIN" \
    || { note "FATAL: mksh checkout failed ($abi)"; return 1; }
  # Build.sh probes the target with the cross compiler rather than running
  # target binaries, and has an explicit Android category.
  ( cd "$src" && CC="$cc" TARGET_OS=Android \
      CFLAGS="-O2 -fPIE" LDFLAGS="-pie -Wl,-z,max-page-size=16384" \
      sh Build.sh ) > "$WORK/mksh-build-$abi.log" 2>&1 \
    || { note "FATAL: mksh build failed ($abi); last 60 log lines:"; \
         tail -60 "$WORK/mksh-build-$abi.log" | tee -a "$STATUS"; return 1; }
  [ -f "$src/mksh" ] || { note "FATAL: mksh produced no binary ($abi)"; \
         tail -40 "$WORK/mksh-build-$abi.log" | tee -a "$STATUS"; return 1; }
  cp "$src/mksh" "$outdir/libmksh.so"
  note "mksh $abi: $(stat -c%s "$outdir/libmksh.so") bytes"
}

build_toybox() {  # $1=abi $2=triple
  local abi="$1" triple="$2" outdir="$ENGINE/jniLibs/$abi"
  local cc="$NDK_BIN/${triple}29-clang" src="$WORK/toybox-$abi"
  checkout_pinned "$src" https://github.com/landley/toybox.git "$TOYBOX_PIN" \
    || { note "FATAL: toybox checkout failed ($abi)"; return 1; }
  # Our miniconfig is AOSP's android config minus the parts that need libraries
  # the NDK does not ship (libselinux, libcrypto, libz). toybox falls back to
  # its own hash and deflate implementations for those.
  ( cd "$src" && KCONFIG_ALLCONFIG="$DIR/payload/toybox-miniconfig" \
      scripts/genconfig.sh -n ) > "$WORK/toybox-config-$abi.log" 2>&1 \
    || { note "FATAL: toybox genconfig failed ($abi)"; \
         tail -40 "$WORK/toybox-config-$abi.log" | tee -a "$STATUS"; return 1; }
  ( cd "$src" && make -j"$(nproc)" toybox CC="$cc" HOSTCC=cc CROSS_COMPILE= \
      CFLAGS="-O2 -fPIE" LDFLAGS="-pie -Wl,-z,max-page-size=16384" \
      STRIP="$NDK_BIN/llvm-strip" ) > "$WORK/toybox-build-$abi.log" 2>&1 \
    || { note "FATAL: toybox build failed ($abi); last 60 log lines:"; \
         tail -60 "$WORK/toybox-build-$abi.log" | tee -a "$STATUS"; return 1; }
  local built="$src/toybox"
  [ -f "$built" ] || built="$src/generated/unstripped/toybox"
  [ -f "$built" ] || { note "FATAL: toybox produced no binary ($abi)"; \
         tail -40 "$WORK/toybox-build-$abi.log" | tee -a "$STATUS"; return 1; }
  cp "$built" "$outdir/libtoybox.so"
  # The command list is read out of the build's own generated tables by
  # scripts/install.c, so it cannot disagree with the binary that was built.
  if [ ! -s "$WORK/toybox-commands.txt" ]; then
    cc -I "$src" "$src/scripts/install.c" -o "$WORK/instlist" 2>>"$STATUS" \
      || { note "FATAL: toybox instlist build failed"; return 1; }
    "$WORK/instlist" | tr ' \t' '\n\n' \
      | grep -E '^[a-z0-9][a-z0-9_.-]*$' | sort -u > "$WORK/toybox-commands.txt"
  fi
  note "toybox $abi: $(stat -c%s "$outdir/libtoybox.so") bytes, " \
       "$(wc -l < "$WORK/toybox-commands.txt") commands"
}

build_oniguruma() {  # $1=abi $2=triple
  local abi="$1" triple="$2"
  local cc="$NDK_BIN/${triple}29-clang" ar="$NDK_BIN/llvm-ar" ranlib="$NDK_BIN/llvm-ranlib"
  local oprefix="$WORK/oniguruma-$abi-android"
  ( cd "$WORK/oniguruma-src"
    make distclean >/dev/null 2>&1 || true
    ./configure --host="$triple" --prefix="$oprefix" \
      --disable-shared --enable-static \
      CC="$cc" AR="$ar" RANLIB="$ranlib" CFLAGS="-O2 -fPIC" >/dev/null
    make -j4 >/dev/null
    make install >/dev/null
  ) || { note "FATAL: oniguruma build failed for $abi"; return 1; }
  [ -f "$oprefix/lib/libonig.a" ] || { note "FATAL: libonig.a missing for $abi"; return 1; }
  note "oniguruma $abi: $(stat -c%s "$oprefix/lib/libonig.a") bytes"
}

build_jq() {  # $1=abi $2=triple
  local abi="$1" triple="$2" outdir="$ENGINE/jniLibs/$abi"
  local cc="$NDK_BIN/${triple}29-clang" ar="$NDK_BIN/llvm-ar" ranlib="$NDK_BIN/llvm-ranlib"
  local oprefix="$WORK/oniguruma-$abi-android" jprefix="$WORK/jq-$abi-android"
  # A fresh tree per ABI: jq builds in-tree, and the two ABIs would otherwise
  # fight over config.status and the generated Makefiles.
  local src="$WORK/jq-$abi"
  rm -rf "$src" && cp -r "$WORK/jq-src" "$src"
  ( cd "$src"
    ./configure --host="$triple" --prefix="$jprefix" \
      --disable-shared --enable-static \
      --with-oniguruma="$oprefix" \
      CC="$cc" AR="$ar" RANLIB="$ranlib" \
      CFLAGS="-O2 -fPIE -I$oprefix/include" \
      LDFLAGS="-pie -L$oprefix/lib -Wl,-z,max-page-size=16384" >/dev/null
    # The release tarball ships a pre-generated parser and lexer, so no host
    # bison/flex is needed - but if make ever decides parser.y is NEWER than
    # parser.c (equal timestamps from the tarball are decided by mtime), it
    # tries to regenerate and fails. Up to date, unconditionally.
    touch src/parser.c src/lexer.c
    timeout 900 make -j4 >/dev/null
    make install >/dev/null
  ) || { note "FATAL: jq build failed for $abi"; return 1; }
  local installed="$jprefix/bin/jq"
  [ -f "$installed" ] || { note "FATAL: jq was not installed for $abi"; return 1; }
  # libtool leaves a wrapper SCRIPT where the binary should be when it links
  # against an uninstalled shared library. A shell script named jq would be
  # sealed, symlinked into the prefix, and then fail at the first call - so
  # check the magic bytes instead of trusting the name.
  head -c 4 "$installed" | grep -q ELF \
    || { note "FATAL: installed jq is not an ELF binary ($abi)"; return 1; }
  cp "$installed" "$outdir/libjq.so"
  chmod 755 "$outdir/libjq.so"
  note "jq $abi: $(stat -c%s "$outdir/libjq.so") bytes"
}


for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_mksh "$abi" "x86_64-linux-android";  build_toybox "$abi" "x86_64-linux-android" ;;
    arm64-v8a) build_mksh "$abi" "aarch64-linux-android"; build_toybox "$abi" "aarch64-linux-android" ;;
  esac
done

# ---------------------------------------------------------------------------
# Python. Two things about this build are not like the others:
#
# 1. EVERY extension module is linked INTO the interpreter. Android cannot
#    dlopen: app data is not executable and the platform will not load a .so
#    from it, so the usual lib-dynload/ of shared modules would be dead
#    weight that fails on first import. Modules/Setup.local names them
#    explicitly, with *static*, and the list is ours rather than whatever the
#    build host happens to detect.
# 2. The stdlib ships as ONE source zip and is read through zipimport - ~570
#    files as one ~4.3 MB payload entry instead of 570 files the extractor has
#    to write and checksum at first run. Do NOT flatten tagged __pycache__
#    files into this zip: sourceless zipimport looks for module.pyc, not
#    module.cpython-313.pyc. Board 40191275984 proved the latter cannot even
#    import encodings during interpreter startup.
#
# Both were measured on a host build before being written here: static
# interpreter 7.3 MB stripped (7.1 MB in Android CI), stdlib source zip ~4.3 MB.
# ---------------------------------------------------------------------------
build_host_python() {
  local src="$WORK/cpython-host" prefix="$WORK/cpython-host-install"
  rm -rf "$src" "$prefix" && cp -r "$WORK/cpython-src" "$src"
  # Cross-compiling needs a build python of the SAME version, and expecting
  # the runner's python to be 3.13 is a bet on the image. Building it costs
  # ~2 minutes and makes the build independent of what CI happens to have.
  ( cd "$src"
    ./configure --prefix="$prefix" --without-ensurepip --disable-test-modules >/dev/null
    make -j4 >/dev/null
    make install >/dev/null
  ) || { note "FATAL: host python build failed"; return 1; }
  [ -x "$prefix/bin/python3.13" ] || { note "FATAL: host python missing"; return 1; }
  note "host python: $("$prefix/bin/python3.13" -c 'import sys;print(sys.version.split()[0])')"
}

build_python() {  # $1=abi $2=triple
  local abi="$1" triple="$2" outdir="$ENGINE/jniLibs/$abi"
  local cc="$NDK_BIN/${triple}29-clang" ar="$NDK_BIN/llvm-ar" ranlib="$NDK_BIN/llvm-ranlib"
  local zprefix="$WORK/zlib-$abi-android" pprefix="$WORK/python-$abi-android"
  local hostpy="$WORK/cpython-host-install/bin/python3.13"
  local src="$WORK/cpython-$abi"
  rm -rf "$src" && cp -r "$WORK/cpython-src" "$src"
  # Modules that are pure C (or need only zlib, which we cross-build) and
  # therefore safe to link statically on bionic.
  local allow="_asyncio _bisect _contextvars _csv _datetime _heapq _interpchannels \
_interpqueues _interpreters _json _lsprof _opcode _pickle _queue _random _socket \
_statistics _struct _typing _zoneinfo array binascii cmath math mmap select _sysconfig \
_elementtree pyexpat _md5 _sha1 _sha2 _sha3 unicodedata _posixsubprocess fcntl resource \
termios zlib _decimal _blake2 _multibytecodec"
  # Modules that must not be built at all. Board 40163562970 failed on two of
  # them, and both failures are instructive rather than incidental:
  #   _ctypes (and likewise _sqlite3, _bz2, _lzma, readline): configure finds
  #     the HOST's libraries on the CI runner and enables the module; the
  #     cross compiler then cannot find ffi.h and the build dies.
  #   _multiprocessing, _posixshmem: bionic has no named POSIX semaphores, so
  #     HAVE_MP_SEMAPHORE is undefined and _PyMp_sem_unlink is never compiled -
  #     while multiprocessing.c calls it whenever POSIX_SEMAPHORES_NOT_ENABLED
  #     is also undefined. The final link fails. There is therefore no
  #     `import multiprocessing` in this python; subprocess is unaffected.
  #   grp, syslog, _ssl, _hashlib, _dbm, _gdbm, _tkinter, curses, _uuid, nis,
  #     ossaudiodev, spwd: libc functions or libraries Android does not have.
  # Filtering these out of the Setup.stdlib copy appended below is NOT enough
  # (that is exactly what the first board did): makesetup also reads
  # Setup.stdlib itself, and that is where _ctypes came from. makesetup is
  # first-rule-wins and reads Setup.local first, so naming a module under
  # *disabled* in Setup.local removes it from every file that follows.
  local drop="_ctypes _ctypes_test grp syslog _posixshmem _multiprocessing \
readline _curses _curses_panel _tkinter _ssl _hashlib _sqlite3 _bz2 _lzma \
_dbm _gdbm _uuid nis ossaudiodev spwd"
  local dropre=""
  dropre=$(printf '%s' "$drop" | tr ' ' '|')
  ( cd "$src"
    { echo "*static*"
      for m in $allow; do
        grep -E "^#?${m} +" Modules/Setup | head -1 | sed 's/^#//'
      done
    } > Modules/Setup.local
    ./configure --host="$triple" --build=x86_64-linux-gnu \
      --prefix="$pprefix" --with-build-python="$hostpy" \
      --disable-shared --without-ensurepip --disable-test-modules \
      ac_cv_file__dev_ptmx=yes ac_cv_file__dev_ptc=no \
      ac_cv_buggy_getaddrinfo=no ac_cv_little_endian_double=yes \
      CC="$cc" AR="$ar" RANLIB="$ranlib" \
      CFLAGS="-O2 -fPIE -I$zprefix/include" \
      LDFLAGS="-pie -L$zprefix/lib -lz -Wl,-z,max-page-size=16384" >/dev/null
    # Whatever configure auto-detected beyond our list: keep the ones bionic
    # can actually support (appended here, so they are static too), and then
    # disable the rest by name. Two details matter:
    #   - Module lines only. Setup.stdlib begins with its own *shared*
    #     marker, and copying that marker would turn everything appended back
    #     into shared objects - unloadable on Android, and not even linkable
    #     with -pie in LDFLAGS (the host linker wants a main()).
    #   - The *disabled* section must come LAST in Setup.local: every module
    #     after the marker is disabled, so anything appended after it would
    #     be switched off by mistake.
    if [ -f Modules/Setup.stdlib ]; then
      grep -E "^[a-z_][a-zA-Z0-9_]* " Modules/Setup.stdlib \
        | grep -vE "^(${dropre}) " >> Modules/Setup.local
    fi
    { echo "*disabled*"; echo "$drop"; } >> Modules/Setup.local
    timeout 1800 make -j4 >/dev/null
    make install >/dev/null
  ) || { note "FATAL: python build failed for $abi (see python-$abi.log)"; return 1; }
  local built="$pprefix/bin/python3.13"
  [ -f "$built" ] || { note "FATAL: python3.13 not installed for $abi"; return 1; }
  head -c 4 "$built" | grep -q ELF || { note "FATAL: python3.13 is not an ELF ($abi)"; return 1; }
  # Nothing may be left behind as a shared module: it could not be loaded.
  local shared=0
  shared=$(find "$pprefix/lib/python3.13/lib-dynload" -name '*.so' 2>/dev/null | wc -l)
  [ "$shared" = "0" ] || { note "FATAL: $shared shared extension modules for $abi (cannot dlopen on Android)"; return 1; }
  "$NDK_BIN/llvm-strip" "$built"
  cp "$built" "$outdir/libpython3.so"
  chmod 755 "$outdir/libpython3.so"
  note "python $abi: $(stat -c%s "$outdir/libpython3.so") bytes (stripped, static modules)"
}

build_python_stdlib() {
  local lib="$WORK/python-stdlib"
  rm -rf "$lib" && cp -r "$WORK/cpython-src/Lib" "$lib"
  mkdir -p "$STAGE/usr/lib"
  ( cd "$lib"
    rm -rf test tests idlelib tkinter lib2to3 distutils
    find . -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true
    # Source is both smaller than the compiled archive and unambiguous to
    # zipimport. Board 40191275984 shipped flattened tagged pycs such as
    # encodings/__init__.cpython-313.pyc; sourceless zipimport actually looks
    # for encodings/__init__.pyc, so the interpreter could not initialize its
    # filesystem codec. Keeping the .py files fixed that in an isolated-prefix
    # host reproduction and costs ~4.3 MB rather than 6.0 MB.
    zip -qr9 "$STAGE/usr/lib/python3.13.zip" .
  ) || { note "FATAL: python stdlib zip failed"; return 1; }
  note "python stdlib source zip: $(stat -c%s "$STAGE/usr/lib/python3.13.zip") bytes"
}

# ---------------------------------------------------------------------------
# Node 24: a REAL Android/Bionic executable, not the 4 KiB-aligned libnode from
# nodejs-mobile (which cannot run on 16 KiB-page devices and has no main()).
# Upstream labels Android unsupported, so use the pinned Termux Android fixes
# current for this exact Node release. Dependencies stay bundled. ICU is FULL
# (--with-intl=full-icu, from the ICU sources and data bundled in the Node
# tarball): RegExp \p{L} and Intl need it, and the owner accepted the larger
# APK for that (owner, 2026-10-28). Inspector, snapshots and code cache stay
# omitted: this is the user's CLI tool, not the OpenCode server (Bun remains
# the server in additive step 1).
#
# A source build remains the authoritative recipe, and a full-icu V8 build is
# slower than the no-Intl one that the previous -v1 cache came from, so one job
# cannot build both ABIs. The cache is therefore bootstrapped one ABI per
# CI run: P4_NODE_CAPTURE_STOP=1 (set by phase10/scripts/00-run-phase10.sh)
# builds the first missing ABI, captures it as sub-2 MiB chunks under
# docs/progress/phase10-evidence (the always post-step commits that directory,
# even when a runner is cancelled), and stops with NODE_CACHE_BOOTSTRAP_CAPTURED.
# Once both ABIs are captured, later runs restore them and build the full APK.
# Set P4_REBUILD_NODE=1 to ignore the cache and reproduce from source.
# ---------------------------------------------------------------------------
# RULE (owner, 2026-10-30): do not change NODE_PIN, NDK_PIN, NODE_TERMUX_PATCH_PIN or the
# -icufull-v2 tag without a planned bootstrap. A new key means no committed cache, so each
# ABI is built from source (about 3 h, one ABI per run). phase10/scripts/30-static-checks.sh
# prints a WARN line for each ABI that has no committed cache for the current key.
NODE_CACHE_KEY="node-${NODE_PIN}-ndk${NDK_PIN}-termux-${NODE_TERMUX_PATCH_PIN:0:12}-icufull-v2"
NODE_PREBUILT_ROOT="$DIR/prebuilt/$NODE_CACHE_KEY"
NODE_BOOTSTRAP_ROOT="$DIR/../docs/progress/phase10-evidence/node-cache/$NODE_CACHE_KEY"

restore_node_cache() {  # $1=abi
  local abi="$1" outdir="$ENGINE/jniLibs/$abi" root="" archive="$WORK/node-cache-$abi.tgz"
  for root in "$NODE_PREBUILT_ROOT/$abi" "$NODE_BOOTSTRAP_ROOT/$abi"; do
    [ -f "$root/archive.sha256" ] || continue
    compgen -G "$root/node-runtime.tgz.part-*" >/dev/null || continue
    cat "$root"/node-runtime.tgz.part-* > "$archive"
    [ "$(sha256sum "$archive" | cut -d' ' -f1)" = "$(cat "$root/archive.sha256")" ] \
      || { note "FATAL: Node prebuilt archive checksum failed for $abi ($root)"; return 1; }
    tar xzf "$archive" -C "$outdir"
    [ -x "$outdir/libnode.so" ] && [ -x "$outdir/libnpmcli.so" ] \
      || { note "FATAL: Node cache for $abi lacks executable outputs"; return 1; }
    if "$NDK_BIN/llvm-readelf" -d "$outdir/libnode.so" | grep -q 'libc++_shared.so'; then
      [ -f "$outdir/libc++_shared.so" ] \
        || { note "FATAL: Node cache for $abi lacks libc++_shared.so"; return 1; }
    fi
    local machine=""
    machine=$("$NDK_BIN/llvm-readelf" -h "$outdir/libnode.so" | sed -n 's/.*Machine:[[:space:]]*//p')
    case "$abi:$machine" in
      arm64-v8a:AArch64|x86_64:*X86-64*) ;;
      *) note "FATAL: Node cache machine mismatch for $abi: $machine"; return 1 ;;
    esac
    note "node $abi: restored verified $NODE_CACHE_KEY ($(stat -c%s "$outdir/libnode.so") bytes)"
    return 0
  done
  return 2
}

capture_node_cache() {  # $1=abi
  local abi="$1" outdir="$ENGINE/jniLibs/$abi" root="$NODE_BOOTSTRAP_ROOT/$abi"
  local archive="$WORK/node-runtime-$abi.tgz" files="libnode.so libnpmcli.so"
  [ -f "$outdir/libc++_shared.so" ] && files="$files libc++_shared.so"
  rm -rf "$root" && mkdir -p "$root"
  ( cd "$outdir" && tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
      -cf - $files | gzip -9n > "$archive" )
  sha256sum "$archive" | cut -d' ' -f1 > "$root/archive.sha256"
  split -b 1900k -d -a 3 "$archive" "$root/node-runtime.tgz.part-"
  ( cd "$outdir" && sha256sum $files ) > "$root/files.sha256"
  cat > "$root/README.txt" <<EOF
Generated from the pinned source recipe in phase4/scripts/10-build-payload.sh.
cache=$NODE_CACHE_KEY
abi=$abi
source_run_rebuild=P4_REBUILD_NODE=1
EOF
  note "node $abi cache bootstrap: $(du -sk "$root" | cut -f1) KB in $(find "$root" -name 'node-runtime.tgz.part-*' | wc -l) chunks"
}

build_node() {  # $1=abi $2=target triple $3=node dest cpu $4=gyp arch
  local abi="$1" triple="$2" destcpu="$3" gyparch="$4"
  local outdir="$ENGINE/jniLibs/$abi" src="$WORK/node-$abi"
  local cc="$NDK_BIN/${triple}29-clang" cxx="$NDK_BIN/${triple}29-clang++"
  local hostcc="$(command -v gcc cc | head -1)" hostcxx="$(command -v g++ c++ | head -1)"
  local zprefix="$WORK/zlib-$abi-android" ninja=""
  local ndkhome=""
  ndkhome="$(cd "$NDK_BIN/../../../../.." && pwd)"
  ninja="$(command -v ninja 2>/dev/null || true)"
  if [ -z "$ninja" ]; then
    ninja="$(find "$(dirname "$NDK_ROOT")" -type f -name ninja -perm -111 2>/dev/null | sort -r | head -1)"
  fi
  [ -x "$ninja" ] || { note "FATAL: Node needs Ninja (not found on PATH or under Android SDK)"; return 1; }
  local patchdir="$WORK/termux-node-patches"
  note "--- Node $NODE_PIN ($abi, Android/Bionic, full-icu, no inspector/snapshot) ---"
  if [ "${P4_REBUILD_NODE:-0}" != "1" ]; then
    if restore_node_cache "$abi"; then
      return 0
    else
      local cache_rc=$?
      [ "$cache_rc" = "2" ] \
        || { note "FATAL: unusable Node cache for $abi"; return 1; }
      note "node $abi: no prebuilt cache; reproducing from pinned source"
    fi
  else
    note "node $abi: P4_REBUILD_NODE=1; reproducing from pinned source"
  fi
  [ -f "$zprefix/lib/libz.a" ] \
    || { note "FATAL: Node needs the already-built zlib archive for $abi"; return 1; }
  rm -rf "$src" && mkdir -p "$src"
  tar xzf "$WORK/node.tgz" -C "$src" --strip-components=1

  # Build/runtime fixes only; test patches do not belong in the shipped source.
  local patches="avoid-ficlone-ioctl.patch common.gypi.patch \
deps-uv-src-unix-core.c.patch deps-uv-src-unix-process.c.patch deps-uv-uv.gyp.patch \
deps-uvwasi-src-uvwasi.c.patch deps-v8-src-flags-flag-definitions.h.patch \
deps-v8-src-trap-handler-trap-handler.h.patch fix_multiple_definitions.patch \
lib-child_process.js.patch lib-os.js.patch node.gyp.patch src-debug_utils.cc.patch \
src-getaddrinfo.patch src-node_internals.h.patch src-node_report.cc.patch \
tools-gyp-pylib-gyp-generator-ninja.py.patch tools-v8_gypfiles-toolchain.gypi.patch \
tools-v8_gypfiles-v8.gyp.patch"
  ( cd "$src"
    for p in $patches; do
      patch -p1 -s < "$patchdir/$p" \
        || { echo "Node patch failed: $p" >&2; exit 1; }
    done
    # Termux's compile-time prefix is dynamic in this app (debug and release
    # application ids differ). TMPDIR is always supplied by RuntimeEnv; these
    # strings are only unreachable fallbacks/perf-output defaults. child_process
    # is different: honour the sealed SHELL at runtime instead of baking a path.
    grep -rl '@TERMUX_PREFIX@' deps lib src tools 2>/dev/null \
      | xargs -r sed -i 's|@TERMUX_PREFIX@|/data/local/tmp/opencode|g'
    sed -i "s@file = '/data/local/tmp/opencode/bin/sh';@file = process.env.SHELL || '/system/bin/sh';@" lib/child_process.js

    export PATH="$NDK_BIN:$PATH"
    export CC="$cc" CXX="$cxx" LINK="$cxx"
    export CC_host="$hostcc" CXX_host="$hostcxx" LINK_host="$hostcxx"
    export CFLAGS="-O2 -fPIE -D__TERMUX__"
    export CXXFLAGS="-O2 -fPIE -D__TERMUX__"
    export LDFLAGS="-pie -Wl,-z,max-page-size=16384"
    export GYP_DEFINES="target_arch=$gyparch v8_target_arch=$gyparch android_target_arch=$gyparch host_os=linux OS=android android_ndk_path=$ndkhome"
    /usr/bin/python3 configure.py \
      --dest-cpu="$destcpu" --dest-os=android --cross-compiling \
      --openssl-no-asm --with-intl=full-icu --without-inspector \
      --without-node-snapshot --without-node-code-cache \
      --shared-zlib --shared-zlib-includes="$zprefix/include" \
      --shared-zlib-libpath="$zprefix/lib" \
      --without-npm --without-corepack --ninja >/dev/null
    # Cross-configure leaks external target dependency paths into HOST tools.
    # node_js2c is an x86_64 runner executable; feeding its host linker the
    # arm64 libz.a produced board 40214575682's "incompatible with elf64-x86-64".
    # Remove only the target -I/-L from Ninja's obj.host rules. `-lz` remains,
    # so the host resolves Ubuntu zlib while target rules retain our archive.
    find out/Release/obj.host -name '*.ninja' -type f -exec \
      sed -i -e "s|-I$zprefix/include||g" -e "s|-L$zprefix/lib||g" {} +
    if find out/Release/obj.host -name '*.ninja' -type f -exec grep -lF "$zprefix" {} + | grep -q .; then
      echo "target zlib path survived in a Node host Ninja rule" >&2
      exit 1
    fi
    # ninja's output goes to a file the workflow copies into the evidence, so a
    # build that is cancelled or fails leaves its last steps behind (run 40868426546
    # was cancelled 2h40m into this step and left no trace of where it was).
    timeout 10800 "$ninja" -C out/Release -j4 > "$ENGINE/node-ninja-$abi.log" 2>&1
  ) || { tail -n 80 "$ENGINE/node-ninja-$abi.log" 2>/dev/null | tee -a "$STATUS"; note "FATAL: Node build failed for $abi"; return 1; }
  note "node $abi: ninja finished at $(date -u +%FT%TZ)"

  local built="$src/out/Release/node"
  [ -f "$built" ] || { note "FATAL: Node output missing for $abi"; return 1; }
  # ICU is the reason this cache exists (\p{L} in RegExp needs the Unicode
  # property tables). Check the unstripped binary for the bundled ICU data
  # symbol: a no-Intl build cannot pass this.
  # The data symbol is icudt<major><endianness>_dat (configure.py sets endianness to
  # sys.byteorder[0], so little-endian is icudt78l_dat). Run 40850982684 failed here
  # with the old pattern 'icudt[0-9]*_dat', which cannot match the l. nm goes to a
  # file, not into grep -q: with pipefail an early grep exit gives llvm-nm SIGPIPE.
  "$NDK_BIN/llvm-nm" "$built" > "$WORK/nm-$abi.txt" 2>/dev/null || true
  if ! grep -Eq 'icudt[0-9]+[lb]?_dat' "$WORK/nm-$abi.txt" \
     && ! grep -aEq 'icudt[0-9]+[lb]' "$built"; then
    note "FATAL: Node $abi has no bundled ICU data symbol (full-icu not built)"
    return 1
  fi
  note "node $abi: bundled ICU data symbol present"
  cp "$built" "$outdir/libnode.so"
  "$NDK_BIN/llvm-strip" "$outdir/libnode.so"
  chmod 755 "$outdir/libnode.so"

  # npm/npx are JavaScript data and cannot be execve'd from app storage. Their
  # sealed native entry point execs libnode.so with the verified CLI JS path.
  "$cc" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
    -o "$outdir/libnpmcli.so" "$NATIVE_SRC/npm-shim.c"
  "$NDK_BIN/llvm-strip" "$outdir/libnpmcli.so"
  chmod 755 "$outdir/libnpmcli.so"

  # Node normally needs the NDK C++ runtime. It is code, so it must live beside
  # Node in nativeLibraryDir too; copying it as payload data would not load.
  if "$NDK_BIN/llvm-readelf" -d "$outdir/libnode.so" | grep -q 'libc++_shared.so'; then
    cp "$(dirname "$NDK_BIN")/sysroot/usr/lib/$triple/libc++_shared.so" "$outdir/libc++_shared.so"
    chmod 755 "$outdir/libc++_shared.so"
  fi

  note "node $abi: $(stat -c%s "$outdir/libnode.so") bytes stripped; npm shim $(stat -c%s "$outdir/libnpmcli.so") bytes"
  capture_node_cache "$abi" \
    || { note "FATAL: could not capture reproducible Node cache for $abi"; return 1; }
  rm -rf "$src"  # one V8 output tree at a time: runner disk is a real limit
  # One ABI per CI job: a full-icu V8 build for both ABIs does not fit in one
  # six-hour hosted-runner job. The bootstrap captures this ABI, then stops
  # with a marker; the next run restores it and builds the other ABI.
  if [ "${P4_NODE_CAPTURE_STOP:-0}" = "1" ]; then
    note "NODE_CACHE_BOOTSTRAP_CAPTURED abi=$abi key=$NODE_CACHE_KEY"
    exit 0
  fi
}

# OpenSSH client suite. One shared libcrypto per ABI avoids statically repeating
# ~6 MB of crypto in five client programs. It is still APK code: libcrypto.so is
# packaged beside the clients in nativeLibraryDir and found through the same
# APK-only LD_LIBRARY_PATH that Node uses, never through writable app data.
build_openssh_clients() {  # $1=abi $2=target triple $3=OpenSSL platform
  local abi="$1" triple="$2" ssl_platform="$3"
  local outdir="$ENGINE/jniLibs/$abi" cc="$NDK_BIN/${triple}29-clang"
  local ar="$NDK_BIN/llvm-ar" ranlib="$NDK_BIN/llvm-ranlib"
  local zprefix="$WORK/zlib-$abi-android" oprefix="$WORK/openssl-$abi-android"
  local osrc="$WORK/openssl-$abi" src="$WORK/openssh-$abi"
  local ndkhome=""
  ndkhome="$(cd "$NDK_BIN/../../../../.." && pwd)"
  note "--- OpenSSH $OPENSSH_PIN clients ($abi, OpenSSL $OPENSSL_PIN shared) ---"

  rm -rf "$osrc" "$oprefix" && cp -r "$WORK/openssl-src" "$osrc"
  # OpenSSL normally versions the filename/SONAME as libcrypto.so.3. Android's
  # JNI packager extracts lib*.so, not .so.N. A tiny inherited target keeps the
  # upstream build but deliberately names both file and SONAME libcrypto.so.
  cat > "$osrc/Configurations/99-opencode.conf" <<'EOF'
my %targets = (
    "android-opencode-arm64" => {
        inherit_from => [ "android-arm64" ],
        shared_extension => ".so",
    },
    "android-opencode-x86_64" => {
        inherit_from => [ "android-x86_64" ],
        shared_extension => ".so",
    },
);
EOF
  ( cd "$osrc"
    export ANDROID_NDK_ROOT="$ndkhome"
    export PATH="$NDK_BIN:$PATH"
    ./Configure "$ssl_platform" -D__ANDROID_API__=29 \
      --prefix="$oprefix" --libdir=lib \
      shared no-tests no-docs no-apps >/dev/null
    timeout 1200 make -j4 build_libs >/dev/null
    make install_dev >/dev/null
  ) || { note "FATAL: OpenSSL crypto build failed for $abi"; return 1; }
  [ -f "$oprefix/lib/libcrypto.so" ] \
    || { note "FATAL: unversioned shared libcrypto missing for $abi"; return 1; }
  cp "$oprefix/lib/libcrypto.so" "$outdir/libcrypto.so"
  "$NDK_BIN/llvm-strip" "$outdir/libcrypto.so"
  chmod 755 "$outdir/libcrypto.so"
  "$NDK_BIN/llvm-readelf" -d "$outdir/libcrypto.so" | grep -Fq 'Library soname: [libcrypto.so]' \
    || { note "FATAL: libcrypto SONAME is not libcrypto.so ($abi)"; return 1; }
  note "openssl $abi libcrypto.so: $(stat -c%s "$outdir/libcrypto.so") bytes"

  rm -rf "$src" && cp -r "$WORK/openssh-src" "$src"
  ( cd "$src"
    # codeload includes generated configure; copying may make m4/openssh.m4
    # appear newer by a timestamp tick and trigger an unnecessary autoreconf.
    touch configure
    CC="$cc" LD="$cc" AR="$ar" RANLIB="$ranlib" \
    CPPFLAGS="-D__ANDROID__ -Dfd_mask=int -DHAVE_ATTRIBUTE__SENTINEL__=1 -DBROKEN_SETRESGID -I$oprefix/include -I$zprefix/include" \
    CFLAGS="-O2 -fPIE" \
    LDFLAGS="-pie -L$oprefix/lib -L$zprefix/lib -Wl,-z,max-page-size=16384" \
    LIBS="-ldl" \
    ac_cv_func_endgrent=yes \
    ac_cv_func_fmt_scaled=no \
    ac_cv_func_getlastlogxbyname=no \
    ac_cv_func_readpassphrase=no \
    ac_cv_func_strnvis=no \
    ac_cv_header_sys_un_h=yes \
    ac_cv_lib_crypt_crypt=no \
    ac_cv_search_getrrsetbyname=no \
    ac_cv_func_bzero=yes \
    ac_cv_func_closefrom=no \
    ac_cv_func_close_range=no \
    ac_cv_member_struct_passwd_pw_gecos=no \
    ./configure --host="$triple" --prefix=/usr --sysconfdir=/etc/ssh \
      --disable-etc-default-login --disable-lastlog --disable-libutil \
      --disable-pututline --disable-pututxline --disable-strip \
      --disable-utmp --disable-utmpx --disable-wtmp --disable-wtmpx \
      --disable-security-key --without-shadow --without-stackprotect \
      --without-openssl-header-check --without-zlib-version-check \
      --with-ssl-dir="$oprefix" --with-zlib="$zprefix" \
      --with-default-path=/usr/bin:/system/bin \
      --with-pid-dir=/tmp --with-privsep-path=/tmp >/dev/null
    timeout 1800 make -j4 ssh scp sftp ssh-add ssh-agent ssh-keygen ssh-keyscan >/dev/null
  ) || { note "FATAL: OpenSSH client build failed for $abi"; \
         tail -80 "$src/config.log" 2>/dev/null | tee -a "$STATUS"; return 1; }

  local tool="" lib=""
  for tool in ssh scp sftp ssh-add ssh-agent ssh-keygen ssh-keyscan; do
    case "$tool" in
      ssh)         lib="libsshclient.so" ;;
      scp)         lib="libscp.so" ;;
      sftp)        lib="libsftp.so" ;;
      ssh-add)     lib="libsshadd.so" ;;
      ssh-agent)   lib="libsshagent.so" ;;
      ssh-keygen)  lib="libsshkeygen.so" ;;
      ssh-keyscan) lib="libsshkeyscan.so" ;;
    esac
    [ -x "$src/$tool" ] || { note "FATAL: OpenSSH did not build $tool for $abi"; return 1; }
    cp "$src/$tool" "$outdir/$lib"
    "$NDK_BIN/llvm-strip" "$outdir/$lib"
    chmod 755 "$outdir/$lib"
    if "$NDK_BIN/llvm-readelf" -d "$outdir/$lib" | grep -q 'libcrypto.so.3'; then
      note "FATAL: $tool requests unpackageable libcrypto.so.3 ($abi)"
      return 1
    fi
    case "$tool" in
      ssh|ssh-add|ssh-agent|ssh-keygen|ssh-keyscan)
        "$NDK_BIN/llvm-readelf" -d "$outdir/$lib" | grep -Fq 'Shared library: [libcrypto.so]' \
          || { note "FATAL: $tool did not link the packaged libcrypto.so ($abi)"; return 1; }
        ;;
    esac
    note "openssh $abi $tool: $(stat -c%s "$outdir/$lib") bytes"
  done
  rm -rf "$osrc" "$src"
}

build_node_npm_data() {
  local npm="$STAGE/usr/lib/node_modules/npm"
  rm -rf "$npm" && mkdir -p "$npm"
  tar xzf "$WORK/node.tgz" -C "$npm" --strip-components=3 \
    "node-${NODE_PIN}/deps/npm"
  local got=""
  got=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$npm/package.json")
  [ "$got" = "$NPM_PIN" ] || { note "FATAL: Node $NODE_PIN bundled npm $got, expected $NPM_PIN"; return 1; }
  rm -rf "$npm/docs" "$npm/man" "$npm/test" "$npm/tap-snapshots"
  find "$npm" -type f \( -name '*.md' -o -name '*.map' \) -delete
  note "npm $got data: $(find "$npm" -type f | wc -l) files, $(du -sk "$npm" | cut -f1) KB"
}

# jq, and the oniguruma it links for regex (test/match/sub). Built after the
# shell and coreutils so a failure here cannot take the userland core with it.
for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_oniguruma "$abi" "x86_64-linux-android";  build_jq "$abi" "x86_64-linux-android" ;;
    arm64-v8a) build_oniguruma "$abi" "aarch64-linux-android"; build_jq "$abi" "aarch64-linux-android" ;;
  esac
done

# Python: the build python first (one host build, ~2 min), then one
# cross-build per ABI. The stdlib is built once - it is data, not an
# executable, so both ABIs share the same zip.
build_host_python || exit 1
for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_python "$abi" "x86_64-linux-android" ;;
    arm64-v8a) build_python "$abi" "aarch64-linux-android" ;;
  esac
done
build_python_stdlib || exit 1

# Node is the last large budget decision. Build it after Python is proven so a
# V8 failure cannot erase the smaller, already-working language runtime.
for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_node "$abi" "x86_64-linux-android" "x64" "x64" ;;
    arm64-v8a) build_node "$abi" "aarch64-linux-android" "arm64" "arm64" ;;
  esac
done
build_node_npm_data || exit 1

# OpenSSH is the last core-tier tool. Its client suite is source-built after
# Node cache restore, and uses the zlib prefixes already produced for Git.
for abi in "${ABIS[@]}"; do
  case "$abi" in
    x86_64)    build_openssh_clients "$abi" "x86_64-linux-android" "android-opencode-x86_64" ;;
    arm64-v8a) build_openssh_clients "$abi" "aarch64-linux-android" "android-opencode-arm64" ;;
  esac
done

# This MUST be after every data producer: Python's zip and npm's JS tree live
# in usr/. Packing earlier silently omits them even though STAGE looks complete.
pack_runtime_payload || exit 1
# Do not trust STAGE alone: the bug on two consecutive boards was exactly that
# the file existed there but the already-built APK payload did not contain it.
for data in usr/lib/python3.13.zip usr/lib/node_modules/npm/package.json; do
  tar -tzf "$ENGINE/assets/runtime-payload.tar.gz" "$data" >/dev/null \
    || { note "FATAL: $data is not in runtime-payload.tar.gz"; exit 1; }
done
python3 - "$ENGINE/assets/runtime-manifest.json" <<'EOF'
import json, sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
for path in ("usr/lib/python3.13.zip", "usr/lib/node_modules/npm/package.json"):
    entry = manifest.get("files", {}).get(path)
    if not entry or int(entry.get("size", 0)) <= 0:
        raise SystemExit(path + " is not in runtime-manifest.json")
    print("tool data packaged: %s = %d bytes, manifest + tar agree" % (path, entry["size"]))
EOF

# Same PIE/DYN assertion the runtime's own shims get: an ET_EXEC fixed-address
# binary is rejected by the platform, and a userland that cannot be exec'd is
# worse than no userland because it fails quietly, at the first command.
READELF="$NDK_BIN/llvm-readelf"
[ -x "$READELF" ] || READELF="$(command -v llvm-readelf readelf | head -1)"
for abi in "${ABIS[@]}"; do
  for so in libmksh.so libtoybox.so libjq.so libpython3.so libnode.so libnpmcli.so libwget.so \
            libcrypto.so libsshclient.so libscp.so libsftp.so libsshadd.so \
            libsshagent.so libsshkeygen.so libsshkeyscan.so; do
    "$READELF" -h "$ENGINE/jniLibs/$abi/$so" 2>&1 | tee -a "$STATUS" | grep -q 'DYN' \
      || { note "FATAL: $so ($abi) is not a PIE/DYN executable (ELF header above)"; exit 1; }
  done
done

# The sealed exec records: the runtime's five tools, then the shell, then every
# command toybox built. One line each, "<prefix path> <TAB> <library>".
printf 'bin/bun\tlibbun.so\nbin/git\tlibgit.so\nbin/rg\tlibrg.so\n' \
  > "$SEALED_EXEC_RECORDS"
printf 'bin/curl\tlibcurl.so\nbin/wget\tlibwget.so\nbin/jq\tlibjq.so\n' >> "$SEALED_EXEC_RECORDS"
# `python` and `python3` are both expected by scripts in the wild; one binary,
# two names, exactly as upstream installs it.
printf 'bin/python3\tlibpython3.so\nbin/python\tlibpython3.so\n' >> "$SEALED_EXEC_RECORDS"
printf 'bin/node\tlibnode.so\nbin/npm\tlibnpmcli.so\nbin/npx\tlibnpmcli.so\n' >> "$SEALED_EXEC_RECORDS"
printf 'bin/ssh\tlibsshclient.so\nbin/scp\tlibscp.so\nbin/sftp\tlibsftp.so\n' >> "$SEALED_EXEC_RECORDS"
printf 'bin/ssh-add\tlibsshadd.so\nbin/ssh-agent\tlibsshagent.so\n' >> "$SEALED_EXEC_RECORDS"
printf 'bin/ssh-keygen\tlibsshkeygen.so\nbin/ssh-keyscan\tlibsshkeyscan.so\n' >> "$SEALED_EXEC_RECORDS"
printf 'bin/git-remote-http\tlibgitremotehttp.so\nbin/git-remote-https\tlibgitremotehttp.so\n' \
  >> "$SEALED_EXEC_RECORDS"
printf 'bin/sh\tlibmksh.so\n' >> "$SEALED_EXEC_RECORDS"
while IFS= read -r cmd; do
  printf 'bin/%s\tlibtoybox.so\n' "$cmd" >> "$SEALED_EXEC_RECORDS"
done < "$WORK/toybox-commands.txt"
note "sealed exec records: $(wc -l < "$SEALED_EXEC_RECORDS") entries"

note "=== sealed layout ==="
for abi in "${ABIS[@]}"; do
  python3 "$SEAL_PY" --abi "$abi" --out "$ENGINE/assets" \
      --exec-file "$SEALED_EXEC_RECORDS" \
      | tee -a "$STATUS" || { note "FATAL: sealed layout generation failed for $abi"; exit 1; }
  # Every library named in the manifest must exist for this ABI, or the app
  # would install a link to nothing and the tool would be silently absent.
  while IFS=$'\t' read -r prefix lib; do
    case "$prefix" in ''|\#*) continue ;; esac
    [ -f "$ENGINE/jniLibs/$abi/$lib" ] || {
      note "FATAL: sealed-exec-$abi.txt names $lib for $prefix but jniLibs/$abi/$lib is missing"
      exit 1
    }
  done < "$ENGINE/assets/sealed-exec-$abi.txt"
done
note "sealed exec manifests: $(cd "$ENGINE/assets" && ls sealed-exec-*.txt | tr '\n' ' ')"

note "=== engine layout ==="
( cd "$ENGINE" && find . -type f -exec ls -la {} \; | awk '{print $5, $9}' )
note "PAYLOAD_READY"
