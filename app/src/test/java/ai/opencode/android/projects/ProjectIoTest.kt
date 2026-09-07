package ai.opencode.android.projects

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the project filesystem operations. These are the destructive and
 * size-capped routines behind rename/delete/import/export, so they are tested on
 * a real (temporary) filesystem with no Android framework involved.
 */
class ProjectIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(path: File, content: String): File {
        path.parentFile?.mkdirs()
        path.writeText(content)
        return path
    }

    @Test
    fun renameMovesATreeAndKeepsContents() {
        val from = tmp.newFolder("old")
        write(File(from, "src/Main.kt"), "fun main() = println(1)")
        write(File(from, "README.md"), "hi")

        val to = File(tmp.root, "new")
        assertTrue(ProjectIo.renameDir(from, to))
        assertFalse(from.exists())
        assertEquals("fun main() = println(1)", File(to, "src/Main.kt").readText())
        assertEquals("hi", File(to, "README.md").readText())
    }

    @Test
    fun renameRefusesToClobberAnExistingDirectory() {
        val from = tmp.newFolder("a")
        write(File(from, "f.txt"), "a")
        val to = tmp.newFolder("b")
        write(File(to, "f.txt"), "b")

        assertFalse(ProjectIo.renameDir(from, to))
        assertTrue(from.exists())
        assertEquals("b", File(to, "f.txt").readText())
    }

    @Test
    fun deleteTreeRemovesRecursively() {
        val dir = tmp.newFolder("gone")
        write(File(dir, "a/b/c.txt"), "deep")
        ProjectIo.deleteTree(dir)
        assertFalse(dir.exists())
    }

    @Test
    fun copyTreeEnforcesTheByteCapAndLeavesNothingPartial() {
        val src = tmp.newFolder("src")
        write(File(src, "big.bin"), "0123456789") // 10 bytes
        val dst = File(tmp.root, "dst")

        val tooLarge = try {
            ProjectIo.copyTree(src, dst, 5L)
            false
        } catch (t: ProjectIo.TooLarge) {
            true
        }
        assertTrue("a copy over the cap must throw TooLarge", tooLarge)
        // The half-copied file must not linger as if it were complete.
        assertFalse("partial target must not exist", File(dst, "big.bin").exists())

        val ok = ProjectIo.copyTree(src, File(tmp.root, "dst2"), 100L)
        assertTrue(ok > 0)
        assertEquals("0123456789", File(tmp.root, "dst2/big.bin").readText())
    }

    @Test
    fun writeStreamRemovesThePartialFileWhenTheCapIsBreached() {
        val dst = File(tmp.root, "out.bin")
        val tooLarge = try {
            ProjectIo.writeStream("0123456789".byteInputStream(), dst, 5L)
            false
        } catch (t: ProjectIo.TooLarge) {
            true
        }
        assertTrue("a write over the cap must throw TooLarge", tooLarge)
        assertFalse("the partial destination must be removed", dst.exists())
    }

    @Test
    fun copyTreeSkipsSymlinks() {
        val src = tmp.newFolder("src")
        write(File(src, "real.txt"), "real")
        // A symlink pointing outside the tree; copy must not follow it.
        val outside = tmp.newFile("outside.txt").also { it.writeText("secret") }
        val link = File(src, "link.txt")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }

        val dst = File(tmp.root, "dst")
        ProjectIo.copyTree(src, dst, 1_000_000L)
        assertTrue(File(dst, "real.txt").isFile)
        // On filesystems that allow the symlink at all, the copy skips it; on
        // filesystems that refuse it, the runCatching above simply created nothing.
        assertFalse("a symlink must not be copied through", File(dst, "link.txt").exists())
    }

    @Test
    fun zipTreeRoundTripsFiles() {
        val dir = tmp.newFolder("proj")
        write(File(dir, "src/Main.kt"), "fun main() {}")
        File(dir, "empty").mkdirs() // an empty dir must round-trip into the zip
        write(File(dir, "README.md"), "readme")

        val zip = File(tmp.root, "out.zip")
        val total = ProjectIo.zipTree(dir, zip)
        assertTrue(total > 0)
        assertTrue(zip.isFile)

        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("src/"))
            assertTrue(names.contains("src/Main.kt"))
            assertTrue(names.contains("README.md"))
            assertTrue(names.contains("empty/"))
            assertEquals("fun main() {}", zf.getInputStream(zf.getEntry("src/Main.kt")).readBytes().decodeToString())
        }
    }

    @Test
    fun sizeOfSumsAWholeTree() {
        val dir = tmp.newFolder("s")
        write(File(dir, "a.txt"), "1234") // 4
        write(File(dir, "b/c.txt"), "123456") // 6
        assertEquals(10L, ProjectIo.sizeOf(dir))
    }
}
