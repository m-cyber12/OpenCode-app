package ai.opencode.android.runtime

import ai.opencode.android.preview.PortScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v9.9 (owner): "the model should already be fully aware of the environment
 * and its capabilities" - the agent was observed burning a 12-minute turn
 * probing for python/node/curl (none exist), never finding `bun` (which
 * does), rediscovering the seccomp stderr noise, and learning by trial that
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
            val f = File(dir, name)
            if (!f.isFile || f.readText() != text) f.writeText(text)
        }
        val brief = File(configDir, FILE_NAME)
        val text = briefText(engine, dir.absolutePath)
        if (!brief.isFile || brief.readText() != text) brief.writeText(text)
        wireConfig(File(configDir, "config.json"), brief.absolutePath)
    }.getOrDefault(false)

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
them, never probe.

## Tools
On PATH: `bun` (full runtime - `bun -e`, scripts, `Bun.serve`, `fetch`,
JSON, bundling: use it for everything python/node/curl would do), `git`,
`rg`, plus /system/bin toybox (sh, ls, cp, mv, sed, grep, awk, tar, find,
diff, ps, kill, nc, base64, sha256sum, ...).
NOT installed - do not probe: python, node, npm, pip, perl, ruby, curl,
wget, apt. There is NO node binary at all: npm-style CLIs start with
`#!/usr/bin/env node` and die - the webapp brief below has the working
pattern before you touch any package.json project.

## Quirks (device-verified)
- stderr `[seccomp] preload handler installed (rc=0)` is harmless noise.
- Only 127.0.0.1 is reachable; no LAN address exists.
- `${'$'}TMPDIR` may NOT exist yet: `mkdir -p "${'$'}TMPDIR"` first, or keep
  scratch/log files inside the project directory.
- Plain `cmd &` and `nohup` can die with your shell; background with:
  `setsid sh serve.sh </dev/null >serve.log 2>&1 &`

## Live preview, in one line
A server on a watched loopback port shows the user a "Live preview" button
by itself; or hand off explicitly by writing `.preview/serve.json`
({"port": 8080, "path": "/page.html"} - the path must name a file that
EXISTS; omit "port" for plain HTML/CSS/JS: the app serves the project
itself, do NOT start your own server). Read the preview brief ONCE
before your first hand-off.

## Cost - every wasted step is billed
The whole conversation, including EVERY image ever read, is re-sent to the
model on each tool step. Read a screenshot AT MOST ONCE, as late as
possible, never twice; check text before pixels; verify once - never
re-probe what cannot have changed; read each topic brief at most once and
only when its topic is actually at hand.

## Topic briefs - the summary tells you which file; go straight there
- $briefsDir/preview.md - full hand-off/reload rules, the watched port
  list, rendering engine + layout rules (viewport meta, vh not dvh), the
  no-dependency static-server recipe. Read before the FIRST preview
  hand-off, or when the pane looks wrong.
- $briefsDir/screenshots.md - stage screenshots via `.preview/capture.json`,
  the self-confirming CAPTURE log lines, latest.png/console.log debugging.
  Read before taking or reading any screenshot.
- $briefsDir/webapp.md - running a real/cloned framework project (Next,
  Vite, ...): install, starting dev servers WITHOUT node, readiness,
  missing-env crashes. Read BEFORE the first command in any project that
  has a package.json.
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
A server listening on any of them makes a "Live preview" button appear by
itself. To hand off explicitly (ANY port, opens immediately), write
`.preview/serve.json` at the project root:
  {"port": 8080, "path": "/page.html"} -> show the server you started
  {"path": "/page.html"}               -> no server: the app serves this
                                          project over loopback itself
Re-writing serve.json after edits RELOADS the preview with fresh bytes
(never cached) - re-trigger instead of wondering. Two rules:
  1. `path` must name a file that EXISTS ("/" needs index.html).
  2. Confirm the server answers BEFORE the hand-off:
     `nc -w 2 127.0.0.1 8080 < /dev/null`.

After each load the app saves the frame to `.preview/latest.png` and
mirrors the page's JS console + load errors + a geometry line
(`VIEWPORT WxH dpr=... page=... scrollY=...`) to `.preview/console.log`.
Debug in that order: console.log and the VIEWPORT text are cheap, a
screenshot read is expensive. Preview and screenshots render with THIS
engine, not the Chrome app: $engine - target it.
Layout rules (pane == browser):
- Always `<meta name="viewport" content="width=device-width, initial-scale=1">`.
- Full-height/centered layouts: plain `vh` + `html,body{height:100%;margin:0}`.
  NEVER use dvh/svh/lvh - they can collapse the layout on this engine.

Static server recipe (bun, 8080, serves cwd; "/" falls back to the first
*.html; 404 instead of crashing on misses):
  setsid bun -e 'Bun.serve({port:8080,hostname:"127.0.0.1",async fetch(r){let p=decodeURIComponent(new URL(r.url).pathname);if(p==="/"){const h=[...new Bun.Glob("*.html").scanSync(".")];p="/"+(h.includes("index.html")?"index.html":(h[0]??"index.html"))}const f=Bun.file("."+p);return await f.exists()?new Response(f):new Response("Not found: "+p,{status:404})}})' </dev/null >serve.log 2>&1 &
""".trimIndent() + "\n"

        val screenshots = """
# Stage screenshots - read once, before the first screenshot

Write `.preview/capture.json`:
  {"name": "02-after-login-fix", "path": "/login.html", "port": 8080}
(omit "port" -> the app serves the project). The page renders off-screen
at device size into `screenshots/NNN-<name>.png` at the PROJECT ROOT
(NNN = capture order), plus a refreshed `.preview/latest.png`. This is
the ONLY way to take stage shots - never copy latest.png around by hand.
Each capture confirms itself ~5s after the write, at the end of
`.preview/console.log`: `CAPTURE saved screenshots/NNN-<name>.png ...` or
`CAPTURE FAILED ... reason=...` - check that line instead of assuming; on
FAILED report the reason, do not improvise workarounds. Name shots after
the stage they document, and remember the cost rule: each image you read
rides along on every later step.
Under a dev server's cold compile or HMR churn a capture can report
`timeout` - retry once the route is warm. `.preview/latest.png` can lag
the live pane; check its mtime before trusting it.
""".trimIndent() + "\n"

        val webapp = """
# Running a real project (package.json exists) - read BEFORE the first command

Verified on-device end to end: a cloned Next.js 15 + React 19 + next-intl
site served into the Live preview. Follow the order - every skipped step
was once paid for in wasted turns. And serve the user's REAL site: no
placeholder status pages.

1. Read package.json FIRST - `scripts` and `engines`. Never guess the
   toolchain.
2. Install once, in the background, then WAIT (big trees: ~10 min,
   hundreds of MB):
   `mkdir -p "${'$'}TMPDIR" 2>/dev/null; setsid bun install </dev/null >install.log 2>&1 &`
   Poll `du -sh node_modules` and `tail -3 install.log`. On shared
   storage the install ENDS with `Failed to link <pkg>: EACCES` lines -
   HARMLESS: packages extract fully, only `node_modules/.bin` is never
   created. Do not reinstall.
3. Starting tools: there is NO node (`#!/usr/bin/env node` shebangs
   cannot run) and no `.bin`, so `bun run dev` / `next dev` will not
   resolve. Run the tool's JS entry under bun by ABSOLUTE path (relative
   script paths die with `CouldntReadCurrentDirectory`; `bun -e` is
   unaffected):
   `setsid bun /abs/project/node_modules/next/dist/bin/next dev -p 8080 -H 127.0.0.1 </dev/null >dev.log 2>&1 &`
4. Native addons (`.node` prebuilds) cannot dlopen inside the app. When
   a dep crashes on one, stub THAT module's JS entry inside node_modules
   (no-op or passthrough) - never touch project source; stubs die with
   node_modules, so re-apply after any reinstall. Verified Next 15 set:
   - `@parcel/watcher` -> no-op stub (file-watching is optional in dev);
   - `@swc/core` -> passthrough stub (only next-intl's message extractor
     uses it);
   - Next itself falls back to wasm SWC automatically, but first patch
     `node_modules/next/dist/lib/helpers/get-registry.js` to return
     `https://registry.npmjs.org/` directly (it shells out to npm, which
     does not exist), and `rm -rf node_modules/next/wasm
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
""".trimIndent() + "\n"

        return linkedMapOf(
            "preview.md" to preview,
            "screenshots.md" to screenshots,
            "webapp.md" to webapp,
        )
    }
}
