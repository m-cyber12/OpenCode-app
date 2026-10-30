package ai.opencode.android.runtime

import ai.opencode.android.preview.PortScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v9.9 (owner): "the model should already be fully aware of the environment
 * and its capabilities" - the agent was observed burning a 12-minute turn
 * probing for tools instead of trusting the app's inventory, rediscovering
 * the seccomp stderr noise, and learning by trial that
 * `nohup` dies while `setsid` survives. Arena-style agents do not probe,
 * because their platform BRIEFS them; this file is that brief for OpenCode
 * on Android.
 *
 * Mechanism - OpenCode's own, nothing invented: upstream `config.instructions`
 * (packages/opencode/src/config + session/instruction.ts) injects listed
 * files into the system context of every session. The app writes
 * `environment.md` into the global config dir and wires it into
 * `config.json`'s `instructions` array.
 *
 * Boundaries, deliberately kept:
 *  - `AGENTS.md` stays the USER'S memory (ProjectMemory's contract: nothing
 *    silently injected there). The brief is a separate, app-owned file.
 *  - A user `config.json` is only ever EXTENDED (instructions union); if it
 *    exists but cannot be parsed as strict JSON (e.g. hand-written JSONC),
 *    it is left byte-for-byte untouched and the wiring is skipped - never
 *    clobber the user's configuration for our convenience.
 */
object EnvironmentBrief {

    const val FILE_NAME = "environment.md"

    /**
     * v9.21 (owner): "split the brief ... one main brief that introduces the
     * environment, the rest as separate briefs, each covering a specific
     * topic. The model should only access those secondary briefs when
     * necessary ... summarized or referenced in the main brief so it
     * immediately knows which brief to access." The main brief is the only
     * text injected into EVERY request; the topic files below live next to
     * it and cost tokens only when their topic actually comes up (the agent
     * reads them with its normal file tool, once).
     */
    const val BRIEFS_DIR = "briefs"

    /**
     * Install/refresh the main brief + topic briefs and wire ONLY the main
     * one into config.json. [engine] names the device's WebView
     * provider+version (v9.14): preview and screenshots render with THAT
     * engine, and the agent must target it instead of assuming newest Chrome.
     */
    fun install(configDir: File, engine: String = "unknown"): Boolean = runCatching {
        configDir.mkdirs()
        val dir = File(configDir, BRIEFS_DIR)
        dir.mkdirs()
        for ((name, text) in topicBriefs(engine)) {
            lockedWrite(File(dir, name), text)
        }
        val brief = File(configDir, FILE_NAME)
        val text = briefText(engine, dir.absolutePath)
        lockedWrite(brief, text)
        wireConfig(File(configDir, "config.json"), brief.absolutePath)
    }.getOrDefault(false)

    /**
     * The brief is the app's text, not the model's. Every brief file is kept
     * READ-ONLY (an edit or write by the agent fails with EACCES), and every
     * install re-checks the bytes: a file the agent changed anyway (chmod, a
     * shell redirect after a chmod) is rewritten from the app's copy and locked
     * again. The app calls [install] again before every prompt, so an edit
     * made during one turn cannot survive into the next one.
     */
    internal fun lockedWrite(f: File, text: String) {
        if (f.isFile && f.readText() == text) {
            if (f.canWrite()) f.setReadOnly()
            return
        }
        if (f.exists()) f.setWritable(true, true)
        f.writeText(text)
        f.setReadOnly()
    }


    /**
     * Ensure config.json's `instructions` contains [briefPath].
     * @return true when the wiring is in place, false when a user-authored
     *   config could not be parsed and was therefore left alone.
     */
    internal fun wireConfig(config: File, briefPath: String): Boolean {
        val root: JSONObject = if (config.isFile) {
            runCatching { JSONObject(config.readText()) }.getOrNull() ?: return false
        } else {
            JSONObject().put("\$schema", "https://opencode.ai/config.json")
        }
        val instructions = root.optJSONArray("instructions") ?: JSONArray()
        for (i in 0 until instructions.length()) {
            if (instructions.optString(i) == briefPath) return true
        }
        instructions.put(briefPath)
        root.put("instructions", instructions)
        config.writeText(root.toString(2) + "\n")
        return true
    }

    /**
     * The MAIN brief - the only always-injected text, so every word here is
     * paid on every step of every session. Identity, tool inventory, the
     * device quirks that waste a turn each when rediscovered, the cost
     * discipline, and the INDEX: per topic one summary line + the exact file
     * to read, so the agent goes straight there instead of searching.
     */
    fun briefText(engine: String = "unknown", briefsDir: String = BRIEFS_DIR): String {
        return """
# Device environment (written by the OpenCode Android app; regenerated at startup - do not edit)

You run INSIDE an Android app on the user's phone (no root, no VM). All
facts here and in the topic briefs are enforced or device-verified - trust
them, never probe. Briefs are OVERWRITTEN at every app start: never
edit them - put suggestions in BRIEF-SUGGESTIONS.md at the project root.

## Tools
Installed and on PATH: `bun`, Node.js 24 (`node`), npm 11 (`npm`/`npx`),
CPython (`python3`/`python`), `git`, `rg`, `curl`, `wget`, `jq`, OpenSSH
clients, and OUR prefix's `sh` + ~190 coreutils - prefer ours; Android's
/system/bin varies by phone. Bun remains the app server; Node/npm and Python
are agent tools. npm installs on shared storage may require `--no-bin-links`.

## Quirks (device-verified)
- stderr `[seccomp] preload handler installed (rc=0)` is harmless noise.
- Only 127.0.0.1 is reachable; no LAN address exists.
- `${'$'}TMPDIR` may NOT exist yet: `mkdir -p "${'$'}TMPDIR"` first, or keep
  scratch/log files inside the project directory.
- Plain `cmd &` and `nohup` can die with your shell; background with:
  `setsid sh serve.sh </dev/null >serve.log 2>&1 &`

## Live preview, in one line
Nothing auto-opens, ever. Hand off by writing `.preview/serve.json`
({"port": 8080, "path": "/page.html"} - the path must name a file that
EXISTS; omit "port" for plain HTML/CSS/JS: the app serves the project
itself, do NOT start your own server). A watched port already taken
belongs to ANOTHER project - never kill it; take the next free one and
record it. A hand-off lights the user's "See What You've Made" console
with "Project updated" - they tap when ready; announce it, never grab.
Read the preview brief ONCE before your first hand-off.

## Ship clean
Raw providers can leak chat markers (pipe-wrapped tokens, stray fences)
into files you write, breaking the user's page in EVERY browser. After
big writes grep them for '<|'; any hit - rewrite that file clean.

## GitHub
Env value `key` set = GitHub project: the remote repo is the PRIMARY
workspace, this sandbox is temporary - a change that is not pushed is
LOST. The checkout is on its
`opencode/<project>` branch (token-free origin) and git speaks https.
YOU push: set git user.name/email once and
commit + push after EVERY logical change - starting with the very first
prompt's edits, never waiting to be asked:
`git push https://x-access-token:${'$'}key@github.com/<owner>/<repo>.git HEAD`
(owner/repo: `git remote get-url origin`). Stay there -
no extra branches unless the user asks; never print or commit it;
`key` also works for GitHub REST via fetch. The project's
AGENTS.md repeats these rules. Unset `key` = NOT a GitHub project:
off BY DESIGN, never a missing token - don't tell the user to set one;
GitHub work needs a GitHub project (Create project > GitHub).

## Asking the user
Anything with choices goes through the `question` tool: put ALL your
questions in ONE call, each with its options - never one ask per
step, never options as prose. In Plan mode follow the plan workflow
the system gives you, but post the FULL plan in the chat - the user
reads chat, never files; no extra files unless asked - then call
`plan_exit` - that tool asks the user for approval itself, so never
ask "is this plan okay" through `question`. On approval you continue
IN THIS SAME TURN as the build agent: start executing immediately,
with no closing prose - never say "ready to build" or tell the user
to switch modes.

## Cost - every wasted step is billed
The whole conversation, including EVERY image ever read, is re-sent to the
model on each tool step. This steers READING, never your tools: capture
ANY page/route any time - writes cost nothing. But read the small
`.preview/latest.jpg` by default; a full `screenshots/*.png` only when
pixel detail truly matters - roughly one read per fix round. Text first:
every load appends a `REPORT` line (viewport, doc, overflowX, meta,
broken imgs) to `.preview/console.log` - a layout verdict in ~40 tokens;
overflowX=0 with no errors means move on. Verify once - never re-probe
what cannot have changed; read each topic brief at most once.

## Topic briefs - the summary tells you which file; go straight there
- $briefsDir/preview.md - full hand-off/reload rules, the watched port
  list, rendering engine + layout rules (viewport meta, vh not dvh), the
  no-dependency static-server recipe. Read before the FIRST preview
  hand-off, or when the pane looks wrong.
- $briefsDir/screenshots.md - the capture loop: capture.json, CAPTURE +
  REPORT lines, report.json, latest.jpg and the per-round image budget.
  Read before taking or reading any screenshot.
- $briefsDir/webapp.md - running a real/cloned framework project (Next,
  Vite, ...): npm/Bun installs, dev servers, readiness, missing-env crashes.
  Read BEFORE the first command in any project that has a package.json.
- $briefsDir/tools.md - PATH order, exact installed tools, Android limits.
  Read before shelling out.
- $briefsDir/extensions.md - MCP servers and skills: where they live, how to
  register one. Read before adding either.
- $briefsDir/video.md - make any MP4 video from code (tested recipe).
""".trimIndent() + "\n"
    }

    /**
     * The topic briefs, each a complete standalone reference for one job.
     * File names are load-bearing: the main brief's index points at them.
     */
    fun topicBriefs(engine: String = "unknown"): Map<String, String> {
        val watched = PortScanner.CANDIDATE_PORTS.joinToString(", ")
        val preview = """
# Preview & layout - read once, before the first hand-off

The app watches these loopback ports: $watched.
A server on one lights the user's console - it NEVER opens the page by
itself (v9.37, owner's rule); a tap on "See What You've Made" does. Hand
off (or retarget an open pane) by writing
`.preview/serve.json` at the project root:
  {"port": 8080, "path": "/page.html"} -> show the server you started
  {"path": "/page.html"}               -> no server: the app serves this
                                          project over loopback itself
Re-writing serve.json after edits RELOADS the preview with fresh bytes
(never cached) - re-trigger instead of wondering. `{"stop": true}`
shuts the app's static server for this project and dims the console -
write it when the project is done; your OWN servers you kill yourself.
Two rules:
  1. `path` must name a file that EXISTS ("/" needs index.html).
  2. Confirm the server answers BEFORE the hand-off:
     `nc -w 2 127.0.0.1 8080 < /dev/null`.
A previous hand-off can go STALE - the server may have died since.
Before trusting one, confirm in a single step: `ps -o PID,ARGS | grep
"[y]ourserver"`, the `nc` probe above, and one fetch for status/bytes -
then rewrite serve.json to reload the pane with fresh bytes.

After each load the app saves `.preview/latest.png` and the small agent
copy `.preview/latest.jpg`, and mirrors the page's JS console + load
errors + a `REPORT viewport=... doc=... overflowX=... meta=ok|missing
imgs=ok/total broken=... text=...` digest line to `.preview/console.log`.
Debug order: REPORT + console lines first (cheap text), latest.jpg when
LOOKS must be judged, a full PNG only for true pixel detail. A `REPORT`
with meta=ok, overflowX=0 and no errors means the page is structurally
sound: trust it and move on - no screenshot needed to feel confident.
Preview and screenshots render with THIS engine, not the Chrome app:
$engine - target it.
Layout rules (pane == browser):
- Always `<meta name="viewport" content="width=device-width, initial-scale=1">`.
- Full-height/centered layouts: plain `vh` + `html,body{height:100%;margin:0}`.
  NEVER use dvh/svh/lvh - they can collapse the layout on this engine.

Static server recipe (bun, 8080, serves cwd; "/" falls back to the first
*.html; 404 instead of crashing on misses):
  setsid bun -e 'Bun.serve({port:8080,hostname:"127.0.0.1",async fetch(r){let p=decodeURIComponent(new URL(r.url).pathname);if(p==="/"){const h=[...new Bun.Glob("*.html").scanSync(".")];p="/"+(h.includes("index.html")?"index.html":(h[0]??"index.html"))}const f=Bun.file("."+p);return await f.exists()?new Response(f):new Response("Not found: "+p,{status:404})}})' </dev/null >serve.log 2>&1 &
""".trimIndent() + "\n"

        val screenshots = """
# The capture loop - read once, before your first screenshot

Write `.preview/capture.json`:
  {"name": "02-after-login-fix", "path": "/login.html", "port": 8080}
(omit "port" -> the app serves the project). The page renders off-screen
at device size and the app files, per capture:
  screenshots/NNN-<name>.png - the HUMAN gallery at the project root
  .preview/latest.jpg        - the small copy: your default pixel read
  .preview/report.json       - the same frame's layout numbers (text)
This is the ONLY way to take stage shots - never copy files by hand.
Capturing is UNLIMITED: any page, any route, any port, any time - a
capture is a file write, free for your context. Only READING costs.

~5s after each write, `.preview/console.log` ends with the confirmation:
  CAPTURE saved screenshots/NNN-<name>.png (...) view=latest.jpg url=...
  REPORT viewport=412x915 doc=412x2350 overflowX=0px meta=ok imgs=4/5 broken=1 text=1810ch frame=changed
or `CAPTURE FAILED ... reason=...` - read those lines instead of
assuming; on FAILED report the reason, never improvise workarounds.

THE LOOP (why it exists: every image you read rides on EVERY later step -
pixels are a once-per-round luxury, not a feedback channel):
1. Batch your edits. One capture per fix round, never per CSS tweak.
2. Read the REPORT line (or report.json) FIRST: meta=missing = page
   renders desktop-wide/tiny in EVERY browser (add the viewport meta);
   overflowX>0 = horizontal scroll bug; broken>0 = wrong image paths;
   text near 0 or doc height ~0 = empty render. Structure verdicts are
   FREE - make them, fix, done. Do NOT screenshot to hunt which element
   overflows - the numbers say if the layout is sound; your CSS says
   which box is wrong.
3. Read latest.jpg to judge LOOKS (spacing, taste, "is this the
   design") - about once per round, as late as possible; the full PNG
   only when pixel detail truly matters (priced per read, not banned).
   The user watches the pane live: for taste, one question to the user
   beats ten self-captures.
4. frame=unchanged-vs-prev means your edit did NOT reach the render -
   stale server or wrong path. Fix that; capturing again proves nothing.
Under a dev server's cold compile or HMR churn a capture can report
`timeout` - retry once the route is warm. `.preview/latest.jpg` can lag
the live pane; check its mtime before trusting it. The app appends a
BUDGET line when captures pile up - treat it as "switch to REPORT text".
""".trimIndent() + "\n"

        val webapp = """
# Running a real project (package.json exists) - read BEFORE the first command

Verified on-device end to end: a cloned Next.js 15 + React 19 + next-intl
site served into the Live preview. Follow the order - every skipped step
was once paid for in wasted turns. And serve the user's REAL site: no
placeholder status pages.

1. Read package.json FIRST - `scripts` and `engines`. Never guess the
   toolchain.
2. Use the lockfile/package metadata: npm and Bun are BOTH installed.
   Install once, in the background, then WAIT (big trees: ~10 min,
   hundreds of MB), for example:
   `mkdir -p "${'$'}TMPDIR" 2>/dev/null; setsid npm install --no-bin-links </dev/null >install.log 2>&1 &`
   `bun install` is also available for Bun projects. Poll `du -sh
   node_modules` and `tail -3 install.log`. On shared storage, symlink
   creation can end with `Failed to link <pkg>: EACCES` lines - packages
   extracted successfully but `node_modules/.bin` may be absent. Do not
   reinstall just for those link errors.
3. Starting tools: real Node 24 is on PATH, so `#!/usr/bin/env node`
   works; npm 11 is on PATH too. Try the declared package script first.
   If shared storage lacks `.bin`, invoke the tool's JS entry by ABSOLUTE
   path with `node` (relative script paths can die with
   `CouldntReadCurrentDirectory`):
   `setsid node /abs/project/node_modules/next/dist/bin/next dev -p 8080 -H 127.0.0.1 </dev/null >dev.log 2>&1 &`
   The same absolute-path command with `bun` is the device-verified
   fallback for Bun-compatible projects.
4. Native addons (`.node` prebuilds) cannot dlopen inside the app. When
   a dep crashes on one, stub THAT module's JS entry inside node_modules
   (no-op or passthrough) - never touch project source; stubs die with
   node_modules, so re-apply after any reinstall. Verified Next 15 set:
   - `@parcel/watcher` -> no-op stub (file-watching is optional in dev);
   - `@swc/core` -> passthrough stub (only next-intl's message extractor
     uses it);
   - Next itself falls back to wasm SWC automatically. If its registry
     subprocess fails on Android, patch
     `node_modules/next/dist/lib/helpers/get-registry.js` to return
     `https://registry.npmjs.org/` directly. `rm -rf node_modules/next/wasm
     node_modules/next/next-swc-fallback` if an interrupted run left
     empty dirs there - the downloader sees them and silently skips.
5. Readiness lives in the log, not in your patience: poll `tail -5
   dev.log` for the ready/port line, then warm the REAL route - first
   compile per route is slow (can be 60-90 s cold, seconds warm):
   `bun -e 'const r=await fetch("http://127.0.0.1:8080/en");console.log(r.status)'`
   TLS to fonts.gstatic.com (next/font) can flake mid-compile with
   `unknown certificate verification error` + retries; it recovers BY
   ITSELF - wait or re-request, and do not chase TLS env flags (Bun
   ignores them).
6. Hand off with the ROUTE, not a file: `.preview/serve.json`
   {"port": 8080, "path": "/en"} - when "port" is set, "path" is a URL
   route on YOUR server (locale prefix and all); trust the port the log
   printed over the one you expected. Confirm first:
   `nc -w 2 127.0.0.1 8080 < /dev/null`.
7. Crash on missing env vars (API keys, database URLs): `.env.local`
   with placeholder values, restart - a dev preview needs no real
   secrets.
8. Never `pkill -f "next dev"` - the pattern matches your own shell;
   kill by PID from `ps -o PID,ARGS`. Serving needs NONE of: lint,
   typecheck, test, e2e, `next build`. Do not run them unless asked.
9. `Ready` is NOT "site up": each route compiles on its FIRST request (an
   idle server shows zero progress - that means nothing). Warm routes in
   the BACKGROUND with a long timeout, so a slow first paint cannot hit a
   command timeout - the harness KILLS YOUR WHOLE PROCESS TREE when a
   command times out, so servers start with setsid in their OWN short
   command and you poll in separate commands:
   `setsid bun -e 'const r=await fetch("http://127.0.0.1:8080/",{signal:AbortSignal.timeout(420000)});console.log(r.status,(await r.text()).length)' </dev/null >warm.log 2>&1 &`
   First page-graph compile can be minutes; first render after compile
   can be minutes more; steady state is seconds.
10. Slow vs stuck, in cheap text (never screenshots): sample
   `grep -c '"name":"build-module' .next/trace` twice, 45 s apart -
   rising = working; flat plus ~0 CPU growth in
   `awk '{print ${'$'}14+${'$'}15}' /proc/PID/stat` = blocked, not slow. Prove
   the toolchain with a light API route FIRST: if it compiles, only the
   page graph is stuck. Verify installs by the TREE
   (`[ -d node_modules/next ]`), never by install.log (it ends in EACCES
   noise by design). `pgrep -f`/`pkill -f` match your own shell - use
   `ps -o PID,ARGS | grep "[n]ext"`.
11. A Next compile stall is almost always next/font/google: dev.log shows
   `unknown certificate verification error` + `Retrying 1/3...` then
   silence - node:https hangs in the font loader (its socket timeout
   never arms) while Bun's global fetch is reliable. Fix once per
   install: rewrite
   `node_modules/next/dist/compiled/@next/font/dist/google/fetch-resource.js`
   to use global `fetch(url, {signal: AbortSignal.timeout(8000)})`,
   return `Buffer.from(await res.arrayBuffer())`, throw unless status
   200. A few woff2 misses afterwards are fine - dev falls back to a
   system font by design.
12. BEFORE any big in-chat download, check the add-on store:
   `ls ../.addons/` (it sits in the workspace root, one level above
   every project). A `next-swc-wasm-<version>.tgz` there is the wasm
   SWC compiler, pre-downloaded by the app. Use it instead of letting
   Next download hundreds of MB:
   `tar -xzf ../.addons/next-swc-wasm-<version>.tgz` (extracts to
   ./package), then `mkdir -p node_modules/@next && mv package
   node_modules/@next/swc-wasm-nodejs`. Next resolves that package from
   node_modules before its own downloader runs; if the version does not
   match and Next downloads anyway, let it - the attempt cost nothing.
13. When the user wants to SEE a framework app, name the cheap paths
   BEFORE compiling anything: 1) the Add-ons entry in the app menu -
   the app itself downloads the wasm compiler once, token-free, and
   every later preview starts fast; 2) if GitHub is connected (env
   `key`), commit a GitHub Pages workflow on the project branch and
   push it yourself (main brief, GitHub section) - Pages builds and
   hosts the site; the token covers workflow files.
   Without GitHub, offer only option 1.
""".trimIndent() + "\n"


        // v9.42 step 1: the tools page exists because the main brief is the
        // only always-paid text, and a growing userland (a shell, ~190
        // coreutils, and more to come) cannot fit in it. The word budget on
        // the main brief is a structural rule, not a style preference, so the
        // detail lives here and the main brief points at it.
        val tools = """
# Tools & PATH - read once, before shelling out in a new project

PATH order is OUR prefix first, then Android's /system/bin:

  1. OUR prefix - a pinned shell (`sh`) plus ~190 coreutils (ls, cp, mv,
     sed, grep, find, tar, diff, sha256sum, gzip, vi, ...). Sealed in the
     APK: ONE version, the same command set on every phone, and executable
     where app data is not. PREFER THESE.
  2. /system/bin - the DEVICE's toybox. It varies by manufacturer and
     Android version, so a command that exists on one phone is missing on
     another. Use it only for something ours genuinely lacks (getprop,
     cmd, am, ...).

`bun` remains the SERVER runtime and is also available for scripting:
`bun -e '...'`, `bun run script.ts`, `Bun.serve`, fetch, JSON, bundling.
`node` is real Node 24 LTS and `npm`/`npx` are the bundled npm 11 package
manager; they are sealed APK executables, while installed package data stays
under the project. `git` speaks https (certs come from the device's own CA
store) and SSH. OpenSSH provides `ssh`, `scp`, `sftp`, `ssh-keygen`, `ssh-agent`,
`ssh-add`, and `ssh-keyscan`; keys/config/known_hosts live under `${'$'}HOME/.ssh`.
`rg` is ripgrep, and both `curl` and `wget` download through the same
sealed curl/Mbed TLS backend and the device CA store. `wget` supports ordinary
non-recursive downloads (`-q`, `-O`, `-P`, `-T`, headers, resume, and spider)
and fails loudly on crawl/mirror options instead of guessing. `jq` includes its
regex functions (`test`/`match`/`sub`), and `python3` is real CPython with its
stdlib (no pip - Android wheels are generally unavailable).

NOT installed - do not probe: pip, perl, ruby, apt. The webapp brief
has the working package patterns - read it BEFORE touching any project with
a package.json.
""".trimIndent() + "\n"

        // v10 (owner): MCP servers and skills, the way Claude Code users expect
        // them to work. Every path and command here is upstream's own (skill
        // discovery: `{skill,skills}/**/SKILL.md` under the config dirs) or was
        // device-verified; the owner's npx MCP setup is the reference case.
        val extensions = """
# MCP servers & skills - read before adding either

## Skills (instructions the agent loads when a task needs them)
- A skill is a folder with a `SKILL.md`. The header between the two `---` lines
  holds `name` and `description`; the body holds the instructions. Like a Claude
  Code skill, the header decides WHEN a skill is used: write `description` as
  "Use when ..." with the concrete situations and the words a user would say.
- Where skills live:
  - project skill: `.opencode/skills/<name>/SKILL.md` in the project (this project only);
  - global skill: `skills/<name>/SKILL.md` in the app's global config folder
    (every project of this app; it sits one level above `briefs/`).
  Upstream also accepts the singular folder name `skill/`.
- To create one: make the folder, write `SKILL.md` with the header, then a body of
  numbered steps, the exact commands, and the check that proves the job is done.
  Put long reference material in other files in the same folder and name them by
  relative path, so they are read only when a step needs them.
- To use one: the `skill` tool loads a skill by name. Load it once per task, not once
  per step, and follow it as written.
- A user can see what is installed in Settings > Skills (name, scope, description and
  the file's path). A skill created during a conversation may need the agent restarted
  before it appears there; if the skill you need is missing, say so and ask the user.
- Do not rewrite a skill nobody asked you to change. A skill is the user's instruction.

## MCP servers (external tools served by a local process)
- Register a server in the project's `opencode.json` at the project root, under `mcp`.
  If that file already exists, add the key and keep every entry that is there.
  Write valid JSON only.
- A local server (the only kind this runtime supports):
  `{"mcp": {"<name>": {"type": "local", "command": ["npx", "-y", "<package>"]}}}`
  Optional keys, all upstream's own: `environment` (an object of strings passed to the
  process), `enabled` (true/false) and `timeout` (milliseconds). For an `npx` server put
  `"timeout": 120000` in: the first start downloads the package, which takes longer
  than the default.
- The user can add a server in Settings > MCP servers > Add a server by typing only the
  npm package name (the app builds the config and names the server). If you add one
  yourself, the same config is what it writes.
- A secret never goes into a file the user may commit. Put a placeholder in
  `environment`, and tell the user which value to fill in and where.
- `npx` and `bunx` both work. `node` is Node 24 and `npm` is npm 11.
- Remote `http` and `sse` servers are NOT supported by this runtime. That is an
  upstream restriction. If the user asks for one, say so; do not fake it.
- Check a server in Settings > MCP servers. Each row shows upstream's own status word
  (connected, failed, needs_auth). "Refresh statuses" re-reads them. A failed server
  shows upstream's error text: quote it and do not guess the cause.
- After changing `opencode.json`, refresh the statuses. If the server still does not
  appear, ask the user to restart the agent from Settings > Runtime.
- Known Node limit: some installers crash with `Invalid regular expression` on Unicode
  property escapes such as `\p{L}`. If a Node command fails that way, run the package's
  JavaScript entry point with `bun` instead (`bun node_modules/<pkg>/<entry>.js`), and
  say so in your reply.
- Install MCP packages under the project. Never install them into the app's own runtime.
""".trimIndent() + "\n"

        val video = """
# Making videos from code (MP4) - read before you make any video, GIF or animation

The app has NO native ffmpeg. Video works through a WebAssembly build of ffmpeg
(the npm package `@ffmpeg/core`, version 0.12.10), run by Node.js 24 that the app
bundles. This route was tested on a Linux host with Node 22 (round trip: 30 frames
encoded to H.264 MP4 and decoded back, 30 of 30 frames). It is NOT yet proven on the
phone: if a step fails there, quote the error and say so; do not guess.

## Steps
1. In the project folder, install the core once:
   `npm install @ffmpeg/core@0.12.10`
   (on shared storage npm may need `--no-bin-links`). Do not install the other
   `@ffmpeg/*` packages: the script below only needs the core.
2. Write `make-video.mjs` in the project folder with exactly this content, then
   change only the settings (W, H, FPS, N) and `drawFrame` for your video:

```js
// Makes out.mp4 from frames drawn by this script. Run it in the project folder:
//   node make-video.mjs
// Needs @ffmpeg/core 0.12.10 installed in this project (npm install @ffmpeg/core@0.12.10).
import fs from "fs";

// Settings: change these, and drawFrame, for your video.
const W = 320, H = 240, FPS = 10, N = 30;

// The ffmpeg WebAssembly build is written for a web worker. It needs the two
// globals a worker has, and nothing else from a browser.
const coreDir = "./node_modules/@ffmpeg/core/dist/esm";
globalThis.self = { location: { href: "file://" + process.cwd() + "/" + coreDir.slice(2) + "/" } };
const createFFmpegCore = (await import(coreDir + "/ffmpeg-core.js")).default;
const logs = [];
const core = await createFFmpegCore({
  wasmBinary: fs.readFileSync(coreDir + "/ffmpeg-core.wasm"),
  print: (m) => logs.push(m),
  printErr: (m) => logs.push(m),
});

// Draw frame f into px: raw RGBA, 4 bytes per pixel, row by row.
function drawFrame(f, px) {
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const o = (y * W + x) * 4;
      const inSquare = x > f * 8 && x < f * 8 + 60 && y > 90 && y < 150;
      px[o] = inSquare ? 255 : 20;
      px[o + 1] = 60;
      px[o + 2] = inSquare ? 40 : 200;
      px[o + 3] = 255;
    }
  }
}

const frames = new Uint8Array(W * H * 4 * N);
for (let f = 0; f < N; f++) {
  drawFrame(f, frames.subarray(f * W * H * 4, (f + 1) * W * H * 4));
}
core.FS.writeFile("frames.raw", frames);

const rc = core.exec("-f", "rawvideo", "-pix_fmt", "rgba", "-s", W + "x" + H, "-r", String(FPS),
  "-i", "frames.raw", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "ultrafast",
  "-movflags", "+faststart", "out.mp4");
if (rc !== 0) {
  console.error("encode failed, rc=" + rc + " last log: " + logs.slice(-3).join(" | "));
  process.exit(1);
}
// Check the file decodes end to end before you report success.
const check = core.exec("-v", "error", "-i", "out.mp4", "-f", "null", "-");
fs.writeFileSync("out.mp4", core.FS.readFile("out.mp4"));
console.log("wrote out.mp4 bytes=" + fs.statSync("out.mp4").size + " decode_rc=" + check);
if (check !== 0) process.exit(1);
```

3. Run `node make-video.mjs`. It writes `out.mp4` in the project folder and checks
   that the file decodes. A line `wrote out.mp4 ... decode_rc=0` means success.
4. Show the result: the file is `out.mp4`. To show it in the preview, write an
   `index.html` containing `<video controls src="out.mp4"></video>` and hand it off as
   the preview brief says. (The hand-off is not yet tested on the phone.)

## Limits (measured on the host, not the phone)
- The whole video is held in memory as raw RGBA: width x height x 4 x frames bytes.
  Tested: 320x240 x 30 frames; 640x360 x 72 frames; 1280x720 x 48 frames and
  1280x720 x 240 frames (885 MB raw buffer, 10 s of video, 7 s to encode).
  Larger sizes were not tested. Keep the raw buffer small on a phone (start
  with 640x360 and 10 to 15 seconds at 24 fps) and report any memory error verbatim.
- There is no canvas, no browser and no PNG decoder in this runtime. Draw pixels
  yourself in `drawFrame`, or use plain JavaScript to rasterise shapes and text.
- Audio is not included. Output is H.264 (libx264) in yuv420p with the faststart flag.
""".trimIndent() + "\n"

        return linkedMapOf(
            "preview.md" to preview,
            "screenshots.md" to screenshots,
            "webapp.md" to webapp,
            "tools.md" to tools,
            "extensions.md" to extensions,
            "video.md" to video,
        )
    }
}
