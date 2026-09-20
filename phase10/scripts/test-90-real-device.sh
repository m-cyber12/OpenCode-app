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
#                 must be red for THAT reason - UI_DUMP FAIL naming the dump channel -
#                 instead of blaming the app (runs with --timeout-scale 0.02, so the
#                 failure paths cost seconds instead of minutes).
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
cat > "$TMP/bin/adb" <<EOF
#!/usr/bin/env bash
exec env P10D_FAKE_ROOT="$TMP" P10D_FAKE_SCENARIO="\${P10D_FAKE_SCENARIO:-happy}" \\
  python3 "$DIR/scripts/test-90-fake-adb.py" "\$@"
EOF
chmod +x "$TMP/bin/adb"

# ------------------------------------------------------------------ scenarios --
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
check "$(grep -qa '^P10D_UI_DUMP FAIL' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" "UI_DUMP FAIL is reported"
check "$(grep -qa 'could not be read at all' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the reason names the dump channel, not the app"
check "$(grep -qa 'could not get idle state' "$OUT/DIAGNOSIS.txt" && echo 0 || echo 1)" \
  "the raw uiautomator error is in DIAGNOSIS.txt"
check "$(grep -qa '^P10D_DEVICE_AWAKE PASS' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the device itself was fine - the failure is not blamed on the phone"
# The self-contradiction that started this: a verdict saying the app never appeared
# while mCurrentFocus is the app. When the dump channel is the problem, the verdict
# itself has to say so.
check "$(grep -qa 'accessibility dump was unreadable' "$OUT/SUMMARY.txt" && echo 0 || echo 1)" \
  "the failing verdict itself names the unreadable dump (no self-contradicting evidence)"

echo
echo "=== driver self-test: pass=$pass fail=$fail ==="
if [ "$fail" = 0 ]; then echo "SELFTEST PASS (driver runs clean, and fails for the RIGHT reasons)"; else echo "SELFTEST FAIL"; fi
[ "$KEEP" = 1 ] && echo "kept: $TMP"
exit $([ "$fail" = 0 ] && echo 0 || echo 1)
