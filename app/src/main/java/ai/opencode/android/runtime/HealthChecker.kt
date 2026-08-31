package ai.opencode.android.runtime

import java.net.HttpURLConnection
import java.net.URL

/**
 * Health is a verified HTTP response, not merely "process launched".
 * Polls /global/health on loopback with Basic auth (the same check the
 * Electron desktop sidecar uses against its forked server).
 */
class HealthChecker(
    private val port: Int = RuntimeEnv.SERVER_PORT,
    private val user: String = RuntimeEnv.SERVER_USER,
) {
    data class Health(val healthy: Boolean, val code: Int, val body: String) {
        override fun toString(): String = "healthy=$healthy http=$code body=${body.take(200)}"
    }

    fun probe(password: String, timeoutMs: Int = 4000): Health {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://127.0.0.1:$port/global/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                val cred = android.util.Base64.encodeToString(
                    "$user:$password".toByteArray(),
                    android.util.Base64.NO_WRAP,
                )
                setRequestProperty("Authorization", "Basic $cred")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val healthy = code in 200..299 && body.contains("\"healthy\"", ignoreCase = true)
            Health(healthy, code, body)
        } catch (t: Throwable) {
            Health(false, -1, t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Wait for health after a start. Returns the first healthy probe, or null
     * after [timeoutMs]. Bounded — never blocks forever.
     */
    fun waitHealthy(password: String, timeoutMs: Long = 45_000, intervalMs: Long = 1000): Health? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Health? = null
        while (System.currentTimeMillis() < deadline) {
            val h = probe(password)
            last = h
            if (h.healthy) return h
            Thread.sleep(intervalMs)
        }
        return last
    }
}
