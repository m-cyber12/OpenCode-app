# Environment-brief acceptance test (v9.9.1)

One prompt, run on the phone, that cannot be passed by luck. It forces every
behaviour the brief teaches: scripting without probing, a loopback server on a
watched port, correct backgrounding, a verified hand-off to the preview, and
honesty about what the device cannot do.

## Setup
- Install the current smoke APK and open the app once so the runtime starts
  (the brief is written at runtime startup).
- Use a fresh project (or an existing one - the test does not care), Build
  mode, any capable model.

## The prompt (paste verbatim)

```
Create a page clock.html in this project: a live digital clock that updates
every second, plus a button that cycles the background colour. Serve it and
open the live preview for me. Then answer two questions in one line each:
(1) which languages/runtimes can you script with on this device?
(2) can my laptop on the same Wi-Fi open this preview - yes or no, and why?
```

## Scorecard - watch the tool cards, not just the answer

PASS requires all of P1-P5. P6 is a bonus.

| # | What to look for | Why it proves the brief landed |
|---|---|---|
| P1 | No probing turn: no `command -v python/node/npx`, no `which` chains, no `ip addr`/`getprop`/`ifconfig` hunting | The brief says what exists; probing means it was not read |
| P2 | Any scripting goes straight to `bun`; answer (1) names bun (+ git/rg/toybox sh) and says python/node do NOT exist without having run probe commands | Tool inventory came from the brief, not discovery |
| P3 | Server starts on 127.0.0.1 on a watched port (8080 is the natural pick) and is backgrounded with `setsid ... &`, not `nohup` | The quirks section was absorbed |
| P4 | The server is CHECKED before hand-off (e.g. `nc -w 2 127.0.0.1 8080`), and `.preview/serve.json` is written AFTER that with the real page path (`"/clock.html"`, not `"/"`) - the preview then opens by itself on a WORKING page, no 404/ENOENT | The exact v9.9 failure (ENOENT on ./index.html) cannot recur |
| P5 | Answer (2) is NO - loopback only; no `http://<device-ip>:8080` offered | The old 12-minute turn promised a LAN URL that could never work |
| P6 | Bonus: it reads `.preview/latest.png` after serving to confirm what you see | Agent eyes used unprompted |

## Instant-fail markers

- Probing for python/node/curl/npm, or any `--version` fishing expedition.
- `nohup` for the server, or a server bound to `0.0.0.0`/`localhost` by name.
- serve.json written before the server answers, or with `"path": "/"` when
  no index.html exists.
- A Wi-Fi/LAN URL offered to the user.
- The seccomp stderr lines treated as an error worth investigating.

## Variant B (no-server hand-off, 30 seconds)

Prompt: `Show me 1.html in the live preview without starting any server.`
Expected: ONE tool call that writes `.preview/serve.json` with
`{"path": "/1.html"}` (no port) - the app serves the project itself and the
preview opens on the page. Anything that starts a server fails the variant.

## What to report back

Model used, time + token counts of the turn, which of P1-P6 held, and a
screenshot of the preview. P1/P2 timing matters: the pre-brief baseline for
the same kind of task was 12m32s / ~16.5k tokens in; the brief should cut the
discovery part of that to roughly zero.
