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
 * WHERE THE USER'S PROJECTS LIVE — the question this class exists to answer
 * honestly, because three different locations have been tried and the platform
 * treats them very differently:
 *
 *  1. [publicWorkspaces] — `/storage/emulated/0/Documents/OpenCode/<project>`.
 *     THE DEFAULT. A real, shared-storage directory: any file manager, the Files
 *     app, MTP, `adb shell`/`adb pull` and a desktop file browser see the project
 *     files live, with no export step. Writing there by path needs
 *     `MANAGE_EXTERNAL_STORAGE` ("All files access") on Android 11+, which the app
 *     asks for in its own UI with the reason attached; see [mode] and
 *     [workspacesVisibleToFileManagers].
 *  2. [externalWorkspaces] — `<getExternalFilesDir()>/workspaces`. Needs no
 *     permission and is reachable by `adb shell`/`adb pull` — but on Android 11+
 *     the platform blocks *apps* (including every file manager) from browsing
 *     anyone's `Android/data`, so it is the fallback when the permission is
 *     refused, not the default.
 *  3. [internalWorkspaces] — `filesDir/workspaces`. `filesDir` is
 *     `/data/data/<applicationId>/files`, which no file manager, no MTP browse and
 *     no non-root `adb shell` can see at all. Kept as a migration source and as
 *     the last-resort root on a device with no usable external storage; the app
 *     says so plainly in its storage panel instead of pretending otherwise.
 *
 * A fourth possibility exists and is deliberately not used as the live root: a
 * folder chosen through the Storage Access Framework. SAF hands the app a
 * `content://` tree URI, and the embedded OpenCode runtime is a POSIX process
 * (git, bun, ripgrep, shell tools) that needs a real path — so a SAF tree can only
 * be used live when it can be RESOLVED to a real path on the primary volume,
 * which this app verifies by writing a probe file before accepting it
 * ([storageOverrideRoot]). Anything else (SD card, cloud provider) would need a
 * full VFS layer inside the runtime, which is a re-engineering of OpenCode's file
 * tools and therefore out of scope (Core Rule 2/3).
 *
 * What the change does NOT do is weaken Phase 7's isolation. Projects are still
 * separate directories, the server still scopes every request by the project
 * directory it was given, and upstream's own `FSUtil.contains` guard still refuses
 * `../` escapes — W1-W3 are re-run against whichever root is active. The boundary
 * that moves is *visibility*; the boundary that is enforced is unchanged.
 *
 * Executables do NOT live under filesDir: on API 29+ the app home dir is
 * mounted no-exec (W^X). They ship as JNI libs and are executed from
 * [Context.getApplicationInfo].nativeLibraryDir via the bin/ symlinks.
 */
class RuntimePaths private constructor(
    filesDir: File,
    nativeLibraryDir: File,
    externalFilesDir: File?,
    /**
     * The user's chosen folder (Storage Access Framework), already resolved to a
     * real path and probe-verified by the caller. Null when they never chose one.
     */
    overrideRoot: File? = null,
    /** Shared-storage volume root (e.g. `/storage/emulated/0`), null when unmounted. */
    publicStorageRoot: File? = null,
    /** True when the app may write anywhere on shared storage (All files access). */
    publicStorageUsable: Boolean = false,
    /** API level, needed only for the Android 10 exception in [fileManagerVisible]. */
    apiLevel: Int = 34,
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
     * The shared-storage project root, when the app is allowed to write outside
     * its own sandbox. `Documents/OpenCode` on the primary volume: a path the
     * Files app, any file manager, MTP and `adb` all show like any other user
     * folder.
     */
    val publicWorkspaces: File? = if (publicStorageUsable) {
        publicStorageRoot?.let { File(it, PUBLIC_PROJECTS_DIR) }
    } else {
        null
    }

    /** The folder the user picked through SAF, when it resolved to a real path. */
    val storageOverrideRoot: File? = overrideRoot

    /**
     * Which of the three locations projects are actually in. [StorageMode]
     * documents what each one is visible to; the UI reads it so it never has to
     * guess, and the device gates assert on it.
     */
    val mode: StorageMode = when {
        storageOverrideRoot != null -> StorageMode.CHOSEN
        publicWorkspaces != null -> StorageMode.PUBLIC
        externalWorkspaces != null -> StorageMode.APP_EXTERNAL
        else -> StorageMode.INTERNAL
    }

    /**
     * Where projects really live, resolved in order: the folder the user chose,
     * the shared-storage default, the app-specific external directory, and only
     * then app-private storage.
     */
    val workspaces: File = when (mode) {
        StorageMode.CHOSEN -> storageOverrideRoot!!
        StorageMode.PUBLIC -> publicWorkspaces!!
        StorageMode.APP_EXTERNAL -> externalWorkspaces!!
        StorageMode.INTERNAL -> internalWorkspaces
    }

    /**
     * Can an ordinary FILE MANAGER (or the Files app) browse the live project
     * folder without an export step? This is the property the product decision in
     * the Phase 10 continuation v3 turns on, so it is stated once, here, instead
     * of being re-derived in the UI:
     *
     *  * [StorageMode.PUBLIC] / [StorageMode.CHOSEN] — yes, it is a normal shared
     *    folder;
     *  * [StorageMode.APP_EXTERNAL] — yes on Android 10, where file managers may
     *    browse `Android/data`; NO on Android 11+, where the platform blocks apps
     *    from browsing another app's directory (a plain `adb shell` still reaches
     *    it, which is a different question);
     *  * [StorageMode.INTERNAL] — no: `/data/data` is invisible to everything
     *    outside the app, root excepted.
     */
    val fileManagerVisible: Boolean = when (mode) {
        StorageMode.CHOSEN, StorageMode.PUBLIC -> true
        StorageMode.APP_EXTERNAL -> apiLevel < 30
        StorageMode.INTERNAL -> false
    }

    /** Can a non-root `adb shell` (and a PC over MTP/adb pull) reach the files? */
    val workspacesVisibleToShell: Boolean = mode != StorageMode.INTERNAL

    /** Kept for the older gates: "the project root is not under /data/data". */
    val workspacesAreExternal: Boolean = mode != StorageMode.INTERNAL

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

        /**
         * Where the shared-storage default lives: `Documents/OpenCode`, inside a
         * standard user-facing folder rather than at the root of internal storage.
         * Every file manager shows Documents, and the name says whose folder it is.
         */
        const val PUBLIC_PROJECTS_DIR = "Documents/OpenCode"

        /**
         * The shared-storage volume root, or null when it is not mounted. Read
         * through the deprecated `getExternalStorageDirectory` on purpose: it is
         * still the only API that names the primary volume's real path, which is
         * what a POSIX runtime needs (a `content://` URI is useless to git and bun).
         */
        private fun sharedStorageRoot(): File? = runCatching {
            if (android.os.Environment.getExternalStorageState() == android.os.Environment.MEDIA_MOUNTED) {
                android.os.Environment.getExternalStorageDirectory()
            } else {
                null
            }
        }.getOrNull()

        /**
         * May the app write anywhere on shared storage?
         *
         * API 30+: `MANAGE_EXTERNAL_STORAGE`, granted by the user from the app's
         * own storage panel. API 29 and below: not needed — the app-specific
         * external directory is itself browsable by file managers on Android 10, so
         * [StorageMode.APP_EXTERNAL] is already the visible option there and the
         * fallback is not a downgrade.
         */
        private fun sharedStorageUsable(): Boolean = runCatching {
            android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        }.getOrDefault(false)

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
                    overrideRoot = StorageChoice.chosenRoot(context),
                    publicStorageRoot = sharedStorageRoot(),
                    publicStorageUsable = sharedStorageUsable(),
                    apiLevel = android.os.Build.VERSION.SDK_INT,
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
            overrideRoot: File? = null,
            publicStorageRoot: File? = null,
            publicStorageUsable: Boolean = false,
            apiLevel: Int = 34,
        ): RuntimePaths = RuntimePaths(
            filesDir = filesDir,
            nativeLibraryDir = nativeLibraryDir,
            externalFilesDir = externalFilesDir,
            overrideRoot = overrideRoot,
            publicStorageRoot = publicStorageRoot,
            publicStorageUsable = publicStorageUsable,
            apiLevel = apiLevel,
        )

        /**
         * Drop the cached instance so the next [get] re-resolves the storage roots.
         * Called after the user grants All files access or picks a folder: the
         * active root really does change at runtime, and a cached answer would mean
         * the app keeps writing to the old place while the UI shows the new path.
         */
        fun refresh() {
            synchronized(this) { instance = null }
        }
    }
}
