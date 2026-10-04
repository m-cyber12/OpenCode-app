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
     * real device (toybox inventory, seccomp noise, setsid-vs-nohup).
     * Kept deliberately compact: it rides along in every session's context
     * (~600 tokens) and pays for itself by ending blind probing.
     */
    fun briefText(engine: String = "unknown"): String {
        val watched = PortScanner.CANDIDATE_PORTS.joinToString(", ")
        return """
# Device environment (written by the OpenCode Android app; regenerated at startup - do not edit)

You run INSIDE an Android app on the user's phone. No root, no VM, no other
package manager. The facts below are enforced or device-verified - trust them
instead of probing.

## Tools that exist (on PATH)
- `bun` - full Bun JavaScript runtime. Your escape hatch for everything:
  one-liners (`bun -e '...'`), scripts, HTTP servers (`Bun.serve`), HTTP
  requests (`fetch`), JSON, bundling.
- `git`, `rg` (ripgrep) - real builds.
- `/system/bin` toybox: sh, ls, cp, mv, sed, grep, awk, tar, gzip, find,
  diff, ps, kill, nc/netcat, base64, sha256sum, and the usual rest.

## Tools that do NOT exist
No python, node, npm, npx, pip, perl, ruby, curl, wget, apt, brew.
Do not spend turns probing for them - use `bun` for anything they would do.

## Quirks (device-verified; rediscovering each one wastes a turn)
- stderr lines `[seccomp] preload handler installed (rc=0)` are harmless
  loader noise. Ignore them everywhere.
- `ip addr` fails (no netlink permission) and there is no usable LAN address:
  the ONLY reachable interface is 127.0.0.1. Other devices can never connect;
  do not offer `http://<device-ip>:port` URLs.
- Backgrounding: plain `cmd &` and `nohup` can die with your shell. Use:
  `setsid sh serve.sh </dev/null >"${'$'}TMPDIR/serve.log" 2>&1 &`
- Stay inside the project directory (your cwd); `${'$'}TMPDIR` is for scratch.

## Live preview - how the user sees your work (no permission needed)
The app probes these loopback ports every few seconds:
  $watched
The moment your server listens on one of them, a "Live preview" button
appears in the app by itself - start a server on 127.0.0.1 and you are done.

To hand off explicitly (ANY port, and the preview opens in front of the user
immediately), write `.preview/serve.json` at the project root:
  {"port": 8080, "path": "/page.html"} -> show the server you started
  {"path": "/page.html"}               -> no server needed: the app itself
                                          serves this project over loopback http
A fresh write of that file is the trigger - and re-writing it after editing
the page RELOADS the preview with fresh bytes (the app's preview never
caches), so re-trigger instead of wondering whether the user sees the new
version. Two rules that prevent a broken first impression:
  1. `path` must point at a file that actually EXISTS. "/" only works if
     index.html exists - otherwise use the real filename ("/1.html").
  2. Start your server and check it answers BEFORE writing serve.json
     (`nc -w 2 127.0.0.1 8080 < /dev/null`); the preview opens within ~1.5s
     of the write and must not land on a dead or empty port.

After each page load the app screenshots the preview into
`.preview/latest.png` - read it to SEE what the user currently sees.
The preview and all screenshots render with THIS engine, not with the
Chrome app: $engine. Target that version's CSS/JS support.
Its JavaScript console and load errors are mirrored to
`.preview/console.log`. When the preview looks wrong, read console.log
FIRST - one SyntaxError silently kills a whole script. latest.png shows
WHAT rendered; console.log shows WHY not.
Layout rules that keep the preview identical to a browser:
- Always include `<meta name="viewport" content="width=device-width,
  initial-scale=1">`.
- For full-height or vertically centered layouts use plain `vh` units
  plus `html,body{height:100%;margin:0}`. NEVER use dvh/svh/lvh units -
  on this device's engine they can resolve to nothing and the layout
  silently collapses to the top of the page.

## Screenshots, organized - document any stage you consider important
Write `.preview/capture.json`:
  {"name": "02-after-login-fix", "path": "/login.html", "port": 8080}
The app loads that page OFF-SCREEN at device size (no preview needs to be
open) and saves the settled frame as `screenshots/NNN-<name>.png` at the
PROJECT ROOT, next to your other files (NNN = capture order), plus a
refreshed latest.png. Omit "port" to have the app serve the project
itself. This is the ONLY way to take stage screenshots - do not copy
latest.png around by hand. Every capture CONFIRMS ITSELF: ~5s after the
capture.json write, a line appears at the end of `.preview/console.log` -
`CAPTURE saved screenshots/NNN-<name>.png ...` on success or
`CAPTURE FAILED ... reason=...` on failure. Check that line instead of
assuming; if it says FAILED, report the reason, do not improvise
workarounds. Name shots after the stage they document.

## Cost - every wasted step is billed, keep turns lean
- The whole conversation is RE-SENT to the model on every tool step, and
  an image you read is re-sent with it, every step, until the task ends.
  Read a screenshot AT MOST ONCE, as late as possible (right before you
  verify or answer), and never re-read one you have already seen.
- Check text first: console.log and the page source are cheap;
  screenshots are expensive.
- For plain HTML/CSS/JS, do NOT start your own server - write serve.json
  without "port" and the app serves the project. Only run a server for
  dynamic behavior the static server cannot do.
- One verification is enough; do not re-probe, re-list or re-read files
  that cannot have changed.

Static server recipe (bun, port 8080, current directory; serves index.html
or the first *.html at "/", answers 404 instead of crashing on misses):
  setsid bun -e 'Bun.serve({port:8080,hostname:"127.0.0.1",async fetch(r){let p=decodeURIComponent(new URL(r.url).pathname);if(p==="/"){const h=[...new Bun.Glob("*.html").scanSync(".")];p="/"+(h.includes("index.html")?"index.html":(h[0]??"index.html"))}const f=Bun.file("."+p);return await f.exists()?new Response(f):new Response("Not found: "+p,{status:404})}})' </dev/null >"${'$'}TMPDIR/serve.log" 2>&1 &
""".trimIndent() + "\n"
    }
}
