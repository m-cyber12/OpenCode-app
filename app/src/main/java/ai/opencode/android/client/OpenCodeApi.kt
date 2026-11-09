package ai.opencode.android.client

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import ai.opencode.android.client.v2.V2Mapping

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
        // OpenCode v2 scopes location routes with the deepObject query
        // `location[directory]=<dir>`. The instance-free routes (health, event
        // stream) take no scope. A caller that sets `directory` explicitly (the
        // session list filter) keeps its own value.
        if (dir != null && !q.containsKey("directory") && !q.containsKey("location[directory]") && path !in UNSCOPED_PATHS) {
            q["location[directory]"] = dir
        }
        if (q.isEmpty()) return path
        val suffix = q.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
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
            readTimeout = if (method == "GET" && path.startsWith("/api/event")) Int.MAX_VALUE / 4 else readTimeoutMs
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

    /** GET /api/info — server identity; the body carries `version`. */
    fun health(): org.json.JSONObject =
        org.json.JSONObject(request("GET", "/api/info").body)

    /**
     * POST /api/location/reload — rebuilds every loaded location so config and
     * credential changes take effect (v1 `global.dispose`). Pending permissions
     * and forms are cancelled by the server.
     */
    fun dispose() {
        request("POST", "/api/location/reload")
    }

    // ---- sessions ----------------------------------------------------------

    /** GET /session — the real session list (optionally filtered to roots). */
    fun listSessions(limit: Int = 50, roots: Boolean? = null, directory: String? = null): List<SessionInfo> {
        val q = LinkedHashMap<String, String>()
        q["limit"] = limit.toString()
        // v2 `GET /api/session` filters by a top-level `directory` (SessionsQuery),
        // not by the location deepObject. `roots` has no v2 equivalent and is ignored.
        (directory ?: this.directory)?.let { q["directory"] = it }
        val data = org.json.JSONObject(request("GET", "/api/session", query = q).body).optJSONArray("data")
            ?: org.json.JSONArray()
        return (0 until data.length()).map { SessionInfo.from(V2Mapping.session(data.getJSONObject(it))) }
    }

    /** POST /api/session — create a session at the pinned location (the server owns ids). */
    fun createSession(title: String? = null): SessionInfo {
        val body = org.json.JSONObject()
        if (title != null) body.put("title", title)
        directory?.let { body.put("location", org.json.JSONObject().put("directory", it)) }
        val data = org.json.JSONObject(request("POST", "/api/session", body.toString()).body).getJSONObject("data")
        return SessionInfo.from(V2Mapping.session(data))
    }

    /** GET /api/session/:id */
    fun getSession(sessionID: String): SessionInfo {
        val data = org.json.JSONObject(request("GET", "/api/session/$sessionID").body).getJSONObject("data")
        return SessionInfo.from(V2Mapping.session(data))
    }

    /** POST /api/session/:id/interrupt — stop the running turn. */
    fun abortSession(sessionID: String) {
        request("POST", "/api/session/$sessionID/interrupt", "{}")
    }

    /** DELETE /session/:id — upstream's `session.remove`; the server deletes. */
    fun deleteSession(sessionID: String) {
        request("DELETE", "/api/session/$sessionID")
    }

    /**
     * POST /session/:id/summarize — upstream's `/compact` (v9.19 token saver).
     * The server runs AI compaction over the history: later turns resend the
     * short summary instead of the full transcript. Payload per upstream
     * `SummarizePayload` (httpapi/groups/session.ts): providerID + modelID of
     * the model that writes the summary.
     */
    fun summarizeSession(sessionID: String, providerID: String, modelID: String, auto: Boolean = false) {
        // v2 `POST /api/session/:id/compact` uses the session's own model; the
        // summary model arguments have no v2 field and are not sent.
        request("POST", "/api/session/$sessionID/compact", "{}")
    }

    /** GET /session/:id/message?limit= — history with parts. */
    /**
     * GET /session/:id/todo - the server's own todo list for the session
     * (upstream `session.todo`). It is the authoritative copy the todo bar reads
     * after a turn, so a missed `todo.updated` frame cannot leave the bar on an
     * old step. Malformed rows are skipped, never invented.
     */
    fun todos(sessionID: String): List<TodoItem> {
        // OpenCode v2 has no todo route yet (port map: TBD). An empty list is
        // reported honestly; the todo bar stays hidden until that is ported.
        return emptyList()
    }

    /**
     * GET /api/session/:id/message — the newest [limit] messages (the endpoint caps a
     * page at 200), returned oldest first as v1 `{info, parts}` objects.
     */
    fun messages(sessionID: String, limit: Int = 200): List<MessageInfo> {
        val page = limit.coerceIn(1, 200)
        val body = org.json.JSONObject(
            request(
                "GET",
                "/api/session/$sessionID/message",
                query = mapOf("limit" to page.toString(), "order" to "desc"),
            ).body,
        )
        val data = body.optJSONArray("data") ?: org.json.JSONArray()
        val mapped = V2Mapping.messages(data, sessionID, newestFirst = true)
        return (0 until mapped.length()).map { MessageInfo.from(mapped.getJSONObject(it)) }
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
        variant: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): Int {
        // v2 keeps model, agent and variant on the session, not on each prompt, so
        // they are set first (only when they change for this session).
        if (model != null) syncModel(sessionID, model, variant)
        if (agent != null) syncAgent(sessionID, agent)
        val body = org.json.JSONObject().put("text", text)
        if (attachments.isNotEmpty()) {
            val files = org.json.JSONArray()
            for (a in attachments) {
                val f = org.json.JSONObject().put("uri", a.url)
                if (a.filename.isNotEmpty()) f.put("name", a.filename)
                files.put(f)
            }
            body.put("files", files)
        }
        return request("POST", "/api/session/$sessionID/prompt", body.toString()).status
    }

    /** Last model/variant pushed to each session, so a turn does not re-send an unchanged model. */
    private val syncedModel = HashMap<String, String>()
    private val syncedAgent = HashMap<String, String>()

    /** POST /api/session/:id/model when the model or variant differs from the last one sent. */
    private fun syncModel(sessionID: String, model: ModelRef, variant: String?) {
        val key = "${model.providerID}/${model.bareModelID}/${variant.orEmpty()}"
        if (syncedModel[sessionID] == key) return
        val ref = org.json.JSONObject()
            .put("id", model.bareModelID)
            .put("providerID", model.providerID)
        if (!variant.isNullOrEmpty()) ref.put("variant", variant)
        request("POST", "/api/session/$sessionID/model", org.json.JSONObject().put("model", ref).toString())
        syncedModel[sessionID] = key
    }

    /** POST /api/session/:id/agent when the agent differs from the last one sent. */
    private fun syncAgent(sessionID: String, agent: String) {
        if (syncedAgent[sessionID] == agent) return
        request("POST", "/api/session/$sessionID/agent", org.json.JSONObject().put("agent", agent).toString())
        syncedAgent[sessionID] = agent
    }

    /**
     * POST /session/:id/revert — upstream's own "undo message": stages a revert to
     * [messageID] (restoring the file snapshots it took). `SessionRevert.RevertInput`
     * is `{sessionID, messageID, partID?}` and nothing else, so this is exactly the
     * call the TUI's `session.undo` command makes.
     */
    fun revert(sessionID: String, messageID: String, partID: String? = null): org.json.JSONObject =
        notPorted("revert (v2: stage then commit)")

    /** Clears a staged revert ("redo"). Slice 2: v2 `DELETE /api/session/:id/revert`. */
    fun unrevert(sessionID: String): org.json.JSONObject = notPorted("unrevert")

    /** PATCH /api/session/:id — rename a session (upstream owns titles; we only ask). */
    fun updateSessionTitle(sessionID: String, title: String): SessionInfo {
        val body = org.json.JSONObject().put("title", title).toString()
        val data = org.json.JSONObject(request("PATCH", "/api/session/$sessionID", body).body).getJSONObject("data")
        return SessionInfo.from(V2Mapping.session(data))
    }

    /**
     * Raised by a method whose v2 route is not ported yet (migration phase 4, slice 2).
     * It fails loudly rather than returning an empty answer that looks real.
     */
    private fun notPorted(what: String): Nothing =
        throw ApiException(501, "", "$what is not on OpenCode v2 yet (migration phase 4, slice 2)")

    /**
     * POST /session/:id/shell — run a shell command *through the server's*
     * shell path (recorded as a real tool part on the transcript).
     */
    fun shell(sessionID: String, command: String, agent: String): Int =
        notPorted("shell command (v2: POST /api/session/:id/shell)")

    /** Same call as [shell], returning the response body (diagnostic gates). */
    fun shellOutput(sessionID: String, command: String, agent: String): String =
        notPorted("shell command (v2: POST /api/session/:id/shell)")

    // ---- permissions -------------------------------------------------------

    /** GET /api/permission/request — pending permission asks for this location, as v1-shaped rows. */
    fun pendingPermissions(): List<PermissionRequest> {
        val data = org.json.JSONObject(request("GET", "/api/permission/request").body).optJSONArray("data")
            ?: org.json.JSONArray()
        return (0 until data.length()).map { PermissionRequest.from(V2Mapping.permissionRequest(data.getJSONObject(it))) }
    }

    /**
     * POST /api/session/:id/permission/:requestID/reply — once | always | reject.
     * v2 routes the reply through the owning session, so the session id is required.
     */
    fun replyPermission(sessionID: String, requestID: String, reply: String, message: String? = null) {
        val body = org.json.JSONObject().put("decision", reply)
        if (message != null) body.put("message", message)
        request("POST", "/api/session/$sessionID/permission/$requestID/reply", body.toString())
    }

    // ---- MCP ---------------------------------------------------------------

    /** GET /mcp — name -> {status} for every configured MCP server. */
    fun mcpStatus(): Map<String, String> = mcpEntries().mapValues { it.value.status }

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
        // v2 `GET /api/mcp` -> `{location, data: Mcp.Server[]}`; each server's status is
        // a tagged object `{status, error?}` (connected, pending, disabled, failed, needs_auth).
        val data = org.json.JSONObject(request("GET", "/api/mcp").body).optJSONArray("data")
            ?: org.json.JSONArray()
        val out = LinkedHashMap<String, McpEntry>()
        for (i in 0 until data.length()) {
            val server = data.optJSONObject(i) ?: continue
            val name = server.optString("name")
            val st = server.optJSONObject("status") ?: continue
            if (name.isEmpty()) continue
            out[name] = McpEntry(name, st.optString("status"), st.optString("error"))
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
        // v2 `GET /api/session/active` lists running sessions only (`{type: "running"}`);
        // an idle session is simply absent, which the status map already means.
        val data = org.json.JSONObject(request("GET", "/api/session/active").body).optJSONObject("data")
            ?: org.json.JSONObject()
        val obj = V2Mapping.activeStatus(data)
        val out = LinkedHashMap<String, SessionStatusInfo>()
        for (k in obj.keys()) {
            val o = obj.optJSONObject(k) ?: continue
            out[k] = SessionStatusInfo(type = o.optString("type"))
        }
        return out
    }

    // ---- questions (upstream's question tool blocks a turn like an ask) -----

    /** GET /question — pending question requests (`QuestionV1.Request`). */
    /** GET /api/form — pending forms (v1 questions) for this location. Forms the app cannot answer are left out. */
    fun pendingQuestions(): List<QuestionRequest> {
        val data = org.json.JSONObject(request("GET", "/api/form").body).optJSONArray("data") ?: org.json.JSONArray()
        val out = ArrayList<QuestionRequest>(data.length())
        for (i in 0 until data.length()) {
            val q = data.optJSONObject(i)?.let { V2Mapping.formRequest(it) } ?: continue
            out.add(QuestionRequest.from(q))
        }
        return out
    }

    /**
     * POST /question/:requestID/reply — `{answers: [[label, ...], ...]}`, one array
     * of selected labels per question, in order. Upstream's own shape.
     */
    fun replyQuestion(sessionID: String, requestID: String, answers: List<List<String>>) {
        // The answer needs the form's own field definitions (keys, option values), so the
        // form is read first; the app keeps no copy of it.
        val form = org.json.JSONObject(request("GET", "/api/session/$sessionID/form/$requestID").body)
            .getJSONObject("data")
        val fields = V2Mapping.askableFields(form.optJSONArray("fields") ?: org.json.JSONArray())
            ?: throw ApiException(501, "", "this form needs a web step and cannot be answered in the app")
        val answer = V2Mapping.answerFor(fields, answers)
        request(
            "POST",
            "/api/session/$sessionID/form/$requestID/reply",
            org.json.JSONObject().put("answer", answer).toString(),
        )
    }

    /** Decline to answer (the agent continues). */
    fun rejectQuestion(sessionID: String, requestID: String) {
        request("DELETE", "/api/session/$sessionID/form/$requestID")
    }

    /**
     * POST /mcp — connect an MCP server for this instance with an OpenCode MCP
     * config object (verbatim upstream shape: `type: local|remote`).
     * Note: this endpoint is instance state, it does NOT persist across a
     * restart; durable config goes through [patchGlobalConfig].
     */
    fun addMcp(name: String, config: org.json.JSONObject): org.json.JSONObject {
        // v2 `PUT /api/experimental/mcp/:server` connects the server at once; it replaces
        // a server with the same name. The reply is 204, so the status is read back.
        val body = org.json.JSONObject().put("config", V2Mapping.mcpConfig(config))
        request("PUT", "/api/experimental/mcp/${pathSegment(name)}", body.toString())
        val status = mcpEntries()[name]?.status ?: "unknown"
        return org.json.JSONObject().put("status", status)
    }

    fun connectMcp(name: String) {
        request("POST", "/api/experimental/mcp/${pathSegment(name)}/connect")
    }

    fun disconnectMcp(name: String) {
        request("POST", "/api/experimental/mcp/${pathSegment(name)}/disconnect")
    }

    // ---- providers / credentials -------------------------------------------

    /** GET /provider — `{all, default, connected}` from OpenCode itself. */
    fun providers(): ProviderSnapshot {
        // v2 splits this across three routes; V2Mapping rebuilds the v1 document
        // so the parsing below stays the same.
        val providers = org.json.JSONObject(request("GET", "/api/provider").body).optJSONArray("data")
            ?: org.json.JSONArray()
        val models = org.json.JSONObject(request("GET", "/api/model").body).optJSONArray("data")
            ?: org.json.JSONArray()
        val defaultModel = org.json.JSONObject(request("GET", "/api/model/default").body).optJSONObject("data")
        val obj = V2Mapping.providerDocument(providers, models, defaultModel)
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
                    // Upstream `Model.variants` is a record keyed by variant id
                    // ("low" | "high" | "xhigh" | ...); the settings under each
                    // key are provider-internal, so only the ids are kept - the
                    // ids are what `PromptInput.variant` accepts.
                    val variantsRaw = m.optJSONObject("variants")
                    val variants = ArrayList<String>()
                    if (variantsRaw != null) {
                        for (v in variantsRaw.keys()) variants.add(v)
                    }
                    models.add(
                        ModelEntry(
                            id = m.optString("id").ifEmpty { k },
                            name = m.optString("name").ifEmpty { k },
                            status = m.optString("status"),
                            variants = variants,
                        ),
                    )
                }
            }
            entries.add(
                ProviderEntry(
                    id = pid,
                    name = pr.optString("name").ifEmpty { pid },
                    models = models,
                    source = pr.optString("source"),
                ),
            )
        }
        return ProviderSnapshot(allIds = ids, connected = con, defaultModel = defaults, entries = entries)
    }

    /**
     * PUT /auth/:providerID — OpenCode's own credential store (`auth login`).
     * This is the normal upstream mechanism; the app is only a client of it.
     */
    fun setProviderAuth(providerID: String, apiKey: String, metadata: org.json.JSONObject? = null): Unit =
        notPorted("provider credential (v2: POST /api/credential, slice 2)")

    fun deleteProviderAuth(providerID: String): Boolean =
        notPorted("provider credential removal (slice 2)")

    // ---- config (OpenCode's normal configuration mechanism) ---------------

    /** GET /global/config — the raw global opencode.json(c) document. */
    fun globalConfig(): org.json.JSONObject =
        // Slice 2: v2 `GET /api/config` (its shape differs: `shell`, `subagent`, ...).
        // Until then the policy reads as empty, which the UI shows as "not loaded".
        org.json.JSONObject()

    /**
     * Config writes. Slice 2: v2 `PATCH /api/experimental/config`. Failing here
     * keeps the ask-by-default enforcement honest: it reports that it could not run.
     */
    fun patchGlobalConfig(patch: org.json.JSONObject): org.json.JSONObject =
        notPorted("config write (v2: PATCH /api/experimental/config)")

    // ---- files (read side; the server keeps ownership of writes) ----------

    /** File and directory listing. Slice 2: v2 `GET /api/fs/list`. */
    fun fileList(path: String = ""): List<FileEntry> {
        // v2 `GET /api/fs/list?path=` -> `{location, data: FileSystem.Entry[]}`; entry paths
        // stay relative to the workspace location.
        val query = if (path.isEmpty()) emptyMap() else mapOf("path" to path)
        val data = org.json.JSONObject(request("GET", "/api/fs/list", query = query).body)
            .optJSONArray("data") ?: org.json.JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { FileEntry(path = it.optString("path"), type = it.optString("type")) }
    }

    /** File content as text. v2 `GET /api/fs/read/<relative path>`; the server confines it to the location. */
    fun fileContent(path: String): String {
        val segments = path.split("/").filter { it.isNotEmpty() }.joinToString("/") { pathSegment(it) }
        return request("GET", "/api/fs/read/$segments").body
    }

    /** Percent-encodes one path segment (spaces as %20, not the form-encoding '+'). */
    private fun pathSegment(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

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

    /**
     * One entry of upstream's `Provider.Info.models` record. [variants] are the
     * ids of the model's own reasoning variants (upstream `Model.variants` keys,
     * e.g. "low"/"medium"/"high"/"xhigh"); empty when the model reports none.
     */
    data class ModelEntry(
        val id: String,
        val name: String,
        val status: String,
        val variants: List<String> = emptyList(),
    ) {
        /** Upstream's own `ModelStatus` literals; a blank status is just "listed". */
        val deprecated: Boolean get() = status == "deprecated"
    }

    /**
     * One entry of upstream's `GET /provider` `all` list. [source] is upstream's
     * own `Provider.Info.source` ("env" | "config" | "custom" | "api"): a
     * provider the loaded instance built from a stored API key reports "api";
     * a bare catalog entry reports "custom". That is the observable that tells
     * a stale instance (credential written, table not rebuilt) from a live one.
     */
    data class ProviderEntry(val id: String, val name: String, val models: List<ModelEntry>, val source: String = "")

    data class ProviderSnapshot(
        val allIds: List<String>,
        val connected: List<String>,
        val defaultModel: Map<String, String>,
        val entries: List<ProviderEntry> = emptyList(),
    ) {
        fun modelsOf(providerID: String): List<ModelEntry> =
            entries.firstOrNull { it.id == providerID }?.models ?: emptyList()

        /**
         * The reasoning-variant ids the server lists for [ref]'s model - the
         * exact set `PromptInput.variant` accepts for that model. The model id
         * is matched both verbatim and with the provider prefix stripped
         * (the same normalization [ModelRef.bareModelID] applies when the id
         * is sent), so a default-map ref like "subconscious/tim-..." still
         * finds its catalog row.
         */
        fun variantsOf(ref: ModelRef?): List<String> {
            if (ref == null) return emptyList()
            val bare = ref.bareModelID
            return modelsOf(ref.providerID)
                .firstOrNull { it.id == ref.modelID || it.id == bare }
                ?.variants ?: emptyList()
        }
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
        /**
         * v9.41 (owner item 23): the bundled provider whose models are free.
         * It pins the top of the Providers card with a "Free" pill and owns
         * the "Free Models" group in the Models card and the quick switch.
         */
        const val ZEN_PROVIDER_ID = "opencode"
        /** Routes that must NOT carry a directory (instance resolution). */
        /** Routes that take no location scope: server identity and the event stream. */
        private val UNSCOPED_PATHS = setOf("/api/info", "/api/event")

    }
}
