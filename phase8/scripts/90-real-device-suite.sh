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

rash() { printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'; }

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

# ---- R2: the toybox staging path on THIS API ---------------------------------
TB_B64=$(cd "$OUT" && mkdir -p tb && printf 'tb-a\n' > tb/a.txt && printf 'tb-b\n' > tb/b.txt && tar cz -C tb . | base64 -w0)
TB_OUT=$(rash "rm -rf '$FILES/tmp/tb' && mkdir -p '$FILES/tmp/tb' && echo '$TB_B64' | base64 -d | tar xz -C '$FILES/tmp/tb' && ls '$FILES/tmp/tb' | tr '\n' ' ' && echo toybox_rc=\$?" 2>/dev/null)
TB_API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if echo "$TB_OUT" | grep -q "toybox_rc=0" && echo "$TB_OUT" | grep -q "a.txt"; then
  rd TOYBOX 0 "base64|base64 -d|tar xz -C dir works on this real device api=$TB_API (closes the harness-staging half for this API; API-29 remains the untested half - see report)"
else
  rd TOYBOX 1 "toybox staging failed on api=$TB_API: $(echo "$TB_OUT" | head -2)"
fi

# ---- R3: cold start on real silicon ------------------------------------------
T0=$(date +%s)
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
# Unauthenticated by design: the server REFUSES with 401 until it has the
# Keystore password, so "any HTTP response on loopback" proves the server is
# up; the supervisor's own log is the authority on the HEALTHY transition.
COLD_OK=1; H=""
for _ in $(seq 1 150); do
  H=$(rash "'$FILES/bin/bun' -e \"const r=await fetch('http://127.0.0.1:$PORT/global/health');console.log(r.status)\" 2>/dev/null" | grep -o '^[0-9][0-9][0-9]' | head -1)
  if [ "$H" = "200" ] || [ "$H" = "401" ]; then COLD_OK=0; break; fi
  sleep 2
done
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

  # ---- PREFLIGHT (round 14) ---------------------------------------------------
  # Round 13 spent 10 minutes on a SILENT KEYPROBE failure: OpenCode reports
  # provider auth/model errors as empty completions (report S13), so anything
  # wrong with the credential or the model id is INVISIBLE at the gate level.
  # Ask the provider directly, from the phone, BEFORE spending the run.
  # It answers in ~2 s and prints the provider's own words. Two questions, not
  # one - because an "AQ." key can authenticate fine and still 404 on an older
  # model id, which is a completely different fix:
  #   1. is the KEY accepted?        (list models)
  #   2. does THIS MODEL exist for it? (fetch that model, and if not, name the
  #      generateContent-capable models the key actually has)
  PF_KB64=$(printf '%s' "$MODEL_KEY" | base64 -w0)
  rash "echo '$PF_KB64' | base64 -d > '$FILES/tmp/pfkey'" >/dev/null 2>&1
  if [ "$PROVIDER" = "google" ]; then
    PF_JS='const k=(await Bun.file(process.env.PF||"").text()).trim();
const M=(process.env.PFMODEL||"").trim();
const r=await fetch("https://generativelanguage.googleapis.com/v1beta/models?key="+k,{signal:AbortSignal.timeout(20000)});
const t=await r.text();
let n=0,names=[];
try{const j=JSON.parse(t);const ms=j.models||[];n=ms.length;
  names=ms.filter(m=>(m.supportedGenerationMethods||[]).includes("generateContent"))
          .map(m=>String(m.name||"").replace("models/",""));}catch{}
if(r.status!==200){
  console.log("P8KEYPREFLIGHT provider=google key=REJECTED http="+r.status+" body="+t.replace(/\s+/g," ").slice(0,220));
}else{
  const hit=names.includes(M);
  console.log("P8KEYPREFLIGHT provider=google key=OK http=200 models="+n+" model="+M+" modelUsable="+hit+
    (hit?"":" available="+names.slice(0,12).join(",")));
}'
  else
    PF_JS='const k=(await Bun.file(process.env.PF||"").text()).trim();
const r=await fetch("https://openrouter.ai/api/v1/auth/key",{headers:{Authorization:"Bearer "+k},signal:AbortSignal.timeout(20000)});
const t=await r.text();
console.log("P8KEYPREFLIGHT provider=openrouter http="+r.status+" body="+t.replace(/\s+/g," ").slice(0,220));'
  fi
  PF_B64=$(printf '%s' "$PF_JS" | base64 -w0)
  PF_OUT=$(rash "echo '$PF_B64' | base64 -d > '$FILES/tmp/pf.js'; PF='$FILES/tmp/pfkey' PFMODEL='$MODEL' '$FILES/bin/bun' '$FILES/tmp/pf.js' 2>&1; rm -f '$FILES/tmp/pf.js' '$FILES/tmp/pfkey'" 2>/dev/null | grep -a 'P8KEYPREFLIGHT' | head -1)
  echo "${PF_OUT:-P8KEYPREFLIGHT no output}" | tee -a "$LOG" > "$OUT/key-preflight.txt"
  case "$PF_OUT" in
    "")
      log "preflight produced no output (bun/egress problem) - continuing, the gates will show it" ;;
    *modelUsable=true*)
      log "preflight OK: the provider accepted this key AND serves model '$MODEL'" ;;
    *modelUsable=false*)
      # The key is fine; the model id is not available to it. Auto-recover
      # rather than burn the run: take the first generateContent model the key
      # itself reported (the AQ.-key rollout 404s several older ids).
      PF_PICK=$(printf '%s' "$PF_OUT" | sed -n 's/.* available=\([^, ]*\).*/\1/p')
      if [ -n "$PF_PICK" ]; then
        log "PREFLIGHT: model '$MODEL' is NOT available to this key; switching to '$PF_PICK' (reported by the key itself)"
        MODEL="$PF_PICK"
      else
        log "PREFLIGHT FAILED - model '$MODEL' is not available to this key and no alternative was reported."
        rec P8D_KEYPROBE SKIP "preflight: $PF_OUT"
        SKIP=$((SKIP+1))
        MODEL_KEY=""
      fi ;;
    *http=200*)
      log "preflight OK: the provider accepted this key" ;;
    *)
      log "PREFLIGHT FAILED - the provider REJECTED this key, so the live gates cannot pass."
      log "Fix the key and re-run; skipping the live model gates to save ~10 minutes."
      rec P8D_KEYPROBE SKIP "preflight: $PF_OUT"
      SKIP=$((SKIP+1))
      MODEL_KEY="" ;;
  esac
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
summarize_gates "KEYPROBE TOOL CLEANUP" "$OUT/live-verdicts.txt" "instrument rc=$LIVE_RC"
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
