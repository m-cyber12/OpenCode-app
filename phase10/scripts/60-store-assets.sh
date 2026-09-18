#!/usr/bin/env bash
# 60-store-assets.sh - turn the screenshots a DEVICE RUN produced into the Play
# listing package, and refuse anything that would fail Play's requirements.
#
# The Phase 10 rule this script enforces: a store screenshot comes from a real
# device run of the real build. There is no placeholder mode and no synthetic
# mock-up path - if no device run has happened, this script fails and says so,
# because a hand-drawn "screenshot" in a store listing is a claim about the app
# that nobody verified.
#
# Where the images come from:
#   * the Phase 6 UI gates capture PNGs on device (FirstRunUiGatesTest F1-F4 plus
#     the chat gates U1-U8) into the app's filesDir/screenshots, and
#     phase6/scripts/20-ui-gates.sh pulls them into <phase>/out/evidence/screenshots/
#   * Phase 10's run copies them into docs/progress/phase10-evidence/<stage>/screenshots/
#
# Usage:
#   bash phase10/scripts/60-store-assets.sh [--from DIR] [--out DIR] [--min N]
#     --from DIR   where to look for device screenshots (default: search
#                  docs/progress/phase10-evidence for any screenshots/ dir)
#     --out DIR    destination (default docs/store/screenshots)
#     --min N      minimum number of screenshots Play needs (default 2)
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
FROM=""
OUT="$ROOT/docs/store/screenshots"
MIN=2
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --min) MIN="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,21p' "$DIR/scripts/$(basename "$0")"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done

fail() { echo "FAIL $*" >&2; exit 1; }

# ImageMagick is a convenience, not a dependency: the PNG header carries the size,
# and the whole point of this script is to be runnable on the owner's machine (and on
# a CI image that may not have ImageMagick) without weakening the check.
if command -v identify >/dev/null 2>&1; then
  dims() { identify -format "%w %h" "$1" 2>/dev/null; }
elif command -v python3 >/dev/null 2>&1; then
  dims() { python3 -c 'import struct,sys
d=open(sys.argv[1],"rb").read(33)
print("%d %d"%struct.unpack(">II",d[16:24]))' "$1" 2>/dev/null; }
else
  fail "neither ImageMagick's identify nor python3 is available to read image sizes"
fi

# ---- locate the device screenshots -----------------------------------------
SRCS=()
if [ -n "$FROM" ]; then
  [ -d "$FROM" ] || fail "--from $FROM is not a directory"
  SRCS+=("$FROM")
else
  while IFS= read -r d; do SRCS+=("$d"); done < <(find "$ROOT/docs/progress/phase10-evidence" -type d -name screenshots 2>/dev/null | sort)
  # The Phase 6/8/9 evidence trees are valid fallbacks ONLY if they are from a
  # Phase 10 run: the pre-Phase-10 screenshots show the old applicationId's build.
  # They are accepted, but labelled, because the UI they show is what Phase 6
  # gated and Phase 10 re-verified - never silently mixed.
  while IFS= read -r d; do SRCS+=("$d"); done < <(find "$ROOT/docs/progress/phase9-evidence" -type d -name screenshots 2>/dev/null | sort)
fi
[ "${#SRCS[@]}" -gt 0 ] || fail "no screenshots/ directory found. Run the Phase 10 workflow (which runs the Phase 6 UI gates on the release-shaped build) first, or pass --from DIR."

echo "sources:"
for d in "${SRCS[@]}"; do echo "  $d ($(ls -1 "$d"/*.png 2>/dev/null | wc -l) png)"; done

mkdir -p "$OUT" || fail "cannot create $OUT"
rm -f "$OUT"/*.png 2>/dev/null || true

# ---- copy + validate -------------------------------------------------------
COPIED=0; REJECTED=0
for d in "${SRCS[@]}"; do
  for f in "$d"/*.png; do
    [ -f "$f" ] || continue
    # `identify -format` prints no trailing newline, and `read` fails at EOF without
    # one: the here-string below would have made every file unreadable, i.e. a silent
    # "0 rejected" that looks like an empty directory.
    read -r W H <<< "$(dims "$f")"
    base="$(basename "$f")"
    # Play: 320..3840 px per side, aspect ratio 16:9 or 9:16 (with tolerance),
    # PNG or JPEG, no alpha requirement. Narrow-tolerance on purpose: an emulator
    # screenshot of a non-standard window size is the classic submission rejection.
    if [ -z "${W:-}" ] || [ -z "${H:-}" ]; then REJECTED=$((REJECTED+1)); echo "  reject $base (unreadable)"; continue; fi
    if [ "$W" -lt 320 ] || [ "$H" -lt 320 ] || [ "$W" -gt 3840 ] || [ "$H" -gt 3840 ]; then
      REJECTED=$((REJECTED+1)); echo "  reject $base (${W}x${H}: outside Play's 320-3840 px range)"; continue
    fi
    ratio_ok=$(python3 - "$W" "$H" <<'PY'
import sys
w, h = int(sys.argv[1]), int(sys.argv[2])
r = w / h
targets = (16/9, 9/16)
print("1" if any(abs(r - t) <= 0.03 for t in targets) else "0")
PY
)
    if [ "$ratio_ok" != "1" ]; then
      REJECTED=$((REJECTED+1)); echo "  reject $base (${W}x${H}: ratio is not 16:9 or 9:16; Play rejects a listing image that is neither)"; continue
    fi
    cp "$f" "$OUT/$base" || continue
    COPIED=$((COPIED+1))
    echo "  keep   $base (${W}x${H})"
  done
done

# ---- icons -----------------------------------------------------------------
for pair in "icon-512.png:512x512" "feature-graphic-1024x500.png:1024x500"; do
  n="${pair%%:*}"; want="${pair##*:}"
  p="$ROOT/docs/store/$n"
  [ -f "$p" ] || fail "missing $p (see docs/STORE-LISTING.md)"
  got=$(dims "$p"); got="${got// /x}"
  [ "$got" = "$want" ] || fail "$n is $got, Play requires $want"
  echo "  ok     $n ($got)"
done

# ---- verdict ---------------------------------------------------------------
echo
if [ "$COPIED" -lt "$MIN" ]; then
  echo "STORE-ASSETS FAIL: $COPIED usable screenshots (Play needs at least $MIN), $REJECTED rejected"
  echo "  screenshots must come from a real device run of the Phase 10 build:"
  echo "    bash phase10/scripts/00-run-phase10.sh      (CI: the whole pipeline)"
  echo "    bash phase10/scripts/90-real-device-signed.sh --apk <signed apk>   (on a phone)"
  exit 1
fi
echo "STORE-ASSETS PASS: $COPIED screenshots in $OUT ($REJECTED rejected)"
echo "NOTE: these files are store assets, not evidence. The gate verdicts that"
echo "      produced them are in docs/progress/phase10-evidence/."
