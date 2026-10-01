package ai.opencode.android.preview

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Sandbox static server, proven over real loopback sockets: it serves the
 * project and ONLY the project, falls through to index.html, names types
 * correctly, and refuses to escape its root. All on 127.0.0.1 - the JVM test
 * needs no network.
 */
class PreviewServerTest {

    private lateinit var root: File
    private lateinit var server: PreviewServer

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("preview").toFile()
        File(root, "index.html").writeText("<html><body>home</body></html>")
        File(root, "style.css").writeText("body{color:red}")
        File(root, "app.mjs").writeText("export const x = 1")
        File(root, "sub").mkdirs()
        File(root, "sub/index.html").writeText("<p>sub</p>")
        File(root.parentFile, "outside-secret.txt").writeText("secret")
        server = PreviewServer(root)
        server.start(preferredPort = 0)
    }

    @After
    fun tearDown() {
        server.stop()
        File(root.parentFile, "outside-secret.txt").delete()
        root.deleteRecursively()
    }

    private fun get(path: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        val type = conn.contentType ?: ""
        conn.disconnect()
        return code to "$type|$body"
    }

    @Test
    fun servesTheRootIndexForSlash() {
        val (code, payload) = get("/")
        assertEquals(200, code)
        assertTrue(payload.contains("text/html"))
        assertTrue(payload.contains("home"))
    }

    @Test
    fun servesFilesWithTheirOwnMimeTypes() {
        assertTrue(get("/style.css").second.startsWith("text/css"))
        assertTrue(get("/app.mjs").second.startsWith("text/javascript"))
    }

    @Test
    fun aDirectoryFallsThroughToItsIndex() {
        val (code, payload) = get("/sub/")
        assertEquals(200, code)
        assertTrue(payload.contains("sub"))
    }

    @Test
    fun missingFilesAre404NotCrashes() {
        assertEquals(404, get("/nope.html").first)
    }

    @Test
    fun pathTraversalIsConfinedToTheRoot() {
        // The resolver itself refuses to leave the root...
        assertNull(server.resolve("/../outside-secret.txt"))
        assertNull(server.resolve("/%2e%2e/outside-secret.txt"))
        // ...and over the wire the same request cannot leak the file.
        val (_, payload) = get("/../outside-secret.txt")
        assertFalse(payload.contains("secret"))
    }

    @Test
    fun queryStringsDoNotConfuseTheLookup() {
        assertEquals(200, get("/index.html?v=1").first)
    }

    @Test
    fun stopReallyStops() {
        server.stop()
        assertFalse(server.running)
    }

    @Test
    fun bindsTheIpv4LoopbackTheUrlActuallyDials() {
        // The owner's phone refused http://127.0.0.1:8642 because Android's
        // getLoopbackAddress() handed ::1 to the bind while the URL dialled
        // IPv4. The bound host is pinned here so that regression is loud.
        assertEquals("127.0.0.1", server.boundHost)
    }

    @Test
    fun mimeMapCoversTheWebBasics() {
        assertEquals("text/html; charset=utf-8", PreviewServer.mimeOf("a.html"))
        assertEquals("image/svg+xml", PreviewServer.mimeOf("logo.svg"))
        assertEquals("application/wasm", PreviewServer.mimeOf("m.wasm"))
        assertEquals("application/octet-stream", PreviewServer.mimeOf("blob.bin"))
    }
}
