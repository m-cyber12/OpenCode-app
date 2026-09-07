package ai.opencode.android.memory

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the AGENTS.md reader/writer. These are the exact files OpenCode
 * loads (a project-root AGENTS.md and the global-config AGENTS.md), so the tests
 * pin the invariants that make the memory layer honest: the right paths, blank
 * meaning "no rules", and removal actually deleting the file.
 */
class ProjectMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun memory(): ProjectMemory = ProjectMemory(
        workspacesRoot = tmp.newFolder("workspaces"),
        globalRulesDir = tmp.newFolder("xdg/config/opencode"),
    )

    @Test
    fun projectAndGlobalFilesLiveInTheirOwnScopes() {
        val m = memory()
        assertEquals("AGENTS.md", m.projectFile("p").name)
        assertEquals("AGENTS.md", m.globalFile().name)
        assertTrue(m.projectFile("p").absolutePath.contains("workspaces"))
        assertTrue(m.globalFile().absolutePath.contains("opencode"))
        // The two scopes must never be the same file.
        assertFalse(m.projectFile("p").absolutePath == m.globalFile().absolutePath)
    }

    @Test
    fun writeThenReadRoundTrips() {
        val m = memory()
        assertTrue(m.writeProject("app", "You are building a Kotlin app."))
        assertEquals("You are building a Kotlin app.", m.readProject("app"))
        assertTrue(m.hasProject("app"))
    }

    @Test
    fun blankWriteRemovesTheFile() {
        val m = memory()
        m.writeProject("app", "some rules")
        assertTrue(m.writeProject("app", "   "))
        assertFalse(m.hasProject("app"))
        assertEquals("", m.readProject("app"))
    }

    @Test
    fun removeDeletesTheFile() {
        val m = memory()
        m.writeGlobal("always be terse")
        assertTrue(m.hasGlobal())
        assertTrue(m.removeGlobal())
        assertFalse(m.hasGlobal())
        assertEquals("", m.readGlobal())
    }

    @Test
    fun readingAMissingFileIsEmptyNotAnError() {
        val m = memory()
        assertEquals("", m.readProject("nope"))
        assertEquals("", m.readGlobal())
        assertFalse(m.hasProject("nope"))
        assertFalse(m.hasGlobal())
    }

    @Test
    fun contentIsTrimmedOfSurroundingWhitespace() {
        val m = memory()
        m.writeGlobal("  \nrule one\nrule two\n  ")
        assertEquals("rule one\nrule two", m.readGlobal())
    }
}
