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
 * Scope of this slice: live text and reasoning, turn status, errors. Tools,
 * permissions, forms, sessions list and history are later slices of phase 4
 * (see docs/progress/v2-full-migration-plan.md).
 */
object V2Translator {

    /** One v1 frame: event name and its properties object. */
    data class Frame(val type: String, val props: JSONObject)

    /** Translate one v2 event. Unknown or incomplete events produce no frames. */
    fun translate(type: String, data: JSONObject): List<Frame> {
        val sid = data.optString("sessionID")
        // Every event this slice handles is scoped to a session; without one, drop it.
        if (sid.isEmpty()) return emptyList()
        return when (type) {
            "session.execution.started" -> listOf(status(sid, "busy"))
            "session.execution.succeeded", "session.execution.interrupted" ->
                listOf(status(sid, "idle"), Frame("session.idle", JSONObject().put("sessionID", sid)))
            "session.execution.failed" -> failure(sid, data.optJSONObject("error"))
            "session.step.failed" -> failure(sid, data.optJSONObject("error"))
            "session.step.started" -> stepStarted(sid, data)
            "session.text.started" -> partStarted(sid, data, "text")
            "session.reasoning.started" -> partStarted(sid, data, "reasoning")
            "session.text.delta" -> partDelta(sid, data, "text")
            "session.reasoning.delta" -> partDelta(sid, data, "reasoning")
            "session.text.ended" -> partEnded(sid, data, "text")
            "session.reasoning.ended" -> partEnded(sid, data, "reasoning")
            "session.idle" -> listOf(Frame("session.idle", JSONObject().put("sessionID", sid)))
            else -> emptyList()
        }
    }

    /** The part id is stable across started, delta and ended frames of the same text. */
    fun partID(messageID: String, kind: String, ordinal: Int): String = "$messageID:$kind:$ordinal"

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

    private fun stepStarted(sid: String, data: JSONObject): List<Frame> {
        val mid = data.optString("assistantMessageID")
        if (mid.isEmpty()) return emptyList()
        val info = JSONObject()
            .put("id", mid)
            .put("sessionID", sid)
            .put("role", "assistant")
            .put("time", JSONObject().put("created", data.optLong("created", 0L)))
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
