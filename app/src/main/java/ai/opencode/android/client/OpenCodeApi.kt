package ai.opencode.android.client

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * HTTP client for the local OpenCode server API.
 *
 * This is deliberately a *thin client*: every method maps 1:1 onto an upstream
 * OpenCode HTTP endpoint (paths and bodies taken from the pinned server
 * source — `packages/opencode/src/server`), and no agent logic, session
 * bookkeeping or permission policy is reimplemented here. The Android UI is a
 * consumer of the same API the desktop app and the TUI use.
 *
 * Only loopback base URLs are accepted (see [LoopbackGuard]): the app must talk
 * to the OpenCode server it runs on the device, never to a remote one.
 */
class OpenCodeApi(
    baseUrl: String,
    private val username: String,
    private val password: String,
    /** Workspace directory sent as `?directory=` (how OpenCode scopes an instance). */
    var directory: String? = null,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 40_000,
) {

    /** Raised for any non-2xx response or transport failure. */
    class ApiException(
        val status: Int,
        val body: String,
        message: String,
        cause: Throwable? = null,
    ) : Exception(message.ifEmpty { cause?.message ?: "OpenCode API call failed ($status)" }, cause)

    data class Response(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    val baseUrl: String = LoopbackGuard.checked(baseUrl)

    // ---- generic request plumbing -----------------------------------------

    private fun encodeQuery(path: String, query: Map<String, String>): String {
        val q = LinkedHashMap<String, String>(query)
        val dir = directory
        // Instance-scoped routes need the directory context; global routes
        // tolerate it being absent, so only add it when known.
        if (dir != null && !q.containsKey("directory") && path !in GLOBAL_PATHS) q["directory"] = dir
        if (q.isEmpty()) return path
        val suffix = q.entries.joinToString("&") { (k, v) ->
            k + "=" + URLEncoder.encode(v, "UTF-8")
        }
        return path + (if (path.contains('?')) "&" else "?") + suffix
    }

    /**
     * Perform a request. `bodyJson == null` sends no body. Returns the raw
     * response; callers parse what they need (so a schema change upstream shows
     * up as a parse error here rather than a silently dropped field).
     */
    fun request(method: String, path: String, bodyJson: String? = null, query: Map<String, String> = emptyMap()): Response {
        val full = encodeQuery(path, query)
        val conn = (URL(baseUrl + full).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = if (method == "GET" && path.startsWith("/global/event")) Int.MAX_VALUE / 4 else readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Basic " + authHeader())
            if (bodyJson != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            } else if (method != "GET") {
                // Bodyless POST/PUT/DELETE (abort, dispose, mcp disconnect, DELETE
                // /auth) must still be well-formed HTTP/1.1: declare a zero-length
                // body instead of omitting framing, which is what a browser
                // fetch() with no body does.
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }
        try {
            if (bodyJson != null) {
                conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                throw ApiException(status, body.take(2000), "HTTP $status on $method $full: ${body.take(300)}")
            }
            return Response(status, body)
        } catch (e: ApiException) {
            throw e
        } catch (t: Throwable) {
            throw ApiException(-1, "", "$method $full failed: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            conn.disconnect()
        }
    }

    private fun authHeader(): String =
        Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    // ---- global / lifecycle ------------------------------------------------

    /** GET /global/health — `{healthy, version}`. */
    fun health(): org.json.JSONObject =
        org.json.JSONObject(request("GET", "/global/health").body)

    /** POST /global/dispose — tears down every loaded OpenCode instance. */
    fun dispose() {
        request("POST", "/global/dispose")
    }

    // ---- sessions ----------------------------------------------------------

    /** GET /session — the real session list (optionally filtered to roots). */
    fun listSessions(limit: Int = 50, roots: Boolean? = null): List<SessionInfo> {
        val q = LinkedHashMap<String, String>()
        q["limit"] = limit.toString()
        if (roots != null) q["roots"] = roots.toString()
        val arr = org.json.JSONArray(request("GET", "/session", query = q).body)
        return (0 until arr.length()).map { SessionInfo.from(arr.getJSONObject(it)) }
    }

    /** POST /session — create a session server-side (the server owns ids). */
    fun createSession(title: String? = null): SessionInfo {
        val body = org.json.JSONObject()
        if (title != null) body.put("title", title)
        return SessionInfo.from(org.json.JSONObject(request("POST", "/session", body.toString()).body))
    }

    /** GET /session/:id */
    fun getSession(sessionID: String): SessionInfo =
        SessionInfo.from(org.json.JSONObject(request("GET", "/session/$sessionID").body))

    /** POST /session/:id/abort — interrupt the running turn. */
    fun abortSession(sessionID: String) {
        request("POST", "/session/$sessionID/abort")
    }

    /** DELETE /session/:id — upstream's `session.remove`; the server deletes. */
    fun deleteSession(sessionID: String) {
        request("DELETE", "/session/$sessionID")
    }

    /** GET /session/:id/message?limit= — history with parts. */
    fun messages(sessionID: String, limit: Int = 200): List<MessageInfo> {
        val arr = org.json.JSONArray(
            request("GET", "/session/$sessionID/message", query = mapOf("limit" to limit.toString())).body,
        )
        return (0 until arr.length()).map { MessageInfo.from(arr.getJSONObject(it)) }
    }

    /**
     * POST /session/:id/prompt_async — queue a user turn and return (204).
     * Progress arrives on the SSE stream; this client never runs the loop.
     *
     * [attachments] are upstream's own `FilePartInput` members of the same `parts`
     * array (`{type:"file", mime, filename?, url}`); the server resolves `file:`
     * URLs itself (`session/prompt.ts`, `case "file:"`, which reads with
     * `bypassCwdCheck`). The app never parses or re-encodes the file.
     */
    fun promptAsync(
        sessionID: String,
        text: String,
        model: ModelRef? = null,
        agent: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): Int {
        val parts = org.json.JSONArray()
        if (text.isNotEmpty()) {
            parts.put(org.json.JSONObject().put("type", "text").put("text", text))
        }
        for (a in attachments) {
            val f = org.json.JSONObject().put("type", "file").put("mime", a.mime).put("url", a.url)
            if (a.filename.isNotEmpty()) f.put("filename", a.filename)
            parts.put(f)
        }
        val body = org.json.JSONObject().put("parts", parts)
        if (model != null) {
            body.put(
                "model",
                org.json.JSONObject().put("providerID", model.providerID).put("modelID", model.bareModelID),
            )
        }
        if (agent != null) body.put("agent", agent)
        return request("POST", "/session/$sessionID/prompt_async", body.toString()).status
    }

    /**
     * POST /session/:id/revert — upstream's own "undo message": stages a revert to
     * [messageID] (restoring the file snapshots it took). `SessionRevert.RevertInput`
     * is `{sessionID, messageID, partID?}` and nothing else, so this is exactly the
     * call the TUI's `session.undo` command makes.
     */
    fun revert(sessionID: String, messageID: String, partID: String? = null): org.json.JSONObject {
        val body = org.json.JSONObject().put("messageID", messageID)
        if (partID != null) body.put("partID", partID)
        return org.json.JSONObject(request("POST", "/session/$sessionID/revert", body.toString()).body)
    }

    /** POST /session/:id/unrevert — upstream's "redo": clears a staged revert. */
    fun unrevert(sessionID: String): org.json.JSONObject =
        org.json.JSONObject(request("POST", "/session/$sessionID/unrevert", "{}").body)

    /** PATCH /session/:id — rename a session (upstream owns titles; we only ask). */
    fun updateSessionTitle(sessionID: String, title: String): SessionInfo =
        SessionInfo.from(
            org.json.JSONObject(
                request("PATCH", "/session/$sessionID", org.json.JSONObject().put("title", title).toString()).body,
            ),
        )

    /**
     * POST /session/:id/shell — run a shell command *through the server's*
     * shell path (recorded as a real tool part on the transcript).
     */
    fun shell(sessionID: String, command: String, agent: String): Int {
        // ShellInput (upstream, pinned): { agent, command, model?, messageID? }
        // - nothing else. Sending extra keys would be silently stripped, so the
        // client keeps exactly the documented shape.
        val body = org.json.JSONObject()
            .put("agent", agent)
            .put("command", command)
        return request("POST", "/session/$sessionID/shell", body.toString()).status
    }

    // ---- permissions -------------------------------------------------------

    /** GET /permission — pending permission asks for this instance. */
    fun pendingPermissions(): List<PermissionRequest> {
        val arr = org.json.JSONArray(request("GET", "/permission").body)
        return (0 until arr.length()).map { PermissionRequest.from(arr.getJSONObject(it)) }
    }

    /** POST /permission/:requestID/reply — once | always | reject. */
    fun replyPermission(requestID: String, reply: String, message: String? = null) {
        val body = org.json.JSONObject().put("reply", reply)
        if (message != null) body.put("message", message)
        request("POST", "/permission/$requestID/reply", body.toString())
    }

    // ---- MCP ---------------------------------------------------------------

    /** GET /mcp — name -> {status} for every configured MCP server. */
    fun mcpStatus(): Map<String, String> {
        val obj = org.json.JSONObject(request("GET", "/mcp").body)
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) {
            out[k] = obj.optJSONObject(k)?.optString("status") ?: obj.optString(k)
        }
        return out
    }

    /**
     * GET /mcp with upstream's own status object kept whole.
     *
     * `MCP.Status` (pinned source, `packages/opencode/src/mcp/index.ts`) is a union:
     * `{status:"connected"}`, `{status:"disabled"}`, `{status:"failed", error}`,
     * `{status:"needs_auth"}`, `{status:"needs_client_registration", error}`.
     * [mcpStatus] above flattens that to name -> status and therefore DROPS the
     * `#error` text - which is precisely the field that explains the documented
     * remote-MCP restriction to a user. It is kept because Phase 5's instrumented
     * gate K4 asserts on it; the UI uses this one.
     */
    fun mcpEntries(): Map<String, McpEntry> {
        val obj = org.json.JSONObject(request("GET", "/mcp").body)
        val out = LinkedHashMap<String, McpEntry>()
        for (k in obj.keys()) {
            val o = obj.optJSONObject(k)
            out[k] = McpEntry(
                name = k,
                status = o?.optString("status") ?: obj.optString(k),
                error = o?.optString("error") ?: "",
            )
        }
        return out
    }

    /**
     * GET /session/status — upstream's per-session status map (`{type:"idle"}`,
     * `{type:"busy"}`, `{type:"retry", attempt, message, next}`). Read after a
     * process restart so a turn that is already running is visible immediately
     * instead of only after its next event frame.
     */
    fun sessionStatus(): Map<String, SessionStatusInfo> {
        val obj = org.json.JSONObject(request("GET", "/session/status").body)
        val out = LinkedHashMap<String, SessionStatusInfo>()
        for (k in obj.keys()) {
            val o = obj.optJSONObject(k) ?: continue
            out[k] = SessionStatusInfo(
                type = o.optString("type"),
                attempt = o.optInt("attempt"),
                message = o.optString("message"),
                nextMs = o.optLong("next"),
            )
        }
        return out
    }

    // ---- questions (upstream's question tool blocks a turn like an ask) -----

    /** GET /question — pending question requests (`QuestionV1.Request`). */
    fun pendingQuestions(): List<QuestionRequest> {
        val arr = org.json.JSONArray(request("GET", "/question").body)
        return (0 until arr.length()).map { QuestionRequest.from(arr.getJSONObject(it)) }
    }

    /**
     * POST /question/:requestID/reply — `{answers: [[label, ...], ...]}`, one array
     * of selected labels per question, in order. Upstream's own shape.
     */
    fun replyQuestion(requestID: String, answers: List<List<String>>) {
        val arr = org.json.JSONArray()
        for (a in answers) {
            val one = org.json.JSONArray()
            for (label in a) one.put(label)
            arr.put(one)
        }
        request("POST", "/question/$requestID/reply", org.json.JSONObject().put("answers", arr).toString())
    }

    /** POST /question/:requestID/reject — decline to answer (the agent continues). */
    fun rejectQuestion(requestID: String) {
        request("POST", "/question/$requestID/reject", "{}")
    }

    /**
     * POST /mcp — connect an MCP server for this instance with an OpenCode MCP
     * config object (verbatim upstream shape: `type: local|remote`).
     * Note: this endpoint is instance state, it does NOT persist across a
     * restart; durable config goes through [patchGlobalConfig].
     */
    fun addMcp(name: String, config: org.json.JSONObject): org.json.JSONObject {
        val body = org.json.JSONObject().put("name", name).put("config", config)
        return org.json.JSONObject(request("POST", "/mcp", body.toString()).body)
    }

    fun connectMcp(name: String) {
        request("POST", "/mcp/$name/connect")
    }

    fun disconnectMcp(name: String) {
        request("POST", "/mcp/$name/disconnect")
    }

    // ---- providers / credentials -------------------------------------------

    /** GET /provider — `{all, default, connected}` from OpenCode itself. */
    fun providers(): ProviderSnapshot {
        val obj = org.json.JSONObject(request("GET", "/provider").body)
        val all = obj.optJSONArray("all") ?: org.json.JSONArray()
        val ids = ArrayList<String>(all.length())
        for (i in 0 until all.length()) {
            all.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        val connected = obj.optJSONArray("connected") ?: org.json.JSONArray()
        val con = ArrayList<String>(connected.length())
        for (i in 0 until connected.length()) {
            connected.optString(i).takeIf { it.isNotEmpty() }?.let { con.add(it) }
        }
        val def = obj.optJSONObject("default")
        val defaults = LinkedHashMap<String, String>()
        if (def != null) for (k in def.keys()) defaults[k] = def.optString(k)
        // `Provider.Info` also carries `models: Record<modelID, Model>` with the
        // model's own `name` and `status` ("active" | "alpha" | "beta" |
        // "deprecated"). Kept whole so the model picker lists what the server
        // lists and nothing the app invented.
        val entries = ArrayList<ProviderEntry>(all.length())
        for (i in 0 until all.length()) {
            val pr = all.optJSONObject(i) ?: continue
            val pid = pr.optString("id")
            if (pid.isEmpty()) continue
            val modelsRaw = pr.optJSONObject("models")
            val models = ArrayList<ModelEntry>()
            if (modelsRaw != null) {
                for (k in modelsRaw.keys()) {
                    val m = modelsRaw.optJSONObject(k) ?: continue
                    models.add(
                        ModelEntry(
                            id = m.optString("id").ifEmpty { k },
                            name = m.optString("name").ifEmpty { k },
                            status = m.optString("status"),
                        ),
                    )
                }
            }
            entries.add(ProviderEntry(id = pid, name = pr.optString("name").ifEmpty { pid }, models = models))
        }
        return ProviderSnapshot(allIds = ids, connected = con, defaultModel = defaults, entries = entries)
    }

    /**
     * PUT /auth/:providerID — OpenCode's own credential store (`auth login`).
     * This is the normal upstream mechanism; the app is only a client of it.
     */
    fun setProviderAuth(providerID: String, apiKey: String, metadata: org.json.JSONObject? = null) {
        val body = org.json.JSONObject()
            .put("type", "api")
            .put("key", apiKey)
        if (metadata != null) body.put("metadata", metadata)
        request("PUT", "/auth/$providerID", body.toString())
    }

    fun deleteProviderAuth(providerID: String): Boolean =
        request("DELETE", "/auth/$providerID").body.trim() == "true"

    // ---- config (OpenCode's normal configuration mechanism) ---------------

    /** GET /global/config — the raw global opencode.json(c) document. */
    fun globalConfig(): org.json.JSONObject =
        org.json.JSONObject(request("GET", "/global/config").body)

    /**
     * PATCH /global/config — jsonc-patches the global config file through the
     * server (used for durable MCP + permission policy instead of the app
     * inventing its own settings store).
     */
    fun patchGlobalConfig(patch: org.json.JSONObject): org.json.JSONObject =
        org.json.JSONObject(request("PATCH", "/global/config", patch.toString()).body)

    // ---- files (read side; the server keeps ownership of writes) ----------

    /** GET /file?path= — file/dir listing for the instance directory. */
    fun fileList(path: String = ""): List<FileEntry> {
        val arr = org.json.JSONArray(request("GET", "/file", query = mapOf("path" to path)).body)
        return (0 until arr.length()).map { FileEntry.from(arr.getJSONObject(it)) }
    }

    /** GET /file/content?path= */
    fun fileContent(path: String): String =
        org.json.JSONObject(request("GET", "/file/content", query = mapOf("path" to path)).body)
            .optString("content")

    // ---- models ------------------------------------------------------------

    data class ModelRef(val providerID: String, val modelID: String) {
        /**
         * The id as the prompt body needs it: the two halves, separately.
         *
         * Upstream's GET /provider `default` map - and some `Model.id` values -
         * already carry the provider prefix in the model id, e.g.
         * "subconscious/tim-qwen3.6-27b". Forwarding that verbatim as `modelID`
         * next to `providerID` makes the server resolve
         * "subconscious/subconscious/tim-qwen3.6-27b" and answer
         * ProviderModelNotFoundError ("Did you mean:
         * subconscious/tim-qwen3.6-27b?"), which is exactly how run 34142798659
         * lost both live gates after the default model rotated. Strip only a
         * leading "<providerID>/"; anything else is forwarded untouched, because
         * the app never invents or rewrites a model id of its own.
         */
        val bareModelID: String
            get() =
                if (providerID.isNotEmpty() && modelID.startsWith("$providerID/")) {
                    modelID.substring(providerID.length + 1)
                } else {
                    modelID
                }
    }

    /**
     * A file the user attached to a prompt, staged in app-private storage and handed
     * to the server as an upstream `FilePartInput` (`file:` URL). The app never
     * re-encodes the content: the server reads the path itself.
     */
    data class Attachment(
        val filename: String,
        val mime: String,
        val url: String,
        val sizeBytes: Long,
    )

    /** Upstream's `MCP.Status` union, kept whole (see [mcpEntries]). */
    data class McpEntry(val name: String, val status: String, val error: String) {
        val connected: Boolean get() = status == "connected"
        val needsAction: Boolean get() = status == "needs_auth" || status == "needs_client_registration"
    }

    /** Upstream's `SessionStatus.Info` union flattened (see [sessionStatus]). */
    data class SessionStatusInfo(
        val type: String,
        val attempt: Int = 0,
        val message: String = "",
        val nextMs: Long = 0L,
    ) {
        val busy: Boolean get() = type == "busy" || type == "retry"
    }

    data class SessionInfo(
        val id: String,
        val title: String,
        val directory: String,
        val updatedAt: Long,
        /** Upstream's staged undo (`session.revert.messageID`); empty when none. */
        val revertMessageID: String = "",
        val slug: String = "",
        val projectID: String = "",
        val createdMs: Long = 0L,
        val cost: Double = 0.0,
    ) {
        companion object {
            fun from(o: org.json.JSONObject) = SessionInfo(
                id = o.optString("id"),
                title = o.optString("title"),
                directory = o.optString("directory"),
                updatedAt = o.optJSONObject("time")?.optLong("updated") ?: 0L,
                revertMessageID = o.optJSONObject("revert")?.optString("messageID") ?: "",
                slug = o.optString("slug"),
                projectID = o.optString("projectID"),
                createdMs = o.optJSONObject("time")?.optLong("created") ?: 0L,
                cost = o.optDouble("cost"),
            )
        }
    }

    data class MessageInfo(
        val id: String,
        val role: String,
        val parts: List<org.json.JSONObject>,
        /**
         * The message's own `info` object, kept because it carries the fields the UI
         * must show honestly rather than guess: `error` (upstream's AssistantError),
         * `tokens`, `cost`, `model`, `agent`, `time`.
         */
        val info: org.json.JSONObject? = null,
    ) {
        companion object {
            fun from(o: org.json.JSONObject): MessageInfo {
                val info = o.optJSONObject("info") ?: o
                val parts = o.optJSONArray("parts") ?: org.json.JSONArray()
                val list = ArrayList<org.json.JSONObject>(parts.length())
                for (i in 0 until parts.length()) parts.optJSONObject(i)?.let { list.add(it) }
                return MessageInfo(
                    id = info.optString("id"),
                    role = info.optString("role"),
                    parts = list,
                    info = info,
                )
            }
        }
    }

    data class PermissionRequest(
        val id: String,
        val sessionID: String,
        val permission: String,
        val patterns: List<String>,
        val metadata: org.json.JSONObject?,
        /** Upstream's `always`: what an "always" reply would cover. Shown, not decided here. */
        val always: List<String> = emptyList(),
        val toolCallID: String = "",
    ) {
        companion object {
            fun from(o: org.json.JSONObject): PermissionRequest {
                val arr = o.optJSONArray("patterns") ?: org.json.JSONArray()
                val p = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) p.add(arr.optString(i))
                val alwaysArr = o.optJSONArray("always") ?: org.json.JSONArray()
                val always = ArrayList<String>(alwaysArr.length())
                for (i in 0 until alwaysArr.length()) always.add(alwaysArr.optString(i))
                return PermissionRequest(
                    id = o.optString("id"),
                    sessionID = o.optString("sessionID"),
                    permission = o.optString("permission"),
                    patterns = p,
                    metadata = o.optJSONObject("metadata"),
                    always = always,
                    toolCallID = o.optJSONObject("tool")?.optString("callID") ?: "",
                )
            }
        }
    }

    /** Upstream's `QuestionV1.Request` (the question tool blocks a turn like an ask). */
    data class QuestionOption(val label: String, val description: String)

    data class QuestionItem(
        val header: String,
        val question: String,
        val options: List<QuestionOption>,
        val multiple: Boolean,
        val custom: Boolean,
    )

    data class QuestionRequest(val id: String, val sessionID: String, val items: List<QuestionItem>) {
        companion object {
            fun from(o: org.json.JSONObject): QuestionRequest {
                val raw = o.optJSONArray("questions") ?: org.json.JSONArray()
                val items = ArrayList<QuestionItem>(raw.length())
                for (i in 0 until raw.length()) {
                    val q = raw.optJSONObject(i) ?: continue
                    val optsRaw = q.optJSONArray("options") ?: org.json.JSONArray()
                    val opts = ArrayList<QuestionOption>(optsRaw.length())
                    for (j in 0 until optsRaw.length()) {
                        val opt = optsRaw.optJSONObject(j) ?: continue
                        opts.add(QuestionOption(opt.optString("label"), opt.optString("description")))
                    }
                    items.add(
                        QuestionItem(
                            header = q.optString("header"),
                            question = q.optString("question"),
                            options = opts,
                            multiple = q.optBoolean("multiple"),
                            custom = if (q.has("custom")) q.optBoolean("custom") else true,
                        ),
                    )
                }
                return QuestionRequest(id = o.optString("id"), sessionID = o.optString("sessionID"), items = items)
            }
        }
    }

    /** One entry of upstream's `Provider.Info.models` record. */
    data class ModelEntry(val id: String, val name: String, val status: String) {
        /** Upstream's own `ModelStatus` literals; a blank status is just "listed". */
        val deprecated: Boolean get() = status == "deprecated"
    }

    data class ProviderEntry(val id: String, val name: String, val models: List<ModelEntry>)

    data class ProviderSnapshot(
        val allIds: List<String>,
        val connected: List<String>,
        val defaultModel: Map<String, String>,
        val entries: List<ProviderEntry> = emptyList(),
    ) {
        fun modelsOf(providerID: String): List<ModelEntry> =
            entries.firstOrNull { it.id == providerID }?.models ?: emptyList()
    }

    data class FileEntry(val path: String, val type: String) {
        companion object {
            fun from(o: org.json.JSONObject) = FileEntry(
                path = o.optString("path").ifEmpty { o.optString("name") },
                type = o.optString("type"),
            )
        }
    }

    companion object {
        /** Routes that must NOT carry a directory (instance resolution). */
        private val GLOBAL_PATHS = setOf("/global/health", "/global/dispose", "/global/config", "/global/event")

    }
}
