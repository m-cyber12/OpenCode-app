package ai.opencode.android.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v9.25: the security property of the app-side clone, pinned on the JVM:
 * the token rides ONLY in the process environment (one-shot http.extraHeader
 * via GIT_CONFIG_*), never in argv (visible in `ps`) and never in the clone
 * URL (which git persists into .git/config).
 */
class GitCloneTest {

    @Test
    fun tokenNeverAppearsInArgvOrCloneUrl() {
        val git = File("/fake/bin/git")
        val cmd = GitClone.cloneCommand(git, "https://web.example/o/r.git", File("/ws/r"))
        assertEquals(listOf("/fake/bin/git", "clone", "--progress", "https://web.example/o/r.git", "/ws/r"), cmd)
        assertFalse(cmd.joinToString(" ").contains("tok"))
    }

    @Test
    fun authRidesAsOneShotConfigEnvWithBasicXAccessToken() {
        val env = GitClone.authEnv("tok123")
        assertEquals("1", env["GIT_CONFIG_COUNT"])
        assertEquals("http.extraHeader", env["GIT_CONFIG_KEY_0"])
        val expected = java.util.Base64.getEncoder()
            .encodeToString("x-access-token:tok123".toByteArray(Charsets.UTF_8))
        assertEquals("Authorization: Basic $expected", env["GIT_CONFIG_VALUE_0"])
        // Nothing but the three GIT_CONFIG_* keys - no stray token copies.
        assertEquals(setOf("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0"), env.keys)
    }

    @Test
    fun baseEnvMirrorsTheRuntimeChildEnv() {
        val env = GitClone.baseEnv(File("/data/home"), File("/data/bin"), File("/data/tmp"))
        assertEquals("/data/home", env["HOME"])
        assertTrue(env["PATH"]!!.startsWith("/data/bin:"))
        assertTrue(env["PATH"]!!.contains("/system/bin"))
        assertEquals("/data/tmp", env["TMPDIR"])
        assertEquals("C.UTF-8", env["LANG"])
    }

    @Test
    fun branchSchemeIsTheOwnersChoice() {
        assertEquals("opencode/chat-a1b2c3", GitClone.branchName("a1b2c3"))
        val cmd = GitClone.branchCommand(File("/g"), "opencode/chat-a1b2c3")
        assertEquals(listOf("/g", "checkout", "-b", "opencode/chat-a1b2c3"), cmd)
    }

    @Test
    fun chatIdsAreShortLowercaseHexAndNotConstant() {
        val ids = (1..50).map { GitClone.newChatId() }
        for (id in ids) assertTrue(id.matches(Regex("^[0-9a-f]{6}$")))
        assertTrue("50 draws produced one value - RNG broken", ids.toSet().size > 1)
    }
}
