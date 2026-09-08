#!/usr/bin/env bash
# 30-static-checks.sh - everything Phase 7 can verify WITHOUT an emulator, run
# before the expensive stages so a bad push fails in seconds instead of after a
# 40-minute payload build.
#
# Same discipline as Phase 6 (the development sandbox has no JDK/Gradle, so these
# mechanical checks are the only local verification new Kotlin gets before CI
# compiles it), plus the Phase 6 UI rules now also cover the Phase 7 screens.
#
# Writes phase7/out/static-checks.log (copied into evidence as
# p7-static-checks.log) and exits non-zero if any family fails.
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
LOG="$OUT/static-checks.log"
mkdir -p "$OUT"
: > "$LOG"
cd "$ROOT" || exit 1

RC=0

run() { # $1 = label, rest = command
  local label="$1"; shift
  {
    echo
    echo "=== $label ==="
    "$@"
    local rc=$?
    if [ "$rc" = 0 ]; then echo "OK   $label"; else echo "FAIL $label (rc=$rc)"; fi
    return "$rc"
  } 2>&1 | tee -a "$LOG"
  [ "${PIPESTATUS[0]}" = 0 ] || RC=1
  return 0
}

echo "=== Phase 7 static checks $(date -u +%FT%TZ) ===" | tee -a "$LOG"

# ---- 1. shell + python syntax of every phase script --------------------------
find "$ROOT/spike" "$ROOT/phase3" "$ROOT/phase4" "$ROOT/phase5" "$ROOT/phase6" "$ROOT/phase7" \
     -name '*.sh' -type f 2>/dev/null | sort > "$OUT/shell-files.txt"
SHELLSYN=0
while IFS= read -r f; do
  bash -n "$f" >> "$LOG" 2>&1 || { echo "FAIL bash -n $f" | tee -a "$LOG"; SHELLSYN=1; }
done < "$OUT/shell-files.txt"
if [ "$SHELLSYN" = 0 ]; then
  echo "OK   bash -n on $(wc -l < "$OUT/shell-files.txt") phase scripts" | tee -a "$LOG"
else
  RC=1
fi

PYSYN=0
while IFS= read -r f; do
  python3 -m py_compile "$f" >> "$LOG" 2>&1 || { echo "FAIL py_compile $f" | tee -a "$LOG"; PYSYN=1; }
done < <(find "$ROOT/phase5" "$ROOT/phase6" "$ROOT/phase7" -name '*.py' -type f 2>/dev/null | sort)
if [ "$PYSYN" = 0 ]; then echo "OK   py_compile on every phase python script" | tee -a "$LOG"; else RC=1; fi

# ---- 2. ASCII-only for CI-facing files (recurring operational note) ----------
python3 "$ROOT/phase6/scripts/check-ascii.py" "$ROOT" 2>&1 | tee -a "$LOG"
[ "${PIPESTATUS[0]}" = 0 ] || RC=1

# ---- 3. workflow YAML: parse, and quote every step name with a colon+space ---
# Phase 7 checks only the workflow this session owns (phase7/workflow). The
# Phase 6 checker scans phase6/workflow + .github/workflows, whose installed
# files still trigger on the now-merged Phase 6 branch - a stale-workflow
# condition, not a Phase 7 defect, so it is not folded into this phase's verdict.
python3 "$ROOT/phase7/scripts/check-workflow.py" "$ROOT" 2>&1 | tee -a "$LOG"
[ "${PIPESTATUS[0]}" = 0 ] || RC=1

# ---- 4. Kotlin lexical sanity (no compiler available locally) ---------------
run "kotlin nested-block-comment scan (phase5 lesson)" \
  python3 "$ROOT/phase5/scripts/check-kotlin-comments.py" "$ROOT/app/src"
run "kotlin bracket/annotation balance" \
  python3 "$ROOT/phase6/scripts/check-kotlin-balance.py" "$ROOT/app/src"

# ---- 5. the Phase 6 UI rules, now covering the Phase 7 screens ---------------
run "ui strings + resources" bash "$ROOT/phase6/scripts/check-ui-strings.sh"
run "ui accessibility (contentDescription / labels)" \
  python3 "$ROOT/phase6/scripts/check-ui-a11y.py" "$ROOT/app/src/main/java/ai/opencode/android/ui"
run "ui lazy lists (no take(8), no eager forEach)" \
  python3 "$ROOT/phase6/scripts/check-ui-lists.py" "$ROOT/app/src/main/java/ai/opencode/android/ui"
run "ui purity (screens are functions of state)" \
  python3 "$ROOT/phase6/scripts/check-ui-purity.py" "$ROOT/app/src/main/java"

echo | tee -a "$LOG"
echo "=== static checks summary rc=$RC ===" | tee -a "$LOG"
grep -a '^FAIL' "$LOG" | head -40 | tee -a "$LOG" >/dev/null || true
exit "$RC"
