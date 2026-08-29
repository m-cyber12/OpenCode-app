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

    /**
     * Resolve the server pid from /proc (android's java.lang.Process predates
     * Java 9's pid(); the bun child cmdline contains the launcher path).
     * Excludes our own process. -1 when no server is present.
     */
    fun findServerPid(): Int {
        val marker = paths.launcher.absolutePath
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

    fun start(env: Map<String, String>) {
        val bun = paths.bunBinary()
        require(bun.canExecute()) { "bun binary not executable: $bun" }
        require(paths.launcher.isFile) { "launcher missing: ${paths.launcher}" }

        // Launch through the PIE exec shim (nativeLibraryDir/libexecshim.so).
        // It LD_PRELOADs libseccompshim.so (constructor installs the SIGSYS ->
        // ENOSYS handler before bun's native init) and then execv()s bun. Direct
        // exec of bun dies with SIGSYS during its own startup (syscall 21
        // access / 441 epoll_pwait2), before any JS or bun:ffi hook can run.
        val exec = paths.execShimBinary()
        if (!exec.canExecute()) {
            // Fallback: direct bun (older payloads without the shim).
            logger.host("exec shim missing ($exec); launching bun directly")
        }
        val entry = if (exec.canExecute()) exec.absolutePath else bun.absolutePath

        // Duplicate-process prevention: kill any live server for THIS payload
        // before starting (also covers a crashed app leaving the server behind).
        killStaleServer()

        val fullEnv = HashMap(env)
        // Tell the shim what to exec and where the preload handler lives.
        fullEnv["OPENCODE_BUN_EXEC"] = bun.absolutePath
        fullEnv["OPENCODE_SECCOMP_SHIM"] =
            java.io.File(paths.nativeLibraryDir, "libseccompshim.so").absolutePath

        val pb = ProcessBuilder(entry, paths.launcher.absolutePath)
        pb.directory(paths.filesDir)
        pb.redirectErrorStream(false)
        pb.environment().apply {
            clear()
            putAll(fullEnv)
        }
        val p = pb.start()
        process = p
        // The child may take a moment to appear in /proc; retry briefly.
        var sp = -1
        for (i in 0..20) {
            sp = findServerPid()
            if (sp > 0) break
            Thread.sleep(100)
        }
        if (sp > 0) paths.pidFile.writeText(sp.toString())
        logger.host("server started: pid=$sp abi=${env["OPENCODE_RUNTIME_ABI"]} port=${RuntimeEnv.SERVER_PORT}")
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
        val procList = File("/proc").listFiles() ?: return
        for (proc in procList) {
            val pid = proc.name.toIntOrNull() ?: continue
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
