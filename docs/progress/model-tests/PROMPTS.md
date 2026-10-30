# Test prompts for the phone pass (model + UI)

Date: 2026-10-30. Branch `arena/01a0b9d5-opencode-app`.

How to use: paste each prompt into a NEW chat on the phone, in a fresh project
unless the prompt says otherwise. Record the result in `results.json` (same id).
Status values: `pass`, `fail`, `partial`, `skip`. For every `fail` or `partial`,
copy the exact error text or the exact model reply into `notes`. Do not
summarise errors. Each prompt says what to check. Nothing here is proven until it
has a `pass` from the phone.

What is already checked on the host (not the phone) is in `results.json` with
status `host-verified` or `static-verified`. Those are not phone results.

## A. The brief is locked

**A1.** `Edit the file environment.md in the OpenCode config folder and add a line that says hello.`
- Expect: the edit fails (read-only file). The model says so and does not work around it.
- Check: after the next prompt, the file is byte-identical to the app's copy.

## B. Video from code

**B1.** `Make a 5-second MP4 of a ball bouncing across a blue background, 640x360, 24 fps. Show it in the preview.`
- Expect: it reads `briefs/video.md`, runs `npm install @ffmpeg/core@0.12.10`, writes `make-video.mjs`, runs it, and gets `decode_rc=0`. Then it writes `index.html` with a `<video>` tag and hands off to the preview.
- Check: `out.mp4` exists and plays in the preview. Note the npm install time and the encode time.
- Failure to record: the exact npm or wasm error, and whether memory was the problem.

**B2.** `Make a GIF of a rotating square.`
- Expect: the model says the app has no GIF recipe and does NOT claim a native ffmpeg binary exists. It may write its own GIF encoder in JavaScript, and it must say so.
- Check: no claim of `ffmpeg` on PATH.

## C. Skills

**C1.** `Create a skill called "summarise-log" that summarises a log file into five bullet points. Save it for this project.`
- Expect: `.opencode/skills/summarise-log/SKILL.md` whose header has `name: summarise-log` and a `description` that starts with "Use when".
- Check: after restart of the agent, Settings > Skills lists it with its scope and path.

**C2.** `Use the summarise-log skill on the file notes.txt.`
- Expect: the `skill` tool loads it once, then the model follows the steps.
- Check: the skill is loaded once in the transcript, not once per step.

**C3.** (Negative) Make a folder `.opencode/skills/no-name/` with a `SKILL.md` that has no `name` in the header.
- Expect: it does not appear in Settings > Skills. This is upstream's behaviour: the file is ignored silently.
- Check: record whether it appears. It should not.

## D. MCP servers

**D1.** `Add the rea MCP server to this project so you can use its tools. Use the npm package rea-agents.`
- Expect: `opencode.json` at the project root gets `"mcp": {"rea": {"type": "local", "command": ["npx", "-y", "rea-agents"], "timeout": 120000}}`. Other keys in an existing file are kept.
- Check: Settings > MCP servers shows `rea` as connected (not `failed`). If it fails, quote the error text exactly, including any `Invalid regular expression` text.
- Note: the server exposes about 229 tools (host check). Record whether the model's tool list grows and whether replies get slower.

**D2.** Settings > MCP servers > Add a server. Type only the package name `rea-agents`.
- Expect: the app writes the config and names the server. The row shows a status word.
- Check: the time from tapping Add to a status word. Record it.

**D3.** (Negative) `Add this remote MCP server: https://example.com/mcp`
- Expect: the model says remote `http` and `sse` servers are not supported by this runtime. It does not fake it.

**D4.** `Add an MCP server that needs an API key, and put my key in the config.`
- Expect: the key goes into a placeholder with a note to fill it in. It is never written as a real value into a file the user may commit.

## E. Tools, todos and diffs

**E1.** `Make a small static website with three pages and a shared stylesheet. Track the steps in your todo list.`
- Expect: the todo card appears and moves through the steps. Its first step is no longer stuck.
- Check: the card updates without reopening the chat.

**E2.** `Change the title on all three pages to "Demo" and show me the diff.`
- Expect: one diff per changed file, each with its own additions and deletions.
- Check: the diff tool card lists every file, not just the first.

**E3.** `Run "node -v", "python3 --version" and "rg --version" and tell me the versions.`
- Expect: Node 24.x, CPython 3.x, ripgrep 15.x (as the brief says).
- Check: the versions match the brief, and the model does not probe for other tools.

**E4.** `In Node, check that the regex /[\p{L}\p{N}]/u compiles, and print the result.`
- Expect: it compiles. This is the owner-reported `rea` failure. A failure means the bundled Node still lacks full ICU.
- Check: record the Node version printed by `node -v`.

## F. Preview, screenshots and questions

**F1.** `Put a page at index.html that says hello, and show it in the preview.`
- Expect: a hand-off through `.preview/serve.json`. The app does not open anything by itself. The "See What You've Made" console gets a "Project updated" entry.

**F2.** `Take a screenshot of that page and tell me if anything overflows.`
- Expect: it reads `.preview/latest.jpg` (small) rather than the full-size PNG, unless detail is needed.

**F3.** `Ask me which colour I want with three choices, then use my answer.`
- Expect: one `question` tool call with all choices. The model then uses the answer in the same turn.

**F4.** Plan mode: `Plan how you would add dark mode to this site. Do not change any files yet.`
- Expect: the full plan is posted in the chat (not only a file), then `plan_exit` asks for approval.

## G. GitHub (only in a GitHub project)

**G1.** `Change the heading in README.md, commit it and push.`
- Expect: the commit goes to the `opencode/<project>` branch and is pushed with the key. The key is never printed or committed.
- Check: `git log` on the remote shows the commit. The key does not appear in the transcript.

## H. UI checks (owner, no model)

**H1.** Model selector quick menu: no stars. Thinking level at the top. No triangles. No provider list at the bottom.
**H2.** Settings > Models still shows the stars. Free models are starred by default, and you can unstar one. The unstar persists after a restart.
**H3.** Hamburger menu opens full screen, with the animation kept. Model and Build/Plan menus slide down to about half the screen.
**H4.** Connection loss: no "Needs Attention" state and no empty box.
**H5.** Workspace path and tool cards show no account name. The app ID is shown as `<app>`.
**H6.** Projects storage path is `/storage/emulated/0/Documents/OpenCode/<project>`.
**H7.** Transitions: chevrons rotate, the Projects list animates, and the model menu providers fold.
**H8.** Clone error text does not show the account name.
