package ai.opencode.android.client

import ai.opencode.android.client.v2.V2Translator
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Server-sent-events client for OpenCode's event stream.
 *
 * The app does not poll for progress and does not reconstruct agent state
 * locally: it subscribes to the server's own stream (`GET /api/event`, OpenCode
 * v2) and reduces the frames it receives through [V2Translator]. Each SSE data
 * line is a v2 envelope `{id, created, type, data}`. The v1 `/global/event`
 * path is gone with the v1 server.
 */
class OpenCodeEventStream(
    baseUrl: String,
    private val username: String,
    private val password: String,
    /** Unused by the v2 stream, which covers every location; kept for the call sites. */
    private val directory: String? = null,
    /** Called for every event frame (background thread). */
    private val onEvent: (Event) -> Unit,
    private val onStatus: (String) -> Unit = {},
    /**
     * OpenCode v2 sends no heartbeat frame, so a quiet turn (a long tool run) looks
     * the same as a dead socket. 120 s is the silence budget before reconnecting.
     */
    private val heartbeatTimeoutMs: Long = 120_000,
    private val path: String = "/api/event",
) {

    data class Event(val type: String, val properties: JSONObject, val frame: JSONObject)

    private val base = LoopbackGuard.checked(baseUrl)
    @Volatile private var running = false
    @Volatile private var socket: HttpURLConnection? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "opencode-events").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.disconnect() }
        socket = null
    }

    private fun loop() {
        var backoff = 500L
        while (running) {
            try {
                val opened = readStream()
                if (!running) break
                // A clean end-of-stream (e.g. server instance disposed) also
                // reconnects: OpenCode closes the stream on dispose or reload.
                backoff = if (opened) 250L else backoff
                onStatus("stream ended; reconnecting")
            } catch (t: Throwable) {
                if (!running) break
                onStatus("stream error: ${t.message ?: t.javaClass.simpleName}")
            }
            if (!running) break
            Thread.sleep(backoff.coerceAtMost(5_000L))
            backoff = (backoff * 2).coerceAtMost(5_000L)
        }
        onStatus("stream closed")
    }

    /** @return true if at least one frame was delivered before the stream ended. */
    private fun readStream(): Boolean {
        // /api/event covers every location, so no scope is added.
        val url = base + path
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            // The stream must not be cut by an ordinary read timeout, but it
            // must not hang forever either: the server emits a
            // `server.heartbeat` frame every 10s, so a read timeout equal to
            // the liveness budget turns a silently dead socket into a reconnect
            // instead of a stalled client.
            readTimeout = heartbeatTimeoutMs.toInt()
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8)))
        }
        socket = conn
        var delivered = false
        var lastFrameAt = System.currentTimeMillis()
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
                throw IllegalStateException("event stream HTTP $code ${body.take(200)}")
            }
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            val frames = SseAccumulator()
            while (running) {
                if (System.currentTimeMillis() - lastFrameAt > heartbeatTimeoutMs) {
                    onStatus("stream heartbeat timeout; reconnecting")
                    break
                }
                // readLine blocks; stop()/the timeout closes the socket, which
                // makes it throw and unwind to the reconnect path.
                val line = reader.readLine() ?: break
                val payload = frames.feed(line)
                if (payload != null) {
                    delivered = true
                    lastFrameAt = System.currentTimeMillis()
                    dispatch(payload)
                }
            }
            frames.finish()?.let {
                delivered = true
                dispatch(it)
            }
        } finally {
            runCatching { conn.disconnect() }
            if (socket === conn) socket = null
        }
        return delivered
    }

    private fun dispatch(raw: String) {
        if (path.startsWith("/api/")) {
            // v2 envelope `{id, created, type, data}`: translate to the v1 frames the
            // transcript reads. Unknown events produce nothing.
            val envelope = runCatching { JSONObject(raw) }.getOrElse { return }
            for (f in V2Translator.translateEnvelope(raw)) onEvent(Event(f.type, f.props, envelope))
            return
        }
        val frame = runCatching { JSONObject(raw) }.getOrElse { return }
        val payload = frame.optJSONObject("payload") ?: frame
        val type = EventFrame.deriveType(payload)
        if (type.isEmpty() || type == "server.heartbeat") return
        onEvent(Event(type, EventFrame.properties(payload), payload))
    }

}
