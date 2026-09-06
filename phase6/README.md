# Phase 6 — product UI: harness

Everything in this directory exists to make one claim checkable on a real
emulator: **install APK → open → welcome → the runtime extracts and starts by
itself → create/open a project → chat**, with a conversation-first UI over the
on-device OpenCode server.

```
phase6/
  CI_GRADLE_ONLY            bring-up knob: "1" = compile + JVM unit tests only
  README.md                 this file
  workflow/phase6-ui.yml    copy to .github/workflows/phase6-ui.yml (user action)
  scripts/
    00-run-phase6.sh        orchestrator (static -> compile -> payload -> fresh
                            emulator -> UI gates -> phase5 regression -> evidence)
    20-ui-gates.sh          runs the three instrumented Compose UI classes and
                            turns their P6_* verdict lines into GATES_SUMMARY.txt
    30-static-checks.sh     every check that needs no emulator (runs locally too)
    40-fold-regression.sh   folds the Phase 5 tail into one verdict (P6-R5)
    check-ui-strings.sh     hardcoded copy + missing R.string + URL literals
    check-ui-a11y.py        contentDescription / accessible names / field labels
    check-ui-lists.py       lazy lists: no take(N) caps, no eager forEach
    check-ui-purity.py      screens are pure functions of state; markdown pure Kotlin
    check-kotlin-balance.py brackets, package decl, missing @Composable
    check-ascii.py          ASCII-only CI-facing files
    check-workflow-yaml.py  yaml parse + quoted step names + trigger branch
    ktscan.py               shared Kotlin tokenizer the checks are built on
  out/                      runner output (gitignored), incl. evidence/
```

## Installing the workflow (one user action, ~1 minute)

The session bot token cannot create or modify `.github/workflows/` and cannot
`POST .../dispatches` (re-verified in Phase 5: `403 Resource not accessible by
integration`). The workflows already in the repo are pinned to earlier sessions'
branches (`arena/01a04ca1-…`, `arena/01a05713-…`), which have been merged and
deleted, so they never trigger for this branch either.

1. Open `phase6/workflow/phase6-ui.yml` in this branch.
2. In the GitHub UI: **Add file → Create new file**, path
   `.github/workflows/phase6-ui.yml`, paste the content verbatim, commit **to the
   branch `arena/01a077b3-opencode-app`**.
3. Every later push to this branch then runs the suite and commits evidence to
   `docs/progress/phase6-evidence/`.

No repo secret is needed: the pinned build resolves a key-free default model
(`opencode/big-pickle`), which is what the live-streaming UI gate uses.
`OPENROUTER_API_KEY` is read if it exists, exactly as in Phases 4–5.

## Reading a run from the sandbox

The Actions log/artifact *bodies* are served from hosts this sandbox cannot reach
(Phase 5 finding), so the branch is the log channel:

```
git fetch origin && git log --oneline -5
cat docs/progress/phase6-evidence/GATES_SUMMARY.txt
cat docs/progress/phase6-evidence/compiler-errors.txt   # if a build failed
ls  docs/progress/phase6-evidence/screenshots/
```

`PROGRESS.txt` in the same directory is a heartbeat (every 300 s) that shows which
step a long run is on and the last 40 log lines, so a stalled run and a slow one
are distinguishable.

## Gates

| id | what it asserts | needs a model? |
| --- | --- | --- |
| P6-U1…U8 | deterministic Compose UI gates: lazy transcript (300 messages, scrolls to the last), expandable tool-call card with real output, permission prompt replies, the three degraded states are distinguishable, streaming/stop state, markdown + syntax highlighting, accessibility semantics, lazy session list + switching | no |
| P6-F1…F4 | first-run flow on a **freshly installed** APK: welcome screen with no terminal/URL/port copy, runtime extracts + becomes healthy by itself, project create/open reaches the chat screen, per-stage screenshots captured | no |
| P6-L1…L2 | a real turn through the UI: prompt from the composer streams an assistant reply; a shell request produces a tool card with real output. SKIP (never PASS) when no model can serve a turn | yes (key-free default) |
| P6-R5 | Phase 5's client-integration suite still green on the same device, with `P5-G16` the only red gate (documented upstream restriction, anomalyco/opencode#47644) | as in Phase 5 |

Verdict discipline is Phase 5's: each gate prints `P6_<id> PASS|FAIL|SKIP ::
detail` from inside the app process to stdout **and** logcat; `20-ui-gates.sh`
counts the deduplicated union, so a gate that never ran cannot be mistaken for one
that passed.
