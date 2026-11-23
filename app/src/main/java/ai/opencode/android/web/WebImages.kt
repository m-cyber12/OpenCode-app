package ai.opencode.android.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Website pictures for the chat renderer (2026-11-23).

 * The network code lives here, outside the UI layer, because the UI may not open
 * sockets (phase 6 purity rule). The chat only ever calls [cached] and [fetch].
 *
 * Guard rails, all deliberate:
 *  - https only; a cleartext or file URL is never fetched;
 *  - no cookies, no credentials, no headers from the app - a bare GET;
 *  - a host that resolves to a loopback, private, link-local or multicast address is
 *    refused, so a picture link can never reach the phone's own network or the
 *    embedded server;
 *  - redirects are followed by hand, one hop at a time, with the same checks;
 *  - at most 8 MB downloaded, and any image is decoded at a reduced size;
 *  - pictures live in memory only (a small LRU), nothing is written to disk.
 */
object WebImages {
    /**
     * Off until the owner approves network access for website pictures (2026-11-23).
     * While false, a picture shows its alt text and nothing is fetched.
     */
    const val NETWORK_ENABLED = false

    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_SIDE = 1600
    private const val MAX_HOPS = 3
    private const val CACHE_KIB = 24 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** The picture if it is already in memory, else null. Never blocks. */
    fun cached(url: String): Bitmap? = cache.get(url)

    /** Fetches and decodes [url]; null on any failure. Blocking - call from IO only. */
    fun fetch(url: String): Bitmap? {
        if (!NETWORK_ENABLED) return null
        cache.get(url)?.let { return it }
        val bitmap = runCatching { download(url) }.getOrNull() ?: return null
        cache.put(url, bitmap)
        return bitmap
    }

    private fun download(start: String): Bitmap? {
        var target = start
        for (hop in 0..MAX_HOPS) {
            val url = URL(target)
            if (url.protocol != "https" || !isPublicHost(url.host)) return null
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    target = URL(url, location).toString()
                    continue
                }
                if (code != 200) return null
                if (!conn.contentType.orEmpty().startsWith("image/")) return null
                val bytes = readCapped(conn.inputStream) ?: return null
                return decode(bytes)
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** True only when every address the host resolves to is a public one. */
    private fun isPublicHost(host: String): Boolean {
        if (host.isBlank()) return false
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty()) return false
        return addresses.none { a ->
            val raw = a.address
            a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                a.isSiteLocalAddress || a.isMulticastAddress ||
                (raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC) // IPv6 unique-local
        }
    }

    private fun readCapped(stream: InputStream): ByteArray? {
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            return out.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
