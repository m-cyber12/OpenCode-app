Phase 5 evidence: the Android app driving the on-device OpenCode server as a
real client (loopback-only binding, OpenCode's own API/events/permissions/MCP,
Keystore-held credentials).
  00-run-phase5.log             the orchestrator log (read this first on failure)
  p5-01-device.txt              device + installed-app facts
  p5-04-instrument-export.txt   Keystore password exported for the host drivers
  p5-k-instrument.log           Kotlin instrumented client gates (K1..K8) output
  p5-k-gates.log                per-gate P5_* PASS/FAIL lines from the app process
  p5-k-lines.txt                those lines deduplicated across both channels (the count P5-K uses)
  runtime.log                   the app's own supervisor/runtime log (state machine, bind lines)
  rerun-gate-*.log              phase-4 gate drivers re-run verbatim (G6/G7/G10/G11/G12)
  p5-16-mcp-remote.log          remote MCP transports (StreamableHTTP + SSE + failure case)
  p5-16-fixture-host.log        the host-side MCP server used by that gate
  p5-17-loopback.txt            /proc/net/tcp{,6} + udp audit, launcher bind lines
  p5-17-external-connect.txt    device-side socket probe: loopback open, external refused
  p5-18-credentials.txt         credentials at rest (ciphertext blobs, auth.json mode)
  p5-19-secret-scan.txt         APK/payload/source secret scan results
  jvm-unit-tests/               JVM unit tests (supporting, not runtime, evidence)
  GATES_SUMMARY.txt             machine-readable verdicts (P5_SUMMARY line)
