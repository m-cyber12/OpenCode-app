package ai.opencode.android.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.min
import kotlin.random.Random

/**
 * The runtime supervisor. Owns the full lifecycle and is the single source of
 * truth for the service and the UI:
 *
 *   ABI gate -> ensure dirs/secrets -> extract+validate payload ->
 *   create bin symlinks -> start server -> verify HEALTH (HTTP, not just
 *   "launched") -> watch; on unexpected exit, record a crash and restart with
 *   bounded exponential backoff; on stop, graceful SIGTERM then SIGKILL plus a
 *   /proc sweep so no zombies/duplicates remain.
 *
 * Duplicate-process prevention is enforced three ways: the single
 * RuntimeService instance, a pidfile + /proc sweep before every start
 * ([RuntimeProcess.killStaleServer]), and the server's own port bind failing
 * if a twin ever slipped through.
 */
class RuntimeManager private constructor(private val appContext: Context) {

    private val paths = RuntimePaths.get(appContext)
    private val logger = RuntimeLogger(paths.runtimeLog)
    private val extractor = PayloadExtractor(appContext, paths, logger)
    private val process = RuntimeProcess(paths, logger)
    private val health = HealthChecker()

    private val _state = MutableStateFlow(RuntimeState(RuntimeStatus.STOPPED, "idle"))
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Volatile private var supervisorThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var generation = 0   // bumped on stop/reset; invalidates old supervisor loops
    @Volatile var abi: String? = null
        private set
    @Volatile var manifest: RuntimeManifest? = null
        private set
    private val restartLock = Any()

    private fun publish(status: RuntimeStatus, detail: String, restarts: Int? = null) {
        val cur = _state.value
        _state.value = RuntimeState(
            status = status,
            detail = detail,
            abi = abi,
            restartCount = restarts ?: cur.restartCount,
            manifest = manifest ?: cur.manifest,
            device = cur.device,
        )
        logger.host("state -> $status ($detail)")
    }

    /** Called by RuntimeService on start (idempotent). */
    fun start() {
        synchronized(restartLock) {
            if (running) {
                logger.host("start() ignored: supervisor already running")
                return
            }
            running = true
            generation++
        }
        val gen = generation
        supervisorThread = Thread({ supervise(gen) }, "opencode-supervisor").apply { isDaemon = true; start() }
    }

    /** Graceful stop (idempotent). Never blocks the calling (main) thread. */
    fun stop() {
        synchronized(restartLock) {
            if (!running) {
                logger.host("stop() ignored: not running")
                return
            }
            running = false
            generation++
        }
        Thread({
            try {
                process.stop()
                publish(RuntimeStatus.STOPPED, "stopped by user")
            } catch (t: Throwable) {
                logger.host("stop error: ${t.message}")
            }
        }, "opencode-stop").apply { isDaemon = true; start() }
    }

    /** Wipe extracted payload + data and bring the runtime back up (recovery test hook). */
    fun resetAndRestart() {
        synchronized(restartLock) {
            running = false
            generation++
        }
        Thread({
            try {
                logger.host("resetAndRestart: stopping + wiping runtime dir")
                process.stop()
                paths.runtimeDir.deleteRecursively()
                paths.extractionMarker.delete()
                val gen: Int
                synchronized(restartLock) {
                    running = true
                    generation++
                    gen = generation
                }
                supervise(gen)
            } catch (t: Throwable) {
                logger.host("resetAndRestart error: ${t.message}")
            }
        }, "opencode-reset").apply { isDaemon = true; start() }
    }

    private fun supervise(gen: Int) {
        // A newer start/stop/reset invalidates this loop.
        if (gen != generation) return
        try {
            // ---- 1. ABI / device gate -------------------------------------
            val gate = AbiGate.evaluate()
            if (gate is AbiGate.Result.Unsupported) {
                logger.host("ABI gate: UNSUPPORTED — ${gate.reason}")
                _state.value = _state.value.copy(
                    status = RuntimeStatus.UNSUPPORTED_DEVICE,
                    detail = gate.reason,
                    device = gate.device,
                )
                running = false
                return
            }
            val ok = gate as AbiGate.Result.Ok
            abi = ok.abi
            _state.value = _state.value.copy(abi = ok.abi, device = ok.device)
            logger.host("ABI gate: ok abi=${ok.abi} sdk=${ok.device.sdkInt} model=${ok.device.manufacturer} ${ok.device.model}")

            paths.ensureDirs()

            // ---- 2. extraction + validation -------------------------------
            publish(RuntimeStatus.EXTRACTING, "validating embedded runtime")
            val ext = extractor.ensureExtracted()
            if (ext is PayloadExtractor.Result.Failed) {
                // Corruption we cannot recover from = the APK payload itself is
                // unreadable; report clearly rather than looping.
                publish(RuntimeStatus.FATAL, "runtime extraction failed: ${ext.reason}")
                running = false
                return
            }
            manifest = when (ext) {
                is PayloadExtractor.Result.Extracted -> ext.manifest
                is PayloadExtractor.Result.AlreadyValid -> ext.manifest
                else -> null
            }
            logger.host(
                "payload: opencode ${manifest?.opencodeVersion} @ ${manifest?.opencodeCommit?.take(7)} " +
                    "bun ${manifest?.bunVersion} git ${manifest?.gitVersion} rg ${manifest?.rgVersion}",
            )

            // ---- 3. bin symlinks (exec from nativeLibraryDir) ------------
            ensureBinSymlinks()

            val password = Secrets.ensureServerPassword(paths, logger)
            val apiKey = Secrets.readApiKey(paths)
            val env = RuntimeEnv.build(paths, ok.abi, password, apiKey)

            // ---- 4. start / health / crash loop --------------------------
            var attempts = 0
            while (running && gen == generation) {
                attempts++
                publish(RuntimeStatus.STARTING, "starting OpenCode server (attempt $attempts)", attempts - 1)
                try {
                    process.start(env)
                    process.pumpStdio()
                } catch (t: Throwable) {
                    logger.crash("failed to launch server process: ${t.message}")
                    if (!backoffOrGiveUp(attempts)) break
                    continue
                }

                val h = health.waitHealthy(password, timeoutMs = 45_000)
                if (h != null && h.healthy) {
                    logger.host("health check OK: $h")
                    publish(RuntimeStatus.HEALTHY, "healthy on 127.0.0.1:${RuntimeEnv.SERVER_PORT}", attempts - 1)
                    attempts = 0  // reset backoff after a confirmed healthy run
                } else {
                    logger.crash("server did not become healthy: $h")
                    // Process may still be (sickly) alive — kill it before retry.
                    process.stop(graceWindowMs = 2000)
                    if (!backoffOrGiveUp(attempts)) break
                    continue
                }

                // Watch until exit (blocking wait). Exit while `running` is a crash.
                val code = process.waitForExit()
                if (!running) {
                    publish(RuntimeStatus.STOPPED, "stopped")
                    break
                }
                logger.crash("server exited unexpectedly code=$code")
                val servers = process.countLiveServers()
                if (servers > 0) {
                    logger.host("found $servers leftover server process(es) after exit — sweeping")
                    process.killStaleServer()
                }
                publish(RuntimeStatus.CRASHED_RESTARTING, "server exited (code=$code); restarting", attempts)
                if (!backoffOrGiveUp(attempts)) break
            }
        } catch (t: Throwable) {
            logger.host("supervisor fatal error: ${t.message}\n${t.stackTraceToString().take(2000)}")
            publish(RuntimeStatus.FATAL, "supervisor error: ${t.message}")
            running = false
        }
    }

    /** Exponential backoff between restarts. Returns false to give up. */
    private fun backoffOrGiveUp(attempts: Int): Boolean {
        val maxAttempts = 8
        if (attempts >= maxAttempts) {
            publish(RuntimeStatus.FATAL, "runtime failed $attempts start attempts — giving up (see diagnostics)")
            running = false
            return false
        }
        val base = 1000L
        val cap = 30_000L
        val exp = min(cap, base shl minOf(attempts - 1, 16))
        val sleep = (exp * (0.5 + Random.nextDouble())).toLong().coerceIn(500, cap)
        logger.host("restart backoff: ${sleep}ms (attempt $attempts/$maxAttempts)")
        val deadline = System.currentTimeMillis() + sleep
        while (running && System.currentTimeMillis() < deadline) Thread.sleep(250)
        return running
    }

    /**
     * Create bin/bun, bin/git, bin/rg as symlinks into nativeLibraryDir.
     * Symlinks are files in filesDir (noexec) but the KERNEL resolves them to
     * the nativeLibraryDir target, which is exec-allowed — the standard Android
     * packaging trick for shipping extra executables inside the APK.
     */
    private fun ensureBinSymlinks() {
        paths.binDir.mkdirs()
        data class Link(val link: File, val target: File, val name: String)
        val links = listOf(
            Link(paths.bunLink, paths.bunBinary(), "bun"),
            Link(paths.gitLink, paths.gitBinary(), "git"),
            Link(paths.rgLink, paths.rgBinary(), "rg"),
        )
        for (l in links) {
            try {
                if (l.link.exists()) {
                    val canon = runCatching { l.link.canonicalPath }.getOrNull()
                    if (canon == l.target.absolutePath && l.target.canExecute()) continue
                    l.link.delete()
                }
                // os.symlink via reflection-free API (API 21+):
                android.system.Os.symlink(l.target.absolutePath, l.link.absolutePath)
                logger.host("symlink ${l.name} -> ${l.target.absolutePath} (exec=${l.target.canExecute()})")
            } catch (t: Throwable) {
                logger.host("symlink ${l.name} failed: ${t.message}")
            }
        }
    }

    fun diagnostics(): Diagnostics = Diagnostics.collect(appContext, paths, logger, _state.value)

    companion object {
        @Volatile private var instance: RuntimeManager? = null

        fun get(context: Context): RuntimeManager =
            instance ?: synchronized(this) {
                instance ?: RuntimeManager(context.applicationContext).also { instance = it }
            }
    }
}
