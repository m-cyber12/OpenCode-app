#!/usr/bin/env bash
# TEST ONLY. Builds the OpenCode v2 Node bundle (linux-arm64 asset set) plus its
# app archive, so the Node 26 test APK can run the v2 server on the phone.
# Output: $V2_OUT/node26-v2.zip, with opencode.mjs and assets/ at the top level.
# Nothing here touches the release pipeline; phase10 runs it as continue-on-error.
#
# Source: anomalyco/opencode at the tag below (v2.2.5). Bun 1.4.4 is the
# packageManager pin in that repo.
set -Eeuo pipefail
trap 'echo "::error title=node26-v2::unplanned failure at line $LINENO: $BASH_COMMAND"' ERR

V2_REPO="${V2_REPO:-https://github.com/anomalyco/opencode}"
V2_TAG="${V2_TAG:-v2.2.5}"
V2_WORK="${V2_WORK:-/tmp/v2-src}"
V2_OUT="${V2_OUT:-/tmp/v2-out}"

fatal() { echo "::error title=node26-v2::$*"; exit 1; }
command -v bun >/dev/null 2>&1 || fatal "bun is not on PATH (setup-bun step missing?)"
command -v zip >/dev/null 2>&1 || fatal "zip is not installed"

rm -rf "$V2_WORK"
git clone --depth 1 --branch "$V2_TAG" "$V2_REPO" "$V2_WORK"
echo "v2 source: $(git -C "$V2_WORK" rev-parse --short HEAD) ($V2_TAG)"

cd "$V2_WORK"
bun install --frozen-lockfile

# --bundle-only skips the SEA binary (needs a Node 26 builder and nodejs.org).
# OPENCODE_TARGET=linux-arm64 picks the arm64 asset set, matching the phone.
cd packages/cli
OPENCODE_TARGET=linux-arm64 bun run script/build-node.ts --bundle-only --skip-install

[ -f dist-node/opencode.mjs ] || fatal "dist-node/opencode.mjs was not produced"
[ -d dist-node/assets ] || fatal "dist-node/assets was not produced"

rm -rf "$V2_OUT"
mkdir -p "$V2_OUT/stage"
cp dist-node/opencode.mjs "$V2_OUT/stage/"
cp -r dist-node/assets "$V2_OUT/stage/"
( cd "$V2_OUT/stage" && zip -q -r -6 "$V2_OUT/node26-v2.zip" . )
rm -rf "$V2_OUT/stage"

echo "bundle: $(du -h "$V2_OUT/node26-v2.zip" | cut -f1) $V2_OUT/node26-v2.zip"
sha256sum "$V2_OUT/node26-v2.zip"
unzip -l "$V2_OUT/node26-v2.zip" | tail -n 1
echo "::notice title=node26-v2::bundle=$(stat -c %s "$V2_OUT/node26-v2.zip") bytes (opencode.mjs $(stat -c %s dist-node/opencode.mjs) bytes)"
