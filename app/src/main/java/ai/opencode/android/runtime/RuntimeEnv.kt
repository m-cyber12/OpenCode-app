package ai.opencode.android.runtime

import java.io.File

/**
 * Builds the environment the OpenCode server runs in. Mirrors the proven
 * Phase 2/3 gate layout, relocated to app-private storage:
 *
 *   - HOME / XDG_* / TMPDIR point inside filesDir so SQLite storage, config,
 *     cache and temp never touch shared storage (and match what
 *     xdg-basedir resolves to inside OpenCode).
 *   - PATH starts with the bin/ dir holding the symlinks to nativeLibraryDir
 *     (bun/git/rg) so OpenCode's `which`-style lookups find the REAL bundled
 *     tools, not system stand-ins.
 *   - SHELL=/system/bin/sh (Android mksh) — the verified Phase 3 shell.
 *   - Server auth uses an app-generated random password (never shipped).
 */
object RuntimeEnv {

    const val SERVER_PORT = 4111
    const val SERVER_USER = "opencode"

    /** The ONLY address the server may ever bind to (see LoopbackGuard). */
    const val SERVER_BIND_HOSTNAME = ai.opencode.android.client.LoopbackGuard.SERVER_BIND_HOSTNAME

    /**
     * @param hostnameOverride a requested bind address (never honoured unless it
     *   is loopback). Phase 5 has no "expose to LAN" switch by design; a hostile
     *   or accidental override in the app process env is refused and reported.
     */
    fun hostname(requested: String? = null): Pair<String, String?> =
        ai.opencode.android.client.LoopbackGuard.bindHostname(requested)

    fun build(
        paths: RuntimePaths,
        abi: String,
        password: String,
        hostname: String = SERVER_BIND_HOSTNAME,
    ): Map<String, String> {
        val env = HashMap(System.getenv())
        // Wipe anything from the app process that could confuse a Linux userspace.
        env["HOME"] = paths.home.absolutePath
        env["XDG_DATA_HOME"] = paths.xdgData.absolutePath
        env["XDG_CONFIG_HOME"] = paths.xdgConfig.absolutePath
        env["XDG_STATE_HOME"] = paths.xdgState.absolutePath
        env["XDG_CACHE_HOME"] = paths.xdgCache.absolutePath
        env["TMPDIR"] = paths.tmp.absolutePath
        env["PATH"] = listOf(
            paths.binDir.absolutePath,
            "/system/bin",
            "/system/xbin",
        ).joinToString(File.pathSeparator)
        env["SHELL"] = "/system/bin/sh"
        env["LANG"] = "C.UTF-8"
        // Loopback only: the value comes from RuntimeEnv.hostname(), which can
        // never return a non-loopback address (and the launcher re-checks it).
        env["OPENCODE_SERVER_HOSTNAME"] = hostname
        env["OPENCODE_SERVER_PORT"] = SERVER_PORT.toString()
        env["OPENCODE_SERVER_USERNAME"] = SERVER_USER
        env["OPENCODE_SERVER_PASSWORD"] = password
        env["OPENCODE_CLIENT"] = "android"
        env["OPENCODE_RUNTIME_ABI"] = abi
        // Explicit absolute paths for the launcher glue.
        env["OPENCODE_FILES_DIR"] = paths.filesDir.absolutePath
        env["OPENCODE_BUNDLE"] = paths.serverBundle.absolutePath
        // Native seccomp compatibility shim (jniLib -> nativeLibraryDir). The
        // exec shim sets this path as LD_PRELOAD before Bun is exec'd, so the
        // constructor is active before native startup. launcher.js also tries
        // bun:ffi as a backstop where that Bun build provides it.
        env["OPENCODE_SECCOMP_SHIM"] = File(paths.nativeLibraryDir, "libseccompshim.so").absolutePath
        return env
    }
}
