package ai.opencode.android.client

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Server-sent-events client for OpenCode's event stream.
 *
 * The app does not poll for progress and does not reconstruct agent state
 * locally: it subscribes to the server's own stream (`GET /global/event`, the
 * same one the TUI and the desktop app consume) and reduces the frames it
 * receives. Frames are `event: message` + `data: {directory, project, payload}`
 * where `payload.type` is the event type; a keepalive `server.heartbeat` is
 * emitted by the server every 10s (Local.heartbeat in server.ts), so a quiet
 * stream longer than that means the connection died and we reconnect.
 */
class OpenCodeEventStream(
    baseUrl: String,
    private val username: String,
    private val password: String,
    /** Absolute workspace directory; scopes the stream like every other call. */
    private val directory: String? = null,
    /** Called for every event frame (background thread). */
    private val onEvent: (Event) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val heartbeatTimeoutMs: Long = 25_000,
    private val path: String = "/global/event",
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
                // reconnects: OpenCode closes /global/event on dispose.
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
        // /global/event is instance-agnostic (it fans out every directory), so
        // the directory is only appended to the instance-scoped stream.
        val dir = directory
        val url = if (dir != null && !path.startsWith("/global/")) {
            base + path + "?directory=" + encode(dir)
        } else {
            base + path
        }
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
        val frame = runCatching { JSONObject(raw) }.getOrElse { return }
        val payload = frame.optJSONObject("payload") ?: frame
        val type = EventFrame.deriveType(payload)
        if (type.isEmpty() || type == "server.heartbeat") return
        onEvent(Event(type, EventFrame.properties(payload), payload))
    }

    private fun encode(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")
}
