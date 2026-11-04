package ai.opencode.android.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
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
 * "not bundled".
 *
 * Each Diagnostics call: extracts the bundle once, starts `serve` on 127.0.0.1 once (it
 * stays running between calls), checks GET /api/config, and starts one background sweep.
 * The sweep lists every model from GET /api/model and sends one prompt per model, in its
 * own session, then records the answer. Results appear in later Diagnostics calls.
 * Output appears in Settings > Diagnostics only. Nothing else calls it.
 */
internal object Node26V2Probe {
    private const val ASSET = "node26-v2.zip"
    private const val BINARY = "libnode26.so"
    private const val PORT = 4797
    private const val BASE = "http://127.0.0.1:$PORT"
    private const val START_TIMEOUT_MS = 150_000L
    private const val SWEEP_BUDGET_MS = 45L * 60_000L
    private const val LOG_CHARS = 3000
    private const val SWEEP_LINES = 400

    private val lock = Any()
    private var proc: Process? = null
    private var password: String? = null
    private val serverLog = StringBuilder()
    private var sweepStarted = false
    private var sweepDone = false
    private val sweepOut = ArrayList<String>()

    fun report(context: Context, nativeLibraryDir: File, filesDir: File): String {
        val hasAsset = runCatching { context.assets.open(ASSET).close() }.isSuccess
        if (!hasAsset) return "v2: not bundled (no assets/$ASSET)"
        val bin = File(nativeLibraryDir, BINARY)
        if (!bin.isFile) return "v2: asset present but $BINARY is missing"
        val root = File(filesDir, "node26-v2")
        val sb = StringBuilder()
        return try {
            val entry = extract(context, root)
            val pw = ensureServer(bin, nativeLibraryDir, root, entry, sb)
            if (pw != null) ensureSweep(pw, sb)
            appendSweep(sb)
            sb.toString().trimEnd()
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

    /** Returns the password of a running server, or null if it could not be started. */
    private fun ensureServer(bin: File, libDir: File, root: File, entry: File, sb: StringBuilder): String? {
        val existing = synchronized(lock) {
            val p = proc
            if (p != null && p.isAlive) password else null
        }
        if (existing != null) {
            sb.appendLine("server: running from an earlier call (not restarted)")
            return existing
        }

        val pw = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
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
            put("OPENCODE_SERVER_PASSWORD", pw)
        }
        val p = pb.start()
        synchronized(lock) {
            proc = p
            password = pw
            serverLog.setLength(0)
        }
        Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(lock) {
                            serverLog.appendLine(line.replace(pw, "<redacted>"))
                            if (serverLog.length > LOG_CHARS * 2) serverLog.delete(0, serverLog.length - LOG_CHARS)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val started = System.currentTimeMillis()
        var answer: String? = null
        var lastError = ""
        while (answer == null && p.isAlive && System.currentTimeMillis() - started < START_TIMEOUT_MS) {
            try {
                val (code, body) = request(BASE, pw, "GET", "/api/config", null, 5_000)
                answer = "HTTP $code ${body.take(160)}"
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Thread.sleep(1000)
            }
        }
        if (answer == null) {
            sb.appendLine("server: did not answer (alive=${p.isAlive}, last error: $lastError)")
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            synchronized(lock) {
                proc = null
                password = null
            }
            appendServerLog(sb)
            return null
        }
        sb.appendLine("server: alive=${p.isAlive} started in ${(System.currentTimeMillis() - started) / 1000}s")
        sb.appendLine("GET /api/config with Basic auth: $answer")
        val noAuth = runCatching { request(BASE, null, "GET", "/api/config", null, 5_000) }
            .map { "HTTP ${it.first}" }
            .getOrElse { "error ${it.javaClass.simpleName}" }
        sb.appendLine("GET /api/config without auth: $noAuth")
        return pw
    }

    private fun ensureSweep(pw: String, sb: StringBuilder) {
        val shouldStart = synchronized(lock) {
            if (sweepStarted) {
                false
            } else {
                sweepStarted = true
                true
            }
        }
        if (shouldStart) {
            Thread { sweep(pw) }.apply { isDaemon = true; start() }
            sb.appendLine("sweep: started (GET /api/model, one prompt per model, one session each)")
        } else {
            val done = synchronized(lock) { sweepDone }
            sb.appendLine("sweep: ${if (done) "finished" else "running"} (started by an earlier call)")
        }
    }

    private fun sweep(pw: String) {
        val started = System.currentTimeMillis()
        try {
            val (code, body) = request(BASE, pw, "GET", "/api/model", null, 60_000)
            if (code !in 200..299) {
                emit("model list failed: HTTP $code ${body.take(300)}")
                return
            }
            val models = unwrapArray(body)
            emit("models listed: ${models.length()}")
            for (i in 0 until models.length()) {
                if (System.currentTimeMillis() - started > SWEEP_BUDGET_MS) {
                    emit("stopped: 45 min budget reached before model ${i + 1}")
                    break
                }
                val m = models.getJSONObject(i)
                val provider = m.optString("providerID")
                val modelId = m.optString("id")
                val head = "[${i + 1}/${models.length()}] $provider/$modelId enabled=${m.optBoolean("enabled")} status=${m.optString("status")}"
                val result = try {
                    sweepOne(pw, provider, modelId)
                } catch (e: Exception) {
                    "ERROR ${e.javaClass.simpleName}: ${e.message?.take(160)}"
                }
                emit("$head -> $result")
            }
            emit("DONE")
        } catch (e: Exception) {
            emit("sweep stopped: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
        } finally {
            synchronized(lock) { sweepDone = true }
        }
    }

    private fun sweepOne(pw: String, provider: String, modelId: String): String {
        val create = JSONObject()
            .put("title", "node26 sweep $provider/$modelId")
            .put("model", JSONObject().put("id", modelId).put("providerID", provider))
        val (c1, b1) = request(BASE, pw, "POST", "/api/session", create.toString(), 30_000)
        if (c1 !in 200..299) return "create HTTP $c1 ${b1.take(160)}"
        val sid = JSONObject(b1).getJSONObject("data").getString("id")
        val prompt = JSONObject().put("text", "Reply with exactly: OK")
        val (c2, b2) = request(BASE, pw, "POST", "/api/session/$sid/prompt", prompt.toString(), 30_000)
        if (c2 !in 200..299) return "prompt HTTP $c2 ${b2.take(160)}"
        val waited = try {
            val (c3, _) = request(BASE, pw, "POST", "/api/experimental/session/$sid/wait", null, 120_000)
            "wait HTTP $c3"
        } catch (e: SocketTimeoutException) {
            "wait TIMEOUT 120s"
        }
        val (c4, b4) = request(BASE, pw, "GET", "/api/session/$sid/message", null, 30_000)
        return "$waited; messages HTTP $c4 -> ${verdict(b4)}"
    }

    private fun verdict(body: String): String {
        val items = runCatching { unwrapArray(body).length() }.getOrDefault(-1)
        val tag = if (body.contains("error", ignoreCase = true)) "ERROR-IN-MESSAGES" else "CHECK-TEXT"
        return "$tag items=$items tail=${body.replace("\n", " ").takeLast(260)}"
    }

    /** Accepts either a bare JSON array or an object with a "data" array. */
    private fun unwrapArray(body: String): JSONArray {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).getJSONArray("data")
    }

    private fun emit(line: String) {
        synchronized(lock) {
            sweepOut.add(line)
            if (sweepOut.size > SWEEP_LINES) sweepOut.removeAt(0)
        }
    }

    private fun appendSweep(sb: StringBuilder) {
        val snapshot = synchronized(lock) { Pair(sweepOut.toList(), sweepDone) }
        sb.appendLine("--- sweep results (${snapshot.first.size} lines, done=${snapshot.second}) ---")
        snapshot.first.takeLast(120).forEach { sb.appendLine(it) }
    }

    private fun appendServerLog(sb: StringBuilder) {
        val tail = synchronized(lock) { serverLog.toString().takeLast(LOG_CHARS) }
        sb.appendLine("--- server log (tail, password redacted) ---")
        sb.appendLine(tail)
    }

    /** Returns (HTTP code, body). Basic auth only when a password is given. */
    private fun request(
        base: String,
        password: String?,
        method: String,
        path: String,
        body: String?,
        timeoutMs: Int,
    ): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = timeoutMs
        if (password != null) {
            val token = Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
