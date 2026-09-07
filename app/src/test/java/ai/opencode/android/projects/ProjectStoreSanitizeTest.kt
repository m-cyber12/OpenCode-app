package ai.opencode.android.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the project-name sanitizer.
 *
 * The name becomes a directory under the app's private `filesDir/workspaces`, so
 * this is the one place where a user's free text touches the filesystem. It has to
 * be impossible to produce `..`, a leading dot (a hidden directory the project list
 * would never show again), a separator, or an unbounded length - while still
 * leaving ordinary names, including non-ASCII letters, readable.
 */
class ProjectStoreSanitizeTest {

    @Test
    fun ordinaryNamesAreUnchanged() {
        assertEquals("mobile", ProjectStore.sanitize("mobile"))
        assertEquals("already-fine_1.2", ProjectStore.sanitize("already-fine_1.2"))
        assertEquals("My-App", ProjectStore.sanitize("My App!"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("spaced", ProjectStore.sanitize("  spaced  "))
        assertEquals("", ProjectStore.sanitize("   "))
        assertEquals("", ProjectStore.sanitize(""))
    }

    @Test
    fun separatorsBecomeDashesAndRunsCollapse() {
        assertEquals("a-b-c-d", ProjectStore.sanitize("a/b\\c:d"))
        assertEquals("collapse-spaces", ProjectStore.sanitize("collapse   spaces"))
        assertEquals("launch", ProjectStore.sanitize("🚀 launch"))
    }

    @Test
    fun pathTraversalCannotSurvive() {
        assertEquals("escape", ProjectStore.sanitize("../escape"))
        assertEquals("escape", ProjectStore.sanitize("..\\..\\escape"))
        assertEquals("", ProjectStore.sanitize(".."))
        assertEquals("", ProjectStore.sanitize("."))
        assertEquals("", ProjectStore.sanitize("/"))
        assertTrue(ProjectStore.sanitize("../../etc/passwd").let { !it.contains("/") && !it.contains("..") })
    }

    @Test
    fun aLeadingOrTrailingDotIsDropped() {
        assertEquals("hidden", ProjectStore.sanitize(".hidden"))
        assertEquals("trailing", ProjectStore.sanitize("trailing..."))
        assertEquals("both", ProjectStore.sanitize(".both."))
    }

    @Test
    fun lengthIsCapped() {
        val long = ProjectStore.sanitize("a".repeat(200))
        assertEquals(40, long.length)
        val words = ProjectStore.sanitize("word ".repeat(100))
        assertTrue("got ${words.length}", words.length <= 40)
        assertTrue(words.isNotEmpty())
    }

    @Test
    fun aCappedNameNeverEndsInADashOrDot() {
        val raw = "a".repeat(39) + " ."
        val out = ProjectStore.sanitize(raw)
        assertTrue(out.length <= 40)
        assertTrue("got '$out'", out.isEmpty() || (!out.endsWith("-") && !out.endsWith(".")))
    }

    @Test
    fun nonAsciiLettersAreKeptBecauseTheyAreLegalDirectoryNames() {
        assertEquals("Проект", ProjectStore.sanitize("Проект"))
        assertEquals("پروژه", ProjectStore.sanitize("پروژه"))
        assertEquals("项目", ProjectStore.sanitize("项目"))
    }

    @Test
    fun theResultIsAlwaysAUsableSinglePathComponent() {
        val samples = listOf(
            "", ".", "..", "/", "\\", "a/b", "~", "\$HOME", "x" + "*".repeat(50), "  ", "a b", "-x-", ".y.",
            "Проект", "🚀 launch", "tab\there", "new\nline", "quote\"mark", "semi;colon", "pipe|char",
        )
        for (raw in samples) {
            val out = ProjectStore.sanitize(raw)
            assertTrue("'$raw' -> '$out' too long", out.length <= 40)
            assertTrue("'$raw' -> '$out' has a separator", out.none { it == '/' || it == '\\' })
            assertTrue("'$raw' -> '$out' starts hidden", !out.startsWith("."))
            assertTrue("'$raw' -> '$out' has a dot-dot", !out.contains(".."))
            assertTrue("'$raw' -> '$out' ends oddly", out.isEmpty() || (!out.endsWith("-") && !out.endsWith(".")))
        }
    }

    @Test
    fun theDefaultProjectNameIsAlreadySanitized() {
        assertEquals(ProjectStore.DEFAULT_NAME, ProjectStore.sanitize(ProjectStore.DEFAULT_NAME))
    }
}
