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
        assertTrue("main brief grew to $words words - split or trim it", words < 480)
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
        // error on-device. The distilled steps must stay taught: no node
        // binary -> --bun pattern, background install with log polling,
        // readiness from the log, placeholder env, no build/lint detours.
        val webapp = EnvironmentBrief.topicBriefs().getValue("webapp.md")
        assertTrue(webapp.contains("package.json"))
        assertTrue(webapp.contains("#!/usr/bin/env node"))
        assertTrue(webapp.contains("bun --bun run dev"))
        assertTrue(webapp.contains("bun node_modules/next/dist/bin/next dev"))
        assertTrue(webapp.contains("bun install"))
        assertTrue(webapp.contains("install.log"))
        assertTrue(webapp.contains(".env.local"))
        assertTrue(webapp.contains("nc -w 2 127.0.0.1 3000"))
        assertTrue(webapp.contains("{\"port\": 3000, \"path\": \"/\"}"))
        // The main brief's index warns about the node trap BEFORE the agent
        // ever opens the topic file - that is where the 400k went.
        assertTrue(EnvironmentBrief.briefText().contains("#!/usr/bin/env node"))
    }
}
