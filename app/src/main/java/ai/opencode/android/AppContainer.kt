package ai.opencode.android

import android.content.Context
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.memory.ProjectMemory
import ai.opencode.android.runtime.RuntimeEnv
import ai.opencode.android.runtime.RuntimePaths
import ai.opencode.android.security.SecretStore
import ai.opencode.android.client.LoopbackGuard

/**
 * Wires the pieces together for the UI (and nothing else).
 *
 * The client always points at the loopback address of the server this app owns
 * — [LoopbackGuard] refuses anything else — and reuses the same credential the
 * supervisor generated, so there is exactly one set of credentials and one
 * server. The repository instance is cached and rebuilt only when the workspace
 * or the (rotated) password changes.
 */
class AppContainer private constructor(private val context: Context) {

    private val paths: RuntimePaths = RuntimePaths.get(context)
    val secrets: SecretStore = SecretStore.get(context)
    private var cached: Pair<String, OpenCodeRepository>? = null

    /**
     * v6.1: the per-provider multi-key ring. Key VALUES live in [secrets]
     * (Keystore-encrypted, one blob per key); only labels/last-4/active-slot
     * metadata goes into this prefs file - no key material.
     */
    private val keyringPrefs = context.getSharedPreferences("provider_keyring", Context.MODE_PRIVATE)
    private val keyringInstance: ai.opencode.android.security.ProviderKeyring by lazy {
        ai.opencode.android.security.ProviderKeyring(
            loadMeta = { keyringPrefs.getString("meta", "") ?: "" },
            saveMeta = { keyringPrefs.edit().putString("meta", it).apply() },
            vault = object : ai.opencode.android.security.ProviderKeyring.Vault {
                override fun put(name: String, value: String) = secrets.put(name, value)
                override fun get(name: String): String? = runCatching { secrets.get(name) }.getOrNull()
                override fun delete(name: String): Boolean = secrets.delete(name)
                override fun contains(name: String): Boolean = secrets.contains(name)
            },
        )
    }

    fun keyring(): ai.opencode.android.security.ProviderKeyring = keyringInstance

    fun repositoryFor(workspaceDir: String?): OpenCodeRepository = synchronized(this) {
        val password = runCatching { secrets.get(SECRET_PASSWORD) }.getOrNull().orEmpty()
        val key = (workspaceDir ?: "") + "|" + password.hashCode()
        cached?.takeIf { it.first == key && password.isNotEmpty() }?.let { return it.second }
        cached?.let { runCatching { it.second.stopStream() } }
        cached = null
        val repo = OpenCodeRepository(
            baseUrl = "http://${LoopbackGuard.SERVER_BIND_HOSTNAME}:${RuntimeEnv.SERVER_PORT}",
            username = RuntimeEnv.SERVER_USER,
            password = password,
            workspaceDir = workspaceDir,
            // The model the user last used and their starred quick-switch list live
            // in SharedPreferences, so a rebuilt repository (new project, new chat,
            // app restart) starts from the user's own choice instead of the first
            // model the server happens to list (v4 item 3).
            modelPreference = ai.opencode.android.client.PrefsModelPreference.get(context),
            keyring = keyringInstance,
        )
        cached = key to repo
        repo
    }

    /** Stop the cached repository's event stream and forget it (storage change). */
    private fun releaseCaches() {
        cached?.let { runCatching { it.second.stopStream() } }
        cached = null
    }

    /** True once the supervisor has a Keystore-held password (i.e. runtime usable). */
    fun serverPasswordAvailable(): Boolean =
        runCatching { secrets.get(SECRET_PASSWORD) }.getOrNull()?.isNotEmpty() == true

    fun workspacesRoot(): java.io.File = paths.workspaces

    /**
     * Can a file manager (Files app, third-party, MTP folder browse) open the live
     * project folder right now? Phase 10 continuation v3 makes this the default
     * answer rather than a caveat: projects live in `Documents/OpenCode` and are
     * visible from creation. It is still derived from the platform (the All files
     * access grant, the chosen folder) instead of assumed, because the fallback
     * locations genuinely are not visible and the UI must not claim otherwise.
     */
    fun workspacesVisibleToFileManagers(): Boolean = paths.fileManagerVisible

    /** Which of the storage locations is in effect, for the UI's storage panel. */
    fun storageMode(): ai.opencode.android.runtime.StorageMode = paths.mode

    /** Where projects live, in the app's own words (diagnostics also use this). */
    fun workspacesLocationLabel(): String = paths.workspaces.absolutePath

    /**
     * OpenCode's own persistent-memory files: a per-project `AGENTS.md` at the
     * workspace root, and the global `AGENTS.md` in OpenCode's global config dir.
     * The app reads/writes these exact files (upstream `session/instruction.ts`
     * loads both); it never invents a second memory store.
     */
    fun memory(): ProjectMemory = ProjectMemory(
        workspacesRoot = paths.workspaces,
        globalRulesDir = paths.xdgConfigOpencode,
    )

    /** Names only — never values (this string ends up on screen and in logs). */
    fun storedProviderIdsLabel(): String =
        ai.opencode.android.runtime.Secrets.storedProviderIds(context).joinToString(", ").ifEmpty { "(none)" }

    /** The provider ids with a Keystore-held key, for the setup classifier. */
    fun storedProviderIds(): List<String> =
        ai.opencode.android.runtime.Secrets.storedProviderIds(context)

    fun hardwareBackedLabel(): String = runCatching { secrets.isHardwareBacked().toString() }.getOrDefault("unknown")

    companion object {
        private const val SECRET_PASSWORD = ai.opencode.android.runtime.Secrets.SERVER_PASSWORD

        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }

        /**
         * Drop the cached container and its cached repository, because it resolved
         * the project root at construction time. Called when the storage mode changes
         * (All files access granted, folder chosen) - otherwise the app would keep
         * handing the server the old root while the UI showed the new one.
         */
        fun refresh() {
            synchronized(this) {
                instance?.releaseCaches()
                instance = null
            }
        }
    }
}
