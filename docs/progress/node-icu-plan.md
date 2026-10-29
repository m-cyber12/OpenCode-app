# Shipping ICU in the bundled Node (owner decision, 2026-10-28)

**Owner's choice:** ship ICU in the bundled Node, accept a bigger APK.
**Status:** decided, NOT yet implemented. A naive flag change would fail CI.

## What is wrong
`phase4/scripts/10-build-payload.sh` configures Node with `--with-intl=none`. Without ICU,
V8 rejects Unicode property escapes, so `/[\p{L}\p{N}]/u` fails with
`Invalid regular expression`. That is the owner's `rea` report.

## Why it is not a one-line change
- Node is not rebuilt on every run. It is restored from a checksum-pinned prebuilt
  cache whose key is `NODE_CACHE_KEY="node-${NODE_PIN}-ndk${NDK_PIN}-termux-...-v1"`
  (`phase4/scripts/10-build-payload.sh`, around line 1099).
- Changing only the flag leaves the old `-v1` cache in place, so the flag has no effect.
- Bumping the key to `-v2` forces a from-source build of Node and V8 for both ABIs. The
  script's own comment says this takes nearly the whole six-hour GitHub runner limit, so
  one board would be cancelled. A multi-run bootstrap (the `node-cache` chunks under
  `docs/progress/phase10-evidence/`) would be needed.

## The change, when it is done
1. In `10-build-payload.sh`, replace `--with-intl=none` with `--with-intl=small-icu`.
   small-icu is English-only locale data with the full Unicode property tables.
   Use full-icu only if locale support is needed; it is much larger.
2. Bump `NODE_CACHE_KEY` `-v1` to `-v2` so the old cache is not reused.
3. Run the bootstrap over several boards so each ABI finishes inside the runner limit.
4. Measure the new libnode.so size. Check the APK against the 200 MiB budget.
5. Verify on a board: `node -e "console.log(/[\p{L}\p{N}]/u.test('a'))"` prints `true`.

## Needs the owner's go-ahead
Steps 2 to 4 cost several CI boards and change the APK size. The agent will not start
them unless the owner says so.
