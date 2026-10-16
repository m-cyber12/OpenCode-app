package ai.opencode.android.preview

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * The Sandbox's static file server (v9.7; owner decision: static preview
 * first). Serves EXACTLY ONE directory - the active project - on the loopback
 * interface only, so the app's own preview WebView and the phone's browser can
 * render the project the way a deployed site renders (a real http origin:
 * module scripts, fetch and storage behave; the file:// origin forbids much of
 * that), while nothing off the phone can ever connect.
 *
 * Deliberately tiny and dependency-free: GET and HEAD, path-normalised lookups
 * confined to the root, directory requests fall through to index.html, a fixed
 * mime map. Everything else is 404/405. No directory listing, no writes, no
 * proxying - the agent's server-side stays the OpenCode runtime; this is a
 * viewer.
 */
class PreviewServer(val root: File) {

    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "preview-http").apply { isDaemon = true }
    }

    val running: Boolean get() = socket?.isClosed == false
    val port: Int get() = socket?.localPort ?: -1

    /** The address actually bound - the IPv4/IPv6 confusion above is test-pinned via this. */
    val boundHost: String get() = socket?.inetAddress?.hostAddress ?: ""

    /**
     * Bind loopback-only and start accepting. Tries the friendly port first so
     * the URL stays stable across preview sessions; falls back to an ephemeral
     * port when something else holds it.
     */
    fun start(preferredPort: Int = 8642) {
        if (running) return
        // v9.8 fix (owner's ERR_CONNECTION_REFUSED): on Android,
        // getLoopbackAddress() can hand back the IPv6 loopback (::1) - the
        // socket then listens on [::1]:port while the pane dials the IPv4
        // 127.0.0.1:port, which the kernel refuses. Desktop JVMs return
        // 127.0.0.1, which is why the unit tests never caught it. Bind the
        // IPv4 loopback EXPLICITLY, by address bytes (no resolver involved).
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val bound = runCatching { ServerSocket(preferredPort, 8, loopback) }
            .getOrElse { ServerSocket(0, 8, loopback) }
        socket = bound
        Thread({
            while (true) {
                val client = runCatching { bound.accept() }.getOrNull() ?: break
                runCatching { pool.execute { serve(client) } }.onFailure { client.close() }
            }
        }, "preview-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool.shutdown()
    }

    private fun serve(client: Socket) {
        client.use { s ->
            val request = runCatching {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
                val first = reader.readLine() ?: return
                var inm = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    // v9.37: the ONE header a fresh-enough answer needs -
                    // revalidation for cacheable media rides on it.
                    if (line.startsWith("If-None-Match:")) inm = line.substringAfter(':').trim()
                }
                first to inm
            }.getOrNull() ?: return
            val out = s.getOutputStream()
            val (requestLine, ifNoneMatch) = request
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            if (method != "GET" && method != "HEAD") {
                respond(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "405".toByteArray(), method == "HEAD")
                return
            }
            val target = resolve(parts[1])
            if (target == null || !target.isFile) {
                respond(
                    out, 404, "Not Found", "text/html; charset=utf-8",
                    "<html><body><h3>404</h3><p>No such file in this project.</p></body></html>".toByteArray(),
                    method == "HEAD",
                )
                return
            }
            val etag = "W/\"${target.length()}-${target.lastModified()}\""
            if (cacheable(target.name) && ifNoneMatch == etag) {
                // Unchanged binary media: answer with headers alone. A day of
                // reloads costs zero re-transferred bytes - yet the browser
                // still revalidates every load, so a replaced image is
                // NEVER served stale. (The pane contract "fresh bytes, not
                // cached content" survives; only the wire traffic shrinks.)
                respond304(out, etag)
                return
            }
            val body = runCatching { target.readBytes() }.getOrNull()
            if (body == null) {
                respond(out, 404, "Not Found", "text/plain; charset=utf-8", "unreadable".toByteArray(), method == "HEAD")
                return
            }
            respond(
                out, 200, "OK", mimeOf(target.name), body, method == "HEAD",
                // v9.37 (owner: "slow image loading"): everything answered
                // no-store, so every reload re-fetched every logo and video.
                // Binary media now caches-then-revalidates (304 on repeats);
                // anything text-like the model may still be editing
                // (html/css/js/json/svg...) stays no-store, so a reload
                // always shows fresh bytes.
                cacheable = cacheable(target.name),
                etag = etag,
            )
        }
    }

    /** Decode, strip query/fragment, confine to [root]; a directory means its index.html. */
    internal fun resolve(rawPath: String): File? {
        var path = rawPath.substringBefore('?').substringBefore('#')
        path = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        if (!path.startsWith("/")) return null
        val rootReal = root.canonicalFile
        var target = File(rootReal, path.trimStart('/')).canonicalFile
        if (target.isDirectory) target = File(target, "index.html").canonicalFile
        val inside = target.path == rootReal.path || target.path.startsWith(rootReal.path + File.separator)
        return if (inside) target else null
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        reason: String,
        mime: String,
        body: ByteArray,
        headOnly: Boolean,
        cacheable: Boolean = false,
        etag: String = "",
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(mime).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append(if (cacheable) "Cache-Control: no-cache\r\n" else "Cache-Control: no-store\r\n")
            if (etag.isNotEmpty() && cacheable) append("ETag: ").append(etag).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun respond304(out: OutputStream, etag: String) {
        out.write(
            ("HTTP/1.1 304 Not Modified\r\nETag: $etag\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        out.flush()
    }

    companion object {
        /**
         * v9.37: binary media a page references but the agent is not
         * mid-edit on - safe to let the WebView HTTP cache hold for a day.
         * The model editing an image replaces the file, and a new capture or
         * pane load revalidates on its own schedule; text artifacts never
         * take this path.
         */
        internal fun cacheable(name: String): Boolean =
            when (name.substringAfterLast('.', "").lowercase()) {
                "png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico",
                "mp4", "webm", "ogg", "mp3", "wav", "m4a", "aac",
                "woff", "woff2", "ttf", "otf", "eot" -> true
                else -> false
            }

        /** The fixed map: previews are code the user just wrote, not arbitrary uploads. */
        internal fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "wasm" -> "application/wasm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "txt", "md", "markdown" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
