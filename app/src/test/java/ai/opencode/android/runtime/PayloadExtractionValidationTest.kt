package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Checksum verification and extraction validation (Phase 8 test-matrix items:
 * "extraction" and "checksum verification").
 *
 * [PayloadExtractor.verifyExtraction] is the pure half of the extractor's
 * contract: it decides, before anything is executed, whether an already
 * extracted payload can be trusted (marker version + every file present with
 * the right size and SHA-256). A null answer is the ONLY answer that lets the
 * supervisor start the server from that tree; anything else forces a clean
 * re-extraction from the APK. These tests pin that decision matrix on the JVM;
 * the device side proves the recovery loop end to end (H5 lineage, P8-CORRUPT).
 */
class PayloadExtractionValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A manifest describing exactly the files [write] puts on disk. */
    private fun writePayloadAndManifest(
        root: File,
        marker: File,
        files: Map<String, String>,
        payloadVersion: Int = 5,
    ): RuntimeManifest {
        val entries = ArrayList<ManifestEntry>()
        for ((path, content) in files) {
            val f = File(root, path)
            f.parentFile.mkdirs()
            f.writeText(content)
            entries.add(ManifestEntry(path, sha256(content.toByteArray()), content.length.toLong()))
        }
        val manifest = RuntimeManifest(
            payloadVersion = payloadVersion,
            opencodeCommit = RuntimeVersion.OPENCODE_COMMIT,
            opencodeVersion = RuntimeVersion.OPENCODE_VERSION,
            bunVersion = RuntimeVersion.BUN_VERSION,
            gitVersion = RuntimeVersion.GIT_VERSION,
            rgVersion = RuntimeVersion.RIPGREP_VERSION,
            payloadSha256 = "p",
            entries = entries,
        )
        marker.parentFile.mkdirs()
        marker.writeText(
            org.json.JSONObject()
                .put("payloadVersion", payloadVersion)
                .put("opencodeCommit", RuntimeVersion.OPENCODE_COMMIT)
                .toString(),
        )
        return manifest
    }

    private fun fixture(): Triple<File, File, RuntimeManifest> {
        val root = tmp.newFolder("files")
        val marker = File(root, "runtime/.extracted")
        val manifest = writePayloadAndManifest(
            root,
            marker,
            mapOf(
                "launcher.js" to "console.log('launcher')",
                "opencode/dist/node/node.js" to "console.log('server')",
                "node_modules/jsonc-parser/index.js" to "module.exports = {}",
            ),
        )
        return Triple(root, marker, manifest)
    }

    // ---- the happy path -----------------------------------------------------

    @Test
    fun aCompleteValidExtractionReturnsNull() {
        val (root, marker, manifest) = fixture()
        assertNull(
            PayloadExtractor.verifyExtraction(
                root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
            ),
        )
    }

    // ---- every failure mode the extractor must catch -------------------------

    @Test
    fun aMissingMarkerMeansFirstRunOrWipe() {
        val (root, marker, manifest) = fixture()
        marker.delete()
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("marker"))
    }

    @Test
    fun anUnreadableMarkerIsAReExtraction() {
        val (root, marker, manifest) = fixture()
        marker.writeText("this is not json")
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("unreadable"))
    }

    @Test
    fun aStaleMarkerVersionIsAReExtraction() {
        val (root, marker, manifest) = fixture()
        // The app upgraded; the on-disk payload is from the older payload
        // version. It must not be trusted even though every checksum matches.
        marker.writeText(org.json.JSONObject().put("payloadVersion", manifest.payloadVersion - 1).toString())
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("payloadVersion"))
    }

    @Test
    fun aMissingServerBundleIsAReExtraction() {
        val (root, marker, manifest) = fixture()
        File(root, "opencode/dist/node/node.js").delete()
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("server bundle"))
    }

    @Test
    fun aMissingLauncherIsAReExtraction() {
        val (root, marker, manifest) = fixture()
        File(root, "launcher.js").delete()
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("launcher"))
    }

    @Test
    fun aMissingEntryFileIsAReExtraction() {
        val (root, marker, manifest) = fixture()
        File(root, "node_modules/jsonc-parser/index.js").delete()
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("missing"))
    }

    @Test
    fun aSizeMismatchIsAReExtraction() {
        val (root, marker, manifest) = fixture()
        // Truncation (process killed mid-write) changes the size before it
        // changes the hash in any detectable ordering; both are caught.
        File(root, "launcher.js").writeText("con")
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("size mismatch"))
    }

    @Test
    fun aShaMismatchIsAReExtraction() {
        val (root, marker, manifest) = fixture()
        // Same length, different content: only the hash can catch this.
        val original = File(root, "launcher.js").readText()
        val mutated = original.dropLast(1) + (if (original.last() == 'x') 'y' else 'x')
        File(root, "launcher.js").writeText(mutated)
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("sha256 mismatch"))
    }

    @Test
    fun anUnsafeManifestEntryIsAReExtraction() {
        val (root, marker, _) = fixture()
        val manifest = RuntimeManifest(
            payloadVersion = 5,
            opencodeCommit = RuntimeVersion.OPENCODE_COMMIT,
            opencodeVersion = RuntimeVersion.OPENCODE_VERSION,
            bunVersion = RuntimeVersion.BUN_VERSION,
            gitVersion = RuntimeVersion.GIT_VERSION,
            rgVersion = RuntimeVersion.RIPGREP_VERSION,
            payloadSha256 = "p",
            entries = listOf(ManifestEntry("../evil", "a".repeat(64), 1)),
        )
        val reason = PayloadExtractor.verifyExtraction(
            root, marker, File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"), manifest,
        )
        assertNotNull(reason)
        assertTrue("reason: $reason", reason!!.contains("unsafe"))
    }

    // ---- the tar reader -------------------------------------------------------

    /** Minimal ustar writer (mirrors the helper in PayloadExtractorTest). */
    private fun makeTar(files: Map<String, ByteArray>, dirs: List<String> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeHeader(name: String, size: Long, type: Char) {
            val hdr = ByteArray(512)
            name.toByteArray().copyInto(hdr, 0, minOf(name.length, 100))
            "0000644\u0000".toByteArray().copyInto(hdr, 100)
            "0000000\u0000".toByteArray().copyInto(hdr, 108)
            "0000000\u0000".toByteArray().copyInto(hdr, 116)
            ("%011o\u0000".format(size)).toByteArray().copyInto(hdr, 124)
            hdr[156] = type.code.toByte()
            "ustar\u0000".toByteArray().copyInto(hdr, 257)
            "00".toByteArray().copyInto(hdr, 263)
            var chksum = 0
            for (b in hdr) chksum += b.toInt() and 0xff
            ("%06o\u00000".format(chksum)).toByteArray().copyInto(hdr, 148)
            out.write(hdr)
        }
        for (d in dirs) writeHeader(d, 0, '5')
        for ((path, data) in files) {
            writeHeader(path, data.size.toLong(), '0')
            out.write(data)
            val pad = (512 - (data.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024)) // two zero blocks: end of archive
        return out.toByteArray()
    }

    @Test
    fun unpackRestoresFilesAndDirectories() {
        val dest = tmp.newFolder("dest")
        val tar = makeTar(
            files = mapOf("a/b.txt" to "hello".toByteArray(), "c.txt" to "world".toByteArray()),
            dirs = listOf("a"),
        )
        PayloadExtractor.unpackTar(ByteArrayInputStream(tar), dest)
        assertEquals("hello", File(dest, "a/b.txt").readText())
        assertEquals("world", File(dest, "c.txt").readText())
        assertTrue(File(dest, "a").isDirectory)
    }

    @Test
    fun aTarEntryThatEscapesTheRootIsRejected() {
        val dest = tmp.newFolder("dest2")
        val tar = makeTar(files = mapOf("../evil.txt" to "bad".toByteArray()))
        try {
            PayloadExtractor.unpackTar(ByteArrayInputStream(tar), dest)
            throw AssertionError("path escape must be rejected")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("escapes"))
        }
        assertTrue(!File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun aTruncatedTarEntryIsRejected() {
        val dest = tmp.newFolder("dest3")
        val tar = makeTar(files = mapOf("big.bin" to ByteArray(4096) { it.toByte() }))
        // Chop the file data: the header says 4096 bytes, the stream has less.
        val cut = tar.copyOf(tar.size - 2048 - 512)
        try {
            PayloadExtractor.unpackTar(ByteArrayInputStream(cut), dest)
            throw AssertionError("truncated entry must be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }

    @Test
    fun sha256MatchesTheJdkDigest() {
        val f = File(tmp.root, "h.txt")
        f.writeText("checksum fixture")
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("checksum fixture".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, PayloadExtractor.sha256(f))
    }
}
