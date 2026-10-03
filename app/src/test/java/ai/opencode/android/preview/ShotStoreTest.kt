package ai.opencode.android.preview

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v9.12: the organized screenshot gallery's naming contract. */
class ShotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun slugsAreFilesystemSafeAndReadable() {
        assertEquals("after-login-fix", ShotStore.slug("After Login Fix"))
        assertEquals("stage-2-rtl", ShotStore.slug("stage 2: RTL!!"))
        assertEquals("shot", ShotStore.slug("؟؟؟"))
        assertEquals("shot", ShotStore.slug(""))
        assertEquals("a", ShotStore.slug("--a--"))
    }

    @Test
    fun longNamesAreBoundedButNeverEndInADash() {
        val s = ShotStore.slug("x".repeat(200) + " end")
        assertEquals(48, s.length)
        assertEquals(false, s.endsWith("-"))
    }

    @Test
    fun capturesAreNumberedInOrderInsideProjectRootScreenshots() {
        val project = tmp.newFolder("project")
        val first = ShotStore.nextFile(project, "login page")
        // v9.13 (owner): the gallery sits NEXT TO the project's files.
        assertEquals("screenshots", first.parentFile?.name)
        assertEquals(project, first.parentFile?.parentFile)
        assertEquals("001-login-page.png", first.name)
        first.writeBytes(byteArrayOf(1))
        val second = ShotStore.nextFile(project, "dark mode")
        assertEquals("002-dark-mode.png", second.name)
        second.writeBytes(byteArrayOf(1))
        assertEquals("003-final.png", ShotStore.nextFile(project, "final").name)
    }

    @Test
    fun nonPngClutterDoesNotBreakTheSequence() {
        val project = tmp.newFolder("project")
        val shots = java.io.File(project, ShotStore.SHOTS_DIR)
        shots.mkdirs()
        java.io.File(shots, "notes.txt").writeText("x")
        assertEquals("001-a.png", ShotStore.nextFile(project, "a").name)
    }
}
