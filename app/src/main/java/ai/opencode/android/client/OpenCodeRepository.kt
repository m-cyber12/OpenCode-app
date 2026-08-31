package ai.opencode.android.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The Android side of "the app is an OpenCode client".
 *
 * It owns no agent logic. Everything user-visible comes from two upstream
 * mechanisms and nothing else:
 *   * the REST API ([OpenCodeApi]) for lists, prompts, replies, MCP and config;
 *   * the SSE event stream ([OpenCodeEventStream]) for live progress, reduced by
 *     [Transcript].
 *
 * One repository instance is shared by the UI and by the on-device instrumented
 * tests, so the tests exercise the same client code the app runs (no parallel
 * test-only implementation).
 */
class OpenCodeRepository(
    baseUrl: String,
    private val username: String,
    private val password: String,
    workspaceDir: String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    data class UiState(
        val streaming: Boolean = false,
        val streamStatus: String = "idle",
        val serverVersion: String = "",
        val sessions: List<OpenCodeApi.SessionInfo> = emptyList(),
        val selectedSession: String = "",
        val transcript: Transcript.Snapshot = Transcript.Snapshot(emptyList()),
        val mcp: Map<String, String> = emptyMap(),
        val providers: OpenCodeApi.ProviderSnapshot? = null,
        val model: OpenCodeApi.ModelRef? = null,
        val busy: Boolean = false,
        val error: String = "",
        val notice: String = "",
    )

    val api = OpenCodeApi(baseUrl = baseUrl, username = username, password = password, directory = workspaceDir)

    var workspaceDir: String? = workspaceDir
        private set

    private val transcript = Transcript()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val stream = OpenCodeEventStream(
        baseUrl = baseUrl,
        username = username,
        password = password,
        directory = workspaceDir,
        onEvent = { ev ->
            transcript.apply(ev.type, ev.properties)
            publishTranscript("event ${ev.type}")
        },
        onStatus = { line -> _state.value = _state.value.copy(streamStatus = line) },
    )

    // ---- lifecycle ---------------------------------------------------------

    /** Subscribe to the server stream (the only source of live progress). */
    fun startStream() {
        if (!_state.value.streaming) {
            _state.value = _state.value.copy(streaming = true)
            stream.start()
        }
        refresh()
    }

    fun stopStream() {
        stream.stop()
        _state.value = _state.value.copy(streaming = false, streamStatus = "stopped")
    }

    /** Point the client at another workspace (server-side instance scope). */
    fun setWorkspace(dir: String?) {
        workspaceDir = dir
        api.directory = dir
        refresh()
    }

    /** Pull the authoritative lists from the server (never fabricated locally). */
    fun refresh() {
        scope.launch {
            val errors = ArrayList<String>()
            runCatching { api.health() }.onSuccess { h ->
                _state.value = _state.value.copy(serverVersion = h.optString("version"), error = "")
            }.onFailure { errors.add("health: ${it.message}") }

            runCatching { api.listSessions() }.onSuccess { list ->
                val sel = _state.value.selectedSession
                val keep = if (sel.isNotEmpty() && list.any { it.id == sel }) sel else (list.firstOrNull()?.id ?: "")
                _state.value = _state.value.copy(sessions = list, selectedSession = keep)
                if (keep.isNotEmpty() && keep != sel) {
                    runCatching { api.messages(keep) }
                        .onSuccess { transcript.loadMessages(keep, it); publishTranscript("transcript loaded") }
                        .onFailure { errors.add("messages: ${it.message}") }
                } else {
                    publishTranscript("sessions refreshed")
                }
            }.onFailure { errors.add("sessions: ${it.message}") }

            runCatching { api.mcpStatus() }.onSuccess { m ->
                _state.value = _state.value.copy(mcp = m)
            }.onFailure { errors.add("mcp: ${it.message}") }

            runCatching { api.providers() }.onSuccess { p ->
                // OpenCode's own `default` map (providerID -> modelID); used as
                // the model hint, never invented by the client.
                val cur = _state.value.model
                val first = p.defaultModel.entries.firstOrNull()
                _state.value = _state.value.copy(
                    providers = p,
                    model = cur ?: first?.let { OpenCodeApi.ModelRef(it.key, it.value) },
                )
            }.onFailure { errors.add("providers: ${it.message}") }

            runCatching { api.pendingPermissions() }.onSuccess { list ->
                transcript.replacePrompts(
                    list.map {
                        Transcript.Prompt(it.id, it.sessionID, it.permission, it.patterns, it.metadata?.toString() ?: "")
                    },
                )
                publishTranscript("permissions refreshed")
            }.onFailure { errors.add("permissions: ${it.message}") }

            if (errors.isNotEmpty()) {
                _state.value = _state.value.copy(error = errors.joinToString("; ").take(400))
            }
        }
    }

    fun selectSession(sessionID: String) {
        if (sessionID.isEmpty()) return
        scope.launch {
            runCatching { api.messages(sessionID) }.onSuccess {
                transcript.loadMessages(sessionID, it)
                _state.value = _state.value.copy(selectedSession = sessionID, transcript = transcript.snapshot())
                publishTranscript("transcript loaded")
            }.onFailure { fail("messages", it) }
        }
    }

    fun newSession(title: String?) {
        scope.launch {
            runCatching { api.createSession(title) }.onSuccess { s ->
                _state.value = _state.value.copy(
                    sessions = listOf(s) + _state.value.sessions.filterNot { it.id == s.id },
                    selectedSession = s.id,
                )
                transcript.loadMessages(s.id, emptyList())
                publishTranscript("session created")
            }.onFailure { fail("create session", it) }
        }
    }

    /**
     * Queue a prompt (server-side agent turn). Creates the session first when
     * none is selected, exactly like the TUI does.
     */
    fun sendPrompt(text: String) {
        scope.launch {
            setBusy(true)
            try {
                var sid = _state.value.selectedSession
                if (sid.isEmpty()) {
                    val s = api.createSession(text.take(40))
                    sid = s.id
                    _state.value = _state.value.copy(
                        sessions = listOf(s) + _state.value.sessions,
                        selectedSession = sid,
                    )
                }
                api.promptAsync(sid, text, _state.value.model)
                publishTranscript("prompt accepted")
            } catch (t: Throwable) {
                fail("prompt_async", t)
            } finally {
                setBusy(false)
            }
        }
    }

    /** Run a shell command through OpenCode's own shell endpoint. */
    fun runShell(command: String) {
        val sid = _state.value.selectedSession
        if (sid.isEmpty()) {
            _state.value = _state.value.copy(error = "select or create a session first")
            return
        }
        scope.launch {
            setBusy(true)
            runCatching { api.shell(sid, command, SHELL_AGENT) }
                .onSuccess { publishTranscript("shell accepted ($it)") }
                .onFailure { fail("shell", it) }
            setBusy(false)
        }
    }

    fun abort() {
        val sid = _state.value.selectedSession
        if (sid.isEmpty()) return
        scope.launch { runCatching { api.abortSession(sid) }.onFailure { fail("abort", it) } }
    }

    /** The three upstream replies; the server decides what "always" means. */
    fun replyPermission(requestID: String, reply: String) {
        scope.launch {
            runCatching { api.replyPermission(requestID, reply) }
                .onSuccess { _state.value = _state.value.copy(notice = "permission $reply for $requestID") }
                .onFailure { fail("permission reply", it) }
        }
    }

    /**
     * Connect an MCP server through OpenCode's own `POST /mcp` (live, instance
     * state) and, when [persist], into `opencode.jsonc` via
     * `PATCH /global/config` so it survives a restart. Both are upstream
     * mechanisms; the app defines no MCP config format of its own.
     */
    fun addMcp(name: String, config: JSONObject, persist: Boolean) {
        scope.launch {
            runCatching { api.addMcp(name, config) }
                .onSuccess {
                    _state.value = _state.value.copy(notice = "mcp $name -> ${it.optString("status")}")
                    runCatching { api.mcpStatus() }.onSuccess { m -> _state.value = _state.value.copy(mcp = m) }
                }
                .onFailure { fail("mcp add", it) }
            if (persist) {
                val patch = JSONObject().put("mcp", JSONObject().put(name, config))
                runCatching { api.patchGlobalConfig(patch) }
                    .onSuccess { _state.value = _state.value.copy(notice = "mcp $name saved to global config") }
                    .onFailure { fail("mcp persist", it) }
            }
        }
    }

    fun disconnectMcp(name: String) {
        scope.launch { runCatching { api.disconnectMcp(name) }.onFailure { fail("mcp disconnect", it) } }
    }

    fun connectMcp(name: String) {
        scope.launch { runCatching { api.connectMcp(name) }.onFailure { fail("mcp connect", it) } }
    }

    /**
     * Store a provider key in the Keystore-backed store and hand it to
     * OpenCode's own auth store over `PUT /auth/:providerID` (upstream
     * mechanism). The key is never written to a plaintext app file by us.
     */
    fun provisionProvider(providerID: String, apiKey: String, store: ai.opencode.android.security.SecretStore) {
        scope.launch {
            try {
                store.put(ai.opencode.android.security.SecretNames.providerSecretName(providerID), apiKey)
                api.setProviderAuth(providerID, apiKey)
                val p = api.providers()
                _state.value = _state.value.copy(
                    providers = p,
                    notice = "credential stored (Keystore) for $providerID; " +
                        "OpenCode reports connected=" + p.connected.contains(providerID),
                    error = "",
                )
            } catch (t: Throwable) {
                fail("provision provider", t)
            }
        }
    }

    fun revokeProvider(providerID: String, store: ai.opencode.android.security.SecretStore) {
        scope.launch {
            runCatching {
                store.delete(ai.opencode.android.security.SecretNames.providerSecretName(providerID))
                api.deleteProviderAuth(providerID)
                api.providers()
            }.onSuccess {
                _state.value = _state.value.copy(providers = it, notice = "credential revoked for $providerID")
            }.onFailure { fail("revoke provider", it) }
        }
    }

    /**
     * Pin the model the client sends with each prompt. Defaults to OpenCode's
     * own `default` map from GET /provider; this only records the user's choice
     * in the request payload — resolution/validation stays server-side.
     */
    fun setModel(providerID: String, modelID: String) {
        _state.value = _state.value.copy(
            model = OpenCodeApi.ModelRef(providerID, modelID),
            notice = "model -> $providerID/$modelID",
        )
    }

    /** Clear the client-side model hint so the server applies its own default. */
    fun clearModel() {
        _state.value = _state.value.copy(model = null, notice = "model hint cleared (server default)")
    }

    /** Permission policy for the instance, through OpenCode's config patch. */
    fun setBashPolicy(policy: String) {
        scope.launch {
            val patch = JSONObject().put("permission", JSONObject().put("bash", policy))
            runCatching { api.patchGlobalConfig(patch) }
                .onSuccess { _state.value = _state.value.copy(notice = "bash permission -> $policy") }
                .onFailure { fail("permission policy", it) }
        }
    }

    // ---- internals ---------------------------------------------------------

    private fun setBusy(b: Boolean) {
        _state.value = _state.value.copy(busy = b)
    }

    private fun fail(what: String, t: Throwable) {
        _state.value = _state.value.copy(error = "$what failed: ${t.message}".take(500))
    }

    private fun publishTranscript(status: String) {
        val snap = transcript.snapshot()
        _state.value = _state.value.copy(
            transcript = snap,
            busy = snap.busySessions().isNotEmpty(),
            streamStatus = status,
        )
    }

    companion object {
        /** OpenCode's default coding agent; the shell endpoint requires one. */
        const val SHELL_AGENT = "build"
    }
}
