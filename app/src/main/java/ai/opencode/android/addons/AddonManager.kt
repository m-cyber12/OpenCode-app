package ai.opencode.android.addons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v9.24: the I/O half of Add-ons (the pure half is [Addons]). One download at
 * a time - these files are hundreds of MB on a phone connection, and the only
 * catalog entry today is a single tarball anyway.
 *
 * The state is one immutable snapshot; the screen renders it and nothing
 * else. Progress is reported in bytes so the UI can show a DETERMINATE bar
 * when the size is known and plain text when it is not (owner rule: finite
 * animations only - no indeterminate spinners).
 */
class AddonManager(
    private val workspaceRoot: () -> File,
    private val registryBase: String,
) {

    data class Installed(val fileName: String, val sizeBytes: Long)

    data class State(
        val phase: Phase = Phase.IDLE,
        /** Which catalog id is being worked on, "" when idle. */
        val activeId: String = "",
        val receivedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String = "",
        val installed: List<Installed> = emptyList(),
    )

    enum class Phase { IDLE, RESOLVING, DOWNLOADING, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun refresh() {
        _state.value = _state.value.copy(installed = listInstalled())
    }

    /** Start downloading one catalog entry at the given version/dist-tag. */
    fun download(spec: Addons.AddonSpec, versionOrTag: String = "latest") {
        if (job?.isActive == true) return // one at a time, by design
        _state.value = _state.value.copy(
            phase = Phase.RESOLVING, activeId = spec.id,
            receivedBytes = 0, totalBytes = 0, error = "",
        )
        job = scope.launch {
            try {
                val manifest = Addons.parseManifest(
                    fetchText(Addons.manifestUrl(registryBase, spec.npmPackage, versionOrTag.trim().ifEmpty { "latest" })),
                )
                if (!Addons.isSafeVersion(manifest.version)) error("registry returned unusable version '${manifest.version}'")
                val dir = Addons.dir(workspaceRoot()).apply { mkdirs() }
                val target = File(dir, Addons.tarballName(spec, manifest.version))
                if (!target.isFile) {
                    val part = File(dir, target.name + ".part")
                    downloadTo(manifest.tarballUrl, part)
                    if (!part.renameTo(target)) error("could not finalize ${target.name}")
                }
                _state.value = _state.value.copy(
                    phase = Phase.IDLE, activeId = "", installed = listInstalled(),
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    _state.value = _state.value.copy(phase = Phase.IDLE, activeId = "", installed = listInstalled())
                    throw t
                }
                _state.value = _state.value.copy(
                    phase = Phase.ERROR, activeId = spec.id,
                    error = t.message ?: t.javaClass.simpleName,
                    installed = listInstalled(),
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(fileName: String) {
        // Only names we created: a plain `<id>-<version>.tgz`, never a path.
        if (fileName.contains('/') || fileName.contains('\\') || !fileName.endsWith(".tgz")) return
        File(Addons.dir(workspaceRoot()), fileName).delete()
        refresh()
    }

    // ---- plumbing -----------------------------------------------------------

    private fun listInstalled(): List<Installed> =
        Addons.installed(workspaceRoot()).map { Installed(it.name, it.length()) }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json, */*")
        }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) error("registry answered HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadTo(url: String, part: File) {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) error("download answered HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            _state.value = _state.value.copy(phase = Phase.DOWNLOADING, receivedBytes = 0, totalBytes = total)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var sinceReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            part.delete()
                            throw kotlinx.coroutines.CancellationException("cancelled")
                        }
                        out.write(buf, 0, n)
                        received += n
                        sinceReport += n
                        if (sinceReport >= 512 * 1024) { // report every 0.5 MB, not every read
                            sinceReport = 0
                            _state.value = _state.value.copy(receivedBytes = received, totalBytes = total)
                        }
                    }
                    _state.value = _state.value.copy(receivedBytes = received, totalBytes = total)
                }
            }
        } catch (t: Throwable) {
            runCatching { part.delete() }
            throw t
        } finally {
            conn.disconnect()
        }
    }
}
