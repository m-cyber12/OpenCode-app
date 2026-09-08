#!/usr/bin/env bash
# 30-static-checks.sh - everything Phase 8 can verify WITHOUT an emulator, run
# before the expensive stages so a bad push fails in seconds. Same mechanical
# checks as Phases 5-7 (the sandbox has no JDK/Gradle), extended to the phase8
# tree, plus the new Kotlin (state machine, backoff, env, health, manifest,
# extraction, classifier, persistence) and the new gate drivers.
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

echo "=== Phase 8 static checks $(date -u +%FT%TZ) ===" | tee -a "$LOG"

# ---- 1. shell + python syntax of every phase script --------------------------
find "$ROOT/spike" "$ROOT/phase3" "$ROOT/phase4" "$ROOT/phase5" "$ROOT/phase6" "$ROOT/phase7" "$ROOT/phase8" \
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
done < <(find "$ROOT/phase5" "$ROOT/phase6" "$ROOT/phase7" "$ROOT/phase8" -name '*.py' -type f 2>/dev/null | sort)
if [ "$PYSYN" = 0 ]; then echo "OK   py_compile on every phase python script" | tee -a "$LOG"; else RC=1; fi

# JS driver syntax (node is on the CI image; bun's parse would differ, but the
# drivers are plain ESM with no bun-only syntax - node --check catches typos).
if command -v node >/dev/null 2>&1; then
  JSSYN=0
  for f in "$DIR"/scripts/device/*.js; do
    node --check "$f" >> "$LOG" 2>&1 || { echo "FAIL node --check $f" | tee -a "$LOG"; JSSYN=1; }
  done
  if [ "$JSSYN" = 0 ]; then echo "OK   node --check on every phase8 driver" | tee -a "$LOG"; else RC=1; fi
else
  echo "SKIP node not on this host (CI has it)" | tee -a "$LOG"
fi

# ---- 2. ASCII-only for CI-facing files (recurring operational note) ----------
python3 "$ROOT/phase6/scripts/check-ascii.py" "$ROOT" 2>&1 | tee -a "$LOG"
[ "${PIPESTATUS[0]}" = 0 ] || RC=1

# ---- 3. workflow YAML: parse, and quote every step name with a colon+space ---
# Only the phase8 template is checked: the phase7/phase6/... templates pin their
# OWN sessions' branches, so a branch check against this session's branch would
# fail on them by construction (the Phase 7 regression runs from its script,
# phase7/scripts/20-gates.sh, not from its workflow).
python3 "$ROOT/phase8/scripts/check-workflow-p8.py" "$ROOT" 2>&1 | tee -a "$LOG"
[ "${PIPESTATUS[0]}" = 0 ] || RC=1

# ---- 4. Kotlin lexical sanity (no compiler available locally) ---------------
run "kotlin nested-block-comment scan (phase5 lesson)" \
  python3 "$ROOT/phase5/scripts/check-kotlin-comments.py" "$ROOT/app/src"
run "kotlin bracket/annotation balance" \
  python3 "$ROOT/phase6/scripts/check-kotlin-balance.py" "$ROOT/app/src"
# D8 (the dexer) rejects spaces in method names ("Space characters in
# SimpleName ... not allowed prior to DEX version 040") even though Kotlin
# compiles them. androidTest method names are JVM identifiers: no backticks,
# no spaces, no leading digit.
run "androidTest method names are dex-safe identifiers (D8 rule)" \
  bash -c '! grep -rn "fun \`" "$ROOT/app/src/androidTest" 2>/dev/null'

# ---- 5. the UI rules, all screens --------------------------------------------
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
