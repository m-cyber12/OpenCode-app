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

# Phase 5 writes its counters several to a line ("gates_pass=14 gates_fail=1
# gates_skip=0"), so an anchored ^key= read finds only the first one. Run
# 34134527274 folded exactly that: p5pass=14 but p5fail/p5skip/kf all "?" and the
# pass condition below - which insists the Kotlin gates reported zero failures and
# nothing was skipped - could never be met, so a steady-state Phase 5 tail was
# reported as P6-R5: FAIL. Read a counter wherever it sits on the line, with a
# boundary so gates_pass cannot match inside kotlin_gate_pass.
counter() {
  grep -aoE "(^|[^A-Za-z0-9_])$1=[0-9]+" "$2" 2>/dev/null | head -1 | sed 's/.*=//'
}

verdict=1
detail=""
if [ "$RC5" = "7" ]; then
  verdict=7
  detail="phase5-regression-tail-skipped (P6_SKIP_PHASE5=1)"
elif [ ! -f "$P5SUM" ]; then
  detail="no phase5 summary produced (rc=$RC5)"
else
  p5pass=$(counter gates_pass "$P5SUM")
  p5fail=$(counter gates_fail "$P5SUM")
  p5skip=$(counter gates_skip "$P5SUM")
  kp=$(counter kotlin_gate_pass "$P5SUM")
  kf=$(counter kotlin_gate_fail "$P5SUM")
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
# Same multi-per-line shape as Phase 5's summary, same fix: run #7's fold read
# cur_fail/cur_skip as empty (anchored ^key= never matches past the first key on
# the line) and then wrote the summary back with the two counters BLANKED, so a
# truthful 8 pass / 4 fail / 2 skip became "pass=9 fail= skip=".
cur_pass=$(counter ui_gates_pass "$SUMMARY"); cur_pass=${cur_pass:-0}
cur_fail=$(counter ui_gates_fail "$SUMMARY"); cur_fail=${cur_fail:-0}
cur_skip=$(counter ui_gates_skip "$SUMMARY"); cur_skip=${cur_skip:-0}
case "$KEY" in
  pass) cur_pass=$(( ${cur_pass:-0} + 1 )) ;;
  fail) cur_fail=$(( ${cur_fail:-0} + 1 )) ;;
  skip) cur_skip=$(( ${cur_skip:-0} + 1 )) ;;
esac
sed -i "s/^ui_gates_pass=.*/ui_gates_pass=$cur_pass ui_gates_fail=$cur_fail ui_gates_skip=$cur_skip/" "$SUMMARY"
grep -a '^ui_gates_pass=' "$SUMMARY" || true
exit 0
