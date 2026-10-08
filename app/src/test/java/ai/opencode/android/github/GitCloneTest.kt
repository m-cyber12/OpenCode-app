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
        // Never wait on a terminal that is not there.
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        // Optional dirs omitted -> keys absent (never empty strings).
        assertFalse(env.containsKey("LD_PRELOAD"))
        assertFalse(env.containsKey("XDG_CONFIG_HOME"))
    }

    @Test
    fun seccompShimRidesAsLdPreloadLikeTheServerTree() {
        // v9.26 (owner device): without the shim, git's remote-https helper
        // was seccomp-killed ("remote helper 'https' aborted session"). The
        // app-side env must carry it exactly like the server tree does.
        val env = GitClone.baseEnv(
            home = File("/data/home"),
            binDir = File("/data/bin"),
            tmp = File("/data/tmp"),
            xdgConfig = File("/data/xdg/config"),
            xdgData = File("/data/xdg/data"),
            xdgCache = File("/data/xdg/cache"),
            seccompShim = File("/lib/libseccompshim.so"),
        )
        assertEquals("/lib/libseccompshim.so", env["LD_PRELOAD"])
        assertEquals("/data/xdg/config", env["XDG_CONFIG_HOME"])
        assertEquals("/data/xdg/data", env["XDG_DATA_HOME"])
        assertEquals("/data/xdg/cache", env["XDG_CACHE_HOME"])
    }

    @Test
    fun oneBranchPerProjectNamedAfterIt() {
        // v9.26 (owner): the WHOLE project - every chat - works on one
        // `opencode/<project>` branch created at clone time.
        assertEquals("opencode/creatorshub", GitClone.projectBranch("CreatorsHub"))
        val cmd = GitClone.branchCommand(File("/g"), GitClone.projectBranch("site"))
        assertEquals(listOf("/g", "checkout", "-b", "opencode/site"), cmd)
    }
}
