package ai.opencode.android.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round-trips the client against a local stand-in HTTP server so the *wire
 * shape* is verified (method, path, query, body, auth header) without a device.
 * The on-device instrumented test then proves the same code against the real
 * OpenCode server; these two together are what backs "the app is a client of
 * the upstream API" — nothing here re-implements server behavior, it only
 * records what the client sends.
 */
class OpenCodeApiHttpTest {

    private lateinit var server: HttpServer
    private val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var lastExchange: Record? = null

    data class Record(
        val method: String,
        val path: String,
        val query: String,
        val body: String,
        val auth: String,
        val accept: String,
    )

    @Volatile private var responseBody: String = "{}"
    @Volatile private var responseStatus: Int = 200

    @Before
    fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            lastExchange = Record(
                method = ex.requestMethod,
                path = ex.requestPath.path,
                query = ex.requestPath.query ?: "",
                body = body,
                auth = ex.requestHeaders.getFirst("Authorization") ?: "",
                accept = ex.requestHeaders.getFirst("Accept") ?: "",
            )
            seen.add("${ex.requestMethod} ${ex.requestPath}")
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(responseStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun down() {
        server.stop(0)
    }

    private fun api() = OpenCodeApi(
        baseUrl = "http://127.0.0.1:${server.address.port}",
        username = "opencode",
        password = "secret-pw",
        directory = "/data/user/0/ai.opencode.android/files/workspaces/proj",
    )

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
        val sse = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = sse.address.port
        var hits = 0
        sse.createContext("/global/event") { ex ->
            hits++
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            out.write("event: message\n".toByteArray())
            out.write("data: {\"payload\":{\"type\":\"session.updated\",\"properties\":{\"sessionID\":\"s1\"}}}\n\n".toByteArray())
            out.write("data: {\"payload\":{\"type\":\"sync\",\"syncEvent\":{\"type\":\"message.part.updated.1\",\"data\":{\"sessionID\":\"s1\"}}}}\n".toByteArray())
            out.write("\n".toByteArray())
            out.flush()
            out.close()
        }
        sse.start()
        val got = CountDownLatch(2)
        val types = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val stream = OpenCodeEventStream(
            baseUrl = "http://127.0.0.1:$port",
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
            assertTrue("two events must arrive (got $types)", got.await(15, TimeUnit.SECONDS))
            // The stream is a subscription, not a one-shot: after the server
            // closed the body the client must reconnect on its own.
            val deadline = System.currentTimeMillis() + 6_000
            while (hits < 2 && System.currentTimeMillis() < deadline) Thread.sleep(150)
            assertTrue("stream should have reconnected (hits=$hits)", hits >= 2)
            assertTrue(types.toString(), types.contains("session.updated"))
            assertTrue(types.toString(), types.contains("message.part.updated"))
        } finally {
            stream.stop()
            sse.stop(0)
        }
    }
}
