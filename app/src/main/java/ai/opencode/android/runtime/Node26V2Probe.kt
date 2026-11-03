package ai.opencode.android.runtime

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Test-build probe for the OpenCode v2 server on the bundled Node 26 (spike/opencode-v2).
 *
 * It runs only when the APK carries assets/node26-v2.zip, which the phase10 pipeline adds
 * to the TEST-ONLY copy and never to the release APK. Without that asset it reports
 * "not bundled". The probe extracts the bundle, starts `serve` on 127.0.0.1 with a random
 * password, checks GET /api/config without and with Basic auth, then stops the server.
 * Output appears in Settings > Diagnostics only. Nothing else calls it.
 */
internal object Node26V2Probe {
    private const val ASSET = "node26-v2.zip"
    private const val BINARY = "libnode26.so"
    private const val PORT = 4797
    private const val START_TIMEOUT_MS = 150_000L
    private const val LOG_CHARS = 3000

    fun report(context: Context, nativeLibraryDir: File, filesDir: File): String {
        val hasAsset = runCatching { context.assets.open(ASSET).close() }.isSuccess
        if (!hasAsset) return "v2: not bundled (no assets/$ASSET)"
        val bin = File(nativeLibraryDir, BINARY)
        if (!bin.isFile) return "v2: asset present but $BINARY is missing"
        val root = File(filesDir, "node26-v2")
        return try {
            val entry = extract(context, root)
            serve(bin, nativeLibraryDir, root, entry)
        } catch (e: Exception) {
            "v2: error=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun extract(context: Context, root: File): File {
        val entry = File(root, "opencode.mjs")
        val marker = File(root, ".complete")
        if (marker.isFile && entry.isFile) return entry
        root.deleteRecursively()
        root.mkdirs()
        val base = root.canonicalPath + File.separator
        context.assets.open(ASSET).use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val item = zip.nextEntry ?: break
                    val out = File(root, item.name).canonicalFile
                    require(out.path.startsWith(base)) { "unsafe zip entry ${item.name}" }
                    if (item.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        marker.writeText("ok")
        return entry
    }

    private fun serve(bin: File, libDir: File, root: File, entry: File): String {
        val password = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val home = File(root, "home").apply { mkdirs() }
        val pb = ProcessBuilder(
            bin.absolutePath,
            "--experimental-ffi",
            "--use-system-ca",
            "--disable-warning=ExperimentalWarning",
            entry.absolutePath,
            "serve",
            "--hostname",
            "127.0.0.1",
            "--port",
            PORT.toString(),
        ).redirectErrorStream(true).directory(root)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", libDir.absolutePath)
            put("HOME", home.absolutePath)
            put("TMPDIR", home.absolutePath)
            put("XDG_DATA_HOME", File(home, "data").absolutePath)
            put("XDG_CONFIG_HOME", File(home, "config").absolutePath)
            put("XDG_STATE_HOME", File(home, "state").absolutePath)
            put("XDG_CACHE_HOME", File(home, "cache").absolutePath)
            put("OPENCODE_SERVER_PASSWORD", password)
        }
        val log = StringBuilder()
        val proc = pb.start()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(log) {
                            if (log.length < LOG_CHARS * 4) log.appendLine(line.replace(password, "<redacted>"))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val sb = StringBuilder()
        try {
            sb.appendLine("file=${entry.absolutePath} bundle=${root.walkTopDown().count()} files")
            val started = System.currentTimeMillis()
            var noAuth: String? = null
            var withAuth: String? = null
            var lastError = ""
            while (withAuth == null && proc.isAlive && System.currentTimeMillis() - started < START_TIMEOUT_MS) {
                try {
                    noAuth = noAuth ?: request(null, password)
                    withAuth = request(password, password)
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    Thread.sleep(1000)
                }
            }
            val elapsed = (System.currentTimeMillis() - started) / 1000
            sb.appendLine("alive=${proc.isAlive} elapsed=${elapsed}s")
            sb.appendLine("GET /api/config without auth: ${noAuth ?: "no answer ($lastError)"}")
            sb.appendLine("GET /api/config with Basic auth: ${withAuth ?: "no answer ($lastError)"}")
        } finally {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
            reader.join(2000)
        }
        synchronized(log) {
            sb.appendLine("--- server log (tail, password redacted) ---")
            sb.appendLine(log.toString().takeLast(LOG_CHARS))
        }
        return sb.toString().trimEnd()
    }

    /** Returns "HTTP <code> <first 300 chars of body>". Basic auth only when a password is given. */
    private fun request(password: String?, secret: String): String {
        val conn = URL("http://127.0.0.1:$PORT/api/config").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 5000
        if (password != null) {
            val token = Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            "HTTP $code ${body.replace(secret, "<redacted>").take(300)}"
        } finally {
            conn.disconnect()
        }
    }
}
