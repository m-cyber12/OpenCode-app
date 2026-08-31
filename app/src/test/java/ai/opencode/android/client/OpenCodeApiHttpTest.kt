package ai.opencode.android.client

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round-trips the client against a local stand-in HTTP server so the *wire
 * shape* is verified (method, path, query, body, auth header) without a device.
 * The on-device instrumented test then proves the same code against the real
 * OpenCode server; these two together are what backs "the app is a client of
 * the upstream API" - nothing here re-implements server behavior, it only
 * records what the client sends.
 *
 * The stand-in is a hand-rolled HTTP/1.1 responder over a raw [ServerSocket]
 * rather than `com.sun.net.httpserver.HttpServer` on purpose: unit tests here
 * compile against `android.jar`, which has no `com.sun.*` (the first CI compile of
 * this file failed on exactly that), and `ServerSocket` also lets the test assert
 * the exact request framing the client produces - including the zero-length
 * bodies that `HttpURLConnection` only sends when told to, which is the difference
 * between OpenCode accepting `POST /session/:id/shell` and hanging on it.
 */
class OpenCodeApiHttpTest {

    data class Record(
        val method: String,
        val path: String,
        val query: String,
        val body: String,
        val auth: String,
        val accept: String,
        val contentLengthHeader: String?,
    )

    /** `streamThenClose` mimics `GET /global/event`: no length, body until EOF. */
    private class Resp(val status: Int, val body: String, val streamThenClose: Boolean = false)

    private val seen = ConcurrentLinkedQueue<Record>()
    @Volatile private var lastExchange: Record? = null
    @Volatile private var responseBody: String = "{}"
    @Volatile private var responseStatus: Int = 200
    private var fake: FakeHttp? = null

    private fun respondTo(rec: Record): Resp = Resp(responseStatus, responseBody)

    @Before
    fun up() {
        seen.clear()
        lastExchange = null
        responseBody = "{}"
        responseStatus = 200
        fake = FakeHttp(
            onRequest = { rec -> seen.add(rec); lastExchange = rec },
            responder = { rec -> respondTo(rec) },
        )
    }

    @After
    fun down() {
        fake?.close()
        fake = null
    }

    private fun api() = OpenCodeApi(
        baseUrl = "http://127.0.0.1:${fake!!.port}",
        username = "opencode",
        password = "secret-pw",
        directory = "/data/user/0/ai.opencode.android/files/workspaces/proj",
    )

    // ---- wire shape ---------------------------------------------------------

    @Test
    fun healthIsGlobalAndCarriesBasicAuth() {
        responseBody = """{"healthy":true,"version":"1.18.23-android"}"""
        val h = api().health()
        assertTrue(h.getBoolean("healthy"))
        val r = lastExchange!!
        assertEquals("GET", r.method)
        assertEquals("/global/health", r.path)
        assertEquals("global routes must not be directory-scoped", "", r.query)
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:secret-pw".toByteArray())
        assertEquals(expected, r.auth)
    }

    @Test
    fun sessionCreateIsDirectoryScopedAndPassesTitle() {
        responseBody = """{"id":"ses_abc","title":"t1","directory":"/x"}"""
        val s = api().createSession("t1")
        assertEquals("ses_abc", s.id)
        val r = lastExchange!!
        assertEquals("POST", r.method)
        assertEquals("/session", r.path)
        assertTrue(r.query, r.query.contains("directory=%2Fdata%2Fuser%2F0"))
        assertEquals(JSONObject(r.body).getString("title"), "t1")
    }

    /** A body-carrying POST must say how long the body is, or the server waits. */
    @Test
    fun promptAsyncSendsUpstreamPartShape() {
        responseStatus = 204
        responseBody = ""
        val status = api().promptAsync(
            "ses_1",
            "hello",
            OpenCodeApi.ModelRef("opencode", "big-pickle"),
        )
        assertEquals(204, status)
        val r = lastExchange!!
        assertEquals("/session/ses_1/prompt_async", r.path)
        val body = JSONObject(r.body)
        assertEquals("text", body.getJSONArray("parts").getJSONObject(0).getString("type"))
        assertEquals("hello", body.getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("big-pickle", body.getJSONObject("model").getString("modelID"))
    }

    @Test
    fun permissionReplyUsesTheUpstreamRoute() {
        responseBody = "true"
        api().replyPermission("per_9", "once")
        val r = lastExchange!!
        assertEquals("POST", r.method)
        assertEquals("/permission/per_9/reply", r.path)
        assertEquals("once", JSONObject(r.body).getString("reply"))
    }

    @Test
    fun authProvisioningUsesProviderIdRoute() {
        responseBody = "true"
        api().setProviderAuth("openrouter", "sk-or-TEST")
        assertEquals("/auth/openrouter", lastExchange!!.path)
        assertEquals("PUT", lastExchange!!.method)
        val body = JSONObject(lastExchange!!.body)
        assertEquals("api", body.getString("type"))
        assertEquals("sk-or-TEST", body.getString("key"))
    }

    @Test
    fun mcpAddPostsNameAndConfig() {
        responseBody = """{"status":"connected"}"""
        val out = api().addMcp("gates", JSONObject("""{"type":"local","command":["bun","x.js"]}"""))
        assertEquals("connected", out.getString("status"))
        assertEquals("/mcp", lastExchange!!.path)
        assertEquals("gates", JSONObject(lastExchange!!.body).getString("name"))
    }

    @Test
    fun globalConfigPatchIsTheDurablePath() {
        responseBody = """{"info":{},"changed":true}"""
        api().patchGlobalConfig(JSONObject("""{"permission":{"bash":"ask"}}"""))
        assertEquals("PATCH", lastExchange!!.method)
        assertEquals("/global/config", lastExchange!!.path)
        assertEquals("", lastExchange!!.query)
    }

    /**
     * DELETE with no body: HttpURLConnection would otherwise omit Content-Length
     * entirely, and a server that keeps the connection open then blocks. The client
     * sends `Content-Length: 0` (framing choice, not an API change); this pins it.
     */
    @Test
    fun bodylessRequestsDeclareZeroLength() {
        responseBody = "true"
        api().deleteProviderAuth("openrouter")
        val r = lastExchange!!
        assertEquals("DELETE", r.method)
        assertEquals("/auth/openrouter", r.path)
        assertEquals("0", r.contentLengthHeader)
        assertEquals("", r.body)
    }

    @Test
    fun non2xxBecomesTypedError() {
        responseStatus = 404
        responseBody = """{"name":"UnknownError","data":{"message":"nope"}}"""
        val err = runCatching { api().getSession("ses_missing") }.exceptionOrNull()
        assertTrue(err is OpenCodeApi.ApiException)
        assertEquals(404, (err as OpenCodeApi.ApiException).status)
        assertTrue(err.body.contains("UnknownError"))
    }

    /** The event-stream client, end to end, against a real socket. */
    @Test
    fun eventStreamParsesFramesAndReconnects() {
        val hits = AtomicInteger()
        val frames = "event: message\n" +
            "data: {\"payload\":{\"type\":\"session.updated\",\"properties\":{\"sessionID\":\"s1\"}}}\n\n" +
            "data: {\"payload\":{\"type\":\"sync\",\"syncEvent\":{\"type\":\"message.part.updated.1\"," +
            "\"data\":{\"sessionID\":\"s1\"}}}}\n\n"
        val sse = FakeHttp(
            onRequest = { hits.incrementAndGet() },
            responder = { Resp(200, frames, streamThenClose = true) },
        )
        val got = CountDownLatch(2)
        val types = ConcurrentLinkedQueue<String>()
        val stream = OpenCodeEventStream(
            baseUrl = "http://127.0.0.1:${sse.port}",
            username = "opencode",
            password = "secret-pw",
            directory = null,
            onEvent = { ev ->
                types.add(ev.type)
                got.countDown()
            },
            heartbeatTimeoutMs = 1_500,
        )
        try {
            stream.start()
            assertTrue("two events must arrive (got $types)", got.await(20, TimeUnit.SECONDS))
            // The stream is a subscription, not a one-shot: after the server closed
            // the body the client must reconnect on its own.
            val deadline = System.currentTimeMillis() + 10_000
            while (hits.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(150)
            assertTrue("stream should have reconnected (hits=${hits.get()})", hits.get() >= 2)
            assertTrue(types.toString(), types.contains("session.updated"))
            assertTrue(types.toString(), types.contains("message.part.updated"))
        } finally {
            stream.stop()
            sse.close()
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * Minimal HTTP/1.1 responder: parses one request per connection (the client is
     * built with `Connection: close` semantics by this server's own headers), hands
     * it to [responder], writes the reply, closes. No keep-alive, no chunking - the
     * point is the request shape, not being a general web server.
     */
    private class FakeHttp(
        val onRequest: (Record) -> Unit,
        val responder: (Record) -> Resp,
    ) : AutoCloseable {
        private val ss = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0), 32) }
        val port: Int get() = ss.localPort
        @Volatile private var running = true

        init {
            Thread({
                while (running) {
                    val s = try {
                        ss.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    Thread({ handle(s) }, "fake-http-conn").apply { isDaemon = true; start() }
                }
            }, "fake-http-accept").apply { isDaemon = true; start() }
        }

        private fun handle(sock: Socket) {
            try {
                sock.use { s ->
                    val input: InputStream = s.getInputStream()
                    val reader = BufferedReader(InputStreamReader(input, UTF_8))
                    val requestLine = reader.readLine() ?: return
                    val bits = requestLine.split(" ")
                    val method = bits.getOrElse(0) { "" }
                    val target = bits.getOrElse(1) { "" }
                    val q = target.indexOf('?')
                    val path = if (q >= 0) target.substring(0, q) else target
                    val query = if (q >= 0) target.substring(q + 1) else ""
                    var contentLength = -1
                    var clHeader: String? = null
                    var auth = ""
                    var accept = ""
                    while (true) {
                        val h = reader.readLine() ?: break
                        if (h.isEmpty()) break
                        val ci = h.indexOf(':')
                        if (ci <= 0) continue
                        val name = h.substring(0, ci).trim().lowercase()
                        val value = h.substring(ci + 1).trim()
                        when (name) {
                            "content-length" -> { clHeader = value; contentLength = value.toIntOrNull() ?: -1 }
                            "authorization" -> auth = value
                            "accept" -> accept = value
                        }
                    }
                    val body = if (contentLength > 0) {
                        val buf = ByteArray(contentLength)
                        var off = 0
                        while (off < contentLength) {
                            val n = input.read(buf, off, contentLength - off)
                            if (n < 0) break
                            off += n
                        }
                        String(buf, 0, off, UTF_8)
                    } else {
                        ""
                    }
                    val rec = Record(method, path, query, body, auth, accept, clHeader)
                    onRequest(rec)
                    val resp = responder(rec)
                    val reason = when (resp.status) {
                        204 -> "No Content"
                        400 -> "Bad Request"
                        401 -> "Unauthorized"
                        404 -> "Not Found"
                        else -> "OK"
                    }
                    val out = s.getOutputStream()
                    if (resp.streamThenClose) {
                        out.write(
                            ("HTTP/1.1 ${resp.status} $reason\r\nContent-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(UTF_8),
                        )
                        out.write(resp.body.toByteArray(UTF_8))
                    } else {
                        val bytes = resp.body.toByteArray(UTF_8)
                        out.write(
                            ("HTTP/1.1 ${resp.status} $reason\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(UTF_8),
                        )
                        if (bytes.isNotEmpty()) out.write(bytes)
                    }
                    out.flush()
                }
            } catch (_: Throwable) {
                // A truncated or aborted request is a test-fixture detail; the
                // assertions on what was recorded still stand.
            }
        }

        override fun close() {
            running = false
            runCatching { ss.close() }
        }
    }
}
