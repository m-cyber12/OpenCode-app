package ai.opencode.android.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Test-build probe for the Node 26 Android spike (spike/node26/README.md).
 *
 * It runs only when the APK carries lib/arm64-v8a/libnode26.so, which the
 * phase10 pipeline adds to the TEST-ONLY copy and never to the release APK.
 * Without that file it reports "not bundled". Its output is listed in
 * Settings > Diagnostics only. Nothing else in the app calls it.
 */
internal object Node26Probe {
    private const val BINARY = "libnode26.so"
    private const val TIMEOUT_SECONDS = 15L
    private const val MAX_OUTPUT_CHARS = 2000

    fun report(nativeLibraryDir: File, filesDir: File): String {
        val bin = File(nativeLibraryDir, BINARY)
        if (!bin.isFile) return "not bundled (no $BINARY in nativeLibraryDir)"
        val sb = StringBuilder()
        sb.appendLine("file=${bin.absolutePath} size=${bin.length()} exec=${bin.canExecute()}")
        sb.appendLine(run(bin, nativeLibraryDir, filesDir, "--version"))
        sb.appendLine(
            run(
                bin, nativeLibraryDir, filesDir, "-p",
                "JSON.stringify({v: process.version, arch: process.arch, icu: process.versions.icu, " +
                    "v8: process.versions.v8, tz: Intl.DateTimeFormat().resolvedOptions().timeZone, " +
                    "segments: [...new Intl.Segmenter('en', {granularity: 'word'}).segment('ok go')].length})",
            ),
        )
        return sb.toString().trimEnd()
    }

    private fun run(bin: File, libDir: File, home: File, vararg args: String): String {
        val label = "args=${args.joinToString(" ")}"
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true)
            pb.directory(home)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", libDir.absolutePath)
                put("HOME", home.absolutePath)
                put("TMPDIR", home.absolutePath)
            }
            val proc = pb.start()
            val exited = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) proc.destroyForcibly()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.take(MAX_OUTPUT_CHARS)
            val exit = if (exited) proc.exitValue().toString() else "TIMEOUT"
            "$label exit=$exit output=${out.trim()}"
        } catch (e: Exception) {
            "$label error=${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
