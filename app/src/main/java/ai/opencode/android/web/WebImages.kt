package ai.opencode.android.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore

/**
 * Website pictures for the chat renderer (2026-11-23, owner approved network access).
 *
 * This is a background loader, separate from the chat: the UI asks for a picture by
 * URL, gets it from memory if it is there, and otherwise a worker fetches it. Up to
 * [MAX_PARALLEL] downloads run at once, and the rest queue, so a message with many
 * pictures does not stall the chat. The UI layer never opens a socket (phase 6 rule).
 *
 * A link can be to a picture or to a web page (2026-11-26, owner). For a page, the
 * loader reads the page's own metadata (see [PagePicture]) and fetches that picture.
 *
 * Guard rails, all deliberate:
 *  - https only; a cleartext or file URL is never fetched;
 *  - no cookies, no credentials, no headers from the app - a bare GET;
 *  - a host that resolves to a loopback, private, link-local or multicast address is
 *    refused, so a picture link can never reach the phone's own network or the
 *    embedded server;
 *  - redirects are followed by hand, one hop at a time, with the same checks;
 *  - a page is read only up to 512 KB, and only its metadata is used;
 *  - at most 8 MB downloaded for a picture, and any picture is decoded at a reduced size;
 *  - pictures live in memory only (a small LRU), nothing is written to disk;
 *  - only counts are kept for diagnostics - never the URLs.
 */
object WebImages {
    /** Switched on by the owner's approval (2026-11-23). */
    const val NETWORK_ENABLED = true

    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_PAGE_BYTES = 512 * 1024
    private const val MAX_SIDE = 1280
    private const val MAX_HOPS = 3
    private const val CACHE_KIB = 24 * 1024
    private const val MAX_PARALLEL = 4

    private val permits = Semaphore(MAX_PARALLEL)

    private val cache = object : LruCache<String, Bitmap>(CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val lock = Any()
    private var requested = 0
    private var cacheHits = 0
    private var loaded = 0
    private var blocked = 0
    private var failed = 0
    private var totalMs = 0L

    /** Counts only - no URLs - for the diagnostics readout. */
    data class Stats(
        val requested: Int,
        val cacheHits: Int,
        val loaded: Int,
        val blocked: Int,
        val failed: Int,
        val averageMs: Long,
        val cacheKib: Int,
    )

    fun stats(): Stats = synchronized(lock) {
        Stats(
            requested = requested,
            cacheHits = cacheHits,
            loaded = loaded,
            blocked = blocked,
            failed = failed,
            averageMs = if (loaded == 0) 0 else totalMs / loaded,
            cacheKib = cache.size(),
        )
    }

    /** The picture if it is already in memory, else null. Never blocks. */
    fun cached(url: String): Bitmap? = cache.get(url)

    /** Fetches and decodes [url] (a picture, or a page with one); null on any failure. Blocking - call from IO only. */
    fun fetch(url: String): Bitmap? {
        cache.get(url)?.let {
            synchronized(lock) { cacheHits += 1 }
            return it
        }
        synchronized(lock) { requested += 1 }
        if (!NETWORK_ENABLED) {
            synchronized(lock) { blocked += 1 }
            return null
        }
        val started = System.nanoTime()
        permits.acquire()
        val outcome = try {
            runCatching { load(url) }.getOrDefault(Outcome.Failed)
        } finally {
            permits.release()
        }
        return when (outcome) {
            is Outcome.Loaded -> {
                cache.put(url, outcome.bitmap)
                val ms = (System.nanoTime() - started) / 1_000_000
                synchronized(lock) {
                    loaded += 1
                    totalMs += ms
                }
                outcome.bitmap
            }
            Outcome.Blocked -> {
                synchronized(lock) { blocked += 1 }
                null
            }
            is Outcome.Page, Outcome.Failed -> {
                synchronized(lock) { failed += 1 }
                null
            }
        }
    }

    /**
     * A picture link is fetched directly. A page link is read once, its main picture is
     * found, and that picture is fetched with the same checks. A page inside a page is
     * not followed.
     */
    private fun load(url: String): Outcome {
        return when (val first = download(url)) {
            is Outcome.Page -> {
                val picture = PagePicture.find(first.finalUrl, first.html) ?: return Outcome.Failed
                when (val second = download(picture)) {
                    is Outcome.Page -> Outcome.Failed
                    else -> second
                }
            }
            else -> first
        }
    }

    private sealed interface Outcome {
        class Loaded(val bitmap: Bitmap) : Outcome
        class Page(val finalUrl: String, val html: String) : Outcome
        object Blocked : Outcome
        object Failed : Outcome
    }

    private fun download(start: String): Outcome {
        var target = start
        for (hop in 0..MAX_HOPS) {
            val url = URL(target)
            if (!WebImagePolicy.allowed(url)) return Outcome.Blocked
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 6_000
                conn.readTimeout = 10_000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return Outcome.Failed
                    target = URL(url, location).toString()
                    continue
                }
                if (code != 200) return Outcome.Failed
                val type = conn.contentType.orEmpty().lowercase()
                if (type.startsWith("text/html")) {
                    val html = readUpTo(conn.inputStream, MAX_PAGE_BYTES)
                    return Outcome.Page(url.toString(), String(html, Charsets.UTF_8))
                }
                if (!type.startsWith("image/")) return Outcome.Blocked
                val bytes = readCapped(conn.inputStream) ?: return Outcome.Blocked
                val bitmap = decode(bytes) ?: return Outcome.Failed
                return Outcome.Loaded(bitmap)
            } finally {
                conn.disconnect()
            }
        }
        return Outcome.Failed
    }

    /** Reads a picture; null when it is larger than [MAX_BYTES]. */
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

    /** Reads a page's first [limit] bytes. A longer page is cut, not refused: its head holds the metadata. */
    private fun readUpTo(stream: InputStream, limit: Int): ByteArray {
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, minOf(n, limit - out.size()))
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
