#!/usr/bin/env bash
# 00-run-phase10.sh - single orchestrator for the Phase 10 SIGNING / STORE-PREP run.
#
# What this run has to prove (Core Rule 4: a green build is not evidence):
#   1. static: Phase 9's checks (unchanged) + the Phase 10 release invariants -
#      no key material in the tree, the published identity is the intended one,
#      the store package exists and fits Play's limits;
#   2. compile + JVM unit tests (fast fail, before the payload build);
#   3. the embedded runtime payload, built once and reused by every build;
#   4. a FRESH emulator at a phone-sized screen (1080x1920, so the screenshots are
#      usable listing assets):
#        a. debug build -> Phase 6 UI gates (F1-F4, U1-U8). This is the answer to
#           "did the Phase 10 visual polish break a Phase 6 gate?";
#        b. release-shaped SMOKE build (release applicationId, release code shape,
#           debuggable, debug-key signed) -> the SAME Phase 6 UI gates, plus the
#           packaging gates (identity, payload, abi split, no debuggable release);
#        c. store screenshots: adb screencap of the real app, validated against
#           Play's size/ratio rules;
#   5. release APK + AAB, UNSIGNED (CI has no key and must never have one), then
#      inspected byte-for-byte by phase10/scripts/check-apk.py;
#   6. the Phase 9 gates on the same device (provider selection, plugin seed,
#      version string, versions.lock == shipped);
#   7. one GATES_SUMMARY.txt naming EVERY gate verdict, printed last, deciding the
#      job's exit status.
#
# Knobs (never for a verdict run):
#   P10_GRADLE_ONLY=1 / phase10/CI_GRADLE_ONLY: compile + JVM tests only.
#   P10_SKIP_SMOKE=1, P10_SKIP_P9GATES=1, P10_SKIP_SHOTS=1: skip one stage.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
REL="$OUT/release"
SMOKE="$OUT/smoke"
SHOTS="$OUT/screenshots"
mkdir -p "$OUT" "$EV" "$REL" "$SMOKE" "$SHOTS"
MAINLOG="$OUT/00-run-phase10.log"
: > "$MAINLOG"

step() { echo; echo "########## $1 ##########" | tee -a "$MAINLOG"; }
run_c() {
  local t="$1"; shift
  local logf="$OUT/step.$$.out"
  timeout -k 60 "$t" bash -c "${*}" > "$logf" 2>&1
  local rc=$?
  tail -c 200000 "$logf" 2>/dev/null
  cat "$logf" >> "$MAINLOG" 2>/dev/null
  rm -f "$logf"
  if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then echo "STEP_TIMEOUT rc=$rc after ${t}s: ${*}" | tee -a "$MAINLOG"; fi
  [ "$rc" = 0 ] || { echo "STEP_FAILED rc=$rc: ${*}" | tee -a "$MAINLOG"; return 1; }
}

echo "=== PHASE 10 START $(date -u +%FT%TZ) ===" | tee -a "$MAINLOG"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"

FINAL="$EV/GATES_SUMMARY.txt"
: > "$FINAL"
note_gate() { echo "$*" | tee -a "$FINAL" | tee -a "$MAINLOG" >/dev/null; }

collect_evidence() {
  cp "$MAINLOG" "$EV/00-run-phase10.log" 2>/dev/null || true
  cp "$OUT/emulator.log" "$EV/" 2>/dev/null || true
  cp "$ROOT/phase4/out/engine/assets/runtime-manifest.json" "$EV/runtime-manifest.json" 2>/dev/null || true
  mkdir -p "$EV/jvm-unit-tests" "$EV/phase6"
  cp "$ROOT"/app/build/test-results/testDebugUnitTest/*.xml "$EV/jvm-unit-tests/" 2>/dev/null || true
  cp -r "$ROOT/phase6/out/evidence/." "$EV/phase6/" 2>/dev/null || true
  # The Phase 9 gate evidence produced by stage 6 (it writes into phase9/out).
  mkdir -p "$EV/phase9"
  cp -r "$ROOT/phase9/out/evidence/." "$EV/phase9/" 2>/dev/null || true
  # artifact identity reports + hashes (the artifacts themselves go to the
  # workflow's upload, never to git)
  ( cd "$REL" 2>/dev/null && sha256sum * 2>/dev/null ) > "$EV/release-sha256.txt" || true
  ( cd "$REL" 2>/dev/null && ls -la ) > "$EV/release-listing.txt" 2>&1 || true
  ( cd "$SMOKE" 2>/dev/null && ls -la ) > "$EV/smoke-listing.txt" 2>&1 || true
  ( cd "$ROOT/docs/store/screenshots" 2>/dev/null && for f in *.png; do
      [ -f "$f" ] && identify -format "%f %wx%h\n" "$f" 2>/dev/null
    done ) > "$EV/store-screenshots.txt" 2>&1 || true
}

push_evidence() {
  [ "${P10_SKIP_PUSH:-0}" = "1" ] && return 0
  git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 0
  DEST="${GITHUB_REF_NAME:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)}"
  mkdir -p "$ROOT/docs/progress/phase10-evidence"
  cp -r "$EV/." "$ROOT/docs/progress/phase10-evidence/" 2>/dev/null || true
  rm -f "$ROOT/docs/progress/phase10-evidence/emulator.log" 2>/dev/null || true
  find "$ROOT/docs/progress/phase10-evidence" -type f -size +2M -delete 2>/dev/null || true
  # Store screenshots are listing assets, not evidence: they live in docs/store.
  if [ -d "$ROOT/docs/store/screenshots" ] && compgen -G "$ROOT/docs/store/screenshots/*.png" >/dev/null; then
    git -C "$ROOT" add -A docs/store/screenshots/ 2>/dev/null || true
  fi
  export GIT_TERMINAL_PROMPT=0
  timeout 180 git -C "$ROOT" fetch origin "$DEST" >/dev/null 2>&1 || true
  git -C "$ROOT" add -A docs/progress/phase10-evidence/ 2>/dev/null || true
  git -C "$ROOT" diff --cached --quiet && { echo "no evidence changes to commit"; return 0; }
  git -C "$ROOT" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
    commit -m "phase10: signing/store-prep gate evidence (auto)" >/dev/null 2>&1 || true
  # Two runs can overlap on one branch (run #12/#13 of this session did: the second
  # push started a pipeline while the first was still on its emulator). Both then
  # rewrite the same generated files, the rebase conflicts, and the loser's evidence
  # is simply gone - which is what happened to run #13's GATES_SUMMARY. So: retry,
  # and resolve a conflict in favour of the copy already on the branch, because that
  # copy comes from the newest run and this run's files are the same generated paths.
  for attempt in 1 2 3; do
    timeout 180 git -C "$ROOT" pull --rebase --strategy=recursive --strategy-option=theirs \
      origin "$DEST" >/dev/null 2>&1 || git -C "$ROOT" rebase --abort >/dev/null 2>&1 || true
    if timeout 240 git -C "$ROOT" push origin "HEAD:$DEST" > "$OUT/push.out" 2>&1; then
      echo "evidence pushed (attempt $attempt)" >> "$OUT/push.out"
      return 0
    fi
    echo "PUSH_RETRY attempt=$attempt dest=$DEST" >> "$OUT/push.out"
    sleep 20
  done
  echo "PUSH_FAILED dest=$DEST (3 attempts; see push.out)" >> "$OUT/push.out"
  echo "PUSH_FAILED dest=$DEST"
}

record_fatal() {
  echo "FATAL: $1" | tee -a "$MAINLOG"
  note_gate "P10-BUILD FAIL: $1"
  note_gate "P10_SUMMARY gates not reached (build/environment failure) - see 00-run-phase10.log"
  grep -aE '^(e: file:|FAILURE:|BUILD FAILED|> Task .*FAILED|\* What went wrong|.*: error:)' "$MAINLOG" 2>/dev/null | tail -200 > "$EV/compiler-errors.txt" || true
  collect_evidence; push_evidence
  echo "=== PHASE 10 END $(date -u +%FT%TZ) FATAL: $1 ===" | tee -a "$MAINLOG"
  exit 1
}

GRADLE_ONLY=0
[ "${P10_GRADLE_ONLY:-0}" = "1" ] && GRADLE_ONLY=1
grep -qs '^1' "$DIR/CI_GRADLE_ONLY" 2>/dev/null && GRADLE_ONLY=1

step "1/9 static checks (phase 9 invariants + phase 10 release invariants)"
run_c 900 "bash '$DIR/scripts/30-static-checks.sh'" || record_fatal "static checks failed (see phase10/out/static-checks.log)"
cp "$OUT/static-checks.log" "$EV/p10-static-checks.log" 2>/dev/null || true
note_gate "P10-STATIC PASS: phase9 checks unchanged + release invariants (no key material, published identity, store package) + apk inspector self-test"

step "1b/9 the real-device driver, run against a fake phone (self-test)"
# 90-real-device-signed.sh is the one script meant to be run by a human on hardware
# CI cannot reach, and its failure mode used to be an evening of plugging a phone in
# plus a bundle that said nothing. So it is RUN here first, against a fake `adb` that
# models a stock non-rooted Android 14 phone (test-90-real-device.sh), in nine
# scenarios: the happy path has to end with every gate PASS/SKIP and exit 0 on the
# shared Documents/OpenCode root, a locked keyguard has to be reported as a lock (not
# as an app failure), a blank screencap has to fail the screenshot gate, and the v3
# cases have to behave: tag-less dumps (`tags-gone`) must still produce a green run,
# a hierarchy marked `shown="false"` must not blind the driver, a refused All files
# access must FAIL the shared-root visibility check, an unreadable dump channel must
# stop the run for THAT reason (HARNESS_DUMP) instead of blaming the app, and the two
# host-side failures the owner's bundle turned out to contain - a Windows shell that
# rewrote device paths (`msys-mangled`) and a Microsoft Store `python3` stub
# (`no-python`) - must be named in seconds without producing a single app verdict.
# Reading the script is not running it: the first run of this self-test found four
# bugs that would have reached the phone, and the real-device false FAIL was a
# fifth - it is now a scenario.
DSRC=0
# Keep the transcript AND the exit code: run_c deletes its own temp log, and a pipe
# into tee would report tee's status instead of the self-test's.
# 1800s: seven scenarios, and the `dump-unusable` one deliberately spends its time in
# retry loops (it is the scenario that proves the driver retries before it blames the
# app). Measured at ~12 minutes on a CI runner.
run_c 1800 "bash '$DIR/scripts/test-90-real-device.sh' > '$OUT/driver-selftest.log' 2>&1; rc=\$?; cat '$OUT/driver-selftest.log'; exit \$rc" || DSRC=1
cp "$OUT/driver-selftest.log" "$EV/p10-driver-selftest.log" 2>/dev/null || true
if [ "$DSRC" = 0 ]; then
  note_gate "P10_DRIVER_SELFTEST PASS: the real-device driver ran end to end against a fake phone (9 scenarios: happy, locked, blank, tags-gone, shown-hidden, no-grant, dump-unusable, msys-mangled, no-python)"
else
  note_gate "P10_DRIVER_SELFTEST FAIL: see p10-driver-selftest.log - do NOT hand this driver to a phone"
fi

step "2/9 compile + JVM unit tests (fast fail before the payload build)"
run_c 2400 "SKIP_PAYLOAD=1 '$ROOT/gradlew' -p '$ROOT' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest -PskipPayload --no-daemon --stacktrace" \
  || record_fatal "kotlin compile or JVM unit tests failed (compiler-errors.txt)"
UT=$(python3 - "$ROOT/app/build/test-results/testDebugUnitTest" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob(sys.argv[1]+"/*.xml"):
    r=ET.parse(p).getroot(); t+=int(r.get("tests",0)); f+=int(r.get("failures",0)); e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
print(f"tests={t} failures={f} errors={e} skipped={s}")
EOF
)
note_gate "P10-UNIT PASS: JVM $UT (unit tests are NOT runtime evidence - the device gates below are)"
if [ "$GRADLE_ONLY" = "1" ]; then
  note_gate "P10 MODE gradle-only: no payload, no emulator, no device verdicts, no artifacts in this run"
  collect_evidence; push_evidence
  echo "=== PHASE 10 END $(date -u +%FT%TZ) gradle-only ok ===" | tee -a "$MAINLOG"; exit 0
fi

step "3/9 embedded runtime payload (pinned upstream; reused by every build below)"
if [ ! -f "$ROOT/phase4/out/engine/assets/runtime-manifest.json" ]; then
  run_c 3600 "bash '$ROOT/phase4/scripts/10-build-payload.sh'" || record_fatal "payload build failed"
else
  echo "reusing phase4/out/engine payload" | tee -a "$MAINLOG"
fi
MFV=$(python3 -c 'import json,sys;m=json.load(open(sys.argv[1]));print("payloadVersion=%s opencode=%s@%s bun=%s git=%s rg=%s sha256=%s files=%d"%(m["payloadVersion"],m["opencodeVersion"],m["opencodeCommit"][:12],m["bunVersion"],m["gitVersion"],m["rgVersion"],m["payloadSha256"][:16],len(m["files"])))' "$ROOT/phase4/out/engine/assets/runtime-manifest.json")
note_gate "P10-PAYLOAD PASS: $MFV"
( cd "$ROOT/phase4/out/engine" && tar czf "$REL/runtime-payload-engine.tar.gz" assets jniLibs 2>/dev/null ) || true
cp "$ROOT/phase4/out/engine/assets/runtime-manifest.json" "$REL/" 2>/dev/null || true

step "4/9 fresh emulator at a phone-sized screen (1080x1920 @ 420dpi)"
if [ -e /dev/kvm ]; then sudo chmod 666 /dev/kvm 2>/dev/null || true; echo "KVM: $(ls -la /dev/kvm 2>&1)"; else echo "KVM_MISSING"; fi
if [ ! -x "$SDK/emulator/emulator" ] || [ ! -d "$SDK/system-images" ]; then
  run_c 1500 "bash '$ROOT/phase4/scripts/50-install-sdk.sh'" \
    || echo "warn: SDK installer reported failure; the probe below decides" | tee -a "$MAINLOG"
fi
IMAGE_DIR=""; BEST=""
for d in $(find "$SDK/system-images" -mindepth 3 -maxdepth 3 -type d 2>/dev/null | sort -r); do
  rel="${d#"$SDK/system-images/"}"
  api="${rel%%/*}"; rest="${rel#*/}"; tag="${rest%%/*}"; abi="${rest#*/}"
  case "$api" in android-3[0-9]|android-4[0-9]) ;; *) continue;; esac
  [ "$abi" != "x86_64" ] && continue
  s=0; case "$tag" in google_apis) s=3;; default) s=2;; *) s=1;; esac
  key="$api-$s"
  if [ -z "$BEST" ] || [ "$key" \> "$BEST" ]; then BEST="$key"; IMAGE_DIR="$rel"; fi
done
[ -z "$IMAGE_DIR" ] && record_fatal "no usable x86_64 system image under $SDK/system-images"
API="${IMAGE_DIR%%/*}"; REST="${IMAGE_DIR#*/}"; TAG="${REST%%/*}"; ABI="${REST#*/}"
export ANDROID_AVD_HOME="$SDK/avd"; mkdir -p "$ANDROID_AVD_HOME"
AVDM="$(find "$SDK" -name avdmanager -type f | head -1)"
echo "system image: $API/$TAG/$ABI" | tee -a "$MAINLOG"
# A NEW AVD every run, and an explicit phone-sized skin: Phase 10's screenshots
# have to satisfy Play's rules (>=320 px per side, at most 2:1), and the default
# avdmanager skin is 320x640 - i.e. exactly at the floor and 1:2.0, which is an
# asset nobody wants in a store listing.
"$AVDM" delete avd -n phase10 >/dev/null 2>&1 || true
rm -rf "$ANDROID_AVD_HOME/phase10.avd" "$ANDROID_AVD_HOME/phase10.ini" 2>/dev/null || true
echo no | "$AVDM" create avd -n phase10 -k "system-images;$API;$TAG;$ABI" --force 2>&1 | tail -3 | tee -a "$MAINLOG"
cp "$ROOT/phase4/scripts/02-boot-emulator.sh" "$OUT/boot-emulator.sh"
sed -i "s|-avd gates|-avd phase10 -skin 1080x1920 -dpi-device 420|; s|-log '[^']*'|-log '$OUT/emulator.log'|; s|-no-snapshot|-no-snapshot -wipe-data|" "$OUT/boot-emulator.sh"
run_c 1800 "bash '$OUT/boot-emulator.sh'" || record_fatal "emulator did not boot"
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
adb shell svc power stayon true >/dev/null 2>&1 || true
{ echo "device: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "android_release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "screen=$(adb shell wm size 2>/dev/null | tr -d '\r') density=$(adb shell wm density 2>/dev/null | tr -d '\r')"
  echo "avd=phase10 (created fresh this run, -wipe-data -no-snapshot, 1080x1920 skin)"
} > "$EV/device-facts.txt" 2>&1
cat "$EV/device-facts.txt" | tee -a "$MAINLOG"
note_gate "P10-DEVICE PASS: fresh AVD on $(sed -n 's/^android_release=//p' "$EV/device-facts.txt" | head -1) ($(sed -n 's/^screen=//p' "$EV/device-facts.txt" | head -1))"

step "5/9 debug APKs + Phase 6 UI gates (F1-F4, U1-U8) - did the polish break anything?"
run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace" \
  || record_fatal "debug APK build failed"
P6RC=0
run_c 5400 "bash '$ROOT/phase6/scripts/20-ui-gates.sh'" || P6RC=1
echo "phase6 UI gates rc=$P6RC" | tee -a "$MAINLOG"
P6SUM="$ROOT/phase6/out/evidence/GATES_SUMMARY.txt"
if [ -f "$P6SUM" ]; then
  grep -ahE '^P6_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$ROOT/phase6/out/evidence/p6-ui-lines.txt" 2>/dev/null | cut -c1-300 >> "$FINAL"
  grep -a '^ui_gates_pass=' "$P6SUM" | sed 's/^/P6-UI totals: /' >> "$FINAL"
else
  note_gate "P6-UI FAIL: the Phase 6 driver produced no summary (rc=$P6RC)"
fi

step "5b/9 workspace location + visibility (Phase 10 continuation: files must not be a black box)"
# Phase 7's isolation class (W1-W3) re-run against the NEW project root, plus the
# new W4 gate (root is outside /data/data) and the host-side check that a non-root
# `adb shell` can actually list and read what the OpenCode server wrote there. The
# storage move is a Phase 7 guarantee's blast radius, so Phase 7's gates are part
# of this stage rather than a footnote.
WSRC=0
# Own add-only lines file (stage 6 truncates p10-lines.txt; see the note below).
# Removed first so a skipped stage can never be read as a previous run's PASS.
rm -f "$EV/p10-workspace-lines.txt"
run_c 3600 "bash '$DIR/scripts/93-workspace-gates.sh' --out '$OUT/workspace'" || WSRC=1
cp "$OUT/workspace/workspace-gates.log" "$EV/workspace-gates.log" 2>/dev/null || true
cp "$OUT/workspace/visibility/workspace-visibility.log" "$EV/workspace-visibility.log" 2>/dev/null || true
# Renamed into the P10_ namespace so the phase-10 verdict fold (which counts
# ^P10_[A-Z0-9_]+) sees them: a Phase 7 gate re-run on the new root is a Phase 10
# verdict, and an invisible PASS would be worse than no gate at all (run #6).
#
# Written to their OWN lines file, not p10-lines.txt: stage 6 (the smoke build)
# truncates p10-lines.txt when it starts, so anything appended here before that
# stage silently disappears - which is exactly what happened on the first run of
# this stage (the verdicts were in workspace-gates.log and in the job log, and
# absent from GATES_SUMMARY.txt). The summary step folds this file too.
grep -ahE '^(P7_W[0-9]_[A-Z_]+|P10D_VISIBILITY_[A-Z_]+) (PASS|FAIL|SKIP)' "$OUT/workspace/workspace-gates.log" 2>/dev/null \
  | sed -e 's/^P7_/P10_WS_/' -e 's/^P10D_VISIBILITY_/P10_WS_VISIBILITY_/' | cut -c1-400 >> "$EV/p10-workspace-lines.txt"
[ "${WSRC:-0}" = 0 ] || note_gate "P10-WORKSPACE note: driver rc=$WSRC (the verdict lines above are the record)"

step "6/9 release-shaped SMOKE build (release applicationId + packaging, debug-key signed)"
SMOKERC=0
if [ "${P10_SKIP_SMOKE:-0}" = "1" ]; then
  note_gate "P10-SMOKE SKIP: P10_SKIP_SMOKE=1 (iteration knob; a verdict run must not set it)"
else
  run_c 3600 "bash '$DIR/scripts/50-smoke-gates.sh'" || SMOKERC=1
fi

step "7/9 store screenshots from the real app on a phone-sized screen"
if [ "${P10_SKIP_SHOTS:-0}" = "1" ]; then
  note_gate "P10-SHOTS SKIP: P10_SKIP_SHOTS=1 (iteration knob)"
else
  run_c 2400 "bash '$DIR/scripts/70-device-screenshots.sh' --out '$SHOTS'" || true
  SHOTRC=0
  run_c 300 "bash '$DIR/scripts/60-store-assets.sh' --from '$SHOTS' --out '$ROOT/docs/store/screenshots' > '$EV/store-assets.log' 2>&1; cat '$EV/store-assets.log'" || SHOTRC=1
  # The item on the submission checklist (docs/STORE-LISTING.md), now a gate: the
  # listing needs the 512 icon, the 1024x500 feature graphic and at least two
  # screenshots at a real screen size. Checked here, after the copy, so "are the
  # listing assets Play-ready" is a named verdict in GATES_SUMMARY.txt instead of an
  # operator's judgement call.
  if python3 "$DIR/scripts/check-release-invariants.py" "$ROOT" --require-store-assets > "$EV/store-assets-strict.txt" 2>&1; then
    echo "P10_STORE_ASSETS PASS :: 512 icon + 1024x500 feature graphic + $(ls -1 "$ROOT"/docs/store/screenshots/*.png 2>/dev/null | wc -l | tr -d ' ') screenshots at a real screen size" >> "$EV/p10-lines.txt"
  else
    echo "P10_STORE_ASSETS FAIL :: $(grep -a '^FAIL' "$EV/store-assets-strict.txt" | head -2 | tr '\n' ';' | cut -c1-260)" >> "$EV/p10-lines.txt"
  fi
fi

step "8/9 release APK + AAB (CI produces them UNSIGNED) + byte-level inspection"
run_c 3000 "bash '$DIR/scripts/40-release-verify.sh'" || note_gate "P10-RELEASE FAIL: see the log"

step "9/9 Phase 9 gates on the same device (provider selection, plugin seed, version, lock)"
if [ "${P10_SKIP_P9GATES:-0}" = "1" ]; then
  note_gate "P9-GATES SKIP: P10_SKIP_P9GATES=1 (iteration knob)"
else
  # Run-#6 finding, root-caused from p9-provsel-instrument.log + p9-harness-
  # export.log: every P9 failure was an HTTP 401 or "exported credential did
  # not authenticate the live server" - the debug client was authenticating
  # against a FOREIGN server. Stages 6/7 leave the SMOKE app installed and
  # running, and its runtime server binds the same fixed loopback port (4111,
  # RuntimeEnv.SERVER_PORT) the debug app's supervisor must bind; the debug
  # server then cannot start, and everything the P9 driver does over :4111
  # reaches the smoke app's server with the wrong Keystore-held password.
  # The P9 gates must run against the DEBUG build this pipeline produced, so
  # the smoke identity is force-stopped and uninstalled first (its androidTest
  # package with it - a released app is never installed in CI, so uninstalling
  # the id cannot touch one).
  echo "p9 preamble: removing the smoke build so port 4111 belongs to the debug runtime again" | tee -a "$MAINLOG"
  adb shell am force-stop io.github.mcyber12.opencode >/dev/null 2>&1 || true
  adb shell am force-stop io.github.mcyber12.opencode.debug >/dev/null 2>&1 || true
  adb uninstall io.github.mcyber12.opencode.test >/dev/null 2>&1 || true
  adb uninstall io.github.mcyber12.opencode >/dev/null 2>&1 || true
  sleep 3
  P9RC=0
  bash "$ROOT/phase9/scripts/20-gates.sh" >> "$MAINLOG" 2>&1 || P9RC=$?
  grep -ahE '^P9_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$ROOT/phase9/out/evidence/p9-lines.txt" 2>/dev/null | cut -c1-300 >> "$FINAL"
  [ "$P9RC" = 0 ] || note_gate "P9-GATES note: driver rc=$P9RC (verdicts above are the record)"
fi

step "summary"
# Every Phase 10 verdict, by name, into the one file that decides the job: the
# smoke stage and the release stage each keep their own lines file (they run in
# subshells), so they are folded in here rather than trusted to be echoed.
{ grep -ahE '^P10_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$EV/p10-lines.txt" 2>/dev/null
  grep -ahE '^P10_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$EV/p10-release-lines.txt" 2>/dev/null
  grep -ahE '^P10_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$EV/p10-workspace-lines.txt" 2>/dev/null
  grep -ahE '^P10_SMOKE_UI_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$EV/p10-lines.txt" 2>/dev/null
  grep -ahE '^STORE-ASSETS (PASS|FAIL)' "$EV/store-assets.log" 2>/dev/null
  grep -ahE '^P10_SHOTS (PASS|FAIL)' "$EV/p10-screenshots.log" 2>/dev/null
} | sed 's/[[:space:]]*$//' | sort -u | cut -c1-300 >> "$FINAL"
collect_evidence
P6F=$(sed -n 's/^ui_gates_pass=[0-9]* ui_gates_fail=\([0-9]*\).*/\1/p' "$P6SUM" 2>/dev/null | head -1)
P10F=$(grep -acE '^P10_[A-Z0-9_]+ FAIL' "$FINAL")
P9F=$(grep -acE '^P9_[A-Z0-9_]+ FAIL' "$FINAL")
{
  echo "P10_SUMMARY $(date -u +%FT%TZ)"
  grep -ahE '^P10_(SMOKE|RELEASE)_SUMMARY' "$EV/p10-lines.txt" "$EV/p10-release-lines.txt" 2>/dev/null | sort -u
  echo "phase6_ui_fails=${P6F:-n/a} phase10_gate_fails=$P10F phase9_gate_fails=$P9F"
  echo "NOTE: PASS lines above are real gate executions on the emulator/device. Unit and"
  echo "      compile lines are NOT runtime evidence. UNSIGNED release artifacts are"
  echo "      expected in CI: signing is the human step (docs/RELEASE.md)."
} >> "$FINAL"
cat "$FINAL" | tee -a "$MAINLOG"
FINAL_RC=0
[ "${P6F:-0}" = "0" ] || FINAL_RC=1
[ "$P10F" = "0" ] || FINAL_RC=1
[ "${SMOKERC:-0}" = "0" ] || FINAL_RC=1
[ "${SHOTRC:-0}" = "0" ] || FINAL_RC=1
[ "$P9F" = "0" ] || FINAL_RC=1
[ "${WSRC:-0}" = "0" ] || FINAL_RC=1
echo "=== PHASE 10 END $(date -u +%FT%TZ) rc=$FINAL_RC ===" | tee -a "$MAINLOG"
push_evidence
exit "$FINAL_RC"
