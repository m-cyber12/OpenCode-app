package ai.opencode.android.harness.sealed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Extraction of the data half of a payload.
 *
 * The three properties that matter are the three that are tested: a hostile zip
 * cannot write outside the prefix, every file with a manifest record is verified
 * from the bytes as they stream past (so no second read and no unhashed file can
 * slip through), and a payload that carried fewer files than the manifest
 * promised is a failure rather than a difference.
 */
class PayloadExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(entries: List<Pair<String, String>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun manifestOf(entries: List<Pair<String, String>>): RuntimeManifest {
        val lines = entries.joinToString("\n") { (name, content) ->
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            RuntimeManifest.digest(bytes) + "\t" + bytes.size + "\t" + name
        }
        return RuntimeManifest.parse(lines)
    }

    @Test
    fun extract_writesEveryEntryAndCountsBytes() {
        val payload = listOf(
            "etc/profile" to "export PATH=/usr/bin\n",
            "share/motd" to "welcome\n"
        )
        val prefix = tmp.newFolder("prefix")

        val result = PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, null, null
        )

        assertTrue(result.summary(), result.ok())
        assertEquals(2, result.files)
        assertEquals("export PATH=/usr/bin\n", File(prefix, "etc/profile").readText())
        assertEquals(
            ("export PATH=/usr/bin\n".toByteArray().size + "welcome\n".toByteArray().size).toLong(),
            result.bytes
        )
        assertEquals(0, result.verified) // no manifest was supplied
    }

    @Test
    fun extract_verifiesWhileWriting() {
        val payload = listOf("etc/profile" to "export PATH=/usr/bin\n")
        val prefix = tmp.newFolder("prefix")

        val result = PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, manifestOf(payload), null
        )

        assertTrue(result.summary(), result.ok())
        assertEquals(1, result.verified)
    }

    @Test
    fun extract_reportsADigestMismatchInsteadOfInstallingIt() {
        val payload = listOf("etc/profile" to "export PATH=/usr/bin\n")
        val prefix = tmp.newFolder("prefix")
        // Same length, different bytes: a size difference is reported before
        // the digest is ever compared, so this must match in length to reach it.
        val claimed = manifestOf(listOf("etc/profile" to "export PATH=/usr/ban\n"))

        val result = PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, claimed, null
        )

        assertFalse(result.ok())
        assertTrue(result.summary(), result.problems.any { it.startsWith("digest differs: etc/profile") })
    }

    @Test
    fun extract_reportsASizeMismatch() {
        val payload = listOf("etc/profile" to "export PATH=/usr/bin\n")
        val prefix = tmp.newFolder("prefix")
        val claimed = manifestOf(listOf("etc/profile" to "export PATH=/usr/bin"))

        val result = PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, claimed, null
        )

        assertTrue(result.summary(), result.problems.any { it.startsWith("size differs: etc/profile") })
    }

    @Test
    fun extract_reportsATruncatedPayload() {
        val payload = listOf("etc/profile" to "export PATH=/usr/bin\n")
        val claimed = manifestOf(
            listOf(
                "etc/profile" to "export PATH=/usr/bin\n",
                "share/motd" to "welcome\n"
            )
        )
        val prefix = tmp.newFolder("prefix")

        val result = PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, claimed, null
        )

        assertFalse(result.ok())
        assertTrue(result.summary(), result.problems.any { it.contains("manifest lists 2 files, payload carried 1") })
    }

    @Test
    fun extract_refusesToEscapeThePrefix() {
        val payload = listOf(
            "../escaped" to "outside the prefix\n",
            "/absolute" to "also outside\n",
            "etc/kept" to "inside\n"
        )
        val prefix = tmp.newFolder("prefix")

        val result = PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, null, null
        )

        assertEquals(1, result.files)
        assertTrue(File(prefix, "etc/kept").isFile)
        assertFalse(File(tmp.root, "escaped").exists())
        assertTrue(result.problems.any { it.contains("unsafe payload path: ../escaped") })
        assertTrue(result.problems.any { it.contains("unsafe payload path: /absolute") })
    }

    @Test
    fun extract_isIdempotent() {
        val payload = listOf("etc/profile" to "export PATH=/usr/bin\n")
        val prefix = tmp.newFolder("prefix")

        PayloadExtractor.extract(ByteArrayInputStream(zip(payload)), prefix, manifestOf(payload), null)
        File(prefix, "etc/profile").writeText("the user edited this\n")
        val second = PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, manifestOf(payload), null
        )

        assertTrue(second.summary(), second.ok())
        assertEquals("export PATH=/usr/bin\n", File(prefix, "etc/profile").readText())
    }

    @Test
    fun extract_createsDirectoriesAndEmptyDirectoryEntries() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("share/empty/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("share/empty/README"))
            zip.write("nested\n".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        val prefix = tmp.newFolder("prefix")

        val result = PayloadExtractor.extract(ByteArrayInputStream(bytes.toByteArray()), prefix, null, null)

        assertTrue(result.summary(), result.ok())
        assertTrue(File(prefix, "share/empty/README").isFile)
    }

    @Test
    fun extract_reportsProgressPerFile() {
        val payload = listOf("a" to "1", "b" to "2", "c" to "3")
        val prefix = tmp.newFolder("prefix")
        val seen = ArrayList<String>()

        PayloadExtractor.extract(
            ByteArrayInputStream(zip(payload)), prefix, null
        ) { path, _ -> seen.add(path) }

        assertEquals(listOf("a", "b", "c"), seen)
    }
}
