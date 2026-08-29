package ai.opencode.android.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class PayloadExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Build a minimal ustar tar (regular files + dirs) in memory. */
    private fun makeTar(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeHeader(name: String, size: Long, type: Char) {
            val hdr = ByteArray(512)
            val nameBytes = name.toByteArray()
            System.arraycopy(nameBytes, 0, hdr, 0, minOf(nameBytes.size, 100))
            // mode 0000644 at offset 100, uid/gid 0
            "0000644\u0000".toByteArray().copyInto(hdr, 100)
            "0000000\u0000".toByteArray().copyInto(hdr, 108)
            "0000000\u0000".toByteArray().copyInto(hdr, 116)
            val octal = "%011o\u0000".format(size)
            octal.toByteArray().copyInto(hdr, 124)
            hdr[156] = type.code.toByte()
            "ustar\u0000".toByteArray().copyInto(hdr, 257)
            "00".toByteArray().copyInto(hdr, 263)
            // checksum
            var chksum = 0
            for (b in hdr) chksum += b.toInt() and 0xff
            "%06o\u00000".format(chksum).toByteArray().copyInto(hdr, 148)
            out.write(hdr)
        }
        for ((path, data) in files) {
            writeHeader(path, data.size.toLong(), '0')
            out.write(data)
            val pad = (512 - (data.size % 512)) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024)) // two zero blocks
        return out.toByteArray()
    }

    @Test
    fun unpackTarExtractsFilesAndNestedDirs() {
        val dest = tmp.newFolder("stage")
        val files = mapOf(
            "launcher.js" to "console.log('hi')".toByteArray(),
            "opencode/dist/node/node.js" to "NODE_BUNDLE".toByteArray(),
            "opencode/dist/node/asset.txt" to "asset-data".toByteArray(),
        )
        PayloadExtractor.unpackTar(ByteArrayInputStream(makeTar(files)), dest)
        assertEquals("console.log('hi')", File(dest, "launcher.js").readText())
        assertEquals("NODE_BUNDLE", File(dest, "opencode/dist/node/node.js").readText())
        assertEquals("asset-data", File(dest, "opencode/dist/node/asset.txt").readText())
    }

    @Test
    fun unpackTarRejectsPathEscape() {
        val dest = tmp.newFolder("stage")
        val evil = makeTar(mapOf("../escape.txt" to "x".toByteArray()))
        val ex = runCatching {
            PayloadExtractor.unpackTar(ByteArrayInputStream(evil), dest)
        }.exceptionOrNull()
        assertNotNull("path traversal must be rejected", ex)
        assertTrue(ex is SecurityException || ex?.message?.contains("escape") == true)
    }

    @Test
    fun verifyDetectsMissingMarker() {
        val root = tmp.newFolder("root")
        val manifest = RuntimeManifest(
            payloadVersion = 4, opencodeCommit = "c", opencodeVersion = "v", bunVersion = "b",
            gitVersion = "g", rgVersion = "r", payloadSha256 = "",
            entries = listOf(ManifestEntry("opencode/dist/node/node.js", "x", 1)),
        )
        val reason = PayloadExtractor.verifyExtraction(
            root, File(root, ".extracted"),
            File(root, "opencode/dist/node/node.js"), File(root, "launcher.js"),
            manifest,
        )
        assertTrue(reason!!.contains("marker"))
    }

    @Test
    fun verifyDetectsCorruptedContent() {
        val root = tmp.newFolder("root")
        val data = "real-bundle-content".toByteArray()
        val relPath = "opencode/dist/node/node.js"
        File(root, relPath).apply { parentFile.mkdirs(); writeBytes(data) }
        File(root, "launcher.js").writeText("launcher")
        File(root, ".extracted").writeText(JSONObject().put("payloadVersion", 4).toString())

        val good = ManifestEntry(relPath, sha256(data), data.size.toLong())
        val manifest = RuntimeManifest(
            payloadVersion = 4, opencodeCommit = "c", opencodeVersion = "v", bunVersion = "b",
            gitVersion = "g", rgVersion = "r", payloadSha256 = "",
            entries = listOf(good),
        )
        // valid
        assertEquals(null, PayloadExtractor.verifyExtraction(
            root, File(root, ".extracted"),
            File(root, relPath), File(root, "launcher.js"), manifest,
        ))
        // corrupt the file
        File(root, relPath).writeText("tampered")
        val reason = PayloadExtractor.verifyExtraction(
            root, File(root, ".extracted"),
            File(root, relPath), File(root, "launcher.js"), manifest,
        )
        assertTrue("expected sha/size mismatch, got: $reason", reason!!.contains("mismatch"))

        // wrong payload version in marker
        File(root, relPath).writeBytes(data)
        File(root, ".extracted").writeText(JSONObject().put("payloadVersion", 3).toString())
        val reason2 = PayloadExtractor.verifyExtraction(
            root, File(root, ".extracted"),
            File(root, relPath), File(root, "launcher.js"), manifest,
        )
        assertTrue(reason2!!.contains("payloadVersion"))
    }
}
