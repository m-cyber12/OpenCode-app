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

    /**
     * v9.28: the device's CA-certificate directory, newest location first.
     * Returns null when neither exists (then git simply has no CAPATH and
     * https verification fails loudly instead of silently trusting nothing).
     */
    fun systemCaDir(): String? = listOf(
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts",
    ).firstOrNull { dir ->
        val f = File(dir)
        f.isDirectory && !f.list().isNullOrEmpty()
    }

    fun build(
        paths: RuntimePaths,
        abi: String,
        password: String,
        hostname: String = SERVER_BIND_HOSTNAME,
        githubToken: String? = null,
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
        // v9.28: the rebuilt git speaks https (curl + Mbed TLS). Its helper
        // (bin/git-remote-https -> libgitremotehttp.so) is found here, and
        // certificate trust comes from ANDROID'S OWN CA store - a directory
        // of PEM certs the TLS library loads wholesale, so trust follows the
        // device, not our build (Android 14+ keeps it in the Conscrypt APEX,
        // older in /system).
        env["GIT_EXEC_PATH"] = paths.binDir.absolutePath
        systemCaDir()?.let { env["GIT_SSL_CAPATH"] = it }
        // Loopback only: the value comes from RuntimeEnv.hostname(), which can
        // never return a non-loopback address (and the launcher re-checks it).
        env["OPENCODE_SERVER_HOSTNAME"] = hostname
        env["OPENCODE_SERVER_PORT"] = SERVER_PORT.toString()
        env["OPENCODE_SERVER_USERNAME"] = SERVER_USER
        env["OPENCODE_SERVER_PASSWORD"] = password
        env["OPENCODE_CLIENT"] = "android"
        // v9.31 (owner): upstream registers the `question` tool only for the
        // app/cli/desktop clients - OPENCODE_CLIENT=android silently dropped
        // it, so models "asking with options" got Invalid Tool on device.
        // The explicit flag turns it on regardless of the client name
        // (registry.ts: `... || flags.enableQuestionTool`).
        env["OPENCODE_ENABLE_QUESTION_TOOL"] = "1"
        env["OPENCODE_RUNTIME_ABI"] = abi
        // Explicit absolute paths for the launcher glue.
        env["OPENCODE_FILES_DIR"] = paths.filesDir.absolutePath
        env["OPENCODE_BUNDLE"] = paths.serverBundle.absolutePath
        // Native seccomp compatibility shim (jniLib -> nativeLibraryDir). The
        // exec shim sets this path as LD_PRELOAD before Bun is exec'd, so the
        // constructor is active before native startup. launcher.js also tries
        // bun:ffi as a backstop where that Bun build provides it.
        env["OPENCODE_SECCOMP_SHIM"] = File(paths.nativeLibraryDir, "libseccompshim.so").absolutePath
        // v9.23 (owner): the GitHub connector. The Keystore-held token rides
        // ONLY here - as the env value `key` of the local server process, so
        // the agent's shells inherit it (git clone/push via
        // https://x-access-token:$key@github.com/...). Deliberately absent
        // when not connected: an empty var would read as "connected but
        // broken" to the model.
        if (!githubToken.isNullOrBlank()) {
            env[ai.opencode.android.security.GithubConnector.ENV_VAR] = githubToken
        }
        return env
    }
}
