package ai.opencode.android.client.v2

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts OpenCode v2 response bodies into the v1 shapes that `OpenCodeApi`'s
 * parsers already read, so the repository and the UI keep working unchanged
 * during the migration (phase 4, slice 1).
 *
 * Every function here is pure: it takes the decoded `data` of a v2 response and
 * returns JSON in the v1 layout. Field names come from `packages/schema` in
 * OpenCode v2.2.5. Only the fields the app reads are carried over.
 */
object V2Mapping {

    /** `Session.Info` -> the v1 session object read by `SessionInfo.from`. */
    fun session(o: JSONObject): JSONObject {
        val time = o.optJSONObject("time")
        val created = time?.optLong("created") ?: 0L
        val updated = time?.optLong("updated")?.takeIf { it > 0L } ?: created
        return JSONObject()
            .put("id", o.optString("id"))
            .put("title", o.optString("title"))
            .put("directory", o.optJSONObject("location")?.optString("directory").orEmpty())
            .put("projectID", o.optString("projectID"))
            .put("cost", o.optDouble("cost", 0.0))
            .put("time", JSONObject().put("created", created).put("updated", updated))
    }

    /** `GET /api/session/active` `data` (`{sessionID: {type: "running"}}`) -> the v1 status map. */
    fun activeStatus(data: JSONObject): JSONObject {
        val out = JSONObject()
        for (sid in data.keys()) out.put(sid, JSONObject().put("type", "busy"))
        return out
    }

    /**
     * Builds the v1 `GET /provider` document from `GET /api/provider`,
     * `GET /api/model` and `GET /api/model/default`.
     *
     * Only enabled models of enabled providers are listed, and a provider counts as
     * "connected" when it has at least one such model. `default` maps a provider id
     * to the default model id, as v1 did.
     */
    fun providerDocument(providers: JSONArray, models: JSONArray, defaultModel: JSONObject?): JSONObject {
        val names = LinkedHashMap<String, String>()
        val disabled = HashSet<String>()
        for (i in 0 until providers.length()) {
            val p = providers.optJSONObject(i) ?: continue
            val id = p.optString("id")
            if (id.isEmpty()) continue
            names[id] = p.optString("name").ifEmpty { id }
            if (p.optString("activation") == "disabled") disabled.add(id)
        }

        val byProvider = LinkedHashMap<String, JSONObject>()
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            val pid = m.optString("providerID")
            val mid = m.optString("id")
            if (pid.isEmpty() || mid.isEmpty() || pid in disabled || !m.optBoolean("enabled", false)) continue
            val variants = JSONObject()
            m.optJSONArray("variants")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { variants.put(it, JSONObject()) }
                }
            }
            val record = byProvider.getOrPut(pid) { JSONObject() }
            record.put(
                mid,
                JSONObject()
                    .put("id", mid)
                    .put("name", m.optString("name").ifEmpty { mid })
                    .put("status", m.optString("status"))
                    .put("variants", variants),
            )
        }

        val all = JSONArray()
        val connected = JSONArray()
        for ((pid, record) in byProvider) {
            all.put(
                JSONObject()
                    .put("id", pid)
                    .put("name", names[pid] ?: pid)
                    .put("models", record),
            )
            connected.put(pid)
        }

        val defaults = JSONObject()
        val dpid = defaultModel?.optString("providerID").orEmpty()
        val dmid = defaultModel?.optString("id").orEmpty()
        if (dpid.isNotEmpty() && dmid.isNotEmpty()) defaults.put(dpid, dmid)

        return JSONObject()
            .put("all", all)
            .put("connected", connected)
            .put("default", defaults)
    }

    /**
     * One v2 message -> the v1 `{info, parts}` object read by `MessageInfo.from`,
     * or null for message kinds this slice does not render yet.
     *
     * Part ids come from [V2Translator.partID] with the content index as the
     * ordinal, which is also what the stream uses. Streamed and reloaded text
     * therefore share one id.
     */
    fun message(o: JSONObject, sessionID: String): JSONObject? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val created = o.optJSONObject("time")?.optLong("created") ?: 0L
        val type = o.optString("type")
        val role = when (type) {
            "user" -> "user"
            "assistant" -> "assistant"
            else -> return null
        }
        val info = JSONObject()
            .put("id", id)
            .put("sessionID", sessionID)
            .put("role", role)
            .put("time", JSONObject().put("created", created))
        val parts = JSONArray()
        if (type == "user") {
            parts.put(textPart(id, sessionID, "text", 0, o.optString("text")))
        } else {
            val completed = o.optJSONObject("time")?.optLong("completed", 0L) ?: 0L
            if (completed > 0L) info.getJSONObject("time").put("completed", completed)
            o.optJSONObject("model")?.let {
                info.put("model", JSONObject().put("providerID", it.optString("providerID")).put("modelID", it.optString("id")))
            }
            info.put("agent", o.optString("agent"))
            errorOf(o.optJSONObject("error"))?.let { info.put("error", it) }
            val content = o.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> parts.put(textPart(id, sessionID, "text", i, c.optString("text")))
                    "reasoning" -> parts.put(textPart(id, sessionID, "reasoning", i, c.optString("text")))
                    // Tool calls, files and patches are the next slice.
                }
            }
        }
        return JSONObject().put("info", info).put("parts", parts)
    }

    /**
     * A v2 message list, mapped in chronological order. Callers request newest-first
     * (the endpoint's page is capped), so pass [newestFirst] = true to reverse it.
     */
    fun messages(data: JSONArray, sessionID: String, newestFirst: Boolean): JSONArray {
        val mapped = ArrayList<JSONObject>(data.length())
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.let { message(it, sessionID) }?.let { mapped.add(it) }
        }
        if (newestFirst) mapped.reverse()
        val out = JSONArray()
        mapped.forEach { out.put(it) }
        return out
    }

    /** v2 `SessionError.Error` -> v1 `NamedError`-style object read by `turnErrorOf`. */
    fun errorOf(e: JSONObject?): JSONObject? {
        if (e == null) return null
        val message = e.optString("message").ifEmpty { "The turn failed." }
        val data = JSONObject().put("message", message)
        e.optInt("status", 0).takeIf { it != 0 }?.let { data.put("statusCode", it) }
        return JSONObject()
            .put("name", e.optString("type").ifEmpty { "UnknownError" })
            .put("data", data)
    }

    private fun textPart(messageID: String, sessionID: String, kind: String, ordinal: Int, text: String): JSONObject =
        JSONObject()
            .put("id", V2Translator.partID(messageID, kind, ordinal))
            .put("messageID", messageID)
            .put("sessionID", sessionID)
            .put("type", kind)
            .put("text", text)
}
