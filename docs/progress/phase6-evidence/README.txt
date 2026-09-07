Phase 6 evidence: the product UI (first-run experience + conversation-first chat)
running on a fresh emulator against the app's own embedded OpenCode server.
  00-run-phase6.log             the orchestrator log (read this first on failure)
  GATES_SUMMARY.txt             machine-readable verdicts (P6_SUMMARY line)
  p6-static-checks.log          strings/a11y/lazy-list/ascii/kotlin-comment checks
  p6-chat-ui-instrument.log     deterministic Compose UI gates (no runtime needed)
  p6-first-run-instrument.log   first-run flow on a freshly installed APK
  p6-live-chat-instrument.log   live streaming turn through the UI (needs a model)
  p6-ui-lines.txt               every P6_* verdict line, deduplicated
  screenshots/                  PNGs captured ON DEVICE by the UI gates
  logcat.txt                    filtered logcat (OpenCode + test runner + crashes)
  runtime.log                   the app's own supervisor log (state machine)
  opencode-server.log           the embedded server's own log tail
  phase5-regression/            Phase 5 gate verdicts re-run on the same device
