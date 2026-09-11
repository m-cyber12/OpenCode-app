#!/usr/bin/env bash
# 90-real-device-suite.sh - the Phase 8 REAL DEVICE suite (the user's machine).
#
# What runs in CI is an x86_64 emulator with a SOFTWARE keystore. The things
# only a real arm64 device can answer - secure-hardware key residency, the
# cold start on real silicon, the live tool call against the real provider -
# run here, one-shot, from the user's own machine.
#
# WHAT THE USER NEEDS (one-time):
#   1. USB cable, the phone (USB debugging ON: Developer options -> USB debugging),
#      a PC with https://developer.android.com/tools/releases/platform-tools
#      (a zip; extract it and put adb on PATH - on Windows: add the folder to
#      the PATH variable), and a bash shell (Git Bash is fine).
#   2. The two APKs from the CI run's "phase8-hardening" artifact:
#        app-debug.apk  and  app-debug-androidTest.apk
#      next to this script (or pass their paths as $1 and $2).
#   3. Optional: a (short-lived) OpenRouter API key, TYPED AT THE TERMINAL WHEN
#      ASKED - never pasted into chat. Without it the live tool-call gate on
#      the device SKIPs and the rest still runs.
#
# WHAT IT DOES (in order):
#   R1  device facts (model, API, abi, secure-hardware flags)
#   R2  the toybox staging path the harness relies on, on THIS API level
#   R3  install, launch, cold start timing from the supervisor's own log
#   R4  instrumented stress class (key-residency probe measured on real
#       hardware, provider-auth failure, supervised server-kill restart)
#   R5  instrumented live class (key probe + real tool call through the UI,
#       with the key typed at the terminal) - closes Phase 6 L2 on a real
#       device; the key is revoked from the phone afterwards
#   R6  memory / CPU / storage footprint, and the verdict bundle
#
# The verdict bundle lands in ./p8d-out/ on the PC. Send that folder back (or
# paste the contents of p8d-out/SUMMARY.txt) and the phase report folds it in.
set -uo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
OUT="p8d-out"
mkdir -p "$OUT"
LOG="$OUT/run.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PASS=0; FAIL=0; SKIP=0
rec() { echo "$1 $2${3:+ :: $3}" >> "$OUT/SUMMARY.txt"; log "$1 $2${3:+ :: $3}"; }
rd() { # $1=id $2=rc $3=detail
  case "$2" in
    0) PASS=$((PASS+1)); rec "P8D_$1" PASS "$3" ;;
    7) SKIP=$((SKIP+1)); rec "P8D_$1" SKIP "$3" ;;
    *) FAIL=$((FAIL+1)); rec "P8D_$1" FAIL "$3" ;;
  esac
}
: > "$OUT/SUMMARY.txt"

APK="${1:-app-debug.apk}"
TAPK="${2:-app-debug-androidTest.apk}"
PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
FILES="/data/data/$PKG/files"
PORT=4111
# nativeLibraryDir: the ONLY exec-allowed directory for this package. bin/bun is
# a symlink into it, and the exec shim (libexecshim.so) lives here too.
NATIVE_LIB_DIR=""
ABI_NOW=""

rash() { printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'; }

# SECRET REDACTION (round 16). Bun echoes the full request URL in its network
# error messages, and a Gemini key travels in the URL query string (?key=...).
# Round 15 therefore wrote a LIVE API key into p8d-out/key-preflight.txt, which
# the user then uploaded to a PUBLIC repo. Every artifact this suite writes now
# goes through this filter, so a credential can never reach the bundle again.
redact() {
  sed -E -e 's/(key=)AQ\.[A-Za-z0-9_-]+/\1AQ.<REDACTED>/g' \
         -e 's/AQ\.[A-Za-z0-9_-]{12,}/AQ.<REDACTED>/g' \
         -e 's/(key=)AIza[A-Za-z0-9_-]+/\1AIza<REDACTED>/g' \
         -e 's/AIza[A-Za-z0-9_-]{20,}/AIza<REDACTED>/g' \
         -e 's/sk-or-[A-Za-z0-9_-]{10,}/sk-or-<REDACTED>/g' \
         -e 's/(Bearer )[A-Za-z0-9._-]{12,}/\1<REDACTED>/g'
}

# Run a bun script the SAME WAY THE APP DOES.
#
# Round 15 finding: every ad-hoc `$FILES/bin/bun script.js` in this suite was
# launching bun RAW - without the exec shim and without LD_PRELOAD of
# libseccompshim.so. The app never does that (RuntimeProcess.start() execs
# libexecshim.so with OPENCODE_BUN_EXEC + OPENCODE_SECCOMP_SHIM set), because
# Android's per-app seccomp filter turns unknown syscalls into a FATAL SIGSYS
# instead of ENOSYS. A raw bun therefore dies on a signal during startup and
# prints NOTHING - exactly the "P8KEYPREFLIGHT no output in 1s" we recorded.
# So the preflight was never measuring the provider at all; it was measuring
# our own launch bug.
#
# Poll a bun probe INSIDE ONE device shell: $1=js $2=max tries $3=sleep secs.
# One adb round-trip and one process spawn per try instead of a full
# adb+run-as+exec-shim stack per try (see R3 comment).
dbun_poll() {
  _js="$1"; _tries="${2:-150}"; _slp="${3:-2}"
  if [ -n "$NATIVE_LIB_DIR" ]; then
    rash "cd '$FILES' && i=0; while [ \$i -lt $_tries ]; do \
      out=\$(HOME='$FILES/home' TMPDIR='$FILES/tmp' \
        OPENCODE_BUN_EXEC='$NATIVE_LIB_DIR/libbun.so' \
        OPENCODE_SECCOMP_SHIM='$NATIVE_LIB_DIR/libseccompshim.so' \
        '$NATIVE_LIB_DIR/libexecshim.so' '$_js' 2>/dev/null); \
      case \"\$out\" in *200*|*401*) echo \"\$out\"; exit 0;; esac; \
      i=\$((i+1)); sleep $_slp; done; echo timeout"
  else
    rash "cd '$FILES' && i=0; while [ \$i -lt $_tries ]; do \
      out=\$(HOME='$FILES/home' '$FILES/bin/bun' '$_js' 2>/dev/null); \
      case \"\$out\" in *200*|*401*) echo \"\$out\"; exit 0;; esac; \
      i=\$((i+1)); sleep $_slp; done; echo timeout"
  fi
}

# $1 = absolute path of the .js to run, rest = extra "VAR=val" env pairs.
dbun() {
  _js="$1"; shift
  _env="$*"
  _exec="$NATIVE_LIB_DIR/libexecshim.so"
  _shim="$NATIVE_LIB_DIR/libseccompshim.so"
  if [ -n "$NATIVE_LIB_DIR" ]; then
    rash "cd '$FILES' && HOME='$FILES/home' TMPDIR='$FILES/tmp' \
      OPENCODE_BUN_EXEC='$NATIVE_LIB_DIR/libbun.so' \
      OPENCODE_SECCOMP_SHIM='$_shim' \
      $_env '$_exec' '$_js' 2>&1; echo dbun_rc=\$?"
  else
    rash "cd '$FILES' && HOME='$FILES/home' TMPDIR='$FILES/tmp' $_env '$FILES/bin/bun' '$_js' 2>&1; echo dbun_rc=\$?"
  fi
}

# ---- R0: preconditions -------------------------------------------------------
command -v adb >/dev/null 2>&1 || { echo "FATAL: adb not found on PATH (install platform-tools and add it to PATH)"; exit 2; }
[ -f "$APK" ] || { echo "FATAL: app APK not found: $APK (download it from the CI artifact)"; exit 2; }
[ -f "$TAPK" ] || { echo "FATAL: androidTest APK not found: $TAPK (download it from the CI artifact)"; exit 2; }
DEVS=$(adb devices 2>/dev/null | tail -n +2 | grep -c 'device$' || true)
if [ "$DEVS" = "0" ]; then
  echo "FATAL: no authorized device. Connect the phone over USB, enable Developer options -> USB debugging, and accept the authorization dialog."
  exit 2
elif [ "$DEVS" -gt 1 ]; then
  echo "FATAL: more than one device attached; keep only the target phone connected."
  exit 2
fi
log "device attached and authorized"

# ---- R1: device facts --------------------------------------------------------
{
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "manufacturer=$(adb shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "android_release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "secure_hardware_flags:"
  adb shell getprop | grep -iE 'ro\.security|keymint|strongbox|titan' | head -12
} > "$OUT/device-facts.txt" 2>&1
cat "$OUT/device-facts.txt" | tee -a "$LOG"

# ---- R3a: install + launch ---------------------------------------------------
log "installing (uninstall first for a clean state)"
adb uninstall "$TEST_PKG" >/dev/null 2>&1 || true
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$LOG" || { rd INSTALL 1 "adb install app failed"; }
adb install -r -g "$TAPK" 2>&1 | tail -2 | tee -a "$LOG" || { rd INSTALL 1 "adb install androidTest failed"; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
# Harness marker BEFORE first launch (the password export needs it).
rash "mkdir -p '$FILES/harness'; touch '$FILES/harness/enabled'; chmod 700 '$FILES/harness'; echo harness_rc=\$?" >> "$LOG" 2>&1
ABI_NOW=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
APK_PATH_NOW=$(adb shell pm path "$PKG" | tr -d '\r' | sed 's/^package://' | head -1)
if [ -n "$APK_PATH_NOW" ]; then
  case "$ABI_NOW" in
    arm64*) NATIVE_LIB_DIR="$(dirname "$APK_PATH_NOW")/lib/arm64" ;;
    x86_64) NATIVE_LIB_DIR="$(dirname "$APK_PATH_NOW")/lib/x86_64" ;;
    *)      NATIVE_LIB_DIR="$(dirname "$APK_PATH_NOW")/lib/$ABI_NOW" ;;
  esac
fi
log "nativeLibraryDir=$NATIVE_LIB_DIR abi=$ABI_NOW"

# ---- R2: the toybox staging path on THIS API ---------------------------------
TB_B64=$(cd "$OUT" && mkdir -p tb && printf 'tb-a\n' > tb/a.txt && printf 'tb-b\n' > tb/b.txt && tar cz -C tb . | base64 -w0)
TB_OUT=$(rash "rm -rf '$FILES/tmp/tb' && mkdir -p '$FILES/tmp/tb' && echo '$TB_B64' | base64 -d | tar xz -C '$FILES/tmp/tb' && ls '$FILES/tmp/tb' | tr '\n' ' ' && echo toybox_rc=\$?" 2>/dev/null)
TB_API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if echo "$TB_OUT" | grep -q "toybox_rc=0" && echo "$TB_OUT" | grep -q "a.txt"; then
  rd TOYBOX 0 "base64|base64 -d|tar xz -C dir works on this real device api=$TB_API (closes the harness-staging half for this API; API-29 remains the untested half - see report)"
else
  rd TOYBOX 1 "toybox staging failed on api=$TB_API: $(echo "$TB_OUT" | head -2)"
fi

# ---- R2b: bun launch-path self-test (round 15) --------------------------------
# The decisive experiment for the "silent model turn" mystery. Runs the SAME
# trivial script three ways and reports each exit code:
#   raw   : $FILES/bin/bun script.js            (what this suite used to do)
#   shim  : libexecshim.so + LD_PRELOAD shim    (what the APP actually does)
# A raw bun killed by SIGSYS exits 159 (128+31) with no stdout. If raw fails and
# shim succeeds, every previous "no output"/"egress" reading taken through raw
# bun was measuring our launcher, not the network or the provider.
rash "echo 'console.log(\"P8BUNSELFTEST alive=1 v=\"+(process.versions&&process.versions.bun))' > '$FILES/tmp/selftest.js'" >/dev/null 2>&1
RAW_OUT=$(rash "cd '$FILES' && HOME='$FILES/home' '$FILES/bin/bun' '$FILES/tmp/selftest.js' 2>&1; echo raw_rc=\$?")
SHIM_OUT=$(dbun "$FILES/tmp/selftest.js")
rash "rm -f '$FILES/tmp/selftest.js'" >/dev/null 2>&1
{ echo "=== raw bun ==="; printf '%s\n' "$RAW_OUT"
  echo "=== exec-shim bun ==="; printf '%s\n' "$SHIM_OUT"; } > "$OUT/bun-launch-selftest.txt"
RAW_RC=$(printf '%s\n' "$RAW_OUT" | sed -n 's/.*raw_rc=\([0-9]*\).*/\1/p' | tail -1)
SHIM_RC=$(printf '%s\n' "$SHIM_OUT" | sed -n 's/.*dbun_rc=\([0-9]*\).*/\1/p' | tail -1)
RAW_OK=$(printf '%s\n' "$RAW_OUT" | grep -c 'P8BUNSELFTEST alive=1' || true)
SHIM_OK=$(printf '%s\n' "$SHIM_OUT" | grep -c 'P8BUNSELFTEST alive=1' || true)
RAW_SIG=""
[ -n "${RAW_RC:-}" ] && [ "${RAW_RC:-0}" -gt 128 ] 2>/dev/null && RAW_SIG=" (killed by signal $((RAW_RC-128)))"
if [ "${SHIM_OK:-0}" -ge 1 ]; then
  rd BUNLAUNCH 0 "exec-shim bun runs (rc=$SHIM_RC); raw bun alive=${RAW_OK:-0} rc=${RAW_RC:-?}$RAW_SIG :: the app's launch path is the working one; raw bun invocations in a harness are not representative"
else
  rd BUNLAUNCH 1 "NEITHER launch path produced output (shim rc=${SHIM_RC:-?}, raw rc=${RAW_RC:-?}$RAW_SIG) - see bun-launch-selftest.txt"
fi

# ---- R3: cold start on real silicon ------------------------------------------
T0=$(date +%s)
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
# Unauthenticated by design: the server REFUSES with 401 until it has the
# Keystore password, so "any HTTP response on loopback" proves the server is
# up; the supervisor's own log is the authority on the HEALTHY transition.
COLD_OK=1; H=""
# Round 17: the poll used to re-write the probe file and spawn a fresh
# adb+run-as+exec-shim+Bun startup on EVERY iteration. Each of those costs
# seconds, so the measured "wall" was dominated by the instrument, not the
# app: run 6 reported 319s wall against a supervisor window of 17.6s. Write
# the probe ONCE, and poll it with a bounded loop INSIDE a single device
# shell, so the timing reflects the product.
rash "echo \"const r=await fetch('http://127.0.0.1:$PORT/global/health');console.log(r.status)\" > '$FILES/tmp/health.js'" >/dev/null 2>&1
H=$(dbun_poll "$FILES/tmp/health.js" 150 2 | grep -oE '^(200|401)' | head -1)
[ -n "$H" ] && COLD_OK=0
COLD_S=$(( $(date +%s) - T0 ))
COLD_WIN=$(rash "grep -a 'state -> EXTRACTING' '$FILES/log/runtime.log' | head -1" | awk '{print $1}')
COLD_WIN2=$(rash "grep -a 'state -> HEALTHY' '$FILES/log/runtime.log' | head -1" | awk '{print $1}')
if [ "$COLD_OK" = "0" ]; then
  rd COLDSTART 0 "launch -> server answering loopback (http $H) in ${COLD_S}s wall; supervisor log window: $COLD_WIN -> $COLD_WIN2 (fresh install, first extraction included)"
else
  rd COLDSTART 1 "no loopback HTTP response within 300s (log window: $COLD_WIN -> $COLD_WIN2)"
fi

# The instrumented APK emits P8_ verdicts to stdout, logcat and a device-side
# file; which channel survives varies by environment, so every summary reads
# all three. gate_line prints the LAST verdict line for a gate from a file.
gate_line() { awk -v g="P8_$1" 'NF && $1==g && ($2=="PASS"||$2=="FAIL"||$2=="SKIP"){last=$0} END{if(last!="") print last}' "$2" 2>/dev/null; }
summarize_gates() { # $1=id-list $2=verdicts-file $3=rc-label
  local g L V D
  for g in $1; do
    L=$(gate_line "$g" "$2")
    if [ -n "$L" ]; then
      V=$(printf '%s' "$L" | awk '{print $2}')
      D=$(printf '%s' "$L" | sed -E 's/^P8_[A-Z0-9_]+ (PASS|FAIL|SKIP) *(::)? *//' | cut -c1-220)
      case "$V" in
        PASS) rd "$g" 0 "$D" ;;
        SKIP) rd "$g" 7 "$D" ;;
        *)    rd "$g" 1 "$D" ;;
      esac
    else
      rd "$g" 1 "no verdict line ($3); file head: [$(head -c 300 "$2" 2>/dev/null | tr '\n' '|')]"
    fi
  done
}

# ---- R4: the stress class (key residency on real hardware) --------------------
rash "rm -f files/p8-verdicts.txt" >/dev/null 2>&1 || true
adb shell am instrument -w -e class "ai.opencode.android.ui.StressRecoveryGatesTest" \
  "$RUNNER" > "$OUT/stress-instrument.log" 2>&1
STRESS_RC=$?
{ rash "cat files/p8-verdicts.txt" 2>/dev/null
  grep -aoE 'P8_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*' "$OUT/stress-instrument.log" 2>/dev/null
  adb logcat -d 2>/dev/null | grep -aoE 'P8_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*'
} | sed 's/[[:space:]]*$//' | sort -u > "$OUT/stress-verdicts.txt" || true
tail -60 "$OUT/stress-verdicts.txt" | tee -a "$LOG"
summarize_gates "KEYRESIDENCY PROVAUTH SERVERKILL LIFECYCLELOG" "$OUT/stress-verdicts.txt" "instrument rc=$STRESS_RC"

# ---- R5: the live class (the real tool call on the real device) ---------------
# Round 13: the provider is a CHOICE. OpenRouter's UNFUNDED free tier is capped
# at 50 requests/day, 20/min, and serves :free endpoints at lowest priority -
# exactly what device runs 1-3 sampled (one answered turn, then silent empty
# completions). Google's Gemini free tier is request-limited, not credit-
# limited (Flash-class: ~10-15 RPM, hundreds-to-1500 RPD, 250K+ TPM, no card),
# so it can serve the TWO inferences a tool-call turn needs.
# Provider ids are OpenCode's own (models.dev): google | openrouter.
printf 'Provider for the live gates - 1) google (Gemini, free key: https://aistudio.google.com/apikey) 2) openrouter [1]: '
read -r PROV_CHOICE
printf '\n'
case "${PROV_CHOICE:-1}" in
  2|openrouter) PROVIDER=openrouter; DEF_MODEL="openai/gpt-4o-mini"; KEY_LABEL="OpenRouter" ;;
  *)            PROVIDER=google;     DEF_MODEL="gemini-2.5-flash";   KEY_LABEL="Gemini (Google AI Studio)" ;;
esac
printf 'Type a %s API key to run the live tool-call gate on the device, [Enter] to skip: ' "$KEY_LABEL"
read -r MODEL_KEY
printf '\n'
printf 'Model id (default %s), [Enter] for default: ' "$DEF_MODEL"
read -r MODEL
printf '\n'
if [ -n "$MODEL_KEY" ]; then
  MODEL="${MODEL:-$DEF_MODEL}"
  # Cheap shape check so a mis-typed key is caught here, not 20 minutes later
  # as a silent empty turn. Gemini keys look like AIza...; OpenRouter sk-or-...
  # Google AI Studio issues BOTH formats: the classic "AIzaSy..." (39 chars) and
  # the newer "AQ.Ab8RN6..." keys (~52 chars) rolled out in Aug 2026. Round 13
  # only knew the first shape and cried wolf on a perfectly valid AQ. key, so
  # this check accepts both - and it is only ever a warning. The PREFLIGHT
  # below is the authority: it asks the provider instead of guessing.
  case "$PROVIDER:$MODEL_KEY" in
    google:AIza*|google:AQ.*|openrouter:sk-or-*) : ;;
    *) log "WARNING: the typed key does not match a known $KEY_LABEL key shape (provider=$PROVIDER, ${#MODEL_KEY} chars) - the preflight below decides" ;;
  esac
  log "live gates provider=$PROVIDER model=$MODEL keychars=${#MODEL_KEY}"

  # ---- PREFLIGHT: moved on-device (round 16) ----------------------------------
  # The host-side preflight is GONE. A `run-as` shell is not a member of the
  # inet group (AID_INET 3003), and Android's paranoid-network kernel check
  # refuses sockets for processes outside it - so the round-15 preflight died
  # with ConnectionRefused having never contacted Google, and (worse) Bun's
  # error text printed the full request URL, leaking the key into the bundle.
  # The preflight now runs inside the instrumented gate (a child of the SERVER,
  # which does have network) and is reported as the P8_KEYPREFLIGHT gate.
fi
if [ -n "$MODEL_KEY" ]; then
  PB64=$(printf '%s' "$PROVIDER" | base64 -w0)
  rash "echo '$PB64' | base64 -d > '$FILES/harness/provider'" >> "$LOG" 2>&1
  # The key goes base64-over-stdin: it never appears in a shell command line.
  B64=$(printf '%s' "$MODEL_KEY" | base64 -w0)
  rash "echo '$B64' | base64 -d > '$FILES/harness/model-key'; chmod 600 '$FILES/harness/model-key'; echo keybytes=\$(wc -c < '$FILES/harness/model-key')" >> "$LOG" 2>&1
  printf '%s' "$MODEL" | base64 -w0 > "$OUT/model.b64"
  MB64=$(cat "$OUT/model.b64")
  rash "echo '$MB64' | base64 -d > '$FILES/harness/model'" >> "$LOG" 2>&1
  rm -f "$OUT/model.b64" 2>/dev/null || true
else
  log "no key typed: the live model gates will SKIP on the device"
fi
rash "rm -f files/p8-verdicts.txt" >/dev/null 2>&1 || true
adb shell am instrument -w -e class "ai.opencode.android.ui.LiveToolCallGatesTest" \
  "$RUNNER" > "$OUT/live-instrument.log" 2>&1
LIVE_RC=$?
{ rash "cat files/p8-verdicts.txt" 2>/dev/null
  grep -aoE 'P8_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*' "$OUT/live-instrument.log" 2>/dev/null
  adb logcat -d 2>/dev/null | grep -aoE 'P8_[A-Z0-9_]+ (PASS|FAIL|SKIP)[^\r]*'
} | sed 's/[[:space:]]*$//' | sort -u > "$OUT/live-verdicts.txt" || true
tail -60 "$OUT/live-verdicts.txt" | tee -a "$LOG"
summarize_gates "KEYPREFLIGHT KEYPROBE TOOL CLEANUP" "$OUT/live-verdicts.txt" "instrument rc=$LIVE_RC"
# The key must not survive on the phone.
rash "rm -f '$FILES/harness/model-key' '$FILES/harness/model' '$FILES/harness/provider'" >/dev/null 2>&1 || true

# ---- R6: footprint + verdict bundle -------------------------------------------
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
sleep 10
adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' > "$OUT/meminfo.txt" || true
adb shell du -sk "$FILES" 2>/dev/null | tr -d '\r' > "$OUT/storage.txt" || true
adb shell "ls -la '$FILES' 2>/dev/null" | tr -d '\r' > "$OUT/files-layout.txt" || true
rash "tail -c 40000 '$FILES/log/runtime.log' 2>/dev/null" > "$OUT/runtime.log" 2>&1 || true
adb logcat -d 2>/dev/null | grep -aE 'OpenCode|TestRunner' | tail -400 > "$OUT/logcat.txt" || true
adb shell screencap -p > "$OUT/final-screen.png" 2>/dev/null || true

cat >> "$OUT/SUMMARY.txt" <<EOF
P8D_SUMMARY $(date -u +%FT%TZ)
gates_pass=$PASS gates_fail=$FAIL gates_skip=$SKIP
device=$(grep '^model=' "$OUT/device-facts.txt" 2>/dev/null) api=$(grep '^sdk=' "$OUT/device-facts.txt" 2>/dev/null | cut -d= -f2) abi=$(grep '^abi=' "$OUT/device-facts.txt" 2>/dev/null | cut -d= -f2)
EOF
log "=== REAL DEVICE SUMMARY ==="; cat "$OUT/SUMMARY.txt" | tee -a "$LOG"
log "bundle written to ./p8d-out/ - send it back (or paste p8d-out/SUMMARY.txt)"
RC=0
[ "$FAIL" -eq 0 ] || RC=1
exit "$RC"
