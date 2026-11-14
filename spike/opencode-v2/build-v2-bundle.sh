#!/usr/bin/env bash
# TEST ONLY. Builds the OpenCode v2 Node bundle (linux-arm64 asset set) plus its
# app archive, so the Node 26 test APK can run the v2 server on the phone.
# Output: $V2_OUT/node26-v2.zip, with opencode.mjs and assets/ at the top level.
# Nothing here touches the release pipeline; phase10 runs it as continue-on-error.
#
# Source: anomalyco/opencode at the tag below (v2.2.5). Bun 1.4.4 is the
# packageManager pin in that repo.
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

V2_REPO="${V2_REPO:-https://github.com/anomalyco/opencode}"
V2_TAG="${V2_TAG:-v2.2.5}"
V2_WORK="${V2_WORK:-/tmp/v2-src}"
V2_OUT="${V2_OUT:-/tmp/v2-out}"

LOG="${V2_LOG:-/tmp/v2-build.log}"
: > "$LOG"

# The step log is not downloadable from the sandbox, so a failure posts the tail of
# this build log as one annotation (newlines encoded for the workflow command).
annotate() {
  local reason="$1" tail_text
  tail_text="reason: ${reason}"$'\n'"$(tail -c 3000 "$LOG" 2>/dev/null || true)"
  tail_text="${tail_text//'%'/%25}"
  tail_text="${tail_text//$'\r'/%0D}"
  tail_text="${tail_text//$'\n'/%0A}"
  echo "::error title=node26-v2 build failed::${tail_text}"
}
fail_with_log() { annotate "$1"; echo "$1"; exit 1; }
trap 'fail_with_log "unplanned failure at line $LINENO: $BASH_COMMAND"' ERR
fatal() { fail_with_log "$*"; }
command -v bun >/dev/null 2>&1 || fatal "bun is not on PATH (setup-bun step missing?)"
command -v zip >/dev/null 2>&1 || fatal "zip is not installed"

rm -rf "$V2_WORK"
git clone --depth 1 --branch "$V2_TAG" "$V2_REPO" "$V2_WORK"
echo "v2 source: $(git -C "$V2_WORK" rev-parse --short HEAD) ($V2_TAG)"

# Phase 2 invariants on v2 (migration plan, items 4-6). Each script exits non-zero when an
# anchor is missing; with pipefail and the ERR trap, that stops the build. Then the host
# tests run against the patched tree's scanner (Node 22+ strips the TypeScript types).
python3 "$HERE/../../phase4/payload/v2-patches/apply-permission-guard.py" "$V2_WORK" 2>&1 | tee -a "$LOG"
python3 "$HERE/../../phase4/payload/v2-patches/apply-noexec-shell.py" "$V2_WORK" 2>&1 | tee -a "$LOG"
SCAN_COPY=/tmp/v2-shell-scan.ts
sed '/^export \* as ShellScan from/d' "$V2_WORK/packages/core/src/shell/scan.ts" > "$SCAN_COPY"
V2_SHELL_SCAN="$SCAN_COPY" node --test --experimental-strip-types \
  "$HERE/../../phase4/payload/v2-patches/test/permission-guard.test.ts" \
  "$HERE/../../phase4/payload/v2-patches/test/noexec-shell.test.ts" \
  "$HERE/test-stub/stub-model.test.mjs" 2>&1 | tee -a "$LOG"

cd "$V2_WORK"
# Frozen install on this x64 runner would skip the linux-arm64 native packages (the
# platform fields in bun.lock). build-node.ts runs `bun install --os=* --cpu=*` itself
# unless --skip-install is given, so leave that out.
bun install --frozen-lockfile 2>&1 | tee -a "$LOG"

# --bundle-only skips the SEA binary (needs a Node 26 builder and nodejs.org).
# OPENCODE_TARGET=linux-arm64 picks the arm64 asset set, matching the phone.
cd packages/cli
# Zen reads the User-Agent (opencode/<channel>/<version>/opencode) and rejects anything below 1.18.0.
# Without these two variables the bundle reports "local"/"local" and gets HTTP 426 on free models
# (device run 4, 2026-11-05). Use the release channel and the v2.2.5 release version.
OPENCODE_VERSION=2.2.5 OPENCODE_CHANNEL=latest OPENCODE_TARGET=linux-arm64 bun run script/build-node.ts --bundle-only 2>&1 | tee -a "$LOG"

[ -f dist-node/opencode.mjs ] || fatal "dist-node/opencode.mjs was not produced"
[ -d dist-node/assets ] || fatal "dist-node/assets was not produced"

mkdir -p dist-node/assets/test-native
# TEST ONLY, terminal. With NODE_PTY_TGZ (the Bionic package from spike/node-pty-android), the
# real addon replaces the glibc prebuild, and the test launcher points OPENCODE_NODE_PTY_PATH at
# it (marker file pty-real). Without it, the old stub stays: the server starts, terminals throw.
NODE_PTY_PKG="dist-node/assets/@lydell/node-pty-linux-arm64"
if [ -n "${NODE_PTY_TGZ:-}" ] && [ -f "$NODE_PTY_TGZ" ]; then
  rm -rf "$NODE_PTY_PKG"
  tar -xzf "$NODE_PTY_TGZ" -C dist-node/assets/@lydell 2>&1 | tee -a "$LOG"
  [ -f "$NODE_PTY_PKG/prebuilds/linux-arm64/pty.node" ] || fatal "Bionic pty.node missing after extracting $NODE_PTY_TGZ"
  [ -f "$NODE_PTY_PKG/lib/index.js" ] || fatal "node-pty lib/index.js missing after extracting $NODE_PTY_TGZ"
  touch dist-node/assets/test-native/pty-real
  echo "node-pty: real Bionic addon from $NODE_PTY_TGZ" | tee -a "$LOG"
else
  # TEST ONLY: the node-pty loader has no Android build; stub it so the server can start.
  NODE_PTY_UTILS="$NODE_PTY_PKG/lib/utils.js"
  [ -f "$NODE_PTY_UTILS" ] || fatal "node-pty loader not found in the assets: $NODE_PTY_UTILS"
  python3 "$HERE/stub-node-pty.py" "$NODE_PTY_UTILS" 2>&1 | tee -a "$LOG"
fi

# TEST ONLY, file watching. With PARCEL_WATCHER_NODE (watcher.node from spike/parcel-watcher-android),
# the test launcher points OPENCODE_PARCEL_WATCHER_PATH at it. Without it, watching stays off.
if [ -n "${PARCEL_WATCHER_NODE:-}" ] && [ -f "$PARCEL_WATCHER_NODE" ]; then
  cp "$PARCEL_WATCHER_NODE" dist-node/assets/test-native/watcher.node
  echo "parcel watcher: real Bionic binding from $PARCEL_WATCHER_NODE" | tee -a "$LOG"
fi

rm -rf "$V2_OUT"
mkdir -p "$V2_OUT/stage"
cp dist-node/opencode.mjs "$V2_OUT/stage/"
cp -r dist-node/assets "$V2_OUT/stage/"
# The launcher ships with the bundle so the two always match (migration phase 1).
# TEST ONLY: the staged launcher is the release launcher plus test-launcher-block.mjs (native
# paths, config provider, scripted model stub). The release launcher file is not changed.
python3 "$HERE/test-launcher-insert.py" "$HERE/../../phase4/payload/v2/launcher-v2.mjs" \
  "$HERE/test-launcher-block.mjs" "$V2_OUT/stage/launcher-v2.mjs" 2>&1 | tee -a "$LOG"
mkdir -p "$V2_OUT/stage/test-stub"
cp "$HERE/test-stub/stub-model.mjs" "$V2_OUT/stage/test-stub/"
( cd "$V2_OUT/stage" && zip -q -r -6 "$V2_OUT/node26-v2.zip" . )
rm -rf "$V2_OUT/stage"

echo "bundle: $(du -h "$V2_OUT/node26-v2.zip" | cut -f1) $V2_OUT/node26-v2.zip"
sha256sum "$V2_OUT/node26-v2.zip"
unzip -l "$V2_OUT/node26-v2.zip" | tail -n 1
echo "::notice title=node26-v2::bundle=$(stat -c %s "$V2_OUT/node26-v2.zip") bytes (opencode.mjs $(stat -c %s dist-node/opencode.mjs) bytes)"
