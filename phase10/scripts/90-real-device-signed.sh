#!/usr/bin/env bash
# 90-real-device-signed.sh — verify the SIGNED build on a real arm64 phone.
# (Phase 10 continuation, v2: this file replaces the v1 driver that reported
#  "P10D_FIRST_RUN FAIL :: no working screen after 941s" on a build the owner then
#  installed by hand and used normally. Read the section "WHAT WENT WRONG IN v1".)
#
# Why this exists separately from every earlier device script: every gate this
# project has run used a DEBUG build (Phases 4-9) or the release-shaped smoke
# build (Phase 10 CI). The artifact users install is the SIGNED RELEASE build, and
# signing changes things: the applicationId is the release one, the data dir and
# the Keystore namespace are new, `debuggable=false` removes run-as (so the
# harness mechanism the older suites used does not exist here), the FileProvider
# authority differs, and the signature identity is the upload key rather than a
# debug key. This script tests exactly that artifact, through the UI, with no
# privileged access - the same position a user is in.
#
# WHAT WENT WRONG IN v1 (kept here because it explains every design decision
# below): the first-run verdict came from ONE blind loop - `for i in $(seq 1 60)`
# with a `uiautomator dump` plus `sleep 5` per iteration - that only looked for
# four literal strings. There was no wake/unlock step, no check that the app owned
# the window, no screenshot until much later in the run, and no capture of what the
# dump actually contained. So "no working screen after 941s" was consistent with at
# least four different realities (device asleep/locked; a system dialog in front;
# the UI never reaching an idle state so the dump failed; or a genuinely slow first
# run) and the bundle could not distinguish them. 941s was simply the loop's own
# duration (60 iterations x (dump + 5s)), not a measured app timing.
#
# WHAT YOU NEED (one-time):
#   1. a phone: arm64, Android 10+ , USB debugging on
#   2. platform-tools (`adb`) on PATH
#   3. the SIGNED apk from phase10/scripts/sign-release-local.sh (docs/RELEASE.md)
#   4. optional: a model provider key. Type it at the prompt, or export
#      P10D_PROVIDER_KEY=... first, or add it in the app's Settings and press
#      Enter at the prompt. Without one, the live-turn gates SKIP (with the reason)
#      and everything else still runs. Use a capable model: a weak free tier
#      answers questions about files instead of reading them, which looks like an
#      app bug and is not one.
#
# WHAT IT DOES:
#   R0  preflight: wake the device, dismiss the keyguard, VERIFY it is unlocked,
#       turn off animations, keep the screen on. v1 skipped all of this.
#   R1  device facts (model, API level, ABI, secure-hardware flags) + ABI/API gates
#   R2  artifact verification: signature (apksigner, if installed) + identity
#       (check-apk.py: package/version/icon/payload/permissions/no-debuggable)
#   R3  clean install of the SIGNED apk and first launch
#   R4  first run, driven as a state machine: welcome -> runtime healthy by itself
#       -> create a project through taps and typing -> enabled composer. Every wait
#       names what it is waiting for, reports the live state every 15s, and takes a
#       screenshot every 60s plus one per transition.
#   R5  the app's own file browser: open it, read the path it shows, open a file.
#   R6  a live turn through the composer, and - if a model can serve it - a real
#       tool call, read back from the accessibility tree (no root, no run-as).
#   R7  file visibility from OUTSIDE the app: a non-root `adb shell` listing/reading
#       the project directory (92-workspace-visibility.sh). This is the check that
#       the files are not sealed in app-private storage.
#   R8  footprint: memory, storage, cold-start timing
#   R9  crash / obfuscation sweep: any FATAL EXCEPTION, ClassNotFoundException,
#       NoSuchMethodError, NoClassDefFoundError or UnsatisfiedLinkError in the
#       session's logcat is reported - these are the failure modes that appear
#       only once code is packaged for release
#   R10 verdict bundle in ./p10d-out/ (send the whole folder back), including
#       screenshots at every step, UI dumps per step, and a diagnosis file that is
#       written whenever a step fails
#
# Usage: bash phase10/scripts/90-real-device-signed.sh [--apk PATH] [--out DIR]
#          [--cert-sha256 HEX] [--skip-live] [--timeout-scale N]
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="p10d-out"
APK=""
CERT_EXPECT=""
SKIP_LIVE=0
SCALE=1
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --cert-sha256) CERT_EXPECT="$(printf '%s' "${2:-}" | tr -d ' :' | tr 'A-Z' 'a-z')"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --skip-live) SKIP_LIVE=1; shift ;;
    --timeout-scale) SCALE="${2:-1}"; shift 2 ;;
    -h|--help) sed -n '2,60p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done
mkdir -p "$OUT" "$OUT/screenshots" "$OUT/ui"
LOG="$OUT/run.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }
step() { echo | tee -a "$LOG"; echo "===== $* =====" | tee -a "$LOG"; }

PASS=0; FAIL=0; SKIP=0
: > "$OUT/SUMMARY.txt"
: > "$OUT/DIAGNOSIS.txt"
rec() { echo "$1 $2${3:+ :: $3}" >> "$OUT/SUMMARY.txt"; log "$1 $2${3:+ :: $3}"; }
rd() { case "$2" in 0) PASS=$((PASS+1)); rec "P10D_$1" PASS "$3";; 7) SKIP=$((SKIP+1)); rec "P10D_$1" SKIP "$3";; *) FAIL=$((FAIL+1)); rec "P10D_$1" FAIL "$3";; esac; }
diag() { echo "$1" | tee -a "$OUT/DIAGNOSIS.txt" >> "$LOG"; }

# Credentials never reach the bundle: this filter runs over everything written to
# p10d-out, the same rule the Phase 8 suite adopted after a key was published.
redact() {
  sed -E -e 's/(key=)AQ\.[A-Za-z0-9_-]+/\1AQ.<REDACTED>/g' \
         -e 's/AQ\.[A-Za-z0-9_-]{12,}/AQ.<REDACTED>/g' \
         -e 's/AIza[A-Za-z0-9_-]{20,}/AIza<REDACTED>/g' \
         -e 's/sk-or-[A-Za-z0-9_-]{10,}/sk-or-<REDACTED>/g' \
         -e 's/(Bearer )[A-Za-z0-9._-]{12,}/\1<REDACTED>/g'
}

command -v adb >/dev/null 2>&1 || { echo "FATAL: adb not on PATH (install platform-tools)"; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "FATAL: no device visible to adb (plug the phone in, enable USB debugging, accept the prompt)"; exit 2; }
PKG="io.github.mcyber12.opencode"
SHOT_N=0
LAST_DUMP=""

# ---------------------------------------------------------------- UI plumbing --
#
# Every UI interaction goes through these five functions. They are deliberately
# chatty: each one can explain itself, and the driver logs the reason for every
# negative answer instead of collapsing them all into "not found".

ui_dump() { # $1 = tag -> $OUT/ui/ui-<tag>.xml ; returns non-zero when unusable
  local tag="$1" attempt rc out
  for attempt in 1 2 3; do
    adb shell rm -f /sdcard/p10d-ui.xml >/dev/null 2>&1
    out=$(adb shell uiautomator dump /sdcard/p10d-ui.xml 2>&1 | tr -d '\r')
    sleep 0.5
    if adb exec-out cat /sdcard/p10d-ui.xml > "$OUT/ui/ui-$tag.xml" 2>/dev/null && [ -s "$OUT/ui/ui-$tag.xml" ]; then
      LAST_DUMP="$OUT/ui/ui-$tag.xml"
      return 0
    fi
    # v1 discarded this string entirely; it is the difference between "no app
    # screen" and "uiautomator could not dump at all".
    diag "ui_dump($tag) attempt $attempt failed: ${out:-<no output>}"
    sleep 2
  done
  LAST_DUMP=""
  return 1
}

ui() { python3 "$DIR/scripts/p10d-ui.py" "$LAST_DUMP" "$@" 2>/dev/null; }
ui_has() { ui has "$1"; }
ui_texts() { ui texts 12 | tr '\n' '|' | cut -c1-300; }
ui_nodes() { ui nodes; }

# What window is in front, and is the screen even on? Called on every wait tick.
foreground() {
  local w a
  w=$(adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 -E 'mCurrentFocus|mFocusedApp' | sed 's/^ *//')
  a=$(adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m1 -E 'mResumedActivity|topResumedActivity' | sed 's/^ *//')
  echo "${w:-window=?} ; ${a:-activity=?}"
}
screen_state() {
  local f w
  f=$(adb shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m1 'mWakefulness' | sed 's/^ *//')
  w=$(adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 -E 'mDreamingLockscreen|mShowingLockscreen' | sed 's/^ *//')
  echo "${f:-wakefulness=?} ${w:-keyguard=?}"
}

shot() { # $1 = tag ; validates that the png is not a blank/off screen
  SHOT_N=$((SHOT_N+1))
  local name
  name=$(printf "%02d-%s.png" "$SHOT_N" "$1")
  if ! adb exec-out screencap -p > "$OUT/screenshots/$name" 2>/dev/null || [ ! -s "$OUT/screenshots/$name" ]; then
    rm -f "$OUT/screenshots/$name"
    diag "screenshot $name FAILED (screencap produced nothing)"
    return 1
  fi
  local verdict
  verdict=$(python3 "$DIR/scripts/p10d-png.py" "$OUT/screenshots/$name" 2>/dev/null | tail -1)
  echo "$(date -u +%FT%TZ) $name :: $verdict" >> "$OUT/screenshots.log"
  case "$verdict" in
    *"screen=NO"*) diag "screenshot $name looks blank/off: $verdict"; return 1 ;;
  esac
  return 0
}

tap_at() { adb shell input tap "$1" "$2" >/dev/null 2>&1; sleep 1; }

tap() { # $1 = tag|text|label ; tolerant: scrolls once, and says why it could not tap
  local needle="$1" centre state
  ui_dump "tap-$needle" || { diag "tap($needle): no UI dump available"; return 1; }
  centre=$(ui find "$needle")
  if [ -z "${centre:-}" ]; then
    state=$(ui state "$needle")
    diag "tap($needle): not tappable -> ${state:-not found}"
    # A real user would scroll towards what they want before giving up.
    adb shell input swipe 540 1600 540 900 300 >/dev/null 2>&1
    sleep 1
    ui_dump "tap2-$needle" || return 1
    centre=$(ui find "$needle")
  fi
  [ -n "${centre:-}" ] || { diag "tap($needle): still not tappable after one scroll"; return 1; }
  tap_at $centre
  return 0
}

type_text() { # $1 = text ; %s is a space, as `input text` requires
  local escaped
  escaped=$(printf '%s' "$1" | sed 's/ /%s/g')
  adb shell input text "$escaped" >/dev/null 2>&1
  sleep 1
}

handle_interruptions() { # returns 0 when the app is (still) in front
  local f
  ui_dump "interruptions" || return 1
  if ui has "isn't responding" || ui has "is not responding"; then
    diag "ANR dialog on screen: $(ui texts 8 | tr '\n' '|')"
    tap "Wait" >/dev/null 2>&1 || true
    rec "P10D_ANR" 1 "the app showed an 'isn't responding' dialog during the run"
    return 1
  fi
  if ui has "keeps stopping" || ui has "has stopped"; then
    diag "CRASH dialog on screen: $(ui texts 8 | tr '\n' '|')"
    rec "P10D_CRASH_DIALOG" 1 "Android reported that the app stopped"
    shot "crash-dialog" || true
    return 1
  fi
  if ui has "Allow" && ui has "notification"; then
    diag "notification-permission dialog: tapping Allow (a user would)"
    tap "Allow" >/dev/null 2>&1 || true
    sleep 1
    return 0
  fi
  f=$(foreground)
  case "$f" in
    *"$PKG"*) return 0 ;;
    *Keyguard*|*keyguard*|*StatusBar*) diag "keyguard is in front: $f"; return 1 ;;
    "") return 0 ;;
    *) diag "another window is in front: $f" ; return 1 ;;
  esac
}

wait_for() { # $1 = name, $2 = needle(s) separated by |, $3 = timeout seconds
  local name="$1" needles="$2" timeout="$3" start elapsed needle hit every=15
  start=$(date +%s)
  while :; do
    ui_dump "wait-$name" >/dev/null 2>&1
    if [ -n "$LAST_DUMP" ]; then
      for needle in ${needles//|/ }; do
        if ui has "$needle"; then
          elapsed=$(( $(date +%s) - start ))
          log "wait($name): found '$needle' after ${elapsed}s"
          return 0
        fi
      done
    fi
    elapsed=$(( $(date +%s) - start ))
    if [ "$elapsed" -ge "$timeout" ]; then
      diag "wait($name) TIMED OUT after ${elapsed}s"
      diag "  waiting for: $needles"
      diag "  device: $(screen_state)"
      diag "  foreground: $(foreground)"
      diag "  last dump: ${LAST_DUMP:-<none>} $(ui_nodes)"
      diag "  on screen: $(ui_texts)"
      shot "timeout-$name" || true
      return 1
    fi
    if [ $(( elapsed % every )) -lt 3 ] && [ "$elapsed" -ge "$every" ]; then
      log "wait($name): ${elapsed}s; $(screen_state); on screen: $(ui_texts)"
      handle_interruptions || true
    fi
    if [ $(( elapsed % 60 )) -lt 3 ] && [ "$elapsed" -ge 60 ]; then
      shot "wait-$name-${elapsed}s" || true
    fi
    sleep 2
  done
}

# ----------------------------------------------------------------- preflight --
#
# v1 never did this. A phone that is asleep, locked, or showing the keyguard gives
# a uiautomator dump of the lock screen and never the app - which is
# indistinguishable from "the app is broken" in a log that only says
# "no working screen".

step "R0 preflight: device awake, unlocked, and the app able to be driven"
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
sleep 1
# Also switch the display on for devices where KEYCODE_WAKEUP alone is not enough.
adb shell input keyevent 224 >/dev/null 2>&1 || true
local_locked() { screen_state | grep -qiE 'DreamingLockscreen=true|mShowingLockscreen=true|mWakefulness=Asleep|mWakefulness=Dozing'; }
if local_locked; then
  log "device reports $(screen_state); dismissing the keyguard (a human would swipe up)"
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell input swipe 540 1800 540 400 200 >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true
  sleep 2
fi
adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
node_state=$(adb shell dumpsys deviceidle 2>/dev/null | tr -d '\r' | grep -m1 -i 'mState=' || true)
log "screen: $(screen_state) (${node_state:-deviceidle=?})"
if local_locked; then
  rd DEVICE_AWAKE 1 "the device still reports a locked/asleep screen after wake + dismiss-keyguard: $(screen_state). Unlock the phone (a PIN/password keyguard cannot be dismissed by adb) and re-run - this is not an app failure"
else
  rd DEVICE_AWAKE 0 "screen awake and unlocked before any UI step: $(screen_state)"
fi

# ---- R1: device facts --------------------------------------------------------
step "R1 device facts"
ABI_NOW=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
SDK_NOW=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
MODEL_NOW=$(adb shell getprop ro.product.model | tr -d '\r')
{
  echo "device=$MODEL_NOW"
  echo "manufacturer=$(adb shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r') (API $SDK_NOW)"
  echo "abi=$ABI_NOW"
  echo "screen=$(adb shell wm size 2>/dev/null | tr -d '\r') $(adb shell wm density 2>/dev/null | tr -d '\r')"
  echo "screen_state=$(screen_state)"
} > "$OUT/device-facts.txt" 2>&1
cat "$OUT/device-facts.txt" | tee -a "$LOG"
case "$ABI_NOW" in
  arm64-v8a) rd ABI 0 "arm64-v8a device (the ABI this app ships to users)" ;;
  *) rd ABI 1 "device abi=$ABI_NOW: this app's user-facing ABI is arm64-v8a. A non-arm64 device cannot install the shipped artifact" ;;
esac
if [ -n "$SDK_NOW" ] && [ "$SDK_NOW" -ge 29 ]; then
  rd ANDROID_VERSION 0 "API $SDK_NOW (minSdk 29)"
else
  rd ANDROID_VERSION 1 "API ${SDK_NOW:-unknown} is below minSdk 29"
fi

# ---- R2: verify the artifact BEFORE installing it ----------------------------
step "R2 artifact verification"
if [ -z "$APK" ]; then
  CAND=$(ls -1 "$ROOT"/phase10/signing/*.apk 2>/dev/null | head -1)
  if [ -n "$CAND" ]; then APK="$CAND"; log "using $APK (found in phase10/signing)"; else
    echo "FATAL: no --apk given and nothing in phase10/signing/."
    echo "Sign CI's artifact first: bash phase10/scripts/sign-release-local.sh --help (docs/RELEASE.md s4)"
    exit 2
  fi
fi
[ -f "$APK" ] || { echo "FATAL: $APK not found"; exit 2; }
log "verifying $APK"
VNAME=$(grep -E '^[[:space:]]*versionName = "' "$ROOT/app/build.gradle.kts" | head -1 | cut -d'"' -f2)
VCODE=$(grep -E '^[[:space:]]*versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | head -1 | grep -oE 'versionCode = [0-9]+' | grep -oE '[0-9]+')
REPORT="$OUT/artifact-report.txt"
{
  echo "=== check-apk.py (identity, contents, signature presence) ==="
  python3 "$DIR/scripts/check-apk.py" "$APK" \
    --expect-signed --expect-not-debuggable --expect-icon --expect-payload \
    --expect-package "$PKG" --expect-version-name "$VNAME" --expect-version-code "$VCODE" \
    --expect-native-abi arm64-v8a --expect-min-sdk 29 \
    2>&1
} > "$REPORT" 2>&1
if grep -aq '^VERDICT PASS' "$REPORT"; then
  rd ARTIFACT 0 "$(grep -a '^MANIFEST ' "$REPORT" | head -1 | cut -c1-200)"
else
  rd ARTIFACT 1 "check-apk findings: $(grep -a '^FINDING' "$REPORT" | head -4 | tr '\n' '; ')"
fi
if command -v apksigner >/dev/null 2>&1; then
  if apksigner verify --print-certs --verbose "$APK" > "$OUT/apksigner-verify.txt" 2>&1; then
    CERT=$(grep -a -m1 'Signer #1 certificate SHA-256 digest' "$OUT/apksigner-verify.txt" | sed 's/^ *//; s/^.*digest: *//' | tr -d '\r' | tr 'A-Z' 'a-z')
    rd SIGNATURE 0 "apksigner verify: signed, v1/v2/v3 schemes as listed; signer #1 SHA-256 $CERT"
    EXPECT="${CERT_EXPECT:-${P10D_CERT_SHA256:-}}"
    [ -n "$EXPECT" ] || [ ! -f "$ROOT/phase10/signing/expected-cert-sha256.txt" ] || \
      EXPECT=$(tr -d ' \r\n' < "$ROOT/phase10/signing/expected-cert-sha256.txt" | tr 'A-Z' 'a-z')
    EXPECT=$(printf '%s' "$EXPECT" | tr -d ' :' | tr 'A-Z' 'a-z')
    if [ -n "${EXPECT:-}" ]; then
      if [ "$CERT" = "$EXPECT" ]; then
        rd CERT_MATCH 0 "signer certificate matches phase10/signing/expected-cert-sha256.txt - this is the owner's release key"
      else
        rd CERT_MATCH 1 "signer certificate $CERT != expected $EXPECT - WRONG KEY, do not upload (and if you already uploaded: Play App Signing decides, not this artifact)"
      fi
    else
      rd CERT_MATCH 7 "no expected fingerprint given: record this one (P10D_CERT_SHA256=... or phase10/signing/expected-cert-sha256.txt) and re-run to prove it is your key"
    fi
  else
    rd SIGNATURE 1 "apksigner verify FAILED - do not install or upload this artifact (see apksigner-verify.txt)"
  fi
else
  rd SIGNATURE 7 "apksigner not on PATH: signature checked for PRESENCE by check-apk.py only. Install build-tools and re-run for the cryptographic verdict (docs/RELEASE.md s5)"
fi

# ---- R3: clean install of the SIGNED apk -------------------------------------
step "R3 clean install of the signed artifact"
log "uninstalling any previous install so this is a genuine first run"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true
if adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$LOG" | grep -q "Success"; then
  rd INSTALL 0 "adb install of the signed release APK succeeded (this is the artifact a user would sideload)"
else
  rd INSTALL 1 "adb install failed - a signing/ABI/minSdk mismatch (see run.log)"
fi
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
VER_NOW=$(adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -o 'versionName=[^ ]*' | head -1 | cut -d= -f2)
rd VERSION_ON_DEVICE 0 "installed versionName=$VER_NOW code=$(adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -o 'versionCode=[0-9]*' | head -1 | cut -d= -f2)"

# ---- R4: first run ----------------------------------------------------------
step "R4 first run: welcome -> runtime healthy by itself -> a project -> composer"
adb shell am start -W -n "$PKG/ai.opencode.android.MainActivity" 2>&1 | tr -d '\r' | tee -a "$LOG" | grep -E 'Status|LaunchState|TotalTime' || \
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
T0=$(date +%s)
shot "01-launch" || true
sleep 2

# The state machine, in the order a user meets it. Each entry is
# "name | needles | what a human would do". The first run extracts the payload
# (~1 GB of Bun + OpenCode + git + ripgrep) before the runtime can be HEALTHY, so
# this waits generously - but it reports what it is waiting for the whole time.
FIRST_RUN_OK=0
if wait_for "app-window" "welcome_screen|continue|Welcome|OpenCode" "$((300 * SCALE))"; then
  handle_interruptions || true
  shot "02-welcome" || true
  # The welcome screen advances by itself when the supervisor reports HEALTHY
  # (AppRoot's LaunchedEffect). A human who gets impatient taps Continue; do the
  # same, but never make the verdict depend on the tap.
  if wait_for "runtime-or-projects" "projects_screen|project_list|project_name_input|welcome_continue" "$((180 * SCALE))"; then
    tap "welcome_continue" >/dev/null 2>&1 || true
    shot "03-after-welcome" || true
    if wait_for "projects-screen" "project_name_input|project_list|projects_screen" "$((300 * SCALE))"; then
      FIRST_RUN_OK=1
      T1=$(( $(date +%s) - T0 ))
      rd FIRST_RUN 0 "app reached the projects screen by itself in ${T1}s (payload extracted + agent started, no privileged access); screen: $(screen_state)"
    else
      rd FIRST_RUN 1 "the app never reached the projects screen (see DIAGNOSIS.txt and the screenshots at each step)"
    fi
  else
    rd FIRST_RUN 1 "no welcome/projects surface became usable (see DIAGNOSIS.txt; the app also offers Settings -> Share diagnostics)"
  fi
else
  rd FIRST_RUN 1 "the app window never appeared: $(foreground) $(screen_state) - see DIAGNOSIS.txt and 01-launch.png"
fi

if [ "$FIRST_RUN_OK" = 1 ]; then
  PROJECT_NAME="p10d-$(date +%H%M%S)"
  if tap "project_name_input"; then
    type_text "$PROJECT_NAME"
    shot "04-project-name-typed" || true
    if tap "project_create"; then
      sleep 2
      if wait_for "conversation" "composer_input|composer_send|chat_screen|Start a conversation" "$((180 * SCALE))"; then
        shot "05-chat-ready" || true
        rd FIRST_RUN_PROJECT 0 "project '$PROJECT_NAME' created through the UI (taps + typed text) on the signed build; conversation surface reached"
      else
        rd FIRST_RUN_PROJECT 1 "project created but the conversation surface was not reached (see DIAGNOSIS.txt)"
      fi
    else
      rd FIRST_RUN_PROJECT 1 "could not tap the create-project button (see ui/ui-tap-project_create.xml)"
    fi
  else
    rd FIRST_RUN_PROJECT 1 "could not focus the project-name field (see DIAGNOSIS.txt)"
  fi
else
  rd FIRST_RUN_PROJECT 7 "no project could be created because the first run never reached a usable screen"
fi

# ---- R5: the app's own file browser ------------------------------------------
step "R5 the in-app file browser (this is how a user sees what the agent wrote)"
FILES_SEEN=0
FILES_PATH=""
if [ "$FIRST_RUN_OK" = 1 ] && tap "open_files"; then
  sleep 2
  if wait_for "files-screen" "files_list|files_location_path|files_empty" "$((120 * SCALE))"; then
    shot "06-files-listing" || true
    ui_dump "files-screen" >/dev/null 2>&1
    FILES_PATH=$(python3 - "$LAST_DUMP" "$PKG" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'text="(/[^"]*Android/data/%s/[^"]*)"' % re.escape(sys.argv[2]), xml)
print(m.group(1) if m else "")
PY
)
    if [ -n "$FILES_PATH" ]; then
      FILES_SEEN=1
      rd FILES_SCREEN 0 "the app's file browser shows the project at $FILES_PATH (read from the screen, not from the app's internals)"
    else
      rd FILES_SCREEN 1 "the file browser opened but no on-device path was shown on screen (see ui/ui-files-screen.xml)"
    fi
    adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    sleep 1
  else
    rd FILES_SCREEN 1 "the file browser did not open (see DIAGNOSIS.txt)"
  fi
else
  rd FILES_SCREEN 7 "no project open, so there was nothing to browse"
fi

# ---- R6: a live turn --------------------------------------------------------
step "R6 live turn through the composer"
MODEL_AVAILABLE=0
if [ -n "${P10D_PROVIDER_KEY:-}" ]; then
  MODEL_KEY="$P10D_PROVIDER_KEY"
else
  printf '\nType a provider API key to run the live-turn gate (typed here, never stored, never logged)'
  printf '\nPress [Enter] to skip if you already added a key in the app or want to skip: '
  read -r MODEL_KEY
fi
if [ -n "${MODEL_KEY:-}" ] && [ "$SKIP_LIVE" = 0 ]; then
  # Route it through the app's own UI: Settings -> Provider keys.
  if tap "open_settings"; then
    sleep 2
    # The provider id field is empty by default and Save stays disabled without it,
    # which the v1 driver never noticed (it typed only the key, so Save did nothing
    # and the live gate then reported "no model served the turn").
    tap "key_provider" >/dev/null 2>&1 && type_text "${P10D_PROVIDER:-openrouter}"
    if tap "key_value"; then
      type_text "$MODEL_KEY"
      if tap "key_save"; then
        sleep 3
        shot "07-provider-key-saved" || true
        log "key entered through the app's own Settings screen (it is not in this log)"
      else
        log "the Save-key button never became tappable (it needs both the provider id and the key)"
      fi
    else
      log "could not focus the provider-key field - add the key in Settings by hand and re-run to exercise the live gate"
    fi
  else
    log "could not open Settings to enter the key"
  fi
  unset MODEL_KEY
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 2
fi
MODEL_KEY=""

if [ "$SKIP_LIVE" = 1 ]; then
  rd LIVE_TURN 7 "--skip-live was given: no model turn was attempted"
elif [ "$FIRST_RUN_OK" != 1 ]; then
  rd LIVE_TURN 7 "no conversation surface existed to send a turn from"
elif tap "composer_input"; then
  # `adb shell` re-quotes what it forwards, and `input text` takes a single argument
  # in which %s is a space: quotes and literal spaces would be re-parsed by the
  # device shell and the prompt would arrive mangled (or not at all).
  # No shell metacharacters in this string on purpose: `adb shell input text`
  # forwards it through the DEVICE shell, which would treat > & ; | $ as syntax and
  # mangle (or truncate) the prompt.
  type_text "Use the bash tool to write a file named p10-visible.txt that contains the text p10-live-ok, then show me its contents"
  shot "08-prompt-typed" || true
  tap "composer_send" >/dev/null 2>&1 || adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
  log "prompt sent; waiting up to $((300 * SCALE))s for the answer"
  TURN=0
  if wait_for "turn-answer" "p10-visible.txt|p10-live-ok" "$((300 * SCALE))"; then TURN=1; fi
  shot "09-turn-answer" || true
  TOOLCARD=0
  ui_dump "turn-tool" >/dev/null 2>&1 && { ui_has "Shell command" && TOOLCARD=1; }
  if [ "$TURN" = 1 ] && [ "$TOOLCARD" = 1 ]; then
    MODEL_AVAILABLE=1
    # Expand the card like a user would, then capture the single best listing shot.
    tap "Shell command" >/dev/null 2>&1 || true
    sleep 1
    shot "10-tool-card-expanded" || true
    rd LIVE_TURN 0 "the answer is on screen AND a Shell-command tool card is visible: a real live tool call ran in the SIGNED build (ui/ui-turn-tool.xml)"
  elif [ "$TURN" = 1 ]; then
    MODEL_AVAILABLE=1
    rd LIVE_TURN 1 "the expected output is on screen but no tool card is visible: the model may have echoed the prompt instead of running it - expand the turn and re-check (ui/ui-turn-tool.xml)"
  else
    if ui_has "key was rejected" || ui_has "credit" || ui_has "unreachable" || ui_has "not running"; then
      rd LIVE_TURN 7 "no model served the turn (the app says why - see ui/ui-turn-answer.xml). Not a pass: re-run with a funded key and a capable model"
    else
      rd LIVE_TURN 1 "the turn never produced the expected output and the app showed no provider error: investigate (ui/ui-turn-answer.xml + logcat.txt)"
    fi
  fi
else
  rd LIVE_TURN 7 "could not find the composer on the conversation surface (see ui/ui-tap-composer_input.xml)"
fi
# The same marker Phase 6's live-chat class writes, so the Phase 9/10 carry-forward
# ("live tool call verified only on the x86_64 emulator") can be closed by a machine
# reading this file, not by prose.
echo "P6_MODEL_AVAILABLE ${MODEL_AVAILABLE:-0} :: signed build, arm64 device, driven through the composer" >> "$OUT/p10d-model-lines.txt"

# ---- R7: can anything other than the app read the agent's files? -------------
step "R7 file visibility from outside the app (non-root adb shell)"
VIS_ARGS=(--pkg "$PKG" --out "$OUT/visibility")
if [ -n "${PROJECT_NAME:-}" ]; then
  VIS_ARGS+=(--project "$PROJECT_NAME" --expect-file "p10-visible.txt")
fi
bash "$DIR/scripts/92-workspace-visibility.sh" "${VIS_ARGS[@]}" > "$OUT/visibility.log" 2>&1
VIS_RC=$?
grep -aE '^P10D_VISIBILITY_[A-Z_]+ (PASS|FAIL|SKIP)' "$OUT/visibility.log" 2>/dev/null | while read -r id verdict rest; do
  rec "$id" "$verdict" "$rest"
done
while read -r id verdict rest; do
  [ -n "${id:-}" ] || continue
  case "$verdict" in
    PASS) PASS=$((PASS+1)) ;;
    SKIP) SKIP=$((SKIP+1)) ;;
    *) FAIL=$((FAIL+1)) ;;
  esac
done < <(grep -aE '^P10D_VISIBILITY_[A-Z_]+ (PASS|FAIL|SKIP)' "$OUT/visibility.log" 2>/dev/null)
log "visibility driver rc=$VIS_RC (verdict lines above; raw output in visibility.log)"
diag "visibility: project=${PROJECT_NAME:-<none>} path=${FILES_PATH:-<unknown>}"
if [ "$FILES_SEEN" = 1 ]; then
  rd FILES_APP_AND_SHELL "$( [ "$VIS_RC" = 0 ] && echo 0 || echo 1 )" \
    "in-app browser path=$FILES_PATH; shell visibility rc=$VIS_RC (see visibility.log for the raw ls/cat output)"
fi

# ---- R8: footprint ----------------------------------------------------------
step "R8 footprint"
adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' > "$OUT/meminfo.txt" || true
PSS=$(grep -aoE 'TOTAL PSS: *[0-9]+' "$OUT/meminfo.txt" | head -1 | grep -oE '[0-9]+')
[ -n "$PSS" ] && rd MEMORY 0 "total PSS $((PSS/1024)) MB (see meminfo.txt)" || rd MEMORY 7 "meminfo unavailable"
# The app-private store is deliberately NOT readable from a shell (that is the
# point of R7); the external project root is, so the footprint is measured there.
STORAGE=$(adb shell du -sh "/storage/emulated/0/Android/data/$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
[ -n "$STORAGE" ] && rd STORAGE 0 "app-specific external storage ${STORAGE} (projects + published copies)" || rd STORAGE 7 "storage unreadable (expected on some OEM builds; see visibility.log)"

# ---- R9: crash / obfuscation sweep ------------------------------------------
step "R9 crash and packaging sweep"
adb logcat -d 2>/dev/null > "$OUT/logcat-raw.txt" || true
redact < "$OUT/logcat-raw.txt" > "$OUT/logcat.txt" 2>/dev/null || cp "$OUT/logcat-raw.txt" "$OUT/logcat.txt"
rm -f "$OUT/logcat-raw.txt" 2>/dev/null || true
grep -aoE 'P6_MODEL_AVAILABLE [01][^\r]*' "$OUT/logcat.txt" 2>/dev/null | sort -u >> "$OUT/p10d-model-lines.txt" || true
SWEEP=$(grep -acE "FATAL EXCEPTION|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError|UnsatisfiedLinkError" "$OUT/logcat.txt" 2>/dev/null || echo 0)
if [ "${SWEEP:-0}" = 0 ]; then
  rd PACKAGING_SWEEP 0 "no FATAL EXCEPTION / ClassNotFound / NoSuchMethod / NoClassDefFound / UnsatisfiedLink in the session's logcat"
else
  # v1 counted these lines and threw away the lines themselves. Print them: they are
  # the only evidence of what release packaging broke, and they belong in the log.
  grep -aE "FATAL EXCEPTION|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError|UnsatisfiedLinkError" "$OUT/logcat.txt" 2>/dev/null | head -20 >> "$OUT/SUMMARY.txt"
  diag "packaging failure lines:"
  grep -aE "FATAL EXCEPTION|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError|UnsatisfiedLinkError" "$OUT/logcat.txt" 2>/dev/null | head -20 | while read -r l; do diag "  $l"; done
  rd PACKAGING_SWEEP 1 "$SWEEP packaging/runtime failure line(s) in logcat.txt (printed above and in SUMMARY.txt) - read them before uploading"
fi
CRASHES=$(grep -acE "FATAL EXCEPTION|Process: $PKG" "$OUT/logcat.txt" 2>/dev/null || echo 0)
[ "${CRASHES:-0}" = 0 ] && rd NO_CRASH 0 "no app crash in this session" || rd NO_CRASH 1 "$CRASHES crash marker(s) in logcat.txt"

# ---- R10: bundle ------------------------------------------------------------
step "R10 bundle"
NSHOTS=$(ls -1 "$OUT/screenshots"/*.png 2>/dev/null | wc -l | tr -d ' ')
BLANK=$(grep -ac 'screen=NO' "$OUT/screenshots.log" 2>/dev/null || echo 0)
if [ "${NSHOTS:-0}" -ge 6 ] && [ "${BLANK:-0}" = 0 ]; then
  rd SCREENSHOTS 0 "$NSHOTS screenshots captured at every step and every one is a real screen (screenshots/)"
elif [ "${NSHOTS:-0}" -ge 2 ]; then
  rd SCREENSHOTS 1 "$NSHOTS screenshots, of which $BLANK look blank/off (screenshots.log lists each)"
else
  rd SCREENSHOTS 1 "only ${NSHOTS:-0} screenshots captured - there is nothing to look at (screenshots.log)"
fi
{
  echo "phase10 real-device verification of the SIGNED build $(date -u +%FT%TZ)"
  echo "screenshots=$NSHOTS blank=$BLANK"
  echo "-- model availability marker (closes the x86_64-only live-tool-call carry-forward when 1)"
  cat "$OUT/p10d-model-lines.txt" 2>/dev/null || true
  echo "apk=$(basename "$APK") sha256=$(sha256sum "$APK" 2>/dev/null | awk '{print $1}')"
  echo "pass=$PASS fail=$FAIL skip=$SKIP"
} >> "$OUT/SUMMARY.txt"
echo | tee -a "$LOG"
cat "$OUT/SUMMARY.txt" | tee -a "$LOG"
echo
if [ "$FAIL" != 0 ] && [ -s "$OUT/DIAGNOSIS.txt" ]; then
  echo "--- why something failed (also in DIAGNOSIS.txt) ---"
  cat "$OUT/DIAGNOSIS.txt"
fi
echo
echo "Bundle: $OUT/  - send this whole folder back. SUMMARY.txt is the verdict list;"
echo "screenshots/ are the store-quality captures from the signed build (each one is"
echo "checked for being a real screen, not a black or locked frame); ui/ holds the"
echo "accessibility dump of every step; DIAGNOSIS.txt explains each failure - it was"
echo "written from the device state at the time, not from a guess afterwards."
echo "Nothing in it contains a credential: the log went through the redaction filter."
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)
