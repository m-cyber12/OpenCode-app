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
     * Install/refresh the brief and wire it into config.json.
     * [engine] names the device's WebView provider+version (v9.14): the
     * preview and all screenshots render with THAT engine, and the agent
     * must target it instead of assuming the newest Chrome.
     */
    fun install(configDir: File, engine: String = "unknown"): Boolean = runCatching {
        configDir.mkdirs()
        val brief = File(configDir, FILE_NAME)
        val text = briefText(engine)
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
     * The brief itself. Every fact in it is either enforced by this codebase
     * (ports, serve.json, latest.png, loopback-only) or was verified on a
     * real device (toybox inventory, seccomp noise, setsid-vs-nohup, the
     * missing $TMPDIR from the FocusList run's honest-failure report).
     * v9.19 diet (owner: "we should have a token saver"): this text rides
     * in EVERY request of EVERY session, so the same rules now cost about
     * half the tokens. Nothing was dropped - only prose.
     */
    fun briefText(engine: String = "unknown"): String {
        val watched = PortScanner.CANDIDATE_PORTS.joinToString(", ")
        return """
# Device environment (written by the OpenCode Android app; regenerated at startup - do not edit)

You run INSIDE an Android app on the user's phone (no root, no VM). All
facts below are enforced or device-verified - trust them, never probe.

## Tools
On PATH: `bun` (full runtime - `bun -e`, scripts, `Bun.serve`, `fetch`,
JSON, bundling: use it for everything python/node/curl would do), `git`,
`rg`, plus /system/bin toybox (sh, ls, cp, mv, sed, grep, awk, tar, find,
diff, ps, kill, nc, base64, sha256sum, ...).
NOT installed - do not probe: python, node, npm, pip, perl, ruby, curl,
wget, apt.

## Quirks (device-verified)
- stderr `[seccomp] preload handler installed (rc=0)` is harmless noise.
- Only 127.0.0.1 is reachable; no LAN address exists - never offer
  `http://<device-ip>:port` URLs.
- `${'$'}TMPDIR` may NOT exist yet: `mkdir -p "${'$'}TMPDIR"` before using
  it, or just write scratch/log files inside the project directory.
- Plain `cmd &` and `nohup` can die with your shell; background with:
  `setsid sh serve.sh </dev/null >serve.log 2>&1 &`

## Live preview - how the user sees your work
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

## Stage screenshots
Write `.preview/capture.json`:
  {"name": "02-after-login-fix", "path": "/login.html", "port": 8080}
(omit "port" -> the app serves the project). The page renders off-screen
at device size into `screenshots/NNN-<name>.png` at the PROJECT ROOT
(NNN = capture order), plus a refreshed latest.png. This is the ONLY way
to take stage shots. Each capture confirms itself ~5s after the write, at
the end of `.preview/console.log`: `CAPTURE saved screenshots/NNN-<name>.png ...`
or `CAPTURE FAILED ... reason=...` - check that line; on FAILED report
the reason, do not improvise.

## Cost - every wasted step is billed
The whole conversation, including EVERY image ever read, is re-sent to
the model on each tool step. So: read a screenshot AT MOST ONCE, as late
as possible, never twice; check text (console.log, page source) before
pixels; verify once - never re-probe what cannot have changed; for plain
HTML/CSS/JS do NOT start your own server - serve.json without "port".

Static server recipe (bun, 8080, serves cwd; "/" falls back to the first
*.html; 404 instead of crashing on misses):
  setsid bun -e 'Bun.serve({port:8080,hostname:"127.0.0.1",async fetch(r){let p=decodeURIComponent(new URL(r.url).pathname);if(p==="/"){const h=[...new Bun.Glob("*.html").scanSync(".")];p="/"+(h.includes("index.html")?"index.html":(h[0]??"index.html"))}const f=Bun.file("."+p);return await f.exists()?new Response(f):new Response("Not found: "+p,{status:404})}})' </dev/null >serve.log 2>&1 &
""".trimIndent() + "\n"
    }
}
