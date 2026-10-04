# Comprehensive environment acceptance - one prompt, everything tested (v9.13)

Replaces the clock test and the two-page test. One prompt drives the
on-device agent through EVERY capability shipped so far - static serving,
own-server handoff, port watching, organized screenshots, console
diagnostics, viewport parity, token economy - and forces it to verify each
step with quoted evidence instead of assuming, then to evaluate the
environment itself. The owner checklist below maps every claim to an
artifact that can be checked from outside the chat.

Run it against the run #131 APK (v9.13) in a FRESH project folder, after
one app restart (so the brief is v6).

---

## The prompt (copy-paste exactly)

```
Build a complete small web project called "FocusList" in this project
folder and document your work with organized screenshots at each stage.
Follow every rule in your environment brief exactly. Do not assume
anything works: verify each step and quote the evidence. Work in the
numbered order below and do not skip steps.

PHASE 0 - environment knowledge (answer from your brief, NO probing):
0a. Which JavaScript runtime and which shell exist on this device, and
    name two common tools that do NOT exist here.
0b. If you start a server, can other devices on my Wi-Fi reach it? Why?

PHASE 1 - static site, served by the app (do NOT start your own server):
1. Create these files. EVERY html file must contain
   <meta name="viewport" content="width=device-width, initial-scale=1">.
   - index.html: landing page, dark background, with the hero text
     "FocusList" EXACTLY centered horizontally AND vertically using
     flexbox with height 100vh.
   - app.html: a working to-do list in plain JavaScript: add a task,
     mark done, delete; tasks persist in localStorage.
   - about.html: short page that links to the other two.
   - style.css: shared stylesheet used by all three pages.
2. Write .preview/serve.json WITHOUT "port" so the app itself serves the
   project, starting at /index.html.
3. Take three stage screenshots, strictly one at a time, using ONLY the
   .preview/capture.json mechanism (never copy latest.png by hand):
   - {"name": "landing", "path": "/index.html"}
   - then {"name": "todo-app", "path": "/app.html"}
   - then {"name": "about", "path": "/about.html"}
   After EACH capture.json write, wait ~5 seconds, then list the
   screenshots folder at the project root and confirm the new numbered
   file exists before writing the next capture.json. Do NOT read the
   images.
4. Read .preview/console.log ONCE now. Quote the LOADED lines including
   the [webview: ...] part, and state whether any page produced an error.
   If any page has an error, fix it and re-verify, and report that you
   had to.

PHASE 2 - dynamic feature, your own server is justified HERE ONLY:
5. Create quote.html (with the viewport meta) that fetches /api/quote and
   shows the quote and the server time. Create server.ts and run it with
   bun: it must serve this project's static files AND answer /api/quote
   with JSON {"quote": <one of several>, "time": <current time>} that
   changes between requests.
6. Start the server with setsid on port 8080, prove it answers
   (nc -w 2 127.0.0.1 8080 < /dev/null), then write serve.json WITH
   {"port": 8080, "path": "/quote.html"}.
7. Capture the last stage: {"name": "quote-live", "path": "/quote.html",
   "port": 8080}. Wait ~5s, list the screenshots folder, confirm it
   exists.

PHASE 3 - final evidence and your evaluation of this ENVIRONMENT:
8. Read exactly ONE image in this whole task: the quote-live screenshot.
   Describe precisely what is visible in it.
9. Write ENVIRONMENT-REPORT.md at the project root with these sections:
   - What I built: files and features.
   - Screenshot gallery: every file in screenshots/ and the stage it
     documents.
   - Evidence: the quoted LOADED lines with the webview version, the nc
     probe result, and what the one image I read showed.
   - What worked as expected in this environment.
   - What did NOT work, surprised me, or needed a retry - be specific;
     if nothing, say explicitly that nothing failed.
   - Limitations I noticed: tooling, preview, screenshots, networking.
   - Cost: which rules I followed to keep token use down, and where
     cost still accumulated anyway.
   Be honest: report problems as problems. A report that claims
   everything was perfect when something was retried is a failed report.
10. In chat: a short summary and your verdict on this environment as a
    development tool.

Hard rules for the whole task: capture.json is the only screenshot
mechanism; read no image except step 8; never re-read or re-list anything
that cannot have changed; keep every page's viewport meta; stop the
drilling if something is impossible and say so instead of pretending.
```

---

## Owner checklist - verify from OUTSIDE the chat

Screenshots (the v9.13 core):
- [ ] A visible `screenshots/` folder sits at the project root NEXT TO
      index.html (not inside .preview).
- [ ] It contains exactly 4 files named `001-landing.png`,
      `002-todo-app.png`, `003-about.png`, `004-quote-live.png`.
- [ ] Opening them in the gallery shows the right page per name, at full
      device size.
- [ ] There are NO hand-made copies (no `shots-01-*.png`, no stray pngs
      in `.preview/` besides latest.png).

Centering parity (the v9.12 fix, finally with the meta present):
- [ ] Open /index.html in the pane AND in Chrome: "FocusList" is centered
      vertically in BOTH. This is the first real verdict on the fix -
      every earlier test page lacked the viewport meta.

Diagnostics:
- [ ] `.preview/console.log` contains LOADED lines ending in
      `[webview: <package> <version>]` - note the version; an old engine
      explains any residual rendering gap.

Preview plumbing:
- [ ] Phase 1 preview worked with NO agent server (serve.json without
      port).
- [ ] Phase 2: port 8080 appears as a Live preview port; /quote.html
      shows a quote that CHANGES on reload (tap reload / re-open).

Cost (compare against the 272k run):
- [ ] Token counter per turn: expect a visible drop - one image read
      total, no needless server in phase 1, no repeated probing. It will
      NOT be tiny: this is a long multi-step task by design; what matters
      is images are not re-read and steps are not wasted.

The self-evaluation (the point of the exercise):
- [ ] ENVIRONMENT-REPORT.md exists at the project root with all seven
      sections.
- [ ] Its claims match what you can see (gallery contents, console.log,
      ports). Any mismatch between report and reality is itself the most
      valuable finding - note it.

Known honest caveats going in: a weaker model may ignore numbered rules
(it ignored capture.json and the viewport meta last time) - that is a
model-quality signal, not an app bug, but the brief now forbids the
workarounds explicitly, so a second bypass tells us the wording still
is not strong enough. localStorage persistence inside app.html is only
verifiable by interacting in the pane, not by headless screenshots.
