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
            "session.tool.input.started" -> toolFrame(sid, data, "pending", tool = data.optString("name"), created = created)
            "session.tool.input.ended" -> toolFrame(sid, data, "pending", input = toolInput(data.optString("text")))
            "session.tool.called" -> toolFrame(sid, data, "running", tool = data.optString("name"), input = data.optJSONObject("input"))
            "session.tool.progress" -> toolFrame(sid, data, "running", metadata = data.optJSONObject("metadata"))
            "session.tool.success" -> toolFrame(
                sid,
                data,
                "completed",
                output = V2Mapping.contentText(data.optJSONArray("content")),
                metadata = data.optJSONObject("metadata"),
            )
            "session.tool.failed" -> toolFrame(
                sid,
                data,
                "error",
                output = V2Mapping.contentText(data.optJSONArray("content")),
                metadata = data.optJSONObject("metadata"),
                error = data.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { "The tool failed." },
            )
            "session.shell.started" -> shellFrames(sid, data, created)
            "session.shell.ended" -> shellFrames(sid, data, created)
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

    /**
     * One tool call as a v1 tool part. The id depends only on the message and the call, so the
     * live frames and a reloaded history produce the same part. [status] is the v1 status
     * (pending, running, completed, error); [input] is an object, or the raw text while streaming.
     */
    fun toolPart(
        sid: String,
        mid: String,
        callID: String,
        tool: String,
        status: String,
        input: Any? = null,
        output: String = "",
        metadata: JSONObject? = null,
        error: String = "",
        startedMs: Long = 0L,
    ): JSONObject {
        val state = JSONObject().put("status", status)
        if (input != null) state.put("input", input)
        if (output.isNotEmpty()) state.put("output", output)
        if (metadata != null && metadata.length() > 0) state.put("metadata", metadata)
        if (error.isNotEmpty()) state.put("error", error)
        if (startedMs > 0L) state.put("time", JSONObject().put("start", startedMs))
        return JSONObject()
            .put("id", toolPartID(mid, callID))
            .put("messageID", mid)
            .put("sessionID", sid)
            .put("type", "tool")
            .put("tool", tool)
            .put("callID", callID)
            .put("state", state)
    }

    fun toolPartID(messageID: String, callID: String): String = "$messageID:tool:$callID"

    /** The tool input as an object when it is JSON, otherwise the raw text. */
    fun toolInput(text: String): Any =
        runCatching { JSONObject(text) }.getOrNull() ?: text

    private fun toolFrame(
        sid: String,
        data: JSONObject,
        status: String,
        tool: String = "",
        input: Any? = null,
        output: String = "",
        metadata: JSONObject? = null,
        error: String = "",
        created: Long = 0L,
    ): List<Frame> {
        val mid = data.optString("assistantMessageID")
        val callID = data.optString("id")
        if (mid.isEmpty() || callID.isEmpty()) return emptyList()
        val part = toolPart(sid, mid, callID, tool, status, input, output, metadata, error, created)
        return listOf(Frame("message.part.updated", JSONObject().put("sessionID", sid).put("messageID", mid).put("part", part)))
    }

    /**
     * The message that holds one shell command. A shell is not an assistant turn, so it gets its own
     * message id, derived from the shell id: the live frames and a reloaded history then agree.
     */
    fun shellMessageID(shellID: String): String = "shell:$shellID"

    /**
     * One shell command as a v1 `shell` tool part. [status] is the v2 shell status (running,
     * exited, timeout, killed); [exit] is the exit code once the command has exited.
     */
    fun shellPart(
        sid: String,
        mid: String,
        shellID: String,
        command: String,
        status: String,
        exit: Int?,
        output: String,
        startedMs: Long,
    ): JSONObject {
        val (state, error) = shellState(status, exit)
        return toolPart(
            sid,
            mid,
            shellID,
            "shell",
            state,
            input = JSONObject().put("command", command),
            output = output,
            error = error,
            startedMs = startedMs,
        )
    }

    /** v2 shell status -> the v1 tool status, and the error text for a run that did not succeed. */
    fun shellState(status: String, exit: Int?): Pair<String, String> = when (status) {
        "exited" -> if (exit == null || exit == 0) "completed" to "" else "error" to "exit $exit"
        "timeout" -> "error" to "timed out"
        "killed" -> "error" to "killed"
        else -> "running" to ""
    }

    /**
     * `session.shell.started` and `session.shell.ended` carry the shell's info (and, on end, its
     * output). Both become a message.updated (the shell's own message) and one tool part, so the
     * card starts running and then settles on the same part id.
     */
    private fun shellFrames(sid: String, data: JSONObject, created: Long): List<Frame> {
        val shell = data.optJSONObject("shell") ?: return emptyList()
        val shellID = shell.optString("id")
        if (shellID.isEmpty()) return emptyList()
        val mid = shellMessageID(shellID)
        val time = shell.optJSONObject("time")
        val started = time?.optLong("started", 0L)?.takeIf { it > 0L } ?: created
        val exit = if (shell.has("exit")) shell.optInt("exit") else null
        val output = data.optJSONObject("output")?.optString("output").orEmpty()
        val info = JSONObject()
            .put("id", mid)
            .put("sessionID", sid)
            .put("role", "assistant")
            .put("time", JSONObject().put("created", started).also { t ->
                time?.optLong("completed", 0L)?.takeIf { it > 0L }?.let { t.put("completed", it) }
            })
        val part = shellPart(sid, mid, shellID, shell.optString("command"), shell.optString("status"), exit, output, started)
        return listOf(
            Frame("message.updated", JSONObject().put("info", info)),
            Frame("message.part.updated", JSONObject().put("sessionID", sid).put("messageID", mid).put("part", part)),
        )
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
