package ai.opencode.android.runtime

import ai.opencode.android.preview.PortScanner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v9.9: the environment brief - the Arena-style "know your environment"
 * context injected through OpenCode's own `config.instructions` mechanism.
 * The owner watched an agent waste a 12-minute turn probing for tools that
 * don't exist; these tests pin the brief's installation contract.
 *
 * v9.21 (owner): the brief is SPLIT - one lean main brief (the only
 * always-injected text) carrying an index, plus topic briefs read on
 * demand. The tests therefore pin three things: every fact that used to be
 * guaranteed is still guaranteed SOMEWHERE in the installed set (never
 * weakened), the main brief stays lean, and the index actually points at
 * every topic file so the agent never searches.
 */
class EnvironmentBriefTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun instructionsOf(dir: java.io.File): List<String> {
        val config = JSONObject(java.io.File(dir, "config.json").readText())
        val arr = config.getJSONArray("instructions")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** Main brief + every topic brief - "the installed set" the agent can reach. */
    private fun combined(engine: String = "unknown"): String =
        EnvironmentBrief.briefText(engine) + "\n" +
            EnvironmentBrief.topicBriefs(engine).values.joinToString("\n")

    @Test
    fun freshInstallWritesBriefAndWiresConfig() {
        val dir = tmp.newFolder("opencode")
        assertTrue(EnvironmentBrief.install(dir))
        val brief = java.io.File(dir, EnvironmentBrief.FILE_NAME)
        assertTrue(brief.isFile)
        // ONLY the main brief is injected; topic files are read on demand.
        assertEquals(listOf(brief.absolutePath), instructionsOf(dir))
        // Every topic file the index promises actually exists on disk.
        val briefs = java.io.File(dir, EnvironmentBrief.BRIEFS_DIR)
        for (name in EnvironmentBrief.topicBriefs().keys) {
            assertTrue("missing topic brief $name", java.io.File(briefs, name).isFile)
        }
    }

    @Test
    fun installingTwiceAddsNothingTwice() {
        val dir = tmp.newFolder("opencode")
        assertTrue(EnvironmentBrief.install(dir))
        assertTrue(EnvironmentBrief.install(dir))
        assertEquals(1, instructionsOf(dir).size)
    }

    @Test
    fun userConfigIsExtendedNeverReplaced() {
        val dir = tmp.newFolder("opencode")
        java.io.File(dir, "config.json").writeText(
            """{"theme":"custom","instructions":["/sdcard/my-rules.md"]}""",
        )
        assertTrue(EnvironmentBrief.install(dir))
        val config = JSONObject(java.io.File(dir, "config.json").readText())
        assertEquals("custom", config.getString("theme"))
        val briefPath = java.io.File(dir, EnvironmentBrief.FILE_NAME).absolutePath
        assertEquals(setOf("/sdcard/my-rules.md", briefPath), instructionsOf(dir).toSet())
    }

    @Test
    fun unparseableUserConfigIsLeftByteForByteAlone() {
        val dir = tmp.newFolder("opencode")
        val jsonc = "// my hand-written config\n{ \"theme\": \"x\", }\n"
        java.io.File(dir, "config.json").writeText(jsonc)
        assertFalse(EnvironmentBrief.install(dir))
        // The briefs are still written (harmless), the config is untouched.
        assertTrue(java.io.File(dir, EnvironmentBrief.FILE_NAME).isFile)
        assertEquals(jsonc, java.io.File(dir, "config.json").readText())
    }

    @Test
    fun briefsRefreshWhenContentChanges() {
        val dir = tmp.newFolder("opencode")
        val brief = java.io.File(dir, EnvironmentBrief.FILE_NAME)
        brief.writeText("stale from an older app version\n")
        val briefsDir = java.io.File(dir, EnvironmentBrief.BRIEFS_DIR)
        briefsDir.mkdirs()
        val topic = java.io.File(briefsDir, "webapp.md")
        topic.writeText("stale topic\n")
        assertTrue(EnvironmentBrief.install(dir))
        assertEquals(
            EnvironmentBrief.briefText("unknown", briefsDir.absolutePath),
            brief.readText(),
        )
        assertEquals(EnvironmentBrief.topicBriefs().getValue("webapp.md"), topic.readText())
    }

    @Test
    fun mainBriefIndexesEveryTopicFileAndStaysLean() {
        val main = EnvironmentBrief.briefText("unknown", "/cfg/briefs")
        // The index points at every topic file by its real absolute path -
        // the agent must never have to search for the right brief.
        for (name in EnvironmentBrief.topicBriefs().keys) {
            assertTrue("index must point at $name", main.contains("/cfg/briefs/$name"))
        }
        // The main brief is the only always-paid text; keep it lean forever.
        val words = main.split(Regex("\\s+")).count { it.isNotBlank() }
        // v9.23: budget raised 480 -> 485 for the GitHub connector section +
        // the do-not-edit-briefs rule. v9.25: -> 515 for the app-cloned-repo
        // branch contract. v9.27: -> 535 for the transport truth: the bundled
        // git has NO https - a model that tries to push burns tokens on a
        // guaranteed failure, so the brief must say the app is the transport.
        // v9.29 REVERSED that: https git is device-proven, the model pushes
        // itself - the section was rewritten within the same 535 budget.
        // v9.30: -> 550 for the owner's correction: a model in a default
        // project blamed a "missing token" instead of knowing the absence is
        // the design and pointing at Create project > GitHub.
        // v9.31: -> 620 for the question-tool section (batch asks with
        // options; the Plan-mode approval contract). Models tried to ask
        // structured questions on device and hit Invalid Tool - now the tool
        // is enabled AND named, and plan approval rides on it.
        // v9.33: -> 665. Device run: the model answered the approval with
        // "Ready to build when you switch to build mode" - wrong (the app
        // switches itself), and the owner flagged the double approval
        // (question answer + hand-off prompt) as a contradiction. The brief
        // now forbids closing prose and names the two as ONE approval.
        // All owner-mandated content; everything else still fights per word.
        assertTrue("main brief grew to $words words - split or trim it", words < 665)
        // The core product loop and cost discipline stay in the always-read part.
        assertTrue(main.contains("serve.json"))
        assertTrue(main.contains("EXISTS"))
        assertTrue(main.contains("/page.html"))
        assertTrue(main.contains("do NOT start your own server"))
        assertTrue(main.contains("AT MOST ONCE"))
        assertTrue(main.contains("mkdir -p \"\$TMPDIR\""))
        assertTrue(main.contains("seccomp"))
        assertTrue(main.contains("setsid"))
        assertTrue(main.contains("127.0.0.1"))
    }

    @Test
    fun installedSetTellsTheTruthAboutTheEnvironment() {
        // Every guarantee of the pre-split brief, now asserted against the
        // installed SET (main + topics) - the split may move facts, never
        // lose them.
        val text = combined()
        for (port in PortScanner.CANDIDATE_PORTS) {
            assertTrue("set must document watched port $port", text.contains(port.toString()))
        }
        assertTrue(text.contains("bun"))
        assertTrue(text.contains("serve.json"))
        assertTrue(text.contains(".preview/latest.png"))
        assertTrue(text.contains("127.0.0.1"))
        assertTrue(text.contains("seccomp"))
        assertTrue(text.contains("setsid"))
        assertTrue(text.contains("EXISTS"))
        assertTrue(text.contains("/page.html"))
        assertTrue(text.contains("status:404"))
        assertTrue(text.contains("Bun.Glob"))
        assertTrue("server must be checked before the hand-off", text.contains("nc -w 2 127.0.0.1"))
        assertTrue(text.contains(".preview/console.log"))
        assertTrue(text.contains("capture.json"))
        assertTrue(text.contains("screenshots/NNN-<name>.png"))
        assertTrue(text.contains("width=device-width"))
        assertTrue(text.contains("AT MOST ONCE"))
        assertTrue(text.contains("do NOT start your own server"))
        assertTrue(text.contains("CAPTURE saved screenshots/"))
        assertTrue(text.contains("CAPTURE FAILED"))
        assertTrue(text.contains("NEVER use dvh/svh/lvh"))
        assertTrue(text.contains("VIEWPORT"))
        assertTrue(text.contains("html,body{height:100%;margin:0}"))
        // v9.19 (FocusList honest failure): no recipe may log into $TMPDIR.
        assertTrue(text.contains("mkdir -p \"\$TMPDIR\""))
        assertFalse(text.contains("\$TMPDIR/serve.log"))
        // The engine is named where rendering is explained (preview brief).
        assertTrue(
            EnvironmentBrief.topicBriefs("com.test.webview 123.4.5")
                .getValue("preview.md").contains("com.test.webview 123.4.5"),
        )
        // And never promises what the device cannot do.
        assertFalse(text.contains("<device-ip>:port rendered as reachable"))
    }

    @Test
    fun webappBriefCarriesTheCreatorsHubLessons() {
        // v9.21: a cloned Next.js 15 site cost ~400k tokens of trial and
        // error on-device. v9.22: replaced my distillation with the MODEL'S
        // OWN verified setup log (owner-provided) - it corrected one piece
        // (shared storage never links node_modules/.bin, so `bun --bun run`
        // cannot resolve; the absolute-path JS entry is the working route)
        // and added the discoveries below. Each is pinned because each one
        // cost real turns to find.
        val webapp = EnvironmentBrief.topicBriefs().getValue("webapp.md")
        assertTrue(webapp.contains("package.json"))
        assertTrue(webapp.contains("#!/usr/bin/env node"))
        assertTrue(webapp.contains("bun install"))
        assertTrue(webapp.contains("install.log"))
        // Shared-storage install truth: EACCES link failures are harmless,
        // .bin never exists - so tools start by ABSOLUTE path under bun.
        assertTrue(webapp.contains("EACCES"))
        assertTrue(webapp.contains("node_modules/.bin"))
        assertTrue(webapp.contains("CouldntReadCurrentDirectory"))
        assertTrue(webapp.contains("bun /abs/project/node_modules/next/dist/bin/next dev -p 8080 -H 127.0.0.1"))
        // Native addons cannot dlopen; the verified Next 15 stub set.
        assertTrue(webapp.contains("@parcel/watcher"))
        assertTrue(webapp.contains("@swc/core"))
        assertTrue(webapp.contains("get-registry.js"))
        assertTrue(webapp.contains("rm -rf node_modules/next/wasm"))
        // The fonts-TLS flake self-heals; nobody should chase env flags.
        assertTrue(webapp.contains("fonts.gstatic.com"))
        // Hand-off and cleanup truths.
        assertTrue(webapp.contains("{\"port\": 8080, \"path\": \"/en\"}"))
        assertTrue(webapp.contains("nc -w 2 127.0.0.1 8080"))
        assertTrue(webapp.contains(".env.local"))
        assertTrue(webapp.contains("pkill"))
        // The main brief's index warns about the node trap BEFORE the agent
        // ever opens the topic file - that is where the 400k went.
        assertTrue(EnvironmentBrief.briefText().contains("#!/usr/bin/env node"))
        // And screenshots.md carries the capture-under-HMR truths.
        val shots = EnvironmentBrief.topicBriefs().getValue("screenshots.md")
        assertTrue(shots.contains("mtime"))
        assertTrue(shots.contains("timeout"))
    }

    @Test
    fun v923BriefsCarrySecondRunLessonsAndGithubContract() {
        // v9.23: the model's SECOND CreatorsHub run (owner-provided report)
        // burned ~100k tokens, ~80% of it on one silent font-loader hang and
        // on process-tree kills. Each lesson below cost real turns; pin them.
        val webapp = EnvironmentBrief.topicBriefs().getValue("webapp.md")
        // Ready != compiled; warm in background with a long fetch timeout.
        assertTrue(webapp.contains("AbortSignal.timeout(420000)"))
        // Slow-vs-stuck probes are text, not screenshots.
        assertTrue(webapp.contains(".next/trace"))
        assertTrue(webapp.contains("build-module"))
        assertTrue(webapp.contains("/proc/PID/stat"))
        // The font stall: symptom line, patched file, and the fix's shape.
        assertTrue(webapp.contains("unknown certificate verification error"))
        assertTrue(webapp.contains("fetch-resource.js"))
        assertTrue(webapp.contains("AbortSignal.timeout(8000)"))
        // Process hygiene: no self-matching pgrep/pkill, tree-based verify.
        assertTrue(webapp.contains("ps -o PID,ARGS | grep \"[n]ext\""))
        assertTrue(webapp.contains("[ -d node_modules/next ]"))
        // Preview hand-offs can go stale; re-verify before trusting them.
        val preview = EnvironmentBrief.topicBriefs().getValue("preview.md")
        assertTrue(preview.contains("go STALE"))
        // Main brief: the GitHub connector contract + the no-self-edit rule
        // (the owner caught a model editing the briefs; they are app-owned).
        val main = EnvironmentBrief.briefText()
        // v9.29 (owner): git speaks https on device (proven 2026-10-09) and
        // the model pushes its own work - the brief must carry the URL push
        // recipe and must NOT claim the old no-transport/auto-push world.
        assertTrue(main.contains("x-access-token"))
        assertTrue(main.contains("\$key@github.com"))
        assertTrue(main.contains("git remote get-url origin"))
        assertFalse(main.contains("NO network transport"))
        assertFalse(main.contains("auto-push"))
        // Non-GitHub projects have no token AT ALL now - the brief says so.
        // v9.30 (owner device pass): a model in a default project told the
        // user "you didn't set the token". Wrong lesson. The brief must say
        // absence is the DESIGN, never a user mistake, and must point at the
        // create-a-GitHub-project path instead of Settings.
        assertTrue(main.contains("BY DESIGN"))
        assertTrue(main.contains("never a missing token"))
        assertTrue(main.contains("Create project > GitHub"))
        // v9.31: the question tool is enabled (client allowlist + env flag)
        // and the brief must teach it by NAME - batched questions with
        // options.
        assertTrue(main.contains("`question` tool"))
        assertTrue(main.contains("ONE call"))
        // v9.34: plan approval is upstream plan_exit's own ask now (the
        // experimental plan mode the app enables). The brief must name the
        // tool, forbid a duplicate question-tool approval, and demand
        // same-turn continuation with no closing prose - the v9.33 hand-off
        // prompt and the "Approve plan" label are gone WITH the machinery.
        assertTrue(main.contains("`plan_exit`"))
        assertTrue(main.contains("SAME\nTURN") || main.contains("SAME TURN"))
        assertTrue(main.contains("start executing immediately"))
        assertFalse(main.contains("Approve plan"))
        assertFalse(main.contains("hand-off prompt"))
        assertTrue(main.contains("closing prose"))
        assertTrue(main.contains("never print or commit it"))
        assertTrue(main.contains("BRIEF-SUGGESTIONS.md"))
        assertTrue(main.contains("never\nedit them") || main.contains("never edit them"))
    }

    @Test
    fun v924WebappBriefTeachesTheAddonStoreAndTheCheapPreviewPaths() {
        // v9.24 (owner): the app pre-downloads the wasm SWC compiler into
        // <workspace>/.addons; the brief must (a) point agents at it BEFORE
        // they re-download hundreds of MB mid-chat, and (b) make them OFFER
        // the cheap paths (add-on, GitHub Pages) before compiling anything.
        val webapp = EnvironmentBrief.topicBriefs().getValue("webapp.md")
        assertTrue(webapp.contains("../.addons"))
        // The filename contract must match Addons.tarballName exactly.
        assertTrue(webapp.contains("next-swc-wasm-<version>.tgz"))
        assertTrue(webapp.contains("node_modules/@next/swc-wasm-nodejs"))
        assertTrue(webapp.contains("GitHub Pages"))
        // The GitHub option is gated on the connector being present.
        assertTrue(webapp.contains("Without GitHub, offer only option 1"))
        // v9.26: app-cloned repos - the agent must know it is ALREADY on the
        // project's ONE branch (opencode/<project>) with a token-free origin
        // (so it pushes the URL form, never writes the token into .git/config,
        // and does not shard the project across per-chat branches).
        val main = EnvironmentBrief.briefText()
        assertTrue(main.contains("opencode/<project>"))
        assertTrue(main.contains("token-free origin"))
        assertTrue(main.contains("no extra branches"))
    }
}
