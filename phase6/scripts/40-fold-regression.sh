#!/usr/bin/env bash
# 40-fold-regression.sh - fold the Phase 5 regression tail into the Phase 6
# summary as one verdict, P6-R5.
#
# Phase 5's steady state is 14 PASS / 1 FAIL where the single FAIL is P5-G16:
# remote (Streamable HTTP / SSE) MCP servers cannot complete tool discovery on
# this runtime, the cause is discarded inside OpenCode's own MCP client, and it is
# filed upstream as anomalyco/opencode#47644. That gate stays red BY DESIGN, so
# "Phase 5 did not regress" means: every other gate still passes. A Phase 6 change
# that moves any other gate is a regression, not an improvement, and this is where
# it becomes visible.
#
# Usage: 40-fold-regression.sh <phase5-rc>   (0 pass, 7 skipped, else failed)
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
EV="$DIR/out/evidence"
SUMMARY="$EV/GATES_SUMMARY.txt"
P5EV="$DIR/../phase5/out/evidence"
P5SUM="$P5EV/GATES_SUMMARY.txt"
RC5="${1:-1}"
[ -f "$SUMMARY" ] || { echo "no phase6 summary to fold into"; exit 0; }

verdict=1
detail=""
if [ "$RC5" = "7" ]; then
  verdict=7
  detail="phase5-regression-tail-skipped (P6_SKIP_PHASE5=1)"
elif [ ! -f "$P5SUM" ]; then
  detail="no phase5 summary produced (rc=$RC5)"
else
  p5pass=$(sed -n 's/^gates_pass=\([0-9]*\).*/\1/p' "$P5SUM" | head -1)
  p5fail=$(sed -n 's/^gates_fail=\([0-9]*\).*/\1/p' "$P5SUM" | head -1)
  p5skip=$(sed -n 's/^gates_skip=\([0-9]*\).*/\1/p' "$P5SUM" | head -1)
  kp=$(sed -n 's/^kotlin_gate_pass=\([0-9]*\).*/\1/p' "$P5SUM" | head -1)
  kf=$(sed -n 's/^kotlin_gate_fail=\([0-9]*\).*/\1/p' "$P5SUM" | head -1)
  failed_ids=$(grep -aE '^P5-[A-Za-z0-9]+: FAIL' "$P5SUM" 2>/dev/null | sed 's/:.*//' | tr '\n' ',' | sed 's/,$//')
  unexpected=$(grep -aE '^P5-[A-Za-z0-9]+: FAIL' "$P5SUM" 2>/dev/null | grep -av 'P5-G16' | sed 's/ .*//' | tr '\n' ',' | sed 's/,$//')
  detail="phase5=${p5pass:-?}pass/${p5fail:-?}fail/${p5skip:-?}skip kotlin=${kp:-?}pass/${kf:-?}fail failed_ids=${failed_ids:-none}"
  if [ -z "$unexpected" ] && [ "${p5pass:-0}" -ge 14 ] && [ "${kf:-1}" = "0" ] && [ "${p5skip:-1}" = "0" ]; then
    verdict=0
    detail="$detail (only the documented upstream restriction P5-G16 is red)"
  else
    detail="$detail unexpected_failures=${unexpected:-none}"
  fi
fi

case "$verdict" in
  0) LINE="P6-R5: PASS phase5-regression :: $detail"; KEY=pass ;;
  7) LINE="P6-R5: SKIP phase5-regression :: $detail"; KEY=skip ;;
  *) LINE="P6-R5: FAIL phase5-regression :: $detail"; KEY=fail ;;
esac
echo "$LINE"
echo "$LINE" >> "$SUMMARY"
# Keep the machine-readable counters truthful (the summary line is the trailer-is-
# truth discipline Phase 5 established).
cur_pass=$(sed -n 's/^ui_gates_pass=\([0-9]*\).*/\1/p' "$SUMMARY" | head -1)
cur_fail=$(sed -n 's/^ui_gates_fail=\([0-9]*\).*/\1/p' "$SUMMARY" | head -1)
cur_skip=$(sed -n 's/^ui_gates_skip=\([0-9]*\).*/\1/p' "$SUMMARY" | head -1)
case "$KEY" in
  pass) cur_pass=$(( ${cur_pass:-0} + 1 )) ;;
  fail) cur_fail=$(( ${cur_fail:-0} + 1 )) ;;
  skip) cur_skip=$(( ${cur_skip:-0} + 1 )) ;;
esac
sed -i "s/^ui_gates_pass=.*/ui_gates_pass=$cur_pass ui_gates_fail=$cur_fail ui_gates_skip=$cur_skip/" "$SUMMARY"
grep -a '^ui_gates_pass=' "$SUMMARY" || true
exit 0
