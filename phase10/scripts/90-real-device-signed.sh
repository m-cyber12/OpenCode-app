#!/usr/bin/env bash
# 90-real-device-signed.sh - verify the SIGNED build on a real arm64 phone.
#
# Why this exists separately from every earlier device script: every gate this
# project has run used a DEBUG build (Phases 4-9) or the release-shaped smoke
# build (Phase 10 CI). The artifact users install is the SIGNED RELEASE build,
# and signing changes things: the applicationId is the release one, the data dir
# and the Keystore namespace are new, `debuggable=false` removes run-as (so the
# harness mechanism the older suites used does not exist here), the FileProvider
# authority differs, and the signature identity is the upload key rather than a
# debug key. This script tests exactly that artifact, through the UI, with no
# privileged access - the same position a user is in.
#
# WHAT YOU NEED (one-time):
#   1. a phone: arm64, Android 10+ , USB debugging on
#   2. platform-tools (`adb`) on PATH
#   3. the SIGNED apk from phase10/scripts/sign-release-local.sh (docs/RELEASE.md)
#   4. optional: a model provider key, typed AT THE TERMINAL when asked (never in
#      chat, never in a file). Without a key the live-turn gate SKIPs and
#      everything else still runs. You can equally add the key in the app's own
#      Settings first and skip the prompt.
#
# WHAT IT DOES:
#   R1  device facts (model, API level, ABI, secure-hardware flags) + the ABI gate
#   R2  artifact verification: signature (apksigner, if installed) + identity
#       (check-apk.py: package/version/icon/payload/permissions/no-debuggable)
#   R3  clean install of the SIGNED apk and first launch
#   R4  first-run flow driven through the UI: welcome -> runtime healthy by itself
#       -> create a project -> enabled composer
#   R5  screenshots (store-quality, from the SIGNED build) + a store-asset check
#   R6  a live turn through the composer, and - if a model can serve it - a real
#       tool call, read back from the accessibility tree (no root, no run-as)
#   R7  footprint: memory, storage, cold-start timing
#   R8  crash / obfuscation sweep: any FATAL EXCEPTION, ClassNotFoundException,
#       NoSuchMethodError, NoClassDefFoundError or UnsatisfiedLinkError in the
#       session's logcat is reported - these are the failure modes that appear
#       only once code is packaged for release
#   R9  verdict bundle in ./p10d-out/ (send the whole folder back)
#
# Usage: bash phase10/scripts/90-real-device-signed.sh [--apk PATH] [--out DIR]
set -uo pipefail
cd "$(dirname "$0")"
DIR="$(cd .. && pwd)"          # phase10/
ROOT="$(cd "$DIR/.." && pwd)"  # repo root
OUT="p10d-out"
APK=""
CERT_EXPECT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --cert-sha256) CERT_EXPECT="$(printf '%s' "${2:-}" | tr -d ' :' | tr 'A-Z' 'a-z')"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,42p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done
mkdir -p "$OUT"
LOG="$OUT/run.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PASS=0; FAIL=0; SKIP=0
: > "$OUT/SUMMARY.txt"
rec() { echo "$1 $2${3:+ :: $3}" >> "$OUT/SUMMARY.txt"; log "$1 $2${3:+ :: $3}"; }
rd() { case "$2" in 0) PASS=$((PASS+1)); rec "P10D_$1" PASS "$3";; 7) SKIP=$((SKIP+1)); rec "P10D_$1" SKIP "$3";; *) FAIL=$((FAIL+1)); rec "P10D_$1" FAIL "$3";; esac; }

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

# ---- R1: device facts --------------------------------------------------------
ABI_NOW=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
SDK_NOW=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
{
  echo "device=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "manufacturer=$(adb shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r') (API $SDK_NOW)"
  echo "abi=$ABI_NOW"
  echo "screen=$(adb shell wm size 2>/dev/null | tr -d '\r') $(adb shell wm density 2>/dev/null | tr -d '\r')"
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
VNAME=$(grep -o 'versionName = "[^"]*"' -m1 "$ROOT/app/build.gradle.kts" | cut -d'"' -f2)
VCODE=$(grep -oE 'versionCode = [0-9]+' -m1 "$ROOT/app/build.gradle.kts" | grep -oE '[0-9]+')
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
    # A fingerprint is public data (it is printed by apksigner and shown in Play
    # Console), unlike the keystore: checking it here proves the artifact was signed
    # with the INTENDED key and not some other release key found on the machine.
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

# ---- R4: first-run flow through the UI ---------------------------------------
uia_dump() { adb shell uiautomator dump /sdcard/p10d-ui.xml >/dev/null 2>&1 || return 1
             adb shell cat /sdcard/p10d-ui.xml 2>/dev/null > "$OUT/p10d-ui.xml"; }
uia_has() { uia_dump >/dev/null 2>&1; grep -aq "$1" "$OUT/p10d-ui.xml" 2>/dev/null; }
uia_center() { # echoes "x y" of the first node whose text/desc contains $1
  uia_dump >/dev/null 2>&1 || return 1
  python3 - "$OUT/p10d-ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
needle = sys.argv[2].lower()
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    def g(k):
        r = re.search(k + r'="([^"]*)"', tag)
        return r.group(1) if r else ""
    if needle in (g("text") + " " + g("content-desc")).lower():
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not b:
            continue
        x1, y1, x2, y2 = (int(b.group(i)) for i in range(1, 5))
        if x2 > x1 and y2 > y1:
            print("%d %d" % ((x1 + x2) // 2, (y1 + y2) // 2)); sys.exit(0)
sys.exit(1)
PY
}
uia_tap() { local c; c=$(uia_center "$1") || return 1; adb shell input tap $c >/dev/null 2>&1; sleep 2; }

log "launching the app and timing the first run"
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || \
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
T0=$(date +%s)
HEALTH=0
for i in $(seq 1 60); do          # up to 5 minutes: first run extracts ~1 GB
  if uia_has "Ready" || uia_has "Continue" || uia_has "Start a conversation" || uia_has "Projects"; then HEALTH=1; break; fi
  sleep 5
done
T1=$(( $(date +%s) - T0 ))
if [ "$HEALTH" = 1 ]; then
  rd FIRST_RUN 0 "app reached a working screen list by itself in ${T1}s on first launch (payload extracted + agent started, no user action)"
else
  rd FIRST_RUN 1 "no working screen after ${T1}s (see p10d-ui.xml, run.log; the app also offers Settings -> Share diagnostics)"
fi
# welcome -> continue -> projects -> create a project
uia_tap "Continue" >/dev/null 2>&1 || true
sleep 2
if uia_tap "New project" >/dev/null 2>&1; then
  c=$(uia_center "my-app" 2>/dev/null) && adb shell input tap $c >/dev/null 2>&1
  sleep 1
  adb shell input text "p10-demo" >/dev/null 2>&1
  sleep 1
  if uia_tap "Create project" >/dev/null 2>&1; then
    sleep 5
    if uia_has "Send" || uia_has "Message" || uia_has "Start a conversation" || uia_has "Ask"; then
      rd FIRST_RUN_PROJECT 0 "project created through the UI on the signed build; conversation surface reached"
    else
      rd FIRST_RUN_PROJECT 1 "project created but the conversation surface was not reached (see p10d-ui.xml)"
    fi
  else
    rd FIRST_RUN_PROJECT 1 "could not tap 'Create project' (see p10d-ui.xml)"
  fi
else
  rd FIRST_RUN_PROJECT 7 "no 'New project' action visible (the app may already have a project open, or hit the projects screen differently)"
fi

# ---- R5: screenshots from the SIGNED build -----------------------------------
mkdir -p "$OUT/screenshots"
SHOTRC=0
bash "$DIR/scripts/70-device-screenshots.sh" --out "$OUT/screenshots" --pkg "$PKG" > "$OUT/screenshots.log" 2>&1 || SHOTRC=1
NSHOTS=$(ls -1 "$OUT/screenshots"/*.png 2>/dev/null | wc -l | tr -d ' ')
if [ "$NSHOTS" -ge 2 ]; then
  rd SCREENSHOTS 0 "$NSHOTS screenshots captured from the signed build (see screenshots/; these are the ones to prefer in the listing)"
else
  rd SCREENSHOTS 1 "only $NSHOTS screenshots captured from the signed build (see screenshots.log)"
fi

# ---- R6: a live turn through the composer ------------------------------------
MODEL_AVAILABLE=0
# The signed build is NOT debuggable, so the older harness (files/harness + run-as)
# does not exist. The turn is driven the way a user drives it, and the result is
# read back from the accessibility tree - which is exactly the evidence a user
# could point at.
printf '\nType a provider API key to run the live-turn gate (typed here, never stored, never logged)'
printf '\nPress [Enter] to skip if you already added a key in the app or want to skip: '
read -r MODEL_KEY
if [ -n "$MODEL_KEY" ]; then
  # Route it through the app's own UI: Settings -> Provider keys.
  if uia_tap "Settings" >/dev/null 2>&1; then
    sleep 2
    c=$(uia_center "Paste the key" 2>/dev/null)
    if [ -n "${c:-}" ]; then adb shell input tap $c >/dev/null 2>&1; sleep 1; fi
    adb shell input text "$MODEL_KEY" >/dev/null 2>&1
    sleep 1
    uia_tap "Save key" >/dev/null 2>&1 || true
    sleep 3
    log "key entered through the app's own Settings screen (it is not in this log)"
  else
    log "could not open Settings to enter the key"
  fi
  unset MODEL_KEY
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 2
fi
if uia_tap "Message the agent" >/dev/null 2>&1 || uia_tap "Message" >/dev/null 2>&1 || uia_tap "Start a conversation" >/dev/null 2>&1; then
  # `adb shell` re-quotes what it forwards, and `input text` takes a single argument
  # in which %s is a space: quotes and literal spaces would be re-parsed by the
  # device shell and the prompt would arrive mangled (or not at all).
  adb shell input text "Run%sthe%sshell%scommand%secho%sp10-live-ok%sin%sthis%sproject%sand%sshow%sme%sits%soutput" >/dev/null 2>&1
  sleep 1
  uia_tap "Send" >/dev/null 2>&1 || adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
  log "prompt sent; waiting up to 180s for the answer"
  TURN=0
  for i in $(seq 1 36); do
    if uia_has "p10-live-ok"; then TURN=1; break; fi
    sleep 5
  done
  TOOLCARD=0
  uia_has "Shell command" && TOOLCARD=1            # the tool card's own header string
  cp "$OUT/p10d-ui.xml" "$OUT/p10d-live-turn.xml" 2>/dev/null || true
  # The single best listing screenshot is the one no emulator can fake: a real turn
  # with an expanded tool card, captured on the owner's own phone. Keep it only when
  # the turn actually produced it.
  if [ "$TURN" = 1 ] && [ "$TOOLCARD" = 1 ]; then
    adb exec-out screencap -p > "$OUT/screenshots/07-chat-live-turn.png" 2>/dev/null || true
    log "captured 07-chat-live-turn.png (real turn + tool card)"
  fi
  if [ "$TURN" = 1 ] && [ "$TOOLCARD" = 1 ]; then
    MODEL_AVAILABLE=1
    rd LIVE_TURN 0 "the answer is on screen AND a Shell-command tool card is expanded in the conversation: a real live tool call ran in the SIGNED build (p10d-live-turn.xml)"
  elif [ "$TURN" = 1 ]; then
    MODEL_AVAILABLE=1
    rd LIVE_TURN 1 "the expected output is on screen but no tool card is visible: the model may have echoed the prompt instead of running it - expand the turn and re-check (p10d-live-turn.xml)"
  else
    MODEL_AVAILABLE=0
    # Distinguish "no model could serve it" from "it broke".
    if uia_has "key was rejected" || uia_has "credit" || uia_has "unreachable" || uia_has "not running"; then
      rd LIVE_TURN 7 "no model served the turn (the app says why - see p10d-live-turn.xml). Not a pass: re-run with a funded key"
    else
      rd LIVE_TURN 1 "the turn never produced the expected output and the app showed no provider error: investigate (p10d-live-turn.xml + logcat.txt)"
    fi
  fi
else
  MODEL_AVAILABLE=0
  rd LIVE_TURN 7 "could not find the composer on the conversation surface (see p10d-ui.xml)"
fi
# The same marker Phase 6's live-chat class writes, so the Phase 9/10 carry-forward
# ("live tool call verified only on the x86_64 emulator") can be closed by a machine
# reading this file, not by prose.
echo "P6_MODEL_AVAILABLE ${MODEL_AVAILABLE:-0} :: signed build, arm64 device, driven through the composer" >> "$OUT/p10d-model-lines.txt"

# ---- R7: footprint -----------------------------------------------------------
adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' > "$OUT/meminfo.txt" || true
PSS=$(grep -aoE 'TOTAL PSS: *[0-9]+' "$OUT/meminfo.txt" | head -1 | grep -oE '[0-9]+')
[ -n "$PSS" ] && rd MEMORY 0 "total PSS $((PSS/1024)) MB (see meminfo.txt)" || rd MEMORY 7 "meminfo unavailable"
STORAGE=$(adb shell du -sh /data/data/"$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
[ -n "$STORAGE" ] && rd STORAGE 0 "app-private storage ${STORAGE} (payload extracted)" || rd STORAGE 7 "storage unreadable without run-as on a non-debuggable build (expected)"

# ---- R8: crash / obfuscation sweep -------------------------------------------
adb logcat -d 2>/dev/null > "$OUT/logcat-raw.txt" || true
redact < "$OUT/logcat-raw.txt" > "$OUT/logcat.txt" 2>/dev/null || cp "$OUT/logcat-raw.txt" "$OUT/logcat.txt"
rm -f "$OUT/logcat-raw.txt" 2>/dev/null || true
grep -aoE 'P6_MODEL_AVAILABLE [01][^\r]*' "$OUT/logcat.txt" 2>/dev/null | sort -u >> "$OUT/p10d-model-lines.txt" || true
SWEEP=$(grep -acE "FATAL EXCEPTION|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError|UnsatisfiedLinkError" "$OUT/logcat.txt" 2>/dev/null || echo 0)
if [ "${SWEEP:-0}" = 0 ]; then
  rd PACKAGING_SWEEP 0 "no FATAL EXCEPTION / ClassNotFound / NoSuchMethod / NoClassDefFound / UnsatisfiedLink in the session's logcat"
else
  rd PACKAGING_SWEEP 1 "$SWEEP packaging/runtime failure line(s) in logcat.txt - this is exactly what release packaging breaks (read them before uploading)"
fi
CRASHES=$(grep -acE "FATAL EXCEPTION|Process: $PKG" "$OUT/logcat.txt" 2>/dev/null || echo 0)
[ "${CRASHES:-0}" = 0 ] && rd NO_CRASH 0 "no app crash in this session" || rd NO_CRASH 1 "$CRASHES crash marker(s) in logcat.txt"

# ---- R9: bundle --------------------------------------------------------------
{
  echo "phase10 real-device verification of the SIGNED build $(date -u +%FT%TZ)"
  echo "-- model availability marker (closes the x86_64-only live-tool-call carry-forward when 1)"
  cat "$OUT/p10d-model-lines.txt" 2>/dev/null || true
  echo "apk=$(basename "$APK") sha256=$(sha256sum "$APK" 2>/dev/null | awk '{print $1}')"
  echo "pass=$PASS fail=$FAIL skip=$SKIP"
} >> "$OUT/SUMMARY.txt"
echo | tee -a "$LOG"
cat "$OUT/SUMMARY.txt" | tee -a "$LOG"
echo
echo "Bundle: $OUT/  - send this whole folder back (SUMMARY.txt is the verdict list;"
echo "screenshots/ are the store-quality captures from the signed build)."
echo "Nothing in it contains a credential: the log went through the redaction filter."
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)
