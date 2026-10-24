package ai.opencode.android.harness.sealed

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The payload manifest: the thing that turns "the payload extracted" into "the
 * payload is the payload".
 *
 * It is build output read by three different languages over the life of this
 * project, so the format's tolerance (skip a bad line, never crash an install)
 * and its safety rules (no absolute paths, no `..`) are both tested here rather
 * than assumed.
 */
class RuntimeManifestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(path: String, content: String): String {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        return RuntimeManifest.digest(bytes) + "\t" + bytes.size + "\t" + path
    }

    @Test
    fun parse_readsOneRecordPerLine() {
        val manifest = RuntimeManifest.parse(
            "# payload manifest\n" +
                "\n" +
                line("etc/profile", "export PATH=/usr/bin\n") + "\n" +
                line("share/termux/version", "1.2.3\n") + "\n"
        )
        assertEquals(2, manifest.size())
        assertEquals("export PATH=/usr/bin\n".toByteArray().size.toLong(), manifest["etc/profile"]?.size)
        assertEquals("share/termux/version", manifest.entries().keys.last())
    }

    @Test
    fun parse_skipsMalformedLinesAndComments() {
        val good = line("etc/hosts", "127.0.0.1 localhost\n")
        val manifest = RuntimeManifest.parse(
            "not a record\n" +
                "$good\n" +
                "deadbeef\t3\tetc/broken\n" + // digest is not 64 hex chars
                "0123456789012345678901234567890123456789012345678901234567890123\tabc\tetc/size\n"
        )
        assertEquals(1, manifest.size())
        assertEquals("127.0.0.1 localhost\n".toByteArray().size.toLong(), manifest["etc/hosts"]?.size)
    }

    @Test
    fun parse_keepsTheLastRecordForADuplicatedPath() {
        val manifest = RuntimeManifest.parse(
            line("bin/tool", "old\n") + "\n" + line("bin/tool", "newer\n") + "\n"
        )
        assertEquals(1, manifest.size())
        assertEquals("newer\n".toByteArray().size.toLong(), manifest["bin/tool"]?.size)
    }

    @Test
    fun parse_rejectsUnsafePaths() {
        val manifest = RuntimeManifest.parse(
            "012345678901234567890123456789012345678901234567890123456789012a\t1\t/etc/passwd\n" +
                "012345678901234567890123456789012345678901234567890123456789012b\t1\t../escape\n" +
                "012345678901234567890123456789012345678901234567890123456789012c\t1\t./etc/ok\n"
        )
        assertEquals(listOf("etc/ok"), manifest.entries().keys.toList())
    }

    @Test
    fun normalize_stripsDotSlashAndRefusesEscapes() {
        assertEquals("bin/git", RuntimeManifest.normalize("./bin/git"))
        assertEquals("bin/git", RuntimeManifest.normalize("  bin/git  "))
        assertNull(RuntimeManifest.normalize("/bin/git"))
        assertNull(RuntimeManifest.normalize("bin/../git"))
        assertNull(RuntimeManifest.normalize(""))
        assertNull(RuntimeManifest.normalize(null))
    }

    @Test
    fun read_absentOrUnreadableIsAnEmptyManifest() {
        assertTrue(RuntimeManifest.read(File(tmp.root, "nope.txt")).isEmpty())
        assertTrue(RuntimeManifest.read(null as File?).isEmpty())
        assertTrue(RuntimeManifest.read(null as ByteArrayInputStream?).isEmpty())
    }

    @Test
    fun digest_isLowercaseSha256Hex() {
        // Known vector: SHA-256 of the empty string.
        val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(empty, RuntimeManifest.digest(ByteArray(0)))
        assertEquals(64, RuntimeManifest.digest("anything".toByteArray()).length)
        assertEquals(
            RuntimeManifest.digest("anything".toByteArray()),
            RuntimeManifest.digest("anything".toByteArray())
        )
    }

    @Test
    fun verify_acceptsAPrefixThatMatches() {
        val prefix = tmp.newFolder("prefix")
        File(prefix, "etc").mkdirs()
        File(prefix, "etc/profile").writeText("export PATH=/usr/bin\n")
        val manifest = RuntimeManifest.parse(line("etc/profile", "export PATH=/usr/bin\n"))

        assertTrue(manifest.verify(prefix).isEmpty())
    }

    @Test
    fun verify_reportsMissingSizeAndContentChanges() {
        val prefix = tmp.newFolder("prefix")
        val manifest = RuntimeManifest.parse(
            line("etc/gone", "never written\n") + "\n" +
                line("etc/short", "the original text\n") + "\n" +
                line("etc/edited", "the original text\n") + "\n"
        )
        File(prefix, "etc/short").writeText("short\n")
        File(prefix, "etc/edited").writeText("edited by the user\n")

        val problems = manifest.verify(prefix)
        assertTrue(problems.toString(), problems.any { it.startsWith("missing: etc/gone") })
        assertTrue(problems.toString(), problems.any { it.startsWith("size changed: etc/short") })
        assertTrue(problems.toString(), problems.any { it.startsWith("digest differs: etc/edited") })
    }

    @Test
    fun verify_withoutAPrefixIsAProblemNotACrash() {
        assertEquals(listOf("no prefix"), RuntimeManifest.empty().verify(null))
    }
}
