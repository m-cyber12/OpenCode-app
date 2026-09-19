package ai.opencode.android.runtime

import android.content.Context
import java.io.File

/**
 * Filesystem layout for the embedded runtime. Runtime internals live in
 * app-private storage — nothing user-visible, nothing requiring manual setup.
 *
 *   filesDir/
 *     bin/                 symlinks bun/git/rg -> nativeLibraryDir (exec-allowed)
 *     runtime/             extraction marker and host metadata
 *     runtime/.extracted   extraction marker (payload version + manifest sha)
 *     launcher.js          bun entrypoint that imports the server bundle
 *     workspaces/          LEGACY project root (pre-Phase-10 installs); migrated
 *     log/runtime.log      supervisor lifecycle + stdout/stderr of the server
 *     log/crashes/         one file per unexpected server death
 *     diagnostics/         collected diagnostic bundles (shareable)
 *     secrets/server-password, secrets/openrouter-api-key
 *   (XDG dirs live under filesDir/xdg/ so config/data/cache/state are app-private)
 *
 * PHASE 10 CONTINUATION — where the user's projects live:
 *
 * `workspaces` is NOT under filesDir any more unless external storage is
 * unavailable. Projects move to the app-specific *external* directory:
 *
 *   /storage/emulated/0/Android/data/<applicationId>/files/workspaces/<project>
 *
 * Why: `filesDir` is `/data/data/<applicationId>/files`, which no file manager,
 * no MTP/USB browse and no non-root `adb shell` can see at all — the app looked
 * like a sealed black box even though the agent really was writing files. The
 * app-specific external directory needs no permission on any supported API
 * level, is readable by `adb shell`/`adb pull` on a stock non-rooted device, and
 * is where a user (or a desktop tool) expects an app's files to be. It is still
 * private to the app in the sense that matters for Phase 7's isolation: the
 * agent can only ever reach the project directories the app hands it, and
 * upstream's own `FSUtil.contains` guard still refuses `../` escapes (W1-W3
 * re-verified against this root by the Phase 10 device gates).
 *
 * What it does NOT give you (recorded here so nobody re-discovers it): on
 * Android 11+ the platform blocks *apps* from browsing `Android/data` of other
 * apps, so a file manager cannot open this directory on a modern phone. That is
 * why the app also offers an in-app file browser and a SAF "publish to a folder
 * you choose" action; see docs/progress/phase10-signing-publish-prep-report.md.
 *
 * `internalWorkspaces` is the pre-Phase-10 location, kept as the migration
 * source only.
 *
 * Executables do NOT live under filesDir: on API 29+ the app home dir is
 * mounted no-exec (W^X). They ship as JNI libs and are executed from
 * [Context.getApplicationInfo].nativeLibraryDir via the bin/ symlinks.
 */
class RuntimePaths private constructor(
    filesDir: File,
    nativeLibraryDir: File,
    externalFilesDir: File?,
) {

    val filesDir: File = filesDir
    val nativeLibraryDir: File = nativeLibraryDir

    /** The pre-Phase-10 project root. Read only, to migrate an existing install. */
    val internalWorkspaces: File = File(filesDir, WORKSPACES_NAME)

    /**
     * The app-specific external root, when the device has usable external
     * storage. `Context.getExternalFilesDir(null)` already creates the directory
     * and returns null when the volume is not mounted, so the null check is the
     * availability check.
     */
    val externalFilesDir: File? = externalFilesDir
    val externalWorkspaces: File? = externalFilesDir?.let { File(it, WORKSPACES_NAME) }

    /**
     * Where projects really live. External when available (the Phase 10
     * continuation change), internal otherwise — a device with no mounted
     * external storage still gets a working app, it just gets the old,
     * harder-to-reach location.
     */
    val workspaces: File = externalWorkspaces ?: internalWorkspaces

    /** True when [workspaces] is the user-visible (adb/PC-reachable) location. */
    val workspacesAreExternal: Boolean = externalWorkspaces != null

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
        /** Directory name used for the project root under both layouts. */
        const val WORKSPACES_NAME = "workspaces"

        @Volatile
        private var instance: RuntimePaths? = null

        fun get(context: Context): RuntimePaths =
            instance ?: synchronized(this) {
                instance ?: RuntimePaths(
                    context.filesDir,
                    File(context.applicationInfo.nativeLibraryDir),
                    // Creates the directory when the volume is mounted, null when
                    // it is not: the availability probe the layout keys off.
                    runCatching { context.getExternalFilesDir(null) }.getOrNull(),
                ).also { instance = it }
            }

        /**
         * Test-only constructor for JVM unit tests (Phase 8: environment
         * construction is a unit-test matrix item). Production code always goes
         * through [get]; this never registers the singleton.
         *
         * [externalFilesDir] defaults to null, i.e. "no external storage" — the
         * pre-Phase-10 layout, which is what the Phase 8 environment tests assert.
         */
        fun forTesting(
            filesDir: File,
            nativeLibraryDir: File,
            externalFilesDir: File? = null,
        ): RuntimePaths = RuntimePaths(filesDir, nativeLibraryDir, externalFilesDir)
    }
}
