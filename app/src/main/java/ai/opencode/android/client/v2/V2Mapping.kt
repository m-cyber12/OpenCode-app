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

    /**
     * `Permission.Request` (v2) -> the v1 permission object read by `PermissionRequest.from`
     * and `Transcript.onAsked`. `action` is the permission name, `resources` the patterns,
     * `save` what an "always" reply would cover, and the tool source becomes `tool.callID`.
     */
    fun permissionRequest(o: JSONObject): JSONObject {
        val out = JSONObject()
            .put("id", o.optString("id"))
            .put("sessionID", o.optString("sessionID"))
            .put("permission", o.optString("action"))
            .put("patterns", stringArray(o.optJSONArray("resources")))
            .put("always", stringArray(o.optJSONArray("save")))
        o.optJSONObject("metadata")?.let { out.put("metadata", it) }
        o.optJSONObject("source")?.optString("id")?.takeIf { it.isNotEmpty() }?.let {
            out.put("tool", JSONObject().put("callID", it).put("messageID", o.optJSONObject("source")?.optString("messageID").orEmpty()))
        }
        return out
    }

    /**
     * The fields the app can answer. Hidden fields take their default, so they are not asked.
     * A form with an `external` field (an OAuth or web step) is not offered in the app.
     */
    fun askableFields(fields: JSONArray): List<JSONObject>? {
        val out = ArrayList<JSONObject>()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            when (f.optString("type")) {
                "external" -> return null
                else -> if (!f.optBoolean("hidden", false)) out.add(f)
            }
        }
        return out
    }

    /**
     * `Form.Info` -> the v1 question object read by `QuestionRequest.from`. One form
     * field becomes one question. Yes/no fields become two labelled options, and
     * free-text or number fields become custom answers. Returns null for a form the
     * app cannot answer.
     */
    fun formRequest(form: JSONObject): JSONObject? {
        val fields = askableFields(form.optJSONArray("fields") ?: JSONArray()) ?: return null
        val questions = JSONArray()
        for (f in fields) {
            val type = f.optString("type")
            val options = JSONArray()
            when (type) {
                "boolean" -> {
                    options.put(JSONObject().put("label", "Yes").put("description", ""))
                    options.put(JSONObject().put("label", "No").put("description", ""))
                }
                "string", "multiselect" -> f.optJSONArray("options")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        options.put(
                            JSONObject()
                                .put("label", o.optString("label").ifEmpty { o.optString("value") })
                                .put("description", o.optString("description")),
                        )
                    }
                }
            }
            val hasOptions = options.length() > 0
            val customDefault = !hasOptions
            questions.put(
                JSONObject()
                    .put("header", f.optString("title").ifEmpty { f.optString("key") })
                    .put("question", f.optString("description").ifEmpty { f.optString("title").ifEmpty { f.optString("key") } })
                    .put("options", options)
                    .put("multiple", type == "multiselect")
                    .put("custom", if (f.has("custom")) f.optBoolean("custom") else customDefault),
            )
        }
        return JSONObject()
            .put("id", form.optString("id"))
            .put("sessionID", form.optString("sessionID"))
            .put("questions", questions)
    }

    /**
     * Turns the app's answers (one list of selected labels per visible field, in order)
     * into a v2 `Form.Answer`. Labels map back to option values; `Yes`/`No` map to booleans;
     * numbers are parsed; free text is kept as typed. Empty answers are left out.
     */
    fun answerFor(fields: List<JSONObject>, answers: List<List<String>>): JSONObject {
        val out = JSONObject()
        fields.forEachIndexed { index, f ->
            val labels = answers.getOrElse(index) { emptyList() }.map { it.trim() }.filter { it.isNotEmpty() }
            if (labels.isEmpty()) return@forEachIndexed
            val key = f.optString("key")
            val options = f.optJSONArray("options") ?: JSONArray()
            fun valueOf(label: String): String {
                for (i in 0 until options.length()) {
                    val o = options.optJSONObject(i) ?: continue
                    if (o.optString("label") == label) return o.optString("value")
                }
                return label
            }
            when (f.optString("type")) {
                "multiselect" -> {
                    val arr = JSONArray()
                    labels.forEach { arr.put(valueOf(it)) }
                    out.put(key, arr)
                }
                "boolean" -> out.put(key, labels.first().equals("yes", true) || labels.first().equals("true", true))
                "number" -> labels.first().toDoubleOrNull()?.let { out.put(key, it) }
                "integer" -> labels.first().toIntOrNull()?.let { out.put(key, it) }
                else -> out.put(key, valueOf(labels.first()))
            }
        }
        return out
    }

    private fun stringArray(arr: JSONArray?): JSONArray {
        val out = JSONArray()
        if (arr != null) for (i in 0 until arr.length()) out.put(arr.optString(i))
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
