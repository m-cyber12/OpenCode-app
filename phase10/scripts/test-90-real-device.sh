#!/usr/bin/env bash
# test-90-real-device.sh - RUN the real-device driver against a fake phone.
#
# The driver (90-real-device-signed.sh) is the one script in this project that is
# meant to be run by a human on hardware the authoring environment cannot reach, and
# it is also the script whose failure mode is most expensive (an evening of plugging
# a phone in, a bundle that says nothing). So it gets a self-test that actually runs
# it: a fake `adb` (test-90-fake-adb.py) answers as a stock non-rooted Android 14
# phone and drives the app's screens through the same taps the driver makes.
#
# Scenarios:
#   happy         a complete run: install, first run, project, file browser, live turn
#                 with a tool card, a file written by the "model" and read back from
#                 outside the app - from Documents/OpenCode on SHARED storage, the v3
#                 default. Every gate must PASS or SKIP, none may FAIL, exit code 0.
#   locked        the phone is asleep behind the keyguard: the run must say THAT (a
#                 FAIL on DEVICE_AWAKE naming the lock), not "the app never showed a
#                 screen".
#   blank         screencap returns a black frame: the screenshot gate must FAIL and
#                 say the frames are blank, instead of counting files.
#   tags-gone     the dumps carry the app's WORDS but none of its resource ids - the
#                 real first-run failure that made a working app look broken. The run
#                 must still reach the projects screen, create a project and pass,
#                 because every wait also matches content.
#   shown-hidden  the platform marks its whole hierarchy shown="false": the run must
#                 still drive the app (a reader that goes blind here reports a working
#                 app as broken), and the log must say the tap was made on a node the
#                 platform called hidden.
#   no-grant      All files access is denied: the app falls back to Android/data and
#                 the visibility check must FAIL SHARED_ROOT - the app is not allowed
#                 to claim file-manager visibility it does not have.
#   dump-unusable uiautomator cannot dump at all ("could not get idle state"): the run
#                 must be red for THAT reason - HARNESS_DUMP FAIL, naming the dump
#                 channel - and must stop there, before any app verdict is produced
#                 (runs with --timeout-scale 0.02, so the failure paths cost seconds
#                 instead of minutes).
#   msys-mangled  THE v2 FALSE FAIL, reproduced byte for byte: what the driver reads
#                 back as a "dump" is the Windows-mangled device path
#                 ("cat: C:/Program Files/Git/sdcard/p10d-ui.xml: No such file or
#                 directory"), exactly as it appeared in the owner's bundle. The run
#                 must stop in seconds (rc=3), name the host-side cause, and produce
#                 NO app verdict - in v2 this same input produced a six-minute timeout
#                 and "the app window never appeared" on a phone that was showing the
#                 app's projects screen.
#
# Usage: bash phase10/scripts/test-90-real-device.sh [--keep]
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

TMP="$(mktemp -d)"
cleanup() { [ "$KEEP" = 1 ] || rm -rf "$TMP"; }
trap cleanup EXIT

pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ok   $1"; }
bad()  { fail=$((fail+1)); echo "  FAIL $1"; }
check() { if [ "$1" = 0 ]; then ok "$2"; else bad "$2"; fi; }

echo "=== 90-real-device-signed.sh self-test (fake phone, no device needed) ==="
python3 "$DIR/scripts/test-90-fixtures.py" "$TMP/fx" || { echo "FATAL: fixtures failed"; exit 2; }

# Prefer the Phase 6 evidence photos when they are here (they are pictures of the real
# app), but the generator has already written valid frames, so this test never depends
# on them being present.
mkdir -p "$TMP/fx/shots"
for pair in "screen:04-first-run-chat.png" "answer:16-chat-markdown.png" \
            "files:20-files-listing.png" "projects:02-first-run-ready.png" \
            "welcome:01-first-run-welcome.png" "settings:18-settings-a11y.png"; do
  name="${pair%%:*}"; src="${pair#*:}"
  [ -f "$ROOT/docs/progress/phase10-evidence/phase6/screenshots/$src" ] && \
    cp "$ROOT/docs/progress/phase10-evidence/phase6/screenshots/$src" "$TMP/fx/shots/$name.png"
done
# The fake phone needs a file to verify; the driver reads its version from the real
# build file, and check-apk.py is skipped in these runs (P10D_SKIP_ARTIFACT=1) because
# a self-test must not depend on a signed artifact being present.
: > "$TMP/fake.apk"

mkdir -p "$TMP/bin"
# Absolute interpreter on purpose: one scenario deliberately puts Windows-style
# `python3` stubs first on PATH to reproduce the owner's host, and the FAKE PHONE
# still has to work in that run - otherwise the scenario would measure the shim
# breaking instead of the driver's own preflight.
REAL_PY="$(command -v python3 || command -v python)"
cat > "$TMP/bin/adb" <<EOF
#!/usr/bin/env bash
exec env P10D_FAKE_ROOT="$TMP" P10D_FAKE_SCENARIO="\${P10D_FAKE_SCENARIO:-happy}" \\
  "$REAL_PY" "$DIR/scripts/test-90-fake-adb.py" "\$@"
EOF
chmod +x "$TMP/bin/adb"

# ------------------------------------------------------------------ scenarios --
# The stubs a Windows host really has: `python3`/`python` exist on PATH, print the
# Store message and exit non-zero. Both the APK inspector and the accessibility
# reader are Python, so v2 produced "check-apk findings: " (empty) and an empty
# "on screen:" at every wait - then blamed the app.
mkdir -p "$TMP/nopython"
for stub in python3 python py; do
  cat > "$TMP/nopython/$stub" <<'STUB'
#!/bin/sh
echo "Python was not found; run without arguments to install from the Microsoft Store, or disable this shortcut from Settings > Apps > Advanced app settings > App execution aliases."
exit 9009
STUB
  chmod +x "$TMP/nopython/$stub"
done

# -------------------------------------------------------------------------------
# The 2026-09-21 owner failure: Windows Python cannot open a POSIX-absolute script
# path. On Git Bash the repo is /p/OpenCodeGUI/..., and `python /p/.../p10d-ui.py`
# makes Windows Python look for P:\p\OpenCodeGUI\... - so the READER died (its stderr
# was thrown away) and every screen read came back empty while the phone was showing
# the app's screens. Two stubs reproduce that on a POSIX test host:
#   winpython  accepts everything EXCEPT a POSIX-absolute path argument - exactly what
#              Windows Python does with "/p/..." (drive-relative), and
#   cygpath    the Git Bash converter, faked as "prepend C:" so host_path()'s output can
#              be checked on a machine that has no MSYS.
mkdir -p "$TMP/winhost"
cat > "$TMP/winhost/python3" <<EOF
#!/bin/sh
# Windows Python's one behaviour that broke the owner's run: a POSIX-absolute path
# argument is read as "<drive>:\<path>" - which does not exist. Every OTHER argument
# (flags, subcommands, relative paths, the code string of -c) is passed through
# untouched, so this stub behaves like the real interpreter right up to the failure.
# $REAL_PY replaces a C:/... path with its POSIX form so the real reader still runs.
n=\$#
i=0
while [ \$i -lt \$n ]; do
  i=\$((i+1)); a="\$1"; shift
  case "\$a" in
    /*) printf "can't open file 'C:%%s': [Errno 2] No such file or directory\\n" "\$a" >&2; exit 2 ;;
    C:/*) a="\${a#C:}" ;;
  esac
  set -- "\$@" "\$a"
done
exec "$REAL_PY" "\$@"
EOF
chmod +x "$TMP/winhost/python3"
cat > "$TMP/winhost/cygpath" <<'STUB'
#!/bin/sh
# -w / --windows => Windows form, "--" ends the options (both as in the real cygpath).
# Only the "prepend the drive" rule is needed to stand in for MSYS's /<drive>/ mounts.
while [ $# -gt 0 ]; do
  case "$1" in
    -w|--windows) ;;
    --) shift; break ;;
    -*) ;;
    *) break ;;
  esac
  shift
done
case "$1" in /*) printf 'C:%s\n' "$1" ;; *) printf '%s\n' "$1" ;; esac
STUB
chmod +x "$TMP/winhost/cygpath"

run_scenario() { # $1 = name, $2.. = extra driver arguments
  local name="$1"; shift
  rm -rf "$TMP/dev" "$TMP/out-$name"
  mkdir -p "$TMP/dev"
  PATH="$TMP/bin:$PATH" \
  P10D_FAKE_SCENARIO="$name" \
  P10D_SKIP_ARTIFACT=1 \
  P10D_PROVIDER_KEY="sk-or-test-only-not-a-real-key" \
  bash "$DIR/scripts/90-real-device-signed.sh" --apk "$TMP/fake.apk" \
      --out "$TMP/out-$name" "$@" > "$TMP/run-$name.stdout" 2>&1
  echo "$?" > "$TMP/rc-$name"
}

echo
echo "--- scenario: happy path ---"
run_scenario happy
RC=$(cat "$TMP/rc-happy")
OUT="$TMP/out-happy"
check "$([ "$RC" = 0 ] && echo 0 || echo 1)" "driver exits 0 when every gate passes (rc=$RC)"
check "$([ -s "$OUT/SUMMARY.txt" ] && echo 0 || echo 1)" "SUMMARY.txt written"
MISSING=$(grep -aoE '^P10D_[A-Z_]+ (PASS|SKIP)' "$OUT/SUMMARY.txt" | awk '{print $1}' | sort -u | wc -l | tr -d ' ')
check "$([ "${MISSING:-0}" -ge 15 ] && echo 0 || echo 1)" "at least 15 distinct gates reported (got ${MISSING:-0})"
for G in DEVICE_AWAKE ABI ANDROID_VERSION INSTALL VERSION_ON_DEVICE FIRST_RUN FIRST_RUN_PROJECT \
         FILES_SCREEN MEMORY STORAGE PACKAGING_SWEEP NO_CRASH SCREENSHOTS LIVE_TURN \
         FILES_APP_AND_SHELL; do
  if grep -qaE "^P10D_$G (PASS|SKIP)" "$OUT/SUMMARY.txt"; then ok "gate $G PASS/SKIP"; else bad "gate $G missing or FAIL: $(grep -a "^P10D_$G" "$OUT/SUMMARY.txt" | head -1)"; fi
done
check "$(grep -qacE '^P10D_[A-Z_]+ FAIL' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" "no FAIL line anywhere in a clean run"
check "$([ -z "$(cat "$OUT/DIAGNOSIS.txt")" ] && echo 0 || echo 1)" "DIAGNOSIS.txt stays empty on a clean run"
NSHOTS=$(ls -1 "$OUT/screenshots"/*.png 2>/dev/null | wc -l | tr -d ' ')
check "$([ "${NSHOTS:-0}" -ge 8 ] && echo 0 || echo 1)" "screenshots captured at every step (got ${NSHOTS:-0})"
check "$(grep -q 'screen=yes' "$OUT/screenshots.log" && echo 0 || echo 1)" "screenshot validation ran and saw real screens"
check "$(grep -qa '^P10D_FIRST_RUN_PROJECT PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "project was created through taps+typing"
check "$(grep -qa '^P10D_LIVE_TURN PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "live turn with a tool card"
check "$(grep -qa '^P10D_FILES_APP_AND_SHELL PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "in-app browser path == shell-visible path"
check "$(grep -qa 'P6_MODEL_AVAILABLE 1' "$OUT/p10d-model-lines.txt" && echo 0 || echo 1)" "model marker written for the x86_64 carry-forward"
check "$(grep -qE '^P10D_VISIBILITY_(LOCATION|SHELL_LIST|SHELL_READ|OLD_ROOT_EMPTY|SHELL_BASELINE) PASS' "$OUT/visibility.log" && echo 0 || echo 1)" "visibility script reports its own verdicts"
# The file the "model" wrote must be readable from OUTSIDE the app - that is the whole
# point of R7, so assert on the file, not just on the verdict line. And it must be in
# the SHARED location (Documents/OpenCode), not the Android/data corner a file manager
# cannot open on Android 11+: that difference is the v3 product decision.
PROJ=$(cat "$TMP/dev/project" 2>/dev/null)
check "$([ -s "$TMP/dev/shared/Documents/OpenCode/$PROJ/p10-visible.txt" ] && echo 0 || echo 1)" \
  "the agent's file exists on (fake) SHARED storage: Documents/OpenCode/$PROJ/p10-visible.txt"
check "$([ -e "$TMP/dev/ext/io.github.mcyber12.opencode/files/workspaces/$PROJ/p10-visible.txt" ] && echo 1 || echo 0)" \
  "and NOT in Android/data (the location file managers cannot browse)"
check "$(grep -qa '^P10D_FILES_SCREEN PASS.*Documents/OpenCode' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the in-app file browser names the shared location"

echo
echo "--- scenario: locked device ---"
run_scenario locked
RC=$(cat "$TMP/rc-locked"); OUT="$TMP/out-locked"
check "$([ "$RC" != 0 ] && echo 0 || echo 1)" "a locked device is a non-zero exit (rc=$RC)"
check "$(grep -qa '^P10D_DEVICE_AWAKE FAIL' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "DEVICE_AWAKE FAIL reported"
check "$(grep -qaE 'Unlock the phone' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "the reason tells the human what to do (unlock)"
check "$(grep -qa 'no working screen' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" "it does NOT blame the app ('no working screen')"

echo
echo "--- scenario: blank screencap ---"
run_scenario blank
RC=$(cat "$TMP/rc-blank"); OUT="$TMP/out-blank"
check "$([ "$RC" != 0 ] && echo 0 || echo 1)" "blank frames are a non-zero exit (rc=$RC)"
check "$(grep -qa '^P10D_SCREENSHOTS FAIL' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "SCREENSHOTS FAIL reported"
check "$(grep -qa 'blank' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "the reason says the frames are blank/off"

echo
echo "--- scenario: no Compose tags in the dump (the real first-run false FAIL) ---"
run_scenario tags-gone
RC=$(cat "$TMP/rc-tags-gone"); OUT="$TMP/out-tags-gone"
check "$([ "$RC" = 0 ] && echo 0 || echo 1)" "the run passes with tag-less dumps (rc=$RC)"
check "$(grep -qa '^P10D_FIRST_RUN PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "FIRST_RUN PASS on content alone"
check "$(grep -qa '^P10D_FIRST_RUN_PROJECT PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "the project was created by content-matched taps"
check "$(grep -qa '^P10D_LIVE_TURN PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "the live turn ran on content alone"
check "$(grep -qaE '^P10D_[A-Z_]+ FAIL' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" "no FAIL line anywhere in the tag-less run"
check "$(! grep -q 'resource-id="[a-z]' "$OUT/ui/ui-files-screen.xml" && echo 0 || echo 1)" \
  "the fixture really did serve dumps with no tags (evidence, not setup)"

echo
echo "--- scenario: the platform marks everything shown=false ---"
run_scenario shown-hidden
RC=$(cat "$TMP/rc-shown-hidden"); OUT="$TMP/out-shown-hidden"
check "$([ "$RC" = 0 ] && echo 0 || echo 1)" "the run still passes when the dump says shown=false everywhere (rc=$RC)"
check "$(grep -qa 'marked shown=false' "$OUT/run.log" && echo 0 || echo 1)" "the driver says it tapped a node the platform called hidden"
check "$(grep -qa '^P10D_LIVE_TURN PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "the live turn still ran"

echo
echo "--- scenario: the app has no All files access (fallback must be stated) ---"
run_scenario no-grant
RC=$(cat "$TMP/rc-no-grant"); OUT="$TMP/out-no-grant"
check "$([ "$RC" != 0 ] && echo 0 || echo 1)" "the visibility check fails on the Android/data fallback (rc=$RC)"
check "$(grep -qa '^P10D_VISIBILITY_SHARED_ROOT FAIL' "$OUT/visibility.log" && echo 0 || echo 1)" \
  "SHARED_ROOT FAIL names the Android/data location"
check "$(grep -qa '^P10D_FILES_SCREEN PASS.*Android/data' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the app's own file browser states the fallback honestly"

echo
echo "--- scenario: the accessibility channel is unavailable (fast, scaled timeouts) ---"
run_scenario dump-unusable --timeout-scale 0.02
RC=$(cat "$TMP/rc-dump-unusable"); OUT="$TMP/out-dump-unusable"
check "$([ "$RC" != 0 ] && echo 0 || echo 1)" "an unreadable dump channel is a non-zero exit (rc=$RC)"
# v3 stops at R0.5 instead of spending six minutes timing out: the first dump is
# the harness's own test, and if the harness cannot read the screen then no verdict
# below it is a statement about the app.
check "$(grep -qa '^P10D_HARNESS_DUMP FAIL' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "HARNESS_DUMP FAIL is the verdict that names the problem"
check "$(grep -qa '^P10D_DEVICE_AWAKE PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the device itself was fine - the failure is not blamed on the phone"
check "$(grep -qa 'could not get idle state' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "the raw uiautomator error is in DIAGNOSIS.txt"
check "$(grep -qa 'nothing came back at all' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "the reason names the device-side cause (nothing was written)"
check "$(grep -qa 'why: ' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "every failed attempt carries a 'why' line the reader can act on"
# The self-contradiction that started all of this: a verdict saying the app never
# appeared while mCurrentFocus is the app. Nothing may claim that any more.
check "$(grep -qaE '^P10D_(FIRST_RUN|FILES_SCREEN|LIVE_TURN) ' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" \
  "no app verdict is produced at all (the run stopped before it could guess)"

echo
echo "--- scenario: the v2 false FAIL, byte for byte (a Windows host mangled the device path) ---"
# p10d-out/ui/ui-wait-app-window.xml contained exactly the line this scenario
# serves. v2 read it as an empty screen, timed out for six minutes and reported
# "the app window never appeared"; the app was showing its projects screen.
run_scenario msys-mangled
RC=$(cat "$TMP/rc-msys-mangled"); OUT="$TMP/out-msys-mangled"
check "$([ "$RC" = 3 ] && echo 0 || echo 1)" "the run stops early (rc=$RC) instead of timing out for minutes"
check "$(grep -qa '^P10D_HARNESS_DUMP FAIL' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "HARNESS_DUMP FAIL names the unreadable dump"
check "$(grep -qa 'HOST shell rewrote the device path' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the verdict names Git-Bash/MSYS path conversion as the cause"
check "$(grep -qa '^P10D_FIRST_RUN ' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" \
  "the app is never blamed (no FIRST_RUN verdict in a run that could not see)"
check "$(grep -qa 'Program Files/Git/sdcard' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "the raw mangled path is in DIAGNOSIS.txt for the reader to recognize"
check "$(grep -qa 'host shell: .*' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "the diagnosis records which host shell ran it"
check "$(! grep -q 'resource-id=' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "nothing else in the summary pretends to be screen evidence"

echo
echo "--- scenario: a Windows host with no working python3 (the other half of the v2 false FAIL) ---"
rm -rf "$TMP/dev" "$TMP/out-no-python"
mkdir -p "$TMP/dev"
PATH="$TMP/bin:$TMP/nopython:$PATH" \
P10D_FAKE_SCENARIO=happy \
P10D_SKIP_ARTIFACT=1 \
P10D_PROVIDER_KEY="sk-or-test-only-not-a-real-key" \
bash "$DIR/scripts/90-real-device-signed.sh" --apk "$TMP/fake.apk" \
    --out "$TMP/out-no-python" > "$TMP/run-no-python.stdout" 2>&1
echo "$?" > "$TMP/rc-no-python"
RC=$(cat "$TMP/rc-no-python"); OUT="$TMP/out-no-python"
check "$([ "$RC" = 3 ] && echo 0 || echo 1)" "the run stops early (rc=$RC) instead of reporting an app failure"
check "$(grep -qa '^P10D_HARNESS_PYTHON FAIL' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "HARNESS_PYTHON FAIL names the missing interpreter"
check "$(grep -qa '^P10D_ARTIFACT ' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" \
  "no APK verdict is invented from an inspector that never ran"
check "$(grep -qa '^P10D_FIRST_RUN ' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" \
  "no first-run verdict is invented either"
check "$(grep -qa 'Store stub' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "the diagnosis explains the Windows Store stub to the reader"

echo
echo "--- scenario: the reader cannot open its own script (the owner's 2026-09-21 run) ---"
# Same fake host as above, WITHOUT the HOST_SHELL seam: nothing converts, so the reader
# dies the way it really died. What must happen now: the harness notices at R0.5, names
# the reader as the problem, stops (rc=3) and produces NO app verdict - instead of the
# six-minute timeout and the empty "(; acquisition: ...)" PASS the owner's bundle has.
rm -rf "$TMP/dev" "$TMP/out-reader-dead"
mkdir -p "$TMP/dev"
PATH="$TMP/bin:$TMP/winhost:$PATH" \
P10D_FAKE_SCENARIO=happy \
P10D_SKIP_ARTIFACT=1 \
P10D_PROVIDER_KEY="sk-or-test-only-not-a-real-key" \
bash "$DIR/scripts/90-real-device-signed.sh" --apk "$TMP/fake.apk" \
    --out "$TMP/out-reader-dead" > "$TMP/run-reader-dead.stdout" 2>&1
echo "$?" > "$TMP/rc-reader-dead"
RC=$(cat "$TMP/rc-reader-dead"); OUT="$TMP/out-reader-dead"
check "$([ "$RC" = 3 ] && echo 0 || echo 1)" "the run stops at the preflight (rc=$RC) instead of waiting 300s on a visible screen"
check "$(grep -qa '^P10D_HARNESS_READER FAIL' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "HARNESS_READER FAIL names the reader (a gate v3 did not have)"
check "$(grep -qa '^P10D_HARNESS_DUMP PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the dump itself is still reported as readable - the two are separate facts"
check "$(! grep -qa '; acquisition' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "no verdict prints an empty node count (the tell the owner's bundle had)"
check "$(grep -qa "can't open file" "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "DIAGNOSIS.txt quotes the reader's real error, which used to be discarded"
check "$(grep -qa 'host path-translation problem' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "the diagnosis names the cause instead of leaving it to the reader"
check "$(grep -qaE '^P10D_(FIRST_RUN|FIRST_RUN_PROJECT|FILES_SCREEN|LIVE_TURN) ' "$OUT/SUMMARY.txt" && echo 1 || echo 0)" \
  "no app verdict is invented from a reader that never ran"
check "$(! grep -qa 'TIMED OUT' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "nothing times out for minutes - the failure is immediate"

echo
echo "--- scenario: the same host WITH the Windows path conversion (the fix) ---"
# HOST_SHELL forced to windows-msys so host_path() takes the Windows branch (with the
# cygpath stub converting, and winpython accepting the converted C:/... path). Everything
# else is the owner's host shape. This is the run that must come back GREEN.
rm -rf "$TMP/dev" "$TMP/out-winhost"
mkdir -p "$TMP/dev"
PATH="$TMP/bin:$TMP/winhost:$PATH" \
P10D_FORCE_HOST_SHELL=windows-msys \
P10D_FAKE_SCENARIO=happy \
P10D_SKIP_ARTIFACT=1 \
P10D_PROVIDER_KEY="sk-or-test-only-not-a-real-key" \
bash "$DIR/scripts/90-real-device-signed.sh" --apk "$TMP/fake.apk" \
    --out "$TMP/out-winhost" > "$TMP/run-winhost.stdout" 2>&1
echo "$?" > "$TMP/rc-winhost"
RC=$(cat "$TMP/rc-winhost"); OUT="$TMP/out-winhost"
check "$([ "$RC" = 0 ] && echo 0 || echo 1)" "a Windows host with path conversion now runs green (rc=$RC)"
check "$(grep -qa '^P10D_HARNESS_READER PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "HARNESS_READER PASS - the reader really parsed the dump"
check "$(grep -qa '^P10D_DEVICE_AWAKE PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the run got past the preflight and drove the app"
check "$(grep -qaE '^P10D_HARNESS_READER PASS.*C:/' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the verdict shows the converted path it used (evidence of the conversion)"
check "$(grep -qa '^P10D_FIRST_RUN PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the first-run screens were read on the converted path"
check "$(grep -qa '^P10D_LIVE_TURN PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the live turn ran - the whole run is usable on a simulated Windows host"

echo
echo "--- scenario: a relative --out, run from another directory (the owner's shape) ---"
# The owner runs `bash phase10/scripts/90-real-device-signed.sh --apk ...` from the repo
# root, so OUT is the RELATIVE default "p10d-out". The visibility driver is now invoked
# after a `cd` to its own directory, so a relative --out handed to it would land inside
# phase10/. This scenario runs the driver from an unrelated cwd with a relative --out and
# checks the bundle lands next to the caller and nowhere else.
rm -rf "$TMP/dev" "$TMP/relcwd" "$DIR/p10d-out-relative-test"
mkdir -p "$TMP/dev" "$TMP/relcwd"
( cd "$TMP/relcwd" && PATH="$TMP/bin:$PATH" \
  P10D_FAKE_SCENARIO=happy P10D_SKIP_ARTIFACT=1 \
  P10D_PROVIDER_KEY="sk-or-test-only-not-a-real-key" \
  bash "$DIR/scripts/90-real-device-signed.sh" --apk "$TMP/fake.apk" \
      --out p10d-out-relative-test > "$TMP/run-relout.stdout" 2>&1; echo "$?" > "$TMP/rc-relout" )
RC=$(cat "$TMP/rc-relout"); OUT="$TMP/relcwd/p10d-out-relative-test"
check "$([ "$RC" = 0 ] && echo 0 || echo 1)" "a relative --out run is green (rc=$RC)"
check "$([ -s "$OUT/SUMMARY.txt" ] && echo 0 || echo 1)" "the bundle landed under the caller's cwd"
check "$([ -f "$OUT/visibility.log" ] && echo 0 || echo 1)" "visibility.log is in that bundle"
check "$(grep -qa '^P10D_VISIBILITY_SHARED_ROOT PASS' "$OUT/visibility.log" && echo 0 || echo 1)" \
  "the visibility driver ran and passed with a relative --out"
check "$([ ! -d "$DIR/p10d-out-relative-test" ] && echo 0 || echo 1)" \
  "and nothing was written into phase10/ by the child's cd"

echo
echo "=== driver self-test: pass=$pass fail=$fail ==="
if [ "$fail" = 0 ]; then echo "SELFTEST PASS (driver runs clean, and fails for the RIGHT reasons)"; else echo "SELFTEST FAIL"; fi
[ "$KEEP" = 1 ] && echo "kept: $TMP"
exit $([ "$fail" = 0 ] && echo 0 || echo 1)
