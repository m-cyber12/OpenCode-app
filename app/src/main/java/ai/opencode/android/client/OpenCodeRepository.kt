package ai.opencode.android.client

import android.util.Log
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
 *
 * Phase 6 adds presentation-facing plumbing only: the server's own per-session
 * status (so a turn that is already running is visible right after a restart),
 * upstream's `revert`/`unrevert` (its undo/redo, which is how "retry the last
 * turn" is done - upstream has no regenerate endpoint), question replies, MCP
 * status objects kept whole, staged prompt attachments, and [UiError]
 * classification of every failure. No state here is a substitute for a server
 * fact; `draft` and `attachments` are the only two things the client owns, and
 * they exist because a composer has to hold text before the server has it.
 */
class OpenCodeRepository(
    baseUrl: String,
    private val username: String,
    private val password: String,
    workspaceDir: String?,
    /**
     * Where "the model the user last used" and their starred shortlist live.
     *
     * Phase 10 continuation v4 (item 3): the repository is rebuilt per project (the
     * instance directory changes), so anything remembered only in [UiState] is lost
     * exactly when the user opens a new chat or project - which is the reported
     * defect. Null is allowed so tests and one-off callers keep the old in-memory
     * behaviour; the app always passes the real store (AppContainer).
     */
    private val modelPreference: ModelPreference? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    data class UiState(
        val streaming: Boolean = false,
        val streamStatus: String = "idle",
        val serverVersion: String = "",
        val sessions: List<OpenCodeApi.SessionInfo> = emptyList(),
        val selectedSession: String = "",
        val transcript: Transcript.Snapshot = Transcript.Snapshot(emptyList()),
        /** Upstream's `MCP.Status` per server name - the `error` text is kept. */
        val mcp: Map<String, OpenCodeApi.McpEntry> = emptyMap(),
        val providers: OpenCodeApi.ProviderSnapshot? = null,
        val model: OpenCodeApi.ModelRef? = null,
    /**
     * Phase 10 continuation v4, item 3: the models the user starred in Settings,
     * in star order. Only these appear in the chat header's quick switch; the full
     * catalog stays in Settings.
     */
    val starredModels: List<OpenCodeApi.ModelRef> = emptyList(),
        val busy: Boolean = false,
        /** Raw upstream text of the last failed call, verbatim. */
        val error: String = "",
        /** What kind of "not working" that is; the UI maps it onto copy. */
        val errorKind: AgentAvailability = AgentAvailability.UNKNOWN,
        /** True once GET /global/health answered (the local agent is reachable). */
        val serverReachable: Boolean = false,
        val notice: String = "",
        /** Files staged for the next prompt, as upstream `FilePartInput`s. */
        val attachments: List<OpenCodeApi.Attachment> = emptyList(),
        /** Composer text. Owned by the client; the server has no draft concept. */
        val draft: String = "",
        /** Upstream's `GET /session/status` map (idle/busy/retry per session). */
        val sessionStatus: Map<String, OpenCodeApi.SessionStatusInfo> = emptyMap(),
        /**
         * OpenCode's permission policy as read from the global config
         * (`permission.<key> = ask|allow|deny`). Kept verbatim; an absent key is
         * upstream's own "ask" default and is not invented here.
         */
        val permissionPolicy: Map<String, String> = emptyMap(),
    ) {
        /** The selected session as the event stream/history reduced it. */
        val selected: Transcript.SessionView? get() = transcript.session(selectedSession)
        val selectedInfo: OpenCodeApi.SessionInfo? get() = sessions.firstOrNull { it.id == selectedSession }
        val messages: List<Transcript.Message> get() = selected?.messages ?: emptyList()
        val pendingAsks: List<Transcript.Prompt> get() = selected?.pending ?: emptyList()
        val pendingQuestions: List<Transcript.Question> get() = selected?.questions ?: emptyList()

        /**
         * Upstream's own retry state when the server is retrying a provider call
         * (`session.status` type `retry`, `{attempt, message, next}`). The event
         * stream is preferred; the REST map covers a client that joined late.
         */
        val retry: Transcript.RetryInfo?
            get() = selected?.retry
                ?: sessionStatus[selectedSession]?.takeIf { it.type == "retry" }?.let {
                    Transcript.RetryInfo(it.attempt, it.message, it.nextMs)
                }

        /** The error the SERVER reported for the selected session's last turn. */
        val turnError: Transcript.TurnError? get() = selected?.error

        /** Undo/retry needs a user message to revert to (upstream's own rule). */
        val canRetryTurn: Boolean get() = messages.any { it.role == "user" }

        /** Upstream keeps a staged revert on the session; that is redo's precondition. */
        val canRedo: Boolean get() = selectedInfo?.revertMessageID?.isNotEmpty() == true
    }

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
            // The transcript is built from live frames, so a frame that was emitted
            // before this client subscribed is lost for good - and a prompt can be
            // run within a second of the app starting (the UI gates drive it exactly
            // that way). A turn that ENDS is therefore also a repair point: fetch the
            // authoritative messages for the selected session, which is cheap and
            // bounded (one request per turn, single-flight).
            if (EventFrame.isSessionIdle(ev.type)) {
                refreshMessages("turn ended", force = true)
            } else if (EventFrame.isSessionStatus(ev.type) &&
                !hasRows(_state.value.selectedSession)
            ) {
                // A status frame arrived for a turn this client is showing but has no
                // messages for: frames were missed upstream of it, so fetch once.
                refreshMessages("transcript after status")
            }
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

    @Volatile
    private var messagesFetchInFlight = false

    /** Does the transcript already hold rows for [sid]? (`Snapshot` is per-session.) */
    private fun hasRows(sid: String): Boolean =
        _state.value.transcript.session(sid)?.messages?.isNotEmpty() == true

    /**
     * Re-read the selected session's messages from the server.
     *
     * Normally the transcript is fed by the event stream, which is live and cheap.
     * This exists for the two cases where the stream cannot be enough:
     *  - a turn just ended, so the server holds the final state regardless of which
     *    frames this client was subscribed for ([force] = true, one call per turn);
     *  - the app believes a turn is running for the selected session but its
     *    transcript has no messages at all, which means frames were missed.
     *
     * Single-flight, and never throws: a repair that fails must not disturb the UI.
     */
    private fun refreshMessages(status: String, force: Boolean = false) {
        val sid = _state.value.selectedSession
        if (sid.isEmpty()) return
        if (messagesFetchInFlight) return
        if (!force && hasRows(sid)) return
        messagesFetchInFlight = true
        scope.launch {
            try {
                val list = api.messages(sid)
                if (_state.value.selectedSession == sid) {
                    transcript.loadMessages(sid, list)
                    publishTranscript(status)
                }
            } catch (t: Throwable) {
                // Keep it quiet: the stream is the primary path and will report a real
                // failure on its own. A repair that could not run is not an error.
                Log.d("OpenCode/repo", "transcript repair failed: ${t.message}")
            } finally {
                messagesFetchInFlight = false
            }
        }
    }

    /** Pull the authoritative lists from the server (never fabricated locally). */
    fun refresh() {
        scope.launch {
            val errors = ArrayList<String>()
            runCatching { api.health() }.onSuccess { h ->
                _state.value = _state.value.copy(
                    serverVersion = h.optString("version"),
                    serverReachable = true,
                    error = "",
                    errorKind = AgentAvailability.READY,
                )
            }.onFailure {
                errors.add("health: ${it.message}")
                noteCallFailure(it)
            }

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

            runCatching { api.sessionStatus() }.onSuccess { m ->
                _state.value = _state.value.copy(sessionStatus = m)
                publishTranscript("session status refreshed")
            }.onFailure { errors.add("session status: ${it.message}") }

            runCatching { api.mcpEntries() }.onSuccess { m ->
                _state.value = _state.value.copy(mcp = m)
            }.onFailure { errors.add("mcp: ${it.message}") }

            runCatching { api.providers() }.onSuccess { p ->
                // OpenCode's own `default` map (providerID -> modelID); used as
                // the model hint, never invented by the client. When this client
                // instance has no choice in memory yet (new chat, new project,
                // fresh process), the PERSISTED last-used model is the answer -
                // not "the first connected provider", which is what the user
                // reported as a bug (Phase 10 continuation v4, item 3).
                _state.value = _state.value.copy(
                    providers = p,
                    model = _state.value.model ?: defaultModelHint(p, persisted = modelPreference?.lastModel()),
                    starredModels = modelPreference?.starred() ?: _state.value.starredModels,
                )
            }.onFailure { errors.add("providers: ${it.message}") }

            runCatching { api.globalConfig() }.onSuccess { cfg ->
                _state.value = _state.value.copy(permissionPolicy = parsePermissionPolicy(cfg))
            }.onFailure { errors.add("config: ${it.message}") }

            runCatching { api.pendingPermissions() }.onSuccess { list ->
                transcript.replacePrompts(list.map { it.asPrompt() })
                publishTranscript("permissions refreshed")
            }.onFailure { errors.add("permissions: ${it.message}") }

            runCatching { api.pendingQuestions() }.onSuccess { list ->
                transcript.replaceQuestions(
                    list.map {
                        Transcript.Question(
                            id = it.id,
                            sessionID = it.sessionID,
                            items = it.items.map { q ->
                                Transcript.QuestionItem(
                                    header = q.header,
                                    question = q.question,
                                    options = q.options.map { o -> Transcript.QuestionOption(o.label, o.description) },
                                    multiple = q.multiple,
                                    custom = q.custom,
                                )
                            },
                        )
                    },
                )
                publishTranscript("questions refreshed")
            }.onFailure { errors.add("questions: ${it.message}") }

            if (errors.isNotEmpty()) {
                _state.value = _state.value.copy(error = errors.joinToString("; ").take(400))
            }
        }
    }

    /** Re-read only the session list (titles, revert markers, ordering). */
    fun refreshSessions() {
        scope.launch {
            runCatching { api.listSessions() }.onSuccess { list ->
                val sel = _state.value.selectedSession
                _state.value = _state.value.copy(
                    sessions = list,
                    selectedSession = if (sel.isNotEmpty() && list.any { it.id == sel }) sel else _state.value.selectedSession,
                )
            }.onFailure { fail("sessions", it) }
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
                    draft = "",
                )
                transcript.loadMessages(s.id, emptyList())
                publishTranscript("session created")
            }.onFailure { fail("create session", it) }
        }
    }

    /** DELETE /session/:id — the server deletes; the client only asks. */
    fun deleteSession(sessionID: String) {
        scope.launch {
            runCatching { api.deleteSession(sessionID) }.onSuccess {
                val rest = _state.value.sessions.filterNot { it.id == sessionID }
                _state.value = _state.value.copy(
                    sessions = rest,
                    selectedSession = if (_state.value.selectedSession == sessionID) (rest.firstOrNull()?.id ?: "") else _state.value.selectedSession,
                    notice = "session deleted",
                )
                publishTranscript("session deleted")
            }.onFailure { fail("delete session", it) }
        }
    }

    /**
     * Delete every session the server has for a project's directory. OpenCode's
     * `GET /session` takes a `directory` filter (the same field the client pins
     * per instance), so this is the server's own list scoped by workspace; each
     * id then goes through the normal DELETE endpoint.
     *
     * Used when a project is deleted so its history does not linger in the
     * session panel of another project.
     */
    fun deleteSessionsForDirectory(dir: String) {
        if (dir.isBlank()) return
        scope.launch {
            runCatching { api.listSessions(directory = dir) }.onSuccess { list ->
                list.forEach { s -> runCatching { api.deleteSession(s.id) } }
                refreshSessions()
            }.onFailure { fail("delete project sessions", it) }
        }
    }

    /** PATCH /session/:id — rename through the server (it owns titles). */
    fun renameSession(sessionID: String, title: String) {
        if (title.isBlank()) return
        scope.launch {
            runCatching { api.updateSessionTitle(sessionID, title.trim()) }.onSuccess { s ->
                _state.value = _state.value.copy(
                    sessions = _state.value.sessions.map { if (it.id == s.id) s else it },
                )
            }.onFailure { fail("rename session", it) }
        }
    }

    // ---- composer ----------------------------------------------------------

    fun setDraft(text: String) {
        _state.value = _state.value.copy(draft = text)
    }

    /** Stage a file for the next prompt (already copied into app-private storage). */
    fun attach(a: OpenCodeApi.Attachment) {
        _state.value = _state.value.copy(
            attachments = _state.value.attachments.filterNot { it.url == a.url } + a,
        )
    }

    fun detach(url: String) {
        _state.value = _state.value.copy(attachments = _state.value.attachments.filterNot { it.url == url })
    }

    fun clearAttachments() {
        _state.value = _state.value.copy(attachments = emptyList())
    }

    /**
     * Queue a prompt (server-side agent turn). Creates the session first when
     * none is selected, exactly like the TUI does. Attachments go in the same
     * `parts` array as upstream `FilePartInput`s; the server reads the file.
     */
    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        val files = _state.value.attachments
        if (trimmed.isEmpty() && files.isEmpty()) return
        scope.launch {
            setBusy(true)
            try {
                var sid = _state.value.selectedSession
                if (sid.isEmpty()) {
                    val s = api.createSession(titleFor(trimmed, files))
                    sid = s.id
                    _state.value = _state.value.copy(
                        sessions = listOf(s) + _state.value.sessions,
                        selectedSession = sid,
                    )
                }
                val used = _state.value.model
                api.promptAsync(sid, trimmed, used, attachments = files)
                rememberUsedModel(used)
                _state.value = _state.value.copy(
                    draft = "",
                    attachments = emptyList(),
                    error = "",
                    errorKind = AgentAvailability.READY,
                    notice = "",
                )
                publishTranscript("prompt accepted")
                // The user's own turn is on the server now; take it from there rather
                // than waiting for a frame that may have been emitted before this
                // client subscribed (run #16: the transcript stayed empty for 120s
                // while the server had the answer, and the screen is what the user
                // sees).
                refreshMessages("transcript after prompt", force = true)
            } catch (t: Throwable) {
                fail("prompt_async", t)
            } finally {
                // The stream owns `busy` from here on; only clear the optimistic
                // flag if the server has not already said a turn is running.
                setBusy(_state.value.transcript.busySessions().isNotEmpty())
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
            setBusy(_state.value.transcript.busySessions().isNotEmpty())
        }
    }

    /** POST /session/:id/abort — upstream's stop. */
    fun abort() {
        val sid = _state.value.selectedSession
        if (sid.isEmpty()) return
        scope.launch { runCatching { api.abortSession(sid) }.onFailure { fail("abort", it) } }
    }

    // ---- undo / retry (upstream's own revert choreography) -----------------

    /**
     * Upstream's `session.undo` (packages/tui/src/routes/session/index.tsx): abort
     * unless the session is idle, revert to the last user message before the revert
     * marker, then put that message's own text and file parts back in the composer.
     * The app adds no retry endpoint of its own - upstream has none.
     */
    fun undoLastTurn() {
        val sid = _state.value.selectedSession
        if (sid.isEmpty()) return
        scope.launch {
            try {
                abortUnlessIdle(sid)
                val target = lastUserBeforeRevert(sid) ?: return@launch
                api.revert(sid, target.id)
                restoreComposer(target)
                refreshSessionList(sid)
                publishTranscript("reverted to ${target.id}")
            } catch (t: Throwable) {
                fail("revert", t)
            }
        }
    }

    /**
     * "Retry the last turn": upstream's revert to the last user message, then the
     * same prompt queued again. The server clears its own staged revert when a new
     * prompt runs (`session/prompt.ts`: `if (session.revert) revert.cleanup(...)`),
     * so the client does not have to.
     */
    fun retryLastTurn() {
        val sid = _state.value.selectedSession
        if (sid.isEmpty()) return
        scope.launch {
            setBusy(true)
            try {
                abortUnlessIdle(sid)
                val target = lastUserBeforeRevert(sid) ?: return@launch
                api.revert(sid, target.id)
                val text = userText(target)
                val files = userFiles(target)
                _state.value = _state.value.copy(draft = "", attachments = emptyList())
                if (text.isBlank() && files.isEmpty()) return@launch
                val used = _state.value.model
                api.promptAsync(sid, text, used, attachments = files)
                rememberUsedModel(used)
                refreshSessionList(sid)
                publishTranscript("retry accepted")
            } catch (t: Throwable) {
                fail("retry", t)
            } finally {
                setBusy(_state.value.transcript.busySessions().isNotEmpty())
            }
        }
    }

    /** Upstream's `session.redo`: unrevert, or revert forward to the next user message. */
    fun redoLastTurn() {
        val sid = _state.value.selectedSession
        val marker = _state.value.selectedInfo?.revertMessageID ?: ""
        if (sid.isEmpty() || marker.isEmpty()) return
        scope.launch {
            try {
                val later = transcript.snapshot().session(sid)?.messages
                    ?.firstOrNull { it.role == "user" && it.id > marker }
                if (later == null) {
                    api.unrevert(sid)
                    _state.value = _state.value.copy(draft = "", attachments = emptyList())
                } else {
                    api.revert(sid, later.id)
                }
                refreshSessionList(sid)
                publishTranscript(if (later == null) "unreverted" else "reverted forward")
            } catch (t: Throwable) {
                fail("unrevert", t)
            }
        }
    }

    // ---- blocking asks -----------------------------------------------------

    /** The three upstream replies; the server decides what "always" means. */
    fun replyPermission(requestID: String, reply: String) {
        scope.launch {
            runCatching { api.replyPermission(requestID, reply) }
                .onSuccess { _state.value = _state.value.copy(notice = "permission $reply for $requestID") }
                .onFailure { fail("permission reply", it) }
        }
    }

    /**
     * Answer a question request: one array of selected labels per question, in the
     * order the server listed them (`QuestionV1.Reply`).
     */
    fun replyQuestion(requestID: String, answers: List<List<String>>) {
        scope.launch {
            runCatching { api.replyQuestion(requestID, answers) }
                .onSuccess { _state.value = _state.value.copy(notice = "question answered") }
                .onFailure { fail("question reply", it) }
        }
    }

    fun rejectQuestion(requestID: String) {
        scope.launch {
            runCatching { api.rejectQuestion(requestID) }
                .onSuccess { _state.value = _state.value.copy(notice = "question declined") }
                .onFailure { fail("question reject", it) }
        }
    }

    // ---- MCP / providers / policy -----------------------------------------

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
                    runCatching { api.mcpEntries() }.onSuccess { m -> _state.value = _state.value.copy(mcp = m) }
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

    /** Re-read `GET /mcp` (statuses change when the server reconnects). */
    fun refreshMcp() {
        scope.launch {
            runCatching { api.mcpEntries() }
                .onSuccess { _state.value = _state.value.copy(mcp = it) }
                .onFailure { fail("mcp", it) }
        }
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
                val p = applyCredentialChange(providerID)
                // The user just connected this provider: make it the model the
                // composer sends unless they already pinned one on it. Without
                // this, the hint stays on whatever provider the catalog listed
                // first (Phase 8: a `subconscious/...` id nobody had a key for).
                val cur = _state.value.model
                val next = if (cur?.providerID == providerID) cur else defaultModelHint(p, prefer = providerID) ?: cur
                _state.value = _state.value.copy(
                    providers = p,
                    model = next,
                    notice = "credential stored (Keystore) for $providerID; " +
                        "OpenCode reports connected=" + p.connected.contains(providerID) +
                        (if (next != null && next != cur) "; model -> ${next.providerID}/${next.modelID}" else ""),
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
                applyCredentialChange(providerID)
            }.onSuccess { p ->
                val cur = _state.value.model
                val next = if (cur?.providerID == providerID) defaultModelHint(p) else cur
                _state.value = _state.value.copy(
                    providers = p,
                    model = next,
                    notice = "credential revoked for $providerID",
                )
            }.onFailure { fail("revoke provider", it) }
        }
    }

    /**
     * Phase 10 continuation v4, item 2 (manual path): register a provider the
     * server's catalog does not know - the user's own OpenAI-compatible endpoint.
     *
     * Two upstream mechanisms, in this order:
     *
     *  1. `PATCH /global/config` writes the `provider.<id>` block (npm, baseURL,
     *     models) - the same document a hand-written `opencode.json` would carry;
     *  2. `PUT /auth/:id` stores the key in OpenCode's own auth store (the Keystore
     *     copy is kept for the app's own listing/revocation, exactly as the
     *     catalog path does).
     *
     * Then the instance is reset and re-read ([applyCredentialChange]), because a
     * config change - like a credential change - is only visible to the running
     * instance after upstream rebuilds it.
     *
     * The model hint follows the user's own input: the first model id they listed
     * becomes the current model when they do not have one on this provider. That is
     * the honest default for a provider the server has no `default` entry for.
     */
    fun addCustomProvider(
        providerID: String,
        displayName: String,
        baseUrl: String,
        models: List<String>,
        apiKey: String,
        store: ai.opencode.android.security.SecretStore,
    ) {
        scope.launch {
            try {
                val patch = CustomProviderConfig.patchFor(providerID, displayName, baseUrl, models)
                if (patch == null) {
                    _state.value = _state.value.copy(
                        error = "custom provider needs a lowercase id (a-z, 0-9, . _ -), an http(s) base URL and at least one model id",
                        errorKind = AgentAvailability.UNKNOWN,
                    )
                    return@launch
                }
                val id = providerID.trim()
                api.patchGlobalConfig(patch)
                store.put(ai.opencode.android.security.SecretNames.providerSecretName(id), apiKey)
                api.setProviderAuth(id, apiKey)
                val p = applyCredentialChange(id)
                val firstModel = models.firstOrNull()
                val cur = _state.value.model
                val next = if (cur?.providerID == id) cur
                else if (firstModel != null) OpenCodeApi.ModelRef(id, firstModel)
                else defaultModelHint(p, prefer = id) ?: cur
                if (next != null) runCatching { modelPreference?.rememberModel(next) }
                _state.value = _state.value.copy(
                    providers = p,
                    model = next,
                    notice = "custom provider $id stored (config + Keystore); " +
                        "the agent reports it in the catalog: " + p.allIds.contains(id),
                    error = "",
                )
            } catch (t: Throwable) {
                fail("custom provider", t)
            }
        }
    }

    /**
     * PHASE 9 FIX (carried Phase 8 defect: "turns silently use the bundled
     * `opencode` provider instead of the configured one").
     *
     * Root cause, traced in upstream `provider/provider.ts` at the pinned
     * commit: the provider table is an `InstanceState` built ONCE per loaded
     * instance, and it reads `auth.json` (`auth.all()`) while being built.
     * `PUT /auth/:providerID` (`ControlHttpApi.authSet`) only writes that file;
     * it does not invalidate the instance. So after provisioning, the running
     * instance still has no `openrouter`/`google` entry:
     *  - a prompt naming that provider dies with `ProviderModelNotFoundError`
     *    (published only as `session.error`, no assistant message - the
     *    "silent empty turn" of Phase 8 rounds 6-18), and
     *  - a prompt without an explicit model falls to `Provider.defaultModel()`
     *    -> the only provider present, `opencode/big-pickle` (every
     *    `llm.provider=opencode` line in the Phase 8 evidence).
     * Upstream's own desktop client knows this: after `auth.set` it calls
     * `instance.dispose()` (packages/app/src/utils/server-compat.ts) so the
     * next request rebuilds the instance with the credential. This client
     * skipped that step. The fix is the same call, through the public API:
     * `POST /global/dispose` (upstream's "provider list changed" reset), then
     * re-read `GET /provider` - which is what forces the rebuild.
     */
    private fun applyCredentialChange(providerID: String): OpenCodeApi.ProviderSnapshot {
        // A failed dispose is not fatal for the credential write, but it means
        // the running instance may still be stale - say so instead of hiding it.
        runCatching { api.dispose() }.onFailure {
            _state.value = _state.value.copy(notice = "warning: instance reset after $providerID credential change failed: ${it.message}")
        }
        return api.providers()
    }

    /**
     * The hint the composer sends: the user's remembered model when this snapshot
     * still knows its provider, otherwise the Phase 9 rule. [persisted] is passed
     * explicitly by [refresh] (which reads the store once per refresh) and defaults
     * to the store's own value for the other callers.
     */
    private fun defaultModelHint(
        p: OpenCodeApi.ProviderSnapshot,
        prefer: String? = null,
        persisted: OpenCodeApi.ModelRef? = modelPreference?.lastModel(),
    ): OpenCodeApi.ModelRef? = DefaultModelHint.resolve(persisted, p, prefer)

    /**
     * Pin the model the client sends with each prompt. Defaults to OpenCode's
     * own `default` map from GET /provider; this only records the user's choice
     * in the request payload - resolution/validation stays server-side.
     */
    fun setModel(providerID: String, modelID: String) {
        val ref = OpenCodeApi.ModelRef(providerID, modelID)
        // Persist the choice: this is "the model the user last used", and it has to
        // survive the repository being rebuilt for another project (item 3).
        runCatching { modelPreference?.rememberModel(ref) }
        _state.value = _state.value.copy(
            model = ref,
            notice = "model -> $providerID/$modelID",
        )
    }

    /**
     * Remember the model a turn was actually sent with. Covers the implicit case:
     * on a first run the hint comes from the heuristic, and once that model has
     * served a real turn it IS the model the user was using - which is what a new
     * chat should start from.
     */
    private fun rememberUsedModel(ref: OpenCodeApi.ModelRef?) {
        if (ref == null) return
        runCatching { modelPreference?.rememberModel(ref) }
    }

    /**
     * Star/unstar one model. The result is published in [UiState.starredModels], so
     * the chat header's quick switch and the Settings list both re-render from the
     * same value instead of each keeping its own copy of the shortlist.
     */
    fun setStarred(ref: OpenCodeApi.ModelRef, starred: Boolean) {
        // `runCatching` over the nullable store infers Result<List<ModelRef>?>, so the
        // fallback has to be the state we already publish (a Keystore/prefs failure must
        // not wipe the shortlist on screen).
        val list = runCatching { modelPreference?.setStarred(ref, starred) }
            .getOrNull() ?: _state.value.starredModels
        _state.value = _state.value.copy(starredModels = list)
    }

    /** Clear the client-side model hint so the server applies its own default. */
    fun clearModel() {
        _state.value = _state.value.copy(model = null, notice = "model hint cleared (server default)")
    }

    /**
     * Set one permission key's policy (`permission.<key> = ask|allow|deny`)
     * through OpenCode's own config patch, then re-read the config so the
     * settings table reflects what the server actually stored.
     */
    fun setPermissionPolicy(key: String, action: String) {
        scope.launch {
            val patch = JSONObject().put("permission", JSONObject().put(key, action))
            runCatching { api.patchGlobalConfig(patch) }
                .onSuccess {
                    runCatching { api.globalConfig() }.onSuccess { cfg ->
                        _state.value = _state.value.copy(
                            permissionPolicy = parsePermissionPolicy(cfg),
                            notice = "permission $key -> $action",
                        )
                    }
                }
                .onFailure { fail("permission policy", it) }
        }
    }

    /** Drop the last client-side notice/error banner (the user dismissed it). */
    fun clearBanner() {
        _state.value = _state.value.copy(error = "", notice = "")
    }

    // ---- internals ---------------------------------------------------------

    /**
     * Read `permission.<key>` from the global config. Only string-valued keys are
     * surfaced (the settings table edits flat `ask|allow|deny` policies); the
     * upstream object form (per-subtool rules) is left untouched and still wins
     * server-side.
     */
    private fun parsePermissionPolicy(cfg: JSONObject): Map<String, String> {
        val perm = cfg.optJSONObject("permission") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in perm.keys()) {
            val value = perm.opt(key)
            if (value is String) out[key] = value
        }
        return out
    }

    private fun OpenCodeApi.PermissionRequest.asPrompt() = Transcript.Prompt(
        id = id,
        sessionID = sessionID,
        permission = permission,
        patterns = patterns,
        metadata = metadata?.toString() ?: "",
        always = always,
        toolCallID = toolCallID,
    )

    /** Upstream aborts before reverting unless the session is already idle. */
    private suspend fun abortUnlessIdle(sessionID: String) {
        val status = runCatching { api.sessionStatus()[sessionID] }.getOrNull()
        if (status == null || status.type != "idle") {
            runCatching { api.abortSession(sessionID) }
        }
    }

    private suspend fun refreshSessionList(sessionID: String) {
        runCatching { api.listSessions() }.onSuccess { list ->
            _state.value = _state.value.copy(
                sessions = list,
                selectedSession = if (list.any { it.id == sessionID }) sessionID else _state.value.selectedSession,
            )
        }
    }

    /** The last user message upstream would revert to (`messagesBeforeRevert`). */
    private fun lastUserBeforeRevert(sessionID: String): Transcript.Message? {
        val marker = _state.value.sessions.firstOrNull { it.id == sessionID }?.revertMessageID ?: ""
        val all = transcript.snapshot().session(sessionID)?.messages ?: return null
        return all.lastOrNull { it.role == "user" && (marker.isEmpty() || it.id < marker) }
    }

    /** Upstream skips `synthetic` text parts when refilling the composer. */
    private fun userText(m: Transcript.Message): String =
        m.parts.filter { it.type == "text" && !it.synthetic }.joinToString("") { it.text }.trim()

    private fun userFiles(m: Transcript.Message): List<OpenCodeApi.Attachment> =
        m.parts.filter { it.type == "file" && it.url.isNotEmpty() }.map {
            OpenCodeApi.Attachment(
                filename = it.filename.ifEmpty { it.url.substringAfterLast('/') },
                mime = it.mime,
                url = it.url,
                sizeBytes = 0L,
            )
        }

    private fun restoreComposer(m: Transcript.Message) {
        _state.value = _state.value.copy(draft = userText(m), attachments = userFiles(m))
    }

    private fun titleFor(text: String, files: List<OpenCodeApi.Attachment>): String? = when {
        text.isNotBlank() -> text.take(40)
        files.isNotEmpty() -> files.first().filename.take(40)
        else -> null
    }

    private fun setBusy(b: Boolean) {
        _state.value = _state.value.copy(busy = b)
    }

    private fun fail(what: String, t: Throwable) {
        _state.value = _state.value.copy(error = "$what failed: ${t.message}".take(500))
        noteCallFailure(t)
    }

    /**
     * Classify a failed call to the loopback server. A transport failure (-1) means
     * the agent is not answering at all; a 4xx means it answered and refused. Both
     * are kept as raw text too - the headline never replaces the upstream message.
     */
    private fun noteCallFailure(t: Throwable) {
        val status = (t as? OpenCodeApi.ApiException)?.status ?: -1
        _state.value = _state.value.copy(
            errorKind = UiError.classifyServerCall(status, t.message ?: ""),
            serverReachable = status != -1,
        )
    }

    private fun publishTranscript(status: String) {
        val snap = transcript.snapshot()
        val st = _state.value
        val busyFromServer = st.sessionStatus[st.selectedSession]?.busy == true
        _state.value = st.copy(
            transcript = snap,
            busy = snap.busySessions().isNotEmpty() || busyFromServer,
            streamStatus = status,
        )
    }

    companion object {
        /** OpenCode's default coding agent; the shell endpoint requires one. */
        const val SHELL_AGENT = "build"
    }
}
