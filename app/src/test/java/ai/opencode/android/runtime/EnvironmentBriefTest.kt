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
 */
class EnvironmentBriefTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun instructionsOf(dir: java.io.File): List<String> {
        val config = JSONObject(java.io.File(dir, "config.json").readText())
        val arr = config.getJSONArray("instructions")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test
    fun freshInstallWritesBriefAndWiresConfig() {
        val dir = tmp.newFolder("opencode")
        assertTrue(EnvironmentBrief.install(dir))
        val brief = java.io.File(dir, EnvironmentBrief.FILE_NAME)
        assertTrue(brief.isFile)
        assertEquals(listOf(brief.absolutePath), instructionsOf(dir))
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
        // The brief is still written (harmless), the config is untouched.
        assertTrue(java.io.File(dir, EnvironmentBrief.FILE_NAME).isFile)
        assertEquals(jsonc, java.io.File(dir, "config.json").readText())
    }

    @Test
    fun briefRefreshesWhenContentChanges() {
        val dir = tmp.newFolder("opencode")
        val brief = java.io.File(dir, EnvironmentBrief.FILE_NAME)
        brief.writeText("stale from an older app version\n")
        assertTrue(EnvironmentBrief.install(dir))
        assertEquals(EnvironmentBrief.briefText(), brief.readText())
    }

    @Test
    fun briefTellsTheTruthAboutTheEnvironment() {
        val text = EnvironmentBrief.briefText()
        // Every watched port is documented - the scanner and the brief can't drift.
        for (port in PortScanner.CANDIDATE_PORTS) {
            assertTrue("brief must document watched port $port", text.contains(port.toString()))
        }
        assertTrue(text.contains("bun"))
        assertTrue(text.contains("serve.json"))
        assertTrue(text.contains(".preview/latest.png"))
        assertTrue(text.contains("127.0.0.1"))
        assertTrue(text.contains("seccomp"))
        assertTrue(text.contains("setsid"))
        // And never promises what the device cannot do.
        assertFalse(text.contains("<device-ip>:port rendered as reachable"))
    }
}
