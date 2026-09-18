#!/usr/bin/env bash
# 70-device-screenshots.sh - store screenshots, captured from the app running on
# a device with `adb screencap`.
#
# The rule this serves: a store screenshot is a picture of the product, taken on
# a device, from a build that passed the gates. Nothing here draws, composes or
# touches up a screen: it launches the app, taps what a user would tap (found by
# the accessibility text of the node, not by hardcoded coordinates), and captures
# what the phone actually displays - including the status bar, because that is
# what the screen looks like.
#
# Where it runs:
#   * in CI, on the Phase 10 emulator (1080x1920 @ 420dpi, so the images satisfy
#     Play's >=320 px / <=2:1 rules and look like a phone);
#   * on the owner's phone, via phase10/scripts/90-real-device-signed.sh, which
#     captures the SAME screens from the SIGNED build - those are the ones to
#     prefer in the listing, because they are the exact artifact users install.
#
# Usage: bash phase10/scripts/70-device-screenshots.sh [--out DIR] [--pkg ID]
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out/screenshots"
PKG="io.github.mcyber12.opencode"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="${2:-}"; shift 2 ;;
    --pkg) PKG="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,21p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done
mkdir -p "$OUT"
LOG="$DIR/out/evidence/p10-screenshots.log"
mkdir -p "$(dirname "$LOG")"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

command -v adb >/dev/null 2>&1 || { echo "FATAL: adb not on PATH"; exit 2; }
adb shell true >/dev/null 2>&1 || { echo "FATAL: no device"; exit 2; }

CAPTURED=0
shot() { # $1 = filename ; captures the CURRENT screen
  local f="$OUT/$1"
  if adb exec-out screencap -p > "$f" 2>/dev/null && [ -s "$f" ]; then
    local dim
    # ImageMagick may be absent on the owner's machine; the dimensions are then read
    # from the PNG IHDR by 60-store-assets.sh, which is the actual gate.
    dim=$(identify -format "%wx%h" "$f" 2>/dev/null || python3 -c 'import struct,sys;d=open(sys.argv[1],"rb").read(33);print("%dx%d"%struct.unpack(">II",d[16:24]))' "$f" 2>/dev/null || echo "?x?")
    CAPTURED=$((CAPTURED+1))
    log "captured $1 ($dim)"
    return 0
  fi
  rm -f "$f" 2>/dev/null || true
  log "capture FAILED for $1 (screencap produced nothing)"
  return 1
}

# Dump the accessibility tree and print the centre of the first node whose text or
# content-desc contains $1 (case-insensitive). Compose publishes semantics to the
# accessibility tree, which is what makes text-guided tapping possible at all.
find_node_center() {
  local needle="$1"
  adb shell uiautomator dump /sdcard/p10-ui.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/p10-ui.xml 2>/dev/null > "$DIR/out/p10-ui.xml" || return 1
  python3 - "$DIR/out/p10-ui.xml" "$needle" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
needle = sys.argv[2].lower()
best = None
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    text = (re.search(r'text="([^"]*)"', tag).group(1) if re.search(r'text="([^"]*)"', tag) else "")
    desc = (re.search(r'content-desc="([^"]*)"', tag).group(1) if re.search(r'content-desc="([^"]*)"', tag) else "")
    hay = (text + " " + desc).lower()
    if needle in hay:
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bounds:
            continue
        x1, y1, x2, y2 = (int(bounds.group(i)) for i in range(1, 5))
        if x2 <= x1 or y2 <= y1:
            continue
        best = ((x1 + x2) // 2, (y1 + y2) // 2)
        break
if best:
    print("%d %d" % best)
    sys.exit(0)
sys.exit(1)
PY
}

tap_text() { # $1 = text/desc to find ; $2 = what it is (for the log)
  local c
  c=$(find_node_center "$1") || { log "could not find '$1' on screen ($2)"; return 1; }
  log "tapping '$1' at $c ($2)"
  adb shell input tap $c >/dev/null 2>&1
  sleep 2
}

settle() { sleep "${1:-2}"; }

# ---- 1. welcome (fresh launch of the release-shaped build) -------------------
log "=== launching $PKG ==="
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
settle 6
shot 01-welcome.png || true

# ---- 2. projects (Continue -> the project list) -----------------------------
if tap_text "Continue" "welcome action"; then
  settle 4
  shot 02-projects.png || true
fi

# ---- 3. create a project, land in the conversation --------------------------
if tap_text "New project" "projects action"; then
  settle 2
  # Type into whatever text field is focused after the sheet opens; if the field
  # is not focused yet, tap the node whose hint is the placeholder.
  if find_node_center "my-app" >/dev/null 2>&1; then
    c=$(find_node_center "my-app"); adb shell input tap $c >/dev/null 2>&1; sleep 1
  fi
  adb shell input text "demo-app" >/dev/null 2>&1
  settle 1
  shot 03-project-created-form.png || true
  if tap_text "Create project" "create action"; then
    settle 6
    shot 04-chat-empty.png || true
  fi
fi

# ---- 4. settings, and the About / open-source surface -----------------------
if tap_text "Settings" "settings action"; then
  settle 3
  shot 05-settings.png || true
  # Scroll to the bottom for the About / Open source sections. The swipe is derived
  # from the real screen size (a hardcoded 540/1600 was CI-emulator-only and would
  # scroll a phone by the wrong amount, or not at all).
  SIZE=$(adb shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/.*: *//p' | tail -1)
  SW=${SIZE%x*}; SH=${SIZE#*x}
  case "$SW$SH" in ''|*[!0-9]*) SW=1080; SH=1920 ;; esac
  SCX=$((SW / 2)); SCY1=$((SH * 85 / 100)); SCY2=$((SH * 25 / 100))
  adb shell input swipe $SCX $SCY1 $SCX $SCY2 300 >/dev/null 2>&1
  sleep 1
  adb shell input swipe $SCX $SCY1 $SCX $SCY2 300 >/dev/null 2>&1
  sleep 2
  shot 06-settings-open-source.png || true
fi

log "captured=$CAPTURED"
ls -la "$OUT" >> "$LOG" 2>&1 || true
# The caller (60-store-assets.sh) validates sizes/ratios and decides the verdict;
# this script reports failure only when it captured nothing at all, because a
# partial set is still useful and the validator is the gate.
[ "$CAPTURED" -ge 2 ] || { echo "P10_SHOTS FAIL: captured=$CAPTURED (need at least 2)"; exit 1; }
echo "P10_SHOTS PASS: captured=$CAPTURED screenshots into $OUT (validated by 60-store-assets.sh)"
exit 0
