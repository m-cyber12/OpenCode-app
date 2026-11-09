package ai.opencode.android.client

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round-trips the client against a local stand-in HTTP server so the *wire
 * shape* is verified (method, path, query, body, auth header) without a device.
 * The on-device instrumented test then proves the same code against the real
 * OpenCode v2 server; these two together are what backs "the app is a client of
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

    /** `streamThenClose` mimics `GET /api/event`: no length, body until EOF. */
    private class Resp(val status: Int, val body: String, val streamThenClose: Boolean = false)

    private val seen = ConcurrentLinkedQueue<Record>()
    @Volatile private var lastExchange: Record? = null
    @Volatile private var responseBody: String = "{}"
    @Volatile private var responseStatus: Int = 200
    /** Per-path bodies for calls that read several routes (providers, messages). */
    private val bodyByPath = ConcurrentHashMap<String, String>()
    private var fake: FakeHttp? = null

    private fun respondTo(rec: Record): Resp = Resp(responseStatus, bodyByPath[rec.path] ?: responseBody)

    @Before
    fun up() {
        seen.clear()
        lastExchange = null
        responseBody = "{}"
        responseStatus = 200
        bodyByPath.clear()
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

    // ---- wire shape: connect and sessions -----------------------------------

    @Test
    fun healthIsApiInfoAndCarriesBasicAuth() {
        responseBody = """{"version":"2.2.5"}"""
        val h = api().health()
        assertEquals("2.2.5", h.getString("version"))
        val r = lastExchange!!
        assertEquals("GET", r.method)
        assertEquals("/api/info", r.path)
        assertEquals("unscoped routes must not carry a location", "", r.query)
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:secret-pw".toByteArray())
        assertEquals(expected, r.auth)
    }

    @Test
    fun sessionCreateIsLocationScopedAndMapsTheV2Envelope() {
        responseBody = """{"data":{"id":"ses_abc","title":"t1","location":{"directory":"/x"},
            "projectID":"prj_1","cost":0,"time":{"created":1000,"updated":2000}}}"""
        val s = api().createSession("t1")
        assertEquals("ses_abc", s.id)
        assertEquals("/x", s.directory)
        assertEquals(2000L, s.updatedAt)
        val r = lastExchange!!
        assertEquals("POST", r.method)
        assertEquals("/api/session", r.path)
        assertTrue(r.query, r.query.contains("location%5Bdirectory%5D=%2Fdata%2Fuser%2F0"))
        val body = JSONObject(r.body)
        assertEquals("t1", body.getString("title"))
        assertEquals("/data/user/0/ai.opencode.android/files/workspaces/proj", body.getJSONObject("location").getString("directory"))
    }

    @Test
    fun sessionListUsesTheTopLevelDirectoryFilter() {
        responseBody = """{"data":[{"id":"ses_1","title":"a","location":{"directory":"/p"},"time":{"created":1,"updated":5}}],
            "cursor":{}}"""
        val list = api().listSessions(limit = 20, directory = "/other")
        assertEquals(listOf("ses_1"), list.map { it.id })
        val r = lastExchange!!
        assertEquals("GET", r.method)
        assertEquals("/api/session", r.path)
        assertTrue(r.query, r.query.contains("directory=%2Fother"))
        assertTrue(r.query, r.query.contains("limit=20"))
        assertTrue("the explicit filter replaces the location scope", !r.query.contains("location"))
    }

    @Test
    fun abortInterruptsTheSession() {
        api().abortSession("ses_1")
        val r = lastExchange!!
        assertEquals("POST", r.method)
        assertEquals("/api/session/ses_1/interrupt", r.path)
    }

    // ---- wire shape: prompt --------------------------------------------------

    /** Model and agent are session settings in v2: sent once, then only the prompt. */
    @Test
    fun promptSyncsModelAndAgentThenSendsTheText() {
        responseStatus = 204
        responseBody = ""
        val status = api().promptAsync(
            "ses_1",
            "hello",
            OpenCodeApi.ModelRef("opencode", "big-pickle"),
            agent = "plan",
            variant = "high",
        )
        assertEquals(204, status)
        assertEquals(
            listOf("/api/session/ses_1/model", "/api/session/ses_1/agent", "/api/session/ses_1/prompt"),
            seen.map { it.path },
        )
        val model = JSONObject(seen.elementAt(0).body).getJSONObject("model")
        assertEquals("big-pickle", model.getString("id"))
        assertEquals("opencode", model.getString("providerID"))
        assertEquals("high", model.getString("variant"))
        assertEquals("plan", JSONObject(seen.elementAt(1).body).getString("agent"))
        assertEquals("hello", JSONObject(seen.elementAt(2).body).getString("text"))
    }

    @Test
    fun promptDoesNotResendAnUnchangedModel() {
        responseStatus = 204
        responseBody = ""
        val api = api()
        api.promptAsync("ses_1", "one", OpenCodeApi.ModelRef("opencode", "big-pickle"))
        api.promptAsync("ses_1", "two", OpenCodeApi.ModelRef("opencode", "big-pickle"))
        assertEquals(
            listOf("/api/session/ses_1/model", "/api/session/ses_1/prompt", "/api/session/ses_1/prompt"),
            seen.map { it.path },
        )
    }

    @Test
    fun promptOmitsFilesWhenNoneAndMapsAttachmentsToUri() {
        responseStatus = 204
        responseBody = ""
        api().promptAsync("ses_1", "plain")
        assertTrue(!JSONObject(lastExchange!!.body).has("files"))

        api().promptAsync(
            "ses_1",
            "with file",
            attachments = listOf(OpenCodeApi.Attachment("notes.txt", "text/plain", "file:///x/notes.txt", 3)),
        )
        val files = JSONObject(lastExchange!!.body).getJSONArray("files")
        assertEquals("file:///x/notes.txt", files.getJSONObject(0).getString("uri"))
        assertEquals("notes.txt", files.getJSONObject(0).getString("name"))
    }

    // ---- wire shape: providers, status, messages -----------------------------

    /**
     * The v1 provider document is rebuilt from three v2 routes. Disabled models are
     * dropped, a provider is "connected" when it has an enabled model, and the
     * variant ids come from the model's own variant list.
     */
    @Test
    fun providersAreBuiltFromTheV2Routes() {
        bodyByPath["/api/provider"] = """{"data":[{"id":"openai","name":"OpenAI","activation":"auto"},
            {"id":"off","name":"Off","activation":"disabled"}],"location":{"directory":"/p"}}"""
        bodyByPath["/api/model"] = """{"data":[
            {"id":"gpt-5","providerID":"openai","name":"GPT-5","status":"active","enabled":true,
             "variants":[{"id":"low"},{"id":"medium"},{"id":"high"},{"id":"xhigh"}]},
            {"id":"gpt-old","providerID":"openai","name":"Old","status":"deprecated","enabled":false},
            {"id":"m1","providerID":"off","name":"M1","status":"active","enabled":true}],"location":{"directory":"/p"}}"""
        bodyByPath["/api/model/default"] = """{"data":{"id":"gpt-5","providerID":"openai"},"location":{"directory":"/p"}}"""
        val snap = api().providers()
        assertEquals(listOf("openai"), snap.connected)
        assertEquals(listOf("gpt-5"), snap.modelsOf("openai").map { it.id })
        assertEquals(setOf("low", "medium", "high", "xhigh"), snap.modelsOf("openai")[0].variants.toSet())
        assertEquals("gpt-5", snap.defaultModel["openai"])
        assertEquals(emptyList<String>(), snap.modelsOf("off"))
        assertEquals(setOf("low", "medium", "high", "xhigh"), snap.variantsOf(OpenCodeApi.ModelRef("openai", "gpt-5")).toSet())
    }

    @Test
    fun activeSessionsBecomeBusyStatus() {
        responseBody = """{"data":{"ses_1":{"type":"running"}}}"""
        val status = api().sessionStatus()
        assertEquals("/api/session/active", lastExchange!!.path)
        assertTrue(status["ses_1"]!!.busy)
        assertEquals(setOf("ses_1"), status.keys)
    }

    /** The v2 message page is newest-first; the client returns it oldest-first, mapped to v1 parts. */
    @Test
    fun messagesAreMappedOldestFirstWithStablePartIds() {
        responseBody = """{"data":[
            {"id":"msg_a","type":"assistant","agent":"build","model":{"id":"m","providerID":"p"},
             "content":[{"type":"reasoning","text":"think"},{"type":"text","text":"Hello"}],
             "time":{"created":2,"completed":3}},
            {"id":"msg_u","type":"user","text":"hi","files":[],"agents":[],"skills":[],"time":{"created":1}}
            ],"cursor":{}}"""
        val list = api().messages("ses_1", limit = 50)
        assertEquals("/api/session/ses_1/message", lastExchange!!.path)
        assertTrue(lastExchange!!.query, lastExchange!!.query.contains("order=desc"))
        assertTrue(lastExchange!!.query, lastExchange!!.query.contains("limit=50"))
        assertEquals(listOf("msg_u", "msg_a"), list.map { it.id })
        assertEquals("user", list[0].role)
        assertEquals("hi", list[0].parts[0].getString("text"))
        assertEquals("assistant", list[1].role)
        assertEquals(listOf("reasoning", "text"), list[1].parts.map { it.getString("type") })
        assertEquals("msg_a:text:1", list[1].parts[1].getString("id"))
        assertEquals("msg_a:reasoning:0", list[1].parts[0].getString("id"))
        assertEquals(3L, list[1].info!!.getJSONObject("time").getLong("completed"))
    }

    @Test
    fun assistantErrorBecomesANamedErrorObject() {
        responseBody = """{"data":[{"id":"msg_e","type":"assistant","agent":"build",
            "model":{"id":"m","providerID":"p"},"content":[],
            "error":{"type":"provider.free-limit","message":"Free usage limit reached.","status":429},
            "time":{"created":1}}],"cursor":{}}"""
        val msg = api().messages("ses_1").single()
        val err = msg.info!!.getJSONObject("error")
        assertEquals("provider.free-limit", err.getString("name"))
        assertEquals("Free usage limit reached.", err.getJSONObject("data").getString("message"))
        assertEquals(429, err.getJSONObject("data").getInt("statusCode"))
    }

    /** v2 permission requests come back as v1-shaped rows the transcript already renders. */
    @Test
    fun pendingPermissionsMapTheV2RequestShape() {
        responseBody = """{"data":[{"id":"per_1","sessionID":"ses_1","action":"bash",
            "resources":["ls -la"],"save":["bash *"],"metadata":{"command":"ls -la"},
            "source":{"type":"tool","messageID":"msg_1","id":"call_9"}}],"location":{"directory":"/p"}}"""
        val list = api().pendingPermissions()
        assertEquals("/api/permission/request", lastExchange!!.path)
        val p = list.single()
        assertEquals("per_1", p.id)
        assertEquals("ses_1", p.sessionID)
        assertEquals("bash", p.permission)
        assertEquals(listOf("ls -la"), p.patterns)
        assertEquals(listOf("bash *"), p.always)
        assertEquals("call_9", p.toolCallID)
    }

    /** The reply goes through the owning session, with the decision in `decision`. */
    @Test
    fun permissionReplyPostsTheDecisionThroughTheSession() {
        responseStatus = 204
        responseBody = ""
        api().replyPermission("ses_1", "per_1", "always")
        val r = lastExchange!!
        assertEquals("POST", r.method)
        assertEquals("/api/session/ses_1/permission/per_1/reply", r.path)
        assertEquals("always", JSONObject(r.body).getString("decision"))
    }

    // ---- not ported yet: fail loudly, send nothing ---------------------------

    @Test
    fun portsNotDoneYetFailWithoutSendingARequest() {
        val calls: List<() -> Any?> = listOf(
            { api().revert("ses_1", "msg_1") },
            { api().unrevert("ses_1") },
            { api().shell("ses_1", "ls", "build") },
            { api().addMcp("x", JSONObject()) },
            { api().setProviderAuth("openrouter", "k") },
            { api().patchGlobalConfig(JSONObject()) },
            { api().fileContent("opencode.json") },
        )
        for (call in calls) {
            val err = runCatching { call() }.exceptionOrNull()
            assertTrue("expected not-ported error, got $err", err is OpenCodeApi.ApiException)
            assertEquals(501, (err as OpenCodeApi.ApiException).status)
        }
        assertEquals(0, seen.size)
    }

    /** Lists whose routes are not ported report empty rather than failing the whole refresh. */
    @Test
    fun portedAsEmptyListsMakeNoRequest() {
        assertEquals(emptyList<Any>(), api().todos("ses_1"))
        assertEquals(emptyMap<String, Any>(), api().mcpEntries())
        assertEquals(0, seen.size)
    }

    private val pickForm = """{"id":"frm_1","sessionID":"ses_1","title":"Pick",
        "fields":[
          {"type":"multiselect","key":"tools","title":"Tools","description":"Which","options":[
             {"value":"a","label":"Alpha"},{"value":"b","label":"Beta","description":"second"}]},
          {"type":"boolean","key":"ok","title":"OK?"},
          {"type":"string","key":"name","title":"Name"},
          {"type":"string","key":"secret","title":"Hidden","hidden":true}]}"""

    /** Each visible form field becomes one question; hidden fields are not asked. */
    @Test
    fun formsBecomeQuestionsWithTheirOptions() {
        responseBody = """{"data":[$pickForm],"location":{"directory":"/p"}}"""
        val list = api().pendingQuestions()
        assertEquals("/api/form", lastExchange!!.path)
        val q = list.single()
        assertEquals("frm_1", q.id)
        assertEquals("ses_1", q.sessionID)
        assertEquals(3, q.items.size)
        assertEquals("Tools", q.items[0].header)
        assertEquals("Which", q.items[0].question)
        assertEquals(listOf("Alpha", "Beta"), q.items[0].options.map { it.label })
        assertTrue(q.items[0].multiple)
        assertEquals(listOf("Yes", "No"), q.items[1].options.map { it.label })
        assertTrue(q.items[2].custom)
        assertEquals(emptyList<String>(), q.items[2].options.map { it.label })
    }

    /** A form with a web step cannot be answered here, so it is not offered. */
    @Test
    fun formsWithAWebStepAreNotOffered() {
        responseBody = """{"data":[{"id":"frm_2","sessionID":"ses_1","title":"Login",
            "fields":[{"type":"external","key":"oauth","url":"https://example.invalid"}]}]}"""
        assertEquals(emptyList<Any>(), api().pendingQuestions())
    }

    /** The reply reads the form, converts labels back to option values, and posts `answer`. */
    @Test
    fun replyQuestionConvertsLabelsToTheFormAnswer() {
        bodyByPath["/api/session/ses_1/form/frm_1"] = """{"data":$pickForm}"""
        responseBody = ""
        api().replyQuestion("ses_1", "frm_1", listOf(listOf("Alpha", "Beta"), listOf("No"), listOf("my name")))
        assertEquals(
            listOf("/api/session/ses_1/form/frm_1", "/api/session/ses_1/form/frm_1/reply"),
            seen.map { it.path },
        )
        val answer = JSONObject(seen.elementAt(1).body).getJSONObject("answer")
        assertEquals(listOf("a", "b"), answer.getJSONArray("tools").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(false, answer.getBoolean("ok"))
        assertEquals("my name", answer.getString("name"))
        assertTrue(!answer.has("secret"))
    }

    @Test
    fun rejectQuestionCancelsTheForm() {
        responseStatus = 204
        responseBody = ""
        api().rejectQuestion("ses_1", "frm_1")
        val r = lastExchange!!
        assertEquals("DELETE", r.method)
        assertEquals("/api/session/ses_1/form/frm_1", r.path)
    }

    /** A body-less POST declares Content-Length: 0 so the server does not wait for a body. */
    @Test
    fun disposeReloadsTheLocationWithZeroLengthFraming() {
        responseStatus = 204
        responseBody = ""
        api().dispose()
        val r = lastExchange!!
        assertEquals("POST", r.method)
        assertEquals("/api/location/reload", r.path)
        assertEquals("0", r.contentLengthHeader)
    }

    @Test
    fun non2xxBecomesTypedError() {
        responseStatus = 404
        responseBody = """{"name":"NotFoundError","message":"session not found"}"""
        val err = runCatching { api().getSession("ses_missing") }.exceptionOrNull()
        assertTrue(err is OpenCodeApi.ApiException)
        assertEquals(404, (err as OpenCodeApi.ApiException).status)
        assertTrue(err.body.contains("NotFoundError"))
    }

    /** The v2 event stream, end to end, against a real socket: envelopes become v1 frames. */
    @Test
    fun eventStreamTranslatesV2EnvelopesAndReconnects() {
        val hits = AtomicInteger()
        val frames = "data: {\"id\":\"evt_0\",\"created\":1,\"type\":\"server.connected\",\"data\":{}}\n\n" +
            "data: {\"id\":\"evt_1\",\"created\":2,\"type\":\"session.execution.started\",\"data\":{\"sessionID\":\"s1\"}}\n\n" +
            "data: {\"id\":\"evt_2\",\"created\":3,\"type\":\"session.text.delta\",\"data\":" +
            "{\"sessionID\":\"s1\",\"assistantMessageID\":\"msg_1\",\"ordinal\":0,\"delta\":\"hi\"}}\n\n"
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
            onEvent = { ev ->
                types.add(ev.type)
                got.countDown()
            },
            heartbeatTimeoutMs = 1_500,
        )
        try {
            stream.start()
            assertTrue("two translated events must arrive (got $types)", got.await(20, TimeUnit.SECONDS))
            // The stream is a subscription: after the server closed the body the client reconnects.
            val deadline = System.currentTimeMillis() + 10_000
            while (hits.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(150)
            assertTrue("stream should have reconnected (hits=${hits.get()})", hits.get() >= 2)
            assertTrue(types.toString(), types.contains("session.status"))
            assertTrue(types.toString(), types.contains("message.part.delta"))
            assertTrue("server.connected is not a v1 frame", !types.contains("server.connected"))
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
                    // Headers are read a byte at a time, deliberately: wrapping the
                    // stream in a BufferedReader would prefetch the *body* into the
                    // reader's buffer, and the then-empty raw stream would block until
                    // the client's read timeout (this hang was the first CI result of
                    // every body-carrying request here).
                    val head = readHead(input) ?: return
                    val bits = head.substringBefore("\r\n").split(" ")
                    val method = bits.getOrElse(0) { "" }
                    val target = bits.getOrElse(1) { "" }
                    val q = target.indexOf('?')
                    val path = if (q >= 0) target.substring(0, q) else target
                    val query = if (q >= 0) target.substring(q + 1) else ""
                    var contentLength = -1
                    var clHeader: String? = null
                    var auth = ""
                    var accept = ""
                    for (h in head.split("\r\n").drop(1)) {
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

        /** Reads until the blank line that ends the request head; returns it whole. */
        private fun readHead(input: InputStream): String? {
            val mark = byteArrayOf(13, 10, 13, 10)
            val buf = java.io.ByteArrayOutputStream()
            val one = ByteArray(1)
            var matched = 0
            while (true) {
                val n = try { input.read(one) } catch (_: Throwable) { -1 }
                if (n < 0) break
                buf.write(one, 0, 1)
                matched = when {
                    one[0] == mark[matched] -> matched + 1
                    one[0] == mark[0] -> 1
                    else -> 0
                }
                if (matched == 4) break
            }
            val text = buf.toString("UTF-8")
            return if (text.contains("\r\n\r\n")) text else text.ifEmpty { null }
        }

        override fun close() {
            running = false
            runCatching { ss.close() }
        }
    }
}
