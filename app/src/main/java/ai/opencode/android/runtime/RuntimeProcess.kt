package ai.opencode.android.runtime

import java.io.File

/**
 * Launches and tracks the single OpenCode server process, and guarantees clean
 * teardown (no zombies, no duplicates).
 *
 * Launch: `libnode26.so launcher-v2.mjs opencode.mjs` directly from nativeLibraryDir
 * (exec-allowed under W^X). stdout/stderr are streamed to the runtime log by
 * the supervisor thread (the process's stdio pipes are drained there so the
 * child never blocks on a full pipe).
 *
 * Stop: SIGTERM (the server handles it and flushes — proven G13 in Phase 3),
 * wait for a graceful window, then SIGKILL. A /proc sweep then kills any
 * leftover process whose cmdline references our launcher (orphans survive when
 * the direct Process handle is gone, e.g. a prior app process died with the
 * server still running).
 */
class RuntimeProcess(
    private val paths: RuntimePaths,
    private val logger: RuntimeLogger,
) {
    @Volatile var process: Process? = null
        private set

    val isAlive: Boolean get() = runCatching { process?.isAlive == true }.getOrDefault(false)

    /**
     * Resolve the server pid from /proc (android's java.lang.Process predates
     * Java 9's pid(); the server cmdline contains the launcher path).
     * Excludes our own process. -1 when no server is present.
     */
    fun findServerPid(): Int {
        val marker = activeMarker
        val selfPid = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        val procs = File("/proc").listFiles() ?: return -1
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            try {
                val cmd = File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                if (cmd.contains(marker)) return pid
            } catch (_: Throwable) {
            }
        }
        return -1
    }

    /** Current server pid (from /proc), or -1. */
    val pid: Int get() = findServerPid()

    /**
     * @param serverPassword handed to the launcher on stdin, one line, then the
     *   pipe is closed. It is never placed in [env]: the exec-time environment
     *   is readable by same-UID processes through /proc/<pid>/environ.
     */
    @Synchronized
    fun start(
        env: Map<String, String>,
        serverPassword: String,
        hostname: String = RuntimeEnv.SERVER_BIND_HOSTNAME,
    ) {
        // The live-server marker for /proc scans is the v2 launcher.
        activeMarker = paths.v2Launcher.absolutePath
        val command: List<String> = v2Command(env, hostname)
        // Duplicate-process prevention: kill any live server for THIS payload
        // before starting (also covers a crashed app leaving the server behind).
        killStaleServer()

        val pb = ProcessBuilder(command)
        pb.directory(paths.filesDir)
        pb.redirectErrorStream(false)
        pb.environment().apply {
            clear()
            putAll(commandEnv)
        }
        val p = pb.start()
        process = p
        // Deliver the password on the launcher's stdin (see launcher.js
        // readSecretLine, launcher-v2.mjs readPasswordLine). Closing the pipe ends the line.
        try {
            p.outputStream.use { out ->
                out.write((serverPassword + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: java.io.IOException) {
            logger.host("server launch: could not hand the password to the launcher (${e.message})")
            throw e
        }
        // The child may take a moment to appear in /proc; retry briefly.
        var sp = -1
        for (i in 0..20) {
            sp = findServerPid()
            if (sp > 0) break
            Thread.sleep(100)
        }
        if (sp > 0) paths.pidFile.writeText(sp.toString())
        logger.host("server started: pid=$sp engine=${ServerEngine.V2} abi=${env["OPENCODE_RUNTIME_ABI"]} port=${RuntimeEnv.SERVER_PORT}")
    }

    /** Environment for the child process; set by the command builders below. */
    private var commandEnv: Map<String, String> = emptyMap()

    /** Launcher path used to find a live server in /proc. */
    @Volatile
    private var activeMarker: String = paths.v2Launcher.absolutePath

    /** Node 26 runs the v2 launcher, which runs the v2 bundle. */
    private fun v2Command(env: Map<String, String>, hostname: String): List<String> {
        val node = paths.node26Binary()
        require(node.canExecute()) { "node26 binary not executable: $node" }
        require(paths.v2Launcher.isFile) { "v2 launcher missing: ${paths.v2Launcher}" }
        require(paths.v2Entry.isFile) { "v2 bundle missing: ${paths.v2Entry}" }
        commandEnv = HashMap(env)
        return listOf(
            node.absolutePath,
            "--experimental-ffi",
            "--disable-warning=ExperimentalWarning",
            paths.v2Launcher.absolutePath,
            paths.v2Entry.absolutePath,
            "--hostname",
            hostname,
            "--port",
            RuntimeEnv.SERVER_PORT.toString(),
        )
    }

    /** Diagnostics: whether the child we started is still running, or its exit code. */
    fun state(): String {
        val p = process ?: return "no process"
        return if (p.isAlive) "alive" else "exited code=${p.exitValue()}"
    }

    /** Drain child stdout/stderr into the host runtime log (prevents pipe blocking). */
    fun pumpStdio() {
        val p = process ?: return
        val t = Thread({
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.server(it) }
            }
        }, "opencode-stdout").apply { isDaemon = true; start() }
        Thread({
            p.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.server("[stderr] $it") }
            }
        }, "opencode-stderr").apply { isDaemon = true; start() }
    }

    /**
     * Graceful shutdown. Returns true if the process exited within the window.
     * SIGTERM first (server closes HTTP + DB cleanly), then SIGKILL, then a
     * /proc sweep for leftover children.
     */
    @Synchronized
    fun stop(graceWindowMs: Long = 8000): Boolean {
        val p = process
        var exitedGracefully = false
        if (p != null) {
            val pid = findServerPid()
            if (pid > 0) {
                try {
                    logger.host("sending SIGTERM to pid=$pid")
                    android.system.Os.kill(pid, android.system.OsConstants.SIGTERM)
                } catch (t: Throwable) {
                    logger.host("SIGTERM failed: ${t.message}; trying destroy()")
                    runCatching { p.destroy() }
                }
            } else {
                runCatching { p.destroy() }
            }
            val deadline = System.currentTimeMillis() + graceWindowMs
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    exitedGracefully = true
                    break
                }
                Thread.sleep(200)
            }
            if (p.isAlive) {
                logger.host("server did not exit in ${graceWindowMs}ms — SIGKILL")
                runCatching { p.destroyForcibly() }
                p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        val exitCode = p?.let { runCatching { it.exitValue() }.getOrNull() }
        if (exitCode != null) logger.host("server process exit code=$exitCode during stop")
        killStaleServer()
        process = null
        runCatching { paths.pidFile.delete() }
        logger.host("server stopped (graceful=$exitedGracefully)")
        return exitedGracefully
    }

    /** Block until the process exits; returns the exit code. */
    fun waitForExit(): Int {
        val p = process ?: return -1
        val code = p.waitFor()
        logger.host("server process exited code=$code")
        // A newer generation may already own the process slot. Do not clear
        // that process or delete its pidfile when an older waiter returns.
        if (process === p) {
            process = null
            runCatching { paths.pidFile.delete() }
        }
        return code
    }

    /**
     * Finds and kills any leftover OpenCode server process owned by this app.
     * Matches cmdlines containing our launcher path under /proc (works without
     * root inside the app sandbox; ignores processes we can't signal).
     */
    fun killStaleServer() {
        val marker = activeMarker
        val selfPid = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        var killed = 0
        val procList = File("/proc").listFiles() ?: return
        for (proc in procList) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            try {
                val cmd = File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                if (cmd.contains(marker)) {
                    // Found a leftover server — SIGTERM then SIGKILL.
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGTERM) }
                    Thread.sleep(300)
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                    killed++
                    logger.host("killed stale server process pid=$pid cmd=${cmd.take(120)}")
                }
            } catch (_: Throwable) {
                // process vanished / not ours — ignore
            }
        }
        if (killed > 0) logger.host("stale server cleanup: $killed process(es) removed")
    }

    fun countLiveServers(): Int {
        val marker = activeMarker
        val selfPid = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        var n = 0
        for (proc in File("/proc").listFiles() ?: return 0) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            try {
                val cmd = File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                if (cmd.contains(marker)) n++
            } catch (_: Throwable) {
            }
        }
        return n
    }
}
