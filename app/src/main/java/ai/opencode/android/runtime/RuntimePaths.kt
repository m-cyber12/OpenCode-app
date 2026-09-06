package ai.opencode.android.runtime

import android.content.Context
import java.io.File

/**
 * Filesystem layout for the embedded runtime. Everything lives in app-private
 * storage — nothing user-visible, nothing requiring manual setup.
 *
 *   filesDir/
 *     bin/                 symlinks bun/git/rg -> nativeLibraryDir (exec-allowed)
 *     runtime/             extraction marker and host metadata
 *     runtime/.extracted   extraction marker (payload version + manifest sha)
 *     launcher.js          bun entrypoint that imports the server bundle
 *     workspaces/          user projects (the agent's cwd roots)
 *     log/runtime.log      supervisor lifecycle + stdout/stderr of the server
 *     log/crashes/         one file per unexpected server death
 *     diagnostics/         collected diagnostic bundles (shareable)
 *     secrets/server-password, secrets/openrouter-api-key
 *   (XDG dirs live under filesDir/xdg/ so config/data/cache/state are app-private)
 *
 * Executables do NOT live under filesDir: on API 29+ the app home dir is
 * mounted no-exec (W^X). They ship as JNI libs and are executed from
 * [Context.getApplicationInfo].nativeLibraryDir via the bin/ symlinks.
 */
class RuntimePaths private constructor(context: Context) {

    val filesDir: File = context.filesDir
    val nativeLibraryDir: File = File(context.applicationInfo.nativeLibraryDir)

    val binDir: File = File(filesDir, "bin")
    // The payload is extracted FLAT into filesDir (matching the proven Phase 3
    // layout): filesDir/launcher.js, filesDir/node_modules/<jsonc-parser,
    // node-pty, bun-pty>, filesDir/opencode/dist/node/node.js. Bun resolves
    // node_modules by walking up from the bundle dir; the bundle sits in
    // filesDir/opencode/dist/node/, so it finds filesDir/node_modules. The
    // extraction marker and staging still live in filesDir/runtime/ which is
    // NOT part of the payload tarball (it's host metadata).
    val runtimeDir: File = File(filesDir, "runtime")
    val extractionMarker: File = File(runtimeDir, ".extracted")
    val launcher: File = File(filesDir, "launcher.js")
    val nodeModulesDir: File = File(filesDir, "node_modules")

    val xdgData: File = File(filesDir, "xdg/data")
    val xdgConfig: File = File(filesDir, "xdg/config")
    val xdgState: File = File(filesDir, "xdg/state")
    val xdgCache: File = File(filesDir, "xdg/cache")
    val tmp: File = File(filesDir, "xdg/tmp")
    val home: File = File(filesDir, "home")
    val workspaces: File = File(filesDir, "workspaces")

    val logDir: File = File(filesDir, "log")
    val runtimeLog: File = File(logDir, "runtime.log")
    val crashDir: File = File(logDir, "crashes")
    val diagnosticsDir: File = File(filesDir, "diagnostics")

    val secretsDir: File = File(filesDir, "secrets")
    /**
     * Only ever read to migrate a pre-Phase-5 plaintext password into the
     * Keystore (see [Secrets.serverPassword]); the app no longer writes it.
     */
    val serverPasswordFile: File = File(secretsDir, "server-password")
    /** Keystore-encrypted secret blobs (one per secret name). */
    val secretBlobs: File = File(secretsDir, ".")

    /**
     * Test-harness scratch dir. Production code never writes a secret here; the
     * instrumentation APK exports the loopback password into it (only when a
     * `harness/enabled` marker exists) so the host-side JS gate drivers can talk
     * to the app's server with the same credentials the app uses.
     */
    val harnessDir: File = File(filesDir, "harness")
    val harnessMarker: File = File(harnessDir, "enabled")
    val harnessPasswordFile: File = File(harnessDir, "server-password")

    /** Evidence written by the runtime itself (loopback bind audit). */
    val loopbackAuditFile: File = File(logDir, "loopback-audit.txt")
    val pidFile: File = File(filesDir, "runtime.pid")

    val bunLink: File = File(binDir, "bun")
    val gitLink: File = File(binDir, "git")
    val rgLink: File = File(binDir, "rg")

    /** The actual bun executable in nativeLibraryDir (installed by the package manager). */
    fun bunBinary(): File = File(nativeLibraryDir, "libbun.so")
    /** Real Git executable built against the Android NDK/Bionic libc. */
    fun gitBinary(): File = File(nativeLibraryDir, "libgit.so")
    /** Real ripgrep executable built for the Android ABI with the NDK linker. */
    fun rgBinary(): File = File(nativeLibraryDir, "librg.so")
    /** Retained diagnostic compatibility wrapper; tool lookup does not use it. */
    fun childShimBinary(): File = File(nativeLibraryDir, "libchildshim.so")
    /** PIE wrapper that installs the seccomp SIGSYS handler then execs bun. */
    fun execShimBinary(): File = File(nativeLibraryDir, "libexecshim.so")

    // Flat layout (matches the proven Phase 3 gate): bundle and node_modules
    // are direct children of filesDir; runtimeDir holds only host metadata.
    val serverBundle: File = File(filesDir, "opencode/dist/node/node.js")
    val nodeModules: File = File(filesDir, "node_modules")

    // OpenCode creates these at startup. Pre-create them in the host because
    // the app-uid seccomp filter denies the access() probe Bun uses to check a
    // path (mapped to ENOSYS by the seccomp shim), which otherwise makes Bun
    // decide an existing XDG dir is absent and then mkdir() it itself.
    val xdgDataOpencode: File = File(xdgData, "opencode")
    val xdgConfigOpencode: File = File(xdgConfig, "opencode")
    val xdgStateOpencode: File = File(xdgState, "opencode")
    val xdgCacheOpencode: File = File(xdgCache, "opencode")
    val xdgStateOpencodeLog: File = File(xdgStateOpencode, "log")

    fun ensureDirs() {
        listOf(
            binDir, runtimeDir, xdgData, xdgConfig, xdgState, xdgCache, tmp, home,
            workspaces, logDir, crashDir, diagnosticsDir, secretsDir,
            // OpenCode app-data subtree (pre-created so Bun never has to mkdir
            // under the app seccomp filter):
            xdgDataOpencode, xdgConfigOpencode, xdgStateOpencode, xdgCacheOpencode,
            xdgStateOpencodeLog,
        ).forEach { it.mkdirs() }
    }

    companion object {
        @Volatile
        private var instance: RuntimePaths? = null

        fun get(context: Context): RuntimePaths =
            instance ?: synchronized(this) {
                instance ?: RuntimePaths(context.applicationContext).also { instance = it }
            }
    }
}
