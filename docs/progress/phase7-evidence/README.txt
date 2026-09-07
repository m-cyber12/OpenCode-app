Phase 7 evidence: workspace isolation + memory + project management, proven on a
fresh emulator against the app's own embedded OpenCode server.
  00-run-phase7.log             the orchestrator log (read this first on failure)
  GATES_SUMMARY.txt             machine-readable verdicts (P7_SUMMARY line)
  p7-static-checks.log          strings/a11y/lazy-list/ascii/kotlin-comment checks
  p7-isolation-instrument.log   W1/W2/W3 gates (workspace boundary + memory)
  p7-chat-ui-instrument.log     U3/U7 regression (permission ask + accessibility)
  p7-live-chat-instrument.log   L1/L2 (live turn + real tool call through the UI)
  p7-ui-lines.txt               every P6_/P7_ verdict line, deduplicated
  screenshots/                  PNGs captured ON DEVICE by the UI gates
  logcat.txt                    filtered logcat (OpenCode + test runner + crashes)
  runtime.log                   the app's own supervisor log (state machine)
  opencode-server.log           the embedded server's own log tail
  phase5-regression/            Phase 5 gate verdicts re-run on the same device
