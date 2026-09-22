# Phase 10 continuation v4 — driver self-test evidence

**Result: `pass=120 fail=0`, `SELFTEST PASS` — 15 scenarios, run locally on the owner's
checkout shape (Linux host), with the fake phone answering every accessibility dump.**

| File | What it is |
|---|---|
| `driver-selftest-120-tail.log` | the tail of that run: the scenario exit-code table, the summary line, and the v4-specific checks that ran |

What the three earlier rounds in `../v3-driver-selftest/` are for comparison:

| Round | Scenarios | Checks | Result |
|---|---|---|---|
| `driver-selftest-87.log` | 12 | 87 | PASS (pre-v4 driver) |
| `driver-selftest-99.log` | 13 | 99 | PASS (fallback scenarios added) |
| `driver-selftest-105.log` | 14 | 105 | PASS (footer honesty) |
| `driver-selftest-106.log` | 14 | 106 | PASS (the v3 revision that went to the phone) |
| `driver-selftest-120-tail.log` | **15** | **120** | PASS (v4: onboarding scenario, the five v4 verdicts, the fixture-escape fix) |

Two things this file is careful about, because the numbers matter more than the green:

* **the count went up with the scenarios, not with the assertions inside them.** The v4
  additions are a new scenario (`onboarding`: welcome → workspace step → one tap → project
  `1` → chat) and checks for the five new signed-build verdicts; the `happy` scenario's own
  product path is unchanged from v3 apart from the v4 verdict ids it now asserts.
* **a green self-test is evidence about the HARNESS, not about the app.** It says the driver
  runs clean against a phone that answers like a phone, and that it fails for the right
  reasons when the phone lies. Nothing here has been on real hardware: that is the single
  combined device pass the owner runs with `phase10/scripts/90-real-device-signed.sh`.
