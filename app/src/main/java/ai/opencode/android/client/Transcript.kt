package ai.opencode.android.client

import org.json.JSONObject

/**
 * Pure reducer for OpenCode's transcript events.
 *
 * The Android client never reimplements the agent: the server owns messages,
 * parts, tool state and permissions, and the client just mirrors what the
 * stream says. This class is the mirror - no Android, no coroutines, so the
 * mapping is covered by JVM unit tests with real captured frames.
 *
 * Phase 6 additions (all of them mirror upstream, none of them interpret it):
 *  * `message.part.delta` - the server publishes ONLY deltas while a text or
 *    reasoning part streams (`session/processor.ts` calls `updatePartDelta` per
 *    chunk and `updatePart` at start and end), so a client that ignores deltas
 *    shows no reply at all until the part finishes. Deltas are appended to a part
 *    that already exists; the authoritative `message.part.updated` at `text-end`
 *    then overwrites the whole part. A delta for an unknown part is dropped,
 *    exactly as upstream's own CLI reducer does (`if (!kind) return`), so a
 *    client that joins mid-turn cannot invent a part of the wrong type.
 *  * `session.error` and an assistant message's own `error` field - upstream's
 *    `AssistantError` union (`ProviderAuthError`, `APIError`, `MessageAbortedError`,
 *    ...). Kept verbatim (name + message + statusCode + isRetryable) and handed to
 *    [UiError] for classification; the text itself is never rewritten.
 *  * `session.status` of type `retry` - upstream already retries and says so
 *    (`{attempt, message, next}`); the UI shows that instead of a bare spinner.
 *  * tool `state.metadata` (diffs, exit codes, truncation) - see [ToolMeta].
 *  * `question.asked` / `question.replied` - OpenCode's question tool blocks a
 *    turn the same way a permission ask does, so it is mirrored here too.
 */
class Transcript {

    data class Part(
        val id: String,
        val messageID: String,
        val sessionID: String,
        val type: String,
        val text: String,
        val tool: String,
        val callID: String,
        val status: String,
        val input: String,
        val output: String,
        val title: String,
        /** Raw `state.metadata` JSON: diffs, exit codes, truncation. Parsed by [ToolMetaParser]. */
        val metadata: String = "",
        /** `state.error` when a tool part failed. */
        val error: String = "",
        val timeStart: Long = 0L,
        val timeEnd: Long = 0L,
        /**
         * Upstream's `FilePart` fields (`mime`, `filename`, `url`). Kept because
         * the TUI's own undo restores a reverted prompt's file parts into the
         * composer, and because a chat row has to say what a file part is.
         */
        val mime: String = "",
        val filename: String = "",
        val url: String = "",
        /**
         * Upstream marks parts it generated itself (`synthetic`) - the TUI's undo
         * skips those when refilling the composer, so the flag is mirrored.
         */
        val synthetic: Boolean = false,
    )

    /** Upstream's `AssistantError`, kept as the server reported it. */
    data class TurnError(
        val name: String,
        val message: String,
        val statusCode: Int = 0,
        val retryable: Boolean = false,
        val atMs: Long = 0L,
    ) {
        val kind: AgentAvailability get() = UiError.classifyTurnError(name, message, statusCode, retryable)
        val isBlank: Boolean get() = name.isEmpty() && message.isEmpty()
    }

    /** Upstream's `SessionStatus` retry variant: the server is retrying and says when. */
    data class RetryInfo(val attempt: Int, val message: String, val nextMs: Long)

    data class Message(
        val id: String,
        val sessionID: String,
        val role: String,
        val parts: List<Part>,
        val completed: Boolean,
        val error: TurnError? = null,
        val providerID: String = "",
        val modelID: String = "",
        val agent: String = "",
        val tokensInput: Long = 0L,
        val tokensOutput: Long = 0L,
        val cost: Double = 0.0,
        val createdMs: Long = 0L,
        val completedMs: Long = 0L,
    )

    data class Prompt(
        val id: String,
        val sessionID: String,
        val permission: String,
        val patterns: List<String>,
        val metadata: String,
        /** Upstream's `always`: the patterns an "always" reply would cover. */
        val always: List<String> = emptyList(),
        val toolCallID: String = "",
    )

    data class QuestionOption(val label: String, val description: String)

    data class QuestionItem(
        val header: String,
        val question: String,
        val options: List<QuestionOption>,
        val multiple: Boolean,
        val custom: Boolean,
    )

    data class Question(val id: String, val sessionID: String, val items: List<QuestionItem>)

    /** Ordered message ids per session (insertion order = server order). */
    private val order = LinkedHashMap<String, MutableList<String>>()
    private val messages = HashMap<String, Message>()
    private val sessionOrder = mutableListOf<String>()

    /** Sessions with an in-flight turn, per `session.status`/`session.idle`. */
    private val busy = HashMap<String, Boolean>()
    /** Pending permission asks by session (server also exposes GET /permission). */
    private val prompts = LinkedHashMap<String, Prompt>()
    /** Pending questions by id (server also exposes GET /question). */
    private val questions = LinkedHashMap<String, Question>()
    /** Last error the server reported for a session (`session.error` / message error). */
    private val errors = HashMap<String, TurnError>()
    /** Active upstream retry state per session (`session.status` type=retry). */
    private val retries = HashMap<String, RetryInfo>()

    /** True when the last apply() changed something the UI renders. */
    var dirty: Boolean = false
        private set

    fun snapshot(): Snapshot = Snapshot(
        sessions = sessionOrder.map { sid ->
            SessionView(
                sessionID = sid,
                busy = busy[sid] == true,
                messages = order[sid]?.mapNotNull { messages[it] } ?: emptyList(),
                pending = prompts.values.filter { it.sessionID == sid },
                questions = questions.values.filter { it.sessionID == sid },
                error = errors[sid],
                retry = retries[sid],
            )
        },
    )

    data class SessionView(
        val sessionID: String,
        val busy: Boolean,
        val messages: List<Message>,
        val pending: List<Prompt>,
        val questions: List<Question> = emptyList(),
        val error: TurnError? = null,
        val retry: RetryInfo? = null,
    )

    data class Snapshot(val sessions: List<SessionView>) {
        fun pendingTotal(): Int = sessions.sumOf { it.pending.size }
        fun questionTotal(): Int = sessions.sumOf { it.questions.size }
        fun busySessions(): List<String> = sessions.filter { it.busy }.map { it.sessionID }
        fun session(id: String): SessionView? = sessions.firstOrNull { it.sessionID == id }
    }

    /** Replace everything with a server snapshot (GET /session/:id/message). */
    fun loadMessages(sessionID: String, list: List<OpenCodeApi.MessageInfo>) {
        touch(sessionID)
        val ids = ArrayList<String>()
        var lastError: TurnError? = null
        for (m in list) {
            if (m.id.isEmpty()) continue
            val parts = ArrayList<Part>(m.parts.size)
            for (raw in m.parts) parts.add(partOf(sessionID, m.id, raw))
            val info = m.info
            val error = info?.optJSONObject("error")?.let { turnErrorOf(it) }
            if (error != null && !error.isBlank) lastError = error
            messages[m.id] = Message(
                id = m.id,
                sessionID = sessionID,
                role = m.role,
                parts = parts,
                completed = info?.optJSONObject("time")?.has("completed") == true,
                error = error,
                providerID = info?.optString("providerID") ?: "",
                modelID = info?.optString("modelID") ?: "",
                agent = info?.optString("agent") ?: "",
                tokensInput = info?.optJSONObject("tokens")?.optLong("input") ?: 0L,
                tokensOutput = info?.optJSONObject("tokens")?.optLong("output") ?: 0L,
                cost = info?.optDouble("cost") ?: 0.0,
                createdMs = info?.optJSONObject("time")?.optLong("created") ?: 0L,
                completedMs = info?.optJSONObject("time")?.optLong("completed") ?: 0L,
            )
            ids.add(m.id)
        }
        order[sessionID] = ids
        // The server's own history is authoritative: whatever error it carries is
        // the one to show, and one it does not carry is gone.
        if (lastError != null) errors[sessionID] = lastError else errors.remove(sessionID)
        dirty = true
    }

    /**
     * Apply one event frame. Unknown event types are ignored on purpose (the
     * server may add events; the client must not crash on them).
     */
    fun apply(type: String, props: JSONObject) {
        when {
            type == "message.part.updated" -> onPartUpdated(props)
            type == "message.part.delta" -> onPartDelta(props)
            type == "message.part.removed" -> onPartRemoved(props)
            type == "message.updated" -> onMessageUpdated(props)
            type == "session.updated" -> onSessionUpdated(props)
            type == "session.deleted" -> onSessionDeleted(props)
            type == "session.error" -> onSessionError(props)
            EventFrame.isSessionStatus(type) -> onSessionStatus(props)
            EventFrame.isSessionIdle(type) -> {
                setBusy(props.optString("sessionID"), false)
                retries.remove(props.optString("sessionID"))
            }
            EventFrame.isPermissionAsked(type) -> onAsked(props)
            EventFrame.isPermissionReplied(type) -> onReplied(props)
            EventFrame.isQuestionAsked(type) -> onQuestionAsked(props)
            EventFrame.isQuestionAnswered(type) -> onQuestionAnswered(props)
            else -> Unit
        }
    }

    // ---- individual event handlers ----------------------------------------

    private fun onSessionStatus(props: JSONObject) {
        val sid = props.optString("sessionID")
        val status = props.optJSONObject("status")
        val type = status?.optString("type") ?: ""
        if (sid.isNotEmpty()) {
            setBusy(sid, type == "busy" || type == "retry")
            if (type == "retry" && status != null) {
                retries[sid] = RetryInfo(
                    attempt = status.optInt("attempt"),
                    message = status.optString("message"),
                    nextMs = status.optLong("next"),
                )
            } else {
                retries.remove(sid)
            }
            // A new turn supersedes the previous turn's error; without this the
            // banner would keep reporting a failure the agent already moved past.
            if (type == "busy") errors.remove(sid)
        }
    }

    private fun setBusy(sid: String, value: Boolean) {
        if (sid.isEmpty()) return
        if (busy[sid] != value) {
            busy[sid] = value
            // The session row has to exist for the spinner to render on it: a
            // session.status/idle frame can legitimately arrive before any part
            // frame (e.g. right after an app restart mid-turn), and previously the
            // busy flag had no session to attach to and was invisible.
            touch(sid)
            dirty = true
        }
    }

    private fun onSessionError(props: JSONObject) {
        val err = props.optJSONObject("error") ?: return
        val te = turnErrorOf(err)
        if (te.isBlank) return
        val sid = props.optString("sessionID")
        if (sid.isNotEmpty()) {
            touch(sid)
            errors[sid] = te
        }
        dirty = true
    }

    private fun turnErrorOf(err: JSONObject): TurnError = TurnError(
        name = err.optString("name"),
        message = err.optString("message"),
        statusCode = err.optInt("statusCode"),
        retryable = err.optBoolean("isRetryable"),
        atMs = System.currentTimeMillis(),
    )

    private fun onPartDelta(props: JSONObject) {
        val partID = props.optString("partID")
        val delta = props.optString("delta")
        if (partID.isEmpty() || delta.isEmpty()) return
        // Upstream only deltas the `text` field of text/reasoning parts.
        val field = props.optString("field")
        if (field.isNotEmpty() && field != "text") return
        val sid = props.optString("sessionID")
        val mid = props.optString("messageID")
        if (sid.isEmpty() || mid.isEmpty()) return
        val existing = messages[mid] ?: return
        val idx = existing.parts.indexOfFirst { it.id == partID }
        if (idx < 0) return
        val parts = ArrayList(existing.parts)
        parts[idx] = parts[idx].copy(text = parts[idx].text + delta)
        messages[mid] = existing.copy(parts = parts)
        touch(sid)
        dirty = true
    }

    private fun onPartUpdated(props: JSONObject) {
        val part = props.optJSONObject("part") ?: return
        val sid = part.optString("sessionID").ifEmpty { props.optString("sessionID") }
        // Upstream's Part carries its own messageID; the legacy bus frame also puts
        // it on the envelope. Accept either, same fallback the sessionID uses, so a
        // frame that names the message nowhere cannot create a phantom message.
        val mid = part.optString("messageID").ifEmpty { props.optString("messageID") }
        if (sid.isEmpty() || mid.isEmpty()) return
        touch(sid)
        val p = partOf(sid, mid, part)
        val existing = messages[mid]
        val parts = ArrayList(existing?.parts ?: emptyList())
        val idx = parts.indexOfFirst { it.id == p.id }
        // A streamed part can arrive complete after its deltas; the server's copy
        // is authoritative, so it replaces whatever the deltas accumulated.
        if (idx >= 0) parts[idx] = p else parts.add(p)
        // A tool part moving to running/completed means the turn is still live.
        messages[mid] = (existing ?: Message(mid, sid, part.optString("role"), emptyList(), false))
            .copy(parts = parts)
        idsFor(sid).addIfAbsent(mid)
        dirty = true
    }

    private fun onPartRemoved(props: JSONObject) {
        val sid = props.optString("sessionID")
        val mid = props.optString("messageID")
        val pid = props.optString("partID")
        val m = messages[mid] ?: return
        messages[mid] = m.copy(parts = m.parts.filterNot { it.id == pid })
        if (sid.isEmpty() || order[sid]?.contains(mid) == true) dirty = true
    }

    private fun onMessageUpdated(props: JSONObject) {
        val info = props.optJSONObject("info") ?: props
        val mid = info.optString("id")
        val sid = info.optString("sessionID")
        if (mid.isEmpty() || sid.isEmpty()) return
        touch(sid)
        val existing = messages[mid]
        val error = info.optJSONObject("error")?.let { turnErrorOf(it) }?.takeIf { !it.isBlank }
        if (error != null) errors[sid] = error
        val time = info.optJSONObject("time")
        val tokens = info.optJSONObject("tokens")
        messages[mid] = Message(
            id = mid,
            sessionID = sid,
            role = info.optString("role").ifEmpty { existing?.role ?: "" },
            parts = existing?.parts ?: emptyList(),
            completed = time?.has("completed") == true,
            error = error ?: existing?.error,
            providerID = info.optString("providerID").ifEmpty { existing?.providerID ?: "" },
            modelID = info.optString("modelID").ifEmpty { existing?.modelID ?: "" },
            agent = info.optString("agent").ifEmpty { existing?.agent ?: "" },
            tokensInput = tokens?.optLong("input") ?: existing?.tokensInput ?: 0L,
            tokensOutput = tokens?.optLong("output") ?: existing?.tokensOutput ?: 0L,
            cost = if (info.has("cost")) info.optDouble("cost") else existing?.cost ?: 0.0,
            createdMs = time?.optLong("created") ?: existing?.createdMs ?: 0L,
            completedMs = time?.optLong("completed") ?: existing?.completedMs ?: 0L,
        )
        idsFor(sid).addIfAbsent(mid)
        dirty = true
    }

    private fun onSessionUpdated(props: JSONObject) {
        val info = props.optJSONObject("info") ?: props
        val sid = info.optString("id")
        if (sid.isNotEmpty()) touch(sid)
    }

    private fun onSessionDeleted(props: JSONObject) {
        val sid = props.optString("sessionID")
        if (sid.isEmpty()) return
        order[sid]?.forEach { messages.remove(it) }
        order.remove(sid)
        sessionOrder.remove(sid)
        busy.remove(sid)
        errors.remove(sid)
        retries.remove(sid)
        prompts.keys.filter { prompts[it]?.sessionID == sid }.forEach { prompts.remove(it) }
        questions.keys.filter { questions[it]?.sessionID == sid }.forEach { questions.remove(it) }
        dirty = true
    }

    private fun onAsked(props: JSONObject) {
        val id = firstString(props, "id", "requestID")
        if (id.isEmpty() || !id.startsWith("per_")) return
        val patterns = stringList(props.optJSONArray("patterns"))
        val always = stringList(props.optJSONArray("always"))
        val sid = props.optString("sessionID")
        prompts[id] = Prompt(
            id = id,
            sessionID = sid,
            permission = props.optString("permission"),
            patterns = patterns,
            metadata = props.optJSONObject("metadata")?.toString() ?: "",
            always = always,
            toolCallID = props.optJSONObject("tool")?.optString("callID") ?: "",
        )
        // Same reason as setBusy: an ask must be visible under its session even when
        // no transcript frame for that session has arrived yet, otherwise the
        // Approvals surface could never show a permission the server is genuinely
        // waiting on (and blocking a turn on).
        if (sid.isNotEmpty()) touch(sid)
        dirty = true
    }

    private fun onReplied(props: JSONObject) {
        val id = firstString(props, "requestID", "id")
        if (id.isEmpty()) return
        if (prompts.remove(id) != null) dirty = true
    }

    private fun onQuestionAsked(props: JSONObject) {
        val id = firstString(props, "id", "requestID")
        if (id.isEmpty() || !id.startsWith("que_")) return
        val sid = props.optString("sessionID")
        val raw = props.optJSONArray("questions")
        val items = ArrayList<QuestionItem>()
        if (raw != null) {
            for (i in 0 until raw.length()) {
                val q = raw.optJSONObject(i) ?: continue
                val opts = ArrayList<QuestionOption>()
                val rawOpts = q.optJSONArray("options")
                if (rawOpts != null) {
                    for (j in 0 until rawOpts.length()) {
                        val o = rawOpts.optJSONObject(j) ?: continue
                        opts.add(QuestionOption(o.optString("label"), o.optString("description")))
                    }
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
        }
        questions[id] = Question(id = id, sessionID = sid, items = items)
        if (sid.isNotEmpty()) touch(sid)
        dirty = true
    }

    private fun onQuestionAnswered(props: JSONObject) {
        val id = firstString(props, "requestID", "id")
        if (id.isEmpty()) return
        if (questions.remove(id) != null) dirty = true
    }

    /** Merge the server's authoritative pending list (GET /permission). */
    fun replacePrompts(serverPrompts: List<Prompt>) {
        prompts.clear()
        serverPrompts.forEach { prompts[it.id] = it }
        dirty = true
    }

    /** Merge the server's authoritative pending questions (GET /question). */
    fun replaceQuestions(serverQuestions: List<Question>) {
        questions.clear()
        serverQuestions.forEach { questions[it.id] = it }
        dirty = true
    }

    fun prompt(id: String): Prompt? = prompts[id]

    fun question(id: String): Question? = questions[id]

    // ---- helpers -----------------------------------------------------------

    private fun stringList(arr: org.json.JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun partOf(sessionID: String, messageID: String, part: JSONObject): Part {
        val state = part.optJSONObject("state")
        val time = state?.optJSONObject("time")
        val metadata = state?.optJSONObject("metadata")
        return Part(
            id = part.optString("id"),
            messageID = messageID,
            sessionID = sessionID,
            type = part.optString("type"),
            text = part.optString("text"),
            tool = part.optString("tool"),
            callID = part.optString("callID"),
            status = state?.optString("status") ?: "",
            input = state?.opt("input")?.toString() ?: "",
            output = state?.opt("output")?.toString()
                ?: metadata?.optString("output") ?: "",
            title = state?.optString("title") ?: part.optString("title"),
            metadata = metadata?.toString() ?: "",
            error = state?.optString("error") ?: "",
            timeStart = time?.optLong("start") ?: 0L,
            timeEnd = time?.optLong("end") ?: 0L,
            mime = part.optString("mime"),
            filename = part.optString("filename"),
            url = part.optString("url"),
            synthetic = part.optBoolean("synthetic"),
        )
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) (o.opt(k) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun touch(sid: String) {
        if (sid.isNotEmpty() && sid !in sessionOrder) sessionOrder.add(sid)
    }

    private fun idsFor(sid: String): MutableList<String> = order.getOrPut(sid) { mutableListOf() }

    private fun MutableList<String>.addIfAbsent(v: String) {
        if (!contains(v)) add(v)
    }
}
