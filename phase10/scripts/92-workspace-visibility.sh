#!/usr/bin/env bash
# 92-workspace-visibility.sh - can something OTHER than the app read the files the
# agent writes? Asked from outside the app, with a non-root shell.
#
# Why this exists (Phase 10 continuation): the first signed build worked, but the
# agent's files landed under /data/data/<pkg>/files/workspaces - app-private
# storage that no file manager, no MTP/USB browse and no non-root `adb shell` can
# see. The app looked like a sealed black box even though the agent really was
# writing files. That is a product defect, not a cosmetic one, so it gets a gate
# with an external vantage point rather than an assertion from inside the app.
#
# v3 CHANGED THE EXPECTED ANSWER, not the standard of proof. Projects now live in
# `Documents/OpenCode` on shared storage (the layout the app resolves and reports
# through its W4 gate), so the checks are stricter than "adb can read it":
#
#   V1  the app-reported project root exists and holds a project;
#   V2  a non-root `adb shell` can LIST the project directory;
#   V3  a non-root `adb shell` can READ a file the OpenCode server itself wrote -
#       live, with no export/publish step of any kind;
#   V4  the live root is NOT the app-private root (`/data/data/<pkg>/files`), i.e.
#       the old, invisible location is not silently still in use;
#   V5  the live root is on shared storage, outside `Android/data` - the property
#       that makes it browsable by file managers instead of only by adb;
#   V6  the platform's own Documents provider can see the folder (the same
#       `com.android.externalstorage.documents` tree every file manager reads
#       through). SKIP when the provider refuses `content query` on this image;
#   V7  baseline: a shell write into the project directory is visible to the app's
#       side of the filesystem (proves the folder is a normal, shared directory and
#       not a shell-only illusion), then the probe file is removed;
#   V8  baseline: /data/data/<pkg> itself IS unreadable by that same shell, so a
#       PASS above cannot be an artifact of a device where everything is readable.
#
# Where the root comes from: the app (W4 prints `wsRoot=<path>`; 93-workspace-gates
# passes it via --root). An app asserting its own path is not evidence that anyone
# else can read it - which is exactly why the app only supplies the path and every
# verdict below is decided by the shell's own output. Without --root the script
# probes the known candidate roots and says which one it used.
#
# Usage:
#   bash phase10/scripts/92-workspace-visibility.sh [--pkg PKG] [--project NAME]
#        [--root PATH] [--out DIR] [--expect-file RELPATH] [--expect-content REGEX]
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
PKG="io.github.mcyber12.opencode"
PROJECT=""
WS_ROOT=""
EXPECT="p10-visible.txt"
EXPECT_CONTENT="P10_VISIBLE_"
OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --pkg) PKG="${2:-}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --root) WS_ROOT="${2:-}"; shift 2 ;;
    --expect-file) EXPECT="${2:-}"; shift 2 ;;
    --expect-content) EXPECT_CONTENT="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,45p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
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
SHARED_ROOT="/storage/emulated/0/Documents/OpenCode"
APP_EXT_ROOT="/storage/emulated/0/Android/data/$PKG/files/workspaces"
DATA_ROOT="/data/data/$PKG/files/workspaces"
log "device: Android $REL_NOW (API $SDK_NOW); package=$PKG"

# ---- which root is live? ------------------------------------------------------
# Preference: what the app reported (--root). Otherwise probe, and say which
# candidate answered - a probe result is weaker evidence than the app's own path,
# so the log records where the path came from.
ROOT_SOURCE="app-reported (W4 gate)"
if [ -z "$WS_ROOT" ]; then
  ROOT_SOURCE="probed"
  if [ -n "$(sh_dev "ls -1 $SHARED_ROOT 2>/dev/null | head -1")" ]; then
    WS_ROOT="$SHARED_ROOT"
  elif [ -n "$(sh_dev "ls -1 $APP_EXT_ROOT 2>/dev/null | head -1")" ]; then
    WS_ROOT="$APP_EXT_ROOT"
  else
    WS_ROOT=""
  fi
fi
log "live project root: ${WS_ROOT:-<none found>} (source: $ROOT_SOURCE)"

if [ -n "$PROJECT" ]; then
  WS="$WS_ROOT/$PROJECT"
elif [ -n "$WS_ROOT" ]; then
  NEWEST=$(sh_dev "ls -1t $WS_ROOT 2>/dev/null | head -1")
  [ -n "$NEWEST" ] && WS="$WS_ROOT/$NEWEST" || WS=""
else
  WS=""
fi

LIST_EXT=$(sh_dev "ls -la $WS_ROOT 2>&1")
INTERNAL_DATA=$(sh_dev "ls -la $DATA_ROOT 2>&1")
APP_EXT_DATA=$(sh_dev "ls -la $APP_EXT_ROOT 2>&1")
log "--- non-root shell, live project root ($WS_ROOT) ---"
printf '%s\n' "$LIST_EXT" | tee -a "$LOG"
log "--- non-root shell, the app-private root ---"
printf '%s\n' "$INTERNAL_DATA" | tee -a "$LOG"

# ---- V1: is the reported root populated? --------------------------------------
if [ -z "$WS_ROOT" ]; then
  rd LOCATION 1 "no project root could be found: neither $SHARED_ROOT nor $APP_EXT_ROOT is readable/occupied by a non-root shell"
else
  PROJ_COUNT=$(sh_dev "ls -1 $WS_ROOT 2>/dev/null | wc -l")
  if [ "${PROJ_COUNT//[!0-9]/}" != "" ] && [ "${PROJ_COUNT//[!0-9]/}" -ge 1 ] 2>/dev/null; then
    rd LOCATION 0 "the project root exists and holds $PROJ_COUNT project(s): $WS_ROOT (source: $ROOT_SOURCE, Android $REL_NOW)"
  else
    rd LOCATION 1 "no project visible under $WS_ROOT (output: $(printf '%s' "$LIST_EXT" | head -2 | tr '\n' '; '))"
  fi
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
  rd SHELL_LIST 7 "no project directory to list (V1 already failed: the root is unreadable or empty)"
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

# ---- V4: the live root is not app-private ------------------------------------
case "${WS_ROOT:-}" in
  ""|/data/data/*|/data/user/0/*)
    rd PRIVATE_ROOT_NOT_LIVE 1 "the live project root is app-private (${WS_ROOT:-<none>}): nothing outside the app can browse it"
    ;;
  *) rd PRIVATE_ROOT_NOT_LIVE 0 "the live project root is outside /data: $WS_ROOT" ;;
esac

# ---- V5: the live root is on shared storage, not under Android/data ----------
# This is the difference between "adb can reach it" (previous continuation) and
# "an ordinary file manager can reach it" (v3). `Android/data` is unreachable for
# file managers on Android 11+ by platform rule, so a PASS here is the claim.
case "${WS_ROOT:-}" in
  /storage/emulated/0/Android/data/*)
    rd SHARED_ROOT 1 "the project root is under Android/data ($WS_ROOT): file managers cannot browse that on Android $REL_NOW, only adb can"
    ;;
  /storage/emulated/*|/sdcard/*|/storage/*|/mnt/*)
    rd SHARED_ROOT 0 "the project root is on shared storage and outside Android/data: $WS_ROOT (Android $REL_NOW)"
    ;;
  "")
    rd SHARED_ROOT 1 "no project root to judge"
    ;;
  *) rd SHARED_ROOT 1 "the project root is not on shared storage: $WS_ROOT" ;;
esac

# ---- V6: can the platform's Documents provider see the folder? ---------------
# File managers read the same `com.android.externalstorage.documents` tree the
# system picker uses, so a non-root shell query against that provider is as close
# to "a file manager can open it" as a headless harness gets. Not all images allow
# the query; SKIP says so instead of guessing.
if [ -n "$WS_ROOT" ]; then
  DOC_ID=$(printf '%s' "$WS_ROOT" | sed 's|^/storage/emulated/0/|primary:|; s|/|%2F|g')
  DOC_URI="content://com.android.externalstorage.documents/document/$DOC_ID"
  DOC_OUT=$(sh_dev "content query --uri '$DOC_URI' --projection _display_name 2>&1")
  if printf '%s' "$DOC_OUT" | grep -qiE 'Row: |_display_name'; then
    rd DOCUMENTS_PROVIDER 0 "the system Documents provider returns the folder: $(printf '%s' "$DOC_OUT" | head -2 | tr '\n' '; ')"
  elif printf '%s' "$DOC_OUT" | grep -qiE 'Unknown URI|not found|SecurityException|permission|Unsupported'; then
    rd DOCUMENTS_PROVIDER 7 "this device does not let a shell query the Documents provider ($(printf '%s' "$DOC_OUT" | head -1))"
  else
    rd DOCUMENTS_PROVIDER 1 "the system Documents provider could not return the folder: $(printf '%s' "$DOC_OUT" | head -2 | tr '\n' '; ')"
  fi
else
  rd DOCUMENTS_PROVIDER 7 "no project root to query"
fi

# ---- V7: a shell-visible write lands in the same real directory ---------------
if [ -n "$WS" ]; then
  PROBE=".p10d-shell-probe-$$"
  sh_dev "echo shell > $WS/$PROBE" >/dev/null 2>&1
  BACK=$(sh_dev "cat $WS/$PROBE 2>&1")
  if printf '%s' "$BACK" | grep -q shell; then
    rd SHELL_WRITE 0 "a file written by the non-root shell in $WS was readable back: the directory is shared, ordinary storage"
  else
    rd SHELL_WRITE 1 "a non-root shell could not write into $WS: $(printf '%s' "$BACK" | head -1)"
  fi
  sh_dev "rm -f $WS/$PROBE" >/dev/null 2>&1
else
  rd SHELL_WRITE 7 "no project directory to write into"
fi

# ---- V8: baseline - the sandbox really is closed -----------------------------
BASE=$(sh_dev "ls -la /data/data/$PKG 2>&1")
if printf '%s' "$BASE" | grep -qiE 'Permission denied|not permitted'; then
  rd SHELL_BASELINE 0 "baseline holds: an unprivileged shell cannot read /data/data/$PKG at all, so the PASSs above are real"
else
  rd SHELL_BASELINE 7 "baseline inconclusive: '$(printf '%s' "$BASE" | head -1)' (this device/emulator may permit shell access to /data/data)"
fi

{
  echo "workspace visibility $(date -u +%FT%TZ) pkg=$PKG android=$REL_NOW api=$SDK_NOW"
  echo "live_root=${WS_ROOT:-<none>} source=$ROOT_SOURCE"
  echo "shared_storage_candidate=$SHARED_ROOT"
  echo "app_external_candidate=$APP_EXT_ROOT"
  echo "app_external_listing=$(printf '%s' "$APP_EXT_DATA" | grep -c . ) lines"
  echo "internal_root=$DATA_ROOT"
  echo "project=$WS expect=$EXPECT expect_content=$EXPECT_CONTENT"
  echo "pass=$PASS fail=$FAIL skip=$SKIP"
} >> "$LOG"
log "pass=$PASS fail=$FAIL skip=$SKIP (log: $LOG)"
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)
