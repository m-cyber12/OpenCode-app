# Shipping ICU in the bundled Node (owner decision, 2026-10-28; go-ahead 2026-10-29)

**Owner's choice:** ship ICU in the bundled Node, accept a bigger APK.
**Status (2026-10-29):** implemented in `phase4/scripts/10-build-payload.sh`; the
multi-run cache bootstrap is running on CI. Verdicts are pending until the bootstrap
finishes and a full run passes the device gate. Nothing here is proven on a device yet.

## What was wrong
Node was configured with `--with-intl=none`. Without ICU, V8 rejects Unicode property
escapes, so `/[\p{L}\p{N}]/u` fails with `Invalid regular expression`. That is the owner's
`rea` report (2026-10-28).

## What changed
1. `--with-intl=none` became `--with-intl=full-icu`. The ICU sources and the full data
   (`deps/icu-small/source/data/in/icudt78l.dat.bz2`) come from the Node 24.22.1 tarball.
   Locally, `configure.py` with the same flags completes, and the ninja graph contains the
   `icudata` target (`gen/icudt78_dat.c`). Full ICU was chosen over small-icu so that
   non-English locale formatting also works; the APK grows by the data size.
2. `NODE_CACHE_KEY` `-v1` became `-v2` (`...-icufull-v2`). The old no-ICU cache is no longer
   used and was removed from the tree (it stays in git history).
3. The build fails if the unstripped binary has no `icudt*_dat` symbol, so a no-ICU build
   cannot be captured by mistake.
4. One ABI per CI job. The old cache for two ABIs did not fit in one six-hour job. The
   payload step now runs with `P4_NODE_CAPTURE_STOP=1`. When an ABI's cache is missing,
   that ABI is built, captured as sub-2 MiB chunks under `docs/progress/phase10-evidence/`
   (the existing always-run post-step commits that directory), and the run stops with the
   marker `NODE_CACHE_BOOTSTRAP_CAPTURED`. `phase10/scripts/00-run-phase10.sh` records that as
   a "node-cache-bootstrap" mode with no verdicts.
5. The device gate V7 (`HarnessSealedToolsGateTest.v7_nodeAndNpmRunFromTheSealedPrefix`,
   run by phase 6's `harness-sealed-tools`) now also runs
   `node -e "console.log(/\p{L}/u.test('a') + ' ' + new Intl.NumberFormat('de-DE').format(1234.5))"`
   and requires `true 1.234,5`. The German grouping only works with full ICU.

## Bootstrap sequence (each step is one CI run on the branch)
- Run A: arm64-v8a is missing, so it is built and captured; the run stops.
- Run B: arm64-v8a is restored; x86_64 is built and captured; the run stops.
- Run C: both are restored; the full payload, emulator, UI gates and release run.
Each run after A and B needs a new commit on the branch to start; the evidence commit
itself does not trigger the workflow (`paths-ignore`).

## Known risks
- The first from-source full-ICU build is unproven. If it fails, the run records a FATAL
  in `GATES_SUMMARY.txt` and the next run starts over at that ABI.
- The bundled data adds an estimated 25–35 MB per ABI (not measured yet) to the native libraries. The cache chunks
  add the compressed size to git once per ABI.
- The APK size has to be re-measured against the earlier 221.65 MiB report.
