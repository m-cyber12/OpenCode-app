# Phase 10 evidence

Two kinds of file live here, and they are never mixed:

## `local/` — the authoring sandbox (no JDK, no Gradle, no Android SDK, no adb)

| File | What it is |
|---|---|
| `static-checks.txt` | full output of `bash phase10/scripts/30-static-checks.sh`, rc=0: the Phase 9 layer, the Phase 10 release invariants, the APK/AAB inspector's self-test, script syntax, the workflow/no-signing-secret check, and the no-infinite-animation check |

Nothing in `local/` is device or build evidence, because no build or device exists
in that environment. It proves the checks run and pass on the tree that CI will
build; it proves nothing about the app at runtime.

## Everything else — written by `phase10/scripts/00-run-phase10.sh` in CI

`GATES_SUMMARY.txt` is the file that decides the job (its last line is the
verdict). The rest are the gate lines, the inspected artifacts as JSON
(`p10-*-apk.json`, `p10-release-aab.json`), device facts, and the Phase 6 and
Phase 9 evidence captured on the same device during the same run.

The signed-build device bundle is **not** here: `90-real-device-signed.sh` writes
to `p10d-out/` on the owner's machine, because it contains their phone's UI dumps
and must be read (and redacted) before it is committed anywhere.
