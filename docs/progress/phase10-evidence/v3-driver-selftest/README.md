# v3 driver self-test bundles (fake phone) — evidence for Appendix B

**What this is.** The output of `bash phase10/scripts/test-90-real-device.sh --keep`, i.e.
the real device driver (`90-real-device-signed.sh`) run end to end against the **fake
phone** (`test-90-fake-adb.py` + `test-90-fixtures.py`). Three runs are kept:

| Log | When | Scenarios | Result |
|---|---|---|---|
| `driver-selftest.log` | 2026-09-20 | nine | **pass=68 fail=0** |
| `driver-selftest-87.log` | 2026-09-21 | twelve | **pass=87 fail=0** |
| `driver-selftest-99.log` | 2026-09-21 | thirteen | **pass=99 fail=0** |
| `driver-selftest-105.log` | 2026-09-21 | fourteen | **pass=105 fail=0** |

The fourteen scenarios run in CI on every push as step 1b, verdict `P10_DRIVER_SELFTEST`.
The three added on 2026-09-21 after the owner's second device bundle (§B.10) were the
reader dying on a POSIX-absolute script path (`reader-dead`), the same host **with** the
Windows path conversion (`winhost`, must run green), and a relative `--out` used from
another directory (`relout`). The owner's **third** bundle — the same `can't open file`
answer *after* the `cygpath -w` conversion had already gone in — is why the driver stopped
assuming one conversion is right: it now probes the forms and keeps the one that opens,
`reader-dead` was rewritten as the host where **none** of them works, and the thirteenth
scenario (`incomplete`) covers a checkout assembled file by file, where the driver is
present and the programs it drives are not. The fourteenth (`readertmp`) is the fallback
itself: a host whose interpreter can only open files under the driver's own temp dir. It
was added after the fallback was exercised by hand and two defects fell out of it (see
§B.11.5 of the report); the last row of the table is therefore the one that matters most,
because it is the only one that tests the whole run through the *weakest* host shape.

**What this is NOT.** This is not device evidence. The phone in these runs is a shim I
wrote, so it necessarily agrees with my model of the app; the screenshots it serves are
one fixed frame each (every file in `happy/screenshots/` is byte-identical), which is why
no PNG is kept here. Anything about a real phone, a real signed APK, or the real storage
layout on real hardware still comes from the owner's run — see
`docs/progress/phase10-signing-publish-prep-report.md` §B.8.

**Why it is kept anyway.** It is the *behavioural* record behind Appendix B's claims: the
driver reaches the three gates that skipped in the owner's bundle, it stops instead of
blaming the app when the host is broken, and it fails for the right reason when the
storage is not what the product promises.

| Path | What it shows |
|---|---|
| `driver-selftest.log` | the whole self-test: 9 scenarios, 68 checks, 0 failed, plus each scenario's own log |
| `happy/SUMMARY.txt` | a complete run: `FIRST_RUN_PROJECT`, `FILES_SCREEN`, `LIVE_TURN` **PASS** (all three SKIPped in the owner's v2 bundle), project at `/storage/emulated/0/Documents/OpenCode/p10d-…`, `UI_DUMP` 24 readable dumps, `SCREENSHOTS` 10 real screens |
| `happy/DIAGNOSIS.txt` | empty, 0 bytes — a clean run writes no diagnosis (the file exists to prove that) |
| `happy/visibility.log` | the outside-the-app check's raw `ls`/`cat` output for the shared root: `V1–V8`, with `DOCUMENTS_PROVIDER PASS` (the fake phone's provider answers; the real emulator's does not — that SKIP is expected there) |
| `happy/ui/ui-files-screen.xml`, `ui-turn-tool.xml` | the accessibility dumps the `FILES_SCREEN` and `LIVE_TURN` verdicts were decided from |
| `msys-mangled/` | the owner's v2 failure mode reproduced byte for byte: the host rewrites `/sdcard/p10d-ui.xml` into `C:/Program Files/Git/sdcard/…`, so the "dump" is a 72-byte `cat:` error (`ui/ui-harness-preflight.xml` is exactly 72 bytes, like the owner's `ui/*.xml`). Result: `HARNESS_DUMP FAIL` naming MSYS, **no app verdict at all**, rc=3. `cmp` against
`p10d-out/ui/ui-wait-app-window.xml` from the owner's bundle returns identical (72 bytes) |
| `no-python/` | a Windows host whose `python3` is the Microsoft Store stub: `HARNESS_PYTHON FAIL`, diagnosis names the stub, **no APK and no first-run verdict invented** (the v2 bundle's empty `ARTIFACT FAIL` came from exactly this) |
| `no-grant/` | All files access refused: the app correctly falls back to `Android/data/…`, the shell can still read it, and the check **FAILs `SHARED_ROOT`** rather than claiming file-manager visibility — the fallback is stated, not hidden |
| `locked/` | device locked: `DEVICE_AWAKE FAIL` exists and says to unlock, and the bundle does **not** claim "no working screen" (the v1 failure mode) |

The 2026-09-21 scenarios (all in `driver-selftest-87.log` / `driver-selftest-99.log` / `driver-selftest-105.log`; the
bundles themselves are not kept again, to avoid duplicating megabytes of fixed-frame
screenshots):

| Scenario | What it locks in |
|---|---|
| `reader-dead` | the owner's exact failure, in its final form (third bundle): a host whose Python cannot open the reader script by **any** form the driver can hand it. The run must stop at R0.5 with `HARNESS_READER FAIL`, quote the reader's real error (which v3 discarded), print `HARNESS_DUMP PASS` **without** the empty `(; acquisition: …)` node count (the dump was fine — the reader was not, and the two facts stay separate), list the forms it tried in `DIAGNOSIS.txt`, write `reader-probe.txt` into the bundle, and produce **no app verdict** and **no multi-minute timeout** — v3 produced a 300 s timeout and "the app never showed a screen" on a phone showing its projects screen |
| `incomplete` | the checkout assembled by downloading files from the GitHub web UI: the script is there, `p10d-ui.py` / `p10d-png.py` / `check-apk.py` / `92-workspace-visibility.sh` are not. The run must fail at R0.4 with `HARNESS_CHECKOUT FAIL` naming the missing file, tell the reader to clone the branch, and produce **no verdict about the phone at all** — before this gate the same bundle looked like a path bug |
| `winhost` | the same host where the probe finds a working conversion: the run must be green end to end, `HARNESS_READER PASS` must print the converted path it used, `run.log` must state the path mode every Python tool is handed (`python path mode: m`), the screenshot validator must run through that same form, and `FIRST_RUN` / `LIVE_TURN` must pass |
| `readertmp` | the fallback (`MAP_MODE=tmp`): a host that can only open files under the driver's temp dir. The run must be green end to end **through that form** - reader, every dump, the inline dump scripts that read the storage path off the screen, and the screenshot validator - because a fallback that only rescues the reader leaves the rest of the run blind |
| `relout` | the owner's invocation shape (relative `--out`, run from the repo root): the bundle must land under the caller's cwd, `visibility.log` must be inside it, and the child's `cd` into `phase10/` must not leave an `p10d-out-…/` behind |

Reproduce: `bash phase10/scripts/test-90-real-device.sh` (~8 minutes, no device needed;
`--keep` leaves the bundles in `$TMPDIR`).
