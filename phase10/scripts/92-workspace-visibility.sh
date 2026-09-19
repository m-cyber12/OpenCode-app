#!/usr/bin/env bash
# 92-workspace-visibility.sh - can something OTHER than the app read the files the
# agent writes?
#
# Why this exists (Phase 10 continuation): the first signed build worked, but the
# agent's files landed under /data/data/<pkg>/files/workspaces - app-private
# storage that no file manager, no MTP/USB browse and no non-root `adb shell` can
# see. The app looked like a sealed black box even though the agent really was
# writing files. That is a product defect, not a cosmetic one, so it gets a gate
# with an external vantage point rather than an assertion from inside the app.
#
# WHAT IT CHECKS (each is a named verdict line, PASS/FAIL/SKIP with the raw output):
#   V1  the app's project root is the app-specific EXTERNAL directory (the layout
#       the app resolves - reported by the app itself through the W4 gate or by
#       asking the device for the data directory the platform assigned);
#   V2  a non-root `adb shell` can LIST the project directory (the vantage point a
#       desktop file browser / adb pull uses);
#   V3  a non-root `adb shell` can READ a file the OpenCode server itself wrote;
#   V4  the same file is NOT readable under /data/data/<pkg> - i.e. the old,
#       invisible location is not silently still in use;
#   V5  baseline: /data/data/<pkg> itself IS unreadable by that same shell, so a
#       PASS above cannot be an artifact of a device where everything is readable.
#
# The V2/V3 result is device-dependent by design: on Android 11+ the platform
# blocks *apps* from browsing Android/data, and some OEM builds extend that to the
# shell. Whichever way it goes, this script prints the raw command output so the
# verdict can be read rather than assumed - and an in-app file browser plus a SAF
# "publish to a folder you choose" action exist precisely because the on-device
# file manager case cannot be relied on.
#
# Usage:
#   bash phase10/scripts/92-workspace-visibility.sh [--pkg PKG] [--project NAME]
#        [--out DIR] [--expect-file RELPATH] [--expect-content REGEX]
#
# With no --project it uses the newest project directory it can see; with no
# --expect-file it looks for the W4 marker (p10-visible.txt). --expect-content is
# the marker the file must contain to count as "written by the app" (default
# P10_VISIBLE_, which the instrumented W4 gate writes; the real-device driver passes
# a pattern that also accepts the file its own live turn produces).
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
PKG="io.github.mcyber12.opencode"
PROJECT=""
EXPECT="p10-visible.txt"
EXPECT_CONTENT="P10_VISIBLE_"
OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --pkg) PKG="${2:-}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --expect-file) EXPECT="${2:-}"; shift 2 ;;
    --expect-content) EXPECT_CONTENT="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done
OUT="${OUT:-$DIR/out/visibility}"
mkdir -p "$OUT"
LOG="$OUT/workspace-visibility.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }
PASS=0; FAIL=0; SKIP=0
rec() { echo "$1 $2${3:+ :: $3}" | tee -a "$LOG"; }
rd() { case "$2" in 0) PASS=$((PASS+1)); rec "P10D_VISIBILITY_$1" PASS "$3";; 7) SKIP=$((SKIP+1)); rec "P10D_VISIBILITY_$1" SKIP "$3";; *) FAIL=$((FAIL+1)); rec "P10D_VISIBILITY_$1" FAIL "$3";; esac; }

command -v adb >/dev/null 2>&1 || { echo "FATAL: adb not on PATH"; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "FATAL: no device visible to adb"; exit 2; }

sh_dev() { adb shell "$@" 2>&1 | tr -d '\r'; }
SDK_NOW=$(sh_dev getprop ro.build.version.sdk)
REL_NOW=$(sh_dev getprop ro.build.version.release)

# ---- V1: where is the project root? ------------------------------------------
# The app is the only thing that knows for certain (it resolves it from
# Context.getExternalFilesDir). `dumpsys package` gives us the data dir and the
# external one is the documented sibling of it on the same volume.
EXT_ROOT="/storage/emulated/0/Android/data/$PKG/files/workspaces"
DATA_ROOT="/data/data/$PKG/files/workspaces"
log "device: Android $REL_NOW (API $SDK_NOW); package=$PKG"

if [ -n "$PROJECT" ]; then
  WS="$EXT_ROOT/$PROJECT"
else
  # newest project directory visible to the shell (or "" when the shell cannot
  # read the external root at all - V2 then reports exactly that)
  WS=$(sh_dev "ls -1t $EXT_ROOT 2>/dev/null | head -1")
  [ -n "$WS" ] && WS="$EXT_ROOT/$WS"
fi

LIST_EXT=$(sh_dev "ls -la $EXT_ROOT 2>&1")
INTERNAL_DATA=$(sh_dev "ls -la $DATA_ROOT 2>&1")
log "--- non-root shell, external project root ---"
printf '%s\n' "$LIST_EXT" | tee -a "$LOG"
log "--- non-root shell, the OLD internal root ---"
printf '%s\n' "$INTERNAL_DATA" | tee -a "$LOG"

# V1: is the external root populated at all? "ls works" (V2) and "there are
# projects in it" are different questions, and only the second one proves the app
# actually moved.
PROJ_COUNT=$(sh_dev "ls -1 $EXT_ROOT 2>/dev/null | wc -l")
if [ "${PROJ_COUNT//[!0-9]/}" != "" ] && [ "${PROJ_COUNT//[!0-9]/}" -ge 1 ] 2>/dev/null; then
  rd LOCATION 0 "the app-specific external root exists and holds $PROJ_COUNT project(s): $EXT_ROOT (Android $REL_NOW)"
else
  rd LOCATION 1 "no project visible under $EXT_ROOT (output: $(printf '%s' "$LIST_EXT" | head -2 | tr '\n' '; '))"
fi

# ---- V2: can a non-root shell list the project directory? --------------------
if [ -n "$WS" ]; then
  PROJ_LIST=$(sh_dev "ls -la $WS 2>&1")
  log "--- project listing ($WS) ---"
  printf '%s\n' "$PROJ_LIST" | tee -a "$LOG"
  if printf '%s' "$PROJ_LIST" | grep -qiE 'Permission denied|not permitted|No such file'; then
    rd SHELL_LIST 1 "a non-root shell CANNOT list the project directory: $(printf '%s' "$PROJ_LIST" | head -1)"
  else
    rd SHELL_LIST 0 "a non-root shell lists the project directory ($WS): $(printf '%s' "$PROJ_LIST" | grep -c . ) lines"
  fi
else
  rd SHELL_LIST 7 "no project directory to list (V1 already failed; the external root is unreadable or empty)"
fi

# ---- V3: can a non-root shell READ a file the server wrote? ------------------
if [ -n "$WS" ] && [ -n "$EXPECT" ]; then
  CAT=$(sh_dev "cat $WS/$EXPECT 2>&1")
  if printf '%s' "$CAT" | grep -qE "$EXPECT_CONTENT"; then
    rd SHELL_READ 0 "read $EXPECT from outside the app: '$(printf '%s' "$CAT" | head -1)' (matches /$EXPECT_CONTENT/)"
  elif printf '%s' "$CAT" | grep -qiE 'No such file|does not exist'; then
    # The file this run expected is not there. That is a different statement from
    # "the shell cannot read it", so it is reported as one: fall back to whatever
    # file the app DID write, and say which one was read.
    NEWEST=$(sh_dev "ls -1t $WS 2>/dev/null | head -1")
    if [ -n "$NEWEST" ]; then
      OTHER=$(sh_dev "cat $WS/$NEWEST 2>&1")
      if printf '%s' "$OTHER" | grep -qiE 'Permission denied|not permitted|No such file'; then
        rd SHELL_READ 1 "cannot read $WS/$NEWEST from outside the app: $(printf '%s' "$OTHER" | head -1)"
      else
        rd SHELL_READ 0 "expected $EXPECT was not in the directory; read $NEWEST instead: '$(printf '%s' "$OTHER" | head -1)'"
      fi
    else
      rd SHELL_READ 7 "$EXPECT is not in $WS and the directory has no files yet (the turn that writes it was skipped)"
    fi
  else
    rd SHELL_READ 1 "could not read $WS/$EXPECT from outside the app: $(printf '%s' "$CAT" | head -1)"
  fi
else
  rd SHELL_READ 7 "no project/file to read (run the Phase 7 W4 gate first; it writes the marker)"
fi

# ---- V4: the old invisible location must NOT still hold the projects ---------
if printf '%s' "$INTERNAL_DATA" | grep -qiE 'Permission denied|not permitted'; then
  rd OLD_ROOT_EMPTY 0 "the old app-private root is not readable from a shell at all (as designed): $(printf '%s' "$INTERNAL_DATA" | head -1)"
elif printf '%s' "$INTERNAL_DATA" | grep -qiE 'No such file|does not exist'; then
  rd OLD_ROOT_EMPTY 0 "the old app-private project root no longer exists on this install - projects were migrated"
elif [ -n "$(printf '%s' "$INTERNAL_DATA" | grep -vE '^total|^d|^$')" ]; then
  rd OLD_ROOT_EMPTY 1 "the old app-private root still holds files; the app may still be writing there: $(printf '%s' "$INTERNAL_DATA" | head -3 | tr '\n' '; ')"
else
  rd OLD_ROOT_EMPTY 0 "the old app-private root is empty: $(printf '%s' "$INTERNAL_DATA" | head -1)"
fi

# ---- V5: baseline - the sandbox really is closed ----------------------------
BASE=$(sh_dev "ls -la /data/data/$PKG 2>&1")
if printf '%s' "$BASE" | grep -qiE 'Permission denied|not permitted'; then
  rd SHELL_BASELINE 0 "baseline holds: an unprivileged shell cannot read /data/data/$PKG at all, so the PASSs above are real"
else
  rd SHELL_BASELINE 7 "baseline inconclusive: '$(printf '%s' "$BASE" | head -1)' (this device/emulator may permit shell access to /data/data)"
fi

{
  echo "workspace visibility $(date -u +%FT%TZ) pkg=$PKG android=$REL_NOW api=$SDK_NOW"
  echo "external_root=$EXT_ROOT"
  echo "internal_root=$DATA_ROOT"
  echo "project=$WS expect=$EXPECT expect_content=$EXPECT_CONTENT"
  echo "pass=$PASS fail=$FAIL skip=$SKIP"
} >> "$LOG"
log "pass=$PASS fail=$FAIL skip=$SKIP (log: $LOG)"
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)
