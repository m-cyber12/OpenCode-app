package ai.opencode.android.runtime

import java.io.File

/**
 * Launches and tracks the single OpenCode server process, and guarantees clean
 * teardown (no zombies, no duplicates).
 *
 * Launch: `libbun.so <filesDir>/runtime/launcher.js` directly from nativeLibraryDir
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
    val pid: Int get() = process?.pid() ?: -1

    fun start(env: Map<String, String>) {
        val bun = paths.bunBinary()
        require(bun.canExecute()) { "bun binary not executable: $bun" }
        require(paths.launcher.isFile) { "launcher missing: ${paths.launcher}" }

        // Duplicate-process prevention: if a stale pidfile points at a live
        // server for THIS payload, kill it before starting (also covers the
        // case of a crashed app leaving the server behind).
        killStaleServer()

        val pb = ProcessBuilder(bun.absolutePath, paths.launcher.absolutePath)
        pb.directory(paths.filesDir)
        pb.redirectErrorStream(false)
        pb.environment().apply {
            clear()
            putAll(env)
        }
        val p = pb.start()
        process = p
        paths.pidFile.writeText(p.pid().toString())
        logger.host("server started: pid=${p.pid()} abi=${env["OPENCODE_RUNTIME_ABI"]} port=${RuntimeEnv.SERVER_PORT}")
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
    fun stop(graceWindowMs: Long = 8000): Boolean {
        val p = process
        var exitedGracefully = false
        if (p != null) {
            val pid = p.pid()
            try {
                logger.host("sending SIGTERM to pid=$pid")
                android.system.Os.kill(pid, android.system.OsConstants.SIGTERM)
            } catch (t: Throwable) {
                logger.host("SIGTERM failed: ${t.message}; trying destroy()")
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
        process = null
        return code
    }

    /**
     * Finds and kills any leftover OpenCode server process owned by this app.
     * Matches cmdlines containing our launcher path under /proc (works without
     * root inside the app sandbox; ignores processes we can't signal).
     */
    fun killStaleServer() {
        val marker = paths.launcher.absolutePath
        val selfPid = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        var killed = 0
        for (proc in File("/proc").listFiles() ?: continue) {
            val pidStr = proc.name
            val pid = pidStr.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            try {
                val cmd = File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                if (cmd.contains(marker)) {
                    // Found a leftover server (or its bun) — SIGTERM then SIGKILL.
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
        val marker = paths.launcher.absolutePath
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
