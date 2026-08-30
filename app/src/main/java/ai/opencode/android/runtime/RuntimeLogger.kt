package ai.opencode.android.runtime

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single lifecycle log for the runtime host. Everything the supervisor,
 * extractor and process layer do is appended here (bounded, rotated) so the
 * diagnostics screen / export can show what happened without logcat access.
 * The OpenCode server's own stdout/stderr is streamed separately to
 * [RuntimePaths.runtimeLog] too (see RuntimeProcess) — one file, host and
 * server lines interleaved with tags.
 */
class RuntimeLogger(private val file: File) {

    private val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val maxBytes = 2L * 1024 * 1024   // rotate at 2 MiB, keep one .prev

    @Synchronized
    fun log(tag: String, msg: String) {
        try {
            if (file.exists() && file.length() > maxBytes) {
                File(file.parentFile, file.name + ".prev").let { prev ->
                    prev.delete()
                    file.renameTo(prev)
                }
            }
            file.parentFile?.mkdirs()
            file.appendText("${ts.format(Date())} [$tag] $msg\n")
        } catch (t: Throwable) {
            Log.w("RuntimeLogger", "log write failed: ${t.message}")
        }
        Log.i("OpenCode/$tag", msg)
    }

    fun host(msg: String) = log("host", msg)
    fun server(msg: String) = log("server", msg)

    @Synchronized
    fun tail(bytes: Long = 64 * 1024): String {
        if (!file.exists()) return "(no runtime log yet)"
        val len = file.length()
        val skip = (len - bytes).coerceAtLeast(0)
        file.inputStream().use { input ->
            val skipped = input.skip(skip)
            val buf = ByteArray((len - skipped).toInt().coerceAtLeast(0))
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            return String(buf, 0, read)
        }
    }

    fun crash(message: String) {
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date()) + ".log"
        try {
            File(file.parentFile, "crashes").mkdirs()
            File(File(file.parentFile, "crashes"), name).writeText(
                "time=${Date()}\n$message\n\n--- runtime log tail ---\n${tail(32 * 1024)}\n",
            )
        } catch (t: Throwable) {
            Log.w("RuntimeLogger", "crash write failed: ${t.message}")
        }
        log("crash", message)
    }
}
