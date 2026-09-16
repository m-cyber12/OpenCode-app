Phase 8 evidence: hardening + test matrix, proven on a fresh emulator (plus the
real-device suite the user runs from their own machine - see
phase8/scripts/90-real-device-suite.sh and the report).
  00-run-phase8.log             the orchestrator log (read this first on failure)
  GATES_SUMMARY.txt             machine-readable verdicts (P8_SUMMARY line)
  p8-static-checks.log          strings/a11y/lazy-list/ascii/kotlin-comment checks
  p8-stress-instrument.log      stress/recovery gates (KEYRESIDENCY PROVAUTH SERVERKILL LIFECYCLELOG)
  p8-live-instrument.log        live model gates (KEYPROBE TOOL CLEANUP) - the L2 close
  p8-*-verdicts.txt             per-class verdict files (the gates' own channel)
  p8-lines.txt                  every P8_ verdict line, deduplicated
  screenshots/                  PNGs captured ON DEVICE by the UI gates
  runtime.log                   the app's own supervisor log (state machine)
  opencode-server.log           the embedded server's own log tail
  p8-meminfo-*.txt p8-cpu-*.txt p8-storage-footprint.txt   measured perf
  phase7-regression/            Phase 7 gate verdicts re-run on the same device
  phase5-regression/            Phase 5 gate verdicts re-run (security re-verification)
  logcat.txt                    filtered logcat
