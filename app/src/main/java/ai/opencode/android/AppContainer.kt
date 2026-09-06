package ai.opencode.android

import android.content.Context
import ai.opencode.android.client.OpenCodeRepository
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
        )
        cached = key to repo
        repo
    }

    /** True once the supervisor has a Keystore-held password (i.e. runtime usable). */
    fun serverPasswordAvailable(): Boolean =
        runCatching { secrets.get(SECRET_PASSWORD) }.getOrNull()?.isNotEmpty() == true

    fun workspacesRoot(): java.io.File = paths.workspaces

    /** Names only — never values (this string ends up on screen and in logs). */
    fun storedProviderIdsLabel(): String =
        ai.opencode.android.runtime.Secrets.storedProviderIds(context).joinToString(", ").ifEmpty { "(none)" }

    fun hardwareBackedLabel(): String = runCatching { secrets.isHardwareBacked().toString() }.getOrDefault("unknown")

    companion object {
        private const val SECRET_PASSWORD = ai.opencode.android.runtime.Secrets.SERVER_PASSWORD

        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
