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
#       the project directory (92-workspace-visibility.sh) at the path the app
#       itself displayed, plus the v3 checks that the location is on shared storage
#       and outside Android/data - i.e. that an ordinary file manager can open it,
#       not only adb. This is the check that the files are not sealed in app-private
#       storage (or in the Android/data corner that file managers cannot browse).
#
# Every wait below matches a screen by CONTENT (its tags AND the app's own words)
# and counts dumps it could not read (gate UI_DUMP), because the v2 run reported a
# first-run failure on a phone that was sitting on the projects screen: a verdict
# has to distinguish "the app is not there" from "the harness could not see".
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

# WINDOWS / GIT BASH - the failure that produced the v2 "the app window never
# appeared" verdict on a phone that was showing the app.
#
# MSYS (Git for Windows) rewrites arguments that look like POSIX paths when it
# hands them to a native binary. `adb` is a native binary, so
#     adb shell uiautomator dump /sdcard/p10d-ui.xml
# became
#     adb shell uiautomator dump 'C:/Program Files/Git/sdcard/p10d-ui.xml'
# on the device side, and the dump the driver then `cat`-ed was the device's
# "No such file or directory" text. Every UI wait timed out, and the run reported
# the app as broken while dumpsys showed the app's window in front. The bundle the
# owner sent contained exactly that (p10d-out/ui/*.xml, 72 bytes each).
#
# Both variables are the documented opt-outs and are inert on Linux/macOS:
#   MSYS_NO_PATHCONV   Git for Windows
#   MSYS2_ARG_CONV_EXCL  MSYS2/Cygwin (and Git Bash in some versions)
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    HOST_SHELL="windows-msys"
    ;;
  *) HOST_SHELL="posix" ;;
esac
# Self-test seam, used only by phase10/scripts/test-90-real-device.sh: on a POSIX host
# the Windows branch of host_path() can never run, and that branch is exactly where the
# owner's run died. Forcing it here lets the self-test exercise it with a cygpath stub;
# nothing sets this variable unless a test exports it.
case "${P10D_FORCE_HOST_SHELL:-}" in
  windows-msys|posix) HOST_SHELL="$P10D_FORCE_HOST_SHELL" ;;
esac

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"

# ---- host paths handed to NATIVE binaries (python) ---------------------------
#
# THE 2026-09-21 OWNER FAILURE. On Git Bash the repo lives at a POSIX path
# (/p/OpenCodeGUI/...), and that is what $DIR is. Native Windows Python does not
# understand it: a leading "/" means "root of the CURRENT DRIVE", so the reader was
# invoked as
#     python.exe  /p/OpenCodeGUI/phase10/scripts/p10d-ui.py ...
# and Windows Python looked for
#     P:\p\OpenCodeGUI\phase10\scripts\p10d-ui.py
# which does not exist. In that run:
#   * check-apk.py died loudly (its stderr was kept): artifact-report.txt shows
#     "can't open file 'P:/p/OpenCodeGUI/phase10/scripts/check-apk.py'";
#   * p10d-ui.py died SILENTLY, because ui() piped its stderr to /dev/null - so every
#     "on screen:" came back empty, no needle ever matched, the welcome wait timed out
#     for 300s on a phone that was showing its welcome screen, and the run blamed the
#     app while the run's own screenshots proved otherwise.
#
# Every HOST path handed to Python is converted first. cygpath is the accurate
# converter and ships with Git Bash; the sed fallback covers the /<drive>/ mount form
# for hosts that lack it. DEVICE paths are never touched - they stay POSIX for adb,
# and MSYS_NO_PATHCONV (above) stops MSYS from rewriting those.
# Two forms, because neither is right everywhere:
#   -m (mixed: P:/open/app/...) has FORWARD slashes, which Windows accepts and which
#      no shell layer can turn into an escape sequence or a UNC prefix;
#   -w (P:\open\app\...) is the form some Windows-only tools print and expect.
# The 2026-09-21 bundle (run 3) shows why this is not academic: with -w the reader was
# handed P:\OPEN APP\phase10\scripts\p10d-ui.py and Python answered "No such file or
# directory" - a path that looks right in the log and is not the file Python opens.
# So the driver does not guess: it PROBES (see choose_reader) and keeps what works.
win_path() { # $1 = POSIX host path, $2 = cygpath flag
  case "${HOST_SHELL:-}" in
    windows-msys)
      if command -v cygpath >/dev/null 2>&1; then cygpath "$2" -- "$1"
      else printf '%s' "$1" | sed -E 's#^/([A-Za-z])/#\1:/#'
      fi ;;
    *) printf '%s' "$1" ;;
  esac
}
host_path_m() { win_path "$1" -m; }
host_path_w() { win_path "$1" -w; }
# Compatibility: everything that used host_path() wants a path Python can open, and
# the mixed form is the safer default on Windows.
host_path() { host_path_m "$1"; }

# A path typed by a human in a Windows shell ("P:\OPEN APP\phase10\signing\x.apk",
# or one with backslashes and no drive) must be turned into the POSIX form before this
# script uses it: `[ -f ]`, sha256sum and every other MSYS tool read the POSIX form,
# while adb.exe would take either. Without this the footer prints "sha256=" (empty)
# and the APK "is not found" although the human can see it in Explorer.
norm_host_arg() {
  case "${HOST_SHELL:-}" in
    windows-msys)
      case "$1" in
        [A-Za-z]:[\\/]*)
          if command -v cygpath >/dev/null 2>&1; then cygpath -u -- "$1"
          else
            d=$(printf '%s' "$1" | cut -c1 | tr 'A-Z' 'a-z')
            rest=$(printf '%s' "$1" | cut -c3- | tr '\\' '/')
            printf '/%s/%s' "$d" "${rest#/}"
          fi ;;
        *\\*) printf '%s' "$1" | tr '\\' '/' ;;
        *) printf '%s' "$1" ;;
      esac ;;
    *) printf '%s' "$1" ;;
  esac
}

OUT="p10d-out"
APK=""
CERT_EXPECT=""
SKIP_LIVE=0
SCALE=1
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$(norm_host_arg "${2:-}")"; shift 2 ;;
    --cert-sha256) CERT_EXPECT="$(printf '%s' "${2:-}" | tr -d ' :' | tr 'A-Z' 'a-z')"; shift 2 ;;
    --out) OUT="$(norm_host_arg "${2:-}")"; shift 2 ;;
    --skip-live) SKIP_LIVE=1; shift ;;
    --timeout-scale) SCALE="${2:-1}"; shift 2 ;;
    -h|--help) sed -n '2,60p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done
mkdir -p "$OUT" "$OUT/screenshots" "$OUT/ui"
# The absolute form, because two things are handed to a CHILD process that may run in
# another directory: the visibility driver (invoked after a `cd` to $DIR, so a relative
# --out would land inside phase10/) and anything Python opens by path. Relative paths
# are still used for the driver's own files, which stay relative to the caller's cwd.
OUT_ABS="$(cd "$OUT" && pwd)"
LOG="$OUT/run.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }
step() { echo | tee -a "$LOG"; echo "===== $* =====" | tee -a "$LOG"; }

PASS=0; FAIL=0; SKIP=0
# Every wait's budget, scaled by --timeout-scale. Kept in one place because the
# self-test runs whole scenarios with a fractional scale (0.02) to exercise the
# failure paths in seconds instead of minutes - and `$((300 * 0.02))` is a bash
# arithmetic error, so the multiplication is done where fractions exist.
tmo() { awk -v s="$SCALE" -v t="$1" 'BEGIN { v = s * t; if (v < 3) v = 3; printf "%d", v }'; }
: > "$OUT/SUMMARY.txt"
: > "$OUT/DIAGNOSIS.txt"
rec() { echo "$1 $2${3:+ :: $3}" >> "$OUT/SUMMARY.txt"; log "$1 $2${3:+ :: $3}"; }
rd() { case "$2" in 0) PASS=$((PASS+1)); rec "P10D_$1" PASS "$3";; 7) SKIP=$((SKIP+1)); rec "P10D_$1" SKIP "$3";; *) FAIL=$((FAIL+1)); rec "P10D_$1" FAIL "$3";; esac; }
diag() { echo "$1" | tee -a "$OUT/DIAGNOSIS.txt" >> "$LOG"; }

write_footer() { # $1 = screenshots count, $2 = blank count ("" for an early stop)
  {
    echo "phase10 real-device verification of the SIGNED build $(date -u +%FT%TZ)"
    echo "screenshots=${1:-0} blank=${2:-0}"
    echo "-- model availability marker (closes the x86_64-only live-tool-call carry-forward when 1)"
    cat "$OUT/p10d-model-lines.txt" 2>/dev/null || true
    # The third bundle printed `apk=app-release-signed.apk sha256=` - an EMPTY field, which
    # reads as "this file has no hash" and is really "the host could not read that path"
    # (a Windows-form --apk the MSYS tools cannot open, or a file that is not there). An
    # empty value in a bundle is the kind of self-contradicting evidence this whole section
    # is about, so it says which it is; norm_host_arg() above already removes the first
    # cause for arguments a human typed.
    APK_SHA="$(sha256sum "$APK" 2>/dev/null | awk '{print $1}')"
    echo "apk=$(basename "$APK") sha256=${APK_SHA:-<unreadable at $APK>}"
    echo "pass=$PASS fail=$FAIL skip=$SKIP"
  } >> "$OUT/SUMMARY.txt"
}

# Stop early - but with a bundle, not with a half-written one. Used when the
# harness itself cannot see the screen (see R0.5): continuing would produce a pile
# of "the app never ..." verdicts that are really the harness talking.
bail() { # $1 = one-line reason
  echo
  echo "STOPPING: $1"
  echo "See $OUT/DIAGNOSIS.txt; the bundle is complete enough to act on."
  write_footer "${NSHOTS:-0}" "${BLANK:-0}"
  cat "$OUT/SUMMARY.txt" | tee -a "$LOG"
  echo "Bundle: $OUT/  (stopped early: $1)"
  exit 3
}

# Credentials never reach the bundle: this filter runs over everything written to
# p10d-out, the same rule the Phase 8 suite adopted after a key was published.
redact() {
  sed -E -e 's/(key=)AQ\.[A-Za-z0-9_-]+/\1AQ.<REDACTED>/g' \
         -e 's/AQ\.[A-Za-z0-9_-]{12,}/AQ.<REDACTED>/g' \
         -e 's/AIza[A-Za-z0-9_-]{20,}/AIza<REDACTED>/g' \
         -e 's/sk-or-[A-Za-z0-9_-]{10,}/sk-or-<REDACTED>/g' \
         -e 's/(Bearer )[A-Za-z0-9._-]{12,}/\1<REDACTED>/g'
}

# The three helpers, in BOTH forms. The _SRC names are the real files as this script
# holds them (POSIX, what bash and cp can open); the bare names are what gets handed to
# Python, and they start as the converted form because that is the safer default on
# Windows. R0.5 then replaces them with the form that actually opened (choose_reader):
# handing a pre-converted path to a probe would test the wrong thing twice and, worse,
# would make the POSIX probe "succeed" through the converted form and then leave every
# later read on the unconverted one.
UI_PY_SRC="$DIR/scripts/p10d-ui.py"
PNG_PY_SRC="$DIR/scripts/p10d-png.py"
APK_PY_SRC="$DIR/scripts/check-apk.py"
UI_PY="$(host_path "$UI_PY_SRC")"
PNG_PY="$(host_path "$PNG_PY_SRC")"
APK_PY="$(host_path "$APK_PY_SRC")"

# Python is load-bearing: the accessibility reader (p10d-ui.py) is what turns a dump
# into "what is on screen", the screenshot checker decides whether a frame is a real
# screen, and check-apk.py inspects the APK. On Windows `python3` is often the
# Microsoft Store STUB: it exists on PATH, prints "Python was not found" and exits
# non-zero - so `command -v python3` is not a test of anything. The v2 bundle shows
# the consequence: `P10D_ARTIFACT FAIL :: check-apk findings: ` (empty, because the
# inspector never ran) and an empty "on screen:" at every wait, because the reader
# never ran either. Resolve an interpreter that ANSWERS, or say so and stop.
PY=""
for cand in python3 python py; do
  command -v "$cand" >/dev/null 2>&1 || continue
  if [ "$cand" = "py" ]; then
    "$cand" -3 -c 'print(1)' >/dev/null 2>&1 && { PY="$cand -3"; break; } || continue
  fi
  "$cand" -c 'print(1)' >/dev/null 2>&1 && { PY="$cand"; break; }
done

command -v adb >/dev/null 2>&1 || { echo "FATAL: adb not on PATH (install platform-tools)"; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "FATAL: no device visible to adb (plug the phone in, enable USB debugging, accept the prompt)"; exit 2; }
PKG="io.github.mcyber12.opencode"
SHOT_N=0
SHOT_UNVERIFIED=0
LAST_DUMP=""
# How many dumps came back unusable. A run where this is non-zero and the app was
# demonstrably on screen is a harness problem, not a product problem, and the
# summary has to say which one it was.
DUMP_FAILURES=0
LAST_DUMP_MODE=""
# Swipe geometry, refreshed from `wm size` once the device is reachable. Defaults
# are a 1080x1920 phone; nothing here is a verdict, it is only how far the driver
# scrolls when it has to go looking for a control.
SWIPE_FROM_X=540; SWIPE_FROM_Y=1600; SWIPE_TO_X=540; SWIPE_TO_Y=900
set_swipe_geometry() {
  local size w h
  size=$(adb shell wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1)
  [ -n "$size" ] || return 0
  w=${size%x*}; h=${size#*x}
  case "$w$h" in *[!0-9]*|"") return 0 ;; esac
  SWIPE_FROM_X=$(( w / 2 )); SWIPE_TO_X=$(( w / 2 ))
  SWIPE_FROM_Y=$(( h * 4 / 5 )); SWIPE_TO_Y=$(( h * 9 / 20 ))
}

# ------------------------------------------------- screen identity, by CONTENT --
#
# Phase 10 continuation v3. What a screen IS is what it shows, so every wait here
# matches the app's own words as well as its test tags. Both, not either:
#
#  * tags (Compose test tags exposed as resource ids, `testTagsAsResourceId`) are
#    the stable address, but they are a *platform* feature - an OEM build, a
#    different Android version or a merged semantics node can omit them, and the
#    first real-device run failed exactly that way: the phone sat on the projects
#    screen while every tag-based wait timed out, so R4 reported the first run as
#    failed and everything downstream skipped. The dump even had the text.
#  * text can change with a copy edit, which is why the tags are still there.
#
# The needles are literal strings from `app/src/main/res/values/strings.xml`, so a
# rename there has to be reflected here - and a rename is exactly the moment a
# hard-coded locator should stop matching rather than silently locate the wrong
# screen.
NEEDLE_WELCOME='welcome_screen|welcome_continue|Continue|Settings and diagnostics'
NEEDLE_PROJECTS='projects_screen|project_list|project_name_input|project_create|New project|Create project|Create New Project|Project name'
NEEDLE_CHAT='chat_screen|composer_input|composer_send|Start a conversation|Message the agent'
NEEDLE_FILES='files_screen|files_list|files_location|Where these files are|files_storage_mode'
# v4 item 4: the first-run workspace step. It is what a first run now reaches
# instead of the project list, so the driver has to know it by name and by copy.
NEEDLE_ONBOARDING='onboarding_workspace|Where your files will live|Use this folder as workspace'
# v4: the Settings sections the v4 items live in (workspace switch, provider search,
# starred models). Read by text, because that is what a user reads.
NEEDLE_SETTINGS='settings_screen|settings_list|Agent runtime|Workspace|Model'
# The app's own copy is not the only thing on screen (system dialogs, IME, launcher),
# so these are only ever used as additional needles - never as the sole signal.

# ---------------------------------------------------------------- UI plumbing --
#
# Every UI interaction goes through these five functions. They are deliberately
# chatty: each one can explain itself, and the driver logs the reason for every
# negative answer instead of collapsing them all into "not found".

# Is this file actually an accessibility dump? `adb exec-out cat` of a path the
# device does not have returns a one-line error instead, and a "dump" that is an
# error message is the worst possible evidence: it parses as nothing, every wait
# times out, and the run blames the app. v2 wrote exactly that file into the
# bundle (p10d-out/ui/*.xml, 72 bytes of "No such file or directory").
looks_like_xml() {
  local f="${1:-}"
  [ -s "$f" ] || return 1
  head -c 5 "$f" 2>/dev/null | grep -q '<?xml' && return 0
  return 1
}

# Turn "the dump could not be read" into a sentence that names the cause, because
# the two causes have nothing in common: the DEVICE could not be dumped (its
# problem) or the HOST shell mangled the device path (ours). Only the second one
# produced the v2 bundle.
dump_failure_reason() {
  local f="${1:-}" first
  [ -s "$f" ] || { echo "nothing came back at all: uiautomator wrote no dump on the device (its own error is logged above) and the host read an empty file"; return 0; }
  first=$(head -c 200 "$f" | tr -d '\r' | head -1)
  case "$first" in
    *"Program Files"*|*"Git/sdcard"*|*[A-Za-z]:/*)
      echo "the HOST shell rewrote the device path into a Windows path ('$first') - this is the Git-Bash/MSYS path conversion, not the phone. Re-run from a POSIX shell, or use the bundled MSYS_NO_PATHCONV=1 guard this script sets for itself." ;;
    *"No such file or directory"*)
      echo "the device never had that file: uiautomator did not write the dump ('$first')" ;;
    *"Permission denied"*)
      echo "the device refused to read the dump path ('$first')" ;;
    *) echo "unrecognized dump output ('$first')" ;;
  esac
}

ui_dump() { # $1 = tag -> $OUT/ui/ui-<tag>.xml ; returns non-zero when unusable
  local tag="$1" attempt mode src out rc
  # The tag becomes a FILENAME. Taps are named after their needle, and a needle can
  # be a path ("tap-/storage/emulated/0/Documents/OpenCode" - the v6.1 restore tap):
  # slashes there made every write fail, which the run then reported as "the screen
  # could not be seen" about a screen it was reading fine. Sanitize, don't trust.
  tag=$(printf '%s' "$tag" | tr '/ :*?"<>|\\' '__________' | cut -c1-120)
  # Three attempts, and the two things that make `uiautomator dump` fail on a real
  # phone are both worked around rather than reported as "the screen is not there":
  #
  #  * "ERROR: could not get idle state" - the UI never goes idle (a spinner, an
  #    animation, a Compose recomposition loop). `--compressed` uses a different
  #    dump path and usually succeeds where the plain one refuses.
  #  * a dump written but unreadable (scoped storage on /sdcard) - /data/local/tmp
  #    is the directory the tool itself defaults to and is always writable by the
  #    shell user.
  for attempt in 1 2 3; do
    for mode in "" "--compressed"; do
      for src in /sdcard/p10d-ui.xml /data/local/tmp/p10d-ui.xml; do
        adb shell rm -f "$src" >/dev/null 2>&1
        out=$(adb shell uiautomator dump $mode "$src" 2>&1 | tr -d '\r')
        sleep 0.5
        if adb exec-out cat "$src" > "$OUT/ui/ui-$tag.xml" 2>/dev/null && looks_like_xml "$OUT/ui/ui-$tag.xml"; then
          LAST_DUMP="$OUT/ui/ui-$tag.xml"
          LAST_DUMP_MODE="$mode${mode:+ }$src"
          return 0
        fi
        out=$(printf '%s' "$out" | grep -vE '^$' | tail -1)
      done
    done
    # v1 discarded this string entirely; it is the difference between "no app
    # screen" and "uiautomator could not dump at all".
    diag "ui_dump($tag) attempt $attempt failed: ${out:-<no output>} (device: $(screen_state))"
    diag "  why: $(dump_failure_reason "$OUT/ui/ui-$tag.xml")"
    sleep 2
  done
  LAST_DUMP=""
  DUMP_FAILURES=$((DUMP_FAILURES + 1))
  return 1
}

# The reader's stderr used to go to /dev/null, which is why the owner's run could not
# be explained from its own bundle: the one line that said WHY nothing was on screen
# was thrown away. It is kept now, counted, and quoted by the harness gates.
READER_ERR="$OUT/reader-stderr.txt"
READER_FAILURES=0
# Filled in by choose_reader() at R0.5: which way of handing paths to Python actually
# works on THIS host, and what to pass as the reader script.
READER_CMD=""
READER_VIA=""
MAP_MODE="posix"
TMP_READER=""
TMP_READER_WIN=""
TMP_DUMP=""
TMP_DUMP_WIN=""
TMP_DIR=""
TMP_DIR_WIN=""
MAP_SEQ=0

# EVERYTHING the interpreter is handed - not just the reader - has to use the form the
# probe proved works. The screenshot validator and the APK inspector are Python too, and
# on the owner's host a wrong form made them silent: an empty verdict from p10d-png.py
# counted as "not blank" (so the screenshots gate went green with nothing verified) and
# an empty check-apk output read like "the APK is broken". Both now take their paths from
# the same decision.
py_script() { # $1 = POSIX path of a script -> as $PY must receive it
  case "$MAP_MODE" in
    posix) printf '%s' "$1" ;;
    m) host_path_m "$1" ;;
    w) host_path_w "$1" ;;
    tmp)
      # This interpreter could not open the checkout, but it did open the copy the probe
      # made, so every script it runs is a copy in that same directory.
      local b; b="$(basename "$1")"
      cp -f "$1" "$TMP_DIR/$b" 2>/dev/null || true
      printf '%s' "$TMP_DIR_WIN/$b" ;;
    *) printf '%s' "$1" ;;
  esac
}
map_file() { # $1 = POSIX path of a data file -> as $PY must receive it
  case "$MAP_MODE" in
    posix) printf '%s' "$1" ;;
    m) host_path_m "$1" ;;
    w) host_path_w "$1" ;;
    tmp)
      local b; MAP_SEQ=$((MAP_SEQ+1)); b="$(printf '%03d-%s' "$MAP_SEQ" "$(basename "$1")")"
      cp -f "$1" "$TMP_DIR/$b" 2>/dev/null || true
      printf '%s' "$TMP_DIR_WIN/$b" ;;
    *) printf '%s' "$1" ;;
  esac
}

map_dump() { # $1 = POSIX host path of the dump -> the form THIS host's Python opens
  # tmp mode is a PATH, not the file: `$TMP_DIR/dump.xml`. A copy made once would hand
  # every later caller the bytes of whatever screen was current at that moment, which
  # is not a theory - it is what the v4 stages did on the temp-dir host while passing
  # everywhere else. `screen_label` and the inline dump scripts used to map the path
  # without copying, so they read a stale screen and reported things like "the quick
  # switch lists nothing" about a menu that was on screen with the model in it. The
  # copy belongs here, in the one function that turns a path into the form this host's
  # Python can open, so no caller has to remember it.
  case "$MAP_MODE" in
    posix) printf '%s' "$1" ;;
    m) host_path_m "$1" ;;
    w) host_path_w "$1" ;;
    tmp)
      cp -f "$1" "$TMP_DUMP" 2>/dev/null || true
      printf '%s' "$TMP_DUMP_WIN" ;;
    *) printf '%s' "$1" ;;
  esac
}

ui() { # shellcheck disable=SC2086
  local out rc d
  # One mapping, always fresh: see map_dump.
  d="$(map_dump "$LAST_DUMP")"
  out=$($PY "$READER_CMD" "$d" "$@" 2>>"$READER_ERR"); rc=$?
  # A reader FAILURE is "the reader could not answer": exit >= 2 with nothing on
  # stdout (2 = the interpreter could not open the script, 3 = the dump would not
  # parse). Exit 1 is a normal "not found" - `ui has` prints nothing on purpose and
  # must not be counted as a broken reader.
  if [ -z "$out" ] && [ "${rc:-0}" -ge 2 ]; then READER_FAILURES=$((READER_FAILURES + 1)); fi
  printf '%s' "$out"
  return $rc; }
ui_has() { ui has "$1"; }

first_screen_path() { # newest dump -> the workspace path the page header shows
  # Prefers the node tagged projects_workspace_path; falls back to the first
  # absolute path on screen for phones whose dumps carry no resource ids.
  $PY - "$(map_dump "$LAST_DUMP")" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for raw in re.findall(r'<node\b[^>]*>', xml):
    if 'projects_workspace_path' in raw:
        m = re.search(r'text="(/[^"<>]{3,})"', raw)
        if m:
            print(m.group(1)); raise SystemExit
paths = [c for c in re.findall(r'(?:text|content-desc)="(/[^"<>]{3,})"', xml)
         if not c.startswith(("/data/data/", "/data/user/0/"))]
print(paths[0] if paths else "")
PY
}
# Any of these on screen: the content fallback for a check that decides what the
# driver does next (a tag-only check would send a working app down the wrong path
# on a device whose dumps carry no resource ids - the v3 first-run failure).
ui_has_any() { local n; for n in "$@"; do ui has "$n" && return 0; done; return 1; }

# The sentence a verdict needs when the accessibility channel - not the app - was the
# problem. Phase 10 continuation v3: the v2 run reported "the project screen was never
# reached" on a phone that was displaying it, and nothing in the bundle explained why
# the evidence disagreed with itself. Any verdict that could be a harness failure
# carries this note, so a self-contradicting report cannot be produced again.
dump_caveat() {
  [ "${DUMP_FAILURES:-0}" = 0 ] && [ "${READER_FAILURES:-0}" = 0 ] && return 0
  # One line: this is appended to a SUMMARY verdict, and a verdict must stay one
  # parseable line (the summary is read by machine and by eye).
  if [ "${DUMP_FAILURES:-0}" != 0 ]; then
    printf ' NOTE: the accessibility dump was unreadable %s time(s) in this run, so the window/activity state above came from dumpsys rather than the screen and this verdict may describe the harness rather than the app (see UI_DUMP, DIAGNOSIS.txt).' "$DUMP_FAILURES"
  else
    printf ' NOTE: the accessibility reader returned nothing %s time(s) in this run, so a screen that really was on the phone may have read as empty - check reader-stderr.txt before believing this verdict.' "$READER_FAILURES"
  fi
}
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
  local f w win focus
  f=$(adb shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m1 'mWakefulness' | sed 's/^ *//')
  win=$(adb shell dumpsys window 2>/dev/null | tr -d '\r')
  w=$(printf '%s' "$win" | grep -m1 -E 'mDreamingLockscreen|mShowingLockscreen' | sed 's/^ *//')
  focus=$(printf '%s' "$win" | grep -m1 -E 'mCurrentFocus|mFocusedApp' | sed 's/^ *//')
  # Android 12+ dropped mShowingLockscreen from many builds, so the field alone is
  # not a lock detector any more. The window that HAS FOCUS is: while the keyguard
  # is up, the focused window is the keyguard (or SystemUI's status bar), not the app.
  case "$focus" in
    *Keyguard*|*keyguard*|*StatusBar*|*Systemui*|*SystemUI*) w="${w:-} keyguard-focused" ;;
  esac
  echo "${f:-wakefulness=?} ${w:-keyguard=?} ${focus:-focus=?}"
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
  verdict=$($PY "$PNG_PY" "$(map_file "$OUT/screenshots/$name")" 2>/dev/null | tail -1)
  echo "$(date -u +%FT%TZ) $name :: $verdict" >> "$OUT/screenshots.log"
  case "$verdict" in
    *"screen=NO"*) diag "screenshot $name looks blank/off: $verdict"; return 1 ;;
    "") # The validator said nothing at all. That is not "the screen is fine": it is the
        # validator not running (a wrong path form would do exactly this), so it is named
        # here and counted in the screenshots gate, never silently counted as a pass.
       SHOT_UNVERIFIED=$((SHOT_UNVERIFIED+1))
       diag "screenshot $name could not be validated (p10d-png.py returned nothing; see python path mode in run.log) - NOT counted as a real screenshot"
       return 1 ;;
  esac
  return 0
}

tap_at() { adb shell input tap "$1" "$2" >/dev/null 2>&1; sleep 1; }

# Is this string a coordinate pair from `ui find`, or is it a diagnostic line?
# This distinction is load-bearing: `ui find` prints "FOUND_NOT_TAPPABLE ..." and
# exits 1 when the node exists but is disabled, so a driver that only checks for a
# non-empty string will "tap" a disabled control, report success, and hand the
# caller a PASS for something that never happened.
is_centre() { case "$1" in [0-9]*" "[0-9]*) return 0 ;; *) return 1 ;; esac; }

tap() { # $1 = tag|text|label ; tolerant: scrolls once, and says why it could not tap
  local needle="$1" centre state rc
  ui_dump "tap-$needle" || { diag "tap($needle): no UI dump available"; return 1; }
  centre=$(ui find "$needle")
  rc=$?
  # rc 4 means the only match is marked shown="false" by the platform: usable
  # coordinates, but the log should say the node was not on screen. The tap is still
  # made - refusing here is how a driver reports a working app as broken.
  [ "$rc" = 4 ] && log "tap($needle): the matched node is marked shown=false; tapping its bounds anyway ($centre)"
  if ! is_centre "$centre"; then
    state=$(ui state "$needle")
    diag "tap($needle): not tappable (${centre:-no matching node}) state=${state:-none}"
    # A real user would scroll towards what they want before giving up.
    adb shell input swipe "$SWIPE_FROM_X" "$SWIPE_FROM_Y" "$SWIPE_TO_X" "$SWIPE_TO_Y" 300 >/dev/null 2>&1
    sleep 1
    ui_dump "tap2-$needle" || return 1
    centre=$(ui find "$needle")
    if ! is_centre "$centre"; then
      diag "tap($needle): still not tappable after one scroll (${centre:-no matching node})"
      return 1
    fi
  fi
  tap_at $centre
  return 0
}

tap_any() { # $@ = needles, most specific first; taps the first one that is there
  # A tag can be missing from a dump while the control is plainly on screen, so
  # every tap has a content fallback. Whichever needle worked is logged: a silent
  # fallback would hide the day the tags stop being exposed.
  local needle "worked="
  for needle in "$@"; do
    if tap "$needle" 2>/dev/null; then
      worked="$needle"
      break
    fi
  done
  if [ -n "$worked" ]; then
    [ "$worked" = "$1" ] || log "tap_any: '$1' was not tappable; used '$worked' instead"
    return 0
  fi
  diag "tap_any: none of [$*] was tappable (last dump: ${LAST_DUMP:-<none>})"
  return 1
}

return_to_conversation() { # bounded: press BACK until a conversation surface is visible
  # One BACK press is not enough, and the reason is v4: entering a provider key can
  # go Settings -> the provider dialog -> (Save) back to Settings, so a single press
  # lands two levels short and every later wait then fails on the wrong screen. The
  # fake phone's own transition table (settings-key -> settings-openr -> chat) is the
  # proof that the depth is real, not hypothetical.
  #
  # The loop stops as soon as the CONVERSATION is on screen, so it never presses BACK
  # on the screen the run needs to be on (the v3 bug that turned an otherwise clean run
  # into a FAIL: an unconditional BACK on the chat exits the app).
  local i
  for i in 1 2 3; do
    ui_dump "return-to-chat-$i" >/dev/null 2>&1
    if ui_has_any "chat_screen" "composer_input" "Start a conversation" "Message the agent"; then
      [ "$i" = 1 ] || log "return_to_conversation: back on the conversation surface after $i BACK presses"
      return 0
    fi
    adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    sleep 1
  done
  ui_dump "return-to-chat-final" >/dev/null 2>&1
  if ui_has_any "chat_screen" "composer_input" "Start a conversation" "Message the agent"; then
    return 0
  fi
  diag "return_to_conversation: the conversation was not reachable after 3 BACK presses (see ui/ui-leave-settings-final.xml)"
  return 1
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
    rd ANR 1 "the app showed an 'isn't responding' dialog during the run"
    return 1
  fi
  if ui has "keeps stopping" || ui has "has stopped"; then
    diag "CRASH dialog on screen: $(ui texts 8 | tr '\n' '|')"
    rd CRASH_DIALOG 1 "Android reported that the app stopped"
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
      # Split on `|` only. `${needles//|/ }` plus word splitting also broke on the
      # spaces INSIDE a needle, so "Start a conversation" became three needles, the
      # first of which ("Start") matches almost any screen - a wait that reports
      # success for the wrong reason.
      while IFS= read -r needle; do
        [ -n "$needle" ] || continue
        if ui has "$needle"; then
          elapsed=$(( $(date +%s) - start ))
          log "wait($name): found '$needle' after ${elapsed}s"
          return 0
        fi
      done <<< "${needles//|/$'\n'}"
    fi
    elapsed=$(( $(date +%s) - start ))
    if [ "$elapsed" -ge "$timeout" ]; then
      diag "wait($name) TIMED OUT after ${elapsed}s"
      diag "  waiting for: $needles"
      diag "  device: $(screen_state)"
      diag "  foreground: $(foreground)"
      diag "  last dump: ${LAST_DUMP:-<none>} $(ui_nodes)"
      # What the DUMP itself contains, so one line answers the question the v2
      # failure left open: is this "the app never got there" or "the dump cannot
      # describe the app"? `attrs` lists which attributes any node carries - a dump
      # that is this app's screen but has no resource-id is a device where the
      # Compose tags never reached the accessibility tree (the driver then has to
      # match on the app's own words, which it does).
      diag "  dump attrs: $(ui attrs 2>/dev/null | head -1)"
      if ui package "$PKG" 2>/dev/null; then
        diag "  the dump IS this app's screen ($PKG nodes present) - so a needle above is"
        diag "  missing from it, which is a locator problem rather than an app problem"
      fi
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
local_locked() { screen_state | grep -qiE 'DreamingLockscreen=true|mShowingLockscreen=true|keyguard-focused|mWakefulness=Asleep|mWakefulness=Dozing'; }
if local_locked; then
  log "device reports $(screen_state); dismissing the keyguard (a human would swipe up)"
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell input swipe 540 1800 540 400 200 >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true
  sleep 2
fi
adb shell svc power stayon true >/dev/null 2>&1 || true
# Animations OFF, and this is load-bearing rather than cosmetic: `uiautomator dump`
# waits for the window to go idle, and the first-run screen shows an indeterminate
# CircularProgressIndicator while the runtime comes up. A permanently animating
# window means the dump either blocks for ~15s or fails with "could not get idle
# state" - which is indistinguishable, in a log that only records strings, from
# "the app never showed a screen". CI's own driver has set these to 0 since Phase 4
# (00-run-phase10.sh); v1 here did not, which is the most likely reason its counts
# and timings looked nothing like the emulator's.
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

# ---- the host's own checkout, and how to hand paths to its Python --------------
#
# R0.4 (below) exists because of the owner's third bundle (2026-09-21, "OPEN APP"):
# the run reported
#     can't open file 'P:\OPEN APP\phase10\scripts\p10d-ui.py': [Errno 2]
# for a reader that is committed next to this script - while bash, running the very
# same directory, was fine. A checkout assembled file by file (the GitHub web UI
# leaves the helpers behind) has that signature, and it is indistinguishable from a
# path bug unless the driver checks the files it needs with the tools it has.
#
# R0.5 then PROBES the ways of giving Python a path, instead of assuming one:
#   posix  the POSIX path (right when the host's Python is an MSYS/Cygwin one)
#   -m     P:/open/app/...    (forward slashes - no escaping, no UNC surprise)
#   -w     P:\open\app\...   (what Windows tools print)
#   tmp    reader + dump copied to the host temp dir: answers "is it the path or the
#          file?" and rescues a checkout on a drive Python cannot follow
# The first form that returns a node count wins, and the run says which one it used.
HELPER_FILES="scripts/p10d-ui.py scripts/p10d-png.py scripts/check-apk.py scripts/92-workspace-visibility.sh"

missing_helpers() { # prints the missing ones, one per line, empty when complete
  local rel
  for rel in $HELPER_FILES; do
    [ -f "$DIR/$rel" ] || printf 'phase10/%s\n' "$rel"
  done
}

PROBE_LOG=""
PROBE_TRIED=""
probe_reader() { # $1 = label, $2 = script as handed to python, $3 = dump as handed
  local out rc
  PROBE_TRIED="${PROBE_TRIED:+$PROBE_TRIED, }$1"
  out=$($PY "$2" "$3" nodes 2>"$OUT/reader-probe-err.txt"); rc=$?
  if [ "$rc" = 0 ] && [ -n "$out" ]; then
    PROBE_LOG="$PROBE_LOG  probe $1: OK ($out)
"
    printf '%s' "$PROBE_LOG" >> "$OUT/reader-probe.txt"
    return 0
  fi
  PROBE_LOG="$PROBE_LOG  probe $1: rc=$rc out='$(printf '%s' "$out" | head -c 60)' err='$(head -1 "$OUT/reader-probe-err.txt" 2>/dev/null | tr -d '\r' | head -c 200)'
"
  return 1
}

choose_reader() { # sets READER_CMD / MAP_MODE / READER_VIA; returns 1 if nothing works
  local dump="$LAST_DUMP"
  PROBE_TRIED=""; PROBE_LOG=""
  : > "$OUT/reader-probe.txt"
  # 1. POSIX, exactly as the script holds it (the _SRC path, never a converted one)
  if probe_reader "POSIX path" "$UI_PY_SRC" "$dump"; then
    READER_CMD="$UI_PY_SRC"; MAP_MODE=posix
    READER_VIA="the POSIX path (this host's Python understands it)"
    return 0
  fi
  # 2. / 3. the two Windows forms
  if command -v cygpath >/dev/null 2>&1; then
    if probe_reader "cygpath -m" "$(host_path_m "$UI_PY_SRC")" "$(host_path_m "$dump")"; then
      READER_CMD="$(host_path_m "$UI_PY_SRC")"; MAP_MODE=m
      READER_VIA="cygpath -m (forward-slash Windows path)"
      return 0
    fi
    if probe_reader "cygpath -w" "$(host_path_w "$UI_PY_SRC")" "$(host_path_w "$dump")"; then
      READER_CMD="$(host_path_w "$UI_PY_SRC")"; MAP_MODE=w
      READER_VIA="cygpath -w (backslash Windows path)"
      return 0
    fi
  else
    PROBE_TRIED="$PROBE_TRIED, cygpath -m/-w (not installed)"
  fi
  # 4. copies in the host temp dir - the last resort, and the one that tells a path
  #    problem apart from a missing file.
  local tdir="${TMPDIR:-/tmp}/p10d-harness-$$"
  if mkdir -p "$tdir" 2>/dev/null && cp -f "$UI_PY_SRC" "$tdir/p10d-ui.py" 2>/dev/null && cp -f "$dump" "$tdir/dump.xml" 2>/dev/null; then
    TMP_READER="$tdir/p10d-ui.py"; TMP_DUMP="$tdir/dump.xml"
    case "${HOST_SHELL:-}" in
      windows-msys)
        TMP_READER_WIN="$(host_path_m "$TMP_READER")"; TMP_DUMP_WIN="$(host_path_m "$TMP_DUMP")"
        TMP_DIR="$tdir"; TMP_DIR_WIN="$(host_path_m "$tdir")" ;;
      *) TMP_READER_WIN="$TMP_READER"; TMP_DUMP_WIN="$TMP_DUMP"
        TMP_DIR="$tdir"; TMP_DIR_WIN="$tdir" ;;
    esac
    if probe_reader "temp-dir copy" "$TMP_READER_WIN" "$TMP_DUMP_WIN"; then
      READER_CMD="$TMP_READER_WIN"; MAP_MODE=tmp
      READER_VIA="a copy in the host temp dir ($TMP_READER_WIN) - the checkout's own location is not reachable from this Python"
      return 0
    fi
  else
    PROBE_TRIED="$PROBE_TRIED, temp-dir copy (could not copy into $tdir)"
  fi
  printf '%s' "$PROBE_LOG" >> "$OUT/reader-probe.txt"
  return 1
}

# ---- R0.5: can this HOST read the screen at all? -----------------------------
# One dump, before a single UI verdict. If it cannot be read, nothing below can be
# a statement about the app - and the v2 run proved how expensive that is: every
# wait timed out for 6 minutes and the bundle ended with "the app window never
# appeared" on a phone that was showing the app. Whatever the reason (a host shell
# that rewrote the device path, uiautomator refusing, scoped storage), say it once,
# in the place where a human will see it, and stop.
step "R0.5 the harness can read this phone's screen"
# Two host-side prerequisites, checked before any UI work. Both failed silently in
# v2 on Windows, and both made the app look broken:
#   * no working python3 (the Store stub "exists" and prints an error) - then the
#     accessibility reader never runs, so NOTHING can ever be found on screen;
#   * a host shell that rewrites device paths (MSYS) - then the dump is an error
#     message instead of XML.
if [ -z "$PY" ]; then
  diag "harness preflight: no working Python interpreter (tried python3, python, py -3)."
  diag "  On Windows, 'python3' is often a Microsoft Store stub that prints"
  diag "  \"Python was not found\" and exits non-zero; install Python 3, or use the"
  diag "  'py -3' launcher, then re-run. Without it the readers this script drives"
  diag "  (p10d-ui.py, p10d-png.py, check-apk.py) cannot run at all."
  rd HARNESS_PYTHON 1 "no working Python interpreter on this host, so the accessibility reader, the screenshot checker and the APK inspector cannot run (tried python3, python, py -3) - this is a host setup problem, not an app problem"
  bail "no working Python interpreter on this host (see HARNESS_PYTHON)"
else
  rd HARNESS_PYTHON 0 "Python interpreter: $PY ($($PY -c 'import sys; print(sys.version.split()[0])' 2>/dev/null))"
fi

# ---- R0.4: are the helpers this driver drives actually IN this checkout? -------
# Cheapest check first, and the one that answers the owner's third bundle. A checkout
# assembled file-by-file (or an old ZIP with a new script dropped into it) produces a
# run that looks like a path bug and is really a missing file.
MISSING_HELPERS="$(missing_helpers | tr '\n' ' ' | sed 's/ *$//')"
if [ -n "$MISSING_HELPERS" ]; then
  diag "the harness is INCOMPLETE: these committed files are not in this checkout."
  diag "  missing: $MISSING_HELPERS"
  diag "  checked against: $DIR"
  diag "  bash reads this directory to run this script, so these are files that are"
  diag "  absent - not a path-format problem. Downloading individual files from the"
  diag "  GitHub web UI leaves the helpers behind; take the whole branch instead:"
  diag "    git clone https://github.com/m-cyber12/OpenCode-app.git"
  diag "    cd OpenCode-app && git checkout arena/01a0b9d5-opencode-app"
  diag "  (or: Code -> Download ZIP on that branch). Then re-run this script."
  rd HARNESS_CHECKOUT 1 "this checkout is missing committed helper file(s): $MISSING_HELPERS - the run cannot read the phone's screen without them. bash can see the directory ($DIR), so this is an incomplete download rather than a path problem; get the whole branch: git clone https://github.com/m-cyber12/OpenCode-app.git && git checkout arena/01a0b9d5-opencode-app"
  # the console is what the owner actually reads; do not make them open a second file
  # to learn the two commands that fix this
  echo "  missing in this checkout: $MISSING_HELPERS"
  echo "  fix once, then re-run this exact script with the same APK:"
  echo "    git clone https://github.com/m-cyber12/OpenCode-app.git"
  echo "    cd OpenCode-app && git checkout arena/01a0b9d5-opencode-app"
  echo "  (no git on hand: Code -> Download ZIP on that branch, unzip, run from there)"
  bail "the checkout is incomplete (see HARNESS_CHECKOUT)"
fi

# The dump gate: adb writes the phone's screen to /sdcard and reads it back. If the
# bytes are not XML, nothing below can speak about the app (the v2 bundle's dumps were
# 72 bytes of MSYS-rewritten nonsense and the run blamed the app for six minutes).
if ui_dump "harness-preflight"; then
  if choose_reader; then
    # The probe talks to run.log on a green run: which forms failed is only interesting
    # when every form fails (then it goes to DIAGNOSIS.txt below). DIAGNOSIS.txt has to
    # stay empty on a clean run - a bundle whose diagnosis file has text in it is read as
    # "something went wrong here", and it has to mean that.
    printf '%s' "$PROBE_LOG" >> "$LOG"
    # The screenshot validator and the APK inspector run through the same decision: the
    # form that opened the reader is the form they get too.
    UI_PY="$READER_CMD"
    PNG_PY="$(py_script "$PNG_PY_SRC")"
    APK_PY="$(py_script "$APK_PY_SRC")"
    log "python path mode: $MAP_MODE - $PY gets scripts and files as $( [ "$MAP_MODE" = posix ] && printf 'POSIX paths' || printf '%s' "${READER_CMD%/*}/..." ) ($READER_VIA)"
    PRE_NODES=$(ui_nodes)
    rd HARNESS_READER 0 "the accessibility reader parsed the dump: $PRE_NODES node(s), reader=$READER_CMD (via $READER_VIA)"
    rd HARNESS_DUMP 0 "the accessibility dump is readable from this host: $PRE_NODES node(s), acquisition ${LAST_DUMP_MODE:-?}; host shell: ${HOST_SHELL}"
  else
    # Files present, four path forms, no way in: this is the harness's own blindness, and
    # the two facts must stay separate - adb DID get the screen, this host's Python could
    # not open the reader that reads it.
    printf '%s' "$PROBE_LOG" | while IFS= read -r l; do diag "$l"; done
    PRE_BYTES=$(wc -c < "$OUT/ui/ui-harness-preflight.xml" 2>/dev/null | tr -d ' ')
    PRE_ERR=$(head -2 "$READER_ERR" 2>/dev/null | tr -d '\r' | tr '\n' ' ' | cut -c1-200)
    diag "harness preflight: the dump was written and read back, but the READER could not"
    diag "  be made to read it in any of the path forms this driver knows."
    diag "  this is a host path-translation problem, not a missing file: all $((1+$(printf '%s\n' $HELPER_FILES | wc -l | tr -d ' '))) files are present in $DIR"
    diag "  (bash lists them; the interpreter below could not open one of them by any form)"
    diag "  reader script: $UI_PY_SRC ($(wc -c < "$UI_PY_SRC" 2>/dev/null | tr -d ' ') bytes, present and readable by bash)"
    diag "  interpreter:   $PY"
    diag "  forms tried:   ${PROBE_TRIED:-none}"
    diag "  dump:          $PRE_BYTES bytes of XML, from ${LAST_DUMP_MODE:-?} (device path /sdcard/p10d-ui.xml)"
    diag "  host shell: ${HOST_SHELL}; script dir: $DIR; cwd: $(pwd)"
    diag "  reader stderr (first lines): ${PRE_ERR:-<empty>}"
    diag "  if this is Git Bash on Windows: the checkout directory is not reachable from"
    diag "  this Python. Move the checkout somewhere short and ASCII (e.g. C:/src/OpenCode-app),"
    diag "  or run the driver from WSL/Linux, and re-run."
    rd HARNESS_READER 1 "the accessibility reader could not read the dump by any path form this driver knows - every UI verdict after this point would describe the harness, not the app, so the run stops HERE. forms tried: ${PROBE_TRIED}. reader: $PY $UI_PY_SRC; interpreter ${PY##*/}; per-form errors in reader-probe.txt and DIAGNOSIS.txt"
    rd HARNESS_DUMP 0 "the accessibility dump itself was fine: $((PRE_BYTES+0)) bytes of XML written by adb and read back (${LAST_DUMP_MODE:-?}) - the reader above, not the phone, is what failed this run"
    bail "the accessibility reader cannot read this phone's screen on this host (see HARNESS_READER)"
  fi
else
  WHY=$(dump_failure_reason "$OUT/ui/ui-harness-preflight.xml")
  diag "harness preflight: the first accessibility dump could not be read."
  diag "  reason: $WHY"
  diag "  host shell: ${HOST_SHELL} (uname=$(uname -s 2>/dev/null)); script dir: $DIR"
  diag "  acquisition attempt: ${LAST_DUMP_MODE:-<none>}; raw output kept in ui/ui-harness-preflight.xml"
  rd HARNESS_DUMP 1 "this host cannot read the phone's screen: $WHY - no app verdict can be produced from an unreadable dump, so the run stops HERE and does not blame the app"
  bail "the accessibility channel is unusable from this host (see HARNESS_DUMP)"
fi

# ---- R1: device facts --------------------------------------------------------
step "R1 device facts"
set_swipe_geometry
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
if [ "${P10D_SKIP_ARTIFACT:-0}" = 1 ]; then
  # Rehearsal only (and used by phase10/scripts/test-90-real-device.sh): drive the UI
  # flow without a signed artifact present. Recorded as SKIP, never as PASS, so a
  # rehearsal can never be mistaken for an artifact verdict.
  rd ARTIFACT 7 "P10D_SKIP_ARTIFACT=1: the APK's identity/signature were NOT inspected in this run"
else
{
  echo "=== check-apk.py (identity, contents, signature presence) ==="
  $PY "$APK_PY" "$(map_file "$APK")" \
    --expect-signed --expect-not-debuggable --expect-icon --expect-payload \
    --expect-package "$PKG" --expect-version-name "$VNAME" --expect-version-code "$VCODE" \
    --expect-native-abi arm64-v8a --expect-min-sdk 29 \
    2>&1
} > "$REPORT" 2>&1
if grep -aq '^VERDICT PASS' "$REPORT"; then
  rd ARTIFACT 0 "$(grep -a '^MANIFEST ' "$REPORT" | head -1 | cut -c1-200)"
elif ! grep -aqE '^(VERDICT|MANIFEST|FINDING|CONTENTS) ' "$REPORT"; then
  # The inspector produced NOTHING that looks like a verdict. In the owner's
  # 2026-09-21 bundle this read as "P10D_ARTIFACT FAIL :: check-apk findings: " (empty)
  # which scans as "the APK is broken"; what it actually said, one line further down in
  # artifact-report.txt, was that Windows Python could not open the script itself:
  #   can't open file 'P:\p\OpenCodeGUI\phase10\scripts\check-apk.py': [Errno 2]
  # The APK is NOT judged here, and the verdict has to say so.
  rd ARTIFACT 7 "the APK inspector produced no verdict, so the APK was NOT judged: $(grep -av '^===' "$REPORT" | head -1 | cut -c1-200) (see $REPORT)"
elif [ -z "$PY" ] || grep -aqiE 'Python was not found|command not found|not recognized as an internal' "$REPORT"; then
  # The inspector never ran: on Windows a missing `python3` is answered by the
  # Store stub, which prints exactly that and exits non-zero. An empty finding list
  # with a FAIL verdict (the v2 bundle: "check-apk findings: ") reads as "the APK is
  # broken" - it has to read as "this host cannot inspect an APK".
  rd ARTIFACT 7 "could not inspect the APK on this host: $(head -1 "$REPORT" | cut -c1-160) - the APK was NOT judged (install Python 3, or read $REPORT)"
else
  rd ARTIFACT 1 "check-apk findings: $(grep -a '^FINDING' "$REPORT" | head -4 | tr '\n' '; ')"
fi
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

# ---- R3: clean install of the SIGNED apk ------------------------------------
step "R3 clean install of the signed artifact"
log "uninstalling any previous install so this is a genuine first run"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true
# adb.exe is a WINDOWS binary on an MSYS host: the path string is received literally,
# and a POSIX-absolute path like /c/src/app.apk does not exist as far as it is
# concerned ('adb.exe: failed to stat /c/src/app-release-signed.apk: No such file or
# directory' in the owner's 2026-09-23 run - bash and Python read the same string
# fine, which is why R2 verified the artifact and R3 still failed). adb gets the
# mixed path form achieved by the same host_path conversion every Python tool gets.
APK_ADB="$APK"
if [ "${HOST_SHELL:-}" = "windows-msys" ]; then
  APK_ADB="$(host_path_m "$APK")"
  log "adb is a Windows binary on this host - the APK is handed to it as $APK_ADB"
fi
INSTALL_OUT="$(adb install -r -g "$APK_ADB" 2>&1)"
printf '%s\n' "$INSTALL_OUT" >> "$LOG"
if printf '%s\n' "$INSTALL_OUT" | grep -q "Success"; then
  rd INSTALL 0 "adb install of the signed release APK succeeded (as $APK_ADB) - this is the artifact a user would sideload"
else
  # name WHAT adb said, not a guess from a checklist: 'failed to stat' is a host
  # path problem, 'INSTALL_FAILED_*' is a real device verdict - they are different
  # fixes, and the run.log line that carries the difference was already printed
  INSTALL_LINE=$(printf '%s\n' "$INSTALL_OUT" | grep -oE '(INSTALL_FAILED_[A-Z_]+|failed to stat [^:]*: No such file or directory)' | head -1)
  rd INSTALL 1 "adb install failed - ${INSTALL_LINE:-see run.log} (full adb output in run.log)"
  bail "the signed APK did not install; every later stage presumes the app is on the device"
fi
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
VER_NOW=$(adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -o 'versionName=[^ ]*' | head -1 | cut -d= -f2)
rd VERSION_ON_DEVICE 0 "installed versionName=$VER_NOW code=$(adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -o 'versionCode=[0-9]*' | head -1 | cut -d= -f2)"

# ---- R4: first run ----------------------------------------------------------
step "R4 first run: welcome -> runtime healthy by itself -> the workspace step -> a project -> composer"
# v4 item 4 makes the workspace folder the first decision the app asks for, and the
# default folder (Documents/OpenCode) is only writable with All files access. The
# harness grants it the way the Settings toggle does (`appops set ... allow`, no
# root) and SAYS SO in the log: a pass that depended on a hidden grant would be the
# kind of evidence this project does not accept. P10D_GRANT_ALL_FILES=0 skips it -
# that is the honesty pass, where the app must state the folder is not visible to
# file managers instead of claiming otherwise.
if [ "${P10D_GRANT_ALL_FILES:-1}" = "1" ]; then
  adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
  log "All files access: $(adb shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE 2>&1 | tr -d '\r' | head -1)"
else
  log "P10D_GRANT_ALL_FILES=0: leaving All files access ungranted on purpose"
fi

adb shell am start -W -n "$PKG/ai.opencode.android.MainActivity" 2>&1 | tr -d '\r' | tee -a "$LOG" | grep -E 'Status|LaunchState|TotalTime' || \
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
T0=$(date +%s)
shot "launch" || true
sleep 2

# The state machine, in the order a user meets it. The first run extracts the
# payload (~1 GB of Bun + OpenCode + git + ripgrep) before the runtime can be
# HEALTHY, so this waits generously - but it reports what it is waiting for.
FIRST_RUN_OK=0
if wait_for "app-window" "$NEEDLE_WELCOME|OpenCode" "$(tmo 300)"; then
  handle_interruptions || true
  shot "welcome" || true
  # The welcome screen advances by itself when the supervisor reports HEALTHY
  # (AppRoot's LaunchedEffect). A human who gets impatient taps Continue; do the
  # same, but never make the verdict depend on the tap.
  if wait_for "runtime-or-next" "$NEEDLE_WELCOME|$NEEDLE_PROJECTS|$NEEDLE_ONBOARDING|$NEEDLE_CHAT" "$(tmo 180)"; then
    tap_any "welcome_continue" "Continue" >/dev/null 2>&1 || true
    shot "after-welcome" || true
    # A device the app has met before is NOT a first run, and no amount of waiting
    # turns it into one: projects live on shared storage (designed persistence -
    # nothing is deleted on reinstall) and Android backup can restore the app's
    # prefs. The owner's 15:13Z run: the screen showed project 'test' and a working
    # composer for 308 straight seconds while this wait looked for first-run-only
    # needles. So the wait matches any usable surface, then the run CLASSIFIES what
    # it got instead of assuming a fresh install.
    if wait_for "workspace-or-projects" "$NEEDLE_PROJECTS|$NEEDLE_ONBOARDING|$NEEDLE_CHAT" "$(tmo 300)"; then
      FIRST_RUN_OK=1
      T1=$(( $(date +%s) - T0 ))
      FIRST_RUN_MODE="unknown"
      if ui_has_any "Where your files will live" "onboarding_workspace" "Use this folder as workspace"; then
        FIRST_RUN_MODE="workspace-step"
      elif ui_has_any "project_name_input" "New project" "Create project"; then
        FIRST_RUN_MODE="fresh-projects"
      elif ui_has_any "chat_screen" "composer_input" "model_quick_switch" "Start a conversation"; then
        FIRST_RUN_MODE="returning"
      elif ui_has "Projects" && ui_has "New conversation"; then
        FIRST_RUN_MODE="returning"
      fi
      case "$FIRST_RUN_MODE" in
        returning)
          # honesty evidence for "returning": project folders exist on shared
          # storage, read from OUTSIDE the app (never a path the run already knew)
          OUTSIDE_LIST=$(adb shell "ls -1 /storage/emulated/0/Documents/OpenCode 2>&1 | head -8" 2>/dev/null | tr -d '\r' | tr '\n' ' ' | sed 's/[[:space:]]*$//')
          log "returning install detected (workspace holds: ${OUTSIDE_LIST:-unreadable by shell}); the fresh-install flow is CI-verified instrumented (F1-F3)"
          rd FIRST_RUN 0 "returning install: the app opened its projects/chat surface by itself in ${T1}s (payload extracted + agent started, no privileged access). The device already holds project(s) on shared storage (${OUTSIDE_LIST:-listed separately}); NOT deleting anything by design. Fresh-install flow: CI F1-F3. screen: $(screen_state)"
          ;;
        workspace-step|fresh-projects|unknown)
          rd FIRST_RUN 0 "app reached its first-run surface by itself in ${T1}s - $FIRST_RUN_MODE (payload extracted + agent started, no privileged access); screen: $(screen_state)"
          ;;
      esac
    else
      rd FIRST_RUN 1 "the app never reached any usable surface (workspace step, projects, or chat) (see DIAGNOSIS.txt and the screenshots at each step).$(dump_caveat)"
    fi
  else
    rd FIRST_RUN 1 "no welcome/projects surface became usable (see DIAGNOSIS.txt; the app also offers Settings -> Share diagnostics).$(dump_caveat)"
  fi
else
  rd FIRST_RUN 1 "no app surface could be read from the screen: $(foreground) $(screen_state).$(dump_caveat) - see DIAGNOSIS.txt and 01-launch.png"
fi

# ---- R4a: the v4 workspace step (item 4) ------------------------------------
if [ "$FIRST_RUN_OK" = 1 ] && ui_has_any "Where your files will live" "Use this folder as workspace" "onboarding_workspace"; then
  shot "workspace-step" || true
  ui_dump "workspace-step" >/dev/null 2>&1
  FOLDER_SHOWN=$($PY - "$(map_dump "$LAST_DUMP")" <<PY
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
paths = [c for c in re.findall(r'(?:text|content-desc)="(/[^"<>]{3,})"', xml)
         if not c.startswith(("/data/data/", "/data/user/0/"))]
print(paths[0] if paths else "")
PY
)
  # The controls the brief removed must be absent from this screen - read off the
  # screen, so a build that quietly kept one of them fails here.
  REMOVED_HITS=$($PY - "$(map_dump "$LAST_DUMP")" <<PY
import sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
removed = ["Copy path", "Export a copy", "Use a folder I choose", "Use the default location again"]
print(",".join(r for r in removed if r in xml))
PY
)
  PICKER_SHOWN=0; ui_has "Choose folder" && PICKER_SHOWN=1
  ACTION_SHOWN=0; ui_has "Use this folder as workspace" && ACTION_SHOWN=1
  # Anything else that looks like a button is reported: the flow is two controls.
  OTHER_BUTTONS=$($PY - "$(map_dump "$LAST_DUMP")" <<PY
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
allowed = {"Choose folder", "Use this folder as workspace"}
known = {"Where your files will live", "Workspace folder",
         "You can change the folder later in Settings.",
         "The system does not let a file manager open this folder on this Android version. A PC still can."}
labels = set(re.findall(r'(?:text|content-desc)="([^"<>]{2,40})"', xml))
extra = sorted(l for l in labels
               if l not in allowed and l not in known and not l.startswith("/")
               and "Pick one folder" not in l and "subfolder of it" not in l
               and "cannot write in" not in l and "All files access" not in l
               and "This screen has opened" not in l and "The system does not let" not in l)
print("|".join(extra)[:200])
PY
)
  if [ -n "$FOLDER_SHOWN" ] && [ "$PICKER_SHOWN" = 1 ] && [ "$ACTION_SHOWN" = 1 ] && [ -z "$REMOVED_HITS" ]; then
    # The label the action carries is logged, not just matched: the self-test checks
    # this line, and tomorrow's bundle should answer "what did the button say?" without
    # reading the driver.
    log "workspace step: the single action is labelled 'Use this folder as workspace' (tag onboarding_workspace_use); the picker is 'Choose folder' (tag onboarding_workspace_pick)"
    rd WORKSPACE_STEP 0 "the workspace step shows the folder ($FOLDER_SHOWN), one picker and one action; removed controls absent; other controls: ${OTHER_BUTTONS:-none}"
  else
    rd WORKSPACE_STEP 1 "workspace step incomplete: folder='${FOLDER_SHOWN:-<none>}' picker=$PICKER_SHOWN action=$ACTION_SHOWN removedPresent='${REMOVED_HITS:-none}' other='${OTHER_BUTTONS:-none}' (see ui/ui-workspace-step.xml)"
  fi

  # One tap: the first project is created and the app lands in its chat. The chat is
  # an OpenCode session in the project directory, so the check is a filesystem check
  # made from OUTSIDE the app: the folder must exist and hold exactly what a project
  # folder holds (no extra directory per chat).
  WS_ROOT_OUT="${FOLDER_SHOWN:-/storage/emulated/0/Documents/OpenCode}"
  if tap_any "onboarding_workspace_use" "Use this folder as workspace"; then
    # v6: the tap lands on the projects page itself - the workspace path on top,
    # the first project expanded with its New session control, and the chat opens
    # from that surface. The outside-app filesystem check is unchanged.
    if wait_for "projects-after-workspace" "$NEEDLE_PROJECTS" "$(tmo 240)"; then
      shot "projects-after-workspace" || true
      WS_PATH_SHOWN=0; ROW_OPEN=0
      ui_has_any "projects_workspace_path" && WS_PATH_SHOWN=1
      ui_has_any "projects_new_session_1" "project_row_1" && ROW_OPEN=1
      shot "workspace-header" || true
      # The listing comes back through the host's own shell, so the check reads the
      # NAMES in the workspace folder (a project folder called `1`), not a path string
      # this script already knew: the point is that the tap created it.
      FIRST_PROJECT_LS=$(adb shell "ls -1 '$WS_ROOT_OUT' 2>&1" | tr -d '\r' | tr '\n' ' ' | sed 's/[[:space:]]*$//')
      FIRST_PROJECT_DIR=$(adb shell "ls -ld '$WS_ROOT_OUT/1' 2>&1" | tr -d '\r' | head -1)
      PROJECT_ON_DISK=1
      case " $FIRST_PROJECT_LS " in
        *" 1 "*) case "$FIRST_PROJECT_DIR" in
                   *"No such file"*|"") ;;
                   *) PROJECT_ON_DISK=0 ;;
                 esac ;;
      esac
      if [ "$PROJECT_ON_DISK" = 0 ] && [ "$WS_PATH_SHOWN" = 1 ] && [ "$ROW_OPEN" = 1 ] && \
         tap_any "projects_new_session_1" "New session" && \
         wait_for "conversation-after-workspace" "$NEEDLE_CHAT" "$(tmo 180)"; then
        rd WORKSPACE_FIRST_PROJECT 0 "one tap created the first project and the app landed on the projects page: the workspace path is on top, project 1 is expanded ('$WS_ROOT_OUT' contains [$FIRST_PROJECT_LS] from outside the app), and its 'New session' control opened the chat"
      elif [ "$PROJECT_ON_DISK" != 0 ]; then
        rd WORKSPACE_FIRST_PROJECT 1 "the projects page opened but '$WS_ROOT_OUT/1' was not found from outside the app (ls: ${FIRST_PROJECT_DIR:-<empty>}; listing: [$FIRST_PROJECT_LS])"
      else
        rd WORKSPACE_FIRST_PROJECT 1 "post-setup landing incomplete: workspace path shown=$WS_PATH_SHOWN first project expanded=$ROW_OPEN; the New-session -> chat entry did not complete (see ui/ui-projects-after-workspace.xml)"
      fi
    else
      rd WORKSPACE_FIRST_PROJECT 1 "the workspace action did not land on the projects page (the system All-files-access screen may be in front; see the screenshots and ui/ui-workspace-step.xml)"
    fi
  else
    rd WORKSPACE_FIRST_PROJECT 1 "the single workspace action was not tappable (see ui/ui-tap-onboarding_workspace_use.xml)"
  fi
else
  rd WORKSPACE_STEP 7 "no workspace step on screen (a returning install with a project opens its chat directly, which is the documented behaviour)"
  rd WORKSPACE_FIRST_PROJECT 7 "no workspace step, so nothing to confirm"
fi

# ---- R4b: the project card still works (a second project, any time) ---------
PROJECT_NAME=""
if [ "$FIRST_RUN_OK" = 1 ]; then
  if ui_has_any "projects_screen" "project_create" "Create project" "Create New Project" || tap_any "open_projects" "Projects"; then
    if wait_for "projects-screen" "$NEEDLE_PROJECTS" "$(tmo 120)"; then
      # v6: the page leads with the workspace it lists - the folder on top, one
      # Switch control (which opens the known-roots dialog), and a small Import.
      WS_HEADER=0; SW_DIALOG=0
      ui_has_any "projects_workspace_path" && WS_HEADER=1
      WS_BEFORE=$(first_screen_path)
      if tap_any "projects_switch_workspace" "Switch" >/dev/null 2>&1; then
        if wait_for "workspace-switch-dialog" "Where your projects live" "$(tmo 20)" >/dev/null 2>&1; then
          SW_DIALOG=1
        else
          log "note: the Switch control on the projects page did not open the known-roots dialog"
        fi
      fi
      log "projects page header: workspace path shown=$WS_HEADER, Switch control opens the known-roots dialog=$SW_DIALOG"

      # ---- v6.1: Browse -> the system picker -> confirm, walked end to end -----
      # The owner's fourth device pass (2026-09-24) found this exact flow doing
      # nothing: useChosenFolder handed the CACHED old root back to switchTo, which
      # wrote it over the folder the user had just picked. The fix is in
      # StorageController (pinned by the instrumented W7 gate); this stage makes the
      # same taps a finger makes on the signed build, and PASSES only when the page
      # header reads back a DIFFERENT path after the pick. The picker is another
      # app's UI with OEM wording, so a picker this driver cannot read is a SKIP
      # with instructions - never a silent pass.
      if [ "$SW_DIALOG" = 1 ] && [ -n "$WS_BEFORE" ]; then
        adb shell "mkdir -p /sdcard/opencode-picked" >/dev/null 2>&1 || true
        if tap_any "projects_switch_browse" "Browse for a folder" >/dev/null 2>&1 && \
           wait_for "system-picker" "Use this folder|Select this folder" "$(tmo 60)"; then
          shot "system-picker" || true
          # Best effort: enter the folder made for this run when it is on screen;
          # confirming whatever folder the picker opened on still proves the entry
          # point (the check is that the path CHANGES, not which folder it becomes).
          if ui_has "opencode-picked"; then
            tap_any "opencode-picked" >/dev/null 2>&1 || true
            sleep 1
          fi
          if tap_any "Use this folder" "Select this folder" >/dev/null 2>&1; then
            sleep 1
            ui_dump "picker-consent" >/dev/null 2>&1
            # Stock Android asks once more ("Allow ... to access files in ...?").
            if ui_has_any "ALLOW" "Allow"; then tap_any "Allow" >/dev/null 2>&1 || true; fi
            if wait_for "projects-after-browse" "$NEEDLE_PROJECTS" "$(tmo 120)"; then
              WS_AFTER=$(first_screen_path)
              shot "projects-after-browse" || true
              if [ -n "$WS_AFTER" ] && [ "$WS_AFTER" != "$WS_BEFORE" ]; then
                # Leave the phone as found: back through the known-roots dialog to
                # the root the walk started from, then remove the walk's folder.
                RESTORED=0
                if tap_any "projects_switch_workspace" "Switch" >/dev/null 2>&1 && \
                   wait_for "switch-dialog-restore" "Where your projects live" "$(tmo 20)" >/dev/null 2>&1 && \
                   tap_any "$WS_BEFORE" "projects_switch_root" >/dev/null 2>&1 && \
                   wait_for "projects-after-restore" "$NEEDLE_PROJECTS" "$(tmo 120)" >/dev/null 2>&1 && \
                   [ "$(first_screen_path)" = "$WS_BEFORE" ]; then
                  RESTORED=1
                  adb shell "rm -rf /sdcard/opencode-picked" >/dev/null 2>&1 || true
                fi
                rd PICKER_WORKSPACE_SWITCH 0 "Browse -> system picker -> confirm changed the workspace: '$WS_BEFORE' -> '$WS_AFTER' (the page header, read back after the pick); switched back through the known-roots dialog: restored=$RESTORED"
                [ "$RESTORED" = 1 ] || log "note: the walk could not switch back to '$WS_BEFORE'; the run continues in '$WS_AFTER'"
              elif ui_has "That folder cannot be used"; then
                rd PICKER_WORKSPACE_SWITCH 7 "the picker confirmed a folder the app refuses, and the refusal is ON the page ('That folder cannot be used...'): the entry point ran and answered; repeat with a folder on internal storage to see the switch itself"
              else
                rd PICKER_WORKSPACE_SWITCH 1 "confirmed a folder in the system picker but the page header still reads '${WS_AFTER:-<none>}' - the pick was swallowed (the owner's 2026-09-24 bug; see ui/ui-wait-projects-after-browse.xml)"
              fi
            else
              rd PICKER_WORKSPACE_SWITCH 1 "confirmed a folder in the system picker but the projects page never came back (see DIAGNOSIS.txt)"
            fi
          else
            adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
            sleep 1
            rd PICKER_WORKSPACE_SWITCH 7 "the system picker opened but no confirm control matched this OEM's wording (see ui/, screenshot system-picker): confirm 'Use this folder' by hand this pass"
          fi
        else
          rd PICKER_WORKSPACE_SWITCH 7 "the Browse entry did not open a readable system picker; verify Browse -> Use this folder by hand this pass"
        fi
      else
        rd PICKER_WORKSPACE_SWITCH 7 "no known-roots dialog (or no workspace path in the header) to browse from: header=$WS_HEADER dialog=$SW_DIALOG"
      fi
      # Whatever branch ran, end where the walk began: the projects page with no
      # dialog in front (a leftover dialog would swallow the create-project taps).
      ui_dump "after-browse-walk" >/dev/null 2>&1
      if ui_has "Where your projects live"; then
        tap_any "Cancel" >/dev/null 2>&1 || adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
        sleep 1
      fi
      PROJECT_NAME="p10d-$(date +%H%M%S)"
      if tap_any "project_name_input" "Project name"; then
        type_text "$PROJECT_NAME"
        shot "project-name-typed" || true
        if tap_any "project_create" "Create project" "Create New Project"; then
          sleep 2
          # v6: creating keeps the page - the new project expanded, its New
          # session control on screen. Reaching the chat is that control's tap.
          # Text fallback for phones whose dumps carry no Compose tags: the
          # expanded row's control reads "New session" either way.
          if wait_for "project-created-expanded" "projects_new_session_${PROJECT_NAME}|New session" "$(tmo 90)"; then
            shot "project-created-expanded" || true
            tap_any "projects_new_session_${PROJECT_NAME}" "New session" >/dev/null 2>&1 || true
          else
            log "note: the expanded row's New-session control did not show for '$PROJECT_NAME'; trying the row itself"
            tap_any "project_row_${PROJECT_NAME}" "${PROJECT_NAME}" >/dev/null 2>&1 || true
            tap_any "projects_new_session_${PROJECT_NAME}" "New session" >/dev/null 2>&1 || true
          fi
          if wait_for "conversation" "$NEEDLE_CHAT" "$(tmo 180)"; then
            shot "chat-ready" || true
            rd FIRST_RUN_PROJECT 0 "project '$PROJECT_NAME' created through the page's own Create button (taps + typed text) on the signed build; the app stayed on the page with the row expanded, and its New session control opened the conversation"
          else
            rd FIRST_RUN_PROJECT 1 "project created but the conversation surface was not reached via the expanded row (see DIAGNOSIS.txt)"
          fi
        else
          rd FIRST_RUN_PROJECT 1 "could not tap the create-project button (see ui/ui-tap-project_create.xml)"
        fi
      else
        rd FIRST_RUN_PROJECT 1 "could not focus the project-name field (see DIAGNOSIS.txt)"
      fi
    else
      rd FIRST_RUN_PROJECT 1 "the project list did not open (see DIAGNOSIS.txt)"
    fi
  else
    rd FIRST_RUN_PROJECT 7 "no project list and no projects control was reachable; the workspace step already created the first project"
  fi
else
  rd FIRST_RUN_PROJECT 7 "no project could be created because the first run never reached a usable screen.$(dump_caveat)"
fi

# ---- R5t: the v7 tab strip (Changes and Terminal surfaces) -------------------
# The redesign round added two project surfaces beside the conversation: what the
# agent did to the files (stage by stage, with diffs) and what it ran (a read-only
# console). This walk proves both open ON THE SIGNED BUILD by real taps, and that
# the Chat tab leads back - a fresh session shows their empty states, which name
# themselves, so the walk works before any turn has run.
step "R5t v7: the Changes and Terminal tabs on the conversation"
if [ "$FIRST_RUN_OK" = 1 ] && ui_has "project_tab_changes"; then
  if tap_any "project_tab_changes" "Changes"; then
    sleep 1
    if wait_for "changes-surface" "changes_screen|No file changes yet" "$(tmo 90)"; then
      shot "changes-surface" || true
      if tap_any "project_tab_terminal" "Terminal" && wait_for "terminal-surface" "terminal_screen|Nothing has run yet" "$(tmo 90)"; then
        shot "terminal-surface" || true
        if tap_any "project_tab_chat" "Chat" && wait_for "tabs-back-to-chat" "$NEEDLE_CHAT" "$(tmo 90)"; then
          rd TABS_SURFACES 0 "the Changes and Terminal tabs both open their surfaces on the signed build and the Chat tab returns to the conversation (all real taps; empty states name themselves on a fresh session)"
        else
          rd TABS_SURFACES 1 "the Terminal surface opened but the Chat tab did not return to the conversation (see DIAGNOSIS.txt)"
          return_to_conversation >/dev/null 2>&1 || true
        fi
      else
        rd TABS_SURFACES 1 "the Changes surface opened but the Terminal tab did not follow (see DIAGNOSIS.txt)"
        return_to_conversation >/dev/null 2>&1 || true
      fi
    else
      rd TABS_SURFACES 1 "the Changes tab was tapped but its surface never showed (see DIAGNOSIS.txt)"
      return_to_conversation >/dev/null 2>&1 || true
    fi
  else
    rd TABS_SURFACES 1 "the Changes tab is on screen but could not be tapped (see DIAGNOSIS.txt)"
  fi
else
  rd TABS_SURFACES 7 "no conversation open, or this build predates the v7 tab strip"
fi

# ---- R5: the app's own file browser ------------------------------------------
step "R5 the in-app file browser (this is how a user sees what the agent wrote)"
FILES_SEEN=0
FILES_PATH=""
# v8 fix round: the header's Files icon is gone (the owner called it redundant
# next to the bottom tab strip) - the tab IS the door now.
if [ "$FIRST_RUN_OK" = 1 ] && tap_any "project_tab_files" "Files"; then
  sleep 2
  if wait_for "files-screen" "$NEEDLE_FILES" "$(tmo 120)"; then
    shot "files-listing" || true
    ui_dump "files-screen" >/dev/null 2>&1
    # The path the app prints, wherever it is. The v2 driver looked for
    # `/Android/data/<pkg>/...` because that was the only root it knew: a locator
    # that encodes the layout silently stops matching the day the layout changes,
    # which is exactly what happened here. v3 reads ANY absolute path off the
    # screen (and refuses /data/data, which no user could act on), then prints the
    # storage-mode line too, so the verdict says which location the app claims.
    # The dump path goes through map_dump for the same reason every other Python
    # argument does: on a host where only the temp-dir copy opens, handing this inline
    # script the converted path made it read nothing, and the file browser looked like
    # it "showed no path" when the reader simply could not open the file.
    FILES_PATH=$($PY - "$(map_dump "$LAST_DUMP")" <<-PY
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
skip = ("/data/data/", "/data/user/0/")
usable = [c for c in re.findall(r'(?:text|content-desc)="(/[^"<>]{3,})"', xml)
          if not any(c.startswith(p) for p in skip)]
print(usable[0] if usable else "")
PY
)
    FILES_MODE=$($PY - "$(map_dump "$LAST_DUMP")" <<-PY
import sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for needle in ("Documents/OpenCode", "Your folder", "App folder (Android/data)", "App-private storage"):
    if needle in xml:
        print(needle)
        break
PY
)
    if [ -n "$FILES_PATH" ]; then
      FILES_SEEN=1
      rd FILES_SCREEN 0 "the app's file browser shows the project at $FILES_PATH (storage mode: ${FILES_MODE:-not shown}; read from the screen, not from the app's internals)"
    else
      rd FILES_SCREEN 1 "the file browser opened but no on-device path was shown on screen (storage mode: ${FILES_MODE:-not shown}; see ui/ui-files-screen.xml)"
    fi
    # The file browser can be more than one press deep (a folder listing, then the
    # viewer): the bounded loop gets back to the conversation and only diagnoses when
    # it cannot, instead of leaving the next stage waiting on the wrong screen.
    return_to_conversation >/dev/null 2>&1 || \
      log "note: after leaving the file browser the composer was not found again (R6 will report it)"
  else
    rd FILES_SCREEN 1 "the file browser did not open (see DIAGNOSIS.txt)"
  fi
else
  rd FILES_SCREEN 7 "no project open, so there was nothing to browse"
fi

# ---- R5b: the v4 surfaces on the signed build (items 1, 2, 3, 4) ------------
step "R5b v4: simplified storage screen, workspace switch, provider search, model quick switch"

# Helper: dump the current screen and print the first node whose text/content-desc
# contains a needle (its label only, not its coordinates - the log stays readable).
screen_label() { # $1 = needle
  ui_dump "r5b" >/dev/null 2>&1 || { echo ""; return; }
  $PY - "$(map_dump "$LAST_DUMP")" "$1" <<PY
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
needle = sys.argv[2].lower()
for m in re.finditer(r'<node[^>]*>', xml):
    node = m.group(0)
    txt = " ".join(re.findall(r'(?:text|content-desc)="([^"<>]*)"', node))
    if needle in txt.lower():
        print(txt.strip()[:160])
        break
PY
}

# ---- v4 item 4: the storage screen no longer carries copy/export/chooser -----
FILES_SIMPLIFIED=0
if tap_any "project_tab_files" "Files"; then
  if wait_for "files-screen" "$NEEDLE_FILES" "$(tmo 120)"; then
    sleep 1
    shot "v4-files" || true
    ui_dump "v4-files" >/dev/null 2>&1
    FILES_REMOVED=$($PY - "$(map_dump "$LAST_DUMP")" <<PY
import sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
gone = ["Copy path", "Export a copy", "Use a folder I choose", "Use the default location again"]
print(",".join(g for g in gone if g in xml))
PY
)
    if [ -z "$FILES_REMOVED" ]; then
      FILES_SIMPLIFIED=1
      rd FILES_SIMPLIFIED 0 "the signed build's file browser offers no copy-path, no export and no folder chooser; the storage panel states the location only (see 1?-v4-files.png)"
    else
      rd FILES_SIMPLIFIED 1 "the file browser still shows removed controls: $FILES_REMOVED (see ui/ui-v4-files.xml)"
    fi
    return_to_conversation >/dev/null 2>&1 || true
  else
    rd FILES_SIMPLIFIED 1 "the file browser did not open (see DIAGNOSIS.txt)"
  fi
else
  rd FILES_SIMPLIFIED 7 "no project open, so the file browser could not be reached"
fi

# ---- v4 items 2 and 4: Settings - workspace section, provider search, stars --
SETTINGS_REACHED=0
# v8 fix round: Settings moved off the header into the hamburger menu; the menu
# has to be opened first. The `|| true` keeps the old direct path alive as a
# fallback (an older build under this driver still has the flat icon).
tap_any "chat_menu" "Menu" >/dev/null 2>&1 || true
sleep 1
if tap_any "open_settings" "Settings and diagnostics"; then
  if wait_for "settings-screen" "$NEEDLE_SETTINGS" "$(tmo 120)"; then
    SETTINGS_REACHED=1
    sleep 1
    shot "v4-settings" || true
  fi
fi

if [ "$SETTINGS_REACHED" = 1 ]; then
  # The sections are long; a user scrolls. Three swipes bring the workspace and
  # model sections into view on any phone size this project has been tested on.
  i=0
  while [ "$i" -lt 4 ]; do
    adb shell input swipe "$SWIPE_FROM_X" "$SWIPE_FROM_Y" "$SWIPE_TO_X" "$SWIPE_TO_Y" 300 >/dev/null 2>&1
    sleep 1
    i=$((i + 1))
    ui_has_any "Workspace folder" "Choose another folder" "Search providers" && break
  done
  ui_dump "v4-settings-scrolled" >/dev/null 2>&1

  # item 1/4: the workspace switch lives here, with the honest note about switching
  WS_FOLDER=$(screen_label "Workspace folder")
  WS_PICK=$(screen_label "Choose another folder")
  WS_NOTE=$(screen_label "Switching the workspace hides")
  if [ -n "$WS_PICK" ] && [ -n "$WS_NOTE" ]; then
    rd WORKSPACE_SECTION 0 "Settings carries the workspace switch ($WS_PICK) and the switching note is on screen; the section names the folder as '${WS_FOLDER:-<label not read>}'"
  else
    rd WORKSPACE_SECTION 1 "the Settings workspace section is incomplete: folder='${WS_FOLDER:-<none>}' picker='${WS_PICK:-<none>}' note='${WS_NOTE:-<none>}' (see ui/ui-v4-settings-scrolled.xml)"
  fi

  # item 2: search over the catalog, then a provider that asks for a key only
  SEARCH_FIELD=$(screen_label "Search providers")
  PROVIDER_SEARCH=0
  if [ -n "$SEARCH_FIELD" ] && tap_any "Search providers" >/dev/null 2>&1; then
    # something nothing can match: the screen must say so rather than show everything
    adb shell input text "zzzqq" >/dev/null 2>&1
    # Wait for the copy, then read it: on a phone the list filters under the keyboard,
    # and a single read can catch the screen mid-change.
    wait_for "provider-no-match" "No provider matches" "$(tmo 20)" >/dev/null 2>&1
    NO_MATCH=$(screen_label "No provider matches")
    # then a real provider: the list narrows to it and offers the key action
    k=0
    while [ "$k" -lt 5 ]; do
      adb shell input keyevent KEYCODE_DEL >/dev/null 2>&1
      k=$((k + 1))
    done
    adb shell input text "openr" >/dev/null 2>&1
    wait_for "provider-match" "OpenRouter" "$(tmo 20)" >/dev/null 2>&1
    shot "v4-provider-search" || true
    # The dump the verdict points at is taken whether or not the gate passes: a FAIL
    # whose evidence file does not exist is a FAIL nobody can triage.
    ui_dump "v4-provider-search" >/dev/null 2>&1
    MATCH=$(screen_label "OpenRouter")
    if [ -n "$NO_MATCH" ] && [ -n "$MATCH" ]; then
      PROVIDER_SEARCH=1
      rd PROVIDER_SEARCH 0 "the catalog is searchable: an impossible query states '$NO_MATCH' and 'openr' narrows the list to '$MATCH'"
    else
      rd PROVIDER_SEARCH 1 "the search box did not behave: no-match='${NO_MATCH:-<none>}' match='${MATCH:-<none>}' (see ui/ui-v4-provider-search.xml)"
    fi

    # the one-step activation: tapping the key action on a listed provider opens a
    # dialog that asks for the key and nothing else (base URL / models are the
    # catalog's business), and it is dismissed without storing anything.
    # Quiet probe first: a fresh-key phone shows Connect ('Save key'), a stored-key
    # phone shows Manage ('Key options'); probing the absent one with tap_any would
    # write a false "not tappable" line into DIAGNOSIS.txt of a clean run.
    ui_dump "key-chip" >/dev/null 2>&1 || true
    CHIP=""
    if ui_has_any "provider_manage_key_${P10D_PROVIDER:-openrouter}" "Key options"; then
      CHIP="provider_manage_key_${P10D_PROVIDER:-openrouter} Key options"
    elif ui_has_any "provider_connect_${P10D_PROVIDER:-openrouter}" "Save key"; then
      CHIP="provider_connect_${P10D_PROVIDER:-openrouter} Save key"
    fi
    if [ -n "$CHIP" ] && tap_any $CHIP >/dev/null 2>&1; then
      wait_for "provider-key-dialog" "API key for|Stored key for" "$(tmo 20)" >/dev/null 2>&1
      shot "v4-provider-key" || true
      ui_dump "v4-provider-key" >/dev/null 2>&1
      DIALOG_TITLE=$(screen_label "API key for")
      [ -n "$DIALOG_TITLE" ] || DIALOG_TITLE=$(screen_label "Stored key for")
      # Fresh phones read "Only the key is asked for here"; phones with a stored
      # key read the manage body instead (and gain the relocated revoke). Both are
      # the one-key dialog, so either wording proves the same gate.
      ONLY_KEY=$(screen_label "Only the key is asked for here")
      [ -n "$ONLY_KEY" ] || ONLY_KEY=$(screen_label "A key is stored on this phone")
      FIELDS=$($PY - "$(map_dump "$LAST_DUMP")" <<PY
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
print(len(re.findall(r'class="android\.widget\.EditText"', xml)))
PY
)
      if [ -n "$DIALOG_TITLE" ] && [ -n "$ONLY_KEY" ] && [ "$FIELDS" = "1" ]; then
        rd PROVIDER_KEY_ONLY 0 "tapping the key action on a catalog provider asks for the API key only ('$DIALOG_TITLE'); $FIELDS text field on screen; nothing was saved"
      else
        rd PROVIDER_KEY_ONLY 1 "the provider dialog was not the key-only form: title='${DIALOG_TITLE:-<none>}' body='${ONLY_KEY:-<none>}' fields=$FIELDS (see ui/ui-v4-provider-key.xml)"
      fi
      tap_any "Not now" "Cancel" >/dev/null 2>&1 || adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
      sleep 1
    else
      rd PROVIDER_KEY_ONLY 7 "no key action was reachable on the searched provider row"
    fi
  else
    rd PROVIDER_SEARCH 1 "the provider search field was not reachable ('${SEARCH_FIELD:-<none>}')"
    rd PROVIDER_KEY_ONLY 7 "no search field, so no provider to activate"
  fi

  # item 3: star a model of the CONNECTED provider, then switch to it in the chat
  STAR_DESC=""
  i=0
  while [ "$i" -lt 3 ] && [ -z "$STAR_DESC" ]; do
    # expand the provider that has a key (or the bundled one): tapping its status
    # line expands its model list, which is where the star checkboxes live
    tap_any "Key stored" "No key stored" >/dev/null 2>&1 || true
    sleep 1
    STAR_DESC=$(screen_label "in the chat quick switch")
    i=$((i + 1))
  done
  if [ -n "$STAR_DESC" ]; then
    STAR_NAME=$(printf '%s' "$STAR_DESC" | sed -E 's/^(Show|Remove) //; s/ (in|from) the chat quick switch$//')
    shot "v4-star" || true
    if tap_any "in the chat quick switch" >/dev/null 2>&1; then
      sleep 1
      return_to_conversation >/dev/null 2>&1 || true
      sleep 2
      if wait_for "chat-after-settings" "$NEEDLE_CHAT" "$(tmo 90)"; then
        QS_BEFORE=$(screen_label "Model:")
        # (the header label is read the same way on both sides of the pick, so the
        # verdict compares like with like)
        if tap_any "Model:" >/dev/null 2>&1; then
          wait_for "quick-switch-menu" "Starred models" "$(tmo 20)" >/dev/null 2>&1
          shot "v4-quick-switch" || true
          ui_dump "v4-quick-switch" >/dev/null 2>&1
          MENU_TITLE=$(screen_label "Starred models")
          if [ -n "$MENU_TITLE" ] && tap_any "$STAR_NAME" >/dev/null 2>&1; then
            sleep 2
            QS_AFTER=$(screen_label "Model:")
            if [ -n "$QS_AFTER" ] && printf '%s' "$QS_AFTER" | grep -qF "$STAR_NAME"; then
              rd MODEL_QUICK_SWITCH 0 "starred '$STAR_NAME' in Settings, opened the quick switch in the chat ('$MENU_TITLE') and picked it there: the header went from '${QS_BEFORE:-<none>}' to '$QS_AFTER'"
            else
              rd MODEL_QUICK_SWITCH 1 "the quick switch did not set the model: after='${QS_AFTER:-<none>}' expected to contain '$STAR_NAME'"
            fi
          else
            rd MODEL_QUICK_SWITCH 1 "the quick-switch menu ('${MENU_TITLE:-<none>}') did not list the starred model '$STAR_NAME' (see ui/ui-v4-quick-switch.xml)"
          fi
        else
          rd MODEL_QUICK_SWITCH 1 "the chat header has no model control on screen (see ui/ui-r5b.xml)"
        fi
      else
        rd MODEL_QUICK_SWITCH 1 "leaving Settings did not return to the conversation"
      fi
    else
      rd MODEL_QUICK_SWITCH 1 "the star control ('$STAR_DESC') was not tappable (see ui/ui-v4-star.png)"
    fi
  else
    rd MODEL_QUICK_SWITCH 7 "no expandable provider row with a star control was reachable in Settings"
  fi
  # Leave Settings so R6 starts from the conversation, at whatever depth the v4
  # screens put this stage (Settings, the provider search, the key dialog): one press
  # is only right for one of those, so the bounded loop decides.
  return_to_conversation >/dev/null 2>&1 || true
else
  rd WORKSPACE_SECTION 7 "Settings could not be opened"
  rd PROVIDER_SEARCH 7 "Settings could not be opened"
  rd PROVIDER_KEY_ONLY 7 "Settings could not be opened"
  rd MODEL_QUICK_SWITCH 7 "Settings could not be opened"
fi

# ---- R6: a live turn --------------------------------------------------------
step "R6 live turn through the composer"
MODEL_AVAILABLE=0
if [ -n "${P10D_PROVIDER_KEY:-}" ]; then
  MODEL_KEY="$P10D_PROVIDER_KEY"
elif [ -t 0 ]; then
  printf '\nType a provider API key to run the live-turn gate (typed here, never stored, never logged)'
  printf '\nPress [Enter] to skip if you already added a key in the app or want to skip: '
  read -r MODEL_KEY
else
  # No terminal and no P10D_PROVIDER_KEY (e.g. the run was piped or automated):
  # blocking on `read` would hang forever with no output. Skip the live turn with
  # the reason on the record instead.
  MODEL_KEY=""
  log "no terminal and no P10D_PROVIDER_KEY: skipping the key prompt (the live-turn gate will SKIP)"
fi
if [ -n "${MODEL_KEY:-}" ] && [ "$SKIP_LIVE" = 0 ]; then
  # Route it through the app's own UI. v4 item 3: the name+key form is gone; the
  # only typed entry is the catalog dialog. Search the provider, then:
  #  - nothing stored yet   -> the row's Connect chip (label 'Save key'),
  #  - a key already stored -> the row's Manage chip, where the relocated revoke
  #    lives; revoke the stored key, then save the fresh one through the same
  #    dialog (a real rotate, reported as one).
  tap_any "chat_menu" "Menu" >/dev/null 2>&1 || true
  sleep 1
  if tap_any "open_settings" "Settings and diagnostics"; then
    sleep 2
    if tap_any "provider_search" "Search providers"; then
      type_text "${P10D_PROVIDER:-openrouter}"
      sleep 1
      # Probe before tapping: the Manage chip exists only when a key is already
      # stored, so a blind tap_any on the common fresh-phone path would paint a
      # false "not tappable" line into DIAGNOSIS.txt of an otherwise clean run.
      KEY_VIA=""
      ui_dump "key-chips" >/dev/null 2>&1 || true
      if ui_has_any "provider_manage_key_${P10D_PROVIDER:-openrouter}" "Key options"; then
        tap_any "provider_manage_key_${P10D_PROVIDER:-openrouter}" "Key options" && KEY_VIA=rotate
      fi
      if [ -z "$KEY_VIA" ] && tap_any "provider_connect_${P10D_PROVIDER:-openrouter}" "Save key"; then
        KEY_VIA=connect
      fi
      if [ -n "$KEY_VIA" ]; then
        wait_for "key-dialog" "provider_key_value" "$(tmo 30)" >/dev/null 2>&1 || true
        if [ "$KEY_VIA" = rotate ]; then
          tap_any "provider_key_revoke" "Revoke" >/dev/null 2>&1 || true
          sleep 2
          tap_any "provider_connect_${P10D_PROVIDER:-openrouter}" >/dev/null 2>&1 || true
          wait_for "key-dialog-again" "provider_key_value" "$(tmo 30)" >/dev/null 2>&1 || true
        fi
        if tap_any "provider_key_value" "API key"; then
          type_text "$MODEL_KEY"
          if tap_any "provider_key_save" "Save key" "Replace key" "Add key"; then
            sleep 3
            shot "provider-key-saved" || true
            if [ "$KEY_VIA" = rotate ]; then
              log "key rotated through the app's own UI: revoked the stored key via the Manage dialog, saved the fresh one through the same dialog (it is not in this log)"
            else
              log "key entered through the app's own catalog dialog: search -> Connect -> key field -> Save (it is not in this log)"
            fi
          else
            log "the dialog's Save button never became tappable (the key field stayed empty)"
          fi
        else
          log "none of the provider's key dialogs opened - add the key in Settings by hand and re-run to exercise the live gate"
        fi
      else
        log "neither the Manage chip nor the Connect chip for '${P10D_PROVIDER:-openrouter}' was on screen after the search"
      fi
    else
      log "the provider search field was not reachable in Settings"
    fi
  else
    log "could not open Settings to enter the key"
  fi
  unset MODEL_KEY
  # Leave Settings only if Settings is still the screen: `Save` does not navigate
  # (it clears the field), so an unconditional BACK here would be a second back press
  # on whatever screen we are really on - and if that is the conversation, the app
  # exits and every live gate downstream would fail for the wrong reason.
  # Whatever depth the key flow reached (Settings, the search screen, the v4 provider
  # dialog), the run has to come back to the conversation: return_to_conversation presses
  # BACK until it is there and says so when it cannot.
  return_to_conversation >/dev/null 2>&1 || true
  wait_for "ready-to-send" "$NEEDLE_CHAT" "$(tmo 90)" >/dev/null 2>&1 || \
    log "note: the composer was not on screen after the key step - the live turn will report what it sees"
fi
MODEL_KEY=""

if [ "$SKIP_LIVE" = 1 ]; then
  rd LIVE_TURN 7 "--skip-live was given: no model turn was attempted"
elif [ "$FIRST_RUN_OK" != 1 ]; then
  rd LIVE_TURN 7 "no conversation surface existed to send a turn from.$(dump_caveat)"
elif tap_any "composer_input" "Message the agent" "Message"; then
  # `adb shell` re-quotes what it forwards, and `input text` takes a single argument
  # in which %s is a space: quotes and literal spaces would be re-parsed by the
  # device shell and the prompt would arrive mangled (or not at all).
  # No shell metacharacters in this string on purpose: `adb shell input text`
  # forwards it through the DEVICE shell, which would treat > & ; | $ as syntax and
  # mangle (or truncate) the prompt.
  type_text "Use the bash tool to write a file named p10-visible.txt that contains the text p10-live-ok, then show me its contents"
  shot "prompt-typed" || true
  tap_any "composer_send" "Send" >/dev/null 2>&1 || adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
  log "prompt sent; waiting up to $(tmo 300)s for the answer"
  TURN=0
  if wait_for "turn-answer" "p10-visible.txt|p10-live-ok" "$(tmo 300)"; then TURN=1; fi
  shot "turn-answer" || true
  TOOLCARD=0
  ui_dump "turn-tool" >/dev/null 2>&1 && { ui_has "Shell command" || ui_has "bash" || ui_has "tool"; } && TOOLCARD=1
  if [ "$TURN" = 1 ] && [ "$TOOLCARD" = 1 ]; then
    MODEL_AVAILABLE=1
    # Expand the card like a user would, then capture the single best listing shot.
    tap_any "Shell command" "bash" "Write" >/dev/null 2>&1 || true
    sleep 1
    shot "tool-card-expanded" || true
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
# The marker file is whichever one this run produced: R6 asks the model to write
# p10-visible.txt with "p10-live-ok", while the instrumented W4 gate writes
# "P10_VISIBLE_<ts>". Accepting either keeps the check honest ("a shell can read a
# file the app wrote") without inventing a marker the phone run never creates.
VIS_ARGS=(--pkg "$PKG" --out "$OUT_ABS/visibility" --expect-content 'P10_VISIBLE_|p10-live-ok')
if [ -n "${PROJECT_NAME:-}" ]; then
  VIS_ARGS+=(--project "$PROJECT_NAME" --expect-file "p10-visible.txt")
fi
# The root the app itself displayed in its file browser (R5), with the project name
# stripped: the external check then verifies the location the app claims rather than
# a layout this script assumes. Without R5's reading, 92 probes the known candidates
# and says so in its own log.
WS_ROOT_GUESS=""
if [ -n "${PROJECT_NAME:-}" ] && [ -n "${FILES_PATH:-}" ]; then
  case "$FILES_PATH" in
    */"$PROJECT_NAME") WS_ROOT_GUESS="${FILES_PATH%/$PROJECT_NAME}" ;;
  esac
fi
[ -n "$WS_ROOT_GUESS" ] && VIS_ARGS+=(--root "$WS_ROOT_GUESS")
# Run it as a RELATIVE path from its own directory: a leading "/" is exactly what
# MSYS drives (and what killed this stage in the owner's bundle - rc=127
# "bash: /p/OpenCodeGUI/phase10/scripts/92-workspace-visibility.sh: No such file or
# directory", on a checkout where the file is present). A relative path is resolved by
# the child's own cwd, so it works for every bash flavour; args keep POSIX form because
# bash, adb and the coreutils here are all MSYS-side. $OUT is made absolute first so the
# redirect does not depend on the cd.
( cd "$DIR" && bash scripts/92-workspace-visibility.sh "${VIS_ARGS[@]}" ) > "$OUT_ABS/visibility.log" 2>&1
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
VIS_VERDICTS=$(grep -acE '^P10D_VISIBILITY_[A-Z_]+ (PASS|FAIL|SKIP)' "$OUT/visibility.log" 2>/dev/null | head -1)
log "visibility driver rc=$VIS_RC verdicts=${VIS_VERDICTS:-0} (verdict lines above; raw output in visibility.log)"
if [ "${VIS_VERDICTS:-0}" = "0" ]; then
  # The check produced NO verdicts, so nothing about visibility was verified. v2
  # reported that as "P10D_STORAGE SKIP :: storage unreadable (expected on some OEM
  # builds)" - a guess that turned a harness failure (the bundle: "bash:
  # /p/scripts/92-workspace-visibility.sh: No such file or directory", rc=127) into
  # what looked like a phone quirk. It is a red verdict with the reason, not a skip.
  VIS_HARNESS=1
  rd VISIBILITY_HARNESS 1 "the visibility check did not run, so nothing about file visibility was verified on this path (rc=$VIS_RC): $(head -1 "$OUT/visibility.log" 2>/dev/null | cut -c1-200)"
  diag "visibility stage did not launch (rc=$VIS_RC). First lines of visibility.log:"
  head -3 "$OUT/visibility.log" 2>/dev/null | while IFS= read -r l; do diag "  $l"; done
else
  VIS_HARNESS=0
fi
log "visibility context: project=${PROJECT_NAME:-<none>} path=${FILES_PATH:-<unknown>} root=${WS_ROOT_GUESS:-<probe>}"
if [ "$VIS_RC" != 0 ]; then
  # A non-zero rc means at least one visibility verdict FAILed; the first line of
  # each failure is already in SUMMARY.txt, so point DIAGNOSIS.txt at the raw log
  # rather than duplicating it (DIAGNOSIS.txt stays empty when everything passes).
  diag "visibility: one or more verdicts failed (rc=$VIS_RC); raw ls/cat output:"
  grep -aE '^P10D_VISIBILITY_[A-Z_]+ FAIL' "$OUT/visibility.log" 2>/dev/null | while IFS= read -r l; do diag "  $l"; done
fi
if [ "$FILES_SEEN" = 1 ]; then
  if [ "$VIS_HARNESS" = 1 ]; then
    rd FILES_APP_AND_SHELL 7 "in-app browser path=$FILES_PATH; the outside-the-app check did not run (see VISIBILITY_HARNESS), so the path could not be confirmed from a shell"
  else
    rd FILES_APP_AND_SHELL "$( [ "$VIS_RC" = 0 ] && echo 0 || echo 1 )" \
      "in-app browser path=$FILES_PATH; shell visibility rc=$VIS_RC (see visibility.log for the raw ls/cat output)"
  fi
fi

# ---- R8: footprint ----------------------------------------------------------
step "R8 footprint"
adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' > "$OUT/meminfo.txt" || true
PSS=$(grep -aoE 'TOTAL PSS: *[0-9]+' "$OUT/meminfo.txt" | head -1 | grep -oE '[0-9]+')
[ -n "$PSS" ] && rd MEMORY 0 "total PSS $((PSS/1024)) MB (see meminfo.txt)" || rd MEMORY 7 "meminfo unavailable"
# The app-private store is deliberately NOT readable from a shell (that is the
# point of R7); the project root is, so the footprint is measured there. Which root
# that is depends on the storage mode - the shared default, a folder the user chose,
# or the app-specific fallback - so the size is read from the location the app
# reported (R5) and the app-specific directory is measured as well when it exists.
STORAGE=""
STORAGE_WHERE=""
for cand in "$WS_ROOT_GUESS" "/storage/emulated/0/Documents/OpenCode" "/storage/emulated/0/Android/data/$PKG/files/workspaces" "/storage/emulated/0/Android/data/$PKG"; do
  [ -n "$cand" ] || continue
  SIZE=$(adb shell du -sh "$cand" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  if [ -n "$SIZE" ]; then
    STORAGE="${STORAGE:+$STORAGE }$cand=$SIZE"
    [ -n "$STORAGE_WHERE" ] || STORAGE_WHERE="$cand"
  fi
done
if [ -n "$STORAGE" ]; then
  rd STORAGE 0 "on-device project storage: $STORAGE"
elif [ "${VIS_HARNESS:-0}" = 1 ]; then
  rd STORAGE 7 "not measured: the visibility stage never ran on this host (VISIBILITY_HARNESS names why) - this is NOT an OEM/storage verdict"
else
  rd STORAGE 7 "neither the app-reported root nor the known candidate roots could be measured with du (see visibility.log; every one of them was tried)"
fi

# ---- R9: crash / obfuscation sweep ------------------------------------------
step "R9 crash and packaging sweep"
adb logcat -d 2>/dev/null > "$OUT/logcat-raw.txt" || true
redact < "$OUT/logcat-raw.txt" > "$OUT/logcat.txt" 2>/dev/null || cp "$OUT/logcat-raw.txt" "$OUT/logcat.txt"
rm -f "$OUT/logcat-raw.txt" 2>/dev/null || true
grep -aoE 'P6_MODEL_AVAILABLE [01][^\r]*' "$OUT/logcat.txt" 2>/dev/null | sort -u >> "$OUT/p10d-model-lines.txt" || true
# NOTE: no `|| echo 0` here. `grep -c` already prints 0 and exits 1 on no match,
# so the fallback appended a SECOND zero and the value became "0\n0" - which made
# this gate fail on a run with nothing in logcat at all.
# The owner's 15:13Z run taught this the hard way: ColorOS's own phone manager
# (com.coloros.phonemanager, an Avast SDK inside it) crashes in the same logcat and
# both gates blamed the artifact. A line-level grep cannot tell processes apart, so
# the matching must be BLOCK-structured: every FATAL block has its own "Process:"
# line, and a packaging-class error line (ClassNotFound/NoSuchMethod/NoClassDef/
# UnsatisfiedLink) is only ours when the line itself or its enclosing fatal block
# carries our package. Anything uncredited is counted separately and reported,
# never silently lost.
FATAL_ATTRIB=$($PY - "$PKG" "$OUT/logcat.txt" <<'AWKPY'
import re, sys
pkg, path = sys.argv[1], sys.argv[2]
try:
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
except OSError:
    lines = []
PKG_ERRS = ("ClassNotFoundException", "NoSuchMethodError",
            "NoClassDefFoundError", "UnsatisfiedLinkError")
# find fatal block starts and their Process: attribution
our_fatals, our_packaging, uncredited, our_lines = 0, 0, 0, []
fatals = [i for i, l in enumerate(lines) if "FATAL EXCEPTION" in l]
bounds = fatals + [len(lines) + 1]
for k, start in enumerate(fatals):
    block = lines[start:min(start + 10, bounds[k + 1])]
    if any(("Process: " + pkg + ",") in b for b in block):
        our_fatals += 1
        our_lines.append(lines[start])
for i, l in enumerate(lines):
    if "FATAL EXCEPTION" in l:
        continue
    if any(e in l for e in PKG_ERRS):
        if pkg in l:
            our_packaging += 1
            our_lines.append(l)
        else:
            uncredited += 1
uncredited += len(fatals) - our_fatals
print(f"{our_fatals} {our_packaging} {uncredited}")
for l in our_lines[:20]:
    print("LINE " + l)
AWKPY
)
read OUR_FATALS OUR_PACKAGING UNCREDITED_REMAIN <<<"$(printf '%s\n' "$FATAL_ATTRIB" | head -1)"
ATTRIB_LINES=$(printf '%s\n' "$FATAL_ATTRIB" | grep -a '^LINE ' | sed 's/^LINE //')
: "${OUR_FATALS:=0}"; : "${OUR_PACKAGING:=0}"; : "${UNCREDITED_REMAIN:=0}"
if [ "$OUR_FATALS" = 0 ] && [ "$OUR_PACKAGING" = 0 ]; then
  rd PACKAGING_SWEEP 0 "no packaging/runtime failure lines ATTRIBUTED to $PKG ($UNCREDITED_REMAIN uncredited device-side lines - e.g. OEM stack crashes - counted for completeness in run.log; none is the artifact's fault)"
else
  printf '%s\n' "$ATTRIB_LINES" >> "$OUT/SUMMARY.txt"
  diag "packaging failure lines attributed to $PKG:"
  printf '%s\n' "$ATTRIB_LINES" | while read -r l; do diag "  $l"; done
  rd PACKAGING_SWEEP 1 "fatals=$OUR_FATALS packaging=$OUR_PACKAGING failure line(s) ATTRIBUTED to $PKG (printed above and in SUMMARY.txt) - read them before uploading"
fi
if [ "$OUR_FATALS" = 0 ]; then
  rd NO_CRASH 0 "no app crash in this session (fatal blocks whose Process: line names $PKG: 0; $UNCREDITED_REMAIN uncredited device-side lines - system/OEM processes - are not the app)"
else
  rd NO_CRASH 1 "$OUR_FATALS fatal block(s) whose Process: line names $PKG in logcat.txt"
fi

# ---- the accessibility channel itself ----------------------------------------
# Phase 10 continuation v3. The v2 run reported a first-run FAIL while the phone
# was sitting on the projects screen, and the only thing that can explain that is
# the dump channel: every wait in this script is decided by a uiautomator dump, so
# a dump that comes back unusable (or without the app's tags) turns a working app
# into a red run with evidence that contradicts itself.
#
# This gate makes that failure mode visible instead of leaving it as an inference:
# it counts dumps that never became readable across all retries (`ui_dump` tries
# three attempts x two dump modes x two paths). A non-zero count alongside a
# failed or skipped UI gate is reported as a FAIL whose text says the harness could
# not see the screen - which is the honest verdict, since nothing was verified. A
# non-zero count on an otherwise green run is reported as a PASS that names the
# number, because every verdict in it was still decided by a real dump.
DUMPS_OK=$(ls -1 "$OUT/ui"/*.xml 2>/dev/null | wc -l | tr -d ' ')
if [ "${DUMP_FAILURES:-0}" = 0 ]; then
  if [ "${READER_FAILURES:-0}" != 0 ]; then
    # The dumps were fine; the READER came back empty some of the time. That is the
    # harness being partly blind, and the owner's bundle shows a run can look clean
    # while every screen read is empty (stderr was discarded then - it is kept now).
    rd UI_DUMP 1 "$DUMPS_OK dump(s) were readable but the reader returned nothing ${READER_FAILURES} time(s): some waits read a screen that may have been on the phone as empty (first failure in reader-stderr.txt, DIAGNOSIS.txt)"
  else
    rd UI_DUMP 0 "every accessibility dump was readable ($DUMPS_OK dump(s) saved in ui/; last acquisition: ${LAST_DUMP_MODE:-n/a})"
  fi
elif [ "${FAIL:-0}" != 0 ] || [ "${SKIP:-0}" != 0 ]; then
  rd UI_DUMP 1 "$DUMP_FAILURES dump(s) could not be read at all (after 3 attempts x --compressed x 2 paths) and this run also has failed/skipped UI gates: the screen could not be seen, so those verdicts are unverified rather than disproven (see DIAGNOSIS.txt)"
else
  rd UI_DUMP 0 "$DUMP_FAILURES dump(s) needed a retry before they became readable (${DUMPS_OK} usable in ui/) - no verdict below depends on a dump that failed"
fi

# ---- R10: bundle ------------------------------------------------------------
step "R10 bundle"
NSHOTS=$(ls -1 "$OUT/screenshots"/*.png 2>/dev/null | wc -l | tr -d ' ')
BLANK=$(grep -ac 'screen=NO' "$OUT/screenshots.log" 2>/dev/null)
if [ "${SHOT_UNVERIFIED:-0}" != 0 ]; then
  # A capture whose validator said nothing is not evidence of a screen. Naming it here
  # matters: on a host where the validator cannot open its own script, an empty verdict
  # used to count as a good frame and this gate went green with nothing checked.
  rd SCREENSHOTS 1 "$NSHOTS screenshots, but ${SHOT_UNVERIFIED} of them could not be VALIDATED (p10d-png.py returned nothing - a host path problem, not a blank screen; see DIAGNOSIS.txt) - the frames are in screenshots/ but nothing here proves what they show"
elif [ "${NSHOTS:-0}" -ge 6 ] && [ "${BLANK:-0}" = 0 ]; then
  rd SCREENSHOTS 0 "$NSHOTS screenshots captured at every step and every one is a real screen (screenshots/)"
elif [ "${NSHOTS:-0}" -ge 2 ]; then
  # Not a blank-frame problem: there are simply fewer captures than the six a run
  # that reaches every step produces. v2's wording ("4 screenshots, of which 0 look
  # blank/off") read as a contradiction because the verdict was about the missing
  # steps, not about the frames.
  rd SCREENSHOTS 1 "$NSHOTS screenshots (blank/off frames: $BLANK): fewer than the 6 a complete run captures, because the run did not reach every step (see DIAGNOSIS.txt for where it stopped)"
else
  rd SCREENSHOTS 1 "only ${NSHOTS:-0} screenshots captured - there is nothing to look at (screenshots.log)"
fi

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
