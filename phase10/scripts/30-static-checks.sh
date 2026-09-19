#!/usr/bin/env bash
# 30-static-checks.sh - everything Phase 10 can verify WITHOUT a device, run
# before the expensive stages so a bad push fails in seconds.
#
# It is deliberately two layers:
#   1. the Phase 9 checker, verbatim (shell/python/JS syntax, ASCII, workflow
#      YAML, Kotlin lexical sanity, the five UI rules, versions.lock == shipped,
#      the docs set) - Phase 10 must not weaken anything Phase 9 enforced;
#   2. the Phase 10 additions: release invariants (no key material anywhere, the
#      published identity, the store package), the APK/AAB inspector's own
#      self-test, and a check that the Phase 10 workflow cannot sign.
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"; export ROOT
OUT="$DIR/out"
LOG="$OUT/static-checks.log"
mkdir -p "$OUT"
: > "$LOG"
cd "$ROOT" || exit 1

RC=0
run() { # $1 = label, rest = command
  local label="$1"; shift
  {
    echo
    echo "=== $label ==="
    "$@"
    local rc=$?
    if [ "$rc" = 0 ]; then echo "OK   $label"; else echo "FAIL $label (rc=$rc)"; fi
    return "$rc"
  } 2>&1 | tee -a "$LOG"
  [ "${PIPESTATUS[0]}" = 0 ] || RC=1
  return 0
}

echo "=== Phase 10 static checks $(date -u +%FT%TZ) ===" | tee -a "$LOG"

# ---- 1. everything Phase 9 checked, still checked --------------------------
# The Phase 9 script scans the phase trees for syntax, ASCII, workflow YAML and
# the UI rules. Re-running it here means a Phase 10 change cannot quietly drop a
# Phase 9 invariant (its own lock check and docs check are in the same run).
run "phase 9 static checks (not weakened)" bash "$ROOT/phase9/scripts/30-static-checks.sh"

# ---- 2. Phase 10 release invariants ----------------------------------------
run "release invariants (no key material / published identity / store package)" \
  python3 "$ROOT/phase10/scripts/check-release-invariants.py" "$ROOT"

# ---- 3. the APK/AAB inspector works ----------------------------------------
# A checker nobody has run is not evidence. The self-test encodes a binary
# AndroidManifest.xml, packs APK/AAB-shaped zips and asserts the inspector reads
# back what was encoded - including that it FAILS a wrong expectation.
run "apk/aab inspector self-test" python3 "$ROOT/phase10/scripts/test-check-apk.py"

# ---- 3b. the build script's static shape -----------------------------------
# Phase 9's syntax scans cover app/src, NOT app/build.gradle.kts; CI runs 1, 2,
# 3 and 6 each paid for a defect that lived exactly there (use-before-definition,
# a type-safe accessor that cannot exist, a duplicate import, a payload fix that
# must not silently leave the file). This checker pays 0.1 s per run instead.
run "app/build.gradle.kts static checks (balance, order, run-#6 fix present, identity greps)" \
  python3 "$ROOT/phase10/scripts/check-gradle-script.py" "$ROOT"

# ---- 4. shell/python syntax of the Phase 10 tree ---------------------------
SHELLSYN=0
while IFS= read -r f; do
  bash -n "$f" >> "$LOG" 2>&1 || { echo "FAIL bash -n $f" | tee -a "$LOG"; SHELLSYN=1; }
done < <(find "$ROOT/phase10" -name '*.sh' -type f | sort)
[ "$SHELLSYN" = 0 ] && echo "OK   bash -n on the phase10 scripts" | tee -a "$LOG" || RC=1

PYSYN=0
while IFS= read -r f; do
  python3 -m py_compile "$f" >> "$LOG" 2>&1 || { echo "FAIL py_compile $f" | tee -a "$LOG"; PYSYN=1; }
done < <(find "$ROOT/phase10" -name '*.py' -type f | sort)
[ "$PYSYN" = 0 ] && echo "OK   py_compile on the phase10 python" | tee -a "$LOG" || RC=1

# ---- 4b. the real-device driver's helpers are themselves tested -------------
# The v1 signed-build run produced a FAIL that could not be diagnosed from its own
# bundle, because its screenshot check was "the file exists" and its UI check was a
# grep. Both helpers now have self-tests: a checker that cannot tell a black frame
# from a screen, or that silently matches nothing, is worse than no checker.
run "screenshot sanity checker self-test (black/blank vs a real screen)" \
  python3 "$ROOT/phase10/scripts/test-p10d-png.py"
run "uiautomator dump reader self-test (tap targets, disabled states, diagnostics)" \
  python3 "$ROOT/phase10/scripts/test-p10d-ui.py"

# ---- 4b-ii. the fake phone still answers like a phone -----------------------
# test-90-real-device.sh (the driver's end-to-end self-test) runs in the CI pipeline,
# where two minutes is affordable. What runs HERE is the 2-second part: if the fake
# `adb` shim cannot even answer the two questions every run starts with, the driver's
# self-test would fail in a way that looks like a driver bug. Cheap, and it keeps the
# shim honest between full runs.
run "fake-phone shim answers the first two questions of every run" \
  bash -c 'TMP="$(mktemp -d)"; trap "rm -rf \"$TMP\"" EXIT;
           python3 "$ROOT/phase10/scripts/test-90-fixtures.py" "$TMP/fx" >/dev/null &&
           a=$(env P10D_FAKE_ROOT="$TMP" P10D_FAKE_SCENARIO=happy python3 "$ROOT/phase10/scripts/test-90-fake-adb.py" get-state) &&
           b=$(env P10D_FAKE_ROOT="$TMP" P10D_FAKE_SCENARIO=locked python3 "$ROOT/phase10/scripts/test-90-fake-adb.py" shell dumpsys window) &&
           c=$(env P10D_FAKE_ROOT="$TMP" P10D_FAKE_SCENARIO=happy python3 "$ROOT/phase10/scripts/test-90-fake-adb.py" exec-out cat /sdcard/p10d-ui.xml) &&
           [ "$a" = device ] && printf "%s" "$b" | grep -q Keyguard &&
           printf "%s" "$c" | grep -q welcome_screen'

# ---- 4c. icons: only what material-icons-core actually ships -----------------
# Advisory (never fails the run): naming the trap here costs a second, while
# discovering it in a compile step costs a whole pipeline run.
run "compose icon availability (material-icons-core only)" \
  python3 "$ROOT/phase10/scripts/check-compose-icons.py"

# ---- 5. the Phase 10 workflow exists in the tree and cannot sign ------------
# The workflow is REQUIRED to explain which secrets must never be added (and its
# own guard step checks that they are absent), so NAMING them is fine. What must
# never appear is an expression that would PULL one in - the same rule
# phase10/scripts/check-release-invariants.py applies to the installed copy.
run "phase10 workflow template present + no signing secret is pulled in" \
  bash -c 'test -s "$ROOT/phase10/workflow/phase10-release.yml" &&
           ! grep -qE "secrets\.[A-Za-z0-9_]*(KEYSTORE|KEY_ALIAS|KEY_PASSWORD)" "$ROOT/phase10/workflow/phase10-release.yml"'

# ---- 6. the UI polish did not introduce an unbounded animation -------------
# Phase 10 added motion (animateContentSize, a rotating chevron). An INFINITE
# animation would keep Compose's test clock busy and hang waitForIdle() in the
# UI gates - the polish must stay finite.
run "no infinite animations in the UI layer (Compose test clock)" \
  bash -c '! grep -rn "rememberInfiniteTransition\|infiniteRepeatable" "$ROOT/app/src/main/java/ai/opencode/android/ui"'

echo | tee -a "$LOG"
echo "=== phase 10 static checks summary rc=$RC ===" | tee -a "$LOG"
grep -a '^FAIL' "$LOG" | head -40 | tee -a "$LOG" >/dev/null || true
exit "$RC"
