package ai.opencode.android.client.v2

import org.json.JSONObject

/**
 * Translates OpenCode v2 stream events into the v1 frames the app's transcript
 * already understands (`Transcript.apply(type, props)`), so the chat UI stays
 * unchanged while the server moves to v2 (migration phase 4, slice 1).
 *
 * A v2 event envelope is `{id, created, type, data, location?, metadata?}`. Only
 * `data` carries the payload fields used here (`sessionID`, `assistantMessageID`,
 * `ordinal`, `delta`, `text`, `error`). Schemas: `packages/schema/src/session-event.ts`
 * in OpenCode v2.2.5.
 *
 * Scope: live text and reasoning, turn status, errors, permission asks. Tools,
 * forms and MCP events are later slices of phase 4
 * (see docs/progress/v2-full-migration-plan.md).
 */
object V2Translator {

    /** One v1 frame: event name and its properties object. */
    data class Frame(val type: String, val props: JSONObject)

    /**
     * Translate one v2 event. [created] is the envelope's `created` (epoch ms); it is
     * used for the message timestamp because `data` does not carry it. Unknown or
     * incomplete events produce no frames.
     */
    fun translate(type: String, data: JSONObject, created: Long = 0L): List<Frame> {
        // Form events nest the session under `form`; every other event carries it at the top.
        val sid = data.optString("sessionID").ifEmpty { data.optJSONObject("form")?.optString("sessionID").orEmpty() }
        // Every event this slice handles is scoped to a session; without one, drop it.
        if (sid.isEmpty()) return emptyList()
        return when (type) {
            "session.execution.started" -> listOf(status(sid, "busy"))
            "session.execution.succeeded", "session.execution.interrupted" ->
                listOf(status(sid, "idle"), Frame("session.idle", JSONObject().put("sessionID", sid)))
            "session.execution.failed" -> failure(sid, data.optJSONObject("error"))
            "session.step.failed" -> failure(sid, data.optJSONObject("error"))
            "session.step.started" -> stepStarted(sid, data, created)
            "session.text.started" -> partStarted(sid, data, "text")
            "session.reasoning.started" -> partStarted(sid, data, "reasoning")
            "session.text.delta" -> partDelta(sid, data, "text")
            "session.reasoning.delta" -> partDelta(sid, data, "reasoning")
            "session.text.ended" -> partEnded(sid, data, "text")
            "session.reasoning.ended" -> partEnded(sid, data, "reasoning")
            "session.idle" -> listOf(Frame("session.idle", JSONObject().put("sessionID", sid)))
            "permission.asked" -> listOf(Frame("permission.asked", V2Mapping.permissionRequest(data)))
            "form.created" -> data.optJSONObject("form")?.let { V2Mapping.formRequest(it) }
                ?.let { listOf(Frame("question.asked", it)) } ?: emptyList()
            "form.replied" -> listOf(answered("question.replied", sid, data))
            "form.cancelled" -> listOf(answered("question.rejected", sid, data))
            "permission.replied" -> listOf(
                Frame(
                    "permission.replied",
                    JSONObject()
                        .put("sessionID", sid)
                        .put("requestID", data.optString("requestID"))
                        .put("reply", data.optString("reply")),
                ),
            )
            else -> emptyList()
        }
    }

    /**
     * Parse one raw `/api/event` SSE payload (`{id, created, type, data}`) and translate it.
     * Malformed JSON or a missing type yields no frames instead of an exception.
     */
    fun translateEnvelope(raw: String): List<Frame> {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val type = envelope.optString("type")
        if (type.isEmpty()) return emptyList()
        return translate(type, envelope.optJSONObject("data") ?: JSONObject(), envelope.optLong("created", 0L))
    }

    /** The part id is stable across started, delta and ended frames of the same text. */
    fun partID(messageID: String, kind: String, ordinal: Int): String = "$messageID:$kind:$ordinal"

    private fun answered(type: String, sid: String, data: JSONObject): Frame =
        Frame(type, JSONObject().put("sessionID", sid).put("requestID", data.optString("id")))

    private fun status(sid: String, type: String): Frame =
        Frame(
            "session.status",
            JSONObject().put("sessionID", sid).put("status", JSONObject().put("type", type)),
        )

    private fun failure(sid: String, error: JSONObject?): List<Frame> {
        val message = error?.optString("message").orEmpty().ifEmpty { "The turn failed." }
        val name = error?.optString("type").orEmpty().ifEmpty { "UnknownError" }
        val err = JSONObject().put("name", name).put("message", message)
        error?.optInt("status", 0)?.takeIf { it != 0 }?.let { err.put("statusCode", it) }
        return listOf(
            Frame("session.error", JSONObject().put("sessionID", sid).put("error", err)),
            status(sid, "idle"),
        )
    }

    private fun stepStarted(sid: String, data: JSONObject, created: Long): List<Frame> {
        val mid = data.optString("assistantMessageID")
        if (mid.isEmpty()) return emptyList()
        val info = JSONObject()
            .put("id", mid)
            .put("sessionID", sid)
            .put("role", "assistant")
            .put("time", JSONObject().put("created", created))
        return listOf(Frame("message.updated", JSONObject().put("info", info)))
    }

    private fun partStarted(sid: String, data: JSONObject, kind: String): List<Frame> {
        val mid = data.optString("assistantMessageID")
        if (mid.isEmpty()) return emptyList()
        val part = JSONObject()
            .put("id", partID(mid, kind, data.optInt("ordinal")))
            .put("messageID", mid)
            .put("sessionID", sid)
            .put("type", kind)
            .put("text", "")
        return listOf(Frame("message.part.updated", JSONObject().put("sessionID", sid).put("messageID", mid).put("part", part)))
    }

    private fun partDelta(sid: String, data: JSONObject, kind: String): List<Frame> {
        val mid = data.optString("assistantMessageID")
        val delta = data.optString("delta")
        if (mid.isEmpty() || delta.isEmpty()) return emptyList()
        val props = JSONObject()
            .put("sessionID", sid)
            .put("messageID", mid)
            .put("partID", partID(mid, kind, data.optInt("ordinal")))
            .put("field", "text")
            .put("delta", delta)
        return listOf(Frame("message.part.delta", props))
    }

    private fun partEnded(sid: String, data: JSONObject, kind: String): List<Frame> {
        val mid = data.optString("assistantMessageID")
        if (mid.isEmpty()) return emptyList()
        val part = JSONObject()
            .put("id", partID(mid, kind, data.optInt("ordinal")))
            .put("messageID", mid)
            .put("sessionID", sid)
            .put("type", kind)
            .put("text", data.optString("text"))
        return listOf(Frame("message.part.updated", JSONObject().put("sessionID", sid).put("messageID", mid).put("part", part)))
    }
}
