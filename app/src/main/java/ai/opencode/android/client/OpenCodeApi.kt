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
    ) : Exception(message ?: cause?.message ?: "OpenCode API call failed ($status)", cause)

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
     */
    fun promptAsync(sessionID: String, text: String, model: ModelRef? = null, agent: String? = null): Int {
        val parts = org.json.JSONArray().put(
            org.json.JSONObject().put("type", "text").put("text", text),
        )
        val body = org.json.JSONObject().put("parts", parts)
        if (model != null) {
            body.put(
                "model",
                org.json.JSONObject().put("providerID", model.providerID).put("modelID", model.modelID),
            )
        }
        if (agent != null) body.put("agent", agent)
        return request("POST", "/session/$sessionID/prompt_async", body.toString()).status
    }

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
        return ProviderSnapshot(allIds = ids, connected = con, defaultModel = defaults)
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

    data class ModelRef(val providerID: String, val modelID: String)

    data class SessionInfo(val id: String, val title: String, val directory: String, val updatedAt: Long) {
        companion object {
            fun from(o: org.json.JSONObject) = SessionInfo(
                id = o.optString("id"),
                title = o.optString("title"),
                directory = o.optString("directory"),
                updatedAt = o.optJSONObject("time")?.optLong("updated") ?: 0L,
            )
        }
    }

    data class MessageInfo(val id: String, val role: String, val parts: List<org.json.JSONObject>) {
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
    ) {
        companion object {
            fun from(o: org.json.JSONObject): PermissionRequest {
                val arr = o.optJSONArray("patterns") ?: org.json.JSONArray()
                val p = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) p.add(arr.optString(i))
                return PermissionRequest(
                    id = o.optString("id"),
                    sessionID = o.optString("sessionID"),
                    permission = o.optString("permission"),
                    patterns = p,
                    metadata = o.optJSONObject("metadata"),
                )
            }
        }
    }

    data class ProviderSnapshot(
        val allIds: List<String>,
        val connected: List<String>,
        val defaultModel: Map<String, String>,
    )

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
