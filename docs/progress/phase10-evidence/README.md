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
(`p10-*-apk.json`), device facts, and the Phase 6 and Phase 9 evidence captured on
the same device during the same run. `00-run-phase10.log` is the pipeline's own
log (stages 1/9 … 9/9, then the summary), and `p10-driver-selftest.log` is the
driver self-test: nine fake-phone scenarios, 68 checks, run as step 1b.

The signed-build device bundle is **not** here: `90-real-device-signed.sh` writes
to `p10d-out/` on the owner's machine, because it contains their phone's UI dumps
and must be read (and redacted) before it is committed anywhere.

## `v3-driver-selftest/` — kept by hand, and labelled as such

The owner's bundle showed four host-side faults reported as device verdicts
(Appendix B, §B.4). The bundles under `v3-driver-selftest/` are the driver
self-test's own output from one `--keep` run: `happy/` (the three gates that
skipped in the owner's bundle all PASSing), `msys-mangled/` (the owner's 72-byte
`cat:` line, byte-identical), `no-python/`, `no-grant/`, `locked/`. Its README
states the limit plainly — the phone there is a shim I wrote, so it is
behavioural evidence for the **driver**, never device evidence for the app.
